# Coder Stage — Changes Made

Implemented `.pipeline/specs.md` exactly: deleted the standalone Unown feature and extended
Personal Collection's section list with 28 new letter sections (A–Z, !, ?).

## Files deleted (whole file)

1. `app/src/main/java/com/skyler/pokedexbinder/data/local/UnownBinderEntry.kt`
2. `app/src/main/java/com/skyler/pokedexbinder/data/local/UnownBinderDao.kt`
3. `app/src/main/java/com/skyler/pokedexbinder/repository/UnownBinderRepository.kt`
4. `app/src/main/java/com/skyler/pokedexbinder/ui/unown/UnownBinderScreen.kt`
5. `app/src/main/java/com/skyler/pokedexbinder/ui/unown/UnownBinderViewModel.kt`
   (directory `ui/unown/` removed entirely — confirmed empty and deleted)
6. `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration7to8Test.kt`
7. `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt`

## Production files edited

- **`data/local/PokedexDatabase.kt`** — removed `UnownBinderEntry::class` from `entities`,
  reverted `version = 8` → `version = 7`, removed `abstract fun unownBinderDao()`, removed the
  entire `MIGRATION_7_8` object. `MIGRATION_4_5/5_6/6_7` untouched.
- **`di/DatabaseModule.kt`** — removed `MIGRATION_7_8` from `.addMigrations(...)`, removed
  `provideUnownBinderDao`, added `.fallbackToDestructiveMigrationOnDowngrade()` after the
  existing `.fallbackToDestructiveMigration()`.
- **`ui/personalcollection/PersonalCollectionViewModel.kt`** — added private `UNOWN_LETTERS`
  (`'A'..'Z' + ["!","?"]`) and appended 28 `PokemonSection("unown_<letter>", "Unown <letter>",
  listOf("Unown <letter>"))` entries to `PERSONAL_COLLECTION_SECTIONS`, now 33 total.
- **`publish/SnapshotSections.kt`** — mirrored the same 28 entries into
  `PERSONAL_COLLECTION_SECTION_ORDER`.
- **`publish/PublishRepository.kt`** — removed `UnownBinderEntry`/`UnownBinderRepository`
  imports, the `BINDER_ID_UNOWN`/`BINDER_NAME_UNOWN`/`SECTION_UNOWN` constants, the
  `unownBinderRepository` constructor param, the `unownEntries` local in `publish(...)`, the
  `unownEntries` parameter from `buildSnapshot(...)`, and the entire `if (config.publishUnown)`
  snapshot-building block. Pokedex/card-history/connecting-art/personal-collection blocks
  untouched.
- **`publish/RestoreRepository.kt`** — removed `UnownBinderRepository` import,
  `BINDER_ID_UNOWN` constant, `unownBinderRepository` constructor param, and the entire "Unown
  overlay" block. Shared `restored`/`cleared`/`skipped` counters and the surrounding CA/PC
  overlay blocks left intact.
- **`repository/PublishSettingsRepository.kt`** — removed `publishUnown` field from
  `PublishConfig`, `PUBLISH_UNOWN` key, its mapping in the `config` Flow, and
  `setPublishUnown(...)`.
- **`ui/settings/SettingsViewModel.kt`** — removed `setPublishUnown(...)`.
- **`ui/settings/SettingsScreen.kt`** — removed the "Publish Unown" `SettingToggleItem` block
  and its preceding `HorizontalDivider()`; "Publish Card History" item and "Publish now" button
  left intact.
- **`ui/navigation/AppNavigation.kt`** — removed `UnownBinderScreen`/`UnownBinderViewModel`
  imports, the `Screen.Unown`/`Screen.UnownSearch` objects, the two `composable(...)` blocks for
  them, and the `onOpenUnown` argument from the `PersonalCollection` composable. Kept
  `URLEncoder`/`StandardCharsets` imports (still used by QuickScan/Scanner routes).
- **`ui/personalcollection/PersonalCollectionScreen.kt`** — removed `onOpenUnown` param, the
  jump-chip Unown `AssistChip` block, the bottom-of-list Unown `item(span=...)` row, and the
  now-unused `ArrowForward` import. Collapsible header/grid/action-sheet logic (already generic
  over `PERSONAL_COLLECTION_SECTIONS`) untouched.

## Test files edited

- **`app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt`** — removed
  `UnownBinderEntry`/`UnownBinderRepository` imports, the `unownBinderRepository` mock field,
  `publishUnown = false` from `defaultConfig`, the `unownBinderRepository` constructor arg;
  deleted the 4 Unown-specific tests (`buildSnapshot includes/omits unown binder...`,
  `metadata-only republish does not REPLACE unown slots`, `unown BASE slots do not inflate
  pokedexComplete`); fixed all surviving `buildSnapshot(...)` calls to drop the 3rd positional
  `unownEntries` argument.
- **`app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt`** — removed
  `UnownBinderEntry`/`UnownBinderRepository` imports, the `unownBinderRepository` mock field and
  its `coEvery` stub, the constructor arg in `setUp`; deleted the `unownEntry(...)` helper and the
  2 Unown-specific tests (`unown overlay restores and clears...`, `old snapshot without unown
  binder leaves unown untouched`); fixed the inline `PublishRepository(...)` construction in the
  round-trip test (removed `unownBinderRepository = mockk(relaxed = true)`) and its
  `buildSnapshot(...)` call (dropped the 3rd positional arg).
- **`app/src/test/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionViewModelTest.kt`**
  — added the 4 new tests from specs.md §6d: section-list-contains-33 test, unown-query-name
  test, refreshAll-refreshes-33-sections test, uiState-surfaces-unown-section test. No new
  imports needed (all already present).

## Verification gate (specs.md §7)

`grep -rniE "unown" app/src` after all edits, filtered to exclude the expected new
`unown_<letter>`/`Unown <letter>` section keys/titles and unrelated "unowned"/"Unowned" wording
(pre-existing, describes owned/unowned card state elsewhere in the app, not the deleted feature):

Remaining hits, all expected/benign:
- `publish/SnapshotSections.kt:60` — `"unown_$letter" to "Unown $letter"` (new mirror list, §4)
- `ui/personalcollection/PersonalCollectionViewModel.kt:29-31` — new section key/title/query
  (§3)
- `app/src/main/res/raw/pokemon_slots.json:1403-1404` — pre-existing Pokédex slot data for
  species #201 "Unown" (unrelated to the deleted feature; confirmed via `git log` this file
  wasn't touched by the Unown-binder feature's history — last modified by an unrelated GMax
  rename commit)
- `ui/personalcollection/PersonalCollectionViewModelTest.kt:187` — new test assertion (§6d)

**Zero hits** in `di/`, `publish/*.kt` production code (only the expected mirror-list line),
`ui/navigation/`, `ui/settings/`, `data/local/`, or `repository/` beyond the intentional new
section keys. No dangling imports of `ui.unown.*`, `UnownBinderRepository`, `UnownBinderEntry`,
or `UnownBinderDao` anywhere in `app/src`.

## Build/test verification (fresh run)

```
JAVA_HOME=D:\jdk17\jdk-17.0.14+7
./gradlew.bat compileDebugKotlin --console=plain
...
BUILD SUCCESSFUL in 46s
16 actionable tasks: 2 executed, 14 up-to-date
```

```
./gradlew.bat testDebugUnitTest --console=plain --rerun-tasks
...
BUILD SUCCESSFUL in 1m 13s
31 actionable tasks: 31 executed
```

Per-suite result counts (from `app/build/test-results/testDebugUnitTest/*.xml`, this run):

- `PublishRepositoryTest`: tests="30" skipped="0" failures="0" errors="0"
- `RestoreRepositoryTest`: tests="17" skipped="0" failures="0" errors="0"
- `PersonalCollectionViewModelTest`: tests="12" skipped="0" failures="0" errors="0" (8 original +
  4 new from §6d)

No compile errors, no test failures. Only pre-existing warnings surfaced (unrelated
`compileSdk`/`ExperimentalCoroutinesApi`/deprecated-API warnings that predate this change).

## Non-changes confirmed

- `PersonalCollectionRepository.refreshPokemon` / `PersonalCollectionDao` — untouched (already
  generic).
- `personalCollection` publish/restore branches in `PublishRepository`/`RestoreRepository` —
  untouched (already iterate the section list; new sections ride for free).
- `MIGRATION_4_5` / `MIGRATION_5_6` / `MIGRATION_6_7` — untouched.
- `URLEncoder` / `StandardCharsets` imports in `AppNavigation.kt` — kept (still used by
  QuickScan/Scanner).
