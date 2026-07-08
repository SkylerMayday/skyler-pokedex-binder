# Run 1B — Coder Changes

Implements the plan in `.pipeline/specs.md`: real UI for Connecting Art / Personal Collection,
their ViewModels, a shared `DimmableCardImage` helper, navigation wiring, the
`PersonalCollectionRepository.refreshPokemon` stub, and the `releaseDate` thread-through.

## Files created

### `app/src/main/java/com/skyler/pokedexbinder/ui/common/DimmableCardImage.kt` (spec §6)
New shared composable: `AsyncImage` + optional `ColorFilter.colorMatrix(setToSaturation(0f))` and a
`Color.Black.copy(alpha = 0.45f)` scrim `Box` overlay when `dimmed = true`. Used by both new screens
for the "unowned = greyed" visual. No prior desaturation helper existed (MainBinder/Secondary render
`AsyncImage` at full color), so this is genuinely new, not a duplicate.

### `app/src/main/java/com/skyler/pokedexbinder/ui/connectingart/ConnectingArtViewModel.kt` (spec §3)
`@HiltViewModel` constructor-injecting `ConnectingArtRepository`. `uiState` combines
`observeGroups()` + `observeAllSlots()` into `ConnectingArtUiState(groups, slotsByGroup)`,
`stateIn(WhileSubscribed(5_000))`. `_pendingAssignSlotId` StateFlow tracks the slot being assigned
(`beginAssign` / `cancelAssign` / `assignPendingCard`). `reorderGroups(from, to)` computes the new
ordered id list from `uiState.value.groups` (mutable local list, `add(to, removeAt(from))`) and calls
`repository.reorderGroups(ids)` — no local optimistic StateFlow override added (spec said start
without one). `deleteGroup` calls `repository.deleteGroup(groupId)` only — **no manual slot cleanup**,
per the Planner's explicit non-goal (cascade FK on `connecting_art_slot.groupId` handles it).

### `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionViewModel.kt` (spec §7)
`@HiltViewModel` constructor-injecting `PersonalCollectionRepository`. Defines
`PERSONAL_COLLECTION_SECTIONS` (top-level `val`, since the spec's example used a bare `SECTIONS` name
that would collide across files — module-scoped naming to avoid ambiguity) with the 5 fixed Pokémon
sections in spec order, `PersonalCard`, `PersonalCollectionUiState`. `uiState` combines
`observeAllCache()` + `observeEntries()` + local `_isRefreshing`/`_errorMessage` flows, builds
`ownedIds` from entries where `owned == true`, groups cache rows by `pokemonKey` into `PersonalCard`
lists (cache flow is already `ORDER BY releaseDate DESC` from the DAO — no re-sort here). `init` calls
`refreshAll()` only if `repository.cacheCount() == 0`. `refreshAll()` sets `_isRefreshing`, loops
`PERSONAL_COLLECTION_SECTIONS` calling `repository.refreshPokemon(section.key, section.queryNames)`,
catches to `_errorMessage`, resets `_isRefreshing` in `finally`. `toggleOwned` calls
`repository.removeOwned(cardId)` if currently owned, else `repository.setOwned(cardId, true)` — **no
manual owned-state merge added**, per the Planner's explicit non-goal (the cache/entry two-table split
already preserves owned rows across a refresh for free).

## Files edited

### `app/src/main/java/com/skyler/pokedexbinder/ui/connectingart/ConnectingArtScreen.kt` (spec §4)
Replaced the placeholder body. New signature adds `onOpenSlotSearch: (slotId: Int) -> Unit` and
defaults `viewModel = hiltViewModel()`. `Scaffold` with FAB (`+` → create-group sheet). Empty-groups
state shows centered text. `LazyColumn` + `rememberReorderableLazyListState` (list variant, not grid —
the reorder unit is a whole group section) + `ReorderableItem` per group. `GroupSection` is an
extension function on `sh.calvin.reorderable.ReorderableCollectionItemScope` (matches the library's
actual `ReorderableItem` content-lambda receiver type — `ReorderableCollectionItemScope.(isDragging:
Boolean) -> Unit`, verified against the `2.4.0` sources jar rather than assuming Kotlin context
receivers): header `Row` with drag-handle icon + `.longPressDraggableHandle()` + delete `IconButton`,
then a plain nested `Column { repeat(rows) { Row { repeat(cols) { SlotTile(...) } } } }` grid (no
nested scrollable `LazyVerticalGrid`, avoiding the nested-scroll crash the spec called out).
`SlotTile`: empty slot shows a centered `Add` icon and calls `beginAssign` + `onOpenSlotSearch`;
assigned slot renders `DimmableCardImage(dimmed = !owned)` and opens the slot action sheet. Create-group
`ModalBottomSheet` with name field + `1×2/1×3/1×4/2×2/3×3` `FilterChip` presets, Create disabled while
name is blank. Slot action `ModalBottomSheet` toggles Mark as Owned/Unowned + Remove Card + Back, each
dismissing the sheet. Delete-group `AlertDialog` calls `viewModel.deleteGroup(id)` only.

### `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionScreen.kt` (spec §8)
Replaced the placeholder body. Signature unchanged (`onOpenDrawer`, defaulted `viewModel`) — no extra
callbacks. Body wrapped in `PullToRefreshBox` (material3 1.3.1, no accompanist) with
`onRefresh = viewModel::refreshAll`. `LazyVerticalGrid(GridCells.Fixed(3))`; for each section in
`PERSONAL_COLLECTION_SECTIONS` order, emits a full-span header `item` (`GridItemSpan(maxLineSpan)` —
`LazyVerticalGrid` has no `stickyHeader`) followed by its `items(cards)`; skips a section's header
entirely when it has zero cards and isn't currently refreshing (avoids empty headers on first load).
Card cells: `Card` + `DimmableCardImage(dimmed = !owned)`, `aspectRatio(0.72f)`, clickable to open the
action sheet (`Add Card` / `Remove Card` / `Back`, calling `toggleOwned`). First-load feedback:
centered `CircularProgressIndicator` while refreshing with an empty cache; error + `Retry` button when
`errorMessage` is set and cache is empty.

### `app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt` (spec §9)
Added `Screen.ConnectingArtSearch("connecting_art_search/{slotId}")` with `createRoute(slotId: Int)`.
`Screen.ConnectingArt.route` composable now passes `onOpenSlotSearch` navigating to the new route.
Added the search composable: obtains the **same** `ConnectingArtViewModel` instance as the list screen
via `hiltViewModel(navController.getBackStackEntry(Screen.ConnectingArt.route))`, so
`artVm.assignPendingCard(card)` writes to the slot set by `beginAssign` on the list screen. Reuses
`ManualSearchScreen` as-is (`onCardSelected` calls `assignPendingCard` + pops back stack, `onBack`
calls `cancelAssign` + pops). `Screen.PersonalCollection` composable and drawer/bottom-bar/start
destination untouched, per spec.

### `app/src/main/java/com/skyler/pokedexbinder/repository/PersonalCollectionRepository.kt` (spec §5)
Implemented `refreshPokemon(pokemonKey, names)`: for each name, calls
`cardSearchRepository.searchByName(name)` (Pocket filter inherited via `safeSearch`), merges results
across names into a `LinkedHashMap<String, PersonalCollectionCache>` keyed by `card.id` (dedupe,
preserves first-seen order), maps each `TcgCard` to `PersonalCollectionCache(cardId, pokemonKey, name,
imageUrl, setName, releaseDate = setReleaseDate ?: "")`, then calls
`dao.replaceCacheForPokemon(pokemonKey, merged.values.toList())`. **No manual owned-state merge added**
— confirmed by reading `PersonalCollectionDao.replaceCacheForPokemon` (deletes only rows for the given
`pokemonKey` in `personal_collection_cache`; `personal_collection_entry` is untouched) and
`PersonalCollectionEntry` (separate table keyed by `cardId`), matching the spec's rationale.

### `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt` (spec §2)
`TcgSetDto` gained `val releaseDate: String? = null` (nullable, defaulted — safe if the API omits it,
no Moshi adapter break for existing call sites that construct `TcgSetDto(name)` positionally... note:
positional construction with only `name` still compiles since `releaseDate` has a default).

### `app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt` (spec §2)
`TcgCard` gained `val setReleaseDate: String? = null` (trailing, defaulted — no existing call site
needed updating).

### `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt` (spec §2)
`TcgCardDto.toDomain()` now maps `setReleaseDate = set.releaseDate`. The TCGdex `toDomain()` path
(`TcgdexCardBriefDto.toDomain()`) is untouched/still omits it, leaving `setReleaseDate = null` for
TCGdex-merged results — matches spec §5's note that TCGdex cards get `releaseDate = ""` in the cache
and sort last.

## Deviations from spec (with rationale)

- **`SECTIONS` renamed to `PERSONAL_COLLECTION_SECTIONS`.** The spec's pseudocode used a bare
  top-level `SECTIONS` inside the ViewModel file; kept it top-level (not a companion object member,
  since `PokemonSection`/`PersonalCard` are also top-level per the pseudocode) but gave it a
  module-scoped name to avoid an ambiguous/generic top-level identifier in the `ui.personalcollection`
  package. Same list, same order, same content.
- **`GroupSection` as a `ReorderableCollectionItemScope` extension function**, not a plain private
  composable called with a receiver-less lambda. Verified against the `reorderable:2.4.0` sources jar
  that `ReorderableItem`'s content lambda type is `ReorderableCollectionItemScope.(isDragging: Boolean)
  -> Unit` (extension function type), so `.longPressDraggableHandle()` (an extension on that scope) is
  only callable from inside a matching receiver — an extension function was the correct, minimal way to
  thread that scope through, avoiding Kotlin context-receiver syntax (still experimental) that the
  spec's snippet didn't specify either way.
- Everything else matches the spec signatures/structure as written.

## Build result

```
Set-Location "D:\Claude Projects\PokedexBinderV2"
$env:JAVA_HOME = "D:\jdk17\jdk-17.0.14+7"
& .\gradlew.bat assembleDebug testDebugUnitTest --console=plain
```

- `assembleDebug`: **BUILD SUCCEEDED** (compiles cleanly, only a pre-existing unrelated deprecation
  warning on `LocalLifecycleOwner` in `ScannerScreen.kt` and a Moshi/Kapt deprecation notice).
- `testDebugUnitTest`: **8 failing tests**, all pre-existing and pre-listed as known/unrelated:
  `AssignCardUseCaseTest`, `GeminiCardScannerTest` (×4), `SmartThresholdUseCaseTest`,
  `BinderRepositoryTest`, `CardSearchRepositoryTest`. Verified `CardSearchRepositoryTest`'s failure is
  unrelated to the `releaseDate` field addition — its `TcgSetDto("XY")` positional construction still
  compiles (default param), and the failure is a pre-existing query-string mismatch against the
  `POCKET_EXCLUSION` suffix added in an earlier commit (confirmed via `git log` on the test file: last
  touched in `c02112c`, before this run). No new test failures introduced by this change; 49 of 57
  tests pass (same pass count/tests as before Run 1B, plus the new code has no dedicated tests — Tester
  stage owns that).

## Not done (explicitly out of scope per spec §11)

- No new `ConnectingArtRepository` methods.
- No manual slot deletion in `ConnectingArtViewModel.deleteGroup`.
- No manual owned-state merge in `PersonalCollectionRepository.refreshPokemon` / ViewModel.
- No Room migration / schema version bump.
- No sorting/filtering UI in Personal Collection.
- No changes to Pokédex or Card History behavior.
- No new tests written (Tester stage's responsibility) and the app was not run/launched (also Tester's
  responsibility per task instructions).
