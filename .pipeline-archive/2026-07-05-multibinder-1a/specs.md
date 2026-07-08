# Run 1A — Multi-Binder Shared Infrastructure — Implementation Spec

**Source spec:** `docs/superpowers/specs/2026-05-24-multi-binder-design.md`
**Scope:** Shared infra ONLY. Placeholder screens for Connecting Art / Personal Collection with real routes. Run 1B replaces placeholder bodies.
**Author stage:** Planner. Coder follows this literally.

---

## ⚠️ Spec-vs-Reality Reconciliations (read first)

The approved design doc predates two DB migrations that already shipped. Do NOT follow the doc's version numbers blindly — follow this section.

1. **DB version is already 6, not 5.** `PokedexDatabase.kt` line 10 = `version = 6`. `DatabaseModule.kt` already registers `MIGRATION_4_5` and `MIGRATION_5_6`. The doc says "Version 5 → 6" for the new tables — that slot is taken. **This Run adds the four new tables as MIGRATION_6_7 and bumps version to 7.** This matches the task brief (6→7), not the doc.

2. **Pocket filter cannot be appended to the TCGdex API.** The doc says "Every pokemontcg.io API query … appends `-set.series:Pocket`". That DSL is pokemontcg.io-only. `CardSearchRepository` also calls a second backend, `TcgdexApi` (`tcgdexSearchByName`), whose `@Query("name")` param takes a bare name, not a query DSL — appending `-set.series:Pocket` there would corrupt the name search. **Scope of the filter in this Run: append `-set.series:Pocket` to every pokemontcg.io (`api.searchCards`) query string only. Leave TCGdex calls untouched.** TCGdex does not surface Pocket cards under the same set taxonomy, so this is correct, not a gap. Note this explicitly for the Reviewer.

3. **`SlotType` model is unaffected.** Connecting Art / Personal Collection slots are new tables with their own entities; they do not reuse `PokemonSlot` / `MainBinderEntry`.

---

## Section 1 — Room Migration 6→7 + New Entities

### 1.1 New entity files (create)

**File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/ConnectingArtGroup.kt`
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connecting_art_group")
data class ConnectingArtGroup(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val rows: Int,
    val cols: Int,
    val position: Int = 0
)
```

**File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/ConnectingArtSlot.kt`
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
data class ConnectingArtSlot(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val groupId: Int,
    val slotIndex: Int,          // row-major, 0-based
    val cardId: String? = null,
    val cardName: String? = null,
    val cardImageUrl: String? = null,
    val owned: Boolean = false
)
```
> **Index is mandatory.** Room emits a compile-time warning if a `@ForeignKey` child column has no index; without it queries filtering by `groupId` do full-table scans. The migration SQL below MUST create the matching index or Room's schema validation will throw `IllegalStateException` at first open (expected-vs-found schema mismatch).

**File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/PersonalCollectionCache.kt`
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_collection_cache")
data class PersonalCollectionCache(
    @PrimaryKey val cardId: String,
    val pokemonKey: String,   // e.g. "charizard", "minccino_cinccino"
    val name: String,
    val imageUrl: String,
    val setName: String,
    val releaseDate: String
)
```

**File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/PersonalCollectionEntry.kt`
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_collection_entry")
data class PersonalCollectionEntry(
    @PrimaryKey val cardId: String,
    val owned: Boolean = false
)
```

### 1.2 Migration SQL (add to `PokedexDatabase.kt` companion object)

```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // connecting_art_group
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `connecting_art_group` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`name` TEXT NOT NULL, " +
                "`rows` INTEGER NOT NULL, " +
                "`cols` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL)"
        )
        // connecting_art_slot (FK -> connecting_art_group.id, cascade delete)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `connecting_art_slot` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`groupId` INTEGER NOT NULL, " +
                "`slotIndex` INTEGER NOT NULL, " +
                "`cardId` TEXT, " +
                "`cardName` TEXT, " +
                "`cardImageUrl` TEXT, " +
                "`owned` INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(`groupId`) REFERENCES `connecting_art_group`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_connecting_art_slot_groupId` " +
                "ON `connecting_art_slot` (`groupId`)"
        )
        // personal_collection_cache
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `personal_collection_cache` (" +
                "`cardId` TEXT NOT NULL PRIMARY KEY, " +
                "`pokemonKey` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`imageUrl` TEXT NOT NULL, " +
                "`setName` TEXT NOT NULL, " +
                "`releaseDate` TEXT NOT NULL)"
        )
        // personal_collection_entry
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `personal_collection_entry` (" +
                "`cardId` TEXT NOT NULL PRIMARY KEY, " +
                "`owned` INTEGER NOT NULL DEFAULT 0)"
        )
    }
}
```

**Migration-correctness notes for Coder:**
- Room maps `Boolean` → SQLite `INTEGER NOT NULL`. The `owned` default must be `DEFAULT 0` (Room generates the same). Column type MUST be `INTEGER NOT NULL`, not nullable — the entity field is a non-null `Boolean` with a Kotlin default, which Room treats as `NOT NULL DEFAULT 0`.
- Autogenerate PK on `Int` → `INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT`. (Room actually omits AUTOINCREMENT for plain autoGenerate and relies on rowid, but including it is schema-compatible and harmless. To be exact and avoid any validation mismatch, use `INTEGER PRIMARY KEY AUTOINCREMENT` **without** a separate `NOT NULL` — SQLite treats `INTEGER PRIMARY KEY` as an alias for rowid. **Coder: after writing the migration, build once with `exportSchema=false` (already set) and run the app / an instrumented `MigrationTestHelper` if available. If Room throws a schema-mismatch `IllegalStateException` at open, copy the "expected" SQL from the exception message verbatim into the migration.** This is the standard way to get byte-exact DDL.)
- The index name `index_connecting_art_slot_groupId` is exactly the name Room autogenerates for `Index("groupId")`. It must match or validation fails.
- `fallbackToDestructiveMigration()` is currently present in `DatabaseModule.kt`. Keep it — it's the safety net if the DDL is slightly off, but do NOT rely on it: a real `MIGRATION_6_7` must be registered so existing users' Pokédex + Card History data survives. (With the fallback present, a broken migration silently wipes the DB instead of crashing — so the Tester must explicitly verify existing data survives the 6→7 upgrade, not just that the app opens.)

### 1.3 Wire entities into `@Database` (see Section 4)

---

## Section 2 — New DAOs

Mirror `SecondaryBinderDao.kt` exactly: `Flow` observers, `suspend` getters, `@Insert`, `@Transaction` insert-at-end using a `getNextPosition()` helper, `updatePosition`, `deleteById`.

### 2.1 `ConnectingArtDao`

**File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/ConnectingArtDao.kt`

```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectingArtDao {

    // ---- Groups ----
    @Query("SELECT * FROM connecting_art_group ORDER BY position ASC, id ASC")
    fun observeGroups(): Flow<List<ConnectingArtGroup>>

    @Query("SELECT * FROM connecting_art_group ORDER BY position ASC, id ASC")
    suspend fun getGroups(): List<ConnectingArtGroup>

    @Query("SELECT * FROM connecting_art_group WHERE id = :groupId")
    suspend fun getGroup(groupId: Int): ConnectingArtGroup?

    @Insert
    suspend fun insertGroup(group: ConnectingArtGroup): Long

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM connecting_art_group")
    suspend fun getNextGroupPosition(): Int

    /** Inserts a group at the end and creates rows*cols empty slots for it. */
    @Transaction
    suspend fun createGroupWithSlots(name: String, rows: Int, cols: Int) {
        val pos = getNextGroupPosition()
        val groupId = insertGroup(
            ConnectingArtGroup(name = name, rows = rows, cols = cols, position = pos)
        ).toInt()
        val slots = (0 until rows * cols).map { idx ->
            ConnectingArtSlot(groupId = groupId, slotIndex = idx)
        }
        insertSlots(slots)
    }

    @Query("UPDATE connecting_art_group SET position = :position WHERE id = :id")
    suspend fun updateGroupPosition(id: Int, position: Int)

    /** Cascade-deletes all child slots via the FK ON DELETE CASCADE. */
    @Query("DELETE FROM connecting_art_group WHERE id = :id")
    suspend fun deleteGroupById(id: Int)

    // ---- Slots ----
    @Query("SELECT * FROM connecting_art_slot WHERE groupId = :groupId ORDER BY slotIndex ASC")
    fun observeSlots(groupId: Int): Flow<List<ConnectingArtSlot>>

    @Query("SELECT * FROM connecting_art_slot ORDER BY groupId ASC, slotIndex ASC")
    fun observeAllSlots(): Flow<List<ConnectingArtSlot>>

    @Insert
    suspend fun insertSlots(slots: List<ConnectingArtSlot>)

    @Update
    suspend fun updateSlot(slot: ConnectingArtSlot)

    @Query(
        "UPDATE connecting_art_slot SET cardId = :cardId, cardName = :cardName, " +
            "cardImageUrl = :cardImageUrl, owned = 0 WHERE id = :slotId"
    )
    suspend fun assignCard(slotId: Int, cardId: String, cardName: String, cardImageUrl: String)

    @Query(
        "UPDATE connecting_art_slot SET cardId = NULL, cardName = NULL, " +
            "cardImageUrl = NULL, owned = 0 WHERE id = :slotId"
    )
    suspend fun clearSlot(slotId: Int)

    @Query("UPDATE connecting_art_slot SET owned = :owned WHERE id = :slotId")
    suspend fun setOwned(slotId: Int, owned: Boolean)
}
```
> **Note:** `@Insert` returning `Long` is standard for getting the new rowid. `SecondaryBinderDao.insert` returns `Unit`; returning `Long` here is a deliberate, necessary deviation (we need the generated `groupId` to create child slots in the same transaction). Flag for Reviewer as intentional.
> **FK enforcement:** Room enables SQLite foreign keys by default (`PRAGMA foreign_keys = ON`) for every connection, so `ON DELETE CASCADE` fires automatically on `deleteGroupById`. No manual pragma needed. Do NOT add a manual delete of child slots — that would double-delete / mask FK bugs.

### 2.2 `PersonalCollectionDao`

**File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/PersonalCollectionDao.kt`

```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalCollectionDao {

    // ---- Cache ----
    @Query("SELECT * FROM personal_collection_cache WHERE pokemonKey = :pokemonKey ORDER BY releaseDate DESC")
    fun observeCacheForPokemon(pokemonKey: String): Flow<List<PersonalCollectionCache>>

    @Query("SELECT * FROM personal_collection_cache ORDER BY releaseDate DESC")
    fun observeAllCache(): Flow<List<PersonalCollectionCache>>

    @Query("SELECT COUNT(*) FROM personal_collection_cache")
    suspend fun cacheCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(cards: List<PersonalCollectionCache>)

    @Query("DELETE FROM personal_collection_cache WHERE pokemonKey = :pokemonKey")
    suspend fun deleteCacheForPokemon(pokemonKey: String)

    @Query("DELETE FROM personal_collection_cache")
    suspend fun clearCache()

    /** Replaces the cache rows for one Pokémon key atomically (used on refresh). */
    @Transaction
    suspend fun replaceCacheForPokemon(pokemonKey: String, cards: List<PersonalCollectionCache>) {
        deleteCacheForPokemon(pokemonKey)
        upsertCache(cards)
    }

    // ---- Owned entries ----
    @Query("SELECT * FROM personal_collection_entry")
    fun observeEntries(): Flow<List<PersonalCollectionEntry>>

    @Query("SELECT * FROM personal_collection_entry WHERE cardId = :cardId")
    suspend fun getEntry(cardId: String): PersonalCollectionEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: PersonalCollectionEntry)

    @Query("DELETE FROM personal_collection_entry WHERE cardId = :cardId")
    suspend fun deleteEntry(cardId: String)
}
```

---

## Section 3 — New Repositories

Mirror `BinderRepository.kt`: `@Singleton`, constructor `@Inject`, DAO dependency, `Flow` mapping, `suspend` CRUD delegation. Do NOT put screen/ViewModel logic here.

### 3.1 `ConnectingArtRepository`

**File:** `app/src/main/java/com/skyler/pokedexbinder/repository/ConnectingArtRepository.kt`

```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.ConnectingArtDao
import com.skyler.pokedexbinder.data.local.ConnectingArtGroup
import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectingArtRepository @Inject constructor(
    private val dao: ConnectingArtDao
) {
    fun observeGroups(): Flow<List<ConnectingArtGroup>> = dao.observeGroups()
    fun observeAllSlots(): Flow<List<ConnectingArtSlot>> = dao.observeAllSlots()
    fun observeSlots(groupId: Int): Flow<List<ConnectingArtSlot>> = dao.observeSlots(groupId)

    suspend fun createGroup(name: String, rows: Int, cols: Int) =
        dao.createGroupWithSlots(name, rows, cols)

    suspend fun deleteGroup(groupId: Int) = dao.deleteGroupById(groupId)

    suspend fun reorderGroups(orderedGroupIds: List<Int>) {
        orderedGroupIds.forEachIndexed { index, id -> dao.updateGroupPosition(id, index) }
    }

    suspend fun assignCard(slotId: Int, cardId: String, cardName: String, cardImageUrl: String) =
        dao.assignCard(slotId, cardId, cardName, cardImageUrl)

    suspend fun removeCard(slotId: Int) = dao.clearSlot(slotId)

    suspend fun setOwned(slotId: Int, owned: Boolean) = dao.setOwned(slotId, owned)
}
```
> Card *search* is NOT implemented here — Run 1B wires the existing `CardSearchRepository` through the ViewModel, exactly as the doc says ("delegates card search to existing CardSearchRepository"). This Run only provides the persistence surface.

### 3.2 `PersonalCollectionRepository`

**File:** `app/src/main/java/com/skyler/pokedexbinder/repository/PersonalCollectionRepository.kt`

For Run 1A this is the persistence + a thin fetch surface stub. The real per-Pokémon fetch/merge logic (Charizard, Celebi, Leafeon, Tangela, Minccino & Cinccino) lands in Run 1B. Provide:
- cache observers (delegating to DAO),
- owned-entry observers + toggles,
- a `refreshPokemon(pokemonKey, names)` **signature** that Run 1B fills in using `CardSearchRepository.searchByName` (with the Pocket filter already enforced inside `CardSearchRepository`).

```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonalCollectionRepository @Inject constructor(
    private val dao: PersonalCollectionDao,
    private val cardSearchRepository: CardSearchRepository
) {
    fun observeCache(pokemonKey: String): Flow<List<PersonalCollectionCache>> =
        dao.observeCacheForPokemon(pokemonKey)

    fun observeAllCache(): Flow<List<PersonalCollectionCache>> = dao.observeAllCache()

    fun observeEntries(): Flow<List<PersonalCollectionEntry>> = dao.observeEntries()

    suspend fun cacheCount(): Int = dao.cacheCount()

    suspend fun setOwned(cardId: String, owned: Boolean) =
        dao.upsertEntry(PersonalCollectionEntry(cardId = cardId, owned = owned))

    suspend fun removeOwned(cardId: String) = dao.deleteEntry(cardId)

    /**
     * Run 1B: query pokemontcg.io by [names] (Pocket filter enforced in CardSearchRepository),
     * map results to PersonalCollectionCache, and replaceCacheForPokemon(pokemonKey, mapped).
     * Left as a stub in Run 1A — do NOT implement the fetch/merge here yet.
     */
    suspend fun refreshPokemon(pokemonKey: String, names: List<String>) {
        // TODO(Run 1B): fetch via cardSearchRepository + dao.replaceCacheForPokemon(...)
    }
}
```
> `cardSearchRepository` is injected now so the DI graph is complete and Run 1B needs no wiring changes. It's referenced in the TODO so it isn't flagged as unused — acceptable. If the linter still complains about the unused param, keep it; it is load-bearing for the next Run.

---

## Section 4 — `PokedexDatabase` + `DatabaseModule` wiring

### 4.1 `PokedexDatabase.kt` edits

Change the `@Database` annotation (add 4 entities, bump version to 7) and add 2 abstract DAO accessors + register nothing else here (migration object added in Section 1.2):

```kotlin
@Database(
    entities = [
        MainBinderEntry::class,
        SecondaryBinderEntry::class,
        ConnectingArtGroup::class,
        ConnectingArtSlot::class,
        PersonalCollectionCache::class,
        PersonalCollectionEntry::class
    ],
    version = 7,
    exportSchema = false
)
abstract class PokedexDatabase : RoomDatabase() {
    abstract fun mainBinderDao(): MainBinderDao
    abstract fun secondaryBinderDao(): SecondaryBinderDao
    abstract fun connectingArtDao(): ConnectingArtDao
    abstract fun personalCollectionDao(): PersonalCollectionDao

    companion object {
        val MIGRATION_4_5 = /* unchanged */
        val MIGRATION_5_6 = /* unchanged */
        val MIGRATION_6_7 = /* from Section 1.2 */
    }
}
```

### 4.2 `DatabaseModule.kt` edits

Register the migration and provide the two DAOs:

```kotlin
@Provides
@Singleton
fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase =
    Room.databaseBuilder(context, PokedexDatabase::class.java, "pokedex_binder.db")
        .addMigrations(
            PokedexDatabase.MIGRATION_4_5,
            PokedexDatabase.MIGRATION_5_6,
            PokedexDatabase.MIGRATION_6_7
        )
        .fallbackToDestructiveMigration()
        .build()

@Provides
fun provideMainBinderDao(db: PokedexDatabase): MainBinderDao = db.mainBinderDao()

@Provides
fun provideSecondaryBinderDao(db: PokedexDatabase): SecondaryBinderDao = db.secondaryBinderDao()

@Provides
fun provideConnectingArtDao(db: PokedexDatabase): ConnectingArtDao = db.connectingArtDao()

@Provides
fun providePersonalCollectionDao(db: PokedexDatabase): PersonalCollectionDao = db.personalCollectionDao()
```
> Repositories need no `@Provides` — they are `@Singleton` + `@Inject constructor`, so Hilt constructs them automatically once their DAO deps are provided. Matches `BinderRepository` (which has no explicit provider).

---

## Section 5 — Navigation Drawer + Placeholder Screens + Settings toggle removal

### 5.1 New routes in `Screen` sealed class (`AppNavigation.kt`)

Add:
```kotlin
object ConnectingArt : Screen("connecting_art")
object PersonalCollection : Screen("personal_collection")
```
Keep `object SecondaryBinder : Screen("secondary_binder")` — Card History still exists, just moves from bottom-bar-conditional to drawer.

### 5.2 Placeholder screen composables (create)

**File:** `app/src/main/java/com/skyler/pokedexbinder/ui/connectingart/ConnectingArtScreen.kt`
```kotlin
package com.skyler.pokedexbinder.ui.connectingart

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectingArtScreen(onOpenDrawer: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connecting Art") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("Coming soon")
        }
    }
}
```

**File:** `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionScreen.kt`
Identical structure, title `"Personal Collection"`, package `...ui.personalcollection`, function `PersonalCollectionScreen(onOpenDrawer: () -> Unit)`.

> Run 1B replaces these bodies and adds the ViewModels. Keep the `onOpenDrawer` param — the drawer hamburger must work even for placeholders so the nav is testable now.

### 5.3 `ModalNavigationDrawer` wrapping the root `Scaffold`

Rewrite `AppNavigation()` so a `ModalNavigationDrawer` wraps the existing `Scaffold`. Key structure (illustrative — Coder writes final):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination

    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openDrawer() = scope.launch { drawerState.open() }
    fun closeDrawer() = scope.launch { drawerState.close() }

    fun isSelected(route: String) =
        currentDest?.hierarchy?.any { it.route == route } == true

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                DrawerItem("Pokédex", Screen.MainBinder.route)
                DrawerItem("Connecting Art", Screen.ConnectingArt.route)
                DrawerItem("Personal Collection", Screen.PersonalCollection.route)
                DrawerItem("Card History", Screen.SecondaryBinder.route)
            }
        }
    ) {
        Scaffold(
            bottomBar = { /* see 5.4 */ }
        ) { padding ->
            NavHost(...) { /* see 5.5 */ }
        }
    }
}
```

Where `DrawerItem` is a local helper:
```kotlin
@Composable
private fun DrawerItem(label: String, route: String) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = isSelected(route),          // capture via closure or pass in
        onClick = { closeDrawer(); navigateTo(route) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}
```
> **State-management edge cases:**
> - `isSelected`/`closeDrawer`/`navigateTo` are defined inside `AppNavigation` and captured by the drawer content lambda — that's fine in Compose. If Coder prefers, hoist `DrawerItem` out and pass `selected`, `onClick` explicitly. Either is acceptable; do NOT make `DrawerItem` a top-level composable that reaches into `AppNavigation` scope.
> - Drawer must close *before or with* navigation. Using `scope.launch { drawerState.close() }` then `navigateTo` in the same `onClick` is safe — order shown (close then navigate).
> - `rememberDrawerState` + `rememberCoroutineScope` survive recomposition; no extra `remember` needed.
> - Imports to add: `androidx.compose.material3.ModalNavigationDrawer`, `ModalDrawerSheet`, `NavigationDrawerItem`, `NavigationDrawerItemDefaults`, `rememberDrawerState`, `DrawerValue`, `androidx.compose.runtime.rememberCoroutineScope`, `androidx.compose.foundation.layout.Spacer/height`, `androidx.compose.ui.unit.dp`, `kotlinx.coroutines.launch`.

### 5.4 Bottom bar — remove the Secondary Binder item

In the `NavigationBar`, **delete** the entire `if (settings.showSecondaryBinder) { NavigationBarItem(...) }` block (current lines ~93–100). Keep the Pokédex item and the `if (settings.useCameraScanner)` Scan item unchanged. The `isOnSecondary` computation inside the Scan onClick stays valid (it references `Screen.SecondaryBinder.route`, which still exists).

### 5.5 Wire the new routes into `NavHost`

Add two composables; pass `openDrawer` into the placeholders. Existing screens' top bars are NOT modified in this Run (MainBinder etc. keep their current app bars — adding a hamburger to every existing screen is out of scope for 1A; the drawer is still reachable from the two new screens and can be opened programmatically. **However**, to make the drawer genuinely usable from the default start destination, ALSO expose the drawer from `MainBinderScreen`. If `MainBinderScreen` already takes an `onSettingsClick`, add a sibling `onOpenDrawer: () -> Unit` param and let Coder decide the minimal wiring. If wiring MainBinder's app bar is non-trivial, note it and defer to Run 1B, but at minimum the two new screens open the drawer.):

```kotlin
composable(Screen.ConnectingArt.route) {
    ConnectingArtScreen(onOpenDrawer = { openDrawer() })
}
composable(Screen.PersonalCollection.route) {
    PersonalCollectionScreen(onOpenDrawer = { openDrawer() })
}
```
Keep the existing `Screen.SecondaryBinder.route` composable exactly as-is (it already renders `SecondaryBinderScreen`).

> **Decision needed / flag for Reviewer:** the doc says "A hamburger icon in each screen's top app bar opens it." Fully honoring that means touching MainBinder/Secondary/etc. app bars. Run 1A scope keeps existing screens untouched except the minimal MainBinder hamburger. Confirm with user whether adding hamburgers to ALL existing screens belongs in 1A or 1B. Default assumption: minimal (new screens + MainBinder), rest in 1B.

### 5.6 Remove `showSecondaryBinder` everywhere

Delete the setting end-to-end. Exact call sites (verified via grep — the `docs/` matches are stale plan files, ignore them):

1. **`SettingsRepository.kt`**
   - Remove `showSecondaryBinder` field from `data class AppSettings` (line 22).
   - Remove `Keys.SHOW_SECONDARY_BINDER` (line 39).
   - Remove the `showSecondaryBinder = prefs[Keys.SHOW_SECONDARY_BINDER] ?: false,` line in the `settings` map (line 51).
   - Remove `suspend fun setShowSecondaryBinder(...)` (lines 74–76).
2. **`SettingsViewModel.kt`**
   - Remove `fun setShowSecondaryBinder(...)` (line 32).
3. **`SettingsScreen.kt`**
   - Remove the entire "Navigation" section that renders the Secondary Binder toggle (lines ~237–248: `SectionHeader("Navigation")`, its `HorizontalDivider`s, and the `SettingToggleItem` for Secondary Binder). Removing the whole Navigation section is correct since it contained only this one toggle.
4. **`AppNavigation.kt`**
   - Remove the bottom-bar `if (settings.showSecondaryBinder)` block (Section 5.4).

**DataStore edge case (confirm — no crash, no dead path):**
- The DataStore-persisted key `"show_secondary_binder"` may still exist on existing users' devices. Removing the Kotlin `Keys.SHOW_SECONDARY_BINDER` and never reading it means the stored value is simply **ignored** — DataStore does not error on orphan keys, and no migration/cleanup is required. It sits as dead bytes in the prefs file, harmless.
- **No crash risk:** nothing reads the key after removal. Card History is now unconditionally in the drawer (`DrawerItem("Card History", Screen.SecondaryBinder.route)`), so its visibility no longer depends on the removed value.
- **No dead-code path:** the `if (settings.showSecondaryBinder)` branch is deleted, not left dangling. The `SecondaryBinder` route + `SecondaryBinderScreen` remain live via the drawer.
- Do NOT write a DataStore migration to remove the orphan key — unnecessary churn. If desired later, a one-line `prefs.remove(...)` could clean it, but it's explicitly out of scope. State this in the plan so the Reviewer doesn't flag the leftover key as a bug.

---

## Section 6 — Global Pocket-series filter in `CardSearchRepository`

Append `-set.series:Pocket` to **every pokemontcg.io query string**. The single choke point is `safeSearch(query)` → `api.searchCards(query = query)`. Rather than editing each of the ~10 `add(...)`/`safeSearch(...)` call sites individually (error-prone, easy to miss one), **append the filter once inside `safeSearch`**:

```kotlin
private suspend fun safeSearch(query: String): List<TcgCard> = try {
    val filtered = if (query.isBlank()) POCKET_EXCLUSION.trim()
                   else "$query $POCKET_EXCLUSION"
    api.searchCards(query = filtered).data.map { it.toDomain() }
} catch (e: Exception) {
    emptyList()
}

companion object {
    private const val POCKET_EXCLUSION = "-set.series:Pocket"
}
```
> This is the elegant single-point enforcement the doc intends ("applied in CardSearchRepository … enforced consistently everywhere"). Every pokemontcg.io path funnels through `safeSearch`, so one edit covers all of them.

### Every pokemontcg.io query-building call site (verified by reading the full file)

All of these ultimately call `safeSearch(...)`, so the single `safeSearch` edit covers 100% of them. Listed for the Reviewer to confirm none bypass `safeSearch`:

| # | Method | Query passed to `safeSearch` | Line(s) |
|---|--------|------------------------------|---------|
| 1 | `searchByParsedInfo` | `"${nameQuery(name)} number:\"$number\" set.total:$total"` | 32 |
| 2 | `searchByParsedInfo` | `"${nameQuery(name)} number:\"$number\""` | 34 |
| 3 | `searchByParsedInfo` | `"number:\"$number\" set.total:$total"` | 36 |
| 4 | `searchByParsedInfo` | `"nationalPokedexNumbers:$dex number:\"$number\" set.total:$total"` | 38 |
| 5 | `searchByParsedInfo` | `"nationalPokedexNumbers:$dex number:\"$number\""` | 40 |
| 6 | `searchByParsedInfo` (loop) | each `specificQueries` entry | 45 |
| 7 | `searchByParsedInfo` | `nameQuery(name)` (broad, primary async) | 47 |
| 8 | `searchByParsedInfo` | `"nationalPokedexNumbers:$dex"` | 51 |
| 9 | `searchByParsedInfo` | `"number:\"$number\""` | 54 |
| 10 | `searchByNameAndNumber` | `"${nameQuery(name)} number:\"$number\""` then `nameQuery(name)` | 58–59 |
| 11 | `searchByName` | `nameQuery(name)` (primary async) | 63 |
| 12 | `searchByNumber` | `"number:\"$number\""` | 68 |
| 13 | `searchByNumberAndTotal` | `"number:\"$number\" set.total:$total"` then `"number:\"$number\""` | 71–72 |
| 14 | `searchByDexNumber` | `"nationalPokedexNumbers:$dexNumber"` | 75 |
| 15 | `searchPromosByName` | `"${nameQuery(name)} rarity:Promo"` | 78 |

**Because they all route through `safeSearch`, editing `safeSearch` alone is sufficient and correct. Do not also edit the individual strings — that would double-append the filter.**

### TCGdex call sites — intentionally NOT filtered
- `searchByParsedInfo` line 47 (`tcgdexSearchByName` via `secondary` async)
- `searchByName` line 64 (`tcgdexSearchByName` via `secondary` async)
- `tcgdexSearchByName(name)` → `tcgdexApi.searchCards(name)` (line 120)

These hit `TcgdexApi.searchCards(@Query("name") name)`, a different backend with a bare-name param, not the pokemontcg.io query DSL. Appending `-set.series:Pocket` would corrupt the name and break the search. TCGdex's set taxonomy does not expose Pocket cards under `set.series`, so leaving them unfiltered does not reintroduce Pocket cards through the merge. **This is a deliberate scoping decision — Reviewer should confirm it against the doc's intent, not flag it as a missed site.** If the user insists TCGdex must also exclude Pocket, that requires post-filtering TCGdex results by set metadata (not available on `TcgdexCardBriefDto`, which has no series field) — out of scope for 1A, raise as an open question.

---

## Build / Verification Checklist (for Tester)

1. **Compiles:** `./gradlew :app:assembleDebug` (or the project's build task) with zero errors.
2. **Migration integrity:** Install a build at version 6 (current `main`), add a Pokédex assignment + a Card History entry, then upgrade to the version-7 build. Confirm **existing data survives** (this is critical because `fallbackToDestructiveMigration()` would silently wipe it if `MIGRATION_6_7` DDL is wrong — a passing "app opens" is NOT sufficient proof).
3. **Room schema validation:** App opens without `IllegalStateException` (expected-vs-found schema). If it throws, the migration DDL doesn't match Room's generated schema — copy the "expected" SQL from the exception into `MIGRATION_6_7`.
4. **Cascade delete:** Create a Connecting Art group (via a temporary test or `createGroupWithSlots`), delete it, confirm its `connecting_art_slot` rows are gone (FK cascade). Can be an instrumented DAO test.
5. **Drawer:** From the two new screens, hamburger opens the drawer; all 4 entries navigate correctly and the drawer closes; active entry highlighted.
6. **Settings:** No Secondary Binder toggle remains; Settings screen renders without the Navigation section; app doesn't crash reading settings (orphan DataStore key ignored).
7. **Pocket filter:** Log/inspect one outgoing pokemontcg.io request and confirm the query ends with `-set.series:Pocket`. Confirm TCGdex requests are unchanged (bare name).
8. **Card History still reachable** via drawer and still functions (add/scan/search) exactly as before.

---

## Out of scope for Run 1A (do NOT build)
- Real ConnectingArtScreen / PersonalCollectionScreen UI (grids, FAB, bottom sheets, pull-to-refresh) — Run 1B.
- ConnectingArtViewModel / PersonalCollectionViewModel — Run 1B.
- The actual per-Pokémon fetch/merge in `PersonalCollectionRepository.refreshPokemon` — Run 1B.
- Hamburger icons on all pre-existing screens beyond MainBinder — Run 1B (flagged as open question).
- Any DataStore orphan-key cleanup migration.
