# Implementation Plan — Unown Binder + TCGCSV Search Fallback

Stage 1 (Planner) output. Source spec: `docs/superpowers/specs/2026-07-10-unown-binder-and-tcgcsv-fallback.md` (APPROVED).
Coder implements EXACTLY what is below. All paths relative to repo root; package root is
`app/src/main/java/com/skyler/pokedexbinder/`.

---

## 0. Resolutions of spec-vs-codebase mismatches (READ FIRST)

The spec made three assumptions that do not match the actual code. Resolved here so the Coder has zero ambiguity.

### 0.1 Main binder's empty-slot flow is QuickScan, NOT ManualSearch — Unown uses the ManualSearch/Connecting-Art pattern instead
The spec says "mirror the main Pokédex binder" AND "reuse `ManualSearchScreen` … wire TCGCSV into `ManualSearchViewModel.retry()`". These conflict: the main Pokédex binder's empty-slot tap navigates to `QuickScanScreen` (auto-search by slot name, assigns to `main_binder` via `AssignCardUseCase`), which is hardwired to the main binder and does **not** use `ManualSearchViewModel`. `ManualSearchViewModel` is used only by Connecting Art search and Add-to-Secondary.

**Resolution:** Unown mirrors the **Connecting Art** interaction pattern (grid + `ManualSearchScreen` + a parent-scoped ViewModel holding a "pending assign" target), NOT the QuickScan flow. This is what makes the spec's stated TCGCSV trigger point (`ManualSearchViewModel.retry()`) actually reachable from Unown. The single-card-per-slot assignment mechanic (spec's real requirement) is preserved. Do NOT touch `QuickScanViewModel`/`QuickScanScreen`.

### 0.2 TCGCSV has NO name-search endpoint — this is the biggest deviation
Verified live against tcgcsv.com during planning. The API only exposes:
- `GET https://tcgcsv.com/tcgplayer/3/groups` → all Pokémon groups (category 3 = Pokémon; 217 groups as of planning).
- `GET https://tcgcsv.com/tcgplayer/3/{groupId}/products` → all products in one group.

There is **no** server-side name query. `searchTcgcsvByName(name)` must therefore: fetch the groups list, fetch products across groups, and filter by name client-side. See §7 for the exact bounded-fan-out implementation. **This is retry-on-empty only, so the cost is paid rarely and is wrapped in a non-throwing try/catch — acceptable per spec's "live fetch only" constraint, but the Coder MUST verify on-device latency is tolerable (§10 P0).**

Exact verified shapes:
- Groups root: `{ "totalItems": Int, "success": Bool, "errors": [], "results": [ Group ] }`
  - Group: `{ groupId: Int, name: String, abbreviation: String, isSupplemental: Bool, publishedOn: String, modifiedOn: String, categoryId: Int }`
- Products root: same envelope, `results: [ Product ]`
  - Product: `{ productId: Int, name: String, cleanName: String, imageUrl: String, categoryId: Int, groupId: Int, url: String, modifiedOn: String, imageCount: Int, presaleInfo: {...}, extendedData: [ { name: String, displayName: String, value: String } ] }`
  - Card number lives in `extendedData` where `name == "Number"` (e.g. value `"003/034"`). May be absent → treat as `""`.
  - `imageUrl` example: `https://tcgplayer-cdn.tcgplayer.com/product/527885_200w.jpg` (use as-is).

### 0.3 Special-character letter IDs ("!", "?") — no escaping needed anywhere
- Room string PK: `"!"` / `"?"` are ordinary TEXT values, safe as primary keys.
- `binder.json` (Moshi): serialized as normal JSON string values, safe.
- URLs: letter IDs are never placed in a path/query. The pokemontcg.io search uses the text query `"Unown A"` (Retrofit `@Query` URL-encodes it). No manual escaping required.
- `slotId` collision: Unown slotIds are single chars (`A`..`Z`,`!`,`?`); Pokédex slotIds are multi-char pokemon IDs (`bulbasaur`, `af_castform_normal`). No collision in `computeDiff`'s flat slotId map. Documented, no action needed.

---

## 1. New/modified file inventory

**Create (10):**
1. `data/local/UnownBinderEntry.kt`
2. `data/local/UnownBinderDao.kt`
3. `repository/UnownBinderRepository.kt`
4. `data/remote/TcgcsvApi.kt`
5. `data/remote/TcgcsvDto.kt`
6. `ui/unown/UnownBinderViewModel.kt`
7. `ui/unown/UnownBinderScreen.kt`
8. `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration7to8Test.kt`
9. `app/src/test/java/com/skyler/pokedexbinder/ui/ManualSearchViewModelTest.kt`
10. (test additions live in existing files, see §10)

**Modify (11):**
1. `data/local/PokedexDatabase.kt` — entity, version 8, MIGRATION_7_8, dao accessor
2. `di/DatabaseModule.kt` — addMigrations, provideUnownBinderDao
3. `di/NetworkModule.kt` — tcgcsv Retrofit + provideTcgcsvApi
4. `repository/CardSearchRepository.kt` — tcgcsvApi dep + `searchTcgcsvByName`
5. `ui/manualsearch/ManualSearchViewModel.kt` — retry-on-empty TCGCSV fallback
6. `ui/manualsearch/ManualSearchScreen.kt` — optional `initialQuery` + empty-results "search more sources" button
7. `ui/navigation/AppNavigation.kt` — Screen.Unown, Screen.UnownSearch, drawer item, routes
8. `repository/PublishSettingsRepository.kt` — publishUnown key + PublishConfig field
9. `publish/PublishRepository.kt` — BINDER_ID_UNOWN, unown dep, buildSnapshot block, publish() fetch
10. `publish/RestoreRepository.kt` — BINDER_ID_UNOWN overlay
11. `ui/settings/SettingsViewModel.kt` + `ui/settings/SettingsScreen.kt` — publishUnown toggle

**Test files to update (3):** `CardSearchRepositoryTest.kt`, `PublishRepositoryTest.kt`, `RestoreRepositoryTest.kt` (constructor/signature changes + new cases). Details in §10.

---

## 2. Data layer — Unown entity, DAO, repository

### 2.1 `data/local/UnownBinderEntry.kt` (CREATE)
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unown_binder")
data class UnownBinderEntry(
    @PrimaryKey val letterId: String,       // "A".."Z", "!", "?" — 28 fixed IDs; doubles as display label
    val position: Int,                       // 0..27 stable grid ordering (A=0..Z=25, !=26, ?=27)
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null,
    val assignedCardName: String? = null,
    val assignedCardSetName: String? = null
)
```
Column order (constructor order) is authoritative for the migration DDL and the migration test's `PRAGMA table_info` assertion: `letterId, position, assignedCardId, assignedCardImageUrl, assignedCardName, assignedCardSetName`.

Deliberately NO rarity/language/versionName columns (spec §Design — the 2026-07-08 card-details spec was dropped).

### 2.2 `data/local/UnownBinderDao.kt` (CREATE)
Mirror `MainBinderDao`'s core shape (observe all, get by id, upsert, insertAll REPLACE, count).
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UnownBinderDao {
    @Query("SELECT * FROM unown_binder ORDER BY position ASC")
    fun observeAll(): Flow<List<UnownBinderEntry>>

    @Query("SELECT * FROM unown_binder ORDER BY position ASC")
    suspend fun getAll(): List<UnownBinderEntry>

    @Query("SELECT * FROM unown_binder WHERE letterId = :letterId")
    suspend fun getByLetterId(letterId: String): UnownBinderEntry?

    @Upsert
    suspend fun upsert(entry: UnownBinderEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<UnownBinderEntry>)

    @Query("SELECT COUNT(*) FROM unown_binder")
    suspend fun count(): Int
}
```

### 2.3 `repository/UnownBinderRepository.kt` (CREATE)
Mirror `BinderRepository`'s seed/assign/clear pattern (§seedIfEmpty uses a hardcoded list, no JSON asset).
```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.UnownBinderDao
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnownBinderRepository @Inject constructor(
    private val unownBinderDao: UnownBinderDao
) {
    fun observeEntries(): Flow<List<UnownBinderEntry>> = unownBinderDao.observeAll()

    suspend fun getAllEntries(): List<UnownBinderEntry> = unownBinderDao.getAll()

    /** Inserts all 28 empty letter rows the first time the table is empty. Idempotent. */
    suspend fun seedIfEmpty() {
        if (unownBinderDao.count() > 0) return
        unownBinderDao.insertAll(LETTER_IDS.mapIndexed { index, id ->
            UnownBinderEntry(letterId = id, position = index)
        })
    }

    suspend fun assignCard(
        letterId: String,
        cardId: String,
        cardImageUrl: String,
        cardName: String?,
        cardSetName: String?
    ) {
        val existing = unownBinderDao.getByLetterId(letterId) ?: return
        unownBinderDao.upsert(
            existing.copy(
                assignedCardId = cardId,
                assignedCardImageUrl = cardImageUrl,
                assignedCardName = cardName,
                assignedCardSetName = cardSetName
            )
        )
    }

    suspend fun clearCard(letterId: String) {
        val existing = unownBinderDao.getByLetterId(letterId) ?: return
        unownBinderDao.upsert(
            existing.copy(
                assignedCardId = null,
                assignedCardImageUrl = null,
                assignedCardName = null,
                assignedCardSetName = null
            )
        )
    }

    /** Overwrites all rows (restore path — mirrors BinderRepository.seedFromJson). */
    suspend fun overwriteAll(entries: List<UnownBinderEntry>) = unownBinderDao.insertAll(entries)

    companion object {
        /** 28 fixed letter IDs: A..Z, then "!", then "?". Ordering defines grid position. */
        val LETTER_IDS: List<String> =
            ('A'..'Z').map { it.toString() } + listOf("!", "?")

        /** The TCG search query for a given letter, e.g. "Unown A". */
        fun searchNameFor(letterId: String): String = "Unown $letterId"
    }
}
```

---

## 3. Room migration (v7 → v8)

### 3.1 `data/local/PokedexDatabase.kt` (MODIFY)
- Add `UnownBinderEntry::class` to the `entities = [...]` list.
- Change `version = 7` → `version = 8`.
- Add abstract accessor: `abstract fun unownBinderDao(): UnownBinderDao`
- Add `MIGRATION_7_8` in the companion object (after `MIGRATION_6_7`), verbatim DDL (column order matches §2.1):
```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `unown_binder` (" +
                "`letterId` TEXT NOT NULL PRIMARY KEY, " +
                "`position` INTEGER NOT NULL, " +
                "`assignedCardId` TEXT, " +
                "`assignedCardImageUrl` TEXT, " +
                "`assignedCardName` TEXT, " +
                "`assignedCardSetName` TEXT)"
        )
    }
}
```

### 3.2 `di/DatabaseModule.kt` (MODIFY)
- Add `PokedexDatabase.MIGRATION_7_8` to the `.addMigrations(...)` chain (after `MIGRATION_6_7`).
- Add provider:
```kotlin
@Provides
fun provideUnownBinderDao(db: PokedexDatabase): UnownBinderDao = db.unownBinderDao()
```

Seed-on-first-launch: `UnownBinderRepository.seedIfEmpty()` is invoked from `UnownBinderViewModel.init` (§5.1), matching how `MainBinderViewModel.init` seeds `main_binder`. Both the fresh-install path (`fallbackToDestructiveMigration` builds the table from the entity) and the upgrade path (MIGRATION_7_8 creates the empty table) end with an empty table that `seedIfEmpty` fills.

---

## 4. Card search — TCGCSV fallback

### 4.1 `data/remote/TcgcsvDto.kt` (CREATE)
```kotlin
package com.skyler.pokedexbinder.data.remote

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TcgcsvGroupsResponse(
    val results: List<TcgcsvGroupDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TcgcsvGroupDto(
    val groupId: Int,
    val name: String
)

@JsonClass(generateAdapter = true)
data class TcgcsvProductsResponse(
    val results: List<TcgcsvProductDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TcgcsvProductDto(
    val productId: Int,
    val name: String,
    val cleanName: String? = null,
    val imageUrl: String? = null,
    val groupId: Int,
    val extendedData: List<TcgcsvExtendedDataDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TcgcsvExtendedDataDto(
    val name: String,
    val value: String
)
```
Only the fields we consume are declared; Moshi ignores unknown JSON keys by default. `@JsonClass(generateAdapter = true)` matches the codebase convention (see `BinderSnapshot.kt`).

### 4.2 `data/remote/TcgcsvApi.kt` (CREATE)
```kotlin
package com.skyler.pokedexbinder.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

interface TcgcsvApi {
    @GET("tcgplayer/3/groups")
    suspend fun getGroups(): TcgcsvGroupsResponse

    @GET("tcgplayer/3/{groupId}/products")
    suspend fun getProducts(@Path("groupId") groupId: Int): TcgcsvProductsResponse
}
```
Category `3` is hardcoded (Pokémon). Base URL `https://tcgcsv.com/` set in DI.

### 4.3 `di/NetworkModule.kt` (MODIFY)
Add after the tcgdex providers, mirroring their exact `@Named` pattern:
```kotlin
@Provides
@Singleton
@Named("tcgcsv")
fun provideTcgcsvRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
    .baseUrl("https://tcgcsv.com/")
    .client(okHttp)
    .addConverterFactory(MoshiConverterFactory.create(moshi))
    .build()

@Provides
@Singleton
fun provideTcgcsvApi(@Named("tcgcsv") retrofit: Retrofit): TcgcsvApi =
    retrofit.create(TcgcsvApi::class.java)
```

### 4.4 `repository/CardSearchRepository.kt` (MODIFY)
- Add constructor dependency `private val tcgcsvApi: TcgcsvApi` (third param, after `tcgdexApi`).
- Add imports for the TCGCSV DTOs and `kotlinx.coroutines.*` helpers already present.
- Add the new method + private mapper. See §7 for the full bounded-fan-out body.

New public method signature:
```kotlin
suspend fun searchTcgcsvByName(name: String): List<TcgCard>
```

---

## 5. Unown UI (ViewModel + Screen)

### 5.1 `ui/unown/UnownBinderViewModel.kt` (CREATE)
Mirrors `ConnectingArtViewModel`'s pending-assign pattern (§0.1). Seeds on init like `MainBinderViewModel`.
```kotlin
package com.skyler.pokedexbinder.ui.unown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UnownBinderViewModel @Inject constructor(
    private val repository: UnownBinderRepository
) : ViewModel() {

    val entries: StateFlow<List<UnownBinderEntry>> =
        repository.observeEntries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Which letter slot is currently being assigned (set on empty-slot tap / reassign,
    // read by AppNavigation's search result). Mirrors ConnectingArtViewModel.
    private val _pendingAssignLetterId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
    }

    fun beginAssign(letterId: String) { _pendingAssignLetterId.value = letterId }

    fun cancelAssign() { _pendingAssignLetterId.value = null }

    fun assignPendingCard(card: TcgCard) {
        val letterId = _pendingAssignLetterId.value ?: return
        viewModelScope.launch {
            repository.assignCard(letterId, card.id, card.imageUrl, card.name, card.setName)
        }
        _pendingAssignLetterId.value = null
    }

    fun clearCard(letterId: String) {
        viewModelScope.launch { repository.clearCard(letterId) }
    }
}
```

### 5.2 `ui/unown/UnownBinderScreen.kt` (CREATE)
Structure mirrors `MainBinderScreen`'s grid + `ConnectingArtScreen`'s empty/filled slot interaction and `SlotActionSheet`. Column count: `GridCells.Fixed(4)` (spec leaves exact count to implementer; 4 chosen for 28 slots = 7 rows).

Requirements:
- `TopAppBar(title = "Unown")` with a `Menu` navigation icon calling `onOpenDrawer`.
- `LazyVerticalGrid(columns = GridCells.Fixed(4), contentPadding = PaddingValues(8.dp))` over `entries`.
- Each tile: `Card` with `aspectRatio(0.72f)` (same as `MainBinderScreen.SlotCard`).
  - Empty (`assignedCardId == null`): show the letter label (`entry.letterId`) centered; `clickable { onEmptySlotTap(entry.letterId) }`.
  - Filled: `AsyncImage(model = entry.assignedCardImageUrl, contentScale = ContentScale.Crop)`, `clickable { assignedSheetTarget = entry }`.
- Filled-slot tap opens a `ModalBottomSheet` (mirror `ConnectingArtScreen.SlotActionSheet`, minus the owned toggle) with two actions:
  - **Reassign** → `onEmptySlotTap(entry.letterId)` (same begin-assign+navigate path), dismiss sheet.
  - **Remove Card** → `viewModel.clearCard(entry.letterId)`, dismiss sheet.

Composable signature:
```kotlin
@Composable
fun UnownBinderScreen(
    onOpenDrawer: () -> Unit,
    onOpenSlotSearch: (letterId: String) -> Unit,
    viewModel: UnownBinderViewModel = hiltViewModel()
)
```
`onEmptySlotTap(letterId)` body = `{ viewModel.beginAssign(letterId); onOpenSlotSearch(letterId) }`.

---

## 6. Navigation wiring — `ui/navigation/AppNavigation.kt` (MODIFY)

### 6.1 `Screen` sealed class — add two objects
```kotlin
object Unown : Screen("unown")
object UnownSearch : Screen("unown_search/{letterId}") {
    fun createRoute(letterId: String): String {
        val enc = StandardCharsets.UTF_8.toString()
        return "unown_search/" + URLEncoder.encode(letterId, enc).replace("+", "%20")
    }
}
```
`URLEncoder` handles `!` (→ `%21`) and `?` (→ `%3F`) so the nav route arg is safe; decode is automatic via `backStack.arguments?.getString("letterId")` (NavType.StringType decodes). **Note:** because `!`/`?` must survive as a nav argument, the encode above is REQUIRED — a raw `?` would otherwise be parsed as a query separator. Flagged edge case, handled here.

### 6.2 Nav drawer item — insert BETWEEN "Pokédex" and "Connecting Art"
In `ModalDrawerSheet`, after the Pokédex `NavigationDrawerItem` (lines 97–102) and before the Connecting Art one:
```kotlin
NavigationDrawerItem(
    label = { Text("Unown") },
    selected = isSelected(Screen.Unown.route),
    onClick = { closeDrawer(); navigateTo(Screen.Unown.route) },
    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
)
```
Final drawer order: Pokédex, Unown, Connecting Art, Personal Collection, Card History.

### 6.3 NavHost routes — add two composables
Add inside the `NavHost` block. The search route reuses the Unown ViewModel scoped to the `Screen.Unown` back-stack entry (identical to how `ConnectingArtSearch` scopes `ConnectingArtViewModel`):
```kotlin
composable(Screen.Unown.route) {
    UnownBinderScreen(
        onOpenDrawer = { openDrawer() },
        onOpenSlotSearch = { letterId ->
            navController.navigate(Screen.UnownSearch.createRoute(letterId))
        }
    )
}
composable(
    route = Screen.UnownSearch.route,
    arguments = listOf(navArgument("letterId") { type = NavType.StringType })
) { backStack ->
    val letterId = backStack.arguments?.getString("letterId") ?: ""
    val parentEntry = remember(backStack) {
        navController.getBackStackEntry(Screen.Unown.route)
    }
    val unownVm: UnownBinderViewModel = hiltViewModel(parentEntry)
    ManualSearchScreen(
        initialQuery = com.skyler.pokedexbinder.repository.UnownBinderRepository.searchNameFor(letterId),
        onCardSelected = { card ->
            unownVm.assignPendingCard(card)
            navController.popBackStack()
        },
        onBack = {
            unownVm.cancelAssign()
            navController.popBackStack()
        }
    )
}
```
Add import `com.skyler.pokedexbinder.ui.unown.UnownBinderScreen` and `...unown.UnownBinderViewModel`.

---

## 7. TCGCSV fallback logic (the §0.2 core)

### 7.1 `CardSearchRepository.searchTcgcsvByName` implementation
No name-search endpoint → fetch groups, fan out to per-group product listings with bounded concurrency, filter by name substring, map + dedupe. Whole method is defensive: any failure yields `emptyList()` (never throws — satisfies spec's "must not throw" and the safeSearch convention).
```kotlin
suspend fun searchTcgcsvByName(name: String): List<TcgCard> = try {
    coroutineScope {
        val query = name.trim().lowercase()
        if (query.isEmpty()) return@coroutineScope emptyList()

        val groups = tcgcsvApi.getGroups().results
        val groupNameById = groups.associate { it.groupId to it.name }

        // Bounded fan-out: process groups in chunks so we never open 200+ sockets at once.
        val matches = mutableListOf<TcgCard>()
        groups.map { it.groupId }.chunked(TCGCSV_GROUP_CONCURRENCY).forEach { chunk ->
            val chunkResults = chunk.map { groupId ->
                async {
                    runCatching { tcgcsvApi.getProducts(groupId).results }
                        .getOrDefault(emptyList())
                        .filter { product ->
                            product.name.lowercase().contains(query) ||
                                (product.cleanName?.lowercase()?.contains(query) == true)
                        }
                        .map { it.toDomain(groupNameById[it.groupId] ?: "") }
                }
            }.awaitAll().flatten()
            matches += chunkResults
        }

        // Dedupe against the existing name|number|setName key (reuse dedupeKey).
        val seen = HashSet<String>()
        matches.filter { seen.add(dedupeKey(it)) }
    }
} catch (e: Exception) {
    emptyList()
}
```
Private mapper (add near the other `toDomain` mappers):
```kotlin
private fun TcgcsvProductDto.toDomain(setName: String): TcgCard {
    val number = extendedData.firstOrNull { it.name == "Number" }?.value ?: ""
    return TcgCard(
        id = "tcgcsv_$productId",                       // §spec collision-avoidance prefix
        name = name,
        number = number,
        setName = setName,
        imageUrl = imageUrl ?: "",
        pokemonNames = name.split(" & ").map { it.substringBefore(" ").trim() }
        // hp/artist/setReleaseDate left null — TCGCSV product listings carry no rarity/artist
    )
}
```
Add to the `companion object`: `private const val TCGCSV_GROUP_CONCURRENCY = 8`.

`dedupeKey` and `async`/`coroutineScope` are already in the file. Add `import kotlinx.coroutines.awaitAll`.

**Coder verification note (P0):** confirm on-device that a retry completes in tolerable time and the CLB Mr. Mime product is returned. CLB group = "Pokemon TCG Classic" (group 23323, confirmed contains that expansion's products). If latency is unacceptable, that's a P1 optimization (e.g. caching the groups list) — do NOT bundle a static snapshot (spec Non-Goal).

### 7.2 `ui/manualsearch/ManualSearchViewModel.kt` (MODIFY)
Wire TCGCSV into `retry()` as an empty-results-only fallback. First-pass `search()` is UNCHANGED (never queries TCGCSV). The Error-state "Try Again" path must keep working (re-run `search`).
```kotlin
fun retry() {
    if (lastQuery.isBlank()) return
    val last = _state.value
    val lastWasEmptyResults = last is SearchState.Results && last.cards.isEmpty()
    if (lastWasEmptyResults) {
        // Empty on pokemontcg.io + TCGdex → now additionally query TCGCSV (retry-on-empty only).
        _state.value = SearchState.Loading
        viewModelScope.launch {
            try {
                val cards = cardSearchRepository.searchTcgcsvByName(lastQuery)
                _state.value = SearchState.Results(cards)
            } catch (e: Exception) {
                _state.value = SearchState.Error(e.message ?: "Search failed")
            }
        }
    } else {
        // Preserve existing behavior (Error-state "Try Again").
        search(lastQuery)
    }
}
```
(Because the previous results were empty, `base + tcgcsv == tcgcsv`, so no merge needed here — `searchTcgcsvByName` already dedupes internally.)

### 7.3 `ui/manualsearch/ManualSearchScreen.kt` (MODIFY)
Two changes; both backward-compatible for the existing Connecting-Art / Add-to-Secondary callers.

(a) Add an optional `initialQuery` param and auto-search it once:
```kotlin
@Composable
fun ManualSearchScreen(
    onCardSelected: (TcgCard) -> Unit,
    onBack: () -> Unit,
    initialQuery: String? = null,
    viewModel: ManualSearchViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf(initialQuery ?: "") }
    // ...existing...
    LaunchedEffect(Unit) {
        if (!initialQuery.isNullOrBlank()) viewModel.search(initialQuery)
    }
```
(Existing callers pass no `initialQuery` → null → no behavior change.)

(b) In the empty-results branch (currently just `Text("No cards found")`, lines ~96–103), add a button that triggers `retry()` so the user can explicitly pull in TCGCSV:
```kotlin
Text("No cards found", style = MaterialTheme.typography.bodyLarge)
Spacer(Modifier.height(12.dp))
OutlinedButton(onClick = { viewModel.retry() }) {
    Text("Search more sources (TCGplayer)")
}
```
This is the "user explicitly searches again" action the spec's retry-on-empty trigger depends on.

---

## 8. Publish wiring

### 8.1 `repository/PublishSettingsRepository.kt` (MODIFY)
- `PublishConfig`: add `val publishUnown: Boolean = true` (default ON, same as `publishPokedex`).
- `Keys`: add `val PUBLISH_UNOWN = booleanPreferencesKey("publish_unown")`.
- In the `config` flow mapping: `publishUnown = prefs[Keys.PUBLISH_UNOWN] ?: true`.
- Add setter:
```kotlin
suspend fun setPublishUnown(v: Boolean) {
    context.publishDataStore.edit { it[Keys.PUBLISH_UNOWN] = v }
}
```

### 8.2 `publish/PublishRepository.kt` (MODIFY)
- Add top-level constants alongside the existing binder constants:
```kotlin
private const val BINDER_ID_UNOWN = "unown"
private const val BINDER_NAME_UNOWN = "Unown"
private const val SECTION_UNOWN = "Unown"
```
- Constructor: add `private val unownBinderRepository: UnownBinderRepository` (last param).
- In `publish()` (near line 96–98), fetch Unown entries and pass them to `buildSnapshot`:
```kotlin
val unownEntries = if (config.publishUnown) unownBinderRepository.getAllEntries() else emptyList()
val nextSnapshot = buildSnapshot(entries, secondaryEntries, unownEntries, config)
```
- `buildSnapshot` signature gains `unownEntries: List<UnownBinderEntry> = emptyList()` (defaulted so existing tests that omit it still compile — but §10 updates them to pass it explicitly). Add the third binder block, AFTER the cardHistory block, BEFORE the `return`:
```kotlin
if (config.publishUnown) {
    val slots = unownEntries
        .sortedBy { it.position }
        .map { entry ->
            SnapshotSlot(
                dexNumber = 0,
                slotName = "Unown ${entry.letterId}",
                slotType = SLOT_TYPE_BASE,
                slotId = entry.letterId,            // single char; no collision (§0.3)
                cardId = entry.assignedCardId,
                cardName = entry.assignedCardName,
                cardSet = entry.assignedCardSetName,
                imageUrl = entry.assignedCardImageUrl
            )
        }
    binders += SnapshotBinder(
        id = BINDER_ID_UNOWN,
        name = BINDER_NAME_UNOWN,
        sections = listOf(SnapshotSection(name = SECTION_UNOWN, slots = slots))
    )
}
```
Add import `com.skyler.pokedexbinder.data.local.UnownBinderEntry` and `...repository.UnownBinderRepository`.

- `computeDiff`: NO change (already generalizes over `binders.flatMap{sections}.flatMap{slots}` by slotId). Unown slots participate in ADDED/REMOVED/REPLACED automatically.
- `pokedexComplete`: NO change — it filters `it.id == BINDER_ID_POKEDEX`, so Unown BASE slots do NOT inflate the completion count. Verified against current code (line 377–381).
- **Metadata-only republish must not spuriously REPLACE Unown slots:** guaranteed because `computeDiff` compares `cardId` only and Unown `slotId=letterId` is stable across publishes. Covered by a new test in §10.

---

## 9. Restore wiring — `publish/RestoreRepository.kt` (MODIFY)

- Add constant `private const val BINDER_ID_UNOWN = "unown"` (alongside existing `BINDER_ID_POKEDEX`).
- Constructor: add `private val unownBinderRepository: UnownBinderRepository` (last param).
- After the existing pokedex overlay (which ends by calling `binderRepository.seedFromJson(updated)` at line 102), add a parallel Unown overlay block that accumulates into the SAME counters, then writes via `unownBinderRepository.overwriteAll(...)`. Full replacement for the overlay section:
```kotlin
// --- Pokédex overlay (existing, unchanged except counters are now vars reused below) ---
val snapshotSlots: Map<String, SnapshotSlot> = snapshot.binders
    .firstOrNull { it.id == BINDER_ID_POKEDEX }
    ?.sections.orEmpty()
    .flatMap { it.slots }
    .associateBy { it.slotId }

currentStep = RestoreStep.Restoring
onStep(currentStep)

val currentEntries = binderRepository.getAllEntries()
var restored = 0
var cleared = 0
val updated = currentEntries.map { entry ->
    val snap = snapshotSlots[entry.pokemonId] ?: return@map entry
    when {
        snap.cardId != null -> { restored++; entry.copy(
            assignedCardId = snap.cardId, assignedCardName = snap.cardName,
            assignedCardSetName = snap.cardSet, assignedCardImageUrl = snap.imageUrl) }
        entry.assignedCardId != null -> { cleared++; entry.copy(
            assignedCardId = null, assignedCardName = null,
            assignedCardSetName = null, assignedCardImageUrl = null) }
        else -> entry
    }
}
val currentPokemonIds = currentEntries.map { it.pokemonId }.toSet()
var skipped = snapshotSlots.keys.count { it !in currentPokemonIds }
binderRepository.seedFromJson(updated)

// --- Unown overlay (NEW) — same accounting, onto unown_binder by letterId ---
val unownSnapshotSlots: Map<String, SnapshotSlot> = snapshot.binders
    .firstOrNull { it.id == BINDER_ID_UNOWN }
    ?.sections.orEmpty()
    .flatMap { it.slots }
    .associateBy { it.slotId }
if (unownSnapshotSlots.isNotEmpty()) {
    val currentUnown = unownBinderRepository.getAllEntries()
    val updatedUnown = currentUnown.map { entry ->
        val snap = unownSnapshotSlots[entry.letterId] ?: return@map entry
        when {
            snap.cardId != null -> { restored++; entry.copy(
                assignedCardId = snap.cardId, assignedCardName = snap.cardName,
                assignedCardSetName = snap.cardSet, assignedCardImageUrl = snap.imageUrl) }
            entry.assignedCardId != null -> { cleared++; entry.copy(
                assignedCardId = null, assignedCardName = null,
                assignedCardSetName = null, assignedCardImageUrl = null) }
            else -> entry
        }
    }
    val currentLetterIds = currentUnown.map { it.letterId }.toSet()
    skipped += unownSnapshotSlots.keys.count { it !in currentLetterIds }
    unownBinderRepository.overwriteAll(updatedUnown)
}
```
- **Old-snapshot edge case (spec):** a `binder.json` predating this feature has no `"unown"` binder → `unownSnapshotSlots` is empty → the `if` block is skipped entirely → `unown_binder` rows keep their seeded/local state. Correct behavior, no crash. Covered by test §10.
- Add imports `...repository.UnownBinderRepository`.

---

## 10. Tests

### 10.1 `Migration7to8Test.kt` (CREATE, androidTest)
Mirror `Migration6to7Test`'s TWO-method structure exactly.

**Method 1 — `migration7to8SqlIsValidAgainstV7ShapeAndPreservesExistingData`:**
- Build a bare v7-shape `main_binder_v7_shape` table by hand, seed one row (e.g. Charizard with a card).
- Run `MIGRATION_7_8`'s DDL verbatim (the CREATE TABLE from §3.1).
- Assert (a) seeded row untouched; (b) `columnsOf("unown_binder") == listOf("letterId","position","assignedCardId","assignedCardImageUrl","assignedCardName","assignedCardSetName")`.

**Method 2 — `migration7to8NewTableReachableThroughRealDaoAndRepository`:**
- Use the post-migration in-memory DB (`Room.inMemoryDatabaseBuilder`, `allowMainThreadQueries`).
- Get `db.unownBinderDao()`, exercise via a `UnownBinderRepository` wrapping it: `seedIfEmpty()` → assert `getAll().size == 28`; `assignCard("A", ...)` → assert row A has the cardId; `clearCard("A")` → assert null. This proves the DAO/repo layer reaches the new table.
- Reuse the `@Before setUp` / `@After tearDown` boilerplate from `Migration6to7Test`.
- Keep the same header note that this androidTest needs a device/emulator.

### 10.2 `CardSearchRepositoryTest.kt` (MODIFY)
- Update BOTH existing `CardSearchRepository(api, tcgdexApi)` constructions → `CardSearchRepository(api, tcgdexApi, tcgcsvApi)` with `private val tcgcsvApi = mockk<TcgcsvApi>()`.
- Add tests:
  - `searchTcgcsvByName maps products, prefixes id with tcgcsv_, extracts Number, filters by name` — mock `getGroups()` returning one group, `getProducts(groupId)` returning a product named "Mr. Mime" with `extendedData Number=030/034`; assert result id `tcgcsv_<productId>`, number `030/034`, setName = group name.
  - `searchTcgcsvByName filters out non-matching product names` — product "Pikachu" excluded when querying "Mr. Mime".
  - `searchTcgcsvByName returns empty and does not throw on API failure` — `getGroups()` throws → result `emptyList()`.

### 10.3 `ManualSearchViewModelTest.kt` (CREATE, unit test)
Mock `CardSearchRepository`. Use `runTest` + a `MainDispatcherRule` (add a standard `TestWatcher` that sets `Dispatchers.setMain(StandardTestDispatcher())`; if none exists in repo, include it in this file).
- `first search does NOT query TCGCSV` — `search("Mr Mime")` with `searchByName` returning empty; `coVerify(exactly = 0) { repo.searchTcgcsvByName(any()) }`.
- `retry after empty results queries TCGCSV and shows its results` — drive `search()` to `Results([])`, then `retry()`; `coVerify(exactly = 1) { repo.searchTcgcsvByName("Mr Mime") }` and state becomes `Results(tcgcsvCards)`.
- `retry after non-empty results does NOT query TCGCSV` — results non-empty → `retry()` re-runs `search`, `searchTcgcsvByName` never called.
- `retry from Error state re-runs search, not TCGCSV`.

### 10.4 `PublishRepositoryTest.kt` (MODIFY)
- Add `private val unownBinderRepository = mockk<UnownBinderRepository>(relaxed = true)`.
- Update the `PublishRepository(...)` construction to pass it (last arg).
- Add `publishUnown = false` to `defaultConfig` for the existing cases that don't care (keeps their snapshots unchanged), OR set `coEvery { unownBinderRepository.getAllEntries() } returns emptyList()` and add `publishUnown = true` — pick the minimal-churn option: **set `defaultConfig.publishUnown = false`** so existing assertions (which count only pokedex/cardHistory binders) are unaffected, then add dedicated Unown cases with `publishUnown = true`.
- Update `buildSnapshot(entries, emptyList(), config)` calls → `buildSnapshot(entries, emptyList(), emptyList(), config)` (new unown param). (If you keep the `= emptyList()` default on the param, only the new tests need the 4-arg form — but update all for clarity.)
- New cases:
  - `buildSnapshot includes unown binder when publishUnown is on` — pass 2 unown entries (one with a card), config `publishUnown = true`; assert a binder `id == "unown"` with one section "Unown", correct slotIds (`"A"`, `"B"`), cardId present on the filled one.
  - `buildSnapshot omits unown binder when publishUnown is off`.
  - `metadata-only republish does not REPLACE unown slots` — baseline snapshot and next snapshot have the SAME unown cardIds; `computeDiff(baseline, next)` yields no REPLACED delta for any single-char slotId.
  - `unown BASE slots do not inflate pokedexComplete` — unown slots have cards but `pokedexComplete` counts only the pokedex binder.

### 10.5 `RestoreRepositoryTest.kt` (MODIFY)
- Add `private val unownBinderRepository = mockk<UnownBinderRepository>(relaxed = true)`; pass to `RestoreRepository(...)`.
- Default `coEvery { unownBinderRepository.getAllEntries() } returns emptyList()` in `setUp` so existing pokedex-only tests are unaffected (empty unown snapshot → block skipped).
- New cases:
  - `unown overlay restores and clears letter slots and counts them` — snapshot has an `"unown"` binder (slots `A` filled `c-new`, `B` empty), local unown rows (`A` empty, `B` filled `c-old`); stub `getAllEntries()` for unown accordingly; capture `overwriteAll(...)`; assert `A` restored, `B` cleared, counts fold into `restoredCount`/`clearedCount`.
  - `old snapshot without unown binder leaves unown untouched` — snapshot has only `"pokedex"`; `coVerify(exactly = 0) { unownBinderRepository.overwriteAll(any()) }`, no crash, pokedex restore still works.

### 10.6 Settings UI — `SettingsViewModel.kt` + `SettingsScreen.kt` (MODIFY)
- `SettingsViewModel`: add `fun setPublishUnown(v: Boolean) = viewModelScope.launch { publishSettingsRepository.setPublishUnown(v) }`.
- `SettingsScreen`: add a `SettingToggleItem` between "Publish Pokédex" and "Publish Card History" (after line ~249's `HorizontalDivider`):
```kotlin
SettingToggleItem(
    title = "Publish Unown",
    description = "Include the Unown binder in the public page",
    checked = publishConfig.publishUnown,
    onCheckedChange = { viewModel.setPublishUnown(it) }
)
HorizontalDivider()
```

---

## 11. Edge cases checklist (all resolved above — Coder must honor)

1. **Empty TCGCSV response** → `results` defaults to `emptyList()`; filter yields nothing; returns `emptyList()`. ✔ (§7.1)
2. **TCGCSV network failure** → per-group `runCatching` + outer try/catch → never throws, contributes empty. ✔ (§7.1)
3. **First search never hits TCGCSV** → `search()` unchanged; only `retry()`-on-empty calls it. ✔ (§7.2, tested §10.3)
4. **Republish must not spuriously REPLACE Unown** → stable `slotId=letterId`, cardId-only diff. ✔ (§8.2, tested §10.4)
5. **Restore overlay for old snapshots without any unown binder** → empty map → block skipped, no crash. ✔ (§9, tested §10.5)
6. **`!` / `?` in Room PK / JSON / URLs** → PK & JSON safe as-is; nav route arg URL-encoded via `URLEncoder`, decoded by NavType.StringType. ✔ (§6.1, §0.3)
7. **slotId collision (single-char Unown vs pokemon IDs)** → none possible. ✔ (§0.3)
8. **pokedexComplete inflation** → Unown is a separate binder id, excluded from the count. ✔ (§8.2, tested §10.4)
9. **Seed idempotency** → `seedIfEmpty` guards on `count() > 0`; safe on every VM init. ✔ (§2.3)

---

## 12. Build/verify order for the Coder

1. Data layer (§2) + migration (§3) → compile.
2. DI (§3.2, §4.3) + TCGCSV api/dto (§4.1–4.2) + CardSearchRepository (§4.4, §7.1) → compile.
3. Unown UI (§5) + navigation (§6) → compile, run app, confirm drawer entry + 28-slot grid + assign flow.
4. ManualSearch changes (§7.2–7.3) → confirm empty-results "Search more sources" pulls TCGCSV.
5. Publish/restore (§8, §9) + settings (§10.6) → compile.
6. Tests (§10). Unit suite (`./gradlew testDebugUnitTest`) must be green. Migration test (§10.1) requires a device/emulator (androidTest) — run if one is available, else note per existing precedent (`Migration6to7Test` header).
7. On-device P0 verification: assign an Unown card; publish → confirm `binder.json` gains the `"unown"` binder; restore → confirm overlay; find CLB Mr. Mime via the TCGplayer retry fallback and assign it.

**Do not** add manual card entry, a bundled TCGCSV snapshot, TCGCSV as a first-pass source, or Unown to Personal Collection's model — all explicit spec Non-Goals.
