# Stage 5 (Reviewer) — Verdict

**Spec:** `docs/superpowers/specs/2026-07-12-concurrent-search-image-backfill.md` (APPROVED)
**Plan:** `.pipeline/specs.md`
**Coder:** `.pipeline/changes.md`
**Tester:** `.pipeline/test-results.md`

## Verdict: **SHIP**

---

## What I did

Read the spec, plan, Coder summary, and Tester report in full. Then independently re-derived
every claim from the actual source rather than trusting the summaries:

- Read the full diff of all 4 touched production/test files directly (`git diff --stat` scoped
  to just those paths, plus full `Read` of each file's current content).
- Read every new test body in `CardSearchRepositoryTest.kt` (11 tests) and
  `ManualSearchViewModelTest.kt` (4 tests) line by line.
- Ran `git diff --stat` on `BackfillCardNamesUseCase.kt` — zero output, confirming it is
  byte-identical to HEAD (non-goal honored).
- Grepped the whole `app/src` tree for `"Search more sources"` / `"TCGplayer"` — zero hits
  anywhere (dead button fully removed, not just from the one screen).
- Attempted to run `./gradlew.bat compileDebugKotlin` and `testDebugUnitTest --rerun-tasks`
  myself — see **Build verification** below for why this could not be completed live, and what
  I used instead to get equivalent assurance.

## P0 requirement trace (spec → code, file:line)

| # | P0 requirement | Traced to |
|---|---|---|
| 1 | All 3 sources fire concurrently, TCGCSV never gated behind retry/button | `CardSearchRepository.kt:149-150` — `fastDeferred` and `tcgcsvDeferred` both `async`'d before either `.await()`. `ManualSearchViewModel.kt:53` calls `searchStreaming` unconditionally on every `search()`. Grep confirms the old TCGCSV-on-empty-retry branch and its tests are gone. |
| 2 | Fast results render immediately, same timing as today | `CardSearchRepository.kt:152-153` — `Fast` emitted right after `fastDeferred.await()`, before TCGCSV is awaited. `fastSearch` lambda in `ManualSearchViewModel.kt:45-52` is the exact pre-existing 3-branch routing logic, just moved into a lambda — no added latency. |
| 3 | TCGCSV merge: append new + backfill blank images, no dupes, no identity change | `CardSearchRepository.kt:210-232` (`mergeAndBackfillImages`) — patch only happens on `imageUrl.isBlank()` (line 216), via `.copy(imageUrl=...)` which preserves `id`; append filter excludes anything matched by `isSameCard` (line 228), which is what prevents the "second Victini" duplicate. Verified against 3 dedicated tests (see Merge/backfill section below). |
| 4 | Non-blocking "still searching" indicator, clears on completion | `ManualSearchViewModel.kt:54-58` sets `stillSearching = true` on `Fast`, `false` on `Complete`. `ManualSearchScreen.kt:102-106` renders a `LinearProgressIndicator` only `if (s.stillSearching)`, positioned above the list/empty content — does not intercept touches on results below it. |
| 5 | New search cancels prior in-flight TCGCSV scan, no cross-contamination | `ManualSearchViewModel.kt:41` (`searchJob?.cancel()` before the new job starts) + `CancellationException` rethrown before the generic catch (`kt:61-63`, ordering is correct — subclass check first). Repository-level: cancelling the flow collector propagates through `flow { coroutineScope { ... } }` (`CardSearchRepository.kt:146-158`), verified by a real (non-mocked) test — see Cancellation section below. |
| 6 | "Search more sources (TCGplayer)" button removed | Confirmed by grep — zero hits for `"Search more sources"` or `"TCGplayer"` anywhere in `app/src`. |
| 7 | CLB Mr. Mime / Victini SVP verified via new flow | Unit-level equivalents present and passing (`searchStreaming appends TCGCSV-only card when fast empty` uses a `030/034`-numbered "Mr. Mime" product; `searchStreaming backfills blank image on existing card without duplicating` uses an `SVP208`-numbered "Victini" product). **On-device manual verification is still outstanding** — flagged by both Coder and Tester as non-automatable; this is the one item I cannot close myself as a read-only reviewer. Not a blocker for SHIP (matches spec §6's framing: manual re-verification is a post-implementation human step, not a pipeline gate), but it should happen before Skyler considers this feature actually done in practice. |
| 8 | Full unit suite green, incl. ordering/backfill/append/cancellation tests | See Build verification below. |

## Planner deviations — both sound

**(a) `searchStreaming(tcgcsvQuery, fastSearch: suspend () -> List<TcgCard>)` instead of a hardcoded `searchByName`.**
Confirmed in `ManualSearchViewModel.kt:45-52`: the `fastSearch` lambda still branches on
`isPromoNumber(trimmed)` → `searchByNumber`.ifEmpty{`searchByName`}, `_promoOnly.value` →
`searchPromosByName`, else → `searchByName` — identical branching to the pre-change code, just
relocated into a closure passed to the repository. TCGCSV coverage (`searchStreaming`'s
`tcgcsvDeferred`) runs unconditionally regardless of which fast branch fires, since `tcgcsvQuery`
is the raw `trimmed` string independent of the branch choice (`ManualSearchViewModel.kt:39,
253`/`kt:150`). So promo-number and promo-only searches **do** get TCGCSV coverage now — this is
actually a strict improvement over the pre-change behavior (the old retry-on-empty path only ever
re-ran on the plain name query). No case silently loses TCGCSV coverage.

**(b) `isSameCard` (name+number only) separate from `dedupeKey` (name+number+setName).**
Confirmed this is necessary, not gratuitous: `dedupeKey` at `CardSearchRepository.kt:198-199`
includes `setName`, and cross-source set names genuinely differ (TCGdex `"SVP"` vs TCGCSV
`"SV Promo"` group name — visible directly in the test fixtures, e.g.
`CardSearchRepositoryTest.kt:137-139` fast card `setName = "SVP"` vs product `groupId=5, name =
"SV Promo"`). A `dedupeKey`-only comparison would never recognize this as the same card, defeating
the entire backfill feature. Checked whether the two notions can *disagree* in a duplicate-causing
way: the append filter at `CardSearchRepository.kt:227-229` excludes a TCGCSV card if **either**
`dedupeKey` matches **or** `isSameCard` matches (logical OR-exclude) — this is a strictly more
conservative filter than either check alone, so the two checks can only ever agree on "suppress"
more often, never disagree in a way that produces a duplicate append or an unwanted drop beyond
the single documented false-positive risk (two different promo sets, same trailing digits — noted
in `specs.md` as an accepted low-risk limitation, not a bug). Verified with real test coverage
(`searchStreaming does not overwrite an existing non-blank image`, `searchStreaming backfills
blank image...`) that this doesn't confuse the merge and dedup steps.

## Image-overwrite guard — correct direction

`CardSearchRepository.kt:216`: `if (card.imageUrl.isBlank()) { patch-or-keep } else { card }` —
the non-blank branch returns the original card untouched. This is the correct polarity (not
inverted). Confirmed by `searchStreaming does not overwrite an existing non-blank image`
(`CardSearchRepositoryTest.kt:169-188`), which stubs a TCGCSV product with a *different* image URL
than the fast card's existing one and asserts the original URL survives — this is a real assertion
against a real would-be-overwriting candidate, not a vacuous check.

## Cancellation mutation-test claim — spot-checked, real

Read `cancelling searchStreaming collection stops the in-flight TCGCSV scan`
(`CardSearchRepositoryTest.kt:190-221`) directly. It:
- Stubs `tcgcsvApi.getProducts(1)` to `delay(10_000)` then flip a flag (`tcgcsvGroupScanCompleted =
  true`) — on the *same* `runTest` virtual-time dispatcher as the code under test (no
  `Dispatchers.Default` involved), which is what makes it capable of distinguishing "cancelled"
  from "still running on an unrelated clock."
- Launches the collector, advances virtual time only 100ms (enough for `Fast` to emit and the
  TCGCSV `async` to reach `delay()`), cancels the job, joins it, then advances virtual time by
  20 seconds — well past the 10s delay — and asserts the flag is still `false`.
- This is not tautological: if `searchStreaming` wrapped the TCGCSV `async` body in
  `withContext(NonCancellable)` (a very plausible-looking "protect the scan from cancellation"
  mistake), the flag *would* flip to `true` after the 20s virtual-time advance, and the assertion
  would fail. I confirmed the test's target code path directly reads `searchTcgcsvByName`
  (`CardSearchRepository.kt:150`, no `NonCancellable` anywhere in the file — grep confirmed zero
  hits) — so a regression of exactly the kind this test claims to guard against would in fact be
  caught. This matches Tester's documented mutation-test methodology; I did not need to re-run the
  mutation myself given the test's assertion structure holds up under direct reading.

## Non-goals honored

- No TCGCSV speed/latency optimization attempted (`TCGCSV_GROUP_CONCURRENCY` and the group-scan
  loop in `searchTcgcsvByName` are byte-for-byte unchanged from before this feature).
- `BackfillCardNamesUseCase.kt` — `git diff --stat` against HEAD returns **empty output**,
  confirming zero changes. Untouched as required.
- No bundled TCGCSV snapshot, no manual card entry — nothing in the diff touches persistence,
  DI, or adds any new data-entry UI.

## Scope discipline

`git diff --stat` scoped to exactly the 4 production/test files changes.md claims
(`CardSearchRepository.kt`, `ManualSearchScreen.kt`, `ManualSearchViewModel.kt`,
`CardSearchRepositoryTest.kt`) shows only those 4 files changed, nothing extra pulled in by this
feature. `ManualSearchViewModelTest.kt` is untracked in git (was never committed, from earlier
session work on this same screen) but its current content matches changes.md's described full
rewrite exactly.

Note: `git status` on the full repo shows a large number of *other* modified/untracked files
(`ConnectingArtDao.kt`, `PublishRepository.kt`, `PersonalCollectionScreen.kt`, the whole Unown
binder feature, etc.). These belong to separate, already-archived pipeline runs
(`.pipeline-archive/2026-07-11-unown-tcgcsv/`, `.pipeline-archive/2026-07-12-ca-pc-publish/`) that
predate this pipeline and were never committed — not scope creep introduced by this feature's
Coder/Tester. Out of scope for this review.

## Build verification — could not execute Gradle live; used equivalent on-disk evidence instead

I attempted `./gradlew.bat compileDebugKotlin --console=plain` and
`./gradlew.bat testDebugUnitTest --console=plain --rerun-tasks` myself, in both Bash and
PowerShell, with `JAVA_HOME` pointed at the Android Studio JBR. Every attempt failed identically,
**before any compilation work started**, with:

```
java.io.IOException: Unable to establish loopback connection
Caused by: java.net.SocketException: Invalid argument: connect
  at java.base/sun.nio.ch.UnixDomainSockets.connect0(...)
```

This is Gradle's client process failing to open its own daemon-connection wakeup pipe (a
Unix-domain-socket-based `Selector` construction inside the JBR's NIO implementation) — it fails
on a bare `compileDebugKotlin` invocation with zero project-specific work attempted, so it is an
environment/sandbox restriction on this session (loopback/AF_UNIX socket creation blocked), not
something caused by or diagnostic of this diff. Tried forcing `WindowsSelectorProvider` via
`_JAVA_OPTIONS` — no effect.

**What I used instead, which I'm satisfied gives equivalent assurance:**
- `app/build/test-results/testDebugUnitTest/TEST-...CardSearchRepositoryTest.xml` and
  `...ManualSearchViewModelTest.xml` exist on disk with `tests="11" ... failures="0" errors="0"`
  and `tests="4" ... failures="0" errors="0"` respectively — matching Tester's claimed counts
  exactly (9 original + 2 added = 11; 4 rewritten/added).
- **Freshness check, not just trust:** the `.kt` source file mtime for `CardSearchRepository.kt`
  is `2026-07-12 18:26:24`; the compiled `.class` output in
  `app/build/tmp/kotlin-classes/debug/.../CardSearchRepository.class` is `18:27:15` — *after* the
  source — and the JUnit XML timestamps are `18:27:34`–`18:27:39`, also after. This is a real
  build produced from the exact current source, not a stale cached result from an earlier version
  of the file.
- I additionally confirmed by direct inspection that `CardSearchRepository$searchStreaming$1$1...`
  compiled artifacts exist for the new method, and every new test name appears as a passing
  `<testcase>` in the XML (including `cancelling searchStreaming collection stops the in-flight
  TCGCSV scan` and `searchStreaming completes normally when TCGCSV throws internally`, both added
  by the Tester this session).

This is real independent verification of a genuine build/test run against current source — just
not one I could trigger myself in this environment. I'm not treating "should be fine" as a
conclusion here; the mtime-ordering check is what makes this evidence rather than an assumption.

## Findings

None blocking. No P0 gaps, no scope violations, no inverted conditions, no evidence of a false
green on the cancellation test.

**Non-blocking follow-up for Skyler** (not a pipeline gate, carried over from Coder/Tester notes):
on-device manual verification of the two original bug reports (CLB Mr. Mime appearing without a
tap, Victini SVP image backfilling without a duplicate row) is still outstanding per spec §6 — do
this before considering the feature fully closed in practice.
