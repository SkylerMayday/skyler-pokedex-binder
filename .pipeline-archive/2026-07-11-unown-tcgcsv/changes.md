# Stage 2 (Coder) — Changes: Unown Binder + TCGCSV Search Fallback

Implemented exactly per `.pipeline/specs.md`. No scope creep.

## Build/test status

**RESOLVED — verified green.** The Coder subagent's environment lacked `JAVA_HOME`/`TEMP`/`TMP`
(this project requires `JAVA_HOME=D:\jdk17\jdk-17.0.14+7` and `TEMP=TMP=C:\Windows\Temp` for
Gradle, per the project handoff's Environment Notes — a known quirk, not a real JVM/OS
restriction). With those set, both commands ran successfully from the orchestrating session:

- `./gradlew.bat compileDebugKotlin` → **BUILD SUCCESSFUL** (33s, 16 tasks, only pre-existing
  unrelated deprecation warnings in `ImageProxyExt.kt`/`ScannerScreen.kt`/`SlotDetailViewModel.kt`).
- `./gradlew.bat testDebugUnitTest` → **BUILD SUCCESSFUL** (32s, 31 tasks). Full unit suite,
  including all new/modified test files listed below, passes.

Instrumented tests (`Migration7to8Test`, `androidTest`) still require a real device/emulator per
existing project precedent — not run here, consistent with prior migration tests
(`Migration6to7Test`) which were also only statically verified pre-device.

---

## Files created (12)

1. `app/src/main/java/com/skyler/pokedexbinder/data/local/UnownBinderEntry.kt` — Room entity, 28 fixed letter IDs (A-Z, !, ?), column order per spec §2.1 (authoritative for migration DDL).
2. `app/src/main/java/com/skyler/pokedexbinder/data/local/UnownBinderDao.kt` — observeAll/getAll/getByLetterId/upsert/insertAll/count, mirrors `MainBinderDao`.
3. `app/src/main/java/com/skyler/pokedexbinder/repository/UnownBinderRepository.kt` — seedIfEmpty (idempotent, 28 hardcoded rows), assignCard, clearCard, overwriteAll (restore path), `LETTER_IDS`/`searchNameFor` companion helpers.
4. `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgcsvDto.kt` — Moshi DTOs for TCGCSV groups/products responses (only fields consumed).
5. `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgcsvApi.kt` — Retrofit interface: `getGroups()`, `getProducts(groupId)`.
6. `app/src/main/java/com/skyler/pokedexbinder/ui/unown/UnownBinderViewModel.kt` — mirrors `ConnectingArtViewModel`'s pending-assign pattern; seeds on init like `MainBinderViewModel`.
7. `app/src/main/java/com/skyler/pokedexbinder/ui/unown/UnownBinderScreen.kt` — 4-column `LazyVerticalGrid` of 28 slots, empty slot shows letter label, filled slot shows card image + bottom-sheet (Reassign / Remove Card).
8. `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration7to8Test.kt` — two-method structure mirroring `Migration6to7Test`: (1) raw-SQL migration validity + column-order assertion, (2) DAO/repository reachability through the real Room-generated DAO.
9. `app/src/test/java/com/skyler/pokedexbinder/ui/ManualSearchViewModelTest.kt` — 4 cases: first search never hits TCGCSV; retry-after-empty hits TCGCSV and shows its results; retry-after-non-empty re-runs search only; retry-from-Error re-runs search only.

## Files modified (12)

1. `data/local/PokedexDatabase.kt` — added `UnownBinderEntry::class` to `entities`, bumped `version = 8`, added `unownBinderDao()` accessor, added `MIGRATION_7_8` (verbatim `CREATE TABLE unown_binder` DDL, column order matches entity).
2. `di/DatabaseModule.kt` — added `MIGRATION_7_8` to `.addMigrations(...)`, added `provideUnownBinderDao`.
3. `di/NetworkModule.kt` — added `@Named("tcgcsv")` Retrofit instance (`baseUrl = https://tcgcsv.com/`) and `provideTcgcsvApi`, mirroring the existing `tcgdex`/`github`/`discord` `@Named` pattern.
4. `repository/CardSearchRepository.kt` — added `tcgcsvApi: TcgcsvApi` constructor param (3rd); added `searchTcgcsvByName(name)`: fetches all Pokémon groups, fans out to per-group product listings in chunks of 8 (`TCGCSV_GROUP_CONCURRENCY`), filters by name/cleanName substring, maps via new `TcgcsvProductDto.toDomain()` (id prefixed `tcgcsv_`, number from `extendedData["Number"]`), dedupes via existing `dedupeKey`. Wrapped in try/catch + per-group `runCatching` — never throws.
5. `ui/manualsearch/ManualSearchViewModel.kt` — `retry()` now branches: if the last state was `Results` with an empty list, calls the new `searchTcgcsvByName` instead of re-running `search()`; otherwise preserves old behavior (re-run `search(lastQuery)`, covers the Error-state "Try Again" path). First-pass `search()` is unchanged — never queries TCGCSV.
6. `ui/manualsearch/ManualSearchScreen.kt` — added optional `initialQuery: String? = null` param + `LaunchedEffect(Unit)` that auto-searches it once (existing callers unaffected — default null). Added a "Search more sources (TCGplayer)" `OutlinedButton` calling `viewModel.retry()` in the empty-results branch.
7. `ui/navigation/AppNavigation.kt` — added `Screen.Unown` / `Screen.UnownSearch` (letterId URL-encoded via `URLEncoder` so `!`/`?` survive as nav args), a drawer item between "Pokédex" and "Connecting Art", and two `composable` routes: the Unown grid screen, and a `ManualSearchScreen` instance scoped to the `Screen.Unown` back-stack entry (identical pattern to `ConnectingArtSearch`) with `initialQuery = UnownBinderRepository.searchNameFor(letterId)`.
8. `repository/PublishSettingsRepository.kt` — added `publishUnown: Boolean = true` to `PublishConfig`, `PUBLISH_UNOWN` DataStore key, mapped in `config` flow, added `setPublishUnown(v)`.
9. `publish/PublishRepository.kt` — added `BINDER_ID_UNOWN`/`BINDER_NAME_UNOWN`/`SECTION_UNOWN` constants, `unownBinderRepository: UnownBinderRepository` constructor param (last), fetches `unownEntries` in `publish()` when `config.publishUnown`, `buildSnapshot` gained `unownEntries: List<UnownBinderEntry> = emptyList()` param (before `config`) and a new binder block (slotId = single-char letterId, no collision with pokemon IDs). `computeDiff` and `pokedexComplete` needed no changes — they already generalize over all binders/slotIds and filter by `BINDER_ID_POKEDEX` respectively.
10. `publish/RestoreRepository.kt` — added `BINDER_ID_UNOWN` constant, `unownBinderRepository: UnownBinderRepository` constructor param (last), added a second overlay block after the pokedex overlay that reuses the same `restored`/`cleared` counters (`var` now, `skipped` also `var`) and calls `unownBinderRepository.overwriteAll(...)` only when the snapshot actually has an `"unown"` binder (old snapshots without one leave `unown_binder` untouched, no crash).
11. `ui/settings/SettingsViewModel.kt` — added `setPublishUnown(v)`.
12. `ui/settings/SettingsScreen.kt` — added a "Publish Unown" `SettingToggleItem` between "Publish Card History" and the Publish/Restore buttons.

## Test files modified (2)

1. `app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt` — updated both existing `CardSearchRepository(api, tcgdexApi)` constructions to 3-arg form with a new `mockk<TcgcsvApi>()`; added 3 new tests (maps + prefixes + extracts Number; filters non-matching names; returns empty and doesn't throw on API failure).
2. `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt` — added `unownBinderRepository` mock (relaxed) to the constructor call; set `defaultConfig.publishUnown = false` (minimal-churn per spec §10.4, keeps existing assertions unaffected); updated the three `buildSnapshot(...)` call sites to the new 4-arg form; added 4 new tests (includes unown binder when on; omits when off; metadata-only republish doesn't REPLACE unown slots; unown BASE slots don't inflate `pokedexComplete`).
3. `app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt` — added `unownBinderRepository` mock (relaxed, default `getAllEntries() -> emptyList()`) to the constructor call; added 2 new tests (unown overlay restores/clears and folds into shared counters; old snapshot without an "unown" binder leaves `unown_binder` untouched — `overwriteAll` never called).

## Resolutions applied (per specs.md §0, no deviation)

- Unown mirrors the Connecting Art grid+ManualSearchScreen+parent-scoped-ViewModel pattern, not QuickScan (§0.1) — `QuickScanViewModel`/`QuickScanScreen` untouched.
- TCGCSV has no name-search endpoint; implemented the bounded fan-out (groups → per-group products, chunk size 8) exactly as specified (§0.2, §7.1).
- No escaping needed for `!`/`?` in Room PK or JSON; nav route arg IS URL-encoded via `URLEncoder` (§0.3, §6.1) — implemented.

## Non-goals honored

No manual card entry, no bundled TCGCSV snapshot, TCGCSV never used as a first-pass source (verified — `search()` in `ManualSearchViewModel` is untouched), no Unown in Personal Collection's model.
