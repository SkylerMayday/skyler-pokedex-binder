# Implementation Plan — Unown as Personal Collection Sections

**Source spec:** `docs/superpowers/specs/2026-07-12-unown-as-pc-sections.md` (APPROVED)
**Type:** Removal (standalone Unown feature) + extension (28 Personal Collection sections)
**Stage:** Planner → Coder

This is a *removal-heavy* task. The danger is leaving dead/broken code (dangling Hilt
dependencies, orphaned imports, broken constructor arity in tests). Every touch point below was
verified by reading the current source. Coder: implement EXACTLY this. Do not improvise extra
scope.

---

## 0. Summary of the change

1. Delete the standalone Unown feature entirely: entity, DAO, repository, ViewModel, screen, nav
   routes, publish/restore branches, settings toggle, and its tests.
2. Revert the Room database from version 8 back to version 7 (Unown was the *only* thing v8 added
   — justified in §2).
3. Add 28 new sections (`A`–`Z`, `!`, `?`) to `PERSONAL_COLLECTION_SECTIONS`, so Unown letters
   behave identically to the existing 5 sections (auto-search + owned/unowned toggle + collapsible
   header + jump-chip). Also add them to the publish-layer mirror list
   `PERSONAL_COLLECTION_SECTION_ORDER` so they publish/restore for free.

Net result: Personal Collection shows **33 sections** (5 original + 28 Unown). No new Room table,
no new screen, no new nav route.

---

## 1. Files to DELETE (whole file)

| # | Path | Reason |
|---|------|--------|
| D1 | `app/src/main/java/com/skyler/pokedexbinder/data/local/UnownBinderEntry.kt` | Room entity removed |
| D2 | `app/src/main/java/com/skyler/pokedexbinder/data/local/UnownBinderDao.kt` | DAO removed |
| D3 | `app/src/main/java/com/skyler/pokedexbinder/repository/UnownBinderRepository.kt` | Repo removed (constants no longer needed once §3 hardcodes the letters) |
| D4 | `app/src/main/java/com/skyler/pokedexbinder/ui/unown/UnownBinderScreen.kt` | Screen removed |
| D5 | `app/src/main/java/com/skyler/pokedexbinder/ui/unown/UnownBinderViewModel.kt` | ViewModel removed |
| D6 | `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration7to8Test.kt` | Migration 7→8 removed |
| D7 | `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt` | **Verified: all 5 tests are Unown-route-only** — delete whole file |

After deletion, the directory `app/src/main/java/com/skyler/pokedexbinder/ui/unown/` should be
empty; remove the directory too if the tooling leaves it behind.

> Note on D3: `UnownBinderRepository.LETTER_IDS` (`('A'..'Z') + listOf("!","?")`) is reused as the
> *source of the 28 section definitions* in §3, but we inline that expression directly into
> `PersonalCollectionViewModel` rather than keep the file. Do NOT keep `UnownBinderRepository`
> just for the constant — it depends on `UnownBinderDao` which is being deleted, so keeping it
> would break the build.

---

## 2. Room migration decision — REVERT TO VERSION 7

**Decision: revert `PokedexDatabase` to `version = 7`, remove `MIGRATION_7_8` and the
`UnownBinderEntry` entity entirely. Do NOT keep a no-op v8 migration.**

### Justification (verified against the codebase)

- Version 8 was introduced **solely** for `unown_binder`. `MIGRATION_7_8` (PokedexDatabase.kt
  lines 90–102) creates only the `unown_binder` table and nothing else.
- The Connecting Art / Personal Collection tables were created in **`MIGRATION_6_7`** (lines
  43–88: `connecting_art_group`, `connecting_art_slot`, `personal_collection_cache`,
  `personal_collection_entry`). Confirmed: the 2026-07-12 CA/PC **publish** work added no new Room
  entity or migration of its own — it reuses those v7 tables (see `PublishRepository`/
  `RestoreRepository`, which only read existing DAOs). So **nothing else depends on v8.**
- Reverting `entities` to the set minus `UnownBinderEntry` and `version = 7` reproduces the
  **exact original v7 schema identity hash**, because v7 *was* precisely today's entity set minus
  `UnownBinderEntry`. A device currently at v7 (the expected real state — per the spec non-goal,
  "nothing has installed v8 in practice") therefore opens with **zero migration and a matching
  identity hash**. Clean.

### Why not "keep version 8 with a no-op migration"

It offers **no real safety advantage** and is more code. Keeping `version = 8` while changing the
entity set changes Room's generated identity hash; a device that somehow *did* reach v8 (old
schema, with `unown_binder`) would then fail Room's same-version identity-hash check and crash —
`fallbackToDestructiveMigration()` does not rescue an identity mismatch. So keep-v8 does not
protect the one edge case (device-already-at-v8) any better than revert-v7 does, and revert-v7 is
simpler and reflects reality.

### Cheap insurance for the device-already-at-v8 edge case

Add `.fallbackToDestructiveMigrationOnDowngrade()` to the `databaseBuilder` chain (see §5,
DatabaseModule edit). Rationale:

- Expected case (device at v7): the flag is **inert** — no downgrade happens, existing Pokédex/
  CA/PC data is untouched, clean open.
- Edge case (device somehow at v8): without the flag, opening a v8 DB with v7 code throws
  `IllegalStateException("Cannot downgrade database…")` → app won't launch until reinstall. With
  the flag, Room destructively recreates at v7 instead of crashing. Per the spec non-goal there is
  no real v8 data to lose, and a hard crash is strictly worse than a clean recreate.
- Downside: if the edge case fires it wipes all tables. Acceptable because (a) the flag only fires
  on an actual downgrade, which shouldn't exist, and (b) the alternative in that same scenario is a
  crash + manual reinstall, which also wipes.

Keep the existing `.fallbackToDestructiveMigration()` call as-is.

---

## 3. Extend `PERSONAL_COLLECTION_SECTIONS` (PersonalCollectionViewModel.kt)

File: `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionViewModel.kt`

Replace the current declaration (lines 18–24):

```kotlin
val PERSONAL_COLLECTION_SECTIONS = listOf(
    PokemonSection("charizard", "Charizard", listOf("Charizard")),
    PokemonSection("celebi", "Celebi", listOf("Celebi")),
    PokemonSection("leafeon", "Leafeon", listOf("Leafeon")),
    PokemonSection("tangela", "Tangela", listOf("Tangela")),
    PokemonSection("minccino_cinccino", "Minccino & Cinccino", listOf("Minccino", "Cinccino"))
)
```

with:

```kotlin
/** The 28 fixed Unown identifiers: A..Z, then "!", then "?" (grid/section order). */
private val UNOWN_LETTERS: List<String> = ('A'..'Z').map { it.toString() } + listOf("!", "?")

val PERSONAL_COLLECTION_SECTIONS: List<PokemonSection> = listOf(
    PokemonSection("charizard", "Charizard", listOf("Charizard")),
    PokemonSection("celebi", "Celebi", listOf("Celebi")),
    PokemonSection("leafeon", "Leafeon", listOf("Leafeon")),
    PokemonSection("tangela", "Tangela", listOf("Tangela")),
    PokemonSection("minccino_cinccino", "Minccino & Cinccino", listOf("Minccino", "Cinccino"))
) + UNOWN_LETTERS.map { letter ->
    PokemonSection(
        key = "unown_$letter",
        title = "Unown $letter",
        queryNames = listOf("Unown $letter")
    )
}
```

### Key naming scheme & collision safety (verified)

- **Keys:** `unown_A` … `unown_Z`, `unown_!`, `unown_?`. Distinct prefix `unown_` — **no collision**
  with the 5 existing keys (`charizard`, `celebi`, `leafeon`, `tangela`, `minccino_cinccino`).
- **`!` / `?` character safety — verified safe.** The section `key` flows into `pokemonKey`, which
  is used two ways, both safe:
  1. Room queries: `PersonalCollectionDao.deleteCacheForPokemon` uses a **bound parameter**
     (`WHERE pokemonKey = :pokemonKey`, DAO line 25). Bound params are not string-interpolated, so
     `!`/`?` cannot break SQL. `replaceCacheForPokemon` (DAO lines 33–36) delegates to it.
  2. In-memory: `groupBy { it.pokemonKey }` and map lookups (`state.sections[section.key]`) — plain
     string equality, `!`/`?` are ordinary chars.
- **cardId vs section key:** the cache/entry primary key is `cardId` (globally unique TCG id from
  search results), **not** the section key. So `!`/`?` never appear in a PK. No PK-collision risk.
- **Query names:** `"Unown A"` … `"Unown ?"` are passed to `CardSearchRepository.searchByName` via
  `refreshPokemon(section.key, section.queryNames)` — same path as every other section. `refreshAll`
  (VM lines 66–80) already iterates `PERSONAL_COLLECTION_SECTIONS` generically, so the 28 new
  sections auto-refresh with no further change.

No other edit needed in this file — `refreshAll`, `toggleOwned`, and `uiState` are all generic over
the section list.

---

## 4. Publish-layer mirror — `PERSONAL_COLLECTION_SECTION_ORDER` (SnapshotSections.kt)

File: `app/src/main/java/com/skyler/pokedexbinder/publish/SnapshotSections.kt`

This list (lines 53–59) is the publish/restore layer's copy of the section order (used by
`PublishRepository.buildSnapshot` line 387 and, via the section iteration, the PC restore overlay).
The 28 Unown sections must be appended here too, or they will **not** be published/restored. The
doc comment already says "keep the two lists in sync."

Replace lines 53–59:

```kotlin
val PERSONAL_COLLECTION_SECTION_ORDER: List<Pair<String, String>> = listOf(
    "charizard" to "Charizard",
    "celebi" to "Celebi",
    "leafeon" to "Leafeon",
    "tangela" to "Tangela",
    "minccino_cinccino" to "Minccino & Cinccino"
)
```

with:

```kotlin
val PERSONAL_COLLECTION_SECTION_ORDER: List<Pair<String, String>> = listOf(
    "charizard" to "Charizard",
    "celebi" to "Celebi",
    "leafeon" to "Leafeon",
    "tangela" to "Tangela",
    "minccino_cinccino" to "Minccino & Cinccino"
) + (('A'..'Z').map { it.toString() } + listOf("!", "?")).map { letter ->
    "unown_$letter" to "Unown $letter"
}
```

Keep this list definition in exact key/title correspondence with §3. (The two lists are
intentionally duplicated across the UI and publish layers per the existing `SECTION_ORDER`
convention — do not try to share one constant across modules.)

The publish path is content-gated: `buildSnapshot` (PublishRepository lines 387–411) already skips
any section with zero cache rows, so empty Unown letters simply won't appear in `binder.json`. No
other publish/restore change is needed for the *new* sections — they ride the existing
`personalCollection` binder path.

---

## 5. Remove standalone Unown wiring (production source edits)

### 5a. `data/local/PokedexDatabase.kt`

- Remove `UnownBinderEntry::class` from the `entities` array (line 16) — leaving the other 6.
- Change `version = 8` → `version = 7` (line 18).
- Remove the abstract DAO accessor `abstract fun unownBinderDao(): UnownBinderDao` (line 26).
- Remove the entire `MIGRATION_7_8` object (lines 90–102).
- Keep `MIGRATION_4_5`, `MIGRATION_5_6`, `MIGRATION_6_7` unchanged.

### 5b. `di/DatabaseModule.kt`

- Remove `PokedexDatabase.MIGRATION_7_8` from the `.addMigrations(...)` call (line 25). Keep 4_5,
  5_6, 6_7.
- Remove the `provideUnownBinderDao` provider (lines 42–43).
- **Add** `.fallbackToDestructiveMigrationOnDowngrade()` to the builder chain (per §2 insurance),
  e.g. immediately after the existing `.fallbackToDestructiveMigration()` (line 27):

```kotlin
        .addMigrations(
            PokedexDatabase.MIGRATION_4_5,
            PokedexDatabase.MIGRATION_5_6,
            PokedexDatabase.MIGRATION_6_7
        )
        .fallbackToDestructiveMigration()
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
```

- The wildcard import `com.skyler.pokedexbinder.data.local.*` (line 5) stays (other DAOs use it);
  no import edit needed here.

### 5c. `publish/PublishRepository.kt`

- Remove import `com.skyler.pokedexbinder.data.local.UnownBinderEntry` (line 12).
- Remove import `com.skyler.pokedexbinder.repository.UnownBinderRepository` (line 35).
- Remove the three constants (lines 55–57): `BINDER_ID_UNOWN`, `BINDER_NAME_UNOWN`,
  `SECTION_UNOWN`.
- Remove the constructor param `private val unownBinderRepository: UnownBinderRepository,` (line
  88).
- In `publish(...)`: remove the `unownEntries` local (line 118:
  `val unownEntries = if (config.publishUnown) unownBinderRepository.getAllEntries() else emptyList()`).
- In the `buildSnapshot(...)` call (lines 125–128): remove the `unownEntries` argument. New call:

```kotlin
            val nextSnapshot = buildSnapshot(
                entries, secondaryEntries, config,
                connectingArtGroups, connectingArtSlots, personalCache, personalEntries
            )
```

- In the `buildSnapshot` signature (lines 283–292): remove the parameter
  `unownEntries: List<UnownBinderEntry> = emptyList(),` (line 286). Resulting signature:

```kotlin
    internal fun buildSnapshot(
        entries: List<MainBinderEntry>,
        secondaryEntries: List<SecondaryBinderEntry>,
        config: PublishConfig,
        connectingArtGroups: List<ConnectingArtGroup> = emptyList(),
        connectingArtSlots: List<ConnectingArtSlot> = emptyList(),
        personalCache: List<PersonalCollectionCache> = emptyList(),
        personalEntries: List<PersonalCollectionEntry> = emptyList()
    ): BinderSnapshot {
```

- Remove the entire `if (config.publishUnown) { ... }` block that builds the Unown snapshot binder
  (lines 330–350).
- Leave everything else (pokedex, card history, connecting art, personal collection blocks)
  untouched.

### 5d. `publish/RestoreRepository.kt`

- Remove import `com.skyler.pokedexbinder.repository.UnownBinderRepository` (line 10).
- Remove the constant `private const val BINDER_ID_UNOWN = "unown"` (line 15).
- Remove the constructor param `private val unownBinderRepository: UnownBinderRepository,` (line
  42).
- Remove the entire "Unown overlay" block (lines 114–149, the comment
  `// --- Unown overlay ...` through `unownBinderRepository.overwriteAll(updatedUnown)`). The
  `restored`/`cleared`/`skipped` counters are shared vars declared earlier and still used by the
  CA and (for restored/cleared) PC overlays, so leave those counters and the surrounding blocks
  intact — only excise the Unown block.

> Regression note: `UnownBinderEntry` is NOT imported in RestoreRepository (verified), so no import
> to remove there beyond the repository import above.

### 5e. `repository/PublishSettingsRepository.kt`

Decision: **remove the `publishUnown` config entirely** (it only gated the now-deleted Unown
publish branch; PC sections publish via the always-on content-gated `personalCollection` path, so
no toggle is needed for the new Unown sections — confirmed against §4 and the spec non-goal).

- In `data class PublishConfig` (lines 22–30): remove the `publishUnown: Boolean = true` field
  (line 29). Also remove the now-trailing comma handling on the line above as needed.
- Remove `val PUBLISH_UNOWN = booleanPreferencesKey("publish_unown")` from `Keys` (line 43).
- In the `config` Flow mapping (lines 64–72): remove
  `publishUnown = prefs[Keys.PUBLISH_UNOWN] ?: true` (line 71).
- Remove the `setPublishUnown(...)` function (lines 103–105).

### 5f. `ui/settings/SettingsViewModel.kt`

- Remove `fun setPublishUnown(v: Boolean) = ...` (line 42).

### 5g. `ui/settings/SettingsScreen.kt`

- Remove the "Publish Unown" `SettingToggleItem` block (lines 258–263) **and** its preceding
  `HorizontalDivider()` (line 257) so two dividers don't stack. Leave the "Publish Card History"
  item + its following divider (lines 251–256) and the "Publish now" button intact. Verify
  `publishConfig.publishUnown` is not referenced elsewhere in the file after this edit (it is the
  only reference — grep confirmed lines 259–262 only).

### 5h. `ui/navigation/AppNavigation.kt`

- Remove imports (lines 32–33):
  `com.skyler.pokedexbinder.ui.unown.UnownBinderScreen`,
  `com.skyler.pokedexbinder.ui.unown.UnownBinderViewModel`.
- Remove the `Screen.Unown` object (line 45) and the `Screen.UnownSearch` object (lines 46–51).
- Remove the `composable(Screen.Unown.route) { ... }` block (lines 210–217).
- Remove the `composable(route = Screen.UnownSearch.route, ...) { ... }` block (lines 218–238),
  which also removes the last reference to `UnownBinderRepository.searchNameFor` (line 228).
- In the `PersonalCollection` composable (lines 239–244): remove the `onOpenUnown` argument so it
  becomes:

```kotlin
                composable(Screen.PersonalCollection.route) {
                    PersonalCollectionScreen(
                        onOpenDrawer = { openDrawer() }
                    )
                }
```

- **Check the now-unused imports** `java.net.URLEncoder` / `java.nio.charset.StandardCharsets`
  (lines 35–36): these are still used by `Screen.QuickScan.createRoute` and
  `Screen.Scanner.createRoute` (they call `URLEncoder.encode` with `StandardCharsets.UTF_8`), so
  **keep both imports.** Do not remove them.

### 5i. `ui/personalcollection/PersonalCollectionScreen.kt`

- Change the composable signature (lines 30–34): remove the `onOpenUnown: () -> Unit,` param:

```kotlin
@Composable
fun PersonalCollectionScreen(
    onOpenDrawer: () -> Unit,
    viewModel: PersonalCollectionViewModel = hiltViewModel()
) {
```

- Remove the **jump-chip** "6th entry" Unown `AssistChip` block (lines 117–129, the comment
  `// 6th entry ...` through the closing `)` of that `AssistChip`). The `forEach` above it (lines
  107–116) already renders a chip for every section in `PERSONAL_COLLECTION_SECTIONS`, which now
  includes the 28 Unown sections — so Unown letters get the standard jump-chip automatically.
- Remove the **bottom-of-list** Unown `item(span = ...)` row (lines 199–220, the comment
  `// Unown — listed last ...` through its closing `}`). The Unown sections now render inside the
  `PERSONAL_COLLECTION_SECTIONS.forEach` loop (lines 137–197) exactly like the other 5.
- Remove the now-unused import `androidx.compose.material.icons.automirrored.filled.ArrowForward`
  (line 14) — verify no other `ArrowForward` usage remains after the two block removals (grep:
  it was used only in those two removed blocks).
- Everything else (collapsible header, `sectionItemIndex`, `pendingScrollTo`, grid, action sheet)
  is already generic over `PERSONAL_COLLECTION_SECTIONS` and needs no change — the 28 Unown
  sections flow through it unchanged.

---

## 6. Test changes

### 6a. Delete (whole file)

- `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt` (D7 above)
  — all 5 tests reference `Screen.Unown` / `Screen.UnownSearch`, which no longer exist.
- `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration7to8Test.kt` (D6 above).

> Verified: no `UnownBinderRepositoryTest` or `UnownBinderViewModelTest` exists (grep returned
> nothing) — nothing else to delete.

### 6b. `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt`

- Remove import `com.skyler.pokedexbinder.data.local.UnownBinderEntry` (line 12).
- Remove import `com.skyler.pokedexbinder.repository.UnownBinderRepository` (line 33).
- Remove the field `private val unownBinderRepository = mockk<UnownBinderRepository>(relaxed = true)`
  (line 57).
- In `defaultConfig` (lines ~64–71): remove the `publishUnown = false` line (and fix the trailing
  comma on the preceding line).
- In the `PublishRepository(...)` construction in `setUp` (lines 85–89): remove the
  `unownBinderRepository,` argument. New:

```kotlin
        repository = PublishRepository(
            gitHubApi, discordApi, moshi, publishSettingsRepository, binderRepository,
            secondaryBinderDao,
            connectingArtRepository, personalCollectionRepository
        )
```

- **Delete the 4 Unown-specific test methods** (they reference the removed `buildSnapshot`
  `unownEntries` param / `publishUnown` / the `unown` binder):
  - `buildSnapshot includes unown binder when publishUnown is on` (lines ~416–431)
  - `buildSnapshot omits unown binder when publishUnown is off` (lines ~433–441)
  - `metadata-only republish does not REPLACE unown slots` (lines ~443–456)
  - `unown BASE slots do not inflate pokedexComplete` (lines ~458–471)
- **Fix every surviving `buildSnapshot(...)` call** that passed a positional `unownEntries`
  argument. The 3rd positional argument was `unownEntries` — delete that `emptyList()`. Verified
  surviving call sites (line numbers pre-edit): 381, 397, 409, 544, 566, 582, 600, 617, 632, 644,
  658, 695. Each currently reads `buildSnapshot(<a>, <b>, emptyList(), <config>, ...)` — remove the
  3rd `emptyList()` so it becomes `buildSnapshot(<a>, <b>, <config>, ...)`. Example (line 544):

```kotlin
        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            connectingArtGroups = groups, connectingArtSlots = slots
        )
```

  (Coder: do this by searching the file for `buildSnapshot(` and removing the unown positional arg
  in each remaining call; the named CA/PC args after `config` are unaffected.)

### 6c. `app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt`

- Remove import `com.skyler.pokedexbinder.data.local.UnownBinderEntry` (line 6).
- Remove import `com.skyler.pokedexbinder.repository.UnownBinderRepository` (line 16).
- Remove the field `private val unownBinderRepository = mockk<UnownBinderRepository>(relaxed = true)`
  (line 33).
- Remove `coEvery { unownBinderRepository.getAllEntries() } returns emptyList()` from `setUp`
  (line 50).
- In the `RestoreRepository(...)` construction in `setUp` (lines 53–56): remove the
  `unownBinderRepository,` argument. New:

```kotlin
        repository = RestoreRepository(
            publishRepository, publishSettingsRepository, binderRepository,
            connectingArtRepository, personalCollectionRepository
        )
```

- Remove the `unownEntry(...)` helper (lines ~328–335).
- **Delete the 2 Unown-specific test methods:**
  - `unown overlay restores and clears letter slots and counts them` (lines ~337–381)
  - `old snapshot without unown binder leaves unown untouched` (lines ~383–397)
- **The PC test near line ~558–566** constructs a `PublishRepository` inline (it has
  `secondaryBinderDao = mockk(relaxed = true)` and `unownBinderRepository = mockk(relaxed = true)`).
  Remove the `unownBinderRepository = mockk(relaxed = true),` line there so the `PublishRepository`
  constructor call matches the new arity from §5c. Verify the remaining args
  (`gitHubApi`/`discordApi`/`moshi`/`publishSettingsRepository`/`binderRepository`/
  `secondaryBinderDao`/`connectingArtRepository`/`personalCollectionRepository`) still line up
  positionally/by-name.
- Leave all Pokédex / Connecting Art / Personal Collection restore tests intact.

### 6d. Extend `app/src/test/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionViewModelTest.kt`

Add tests verifying the 28 new sections exist and behave like the others. `PERSONAL_COLLECTION_SECTIONS`
is a top-level `val` in the same package, directly referable. Add:

```kotlin
    @Test
    fun `section list contains the 5 originals plus 28 unown letters`() {
        assertEquals(33, PERSONAL_COLLECTION_SECTIONS.size)
        val keys = PERSONAL_COLLECTION_SECTIONS.map { it.key }
        // originals still present and first
        assertEquals(
            listOf("charizard", "celebi", "leafeon", "tangela", "minccino_cinccino"),
            keys.take(5)
        )
        // all 28 unown keys present, including the two symbols
        ('A'..'Z').forEach { assertTrue("missing unown_$it", "unown_$it" in keys) }
        assertTrue("unown_!" in keys)
        assertTrue("unown_?" in keys)
        // keys are unique (no collisions)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `each unown section auto-searches with its Unown letter query name`() {
        val sectionA = PERSONAL_COLLECTION_SECTIONS.first { it.key == "unown_A" }
        assertEquals("Unown A", sectionA.title)
        assertEquals(listOf("Unown A"), sectionA.queryNames)
        val sectionQ = PERSONAL_COLLECTION_SECTIONS.first { it.key == "unown_?" }
        assertEquals(listOf("Unown ?"), sectionQ.queryNames)
    }

    @Test
    fun `refreshAll refreshes every one of the 33 sections`() = runTest {
        coEvery { repository.cacheCount() } returns 1
        val vm = PersonalCollectionViewModel(repository)

        vm.refreshAll()

        // one refreshPokemon call per section (5 + 28)
        coVerify(exactly = 33) { repository.refreshPokemon(any(), any()) }
        // spot-check a couple of unown keys are among the refreshed ones
        coVerify(exactly = 1) { repository.refreshPokemon("unown_A", listOf("Unown A")) }
        coVerify(exactly = 1) { repository.refreshPokemon("unown_?", listOf("Unown ?")) }
    }

    @Test
    fun `uiState surfaces cards for an unown section keyed by its section key`() = runTest {
        cacheFlow.value = listOf(cacheRow("u-1", pokemonKey = "unown_A", name = "Unown A"))
        entriesFlow.value = listOf(PersonalCollectionEntry(cardId = "u-1", owned = true))

        val vm = PersonalCollectionViewModel(repository)

        vm.uiState.test {
            val state = awaitItem()
            val cards = state.sections["unown_A"].orEmpty()
            assertTrue(cards.any { it.cardId == "u-1" && it.owned })
            cancelAndIgnoreRemainingEvents()
        }
    }
```

Notes for Coder:
- The `refreshAll refreshes every one of the 33 sections` test replaces reliance on the loose
  `atLeast = 1` — but keep the existing `init triggers refreshAll only when cache is empty` and
  the other existing tests unchanged; they still pass (they use `charizard` cache rows).
- `cacheRow(...)` already accepts `pokemonKey`/`name` params (helper lines 43–51), so the new
  tests reuse it.
- Imports needed by the new tests (`coEvery`, `coVerify`, `runTest`, `assertEquals`, `assertTrue`,
  `PersonalCollectionEntry`, turbine `test`) are all already imported in this file — no new
  imports required.

> No `PersonalCollectionScreenTest` exists (grep found only the ViewModel test in that package),
> so there is no chip/jump UI test file to extend. The screen changes in §5i are covered by manual
> on-device verification per the spec's Success Criteria.

---

## 7. Build/verify checklist for the Coder → Tester handoff

1. `./gradlew :app:compileDebugKotlin` (or the project's build task) — must compile. The most
   likely miss is a dangling reference: grep the whole `app/src` tree for `Unown` and `unown`
   after edits; the only remaining hits should be the new `unown_<letter>` section keys/titles in
   `PersonalCollectionViewModel.kt`, `SnapshotSections.kt`, and the new tests in
   `PersonalCollectionViewModelTest.kt`. **Any hit in `di/`, `publish/`, `ui/navigation/`,
   `ui/settings/`, `data/local/`, or `repository/` (other than the deleted files being gone) is a
   bug.**
2. Confirm no file still imports `com.skyler.pokedexbinder.ui.unown.*`,
   `...repository.UnownBinderRepository`, `...data.local.UnownBinderEntry`, or
   `...data.local.UnownBinderDao`.
3. `./gradlew :app:testDebugUnitTest` — full unit suite green, including the 4 new PC ViewModel
   tests and the trimmed Publish/Restore tests.
4. Room: because `version` drops 7→8→7 in code but the entity set matches the original v7, a fresh
   install builds v7 cleanly and a v7 device opens with no migration. (androidTest for migrations
   now only covers 4_5/5_6/6_7 — `Migration7to8Test` is deleted; do not add a replacement.)
5. On-device (Skyler, manual): open Personal Collection → 33 sections, Unown letters auto-populate
   and toggle owned/unowned like the others; publish → Unown owned cards appear under the
   `personalCollection` binder in `binder.json`.

---

## 8. Explicit non-changes (do NOT touch)

- `PersonalCollectionRepository.refreshPokemon` / `PersonalCollectionDao` — already generic; no
  edit.
- The `personalCollection` publish/restore branches in `PublishRepository`/`RestoreRepository` —
  they already iterate the section list; the 28 new sections ride them for free.
- `MIGRATION_4_5` / `MIGRATION_5_6` / `MIGRATION_6_7` — unchanged.
- `URLEncoder` / `StandardCharsets` imports in `AppNavigation.kt` — still used by QuickScan/Scanner.
