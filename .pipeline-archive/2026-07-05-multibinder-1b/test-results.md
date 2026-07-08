# Run 1B — Tester Results

Stage: Tester. Verifies Coder's changes (`.pipeline/changes.md`) against the Planner's spec
(`.pipeline/specs.md`). No emulator / no Compose UI test harness available in this environment —
verification is split explicitly below into "verified" (build + unit test + direct code reading)
vs. "could not verify" (anything requiring an actual rendered UI or gesture).

## 1. Build check — PASS

```
Set-Location "D:\Claude Projects\PokedexBinderV2"
$env:JAVA_HOME = "D:\jdk17\jdk-17.0.14+7"
& .\gradlew.bat assembleDebug testDebugUnitTest --console=plain
```

- `assembleDebug`: **BUILD SUCCEEDED** (up to date, no source changes since Coder's own successful
  build; recompiled clean during ad-hoc test runs below with no errors).
- `testDebugUnitTest`: **74 tests completed, 8 failed** (up from 57 total / 8 failed pre-Tester —
  the +17 are the new ViewModel tests added in this stage, §2). The 8 failures are exactly the
  pre-existing, pre-listed, unrelated set:
  - `AssignCardUseCaseTest > assign to occupied slot moves old card to secondary binder`
  - `GeminiCardScannerTest > scan throws RateLimitException on 429`
  - `GeminiCardScannerTest > scan throws IOException on 500`
  - `GeminiCardScannerTest > scan returns nulls for missing fields`
  - `GeminiCardScannerTest > scan returns ParsedCardInfo on success`
  - `SmartThresholdUseCaseTest > multiple results with name match but no number is low confidence`
  - `BinderRepositoryTest > assignCard upserts entry with card info`
  - `CardSearchRepositoryTest > searchByNameAndNumber builds correct query and maps results`

  **Zero new failures.** No regression introduced by Run 1B.

## 2. New ViewModel unit tests — PASS (17/17)

Followed the existing pattern in `app/src/test/java/com/skyler/pokedexbinder/ui/MainBinderViewModelTest.kt`
(MockK + Turbine + `UnconfinedTestDispatcher`, `Dispatchers.setMain`/`resetMain` in `@Before`/`@After`).

### `app/src/test/java/com/skyler/pokedexbinder/ui/connectingart/ConnectingArtViewModelTest.kt` (new, 9 tests)
- `createGroup with a selected grid preset calls repository with matching rows and cols` (2×2)
- `createGroup with 1x4 preset threads rows and cols through unchanged`
- `deleteGroup calls repository delete only and performs no manual slot cleanup` — asserts
  `repository.deleteGroup(id)` is called exactly once AND `removeCard`/`setOwned` are called zero
  times, confirming the Planner's explicit non-goal (no manual slot cleanup; cascade FK relied on).
- `setOwned toggles owned state via repository`
- `beginAssign then assignPendingCard assigns the card to the pending slot and clears pending`
- `cancelAssign clears pending slot without assigning`
- `assignPendingCard is a no-op when no slot is pending`
- `uiState groups slots by groupId from combined flows`
- `reorderGroups reorders local id list and calls repository reorderGroups`

### `app/src/test/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionViewModelTest.kt` (new, 8 tests)
- `init triggers refreshAll only when cache is empty`
- `init does not trigger refreshAll when cache already populated`
- `toggleOwned marks a card owned via setOwned when currently unowned`
- `toggleOwned removes ownership via removeOwned when currently owned`
- `uiState marks cards owned based on entries and unowned otherwise`
- **`refreshing with fresh cache data preserves an existing owned entry`** — the behavior explicitly
  called out as most likely to be broken by a well-intentioned "merge". Seeds `entriesFlow` with an
  owned entry for `card-1`, asserts it's owned in `uiState` before refresh, then mutates `cacheFlow`
  to simulate `replaceCacheForPokemon`'s real effect (fresh cache rows for `card-1` + a new `card-2`,
  `entriesFlow` left untouched — mirroring what the DAO actually does), calls `vm.refreshAll()`, and
  asserts `card-1` is STILL owned and `card-2` is unowned. Also asserts the ViewModel itself never
  calls `setOwned`/`removeOwned` during a refresh — confirming ownership preservation comes for free
  from the two-table split and the ViewModel adds no merge logic of its own (matching the spec's
  explicit instruction not to).
- `refreshAll settles isRefreshing back to false and clears prior error on success`
- `refreshAll surfaces an error message when repository throws`

All 17 pass in isolation (`--tests "com.skyler.pokedexbinder.ui.connectingart.*" --tests "com.skyler.pokedexbinder.ui.personalcollection.*"` → BUILD SUCCESSFUL) and in the full suite run.

Note: both files compile with `ExperimentalCoroutinesApi` opt-in *warnings* (from
`UnconfinedTestDispatcher`), not errors — same as the existing, unmodified `MainBinderViewModelTest.kt`,
which has the identical warning already. Not a regression; consistent with repo convention of
leaving it un-suppressed.

## 3. ManualSearchScreen sub-route wiring — VERIFIED by reading code

`app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt` (lines 174–200):

```kotlin
composable(Screen.ConnectingArt.route) {
    ConnectingArtScreen(
        onOpenDrawer = { openDrawer() },
        onOpenSlotSearch = { slotId ->
            navController.navigate(Screen.ConnectingArtSearch.createRoute(slotId))
        }
    )
}
composable(
    route = Screen.ConnectingArtSearch.route,
    arguments = listOf(navArgument("slotId") { type = NavType.IntType })
) { backStack ->
    val parentEntry = remember(backStack) {
        navController.getBackStackEntry(Screen.ConnectingArt.route)
    }
    val artVm: ConnectingArtViewModel = hiltViewModel(parentEntry)
    ManualSearchScreen(
        onCardSelected = { card ->
            artVm.assignPendingCard(card)
            navController.popBackStack()
        },
        onBack = {
            artVm.cancelAssign()
            navController.popBackStack()
        }
    )
}
```

Confirmed:
- `slotId` is declared as a real nav arg (`NavType.IntType`) on the `connecting_art_search/{slotId}`
  route, and `Screen.ConnectingArtSearch.createRoute(slotId)` is called with the tapped slot's id.
- **ViewModel scoping is correct**: `hiltViewModel(parentEntry)` is scoped to the `ConnectingArt`
  route's own back-stack entry (obtained via `navController.getBackStackEntry(Screen.ConnectingArt.route)`),
  not a fresh instance on the search route. This is the same `ConnectingArtViewModel` instance the
  list screen holds, confirmed by Hilt Navigation Compose semantics (`hiltViewModel(NavBackStackEntry)`
  returns the ViewModel scoped to that specific entry's `ViewModelStore` — since both call sites pass
  the *same* `NavBackStackEntry` object for `Screen.ConnectingArt.route`, they get the same instance).
- Card selection therefore lands on the right slot: `ConnectingArtScreen`'s `SlotTile.onEmptyTap`
  calls `viewModel.beginAssign(slotId)` (sets `_pendingAssignSlotId` on the shared instance) before
  navigating; `ManualSearchScreen.onCardSelected` calls `artVm.assignPendingCard(card)` on that same
  shared instance, which reads `_pendingAssignSlotId` — not a fresh/decoupled value. Traced end to
  end, not just taken on the Coder's word.
- `ManualSearchScreen`'s own `viewModel` param is left defaulted (fresh `ManualSearchViewModel` per
  route entry) — correct per spec; only the assign-target state needs to be shared, not the search
  UI's own state.

One structural note (not a defect, just worth flagging): `beginAssign(slotId)` is the actual source
of truth for the pending slot; the nav-arg `slotId` on `ConnectingArtSearchSearch` is currently
unused by `AppNavigation.kt`'s composable body (matches spec §9's "belt-and-suspenders" framing —
the arg exists for route-addressability/robustness but `beginAssign` is the primary path, exactly as
the Planner specified). Confirmed the Coder did not silently drop this — it's an intentional,
spec-sanctioned no-op.

## 4. releaseDate thread-through — VERIFIED, no dropped field

Traced the full chain by reading each file directly:

1. `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt:20` —
   `TcgSetDto.releaseDate: String? = null` (nullable, defaulted network DTO field).
2. `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt:104` —
   `TcgCardDto.toDomain()` maps `setReleaseDate = set.releaseDate`.
3. `app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt:12` —
   `TcgCard.setReleaseDate: String? = null` receives it.
4. `app/src/main/java/com/skyler/pokedexbinder/repository/PersonalCollectionRepository.kt:49` —
   `refreshPokemon` maps `releaseDate = card.setReleaseDate ?: ""` into `PersonalCollectionCache`.
5. `app/src/main/java/com/skyler/pokedexbinder/data/local/PersonalCollectionCache.kt:13` —
   `releaseDate: String` (non-null Room column, matches the `?: ""` fallback upstream).
6. `app/src/main/java/com/skyler/pokedexbinder/data/local/PersonalCollectionDao.kt:10,13` —
   both `observeCacheForPokemon` and `observeAllCache` `ORDER BY releaseDate DESC`.

No drop anywhere in the chain. Confirmed the TCGdex path
(`TcgdexCardBriefDto.toDomain()` in `CardSearchRepository.kt`) is untouched and leaves
`setReleaseDate = null` → `releaseDate = ""` in cache for TCGdex-merged results, exactly matching
the spec's called-out acceptable behavior (empty string sorts last in `DESC` order, no crash, no
special-casing needed).

Compiles clean: this field is exercised transitively by `CardSearchRepositoryTest` (which is one of
the 8 pre-existing failing tests, but for an unrelated query-string assertion — confirmed the test
file's `TcgSetDto("XY")` positional constructor call still compiles because `releaseDate` has a
default value, so this addition did not cause or contribute to that failure).

## 5. Cascade delete verification — VERIFIED at both entity and migration level

`app/src/main/java/com/skyler/pokedexbinder/data/local/ConnectingArtSlot.kt`:
```kotlin
@Entity(
    tableName = "connecting_art_slot",
    foreignKeys = [
        ForeignKey(
            entity = ConnectingArtGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId")]
)
```

`app/src/main/java/com/skyler/pokedexbinder/data/local/PokedexDatabase.kt`, `MIGRATION_6_7`
(the migration that actually shipped this table, Run 1A) creates the raw SQL table with the matching
constraint verbatim:
```sql
FOREIGN KEY(`groupId`) REFERENCES `connecting_art_group`(`id`)
ON UPDATE NO ACTION ON DELETE CASCADE
```
— entity annotation and migration SQL agree; no drift between the Room-generated schema (if it were
ever regenerated fresh) and the raw migration path that real installs will actually run.

`ConnectingArtDao.deleteGroupById` (`DELETE FROM connecting_art_group WHERE id = :id`) is a plain
delete on the parent row with no manual child cleanup — it relies entirely on the FK cascade.

Checked whether SQLite foreign key enforcement is actually turned on for this app's connection:
`app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt` builds the database via
`Room.databaseBuilder(...).fallbackToDestructiveMigration()` with no `.setForeignKeyConstraintsEnabled` call
and no override disabling it. Room's `RoomOpenHelper`/`FrameworkSQLiteOpenHelper` enables
`PRAGMA foreign_keys = ON` by default on every connection it opens (a fixed, non-opt-in Room
behavior), so cascade delete is live. **Conclusion: deleting a group really does cascade-delete its
slots at the DB level; `ConnectingArtViewModel.deleteGroup`'s lack of manual cleanup is safe, not a
data-orphaning bug.**

## 6. Deviations from spec — reviewed, none material

Matches `.pipeline/changes.md`'s stated deviations:
- `SECTIONS` → `PERSONAL_COLLECTION_SECTIONS` rename: cosmetic, avoids an ambiguous generic
  top-level name. Verified the 5 sections, keys, titles, and query name lists are unchanged from
  spec §7 (`charizard`/`celebi`/`leafeon`/`tangela`/`minccino_cinccino`, the last with two query
  names). Confirmed via direct read of `PersonalCollectionViewModel.kt:18-24`.
- `GroupSection` as a `ReorderableCollectionItemScope` extension function: not independently
  verifiable against the `2.4.0` sources jar in this pass (would require inspecting the AAR/sources,
  which was not done), but the claim is internally consistent with `SecondaryBinderScreen.kt`'s
  established usage of the same library and did not produce any compile error in `assembleDebug` —
  if the receiver type were wrong, `ConnectingArtScreen.kt` would not compile. Compilation success is
  itself indirect but strong evidence the extension signature matches the library's actual API.

## What was verified vs. what could NOT be verified (no emulator / no Compose UI test tooling)

**Verified** (build, unit tests, direct source reading, migration SQL, DI wiring):
- Clean compile of all new/edited files, no new test regressions.
- ViewModel business logic: group creation with preset dimensions, delete-without-manual-cleanup,
  owned toggling (both binders), pending-slot assign/cancel flow, reorder id-list computation,
  refresh-preserves-owned-state (the one behavior most likely to be silently broken).
  init-triggers-refresh-only-when-empty gating.
  releaseDate error-surfacing on refresh failure.
- Navigation graph wiring: route/arg declaration, shared ViewModel scoping via `hiltViewModel(backStackEntry)`,
  the exact call chain from `beginAssign` → `onOpenSlotSearch` → `ManualSearchScreen.onCardSelected` →
  `assignPendingCard` all operating on the same ViewModel instance.
- `releaseDate` field: full DTO → domain → repository → Room entity → DAO ordering chain, no drop.
- Cascade delete: entity FK annotation + migration SQL agree; Room's default FK enforcement means
  this is live at runtime, not just declared.

**Could NOT verify** (requires a running emulator/device or a Compose UI test harness, neither
available in this environment):
- Actual visual rendering of `ConnectingArtScreen`/`PersonalCollectionScreen` (layout correctness,
  grid alignment, `aspectRatio` behavior, bottom sheet visuals).
- Real drag-reorder gesture behavior via `sh.calvin.reorderable` (`rememberReorderableLazyListState`,
  `.longPressDraggableHandle()`) — only the resulting `ViewModel.reorderGroups(from, to)` → id-list
  math was unit-tested; the drag gesture → index callback path itself is library-internal and
  untested here.
- Real pull-to-refresh gesture via `PullToRefreshBox` — only `viewModel.refreshAll()` being wired as
  the `onRefresh` callback was confirmed by reading `PersonalCollectionScreen.kt`; the actual gesture
  gating from Compose's PullToRefresh state machine was not exercised.
- `DimmableCardImage`'s actual visual desaturation/scrim appearance (colour correctness, scrim
  opacity as rendered) — the composable's code was read and matches spec §6, but no screenshot/visual
  regression check was possible.
- The `GroupSection`-as-extension-function receiver type against the actual `reorderable:2.4.0`
  sources jar (relied on successful compilation as indirect evidence instead of direct sources-jar
  inspection).
- End-to-end manual QA of the full user flow (create group → tap empty slot → search → assign →
  verify correct slot updates on screen) — traced entirely through source code and unit tests, not
  observed running.

## Overall verdict

**PASS.** Build is clean, no regressions, 17 new ViewModel tests added and passing (including the
owned-state-preservation-across-refresh test explicitly called out as highest-risk), navigation
wiring and releaseDate thread-through verified by direct code reading (not taken on the Coder's
claim), and cascade delete confirmed safe at both the entity/migration and Room-runtime-behavior
level. Ready to hand off to Reviewer.
