# Stage 2 (Coder) — Changes

Implemented `.pipeline/specs.md` verbatim. No deviations from the plan.

## Files changed

### `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt`
- Added top-level `sealed class SearchProgress` (`Fast`, `Complete`) above `CardSearchRepository`.
- Added imports `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.flow`.
- Added `fun searchStreaming(tcgcsvQuery: String, fastSearch: suspend () -> List<TcgCard>): Flow<SearchProgress>`
  — runs the caller-supplied fast search and `searchTcgcsvByName` concurrently (both `async`'d before
  either `await()`, so TCGCSV starts alongside the fast search, not after it), emits `Fast` then `Complete`.
- Added private helpers next to `mergeResults`/`dedupeKey`: `mergeAndBackfillImages`, `isSameCard`,
  `namesMatch`, `numbersMatch`, `normalizeNumber`. `dedupeKey` reused unchanged; `searchTcgcsvByName`
  reused unchanged as the TCGCSV producer inside `searchStreaming`.

### `app/src/main/java/com/skyler/pokedexbinder/ui/manualsearch/ManualSearchViewModel.kt`
- `SearchState.Results` gained `stillSearching: Boolean = false`.
- Added imports: `SearchProgress`, `CancellationException`, `Job`.
- Added `private var searchJob: Job? = null`.
- Rewrote `search()`: cancels any in-flight `searchJob` before setting `Loading`, builds the
  branch-routed `fastSearch` lambda (promo-number / promo-only / name), collects
  `cardSearchRepository.searchStreaming(trimmed, fastSearch)` mapping `Fast` → `stillSearching = true`
  and `Complete` → `stillSearching = false`. `CancellationException` is rethrown before the generic
  `Exception` catch so a superseded search never renders as an error.
- Collapsed `retry()` to `if (lastQuery.isBlank()) return; search(lastQuery)` — the TCGCSV-on-empty
  branch is gone since TCGCSV now always runs as part of `searchStreaming`.
- `togglePromoFilter()` untouched (already calls `search(lastQuery)`).

### `app/src/main/java/com/skyler/pokedexbinder/ui/manualsearch/ManualSearchScreen.kt`
- Replaced the `is SearchState.Results -> { ... }` branch: wraps the branch content in a `Column`,
  shows a thin `LinearProgressIndicator` above the list/empty area when `s.stillSearching`, and the
  empty-state text now distinguishes "No cards found yet — checking more sources…" (still searching)
  from "No cards found" (done).
- Removed the "Search more sources (TCGplayer)" `OutlinedButton` (dead code per spec — TCGCSV now
  always runs, so there is no "more sources" action left to trigger).
- No new imports needed (`LinearProgressIndicator` already covered by the `material3.*` wildcard).

### `app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt`
- Added imports: `com.skyler.pokedexbinder.data.model.TcgCard`, `kotlinx.coroutines.flow.toList`.
- Kept all 5 existing tests unchanged.
- Added 4 new tests under a `// --- searchStreaming ---` section:
  - `searchStreaming emits Fast before Complete`
  - `searchStreaming backfills blank image on existing card without duplicating`
  - `searchStreaming appends TCGCSV-only card when fast empty`
  - `searchStreaming does not overwrite an existing non-blank image`
- DTO constructor shapes matched the existing passing tests as-is — no field-order issues.

### `app/src/test/java/com/skyler/pokedexbinder/ui/ManualSearchViewModelTest.kt`
- Full rewrite per spec's explicit test-set list:
  - Deleted: `first search does NOT query TCGCSV`, `retry after empty results queries TCGCSV and shows its results`.
  - Replaced: `retry after non-empty results does NOT query TCGCSV` → `new search cancels previous in-flight search`.
  - Rewritten: `retry from Error state re-runs search, not TCGCSV` → `search error then retry re-runs search`.
  - Added: `search shows stillSearching until fast resolves`, `search clears stillSearching on Complete`.
- Final test set (4 tests): `search shows stillSearching until fast resolves`,
  `search clears stillSearching on Complete`, `new search cancels previous in-flight search`,
  `search error then retry re-runs search`.
- Imports updated to `every`/`awaitCancellation`/`flow`/`flowOf` per spec's mocking note
  (`searchStreaming` is non-suspend, stubbed with `every`, not `coEvery`). Dropped the spec-listed
  `kotlinx.coroutines.launch` import — unused in the final test bodies, left out to avoid an
  unused-import warning.

## Self-review (simplification pass)

Reviewed the diff directly against the spec (all 5 files are a small, single-purpose change set —
no repo-wide sweep needed). Findings:
- No reuse issues: `dedupeKey`, `searchTcgcsvByName`, `mergeResults` are reused unchanged exactly as
  the spec directed; `isSameCard` is deliberately distinct from `dedupeKey` (documented rationale in
  specs.md §1.4 — cross-source `setName` values don't match, so `dedupeKey` can't do this job).
- No dead code, no redundant computation, no sequential-where-should-be-concurrent work — the
  `async`-before-`await` ordering that makes TCGCSV start concurrently with the fast search was
  verified line-by-line against the spec's explicit "do not reorder" warning.
- No unnecessary complexity added beyond what the spec's merge/backfill algorithm requires.
- One cleanup: removed the unused `kotlinx.coroutines.launch` import from the rewritten
  `ManualSearchViewModelTest.kt` (listed in the spec's import block but not needed by the final test
  bodies — collecting via `vm.search()` already runs on `viewModelScope`, no bare `launch` needed in
  the tests themselves).

Conclusion: implementation is a faithful, minimal transcription of the approved plan. No further
simplification found.

## Verification — command output

### `./gradlew.bat compileDebugKotlin --console=plain`
```
> Task :app:kspDebugKotlin
> Task :app:compileDebugKotlin

BUILD SUCCESSFUL in 18s
16 actionable tasks: 2 executed, 14 up-to-date
```

### `./gradlew.bat testDebugUnitTest --console=plain`
```
> Task :app:kspDebugUnitTestKotlin
> Task :app:compileDebugUnitTestKotlin
w: ManualSearchViewModelTest.kt:25/30/35 — UnconfinedTestDispatcher/setMain/resetMain need
   @ExperimentalCoroutinesApi opt-in (pre-existing warning pattern, same as before this change;
   not introduced by this diff — the original file had the identical warnings)
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 26s
31 actionable tasks: 10 executed, 21 up-to-date
```

All tests green, including the 4 new `CardSearchRepositoryTest` streaming tests and the 4
rewritten/added `ManualSearchViewModelTest` tests. No compile errors, no test failures.

## Notes for Reviewer

- Manual on-device verification steps (specs.md §6) are not automatable and are left for the
  Reviewer/Skyler: (1) "Mr Mime" search shows CLB Mr. Mime appearing without a tap within ~10s,
  (2) "Victini" SVP image backfills in place without a duplicate row, (3) starting a second search
  mid-scan never lets the first scan's results leak into the second search's list.
- `QuickScanScreen`'s own `retry()` (a different ViewModel) was not touched, per spec §0.
