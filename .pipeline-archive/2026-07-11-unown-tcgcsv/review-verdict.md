# Stage 4 (Reviewer) — Verdict: Unown Binder + TCGCSV Search Fallback

## VERDICT: SHIP

Independently verified — did not take prior stages' self-reports at face value. Read every
changed/created file in full, ran a fresh (non-cached, `--rerun-tasks`) compile and test pass
myself, and traced every P0 requirement to a specific line of production code.

---

## 1. Build/test verification (independent, this session)

Ran with `JAVA_HOME=D:/jdk17/jdk-17.0.14+7`, `TEMP=TMP=C:/Windows/Temp` (forward slashes required
in this Bash tool; the project's documented backslash form fails to parse):

- `./gradlew.bat compileDebugKotlin --console=plain` → **BUILD SUCCESSFUL** (9s, all tasks
  up-to-date, confirming the Coder's compile claim is current).
- `./gradlew.bat testDebugUnitTest --console=plain --rerun-tasks` → **BUILD SUCCESSFUL** (40s, 31
  tasks, forced fresh execution — not cache). Verified `app/build/test-results/testDebugUnitTest/`
  directly: **106 `<testcase>` elements, zero files with `failures="1+"` or `errors="1+"`**,
  matching the Tester's reported 106/0/0 exactly.

Confirmed real (not stale): the test-results XML timestamps and the `--rerun-tasks` flag rule out
a cached false-positive.

## 2. P0 requirements — traced to code

| # | P0 requirement | Satisfied by |
|---|---|---|
| 1 | Room v8 migration adds `unown_binder`, 28-row seed on first launch, migration test covers DDL + DAO reachability | `PokedexDatabase.kt` `MIGRATION_7_8` (verbatim DDL matching `UnownBinderEntry` column order), `version = 8`; `UnownBinderRepository.seedIfEmpty()` guards on `count() > 0`, called from `UnownBinderViewModel.init`; `Migration7to8Test.kt` two methods mirror `Migration6to7Test` exactly (raw-DDL/column assertion + real-DAO reachability incl. seed/assign/clear) |
| 2 | `UnownBinderDao`/`Repository`/`ViewModel`/`Screen` mirroring main binder's single-card-per-slot mechanic | `UnownBinderDao.kt`, `UnownBinderRepository.kt` (seed/assign/clear/overwriteAll), `UnownBinderViewModel.kt` (pending-assign pattern, mirrors ConnectingArt per the Planner's resolved §0.1), `UnownBinderScreen.kt` (28-slot `LazyVerticalGrid(GridCells.Fixed(4))`, empty/filled tap → search/reassign/clear) |
| 3 | "Unown" nav drawer entry, routes correctly | `AppNavigation.kt`: `NavigationDrawerItem("Unown")` inserted between Pokédex and Connecting Art (line ~112); `Screen.Unown`/`Screen.UnownSearch` + two `composable(...)` blocks wired into the `NavHost` |
| 4 | Unown wired into `PublishRepository.buildSnapshot` + `RestoreRepository` overlay | `PublishRepository.kt`: `BINDER_ID_UNOWN`/`BINDER_NAME_UNOWN`, `PublishConfig.publishUnown` (`PublishSettingsRepository.kt`), `buildSnapshot(..., unownEntries, config)` new binder block (lines 304–322 approx); `RestoreRepository.kt`: `BINDER_ID_UNOWN` overlay block after the pokedex overlay, folds into the same `restored`/`cleared`/`skipped` counters |
| 5 | Metadata-only republish must not spuriously REPLACE Unown slots | `computeDiff` unchanged — compares `cardId` only, keyed by stable `slotId = letterId`; verified by `PublishRepositoryTest`: `metadata-only republish does not REPLACE unown slots` (identical baseline/next, asserts no delta for slot "A") |
| 6 | Restore overlay regression test extended to cover Unown | `RestoreRepositoryTest.kt`: `unown overlay restores and clears letter slots and counts them` (real restore/clear/count assertions against captured `overwriteAll` args) + `old snapshot without unown binder leaves unown untouched` (`overwriteAll` never called, no crash) |
| 7 | `CardSearchRepository.searchTcgcsvByName` implemented, wired into `ManualSearchViewModel.retry()` as empty-results-only fallback, `tcgcsv_`-prefixed IDs | `CardSearchRepository.kt` `searchTcgcsvByName` (bounded fan-out, chunk size 8, `TcgcsvProductDto.toDomain()` prefixes `id = "tcgcsv_$productId"`); `ManualSearchViewModel.retry()` branches on `lastWasEmptyResults` before calling it; first-pass `search()` body has zero reference to `searchTcgcsvByName` (verified by reading the method directly) |
| 8 | CLB Mr. Mime (or equivalent) confirmed assignable via TCGCSV fallback, on-device | **Not independently verifiable in this sandbox** (no device/emulator, no live network call attempted) — see Outstanding below |
| 9 | Full unit suite green; new tests for TCGCSV merge/fallback logic verifying not-called-on-first-search and called-on-retry | Confirmed green (§1). `ManualSearchViewModelTest.kt` 4 cases cover exactly this (`coVerify(exactly = 0)` / `coVerify(exactly = 1)`); `CardSearchRepositoryTest.kt` 3 new cases cover mapping/filtering/failure-safety |

## 3. Non-goals honored — verified

- **No manual card entry**: no new manual-entry UI/DB path in the diff; `ManualSearchScreen`'s
  changes are limited to an optional `initialQuery` param and a retry button — still search-driven.
- **No bundled/cached TCGCSV snapshot**: `git status` shows no new JSON/asset file; `TcgcsvApi`
  hits `https://tcgcsv.com/` live on every call, no local cache layer added.
- **TCGCSV never a first-pass source**: `ManualSearchViewModel.search()` body (read directly,
  lines 33–51) has zero reference to `searchTcgcsvByName`; only `retry()` calls it, and only when
  `lastWasEmptyResults` is true.
- **Unown NOT modeled on Personal Collection's pattern**: `UnownBinderEntry`/`Dao`/`Repository`
  mirror `MainBinderEntry`'s single-assignment shape (nullable `assignedCard*` columns, no
  owned/toggle cache), confirmed by reading the entity — no `owned` boolean, no multi-card list.
  `PersonalCollectionViewModel`/entity files are absent from `git status` (untouched).
- **Connecting Art / Personal Collection publish scope untouched**: neither
  `ConnectingArtViewModel`/`Screen` nor `PersonalCollectionViewModel`/`Screen` nor their
  repositories appear in `git status`; `PublishRepository.buildSnapshot` gained only a third
  (Unown) block — the existing pokedex/cardHistory blocks are diff-unchanged apart from the new
  parameter being threaded through.

## 4. Mid-pipeline clarification — verified reflected correctly

Two clarifications from the user were checked against the actual implementation:

1. **"Unown in the style of Pokédex — A-Z + symbols, separate binder, not merged into the main
   Pokédex binder."** Confirmed: `unown_binder` is a fully separate Room table/DAO/repository from
   `main_binder`; `Screen.Unown` is a standalone nav destination, not a section inside
   `MainBinderScreen`; `PublishRepository` emits it as its own `SnapshotBinder(id = "unown")`,
   distinct from `id = "pokedex"`.
2. **"Search UI should reuse the Connecting-Art-style typed search, not QuickScan's camera flow."**
   Confirmed: `Screen.UnownSearch`'s composable renders `ManualSearchScreen` (typed search),
   scoped to a parent `UnownBinderViewModel` via `hiltViewModel(parentEntry)` — the exact
   `ConnectingArtSearch` pattern. `QuickScanScreen`/`QuickScanViewModel` do not appear in
   `git status` — untouched, as the Planner's §0.1 resolution required.

## 5. Spot-checks

- **No unused imports / dead code**: reviewed every new file (`UnownBinderEntry.kt`,
  `UnownBinderDao.kt`, `UnownBinderRepository.kt`, `TcgcsvApi.kt`, `TcgcsvDto.kt`,
  `UnownBinderViewModel.kt`, `UnownBinderScreen.kt`) — no stray imports, no unreachable branches.
  `TcgcsvDto.kt` fields are exactly the ones consumed (Moshi ignores the rest server-side, no
  unused-field warnings apply to data classes).
- **Constructor param / call-site consistency**: `CardSearchRepository`, `PublishRepository`,
  `RestoreRepository` all gained new trailing constructor params; every production call site
  (`di/` modules use `@Inject constructor`, so Hilt wires them — no manual call sites to miss) and
  every test call site was updated (`CardSearchRepositoryTest`, `PublishRepositoryTest`,
  `RestoreRepositoryTest` all pass the new mock). Compile success confirms no missed call site.
- **`restored`/`cleared`/`skipped` mutability**: `RestoreRepository.kt` — `restored`/`cleared`
  were already `var` (pre-existing, lines 75–76); `skipped` was correctly changed from `val` to
  `var` (line 103, confirmed in diff) to allow the Unown overlay to add to it. No compile error
  because the Unown block runs `skipped += ...` after the `var` declaration.
- **`!`/`?` letter IDs — Room PK**: ordinary TEXT PK, no restriction; `Migration7to8Test`'s DAO
  test uses letter "A" only (not `!`/`?` specifically) — this is a real, minor gap (flagged by the
  Tester too, non-blocking) but Room TEXT columns have no character restrictions, so risk is
  structural/near-zero. Recommend adding `!`/`?` to the DAO round-trip assertion next time
  `Migration7to8Test` runs on a device, but does not block shipping.
- **`!`/`?` letter IDs — JSON (binder.json)**: Moshi string fields, no escaping requirement;
  structural, no test needed.
- **`!`/`?` letter IDs — nav route**: `Screen.UnownSearch.createRoute` URL-encodes via
  `URLEncoder.encode(letterId, UTF_8).replace("+", "%20")`. Verified this actually prevents the
  danger scenario: a raw `?` in a nav route argument would be interpreted as a query-string
  separator by Compose Navigation's route parser. New `AppNavigationScreenTest.kt` (4 tests)
  confirms `"!"` → `%21`, `"?"` → `%3F`, all 28 letterIds round-trip through encode→decode, and the
  encoded route contains no stray `?`. This is a pure-JVM test of the encode step only — it does
  not exercise `NavType.StringType`'s Android-side decode, which is correctly deferred to on-device
  verification (no Robolectric/instrumentation harness exists in this project for that).
- **Scope discipline**: `git status --porcelain` shows exactly the file set the Planner specified
  (12 new + 12 modified + 3 test-modified + the spec doc itself) — no unrelated refactors, no
  incidental formatting churn in files outside this feature's footprint.

## 6. Minor observations (non-blocking, not spec violations)

- `nameQuery()` in `CardSearchRepository` (pre-existing, unchanged) sanitizes spaces/hyphens/colons
  in search queries sent to pokemontcg.io but does not sanitize `!`/`?`. A first-pass search for
  "Unown !" or "Unown ?" sends those characters through to pokemontcg.io's Lucene-style query
  unescaped. This is **pre-existing behavior**, not introduced by this feature, and pokemontcg.io's
  query parser may handle it fine (unverified, out of scope) — flagging only for awareness, not as
  a defect of this PR.
- `Migration7to8Test`'s DAO-reachability test exercises letter "A" only, not `!`/`?` specifically
  (see spot-check above) — recommend covering on next device run, non-blocking.

## Outstanding — requires Skyler, on-device

Per spec's own Success Criteria and P0 item 8, these require a real device/emulator and cannot be
verified from this sandbox:

1. `Migration7to8Test.kt` (androidTest) has not been executed against a real device/emulator this
   session (consistent with `Migration6to7Test` precedent — always deferred to device runs).
2. CLB Mr. Mime (or equivalent) found and assigned via the TCGCSV retry fallback, on-device,
   including a latency sanity check (spec §7.1's own flagged verification note — the bounded
   fan-out queries up to 217 groups sequentially in chunks of 8, which could be slow; not a
   unit-testable concern).
3. A full publish → restore cycle with an assigned Unown card confirmed round-tripping through
   `binder.json`, on-device.

None of these block shipping the code — they are the final human-verification steps the spec
itself designates as on-device-only. All code-level P0 requirements are implemented, traced, and
covered by real (non-mocked-away) automated tests, and the full unit suite is green on a fresh run.
