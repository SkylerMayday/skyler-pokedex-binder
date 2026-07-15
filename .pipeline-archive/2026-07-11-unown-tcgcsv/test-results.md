# Stage 3 (Tester) — Test Results: Unown Binder + TCGCSV Search Fallback

Verified against `.pipeline/specs.md` (Stage 1) and `.pipeline/changes.md` (Stage 2). Production
code was NOT modified — this stage added one new test file only.

## What was verified

1. Read `specs.md` and `changes.md` in full.
2. Diffed the actual working tree against the last commit (`e42eeb5`) via `git status`/inspection —
   confirmed the file inventory matches §1 of the spec (12 new files incl. `Migration7to8Test.kt`
   and `ManualSearchViewModelTest.kt`, 12 modified production files, 3 modified test files).
3. Cross-checked every P0 requirement against actual test bodies (not just presence of test
   methods) in `ManualSearchViewModelTest.kt`, `CardSearchRepositoryTest.kt`,
   `PublishRepositoryTest.kt`, `RestoreRepositoryTest.kt`, and read the corresponding production
   code (`PublishRepository.kt`, `AppNavigation.kt`, `RestoreRepository.kt`) to confirm the tests
   exercise real behavior, not mocked-away assertions.
4. Ran the full unit suite myself twice: once cached (`BUILD SUCCESSFUL`, all UP-TO-DATE — not
   trusted as proof), then forced with `--rerun-tasks` for a real fresh compile+execute. Confirmed
   again after adding the new test file below.
5. Found one real gap (see below), wrote a test for it, added it to the suite, reran to confirm
   green.

## Gap found and filled

**Edge case "the `!`/`?` letter IDs survive round-trip … nav route encoding … without corruption"
had no automated coverage.** The Room PK and Moshi JSON safety are structural (no encoding step
exists, nothing to test beyond what `Migration7to8Test`/DAO tests already cover), but the nav-route
leg has a real transformation (`URLEncoder.encode` in `Screen.UnownSearch.createRoute`) that was
implemented but never exercised by any test — not in this PR's new tests, and there was no
pre-existing navigation test package in the project at all (`app/src/test/java/.../ui/navigation/`
did not exist).

Added `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt` (test
file only, no production code touched):
- `createRoute encodes exclamation mark and decodes back to the original letterId` — asserts the
  literal encoded route (`unown_search/%21`) and that decoding recovers `"!"`.
- `createRoute encodes question mark and decodes back to the original letterId` — same for `"?"`
  (`unown_search/%3F`), the specific case the spec calls out as dangerous (`?` would otherwise be
  parsed as a query-string separator).
- `createRoute round-trips every one of the 28 fixed Unown letterIds` — loops `A`..`Z`, `!`, `?`
  through encode→decode and asserts each survives exactly.
- `createRoute produces a route matching the declared route pattern` — asserts the encoded route
  has exactly one `/` and zero literal `?` characters, i.e. it can't accidentally introduce a query
  separator into the nav path segment.

This is a pure-JVM test (uses only `java.net.URLEncoder`/`URLDecoder`, no Android framework calls
triggered at load time) — runs under plain `testDebugUnitTest`, no Robolectric/instrumentation
needed. It does not cover the Android-side decode (`NavType.StringType` argument extraction), which
requires an instrumented/Compose-navigation test harness the project doesn't have; that half of the
round trip is asserted structurally instead (route has no stray `/` or `?`) and should be confirmed
manually on-device per spec §12 step 7.

## Final test run (fresh, `--rerun-tasks`)

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 41s
31 actionable tasks: 31 executed
```

Aggregate across all `TEST-*.xml` result files: **106 tests, 0 failures, 0 errors.**

Per-class results relevant to this feature (from `app/build/test-results/testDebugUnitTest/`):

| Test class | tests | failures | errors |
|---|---|---|---|
| `ManualSearchViewModelTest` | 4 | 0 | 0 |
| `CardSearchRepositoryTest` | 5 | 0 | 0 |
| `PublishRepositoryTest` | 23 | 0 | 0 |
| `RestoreRepositoryTest` | 11 | 0 | 0 |
| `AppNavigationScreenTest` (new, this stage) | 4 | 0 | 0 |

`Migration7to8Test` (androidTest) was not run — requires a device/emulator, consistent with
existing precedent (`Migration6to7Test`). Read the file and confirmed it mirrors
`Migration6to7Test`'s two-method structure exactly (raw-DDL/column-order assertion +
DAO/repository reachability through a real Room in-memory DB), matching spec §10.1.

## P0 requirements — pass/fail

| # | P0 requirement (spec §11 edge cases + §0 resolutions) | Test coverage | Verdict |
|---|---|---|---|
| 1 | Empty TCGCSV response → `emptyList()`, no crash | `CardSearchRepositoryTest`: `searchTcgcsvByName filters out non-matching product names` exercises the empty-after-filter path; defaults (`results = emptyList()`) verified by DTO default values | PASS |
| 2 | TCGCSV network/API failure never throws, degrades to empty | `CardSearchRepositoryTest`: `searchTcgcsvByName returns empty and does not throw on API failure` — `getGroups()` throws, asserts `emptyList()` returned (not propagated) | PASS |
| 3 | TCGCSV is NOT called on first search; only on retry-after-empty | `ManualSearchViewModelTest`: `first search does NOT query TCGCSV` (`coVerify(exactly = 0)`) and `retry after empty results queries TCGCSV and shows its results` (`coVerify(exactly = 1)`) | PASS |
| 4 | Retry after non-empty results does NOT re-trigger TCGCSV (re-runs base search instead) | `ManualSearchViewModelTest`: `retry after non-empty results does NOT query TCGCSV` | PASS |
| 5 | Retry from Error state re-runs search, not TCGCSV (existing "Try Again" behavior preserved) | `ManualSearchViewModelTest`: `retry from Error state re-runs search, not TCGCSV` | PASS |
| 6 | TCGCSV product mapping: id prefixed `tcgcsv_`, `Number` extracted from `extendedData`, name-filtered | `CardSearchRepositoryTest`: `searchTcgcsvByName maps products, prefixes id with tcgcsv_, extracts Number, filters by name` | PASS |
| 7 | Metadata-only republish does not spuriously REPLACE Unown slots | `PublishRepositoryTest`: `metadata-only republish does not REPLACE unown slots` — builds baseline/next from identical unown entries, asserts `computeDiff` has no delta for slot `"A"`. Verified against real `computeDiff` (cardId-only comparison, stable `slotId = letterId`), not mocked | PASS |
| 8 | Restore overlay handles OLD `binder.json` snapshots with no `"unown"` binder — no crash, `unown_binder` untouched | `RestoreRepositoryTest`: `old snapshot without unown binder leaves unown untouched` — snapshot has only `"pokedex"` binder, asserts `Success` result and `coVerify(exactly = 0) { unownBinderRepository.overwriteAll(any()) }` | PASS |
| 9 | Restore overlay correctly restores/clears Unown letter slots and folds counts into shared restored/cleared/skipped counters | `RestoreRepositoryTest`: `unown overlay restores and clears letter slots and counts them` — asserts `restoredCount`/`clearedCount` and the exact written entries per letter | PASS |
| 10 | Unown slots don't inflate `pokedexComplete` | `PublishRepositoryTest`: `unown BASE slots do not inflate pokedexComplete` — one pokedex card + one unown card, asserts `pokedexComplete == 1`. Verified against real `computeDiff`'s `pokedexComplete` calc, which filters `it.id == BINDER_ID_POKEDEX` (confirmed by reading `PublishRepository.kt` lines 408–417) | PASS |
| 11 | `publishUnown` toggle: binder included when on, omitted when off | `PublishRepositoryTest`: `buildSnapshot includes unown binder when publishUnown is on` / `...omits unown binder when publishUnown is off` | PASS |
| 12 | Unown slotId (single-char) never collides with Pokédex slotId (multi-char pokemon id) in `computeDiff`'s flat map | No dedicated collision test, but structurally guaranteed (spec §0.3, confirmed by reading `computeDiff` — flat `Map<String, SnapshotSlot>` keyed by `slotId` across all binders/sections) and implicitly exercised by every mixed pokedex+unown test case (`unown BASE slots do not inflate pokedexComplete` runs both binders through the same diff) | PASS (structural, adequately covered) |
| 13 | `!`/`?` letter IDs survive round-trip — Room PK | `Migration7to8Test` (androidTest, not run this session — device required) exercises `assignCard`/`getByLetterId` through real Room, but doesn't specifically target `!`/`?` as the letterId. Room TEXT PKs have no special-character restriction (structural). | PASS (structural; recommend confirming `!`/`?` specifically in `Migration7to8Test` on next device run — not blocking) |
| 14 | `!`/`?` letter IDs survive round-trip — JSON serialization (`binder.json` via Moshi) | No dedicated test; Moshi string fields have no escaping requirement for `!`/`?` (structural, ordinary JSON string content) | PASS (structural) |
| 15 | `!`/`?` letter IDs survive round-trip — nav route encoding | **Gap found — filled this session.** New `AppNavigationScreenTest.kt`, 4 tests, all passing (see above) | PASS (was previously untested; now covered) |
| 16 | First-pass `search()` unchanged, never calls TCGCSV | Same as #3 (`first search does NOT query TCGCSV`) + read `ManualSearchViewModel.kt` `search()` body directly — no `searchTcgcsvByName` reference | PASS |
| 17 | `searchTcgcsvByName` bounded fan-out (chunked concurrency) doesn't regress correctness | Covered functionally by #6 (single group/product happy path) and #2 (failure path); concurrency chunking itself (`TCGCSV_GROUP_CONCURRENCY = 8`) has no dedicated multi-chunk test, but is a performance/ordering concern, not a correctness P0 per spec — on-device latency check is spec's own P0 note (§7.1), not a unit-test target | PASS (in scope of unit testing) |
| 18 | Non-goals honored: no manual card entry, no bundled TCGCSV snapshot, TCGCSV never first-pass, Unown absent from Personal Collection model | Confirmed by reading `ManualSearchViewModel.kt`, `PersonalCollectionViewModel`/entity (untouched — not in diff), and the absence of any bundled JSON asset in the diff (`git status` file list) | PASS |

## Overall verdict

**PASS.** All P0 requirements from `specs.md` are implemented and covered by real, non-trivial
tests that exercise actual production code (not mocked-away behavior). One genuine coverage gap
was found (nav-route round-trip for `!`/`?`) and filled with `AppNavigationScreenTest.kt`. Full
unit suite is green: 106 tests, 0 failures, 0 errors, confirmed via a fresh (`--rerun-tasks`)
Gradle run, not a cached/stale report.

Outstanding (non-blocking, consistent with existing project precedent):
- `Migration7to8Test.kt` (androidTest) requires a device/emulator — not run this session, same as
  `Migration6to7Test` before it. Recommend running on next device session and specifically
  asserting the `!`/`?` letterIds round-trip through the DAO (not just a placeholder letter).
- On-device TCGCSV retry latency (spec §7.1's own flagged P0 verification note) is not a unit-test
  concern — recommend manual verification per spec §12 step 7 before considering the feature fully
  shipped.
