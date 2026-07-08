# Run 1A — Tester Results

Tested against `.pipeline/specs.md` (Planner) and `.pipeline/changes.md` (Coder).
Environment: PowerShell, `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`, `gradlew.bat`, JDK 17.

---

## 1. Migration correctness (MIGRATION_6_7)

**Environment constraint confirmed real, not assumed:** checked for a connected device/emulator before doing anything else.
- `adb devices` → daemon started, zero devices attached.
- `emulator -list-avds` → empty (no AVDs configured).
- This means **no androidTest can actually execute in this environment.** `MigrationTestHelper` requires either a real device/emulator or Robolectric; neither is available (`robolectric` is not in `gradle/libs.versions.toml`, not a project dependency).

**What I found:** a `Migration6to7Test.kt` already existed in `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/` (written by the Coder this Run). It uses `Room.inMemoryDatabaseBuilder` + raw SQL against `db.openHelper.writableDatabase`, executing MIGRATION_6_7's exact DDL (copy-pasted from `PokedexDatabase.kt`) against a hand-built v6-shaped table, then verifies:
- (a) a pre-seeded `main_binder_v6_shape` row survives untouched after the migration DDL runs
- (b) all 4 new tables have the exact expected columns (via `PRAGMA table_info`)
- (c) the `index_connecting_art_slot_groupId` index exists (via `PRAGMA index_list`)
- (d) inserting a `connecting_art_group` row + child `connecting_art_slot` row works, and `DELETE FROM connecting_art_group` cascades to delete the child slot (`PRAGMA foreign_keys = ON` set explicitly)
- A second test exercises the same cascade behavior through the real `ConnectingArtDao` (`createGroupWithSlots` / `deleteGroupById`) against the post-migration Room-validated schema.

This is a real, rigorous test — not a stub. **However, it did not compile as written.** I ran `compileDebugAndroidTestKotlin` and it failed:
```
Migration6to7Test.kt:189:41 Unresolved reference 'first'.
Migration6to7Test.kt:195:58 Unresolved reference 'first'.
```
Root cause: the test called `kotlinx.coroutines.flow.first(flow)` fully-qualified with no import, on a `Flow` returned via `.let`, and the type inference broke as a result — not a missing dependency (`kotlinx-coroutines-core` is transitively present via `implementation(libs.coroutines.android)`, so it's on the androidTest classpath fine). **Fixed** by adding `import kotlinx.coroutines.flow.first` and simplifying the two call sites to `dao.observeSlots(group.id).first()` / `dao.observeAllSlots().first()`. Re-ran `compileDebugAndroidTestKotlin` — **BUILD SUCCESSFUL**, zero errors.

(Separately, `app/src/androidTest/java/com/skyler/pokedexbinder/data/PokedexDatabaseTest.kt` also fails to compile — confirmed via `git log` this file is **pre-existing**, unrelated to this Run, not touched by the Coder. I temporarily moved it aside to isolate and confirm `Migration6to7Test.kt` compiles independently, then restored it. Not fixed — out of scope for Run 1A.)

**Bottom line on migration verification:**
- **Cannot verify** live execution against a real device/emulator — genuinely impossible in this environment, not a shortcut taken.
- **Can and did verify:** the migration test now compiles cleanly and is ready to run the moment a device/emulator is available (`./gradlew.bat connectedDebugAndroidTest` or via Android Studio). Before my fix, it would NOT have run at all — a real gap the Coder's summary didn't flag ("did not run... no device available" implied "ready but unexecuted," which was false; it wasn't compiling).
- **Manual DDL trace** (character-by-character against spec Section 1.2, done independent of the test file): compared `MIGRATION_6_7` in `PokedexDatabase.kt` lines 41-86 against spec verbatim — identical, no drift. Column types match Room's Kotlin→SQLite mapping (`Boolean` → `INTEGER NOT NULL DEFAULT 0`, autoGenerate `Int` PK → `INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT`, nullable `String?` → bare `TEXT`, non-null `String` → `TEXT NOT NULL`). FK clause `ON UPDATE NO ACTION ON DELETE CASCADE` present. Index name `index_connecting_art_slot_groupId` matches Room's naming convention for `@Index("groupId")`. No existing tables (`main_binder`, `secondary_binder`) are touched by MIGRATION_6_7 — confirmed by reading the full migration body, only `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` statements against new table names.
- `fallbackToDestructiveMigration()` is still present in `DatabaseModule.kt` (confirmed) and `MIGRATION_6_7` is registered via `addMigrations(...)` (confirmed) — so if the DDL were wrong, Room would either throw a schema-mismatch at first open (if migration path isn't used) or silently wipe (if fallback triggers). Given the DDL trace above shows no discrepancy from spec, and the JVM unit test suite (which builds a fresh in-memory DB at the *current* v7 schema via annotations, not via migration) also passes without Room throwing `IllegalStateException`, this is the strongest evidence available without a physical/emulated device. **This is not equivalent to a live migration run and should not be reported to Skyler as such.**

**File modified:** `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration6to7Test.kt` (fixed compile error; logic unchanged from Coder's version).

---

## 2. Unit tests

Ran `./gradlew.bat testDebugUnitTest --console=plain`.

Result: **57 tests completed, 8 failed** — same count and same 8 test names as the Coder's summary claimed:
- `AssignCardUseCaseTest > assign to occupied slot moves old card to secondary binder`
- `GeminiCardScannerTest` × 4 (429/500 exceptions, null-fields mapping, success mapping)
- `SmartThresholdUseCaseTest > multiple results with name match but no number is low confidence`
- `BinderRepositoryTest > assignCard upserts entry with card info`
- `CardSearchRepositoryTest > searchByNameAndNumber builds correct query and maps results`

**No new failures.** Confirmed independently (not just trusting the count match).

**Verified the `CardSearchRepositoryTest` stale-mock claim myself**, per instructions — did not take the Coder's word for it:
- Read `CardSearchRepositoryTest.kt`: the failing test stubs `coEvery { api.searchCards("name:\"Venusaur\" number:\"1\"") }`.
- Read `CardSearchRepository.kt`'s `nameQuery()`: produces `name:*Venusaur*` (wildcard form, no quotes) — does not match the stub.
- Ran `git show HEAD:app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt` (i.e., the version **before** this Run's changes) and confirmed `nameQuery()` already produced the same wildcard form pre-Run. `git log` on the test file shows it was last touched in `c02112c feat: add BinderRepository and CardSearchRepository`, predating this Run entirely.
- **Confirmed: this failure is pre-existing and has nothing to do with the Pocket filter.** The Coder's claim is accurate.

---

## 3. Pocket filter correctness

Read `CardSearchRepository.kt` in full.

`safeSearch(query: String)` (lines 88-93):
```kotlin
private suspend fun safeSearch(query: String): List<TcgCard> = try {
    val filtered = if (query.isBlank()) POCKET_EXCLUSION.trim() else "$query $POCKET_EXCLUSION"
    api.searchCards(query = filtered).data.map { it.toDomain() }
} catch (e: Exception) {
    emptyList()
}
```
with `private const val POCKET_EXCLUSION = "-set.series:Pocket"` in the companion object.

- Confirmed every pokemontcg.io call site (`searchByParsedInfo`, `searchByNameAndNumber`, `searchByName`, `searchByNumber`, `searchByNumberAndTotal`, `searchByDexNumber`, `searchPromosByName`) routes exclusively through `safeSearch(...)` → `api.searchCards(...)`. No call site bypasses it.
- Blank-query edge case handled without a leading-space artifact (`POCKET_EXCLUSION.trim()` used instead of `" " + POCKET_EXCLUSION`).
- **TCGdex path confirmed untouched:** `tcgdexSearchByName(name)` calls `tcgdexApi.searchCards(name)` directly — no `POCKET_EXCLUSION` appended, no reference to it anywhere near the TCGdex code path. Matches spec's explicit scoping decision (TCGdex's `@Query("name")` takes a bare name; the DSL filter would corrupt it there).
- Did not add a new dedicated unit test for this — the existing `CardSearchRepositoryTest` mocks are query-string-exact (`coEvery { api.searchCards("name:\"Pikachu\"") }` etc.), and since the two pre-existing tests already assert exact query strings for `searchByNameAndNumber`/`searchByName`, those tests would already have failed loudly (mismatched stub) had the Pocket suffix been missing OR present in a form the stubs didn't account for — which is exactly what's happening with the one pre-existing stale-mock failure. Adding a redundant test would just re-verify the same code path already covered by static read (above) with equal confidence; skipped to avoid low-value test bloat per coding standards ("keep only active code").

**Verdict: correct, matches spec exactly.**

---

## 4. Nav drawer / settings removal

- `grep -rn "showSecondaryBinder\|SHOW_SECONDARY_BINDER\|setShowSecondaryBinder" app/src/main` → **zero matches.** Confirmed clean removal from `SettingsRepository.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, and everywhere else in `app/src/main`.
- Read `AppNavigation.kt` in full: `ModalNavigationDrawer` wraps the `Scaffold`, `ModalDrawerSheet` contains exactly 4 `NavigationDrawerItem`s — Pokédex (`Screen.MainBinder`), Connecting Art (`Screen.ConnectingArt`), Personal Collection (`Screen.PersonalCollection`), Card History (`Screen.SecondaryBinder`) — each closes the drawer then navigates via `navigateTo(...)`.
- All 4 routes are registered in the `NavHost`: `Screen.MainBinder.route`, `Screen.ConnectingArt.route` → `ConnectingArtScreen(onOpenDrawer = ...)`, `Screen.PersonalCollection.route` → `PersonalCollectionScreen(onOpenDrawer = ...)`, `Screen.SecondaryBinder.route` → `SecondaryBinderScreen(...)` (pre-existing, unchanged).
- `MainBinderScreen` has `onOpenDrawer` wired to a hamburger icon in its `TopAppBar` — confirmed present.

**Verdict: matches spec, confirmed independently, not just trusting the Coder's summary.**

---

## 5. assembleDebug

Ran `./gradlew.bat assembleDebug --console=plain` **after** the `Migration6to7Test.kt` fix (androidTest changes don't affect `assembleDebug`, but confirmed anyway as the final gate).

Result: **BUILD SUCCESSFUL**, 41 actionable tasks, all up-to-date/passed. Zero compile errors.

Also confirmed `compileDebugAndroidTestKotlin` now builds successfully in isolation (see Section 1) — this was NOT true before my fix.

---

## Summary

| Check | Result |
|---|---|
| Migration DDL matches spec (static trace) | Pass |
| Migration test exists, is rigorous, and compiles | Pass (fixed a real compile error — see below) |
| Migration test executed live against real DB upgrade | **Not verified — no device/emulator available, confirmed genuinely absent, not assumed** |
| Unit tests — no new failures | Pass (57 run, 8 pre-existing failures, verified independently via git history) |
| Pocket filter — pokemontcg.io only | Pass |
| Pocket filter — TCGdex untouched | Pass |
| showSecondaryBinder fully removed | Pass |
| Nav drawer — 4 entries, working routes | Pass |
| assembleDebug | Pass |

## Files changed by Tester

- `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration6to7Test.kt` — fixed a genuine compile error (`Unresolved reference 'first'`) that would have silently blocked this test from ever running, even once a device/emulator becomes available. Added `import kotlinx.coroutines.flow.first`; replaced the awkward `.let { flow -> kotlinx.coroutines.flow.first(flow)... }` construction with direct `.first()` calls on the two `Flow` return values.

## Honest gap for the Reviewer / Skyler

The migration's live execution against a real pre-migration v6 SQLite file is **not verified** in this environment. The static DDL trace and the now-compiling `MigrationTestHelper`-style test are the strongest available evidence short of an emulator, but they are not a substitute for actually running it. Recommend running `./gradlew.bat connectedDebugAndroidTest` (or the equivalent in Android Studio with a device/emulator attached) before this migration ships to real users, given `fallbackToDestructiveMigration()` means a wrong migration fails silently by wiping data rather than crashing.
