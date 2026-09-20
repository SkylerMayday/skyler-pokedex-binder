# Feature Spec: Decouple binder.json upload from Discord/changelog notification

**Status:** Draft
**Target ship:** next session

---

## TL;DR

`PublishRepository.publish()` currently uses one signal (`PublishDiff.hasChanges`, owned-status
transitions only) to gate both "should we upload binder.json" and "should we notify
Discord/changelog." A brand-new *unowned* Personal Collection card produces zero owned-transition
deltas, so it never uploads — even though the public site is supposed to show all cached cards
(grayscale until owned). Add a second, content-level signal (`PublishDiff.contentChanged`) that
gates the upload; keep `hasChanges` gating only Discord/changelog.

---

## Problem

`computeDiff()` (`app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt:443-528`)
only emits a `SlotDelta` when a slot's `owned` flips. A new unowned card appearing in the
Personal Collection cache (e.g. from a routine `refreshAll()`) falls through the `when` block's
final branch — "both unowned ... → no change" — producing no delta. `PublishDiff.hasChanges` is
`deltas.isNotEmpty()` (`publish/model/PublishDiff.kt:18`), and `publish()` early-returns
`PublishResult.NoChanges` when `!diff.hasChanges && !diff.isFirstPublish`
(`PublishRepository.kt:131`). Net effect: binder.json is never re-uploaded for a content-only
change, even though the underlying data genuinely changed and the public site intent (all cached
cards, grayscale if unowned — already correct in-app via `ui/common/DimmableCardImage.kt`) requires
it to be.

Separately, `hasChanges` is the wrong gate for Discord/changelog too, in the other direction: it's
*currently* the only gate for upload, but Skyler wants Discord to fire only on a genuine
owned-card change — that part is already correct today, it just needs to survive the upload-gate
fix without becoming coupled to it.

---

## Proposal

Two independent signals on `PublishDiff`:
- **`contentChanged`** (new) — any structural difference between `baseline.binders` and
  `next.binders`, excluding `publishedAt` (which is regenerated every build and would always
  differ). Gates the binder.json upload.
- **`hasChanges`** (existing, unchanged meaning) — owned-transition deltas only. Gates
  changelog.json + Discord.

All of `BinderSnapshot`/`SnapshotBinder`/`SnapshotSection`/`SnapshotSlot` are Kotlin data classes
(confirmed in `publish/model/BinderSnapshot.kt`), so `next.binders != baseline?.binders` is a free,
correct deep-structural comparison — no manual diffing needed.

### File 1 — `app/src/main/java/com/skyler/pokedexbinder/publish/model/PublishDiff.kt`

```kotlin
data class PublishDiff(
    val deltas: List<SlotDelta>,
    val isFirstPublish: Boolean,     // true when no baseline binder.json existed (404)
    val pokedexComplete: Int,
    val pokedexTotal: Int,
    val contentChanged: Boolean      // true iff next.binders differs from baseline.binders at all
                                      // (structural), independent of owned-transition deltas.
                                      // Gates the binder.json upload. hasChanges (below) still
                                      // gates changelog/Discord — distinct, do not conflate.
) {
    val hasChanges: Boolean get() = deltas.isNotEmpty()
    val added get() = deltas.count { it.type == ChangeType.ADDED }
    val replaced get() = deltas.count { it.type == ChangeType.REPLACED }
    val removed get() = deltas.count { it.type == ChangeType.REMOVED }
}
```

### File 2 — `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt`

**A. `computeDiff()` (ends ~line 528)** — compute `contentChanged` from the same `baseline`/`next`
already in scope, add to the returned `PublishDiff`:

```kotlin
        val contentChanged = next.binders != baseline?.binders   // baseline null → always true

        return PublishDiff(
            deltas = deltas,
            isFirstPublish = isFirstPublish,
            pokedexComplete = pokedexComplete,
            pokedexTotal = POKEDEX_TOTAL,
            contentChanged = contentChanged
        )
```

**B. `publish()` early-return gate (line 131)** — swap the signal:

```kotlin
            if (!diff.contentChanged && !diff.isFirstPublish) {
```

(`isFirstPublish` term is now technically redundant — `contentChanged` is always true when
`baseline == null` — but keep it for readability/symmetry with the existing code; it is a no-op.)

**C. Gate changelog + Discord on `hasChanges`, not on "reached this point."** The
`// a. Upload binder.json` block (lines 140-158) stays **unconditional and unchanged** — re-verify
your diff doesn't wrap it in an `if` too. Restructure lines 160 (`// b. Fetch + update
changelog.json`) through 244 (end of Discord block) as follows — the changelog-fetch body and the
Discord-send body are each moved verbatim inside a new `if (diff.hasChanges)`, only the wrapping
and the position of the `onStep` call change:

```kotlin
            // Discord/changelog fire only on an owned-transition change (diff.hasChanges).
            // A content-only publish (e.g. new unowned Personal Collection cards) still uploads
            // binder.json above but skips both: no empty "0 added/0 removed" changelog entry, no
            // wasted GitHub PUT, no Discord ping for something the user can't act on.
            currentStep = PublishStep.NotifyingDiscord
            onStep(currentStep)   // emitted unconditionally so the UI step never looks skipped

            if (diff.hasChanges) {
                // b. Fetch + update changelog.json — VERBATIM existing body (changelogGetResponse
                // ... newEntry ... updatedChangelog ... changelogPutResponse ... through the
                // `if (!changelogPutResponse.isSuccessful) throw PublishFailedException(...)`
                // check) goes here unchanged, just indented one level.

                // Notify Discord — VERBATIM existing body, MINUS the currentStep/onStep lines
                // (already emitted above, outside this if): i.e. just
                // `if (config.discordWebhookUrl.isNotBlank()) { ... } else { Log.w(...) }`.
            } else {
                Log.i("PublishRepository",
                    "Content-only publish (no owned-card changes) — binder.json updated, changelog/Discord skipped")
            }

            currentStep = PublishStep.Done(diff)
            onStep(currentStep)
            PublishResult.Success(diff, System.currentTimeMillis() - start)
```

Exception steps inside the moved bodies are unchanged (`PublishFailedException(PublishStep.Uploading, ...)`
for changelog PUT failure, `PublishFailedException(PublishStep.NotifyingDiscord, ...)` for Discord
failure) — both already carry their own step explicitly, independent of the `currentStep` var.

### Task breakdown (dependency-ordered)

| # | Task | Size | File | Depends on |
|---|---|---|---|---|
| 1 | Add `contentChanged` field to `PublishDiff` | S | `publish/model/PublishDiff.kt` | — |
| 2 | Compute `contentChanged` in `computeDiff()` | S | `PublishRepository.kt` | 1 |
| 3 | Swap early-return gate to `contentChanged` | S | `PublishRepository.kt:131` | 2 |
| 4 | Wrap changelog+Discord in `if (diff.hasChanges)`, move `onStep(NotifyingDiscord)` before it | M | `PublishRepository.kt:160-244` | 3 |
| 5 | Add/update tests (see Acceptance criteria) | M | `PublishRepositoryTest.kt` | 1-4 |

---

## User stories

- As Skyler, I want the public site to pick up newly-discovered unowned cards from a routine
  Personal Collection refresh, so that grayscale placeholders appear before I own the card, not
  only after.
- As Skyler, I want Discord to stay silent and changelog.json to stay untouched when nothing I
  actually own changed, so that the changelog doesn't fill with empty "0 added" noise and I'm not
  pinged for changes I can't act on.

---

## Acceptance criteria

- [ ] **Given** a baseline binder.json and a new snapshot with a new unowned Personal Collection
      card (present in `next`, absent from `baseline`, `owned=false`), **when** `computeDiff()`
      runs, **then** `contentChanged == true` and `hasChanges == false`.
- [ ] **Given** an owned-transition case (ADDED/REMOVED/REPLACED — existing test matrix at
      `PublishRepositoryTest.kt:266-297`), **when** `computeDiff()` runs, **then** both
      `contentChanged == true` and `hasChanges == true` (add an assertion to each of the 3
      existing tests; no behavior regression).
- [ ] **Given** two snapshots with identical `binders` but different `publishedAt`, **when**
      `computeDiff()` runs, **then** `contentChanged == false` (new test — confirms `publishedAt`
      is correctly excluded).
- [ ] **Given** two snapshots with identical `binders` and identical `publishedAt` (existing
      "both present same card" / "both null" tests), **then** `contentChanged == false` too (add
      assertion to existing tests).
- [ ] **Given** a content-only change at the `publish()` level (new unowned Personal Collection
      card via `personalCollectionRepository.getAllCache()`/`getAllEntries()` mocks), **when**
      `publish()` runs, **then** `gitHubApi.putContent(...binder.json...)` IS called, but
      `gitHubApi.getContent/putContent(...changelog.json...)` and `discordApi.sendWebhook(...)`
      are NOT called (new test).
- [ ] **Given** an owned-transition change with a configured Discord webhook, **when** `publish()`
      runs, **then** binder.json PUT, changelog GET+PUT, and Discord webhook are ALL called (new
      test — no existing test currently asserts all three together with a webhook configured).
- [ ] **Given** `isFirstPublish == true` with zero owned cards in the snapshot (e.g. one unassigned
      Pokédex slot), **when** `publish()` runs, **then** it returns `Success` (binder.json PUT
      called) but changelog GET/PUT and Discord are skipped, and `diff.contentChanged == true`
      while `diff.hasChanges == false` (new test — real edge case, not inferred from others).
- [ ] Existing `` `no changes returns NoChanges without PUT calls or webhook` `` test still passes
      unmodified (baseline and freshly-built snapshot are structurally identical except
      `publishedAt` → `contentChanged` correctly false too).
- [ ] All existing tests in `PublishRepositoryTest.kt` pass unmodified in behavior (only new
      assertions added to a few, no assertions removed or weakened).

---

## Out of scope

- `ui/common/DimmableCardImage.kt` / any Personal Collection UI — grayscale/color treatment
  already correct, confirmed this session.
- `skylermayday-site` (separate repo) — not touched.
- Renaming `hasChanges` or changing its owned-transition semantics.
- Any new dependency.
- Any other `hasChanges` call site — grep confirmed exactly one production usage
  (`PublishRepository.kt:131`); no UI code (`PublishViewModel.kt`, `DiscordEmbedBuilder.kt`)
  references it.

---

## Dependencies

### Required before development can start
- [x] Root cause already confirmed by reading `computeDiff()`/`publish()`/`PublishDiff.kt`/
      `BinderSnapshot.kt` this session — no further investigation needed.

### External systems or services
- GitHub Contents API (`gitHubApi.getContent`/`putContent`) — unchanged, no new calls added, one
  call pair (changelog GET/PUT) now conditionally skipped.
- Discord webhook (`discordApi.sendWebhook`) — unchanged, now conditionally skipped (already was
  conditional on `config.discordWebhookUrl`, now additionally conditional on `diff.hasChanges`).

### Team dependencies
- None — single-file (plus one model file) change, one engineer, no design/content/legal input
  needed.

---

## Open questions

None — Skyler resolved the only real design fork (content-only publish should skip changelog
entirely, not write an empty entry) explicitly this session.

---

## Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `onStep(PublishStep.NotifyingDiscord)` now fires before the changelog GET/PUT (moved earlier so it's unconditional) — UI progress label is briefly "wrong" while changelog network calls run | Low | Low | Explicitly directed by Skyler this session; UI only shows a step label, no functional impact. Flag in PR if UI ever adds per-step timing assumptions. |
| A future new content field added to `SnapshotSlot`/`SnapshotSection` accidentally relies on `hasChanges` semantics instead of `contentChanged` | Low | Med | Both fields' doc-comments in `PublishDiff.kt` now state their distinct purpose inline. |
| Structural equality on `binders` (`List<SnapshotBinder>`) is O(n) per publish but snapshots are at most low-thousands of slots — non-issue at this scale | Low | Low | No action needed; flag only if binder count grows by orders of magnitude. |

---

## Smallest shippable increment

**This spec's full scope IS the smallest shippable increment** — it's a single root-cause fix with
no partial-value intermediate state (adding `contentChanged` without wiring it into the gate ships
nothing; wiring the gate without the changelog/Discord split re-notifies for content-only changes,
which is the thing Skyler explicitly doesn't want). Ship all 5 tasks together.

**Future iterations (NOT in this increment):**
- Surfacing "content-only publish (no owned changes)" as a distinct `PublishStep`/UI state instead
  of just a debug log line, if Skyler wants visible confirmation in the Publish screen.
- Retry/idempotency handling for the changelog-drift edge case already flagged in the existing code
  comment at `PublishRepository.kt:216-220` (pre-existing, unrelated to this change).

---

## Friction Notes

- Serena's Kotlin language server failed to initialize in this environment ("Error extracting
  archive") — fell back to plain Read/Grep for all Kotlin investigation. Not a project-specific
  issue signal, but worth knowing this project's stack currently has no working Serena LSP tier.
- The feature request was already fully root-caused and scoped by the requester (exact line
  numbers, exact gate to change, exact test cases required) — this spec mostly formalized and
  cross-checked an already-correct diagnosis rather than doing new discovery. Grounding still
  required reading all 4 files in full to verify the described behavior matched actual code
  (it did, exactly).
