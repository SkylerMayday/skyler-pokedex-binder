# Spec — Concurrent 3-API Search + TCGCSV Image Backfill (2026-07-12)

**Status:** APPROVED — ready for dev-team-pipeline
**Owner:** Skyler
**Scope:** Android app — `CardSearchRepository`, `ManualSearchViewModel`, `ManualSearchScreen`
(shared by every screen that searches: main Pokédex binder's QuickScan-adjacent manual search,
Unown, Connecting Art, Card History's "Add to Secondary")

## Problem Statement

TCGCSV (fixed this session — was silently blocked by bot protection, now works) is the only
source with certain cards (e.g. CLB Mr. Mime) and, separately, the only source with images for
some cards the other two APIs find but can't show art for (e.g. Victini SVP `svp-208` — TCGdex
has the card, no image; TCGCSV has both). But TCGCSV has no search endpoint — every lookup
means scanning all 217 product groups, ~10-13 seconds even under good conditions. Today it only
runs when the user manually taps "Search more sources" after an empty result. Skyler wants it
to just always be searching in the background, merging in when ready, since the fast two
sources return almost instantly anyway.

## Goals

1. Every search fires all 3 sources (pokemontcg.io, TCGdex, TCGCSV) concurrently. Results from
   the fast two (pokemontcg.io + TCGdex) render as soon as they're ready, same as today.
2. When TCGCSV's scan completes (seconds later), its new cards merge into the already-visible
   result list without disrupting what the user's doing (no jarring reset, no lost scroll
   position, no duplicate entries).
3. Any card the fast two sources found but couldn't provide an image for gets its image
   silently backfilled from a matching TCGCSV product, using data already fetched during the
   same background scan — no extra network round-trip for the backfill specifically.
4. The user has some indication a background search is still running, so a 10+ second silence
   after results already appeared doesn't read as "done, nothing more to see."

## Non-Goals

- **Speeding up TCGCSV itself** (e.g. a curated subset of groups to scan, caching the group
  list across searches) — out of scope this round. If the 10-13s latency proves genuinely
  annoying in practice, that's a future optimization, not blocking this UX change.
- **Removing the empty-state "no cards found yet" messaging** — if pokemontcg.io+TCGdex both
  return nothing, the existing empty state still shows immediately; TCGCSV's slower results (if
  any) still merge in afterward per Goal 2, same mechanism, just starting from zero instead of
  some.
- **Applying this to the on-launch backfill routine** (`BackfillCardNamesUseCase`) — that's a
  different code path (already-assigned cards, not live search) and isn't touched here.
- **Bundled/cached TCGCSV snapshot** — still live-fetch only, per the original TCGCSV spec's
  non-goal, unchanged.
- **Manual card entry** — still explicitly rejected, unchanged from the original spec.

## User Stories

- As Skyler, I search "Mr Mime," see pokemontcg.io/TCGdex's (empty) results immediately, and
  within about 10 seconds CLB Mr. Mime appears in the list without me pressing anything.
- As Skyler, I search "Victini," see the SVP card immediately (from TCGdex, no image), and a few
  seconds later that same card's image just appears — I didn't have to do anything, and it
  didn't turn into a duplicate second "Victini" entry.
- As Skyler, while the background TCGCSV scan is still running, I can see some subtle signal
  (not a blocking spinner) that more results might still come in, so I don't think the search is
  fully finished when it isn't.
- As Skyler, if I start a new search before the previous one's TCGCSV scan finishes, the old
  scan's results never leak into the new search's list.

## Design

### CardSearchRepository — expose a streaming/progressive search

Current `searchByName(name): List<TcgCard>` returns one final list after pokemontcg.io+TCGdex
resolve. Add a new streaming variant that also kicks off TCGCSV concurrently and emits twice:

```kotlin
sealed class SearchProgress {
    data class Fast(val cards: List<TcgCard>) : SearchProgress()
    data class Complete(val cards: List<TcgCard>) : SearchProgress()
}

fun searchByNameStreaming(name: String): Flow<SearchProgress> = flow {
    coroutineScope {
        val fastDeferred = async { searchByName(name) }        // existing pokemontcg.io+TCGdex merge
        val tcgcsvDeferred = async { searchTcgcsvByName(name) } // existing TCGCSV scan, started immediately

        val fast = fastDeferred.await()
        emit(SearchProgress.Fast(fast))

        val tcgcsvResults = tcgcsvDeferred.await()
        val merged = mergeAndBackfillImages(fast, tcgcsvResults)
        emit(SearchProgress.Complete(merged))
    }
}
```

Planner to finalize exact placement/signature — this is illustrative, not literal. Key
requirement: `tcgcsvDeferred` starts at the same time as `fastDeferred` (true concurrency, not
sequenced after), so the 10-13s TCGCSV cost overlaps with the fast search + the user reading
results, rather than adding on top of it.

### Merge + image-backfill logic

New private helper (name TBD by Planner, e.g. `mergeAndBackfillImages`):
- New TCGCSV cards not already present (by `dedupeKey`) → appended to the list (existing
  `mergeResults`-style dedup, reused).
- Existing cards (from pokemontcg.io/TCGdex) with `imageUrl.isBlank()` → if a TCGCSV result
  matches by name+number, replace that card's `imageUrl` with the TCGCSV product's image. This
  is a field patch on an existing list entry, not a new entry — must not duplicate the card or
  change its `id`/`dedupeKey` (site/app card identity stays anchored to the original
  pokemontcg.io/TCGdex id, only the image field is patched in from TCGCSV).
- No match found (card genuinely has no image anywhere) → left as-is, existing "no image"
  placeholder behavior unchanged.

### ManualSearchViewModel — progressive state updates

- `SearchState.Results` needs an added flag or a new state to represent "results shown, still
  searching for more" (e.g. `Results(cards: List<TcgCard>, stillSearching: Boolean = false)`).
- `search(query)` collects the new streaming flow: on `Fast`, emit
  `Results(cards, stillSearching = true)`; on `Complete`, emit
  `Results(cards, stillSearching = false)`.
- **Race guard**: if the user fires a new search before the previous one's `Complete` arrives,
  the previous flow collection must be cancelled (structured concurrency — launching the new
  search in the same `viewModelScope.launch` slot, replacing the prior job, is the existing
  pattern to extend; Planner to confirm exact mechanism, e.g. a `Job` reference cancelled before
  each new `search()` call).
- Remove the existing `retry()`-on-empty-triggers-TCGCSV branch and the "Search more sources
  (TCGplayer)" button entirely — TCGCSV now always runs, this manual path is obsolete. Keep
  `retry()`'s other behavior (re-running a failed search from the Error state) unchanged.

### ManualSearchScreen — subtle in-progress indicator

- When `state is Results && state.stillSearching`, show a small, non-blocking indicator (e.g. a
  thin `LinearProgressIndicator` under the top bar, or a small trailing spinner + "Checking more
  sources…" caption at the bottom of the list) — must NOT block interaction with the already-
  visible results (user can still tap/assign a card while this is showing).
- When new/updated cards arrive on `Complete`, they should appear via normal `LazyColumn` item
  diffing (stable `key = { it.id }` already in place) — no full-list flicker, existing items
  that didn't change shouldn't visibly re-render.
- Remove the "Search more sources (TCGplayer)" `OutlinedButton` from the empty-results branch —
  no longer needed (TCGCSV is already running by the time results would show empty at all,
  Goal 2's "empty → merges in later" story covers this case now).

### Call sites (unaffected in shape, just get the new behavior for free)

`Screen.ConnectingArtSearch`, `Screen.UnownSearch`, `Screen.AddToSecondary` all reuse
`ManualSearchScreen`/`ManualSearchViewModel` unchanged at the call-site level — no changes
needed there, they inherit the new concurrent/progressive behavior automatically.

## Requirements

### P0 — Must have
- [ ] All 3 sources fire concurrently on every search; TCGCSV is never gated behind an explicit
      retry/button press anymore.
- [ ] Fast results (pokemontcg.io+TCGdex) render immediately, unchanged timing from today.
- [ ] TCGCSV results merge in when ready: new cards appended, existing imageless cards get their
      image backfilled in place — no duplicate entries, no identity changes on patched cards.
- [ ] A subtle, non-blocking "still searching" indicator shows while the background scan is in
      flight, and disappears when it completes (or immediately if TCGCSV genuinely found
      nothing new).
- [ ] Starting a new search cancels any in-flight previous search's TCGCSV scan — no
      cross-contamination between searches.
- [ ] "Search more sources (TCGplayer)" button removed (dead code now that this is automatic).
- [ ] CLB Mr. Mime and Victini SVP's image both verified working via this new flow (manual
      re-verification of the two originally-reported bugs, now through the new mechanism).
- [ ] Full unit suite green, including new tests for: progressive state emission order (Fast
      before Complete), image-backfill patching an existing card without duplicating it, new
      TCGCSV-only cards appending correctly, and the new-search-cancels-old-scan race guard.

### P1 — Nice to have
- None identified.

### P2 — Future
- Reducing TCGCSV's 10-13s latency (targeted group subset, caching) if it proves genuinely
  disruptive in practice.

## Success Criteria

Personal project — no metrics theater. Done means: Skyler searches for a card missing from
pokemontcg.io/TCGdex (like CLB Mr. Mime) and sees it appear within the search session without
pressing anything; searches for a card with a TCGCSV-only image (like Victini SVP) and sees the
image appear in place; the app never feels broken/stuck during the background scan — verified
on-device.

## Open Questions

- None blocking. (Planner to finalize the exact Flow/state-shape mechanics and the cancellation
  approach for superseded searches — the illustrative code above is a sketch, not literal.)

## Timeline / Phasing

Single dev-team-pipeline pass: repository streaming search + merge/backfill logic → ViewModel
progressive state handling + cancellation guard → screen UI (progress indicator, button
removal) → full unit tests → manual on-device re-verification of both original bug reports.
