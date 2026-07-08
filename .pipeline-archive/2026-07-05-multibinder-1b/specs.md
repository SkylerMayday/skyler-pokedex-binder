# Run 1B Implementation Plan — Connecting Art & Personal Collection Screens

**Stage:** Planner → hand off to Coder.
**Scope:** Replace the two placeholder screen bodies (`ConnectingArtScreen`, `PersonalCollectionScreen`) with real UI + ViewModels + navigation wiring. Do NOT touch Run 1A infrastructure (entities, DAOs, repositories, migration, drawer) except the two small, explicitly-listed additions below (`releaseDate` threading + a `refreshPokemon` implementation stub that Run 1A left as a TODO).

Ground truth already verified against source. Everything below is signature/structure only — Coder writes the bodies.

---

## 0. Pre-existing infrastructure the Coder MUST reuse (do not reinvent)

- `ConnectingArtRepository` (`@Singleton @Inject`) — already exposes: `observeGroups()`, `observeAllSlots()`, `observeSlots(groupId)`, `createGroup(name, rows, cols)`, `deleteGroup(groupId)`, `reorderGroups(orderedGroupIds: List<Int>)`, `assignCard(slotId, cardId, cardName, cardImageUrl)`, `removeCard(slotId)`, `setOwned(slotId, owned)`. **Use these verbatim. Do not add new repo methods for Connecting Art.**
- `PersonalCollectionRepository` (`@Singleton @Inject`) — already exposes: `observeCache(pokemonKey)`, `observeAllCache()`, `observeEntries()`, `cacheCount()`, `setOwned(cardId, owned)`, `removeOwned(cardId)`, and a **stubbed** `refreshPokemon(pokemonKey, names)` that Run 1A left as `TODO`. **This stub is the one piece of infra Run 1B must implement (see §5).**
- `CardSearchRepository.searchByName(name)` — Pocket filter is enforced inside its private `safeSearch()`. Any call routed through it inherits `-set.series:Pocket` automatically. Reuse this for BOTH the Connecting Art per-slot search and the Personal Collection population.
- `ManualSearchScreen(onCardSelected: (TcgCard) -> Unit, onBack: () -> Unit, viewModel = hiltViewModel())` — reuse **as-is** for Connecting Art slot assignment. Do not fork or copy it. `ManualSearchViewModel` is `@HiltViewModel` and self-contained; a fresh `hiltViewModel()` instance is created per route entry, so no shared state concerns.
- Drag-reorder library: `sh.calvin.reorderable` (v2.4.0, already a dependency). Pattern to copy: `SecondaryBinderScreen.kt` — `rememberReorderableLazyGridState` / `ReorderableItem` / `.longPressDraggableHandle()`. For Connecting Art the reorder unit is a **group section (a whole row in a LazyColumn)**, not a grid cell, so use `rememberReorderableLazyListState` (list variant) instead of the grid variant.
- Greyed/dimmed overlay: **no existing helper** — Coder must add one (see §6, shared `DimmableCardImage`). MainBinder/Secondary just render `AsyncImage` at full colour; there is no prior desaturation code to copy.
- Cascade delete: `connecting_art_slot` has `ForeignKey(onDelete = CASCADE)` on `groupId`, and `ConnectingArtDao.deleteGroupById` relies on it. **Deleting a group requires zero manual slot cleanup in the ViewModel** — just call `repo.deleteGroup(groupId)`.

---

## 1. Files to CREATE

| Path | Purpose |
|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/ui/connectingart/ConnectingArtViewModel.kt` | State + events for the Connecting Art binder |
| `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionViewModel.kt` | State + events for the Personal Collection binder |
| `app/src/main/java/com/skyler/pokedexbinder/ui/common/DimmableCardImage.kt` | Shared desaturating card-image composable (used by both screens) |

## 2. Files to EDIT

| Path | Change |
|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/ui/connectingart/ConnectingArtScreen.kt` | Replace placeholder body with real UI; add `onOpenSearch` callback param |
| `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionScreen.kt` | Replace placeholder body with real UI (self-contained, no extra callbacks) |
| `app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt` | Add a `ConnectingArtSearch` sub-route; wire slot-assign search flow; pass new callback into `ConnectingArtScreen` |
| `app/src/main/java/com/skyler/pokedexbinder/repository/PersonalCollectionRepository.kt` | Implement the `refreshPokemon` stub (fetch + map + `replaceCacheForPokemon`) |
| `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt` | Add `releaseDate: String?` to `TcgSetDto` (needed for cache sort) |
| `app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt` | Add `setReleaseDate: String? = null` (threads release date to caller) |
| `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt` | Map `set.releaseDate` → `TcgCard.setReleaseDate` in `TcgCardDto.toDomain()` |

**Migration note:** No schema/migration change. `TcgSetDto.releaseDate` is a network DTO field only (Moshi, nullable — safe if the API omits it). `TcgCard.setReleaseDate` is an in-memory domain field. `PersonalCollectionCache.releaseDate` already exists in the Room entity from Run 1A. Nothing touches Room's schema, so no version bump.

---

## 3. ConnectingArtViewModel (new)

Package `com.skyler.pokedexbinder.ui.connectingart`. Mirror `SecondaryBinderViewModel` structure (constructor-inject the repo, `init` collects flows into `MutableStateFlow`, `viewModelScope.launch` for writes).

```kotlin
data class ConnectingArtUiState(
    val groups: List<ConnectingArtGroup> = emptyList(),
    val slotsByGroup: Map<Int, List<ConnectingArtSlot>> = emptyMap()  // groupId -> slots (already sorted by slotIndex)
)

@HiltViewModel
class ConnectingArtViewModel @Inject constructor(
    private val repository: ConnectingArtRepository
) : ViewModel() {

    val uiState: StateFlow<ConnectingArtUiState>
    // Build by combine(repository.observeGroups(), repository.observeAllSlots()) { groups, slots ->
    //   ConnectingArtUiState(groups, slots.groupBy { it.groupId }) }
    //   .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectingArtUiState())

    // Tracks which slot the user is currently assigning a card to, so AppNavigation's
    // search result knows the target. Set on tapping an empty slot, cleared after assign.
    private val _pendingAssignSlotId = MutableStateFlow<Int?>(null)
    val pendingAssignSlotId: StateFlow<Int?>

    fun beginAssign(slotId: Int)                 // sets _pendingAssignSlotId
    fun cancelAssign()                           // clears it (on search back)
    fun assignPendingCard(card: TcgCard)         // reads pending id, calls repo.assignCard(id, card.id, card.name, card.imageUrl), clears pending
    fun createGroup(name: String, rows: Int, cols: Int)   // repo.createGroup(...)
    fun deleteGroup(groupId: Int)                // repo.deleteGroup(...) — cascade handles slots
    fun reorderGroups(fromIndex: Int, toIndex: Int)  // reorder local group list, then repo.reorderGroups(orderedIds)
    fun setOwned(slotId: Int, owned: Boolean)    // repo.setOwned(...)
    fun removeCard(slotId: Int)                  // repo.removeCard(...)
}
```

Imports: `com.skyler.pokedexbinder.data.local.ConnectingArtGroup`, `ConnectingArtSlot`, `com.skyler.pokedexbinder.data.model.TcgCard`, `ConnectingArtRepository`, `kotlinx.coroutines.flow.*`.

**Reorder detail:** copy `SecondaryBinderViewModel.reorder`'s optimistic pattern is NOT directly usable because groups come from a combined flow (can't locally mutate a derived StateFlow cleanly). Instead: on reorder, compute the new ordered id list from current `uiState.value.groups`, apply `add(to, removeAt(from))` to a local `MutableList<Int>` of ids, then `viewModelScope.launch { repository.reorderGroups(ids) }`. The flow re-emits with updated `position`, so no manual optimistic state needed. If drag feels laggy in testing, add an optional local override StateFlow — but start without it.

---

## 4. ConnectingArtScreen (edit)

New signature:
```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectingArtScreen(
    onOpenDrawer: () -> Unit,
    onOpenSlotSearch: (slotId: Int) -> Unit,           // navigates to ConnectingArtSearch route
    viewModel: ConnectingArtViewModel = hiltViewModel()
)
```

Structure:
- `Scaffold`:
  - `topBar` = existing `TopAppBar("Connecting Art")` with hamburger `onOpenDrawer` (keep as-is).
  - `floatingActionButton` = `FloatingActionButton(Icons.Default.Add)` → `showCreateSheet = true`.
- Body:
  - **Empty state** (groups empty): centered `Text("No groups yet — tap + to create one")` (mirror SecondaryBinderScreen empty copy).
  - **List**: `LazyColumn` with `rememberReorderableLazyListState(listState) { from, to -> viewModel.reorderGroups(from.index, to.index) }`. `items(groups, key = { it.id })` → `ReorderableItem(reorderState, key = group.id) { GroupSection(...) }`.
- `GroupSection(group, slots, ...)` private composable:
  - Header `Row`: group name `Text` (`titleMedium`) + a drag handle affordance (apply `.longPressDraggableHandle()` to the header Row or an explicit drag `Icon`), + `IconButton(Icons.Default.Delete)` → `groupPendingDelete = group` (confirm dialog).
  - Grid: **do NOT nest a scrollable `LazyVerticalGrid` inside the `LazyColumn`** (nested-scroll crash). Instead render the fixed `rows × cols` grid with plain `Column { repeat(rows) { Row { repeat(cols) { SlotTile(...) } } } }`, row-major indexing `slotIndex = r * cols + c`. Each `SlotTile` uses `Modifier.weight(1f).aspectRatio(0.72f)` so columns share width. Look up the slot for an index from the `slots` list (already sorted by `slotIndex`).
- `SlotTile(slot, onEmptyTap, onAssignedTap)`:
  - **Empty** (`slot.cardId == null`): `Card` with centered `Icon(Icons.Default.Add)` placeholder → `onEmptyTap()` calls `viewModel.beginAssign(slot.id)` then `onOpenSlotSearch(slot.id)`.
  - **Assigned**: `DimmableCardImage(imageUrl = slot.cardImageUrl, dimmed = !slot.owned)` (§6). Tap → `slotSheetTarget = slot` (opens slot bottom sheet).
- **Create-group bottom sheet** (`ModalBottomSheet`, shown when `showCreateSheet`):
  - `OutlinedTextField` for group name (state hoisted with `remember`).
  - Grid-preset picker: a `Row`/`FlowRow` of `FilterChip`s, one per preset, labelled `"1×2"`,`"1×3"`,`"1×4"`,`"2×2"`,`"3×3"`. Store presets as a local `val presets = listOf(1 to 2, 1 to 3, 1 to 4, 2 to 2, 3 to 3)` (rows to cols). Selected chip tracked in `remember { mutableStateOf(presets.first()) }`.
  - "Create" `Button` (enabled only when name non-blank) → `viewModel.createGroup(name.trim(), selected.first, selected.second)`; dismiss sheet; reset fields.
- **Slot bottom sheet** (`ModalBottomSheet`, shown when `slotSheetTarget != null`) — matches spec §Slot States:
  - If `slot.owned == false`: buttons `"Mark as Owned"` → `viewModel.setOwned(slot.id, true)`; `"Remove Card"` → `viewModel.removeCard(slot.id)`; `"Back"` → dismiss.
  - If `slot.owned == true`: buttons `"Mark as Unowned"` → `viewModel.setOwned(slot.id, false)`; `"Remove Card"`; `"Back"`.
  - Each action dismisses the sheet.
- **Delete-group confirm**: `AlertDialog` when `groupPendingDelete != null` → confirm calls `viewModel.deleteGroup(group.id)`. (Cascade cleans slots; ViewModel does no manual slot deletion.)

Edge cases to handle explicitly: empty group list; blank group name disables Create; tapping an assigned slot never re-opens search; long-press drag only on group header, not on slot tiles (slot tiles are plain-clickable).

---

## 5. PersonalCollectionRepository.refreshPokemon — implement the stub (edit)

Current body is a `TODO`. Implement:

```kotlin
suspend fun refreshPokemon(pokemonKey: String, names: List<String>) {
    // For each name: cardSearchRepository.searchByName(name)  (Pocket filter inherited)
    // Merge all results across names (dedupe by card.id).
    // Map each TcgCard -> PersonalCollectionCache(
    //     cardId = card.id, pokemonKey = pokemonKey, name = card.name,
    //     imageUrl = card.imageUrl, setName = card.setName,
    //     releaseDate = card.setReleaseDate ?: "")
    // dao.replaceCacheForPokemon(pokemonKey, mapped)   // atomic delete+upsert for THIS key only
}
```

Notes:
- `replaceCacheForPokemon` deletes only rows for `pokemonKey`, so refreshing one Pokémon never wipes others. Cross-key isolation is already guaranteed by the DAO.
- **Owned state is untouched by refresh** — `owned` lives in the separate `personal_collection_entry` table keyed by `cardId`, which `refreshPokemon` never deletes. A re-fetch that returns the same `cardId` leaves its entry intact; new cards simply have no entry (→ unowned). This satisfies the spec's "only adds new cards, doesn't wipe owned state". **No new merge logic needed in the ViewModel — the two-table split already gives merge-preserving-owned for free.** Coder should NOT add a manual merge.
- `searchByName` returns TCGdex-merged results too; those have `id` like `tcgdex_...` and `setReleaseDate == null` → `releaseDate = ""`. Acceptable; they sort last (empty string < dates in `ORDER BY releaseDate DESC`). Do not special-case.

Blank `releaseDate` risk: the DAO sorts `ORDER BY releaseDate DESC`. Empty strings sort to the bottom (newest-first ordering preserved for dated cards). Acceptable per spec ("newest first"); no extra handling required.

---

## 6. Shared DimmableCardImage (new)

`app/src/main/java/com/skyler/pokedexbinder/ui/common/DimmableCardImage.kt`:

```kotlin
@Composable
fun DimmableCardImage(
    imageUrl: String?,
    contentDescription: String?,
    dimmed: Boolean,
    modifier: Modifier = Modifier
)
```
- Renders `AsyncImage(model = imageUrl, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize())`.
- When `dimmed`: apply desaturation via `colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })` AND a dark scrim (`Box` overlay `background(Color.Black.copy(alpha = 0.45f))`) so unowned reads clearly as greyed-out. When not dimmed: full colour, no filter, no scrim.
- Used by both Connecting Art `SlotTile` (dimmed = `!owned`) and Personal Collection card cells (dimmed = `!owned`).

Rationale (record as a rationale node): a single desaturate-scrim helper keeps the "unowned = greyed" visual identical across both new binders and any future one, instead of duplicating `ColorMatrix` logic per screen.

---

## 7. PersonalCollectionViewModel (new)

Package `com.skyler.pokedexbinder.ui.personalcollection`. Mirror the flow-collection pattern.

```kotlin
// Fixed section definitions — the 5 Pokémon in spec order. Keep as a companion constant.
data class PokemonSection(val key: String, val title: String, val queryNames: List<String>)

val SECTIONS = listOf(
    PokemonSection("charizard", "Charizard", listOf("Charizard")),
    PokemonSection("celebi", "Celebi", listOf("Celebi")),
    PokemonSection("leafeon", "Leafeon", listOf("Leafeon")),
    PokemonSection("tangela", "Tangela", listOf("Tangela")),
    PokemonSection("minccino_cinccino", "Minccino & Cinccino", listOf("Minccino", "Cinccino")),
)

data class PersonalCard(
    val cardId: String,
    val name: String,
    val imageUrl: String,
    val owned: Boolean
)

data class PersonalCollectionUiState(
    val sections: Map<String, List<PersonalCard>> = emptyMap(),  // pokemonKey -> cards (sorted newest-first by cache order)
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class PersonalCollectionViewModel @Inject constructor(
    private val repository: PersonalCollectionRepository
) : ViewModel() {

    val uiState: StateFlow<PersonalCollectionUiState>
    // combine(repository.observeAllCache(), repository.observeEntries(), _isRefreshing, _error) { cache, entries, refreshing, err ->
    //   val ownedIds = entries.filter { it.owned }.map { it.cardId }.toSet()
    //   val grouped = cache.groupBy { it.pokemonKey }.mapValues { (_, rows) ->
    //     rows.map { PersonalCard(it.cardId, it.name, it.imageUrl, it.cardId in ownedIds) } }  // cache flow already ORDER BY releaseDate DESC
    //   PersonalCollectionUiState(grouped, refreshing, err) }

    init {
        // First-load population: if repository.cacheCount() == 0, call refreshAll().
        viewModelScope.launch { if (repository.cacheCount() == 0) refreshAll() }
    }

    fun refreshAll() {
        // set _isRefreshing = true; try { SECTIONS.forEach { repository.refreshPokemon(it.key, it.queryNames) } }
        // catch -> _error; finally _isRefreshing = false
    }

    fun toggleOwned(cardId: String, currentlyOwned: Boolean) {
        // if currentlyOwned -> repository.removeOwned(cardId) else repository.setOwned(cardId, true)
    }
}
```

`observeAllCache()` already sorts `ORDER BY releaseDate DESC`, so `groupBy` preserves newest-first within each section. Do not re-sort in the ViewModel.

Owned toggle semantics: `personal_collection_entry` uses "row exists AND owned=true" = owned. Simplest is `setOwned(cardId, true)` to own and `removeOwned(cardId)` to un-own (deletes the row). Use that; `setOwned(cardId, false)` would also work but leaves dead rows — prefer `removeOwned` for un-own.

---

## 8. PersonalCollectionScreen (edit)

Keep signature `fun PersonalCollectionScreen(onOpenDrawer: () -> Unit, viewModel: PersonalCollectionViewModel = hiltViewModel())` — no extra callbacks (population is internal).

Structure:
- `Scaffold` topBar unchanged (hamburger).
- Body wrapped in `PullToRefreshBox` (material3 1.3.1, from Compose BOM 2024.12.01 — confirmed available; do NOT use accompanist):
  ```kotlin
  PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = { viewModel.refreshAll() }) { ... }
  ```
- Inside: `LazyVerticalGrid(columns = GridCells.Fixed(3))`. For each section in `SECTIONS` order:
  - Sticky header via `item(span = { GridItemSpan(maxLineSpan) }) { Text(section.title, style = titleMedium) }` — LazyVerticalGrid has no `stickyHeader`; a full-span header item is the correct pattern here. Skip a section's header+cards if that section has zero cached cards AND not refreshing (avoids empty headers on first load), OR always show header with a "loading…" placeholder while `isRefreshing && cards empty`.
  - `items(cards, key = { it.cardId })` → card cell: `Card { DimmableCardImage(it.imageUrl, it.name, dimmed = !it.owned, ...) }`, `aspectRatio(0.72f)`, `clickable { showSheetFor = it }`.
- **Slot bottom sheet** (`ModalBottomSheet` when a card is tapped) — spec §Slot Behaviour ("Add Card"/"Remove Card"/"Back"):
  - If `!card.owned`: `"Add Card"` → `viewModel.toggleOwned(card.cardId, false)`; `"Back"`.
  - If `card.owned`: `"Remove Card"` → `viewModel.toggleOwned(card.cardId, true)`; `"Back"`.
  - (No image-search here — cards are API-populated, ownership is the only mutation.)
- First-load feedback: while `isRefreshing` and everything empty, show a centered `CircularProgressIndicator` above/instead of the grid so the user isn't staring at a blank screen during the initial network fetch. Show `errorMessage` (if any) with a retry `Button` → `refreshAll()`.

Edge cases: empty cache on first open triggers auto `refreshAll()` in `init`; pull-to-refresh only ADDS new cards and preserves owned (guaranteed by two-table split, §5); network failure surfaces `errorMessage` + retry, does not crash.

---

## 9. AppNavigation changes (edit)

1. Add a sub-route to the `Screen` sealed class for the Connecting Art per-slot search, carrying the target `slotId`:
   ```kotlin
   object ConnectingArtSearch : Screen("connecting_art_search/{slotId}") {
       fun createRoute(slotId: Int) = "connecting_art_search/$slotId"
   }
   ```
2. Update the existing `Screen.ConnectingArt.route` composable to pass the new callback:
   ```kotlin
   composable(Screen.ConnectingArt.route) {
       ConnectingArtScreen(
           onOpenDrawer = { openDrawer() },
           onOpenSlotSearch = { slotId ->
               navController.navigate(Screen.ConnectingArtSearch.createRoute(slotId))
           }
       )
   }
   ```
3. Add the search composable. **ViewModel sharing:** the assign target + `assignPendingCard` live on `ConnectingArtViewModel`. The search route must obtain the SAME `ConnectingArtViewModel` instance as the list screen so `assignPendingCard` writes to the right slot. Scope it to the `Screen.ConnectingArt.route` back-stack entry:
   ```kotlin
   composable(
       route = Screen.ConnectingArtSearch.route,
       arguments = listOf(navArgument("slotId") { type = NavType.IntType })
   ) { backStack ->
       val parentEntry = remember(backStack) { navController.getBackStackEntry(Screen.ConnectingArt.route) }
       val artVm: ConnectingArtViewModel = hiltViewModel(parentEntry)
       ManualSearchScreen(
           onCardSelected = { card ->
               artVm.assignPendingCard(card)
               navController.popBackStack()
           },
           onBack = { artVm.cancelAssign(); navController.popBackStack() }
       )
   }
   ```
   `ManualSearchScreen`'s own `viewModel` param defaults to a fresh `ManualSearchViewModel` via `hiltViewModel()` — leave it defaulted; only the assign target comes from the shared `ConnectingArtViewModel`.
   - Because `beginAssign(slotId)` is called in the screen before navigating AND `slotId` is also passed as a nav arg, the nav arg is the source of truth for robustness against process death: on `assignPendingCard`, prefer the arg-carried slotId if `_pendingAssignSlotId` is null. Simplest: have `ConnectingArtScreen.onOpenSlotSearch` call `beginAssign` (sets the StateFlow) and rely on it; the nav arg is belt-and-suspenders. Coder: keep `beginAssign` as the primary path; the nav arg guarantees the route is addressable and could seed the pending id if needed.
4. Add imports: `ConnectingArtViewModel`, `androidx.navigation.NavType` (already imported), `androidx.compose.runtime.remember` (already imported via `*`).

No change to the drawer, bottom bar, start destination, or the `PersonalCollection` composable signature (it stays `PersonalCollectionScreen(onOpenDrawer = { openDrawer() })`).

---

## 10. Verification checklist for the Tester stage

- Project compiles (`./gradlew :app:assembleDebug`).
- Connecting Art: create a group with each preset → correct `rows×cols` empty tiles appear, row-major.
- Empty slot tap → search → select card → slot shows dimmed image; slot sheet "Mark as Owned" → full colour; "Mark as Unowned" → dimmed; "Remove Card" → empty tile again.
- Delete group → group + all its slots gone (confirm no orphan `connecting_art_slot` rows; cascade).
- Group long-press drag reorders and persists across app restart.
- Personal Collection: first open with empty cache auto-populates 5 sections; Minccino & Cinccino merged under one header; cards dimmed until "Add Card"; owned persists.
- Pull-to-refresh re-syncs, preserves owned state, adds any new cards.
- Network failure on refresh → error + retry, no crash.
- Existing screens (Pokédex, Card History, Scan, Settings) unaffected.

## 11. Explicit non-goals (do not do)

- No new `ConnectingArtRepository` methods (all needed ones exist).
- No manual slot deletion in ViewModel (cascade handles it).
- No manual owned-state merge in Personal Collection (two-table split handles it).
- No Room migration / version bump (no schema change).
- No sorting/filtering UI in Personal Collection (out of scope per spec).
- No changes to Pokédex or Card History behaviour.
