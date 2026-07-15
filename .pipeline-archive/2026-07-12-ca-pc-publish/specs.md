# Implementation Plan — Connecting Art + Personal Collection Publish, Unown Nav Move

**Source spec:** `docs/superpowers/specs/2026-07-12-connecting-art-personal-collection-publish.md` (APPROVED)
**Pipeline stage:** Stage 1 (Planner) → hand to Coder
**Scope:** publish/restore/nav wiring only. No Room schema/migration changes, no new entities/DAOs (tables already exist from v7). One additive, backward-compatible field on `SnapshotSlot`.

---

## 0. Ground-truth facts verified in the codebase (read before implementing)

These are the real field names/shapes the Coder must use — do NOT re-guess:

- **`ConnectingArtGroup`** (`data/local/ConnectingArtGroup.kt`): `id: Int` (autoGenerate PK), `name: String`, `rows: Int`, `cols: Int`, `position: Int`.
- **`ConnectingArtSlot`** (`data/local/ConnectingArtSlot.kt`): `id: Int` (autoGenerate PK), `groupId: Int` (FK), **`slotIndex: Int` (row-major, 0-based) ← the grid-position field**, `cardId: String?`, `cardName: String?`, `cardImageUrl: String?`, **`owned: Boolean` (default false)**. Note: **no `cardSetName`** on CA slots, and CA has a real owned/assigned distinction (DAO `assignCard` sets `owned = 0`; separate `setOwned`).
- **`PersonalCollectionCache`** (`data/local/PersonalCollectionCache.kt`): `cardId: String` (PK), `pokemonKey: String` (e.g. `"charizard"`, `"minccino_cinccino"`), `name: String`, `imageUrl: String`, `setName: String`, `releaseDate: String`. **Confirmed: this is the accurate shape** (spec's "pokemonKey/cardId/name/imageUrl" was partial — `setName` and `releaseDate` also exist).
- **`PersonalCollectionEntry`** (`data/local/PersonalCollectionEntry.kt`): `cardId: String` (PK), `owned: Boolean` (default false). **Confirmed: separate table from cache.** Ownership = presence of a row with `owned = true`; the app's `removeOwned` deletes the row, `setOwned(id, true)` upserts it.
- **The 5 fixed PC sections** (`ui/personalcollection/PersonalCollectionViewModel.kt` → `PERSONAL_COLLECTION_SECTIONS`): keys/titles are
  `charizard`/"Charizard", `celebi`/"Celebi", `leafeon`/"Leafeon", `tangela`/"Tangela", `minccino_cinccino`/"Minccino & Cinccino".
- **DI:** `PublishRepository` and `RestoreRepository` are `@Singleton class … @Inject constructor(...)`; `ConnectingArtRepository` and `PersonalCollectionRepository` are also `@Singleton @Inject`. **Hilt auto-wires new constructor params — no `di/` module edit needed.** (`DatabaseModule.kt` already provides both DAOs.)
- **`computeDiff.hasChanges` = `deltas.isNotEmpty()`** — an empty delta list makes `publish()` return `NoChanges` and upload nothing. This is load-bearing for the owned-flip decision below (§4).
- **No `androidTest/` source set exists** — the only nav test is a plain-JVM route-encoding test (`AppNavigationScreenTest.kt`). There is no Compose-UI instrumentation harness in the project. See §9 for how this constrains the nav test.

---

## 1. Resolved ambiguities (decided here — Coder must not re-litigate)

| # | Question the spec left open | Resolution | Rationale |
|---|---|---|---|
| R1 | CA slot id — CA slots have no natural string key like `pokemonId` | `slotId = "ca-${group.id}-${slotIndex}"` | `group.id` (DB PK) is stable across publishes (unlike `position`, which changes on reorder); `slotIndex` is unique within a group. Composite is globally unique for the `associateBy { slotId }` diff map. Mirrors Card History's `"${pokemonId}-${entry.id}"` DB-id-based key convention. Restore reconstructs the **same** key from local slots (no string parsing). |
| R2 | Within an included CA group, publish all slots or only assigned ones? | **All** slots of the group, ordered by `slotIndex`. | Mirrors the Pokédex binder, which emits empty slots (`cardId = null`) to preserve grid layout. The website's grid needs the full slot set + positions. Empty slots diff cleanly (null cardId). |
| R3 | Does CA publish an `owned` flag, or `owned = true` (like Pokédex/Card History/Unown)? | **`owned = slot.owned`** (the real per-slot value). | CA genuinely has an assigned-but-not-owned state (DAO `assignCard` sets `owned=0`; separate `setOwned`). The spec's "assignment-equals-ownership" list named only Pokédex/Card History/Unown and told the Planner to verify CA's shape — CA is **not** assignment=ownership. Publishing the real value is accurate, free (additive field), and matches how the app renders CA. **Flag for Skyler:** trivially changeable to `true` if he wants CA always full-brightness on the site. |
| R4 | PC section display name source — avoid `publish → ui` layering coupling | Add `PERSONAL_COLLECTION_SECTION_ORDER: List<Pair<String,String>>` to `publish/SnapshotSections.kt` (the publish layer's existing "section metadata mirrored from the UI" home, alongside `GENERATIONS`/`SECTION_ORDER`). | Established convention: the publish layer already keeps its own copy of section metadata (`SnapshotSections.kt` header explicitly says "keep in sync with the app"). Keeps `PublishRepository` self-contained; no import from `ui.personalcollection`. |
| R5 | Owned-state flip must "show as a real change" (spec §Diff) but spec also says `computeDiff` needs "no changes" | **`computeDiff` IS changed** (minimally): same `slotId`, same non-null `cardId`, but `owned` differs → emit one `REPLACED` delta. | The spec is internally inconsistent here — because PC represents an unowned card as a **present** slot (cardId set, so its image ships), an owned flip leaves `cardId` unchanged and the current cardId-only diff yields **zero deltas → `NoChanges` → publish uploads nothing**. That directly breaks the P0 flow "toggle owned → Publish → site updates." Reusing `REPLACED` (vs adding a new `ChangeType`) avoids rippling into `ChangeType`, `SlotChange`, the Discord embed, and the out-of-scope website. Existing binders never change `owned` (always `true`), so **no existing test or binder is affected.** |
| R6 | PC restore behavior for cardIds with no local entry/cache row yet | Restore **upserts `PersonalCollectionEntry(cardId, owned=true)`** for every owned PC snapshot slot regardless of cache/entry presence; for `owned=false` slots, **deletes** any existing owned entry. Never writes `personal_collection_cache`. | Ownership lives in the separate `personal_collection_entry` table, independent of cache. A fresh reinstall has an empty entry table — restricting to "existing entries only" would restore nothing, defeating restore's purpose. This does **not** "fabricate cache rows" (the spec's actual prohibition is about cache metadata/images, which the network refreshes) — it restores ownership bits, which is exactly what restore is for. When the cache later refreshes via pull-to-refresh, owned states line up by `cardId`. |
| R7 | CA restore — restore `owned` too, or only card assignment? | On assign: restore `owned = snap.owned`. On clear: `owned = false`. | Round-trip consistency with R3 (we publish CA `owned`, so we restore it). Harmless; keeps CA fully reconstructable. |
| R8 | PC "skipped" restore accounting | PC restore contributes to `restored`/`cleared` but **not** `skipped`. | Ownership restore never depends on a local row existing (R6), so there is no "snapshot slot with no local target" concept for PC. (CA and Pokédex/Unown keep their existing skipped semantics.) |

---

## 2. New constants

**In `publish/PublishRepository.kt`** (top-level `private const`, next to the existing `BINDER_ID_*`):
```kotlin
private const val BINDER_ID_CONNECTING_ART = "connectingArt"
private const val BINDER_NAME_CONNECTING_ART = "Connecting Art"
private const val BINDER_ID_PERSONAL_COLLECTION = "personalCollection"
private const val BINDER_NAME_PERSONAL_COLLECTION = "Personal Collection"
```

**In `publish/RestoreRepository.kt`** (next to the existing `BINDER_ID_POKEDEX`/`BINDER_ID_UNOWN`):
```kotlin
private const val BINDER_ID_CONNECTING_ART = "connectingArt"
private const val BINDER_ID_PERSONAL_COLLECTION = "personalCollection"
```

**In `publish/SnapshotSections.kt`** (new top-level val, mirrors `PERSONAL_COLLECTION_SECTIONS`):
```kotlin
/**
 * Personal Collection fixed sections: pokemonKey -> display name, in app order.
 * Mirrors PERSONAL_COLLECTION_SECTIONS in ui/personalcollection/PersonalCollectionViewModel.kt —
 * keep the two lists in sync if the 5 Pokémon or their order change.
 */
val PERSONAL_COLLECTION_SECTION_ORDER: List<Pair<String, String>> = listOf(
    "charizard" to "Charizard",
    "celebi" to "Celebi",
    "leafeon" to "Leafeon",
    "tangela" to "Tangela",
    "minccino_cinccino" to "Minccino & Cinccino"
)
```

**Binder id string values are contract with the (out-of-scope) website's second shelf** — use exactly `"connectingArt"` and `"personalCollection"` (camelCase, matching the existing `"cardHistory"`).

---

## 3. Model change — `SnapshotSlot.owned`

**File:** `publish/model/BinderSnapshot.kt`

Add as the **last** field of `SnapshotSlot`, with a default:
```kotlin
@JsonClass(generateAdapter = true)
data class SnapshotSlot(
    val dexNumber: Int,
    val slotName: String,
    val slotType: String,
    val slotId: String,
    val cardId: String?,
    val cardName: String?,
    val cardSet: String?,
    val imageUrl: String?,
    val owned: Boolean = true          // NEW — additive, backward-compatible
)
```

**Backward-compat confirmation (do not skip):**
- Moshi codegen (`@JsonClass(generateAdapter = true)`) honors Kotlin default values: old `binder.json` produced before this field will **deserialize with `owned = true`** (field absent → default). No migration, no schema bump.
- Trailing param with a default → **every existing `SnapshotSlot(...)` construction and every test builder compiles unchanged** and produces `owned = true`. Pokédex (`toSnapshotSlot`), Card History, and Unown branches are **not touched** — they inherit `owned = true` implicitly, satisfying the "no explicit-false regression" P0.

---

## 4. `PublishRepository` changes

**File:** `publish/PublishRepository.kt`

### 4a. Constructor — add two dependencies
```kotlin
@Singleton
class PublishRepository @Inject constructor(
    private val gitHubApi: GitHubApi,
    private val discordApi: DiscordApi,
    private val moshi: Moshi,
    private val publishSettingsRepository: PublishSettingsRepository,
    private val binderRepository: BinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao,
    private val unownBinderRepository: UnownBinderRepository,
    private val connectingArtRepository: ConnectingArtRepository,          // NEW
    private val personalCollectionRepository: PersonalCollectionRepository // NEW
)
```
Add imports: `com.skyler.pokedexbinder.data.local.ConnectingArtGroup`, `…ConnectingArtSlot`, `…PersonalCollectionCache`, `…PersonalCollectionEntry`, `com.skyler.pokedexbinder.repository.ConnectingArtRepository`, `com.skyler.pokedexbinder.repository.PersonalCollectionRepository`.

### 4b. `publish()` — fetch the new data (content-gated, NOT config-gated)
In `publish()`, right after the `unownEntries` line (currently line ~105), add:
```kotlin
// Content-gated binders — always read (no PublishConfig toggle, per spec non-goal).
val connectingArtGroups = connectingArtRepository.getAllGroups()
val connectingArtSlots = connectingArtRepository.getAllSlots()
val personalCache = personalCollectionRepository.getAllCache()
val personalEntries = personalCollectionRepository.getAllEntries()
val nextSnapshot = buildSnapshot(
    entries, secondaryEntries, unownEntries, config,
    connectingArtGroups, connectingArtSlots, personalCache, personalEntries
)
```
(Replaces the existing `val nextSnapshot = buildSnapshot(entries, secondaryEntries, unownEntries, config)` line.)

### 4c. `buildSnapshot()` — new signature (trailing defaulted params keep all existing callers valid)
```kotlin
internal fun buildSnapshot(
    entries: List<MainBinderEntry>,
    secondaryEntries: List<SecondaryBinderEntry>,
    unownEntries: List<UnownBinderEntry> = emptyList(),
    config: PublishConfig,
    connectingArtGroups: List<ConnectingArtGroup> = emptyList(),      // NEW
    connectingArtSlots: List<ConnectingArtSlot> = emptyList(),        // NEW
    personalCache: List<PersonalCollectionCache> = emptyList(),       // NEW
    personalEntries: List<PersonalCollectionEntry> = emptyList()      // NEW
): BinderSnapshot
```
> The 4 new params are trailing and defaulted, so the existing 4-positional test calls `buildSnapshot(entries, emptyList(), emptyList(), defaultConfig)` still compile.

### 4d. Connecting Art branch — insert after the `if (config.publishUnown) { … }` block, before the `return BinderSnapshot(...)`
```kotlin
// Connecting Art — content-gated. Include a group's section only if it has ≥1 assigned slot.
// Emit ALL of an included group's slots (empty ones too) to preserve grid layout.
val caSlotsByGroup = connectingArtSlots.groupBy { it.groupId }
val caSections = connectingArtGroups
    .sortedWith(compareBy({ it.position }, { it.id }))
    .mapNotNull { group ->
        val groupSlots = caSlotsByGroup[group.id].orEmpty()
        if (groupSlots.none { it.cardId != null }) return@mapNotNull null   // skip empty group
        SnapshotSection(
            name = group.name,
            slots = groupSlots
                .sortedBy { it.slotIndex }
                .map { slot ->
                    SnapshotSlot(
                        dexNumber = 0,
                        slotName = "${group.name} #${slot.slotIndex + 1}",
                        slotType = SLOT_TYPE_BASE,
                        slotId = "ca-${group.id}-${slot.slotIndex}",
                        cardId = slot.cardId,
                        cardName = slot.cardName,
                        cardSet = null,                 // CA slots carry no set name
                        imageUrl = slot.cardImageUrl,
                        owned = slot.owned              // R3
                    )
                }
        )
    }
if (caSections.isNotEmpty()) {
    binders += SnapshotBinder(BINDER_ID_CONNECTING_ART, BINDER_NAME_CONNECTING_ART, caSections)
}
```

### 4e. Personal Collection branch — insert right after the CA branch
```kotlin
// Personal Collection — content-gated. One section per fixed Pokémon section that has ≥1 cache row.
// Publish ALL cached cards (owned + unowned); owned flag from personal_collection_entry.
val pcOwnedIds = personalEntries.filter { it.owned }.map { it.cardId }.toSet()
val pcCacheByKey = personalCache.groupBy { it.pokemonKey }
val pcSections = PERSONAL_COLLECTION_SECTION_ORDER.mapNotNull { (key, title) ->
    val rows = pcCacheByKey[key].orEmpty()
    if (rows.isEmpty()) return@mapNotNull null          // skip empty section
    SnapshotSection(
        name = title,
        slots = rows
            .sortedByDescending { it.releaseDate }       // same ordering as the app's cache query
            .map { row ->
                SnapshotSlot(
                    dexNumber = 0,
                    slotName = row.name,
                    slotType = SLOT_TYPE_BASE,
                    slotId = row.cardId,                 // cardId is the PK — globally unique
                    cardId = row.cardId,
                    cardName = row.name,
                    cardSet = row.setName,
                    imageUrl = row.imageUrl,
                    owned = row.cardId in pcOwnedIds     // R6: absent entry => false
                )
            }
    )
}
if (pcSections.isNotEmpty()) {
    binders += SnapshotBinder(BINDER_ID_PERSONAL_COLLECTION, BINDER_NAME_PERSONAL_COLLECTION, pcSections)
}
```

### 4f. `computeDiff()` — add the owned-flip branch (R5)
Inside the `else` (non-first-publish) `when` over each `slotId`, **add one branch** before the trailing "both null / equal → no change" comment. `baselineSlot`/`nextSlot` are guaranteed non-null when their cardId is non-null:
```kotlin
baselineCardId != null && nextCardId != null &&
    baselineCardId == nextCardId &&
    baselineSlot!!.owned != nextSlot!!.owned -> {
    deltas += SlotDelta(
        type = ChangeType.REPLACED,
        slotId = slotId,
        displayName = nextSlot.cardName ?: nextSlot.slotName,
        cardSet = nextSlot.cardSet
    )
}
```
> Note the existing `baselineCardId != null && nextCardId != null && baselineCardId != nextCardId → REPLACED` branch stays. The new branch handles the **equal cardId, changed owned** case. `pokedexComplete` counting is unaffected (it only counts `pokedex` binder BASE slots with a card — CA/PC binders have different ids). No change to the `isFirstPublish` branch.

**First-publish consequence to be aware of (document, don't "fix"):** on the very first publish that includes PC, every cached card (owned *and* unowned) has a non-null `cardId`, so the `isFirstPublish` branch lists each as an `ADDED` delta — potentially a large `changes` list / Discord embed. This is inherent to "publish all cached cards" (spec goal 3) and is acceptable. See §10 gaps for the Discord-embed size note.

---

## 5. `ConnectingArt` read + restore-write methods

### 5a. `data/local/ConnectingArtDao.kt` — add
```kotlin
@Query("SELECT * FROM connecting_art_slot ORDER BY groupId ASC, slotIndex ASC")
suspend fun getAllSlots(): List<ConnectingArtSlot>

@Update
suspend fun updateSlots(slots: List<ConnectingArtSlot>)
```
(`getGroups(): List<ConnectingArtGroup>` suspend already exists. `@Update` on a `List` is supported by Room and matches rows by PK `id`.)

### 5b. `repository/ConnectingArtRepository.kt` — add
```kotlin
suspend fun getAllGroups(): List<ConnectingArtGroup> = dao.getGroups()
suspend fun getAllSlots(): List<ConnectingArtSlot> = dao.getAllSlots()
suspend fun updateSlots(slots: List<ConnectingArtSlot>) = dao.updateSlots(slots)
```

## 6. `PersonalCollection` read methods

### 6a. `data/local/PersonalCollectionDao.kt` — add
```kotlin
@Query("SELECT * FROM personal_collection_cache ORDER BY releaseDate DESC")
suspend fun getAllCache(): List<PersonalCollectionCache>

@Query("SELECT * FROM personal_collection_entry")
suspend fun getAllEntries(): List<PersonalCollectionEntry>
```

### 6b. `repository/PersonalCollectionRepository.kt` — add
```kotlin
suspend fun getAllCache(): List<PersonalCollectionCache> = dao.getAllCache()
suspend fun getAllEntries(): List<PersonalCollectionEntry> = dao.getAllEntries()
```
(Restore reuses the existing `setOwned(cardId, true)` and `removeOwned(cardId)` — no new PC write method needed.)

---

## 7. `RestoreRepository` changes

**File:** `publish/RestoreRepository.kt`

### 7a. Constructor — add two dependencies
```kotlin
@Singleton
class RestoreRepository @Inject constructor(
    private val publishRepository: PublishRepository,
    private val publishSettingsRepository: PublishSettingsRepository,
    private val binderRepository: BinderRepository,
    private val unownBinderRepository: UnownBinderRepository,
    private val connectingArtRepository: ConnectingArtRepository,          // NEW
    private val personalCollectionRepository: PersonalCollectionRepository // NEW
)
```
Add imports for both repositories + `ConnectingArtSlot`.

### 7b. Connecting Art overlay — insert after the existing Unown overlay block, before `currentStep = RestoreStep.Done(...)`
```kotlin
// --- Connecting Art overlay — match local slots by "ca-<groupId>-<slotIndex>" key ---
val caSnapshotSlots: Map<String, SnapshotSlot> = snapshot.binders
    .firstOrNull { it.id == BINDER_ID_CONNECTING_ART }
    ?.sections.orEmpty()
    .flatMap { it.slots }
    .associateBy { it.slotId }
if (caSnapshotSlots.isNotEmpty()) {
    val localSlots = connectingArtRepository.getAllSlots()
    val toUpdate = mutableListOf<com.skyler.pokedexbinder.data.local.ConnectingArtSlot>()
    localSlots.forEach { slot ->
        val key = "ca-${slot.groupId}-${slot.slotIndex}"
        val snap = caSnapshotSlots[key] ?: return@forEach
        when {
            snap.cardId != null -> {
                restored++
                toUpdate += slot.copy(
                    cardId = snap.cardId,
                    cardName = snap.cardName,
                    cardImageUrl = snap.imageUrl,
                    owned = snap.owned            // R7
                )
            }
            slot.cardId != null -> {
                cleared++
                toUpdate += slot.copy(
                    cardId = null, cardName = null, cardImageUrl = null, owned = false
                )
            }
            // else: both empty — no-op
        }
    }
    val localCaKeys = localSlots.map { "ca-${it.groupId}-${it.slotIndex}" }.toSet()
    skipped += caSnapshotSlots.keys.count { it !in localCaKeys }
    if (toUpdate.isNotEmpty()) connectingArtRepository.updateSlots(toUpdate)
}
```

### 7c. Personal Collection overlay — insert right after the CA overlay
```kotlin
// --- Personal Collection overlay — restore OWNED state onto personal_collection_entry by cardId.
// Does NOT write personal_collection_cache (cache is network-refreshed separately). R6/R8. ---
val pcSnapshotSlots: List<SnapshotSlot> = snapshot.binders
    .firstOrNull { it.id == BINDER_ID_PERSONAL_COLLECTION }
    ?.sections.orEmpty()
    .flatMap { it.slots }
    .orEmpty()
if (pcSnapshotSlots.isNotEmpty()) {
    val locallyOwned = personalCollectionRepository.getAllEntries()
        .filter { it.owned }.map { it.cardId }.toSet()
    pcSnapshotSlots.forEach { snap ->
        val cardId = snap.cardId ?: return@forEach   // PC slots always carry cardId; guard anyway
        val isLocallyOwned = cardId in locallyOwned
        when {
            snap.owned && !isLocallyOwned -> {
                restored++
                personalCollectionRepository.setOwned(cardId, true)
            }
            !snap.owned && isLocallyOwned -> {
                cleared++
                personalCollectionRepository.removeOwned(cardId)
            }
            // else: already matches snapshot — no-op
        }
    }
}
```
> Both blocks are **fully guarded**: an old snapshot without the `"connectingArt"` / `"personalCollection"` binder yields an empty map/list → the block is skipped, local data untouched, no crash (mirrors the Unown `isNotEmpty()` guard added last session).

---

## 8. Navigation change

### 8a. `ui/navigation/AppNavigation.kt` — remove the Unown drawer item
Delete this block (currently lines 112–117):
```kotlin
NavigationDrawerItem(
    label = { Text("Unown") },
    selected = isSelected(Screen.Unown.route),
    onClick = { closeDrawer(); navigateTo(Screen.Unown.route) },
    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
)
```
**Leave everything else Unown untouched:** `Screen.Unown`, `Screen.UnownSearch`, the two `composable(...)` route registrations, `UnownBinderScreen`, VM/repo/dao/entity, TCGCSV flow — all unchanged.

### 8b. `ui/navigation/AppNavigation.kt` — pass an Unown launcher into the PC screen
Change the PC composable registration (currently line 245–247):
```kotlin
composable(Screen.PersonalCollection.route) {
    PersonalCollectionScreen(
        onOpenDrawer = { openDrawer() },
        onOpenUnown = { navigateTo(Screen.Unown.route) }   // NEW — same nav used by the old drawer item
    )
}
```
(Uses the existing `navigateTo(...)` helper so back-stack/`restoreState` behavior matches the old drawer entry exactly.)

### 8c. `ui/personalcollection/PersonalCollectionScreen.kt` — add the 6th "navigate-away" entry
1. **Signature:** add the callback param:
```kotlin
fun PersonalCollectionScreen(
    onOpenDrawer: () -> Unit,
    onOpenUnown: () -> Unit,                    // NEW
    viewModel: PersonalCollectionViewModel = hiltViewModel()
)
```
2. **Add the Unown chip** to the jump-chip `Row` (the `Row` at lines 98–115), appended **after** the `PERSONAL_COLLECTION_SECTIONS.forEach { … }` loop, still inside the `Row`:
```kotlin
// 6th entry — navigates away to the full Unown 28-slot screen; does NOT expand inline.
// Visually distinct (trailing arrow) to signal "opens a new screen".
AssistChip(
    onClick = onOpenUnown,
    label = { Text("Unown") },
    trailingIcon = {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(AssistChipDefaults.IconSize)
        )
    }
)
```
Add import `androidx.compose.material.icons.automirrored.filled.ArrowForward` (and `androidx.compose.foundation.layout.size` is already covered by `layout.*`).

3. **Do NOT** give Unown an entry in `PERSONAL_COLLECTION_SECTIONS`, `collapsedSections`, `sectionItemIndex`, `pendingScrollTo`, or the `LazyVerticalGrid` loop. It is a standalone nav trigger only — it never expands an inline grid and must not participate in the scroll-jump index math (which would shift real section indices).

> **Reachability note (documented edge, see §10):** the chip `Row` lives inside the final `else` branch. During the *very first* cold load (empty cache → auto-refresh spinner) or a hard error with zero cards, the chip is briefly not shown. Normal (cached) state renders the `Row`, so Unown is reachable. Accept as a minor transient limitation; do not restructure the screen. If the Reviewer deems it unacceptable, the fix is to hoist the chip `Row` above the `if (isRefreshing …) / else if (error …) / else` switch — noted, not done, to keep scope tight.

---

## 9. Tests

### 9a. `app/src/test/.../publish/PublishRepositoryTest.kt` — MODIFY
- **Constructor/mocks:** add two relaxed mocks and pass them to the `PublishRepository(...)` construction in `setUp()`:
```kotlin
private val connectingArtRepository = mockk<ConnectingArtRepository>(relaxed = true)
private val personalCollectionRepository = mockk<PersonalCollectionRepository>(relaxed = true)
// …
repository = PublishRepository(
    gitHubApi, discordApi, moshi, publishSettingsRepository, binderRepository,
    secondaryBinderDao, unownBinderRepository,
    connectingArtRepository, personalCollectionRepository
)
```
Relaxed mocks return empty lists for the new suspend `getAll*()` reads, so **existing `publish {}` flow tests keep passing** (CA/PC produce no binders when empty).
- **Add builders:**
```kotlin
private fun caGroup(id: Int, name: String, position: Int = id) =
    ConnectingArtGroup(id = id, name = name, rows = 1, cols = 2, position = position)
private fun caSlot(groupId: Int, slotIndex: Int, cardId: String? = null, owned: Boolean = false) =
    ConnectingArtSlot(
        id = groupId * 100 + slotIndex, groupId = groupId, slotIndex = slotIndex,
        cardId = cardId, cardName = cardId?.let { "Card $it" },
        cardImageUrl = cardId?.let { "https://img/$it" }, owned = owned
    )
private fun pcCache(cardId: String, key: String, name: String = "Card $cardId", release: String = "2020-01-01") =
    PersonalCollectionCache(cardId = cardId, pokemonKey = key, name = name,
        imageUrl = "https://img/$cardId", setName = "Set", releaseDate = release)
private fun pcEntry(cardId: String, owned: Boolean) = PersonalCollectionEntry(cardId, owned)
```
- **New `buildSnapshot` / `computeDiff` tests** (call `buildSnapshot` directly with the new trailing args by name, e.g. `repository.buildSnapshot(emptyList(), emptyList(), emptyList(), defaultConfig, connectingArtGroups = …, connectingArtSlots = …)`):
  1. `buildSnapshot includes connectingArt binder when a group has an assigned slot` — 1 group, slots idx0 assigned + idx1 empty → binder present, section name == group name, 2 slots, `slotId == "ca-1-0"`, first slot `cardId` set, `owned` reflects input.
  2. `buildSnapshot omits connectingArt binder when no slots assigned` — group with all-empty slots → `binders.none { it.id == "connectingArt" }`.
  3. `buildSnapshot skips empty connectingArt group but keeps a non-empty one` — group1 empty, group2 assigned → connectingArt binder has exactly one section (group2).
  4. `buildSnapshot connectingArt slotId encodes group id and slot index` — assert exact `"ca-2-1"` for group2/idx1.
  5. `buildSnapshot includes personalCollection binder with all cached cards and owned flags` — cache: 2 charizard rows; entries: one owned → Charizard section has 2 slots, one `owned == true`, other `owned == false`, both `cardId != null`.
  6. `buildSnapshot omits personalCollection binder when cache empty`.
  7. `buildSnapshot skips empty personalCollection section` — cache only for `charizard` → only "Charizard" section present, no others.
  8. `buildSnapshot personalCollection section name uses display title` — assert section name == "Minccino & Cinccino" for key `minccino_cinccino`.
  9. `computeDiff owned flip on same card is REPLACED` — `baseline` slot (`cardId="c1", owned=false`), `next` (`cardId="c1", owned=true`), same `slotId` → 1 delta, `REPLACED`. (Build the two `BinderSnapshot`s directly; can put the slot under a `"personalCollection"` binder or reuse the `binderWith` helper — extend `slot(...)` helper to accept `owned`.)
  10. `computeDiff owned unchanged same card is no change` — both `owned=true`, same card → empty deltas (guards R5 against over-firing).
  11. `buildSnapshot connectingArt and personalCollection slots do not inflate pokedexComplete` — include a pokedex BASE slot + CA/PC content → `diff.pokedexComplete == 1`.

### 9b. `app/src/test/.../publish/RestoreRepositoryTest.kt` — MODIFY
- **Constructor/mocks:** add
```kotlin
private val connectingArtRepository = mockk<ConnectingArtRepository>(relaxed = true)
private val personalCollectionRepository = mockk<PersonalCollectionRepository>(relaxed = true)
```
In `setUp()`, add default stubs and pass to constructor:
```kotlin
coEvery { connectingArtRepository.getAllSlots() } returns emptyList()
coEvery { personalCollectionRepository.getAllEntries() } returns emptyList()
repository = RestoreRepository(
    publishRepository, publishSettingsRepository, binderRepository,
    unownBinderRepository, connectingArtRepository, personalCollectionRepository
)
```
(Existing Pokédex/Unown restore tests keep passing — the new blocks see empty snapshot maps and are skipped.)
- **Add builders** for `ConnectingArtSlot` locals + `connectingArt`/`personalCollection` snapshot binders (reuse `snapshotSlot(...)`, which now defaults `owned=true`; add an `owned` param to the helper for PC tests).
- **New tests:**
  1. `connecting art overlay restores and clears slots by group and position` — snapshot `connectingArt` binder: slot `"ca-1-0"` cardId set, `"ca-1-1"` null; local slots: g1/idx0 empty, g1/idx1 has card → `restored==1`, `cleared==1`; capture `updateSlots` arg, assert idx0 got the card and idx1 cleared.
  2. `connecting art overlay counts snapshot slots with no local match as skipped` — snapshot has `"ca-9-0"`, no local group 9 → `skipped == 1`, `updateSlots` not called (or called with empty — assert no-op).
  3. `old snapshot without connecting art binder leaves connecting art untouched` — Pokédex-only snapshot → `coVerify(exactly = 0) { connectingArtRepository.updateSlots(any()) }`.
  4. `personal collection overlay restores owned by cardId` — snapshot `personalCollection` binder: cardA `owned=true`, cardB `owned=false`; local entries: A absent, B owned → `restored==1` (A), `cleared==1` (B); `coVerify { personalCollectionRepository.setOwned("cardA", true) }` and `coVerify { personalCollectionRepository.removeOwned("cardB") }`.
  5. `personal collection restores owned for a card with no local entry yet` (R6 edge) — snapshot cardX `owned=true`, `getAllEntries()` returns empty → `setOwned("cardX", true)` called, `restored==1`. Proves cache/entry presence is not required.
  6. `personal collection overlay never writes the cache` — after a PC restore, `coVerify(exactly = 0)` on any cache-writing repo method is not directly expressible (repo mock), so instead assert only `setOwned`/`removeOwned` are invoked (the repo exposes no cache-write method reachable from restore) — document that restore touches only the entry table by construction.
  7. `old snapshot without personal collection binder leaves owned untouched` — Pokédex-only snapshot → `coVerify(exactly = 0) { personalCollectionRepository.setOwned(any(), any()) }` and `… removeOwned(any())`.
  8. `owned flag round-trips through publish then restore` (spec P0) — build a PC snapshot via `PublishRepository.buildSnapshot(...)` with one owned + one unowned cache row, feed it as the restore baseline, local entries empty → assert `setOwned(ownedCardId, true)` called and `setOwned`/`removeOwned` NOT called for the unowned card. (Cross-checks §4e ↔ §7c.)

### 9c. `app/src/test/.../ui/navigation/AppNavigationScreenTest.kt` — MODIFY (route-contract guard only)
No Compose-UI instrumentation harness exists (§0), so the drawer-removal + PC-entry relocation is **verified on-device by Skyler** (matches the spec's "verified by Skyler on-device" success criteria). Add one cheap JVM guard so the nav target can't silently drift:
```kotlin
@Test
fun `unown route is stable so the personal collection entry point stays valid`() {
    assertEquals("unown", Screen.Unown.route)
    assertEquals("personal_collection", Screen.PersonalCollection.route)
}
```
Existing `Screen.UnownSearch` encode/decode tests are unaffected (that route is unchanged) and must stay green.

---

## 10. Edge cases & gaps (call-outs for Coder/Tester/Reviewer)

- **Empty CA (zero groups):** `connectingArtGroups` empty → `caSections` empty → no `connectingArt` binder. ✔
- **CA groups exist but all slots empty:** every group fails the `groupSlots.none { it.cardId != null }` guard → `caSections` empty → binder omitted. ✔
- **Empty PC cache:** `pcSections` empty → binder omitted. ✔
- **PC cache present, zero owned:** binder still published (all cards shown, all `owned=false`, site dims all). ✔ (Matches spec goal 3.)
- **Restore against a live cache that dropped a card:** PC restore writes only the `entry` table by `cardId`; the missing cache row is **not** fabricated. When cache next refreshes and the card returns, its restored owned state applies. If it never returns, a harmless orphan `entry` row remains (owned bit with no visible card) — acceptable; the app already tolerates entry rows without cache rows. ✔
- **Owned-flip diff (R5):** covered — same card + changed owned → `REPLACED`, so publish proceeds and re-uploads. Negative case (owned unchanged) tested to prevent spurious deltas.
- **First PC publish delta volume:** every cached card lists as `ADDED` (§4f note). The Discord embed builder (`buildEmbed`) may render a long list — **Tester/Reviewer: confirm `DiscordEmbedBuilder` already caps/truncates the changes list** (there is an existing `DiscordEmbedBuilderTest`); if it does not, that is a pre-existing concern, not introduced here — log to `gaps.md`, do not expand scope.
- **CA `slotId` stability across group delete/recreate:** `group.id` autoincrements, so deleting and recreating a group yields new ids → its slots diff as `REMOVED` (old ids) + `ADDED` (new ids) on the next publish. This is correct behavior (it *is* a different group); noted so it isn't mistaken for a bug.
- **Unown reachability during PC cold-load spinner (§8c note):** transient; documented, not fixed.
- **No `di/` changes:** confirm the build's Hilt graph still compiles after adding constructor params (both new deps are `@Singleton @Inject`, DAOs already provided). A clean `:app:kaptDebugKotlin`/`assembleDebug` is the real check.

---

## 11. Task breakdown (dependency-ordered, sized)

| # | Task | Files | Type | Size |
|---|------|-------|------|------|
| T1 | Add `owned: Boolean = true` to `SnapshotSlot` | `publish/model/BinderSnapshot.kt` | MODEL | S |
| T2 | Add CA read/update DAO+repo methods (`getAllSlots`, `updateSlots`) | `data/local/ConnectingArtDao.kt`, `repository/ConnectingArtRepository.kt` | MODEL | S |
| T3 | Add PC read DAO+repo methods (`getAllCache`, `getAllEntries`) | `data/local/PersonalCollectionDao.kt`, `repository/PersonalCollectionRepository.kt` | MODEL | S |
| T4 | Add `PERSONAL_COLLECTION_SECTION_ORDER` + binder-id constants | `publish/SnapshotSections.kt`, `publish/PublishRepository.kt`, `publish/RestoreRepository.kt` | CORE | S |
| T5 | `PublishRepository` constructor + `publish()` reads + `buildSnapshot` CA/PC branches | `publish/PublishRepository.kt` | CORE | M |
| T6 | `computeDiff` owned-flip `REPLACED` branch | `publish/PublishRepository.kt` | CORE | S |
| T7 | `RestoreRepository` constructor + CA overlay + PC overlay | `publish/RestoreRepository.kt` | CORE | M |
| T8 | Remove Unown drawer item; pass `onOpenUnown` to PC screen | `ui/navigation/AppNavigation.kt` | UI | S |
| T9 | PC screen `onOpenUnown` param + 6th navigate-away chip | `ui/personalcollection/PersonalCollectionScreen.kt` | UI | M |
| T10 | Extend `PublishRepositoryTest` (constructor + buildSnapshot/computeDiff cases) | `test/.../publish/PublishRepositoryTest.kt` | TEST | M |
| T11 | Extend `RestoreRepositoryTest` (constructor + CA/PC overlay cases) | `test/.../publish/RestoreRepositoryTest.kt` | TEST | M |
| T12 | Add nav route-contract guard test | `test/.../ui/navigation/AppNavigationScreenTest.kt` | TEST | S |

**Dependency order:** T1 → (T2, T3, T4 parallel) → T5 → T6 → T7 → (T8, T9 parallel) → (T10, T11, T12 parallel). T1 must land first (everything references `owned`). T5 depends on T1–T4. T7 depends on T1–T4. Tests last.

## 12. Definition of done
- `./gradlew :app:testDebugUnitTest` green, including all new cases in T10–T12 and every pre-existing publish/restore/nav test.
- `./gradlew :app:assembleDebug` compiles (Hilt graph resolves the two new constructor deps; no `di/` edits).
- No new Settings UI, no `PublishConfig` field, no auto-publish/WorkManager/debounce code anywhere (grep-confirm).
- `binder.json` emitted by a manual publish contains a `connectingArt` binder iff ≥1 CA slot is assigned, and a `personalCollection` binder iff ≥1 cache row exists; each PC slot carries an accurate `owned`.
- On-device (Skyler): add a card to a CA group + a PC section → Publish → both appear on the site (PC dim/undim accurate); empty CA group / empty PC section absent; Unown reachable via the PC screen chip with the drawer item gone; toggling a PC card's owned state and re-publishing updates the site.
