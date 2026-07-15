# Stage 3 (Tester) — Test Results

**Spec:** `docs/superpowers/specs/2026-07-12-concurrent-search-image-backfill.md`
**Plan:** `.pipeline/specs.md`
**Coder's summary:** `.pipeline/changes.md`

## What I did

1. Read the spec, plan, and Coder's changes summary in full.
2. Read the actual production code (`CardSearchRepository.kt`, `ManualSearchViewModel.kt`,
   `ManualSearchScreen.kt`) and the actual test bodies (`CardSearchRepositoryTest.kt`,
   `ManualSearchViewModelTest.kt`) directly — did not trust the Coder's summary of what the tests
   assert.
3. Ran the full unit suite fresh with `--rerun-tasks` (forced, no cache) — green (9 + 4 = 13
   pre-existing tests, all passing) before touching anything.
4. Cross-checked every P0 requirement in the spec against real test assertions (see table below).
5. Found one real gap (item 5 — cancellation) and wrote a genuine repository-level test for it.
   Added a second test for item 7 (TCGCSV-throws-internally) which was covered by code reasoning
   but not directly exercised through `searchStreaming`.
6. **Verified the new cancellation test actually catches a regression**, not just green-by-luck:
   mutated `searchStreaming` to wrap the TCGCSV `async` in `withContext(NonCancellable)` (a
   realistic mistake pattern), reran — test failed with the expected assertion message. Reverted
   the mutation, confirmed production code is byte-identical to the Coder's version (grepped for
   mutation markers — none found), reran the full suite fresh — green again.
7. Ran `git log` on the three touched production files — no prior history to review beyond the
   original "add Manual Search screen" / "add LazyColumn key" commits; no evidence of past
   regressions in this area to cross-check against.

## Gaps found and filled

### Gap: cancellation only tested at the ViewModel/mock boundary, not the real repository

`ManualSearchViewModelTest`'s `new search cancels previous in-flight search` test is a genuine
Job-based cancellation test (uses `awaitCancellation()` + `try/finally` to prove the old flow's
collection actually stopped, not just that final state is correct) — but it only exercises a
**mocked** `searchStreaming` return value. Nothing exercised the real
`CardSearchRepository.searchStreaming`'s `flow { coroutineScope { async {...}; async {...} } }`
structure to confirm that cancelling the *collector* actually cancels the in-flight
`searchTcgcsvByName` scan via structured concurrency, as opposed to relying on that being "obviously
true" from the code shape.

**Filled:** added `cancelling searchStreaming collection stops the in-flight TCGCSV scan` to
`CardSearchRepositoryTest.kt`. Stubs `tcgcsvApi.getProducts` to `delay(10_000)` (virtual time, same
`runTest` dispatcher as the code under test — critical for the test to mean anything), launches a
collector job, lets `Fast` emit, cancels the job, advances virtual time well past the delay, and
asserts the TCGCSV work never completed. **Verified this test is load-bearing**, not a false green:
mutated the production `searchStreaming` to wrap the TCGCSV `async` body in
`withContext(NonCancellable)` (escapes cancellation while staying on the same dispatcher/virtual
clock) — the new test failed as expected. Reverted the mutation; confirmed via diff/grep that
production code is unchanged from the Coder's version.

(Note: my first mutation attempt — moving the TCGCSV `async` onto a detached
`CoroutineScope(SupervisorJob())` — produced a false pass, because that scope runs on
`Dispatchers.Default`, which doesn't share `runTest`'s virtual clock; `delay(10_000)` there is real
wall-clock time that never elapses within the test regardless of cancellation, so the test can't
distinguish "cancelled" from "still running on a different clock." I discarded that mutation and
used `NonCancellable` instead, which stays on the same dispatcher and is a much more realistic
regression to protect against — e.g. someone adding `withContext(NonCancellable)` defensively
around the TCGCSV call to "protect it from search-supersession," which is exactly the kind of
plausible-looking mistake that would silently defeat Goal/P0 item 5.)

### Gap: no test exercising `searchStreaming` end-to-end when TCGCSV throws

`searchTcgcsvByName` already has its own test (`searchTcgcsvByName returns empty and does not throw
on API failure`) proving *it* never throws, and specs.md §1.3 reasons from that fact that
`searchStreaming` can't introduce a new failure mode. That reasoning is correct by inspection (no
new `try/catch` was added around `tcgcsvDeferred`, confirmed by reading the code), but nothing
exercised `searchStreaming` itself with a throwing TCGCSV mock to make the "no new failure mode"
claim empirical rather than inferred.

**Filled:** added `searchStreaming completes normally when TCGCSV throws internally` — stubs
`tcgcsvApi.getGroups()` to throw, asserts `searchStreaming` still emits `Fast` then `Complete`
(with just the fast cards, since TCGCSV degrades to empty), no exception escapes the flow.

## P0 requirement checklist (from the original spec)

| # | P0 requirement | Status | Evidence |
|---|---|---|---|
| 1 | All 3 sources fire concurrently every search; TCGCSV never gated behind explicit retry/button | PASS | `searchStreaming` `async`s `fastSearch` and `searchTcgcsvByName` before either `.await()` (code, `CardSearchRepository.kt:149-150`); "Search more sources" button and its test are gone (grep confirmed zero hits for "Search more sources", "TCGplayer", "does NOT query TCGCSV" anywhere in `app/src`) |
| 2 | Fast results render immediately, same timing as today | PASS | `SearchProgress.Fast` emitted right after `fastDeferred.await()`, before TCGCSV is awaited (code) |
| 3 | TCGCSV merges in when ready: appends new, backfills blank images, no duplicates, no identity changes | PASS | `searchStreaming backfills blank image on existing card without duplicating` (asserts `size==1`, id unchanged, imageUrl patched) + `searchStreaming appends TCGCSV-only card when fast empty` (asserts appended card present) + `searchStreaming does not overwrite an existing non-blank image` (asserts existing image untouched, no duplicate) — all real assertions on list size and field identity, not just "an image appeared somewhere" |
| 4 | Non-blocking "still searching" indicator shows while in flight, disappears on completion (or immediately if nothing new) | PASS | `ManualSearchScreen.kt:102-119` — `LinearProgressIndicator` shown iff `s.stillSearching`; empty-state text distinguishes "checking more sources…" vs "No cards found"; ViewModel sets `stillSearching=true` on `Fast`, `false` on `Complete` (`ManualSearchViewModel.kt:53-60`), covered by `search shows stillSearching until fast resolves` / `search clears stillSearching on Complete` |
| 5 | New search cancels previous in-flight TCGCSV scan — no cross-contamination | PASS (gap filled) | ViewModel-level: `new search cancels previous in-flight search` (real `awaitCancellation`/`try-finally` Job test). Repository-level (added this session): `cancelling searchStreaming collection stops the in-flight TCGCSV scan` — proven load-bearing via mutation test (see above) |
| 6 | "Search more sources (TCGplayer)" button removed (dead code) | PASS | Grepped `app/src` for "Search more sources", "TCGplayer", "OutlinedButton" near the Results branch — zero hits; `ManualSearchScreen.kt`'s `Results` branch has no button, only progress bar + list/empty state |
| 7 | CLB Mr. Mime / Victini SVP image both verified via new flow | MANUAL — not automatable | Per spec §6, left for on-device verification by Skyler/Reviewer. Logic covered by unit tests using the same product shapes (Mr. Mime `030/034`, Victini `SVP208`) |
| 8 | Full unit suite green, including: ordering (Fast before Complete), backfill-without-duplicate, TCGCSV-only append, cancellation race guard | PASS | `searchStreaming emits Fast before Complete` asserts emission count/order/content (would catch reordering or collapsing into one emission, not just final value) — all other sub-items covered per rows above |

### Retry-branch removal (spec's Goal — collapsing dead code)

Confirmed genuinely gone: `ManualSearchViewModel.retry()` is now
`if (lastQuery.isBlank()) return; search(lastQuery)` — no TCGCSV-specific branch (code,
`ManualSearchViewModel.kt:74-77`). Grepped test files for the old test names
(`` `first search does NOT query TCGCSV` ``, `` `retry after empty results queries TCGCSV and shows
its results` ``, `` `retry after non-empty results does NOT query TCGCSV` ``) — zero hits, correctly
deleted per spec §5.2. `QuickScanScreen`'s own unrelated `retry()` untouched (confirmed by grep —
only `AppNavigation.kt` and `QuickScanScreen.kt` reference it, neither touched by this change).

### Item 7 from Stage-3 instructions (searchTcgcsvByName exception isolation)

Confirmed: `searchTcgcsvByName` wraps its entire body in `try { coroutineScope {...} } catch
(e: Exception) { emptyList() }` (code, `CardSearchRepository.kt:98-129`) — unchanged from prior
session's work, reused as-is inside `searchStreaming`'s `tcgcsvDeferred` with no new try/catch
wrapper added around it. Empirically confirmed (not just inferred) by the new
`searchStreaming completes normally when TCGCSV throws internally` test.

### Item 8 from Stage-3 instructions (Compose UI logic, read directly)

`ManualSearchScreen.kt:100-150` — the `Results` branch wraps content in a `Column`; shows
`LinearProgressIndicator` only when `s.stillSearching`; if `s.cards.isEmpty()`, shows "No cards
found yet — checking more sources…" when `s.stillSearching` else "No cards found"; otherwise shows
the `LazyColumn` with `key = { it.id }` unchanged. This is a direct, correct reading of the
`stillSearching` → UI mapping, not inferred from the ViewModel state alone.

## Final test run (fresh, forced, after gap-filling and mutation-revert)

```
./gradlew.bat testDebugUnitTest --console=plain --rerun-tasks
...
BUILD SUCCESSFUL in 1m 7s
31 actionable tasks: 31 executed
```

`CardSearchRepositoryTest`: **11 tests, 0 failures** (9 original + 2 added this session).
`ManualSearchViewModelTest`: **4 tests, 0 failures** (unchanged from Coder).
Full suite: green, no other test class touched or affected.

## Notes for Reviewer

- Only test files were modified this session (`CardSearchRepositoryTest.kt`) — no production code
  changes. Production files (`CardSearchRepository.kt`, `ManualSearchViewModel.kt`,
  `ManualSearchScreen.kt`) are exactly what the Coder produced; confirmed via grep for mutation
  markers left over from my verification step (none found) and a clean full-suite rerun afterward.
- Manual on-device verification (spec §6 — CLB Mr. Mime appearing, Victini SVP image backfilling
  in place, second-search-doesn't-leak) is still outstanding and not automatable; flagged for
  Skyler/Reviewer same as the Coder noted.
- `QuickScanScreen`'s separate `retry()` confirmed untouched.
