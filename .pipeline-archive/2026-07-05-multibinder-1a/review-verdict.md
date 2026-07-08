# Run 1A — Reviewer Verdict

**Verdict: SHIP** (with one mandatory pre-install action for Skyler, and follow-up items for Run 1B / Tester).

Reviewed independently against `docs/superpowers/specs/2026-05-24-multi-binder-design.md` and `.pipeline/specs.md`. Read every changed file directly; did not rely on the Coder's or Tester's summaries for any Pass below.

---

## Requirement-by-requirement verification

| Run 1A requirement | Status | Evidence |
|---|---|---|
| 4 new entities match spec 1.1 | Pass | `ConnectingArtGroup.kt`, `ConnectingArtSlot.kt` (FK cascade + `Index("groupId")`), `PersonalCollectionCache.kt`, `PersonalCollectionEntry.kt` — all verbatim to spec |
| MIGRATION_6_7 DDL | Pass (static) | `PokedexDatabase.kt:41-86` — see character-by-character trace below |
| DB version bumped 6→7, entities wired, DAO accessors | Pass | `PokedexDatabase.kt:8-24` |
| Migration registered + DAOs provided | Pass | `DatabaseModule.kt:21-39`; `.fallbackToDestructiveMigration()` retained per spec (`:26`) |
| New DAOs mirror `SecondaryBinderDao` | Pass | `ConnectingArtDao.kt`, `PersonalCollectionDao.kt` verbatim to spec 2.1/2.2; `insertGroup(): Long` deviation is intentional and documented |
| New repos, `@Singleton @Inject`, no `@Provides` | Pass | `ConnectingArtRepository.kt`, `PersonalCollectionRepository.kt` (refreshPokemon stub, cardSearchRepository injected for 1B) |
| Placeholder screens with `onOpenDrawer` | Pass | `ConnectingArtScreen.kt`, `PersonalCollectionScreen.kt` — "Coming soon", hamburger wired |
| ModalNavigationDrawer, 4 entries, all routable | Pass | `AppNavigation.kt:88-118` drawer; `:144-237` NavHost — all 4 routes registered, close-then-navigate order correct |
| Bottom bar: Secondary item removed, Scan conditional kept | Pass | `AppNavigation.kt:122-141` — only Pokédex + camera-conditional Scan; `isOnSecondary` still valid (`:133`) |
| MainBinder hamburger without breaking existing app bar | Pass | `MainBinderScreen.kt:36` param + `:73-77` navigationIcon; existing Publish/Settings actions intact (`:78-85`) |
| `showSecondaryBinder` fully removed | Pass | Independent grep over `app/src/main` for `showSecondaryBinder\|SHOW_SECONDARY_BINDER\|setShowSecondaryBinder` → **zero matches** |
| Pocket filter in `safeSearch()` only, TCGdex untouched | Pass | See below |
| No scope creep (no Run 1B screens/VMs) | Pass | No ViewModels created; `refreshPokemon` left as TODO stub; only MainBinder hamburger added (spec-sanctioned), other existing screens untouched |
| Coder's Migration6to7Test.kt compile error fixed | Pass | Verified directly — see below |

---

## MIGRATION_6_7 DDL trace (highest-risk item)

Compared `PokedexDatabase.kt:44-84` character-by-character against spec Section 1.2 and against what Room 2.6.1 generates from the entity annotations:

- `connecting_art_group`: `id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT`, `name/rows/cols/position` all `NOT NULL`. Matches entity (`id` autoGenerate, all other fields non-null; `position` has Kotlin default but is non-null → `INTEGER NOT NULL`). ✓
- `connecting_art_slot`: PK as above; `groupId/slotIndex INTEGER NOT NULL`; `cardId/cardName/cardImageUrl TEXT` (nullable, no NOT NULL — matches `String?`); `owned INTEGER NOT NULL DEFAULT 0` (matches non-null `Boolean` with Kotlin default); FK `REFERENCES connecting_art_group(id) ON UPDATE NO ACTION ON DELETE CASCADE`; separate `CREATE INDEX index_connecting_art_slot_groupId`. ✓
- `personal_collection_cache`: `cardId TEXT NOT NULL PRIMARY KEY`; `pokemonKey/name/imageUrl/setName/releaseDate TEXT NOT NULL`. Matches (all non-null String). ✓
- `personal_collection_entry`: `cardId TEXT NOT NULL PRIMARY KEY`; `owned INTEGER NOT NULL DEFAULT 0`. ✓
- No `ALTER`/`DROP` on `main_binder` or `secondary_binder`; only `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` on new tables → **existing user data is not touched by the migration body itself.** ✓

**The one theoretical mismatch, resolved:** the migration writes `INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT` for autoGenerate Int PKs, whereas Room's own `createAllTables` emits `INTEGER PRIMARY KEY AUTOINCREMENT` (no explicit `NOT NULL`). This does **not** cause a validation failure in Room 2.6.1: runtime migration validation (`RoomOpenHelper` → `TableInfo.read`) compares `PRAGMA table_info` output — type affinity, notNull flag, pk index, dflt_value — not raw DDL text. For an `INTEGER PRIMARY KEY` rowid-alias column, SQLite reports `notnull = 0` in `PRAGMA table_info` regardless of whether `NOT NULL` was written, so the extra keyword is invisible to the comparator. The spec's "either form is schema-compatible" note is correct.

**Corroborating real-world evidence:** `secondary_binder` (`SecondaryBinderEntry.kt`) also has an autoGenerate Int PK and has already passed Room's runtime validation on the shipped v6 build through migrations 4→5 and 5→6 on Skyler's device. Same PK pattern, already accepted in production.

`exportSchema = false` and no `app/schemas/` dir (confirmed) → there is no compile-time schema JSON to diff against, which is why `assembleDebug` succeeding does NOT itself prove the migration; it proves the *entity→generated-schema* side compiles, not that the *hand-written migration DDL* matches at runtime. That gap is real but narrow, and the analysis above closes it to high confidence.

---

## Pocket filter

`CardSearchRepository.kt:88-93` — `safeSearch()` appends `POCKET_EXCLUSION` (`"-set.series:Pocket"`, `:145`) to every pokemontcg.io query; blank-query edge handled via `.trim()` (no leading-space artifact). Verified every pokemontcg.io path (`searchByParsedInfo`, `searchByNameAndNumber`, `searchByName`, `searchByNumber`, `searchByNumberAndTotal`, `searchByDexNumber`, `searchPromosByName`) routes through `safeSearch` → `api.searchCards`; none bypass it. `tcgdexSearchByName` (`:120-124`) calls `tcgdexApi.searchCards(name)` with a bare name, **no** filter appended. Correct per spec's deliberate scoping decision.

---

## Migration6to7Test.kt compile fix (verified, not trusted)

Read `Migration6to7Test.kt` directly: `import kotlinx.coroutines.flow.first` present (`:6`); the two call sites use clean `dao.observeSlots(group.id).first()` (`:187`) and `dao.observeAllSlots().first()` (`:192`) — no fully-qualified `kotlinx.coroutines.flow.first(flow)` remnant. Tester's fix is in place and the file is syntactically sound. Test logic is genuine (raw-SQL DDL replay + PRAGMA column/index assertions + FK cascade check, plus a real-DAO cascade test) — not a stub.

---

## The unverifiable-live-migration gap — explicit ship call

**Static DDL verification IS sufficient to ship here.** Rationale:
1. DDL trace shows no drift from spec or from Room's Kotlin→SQLite mapping.
2. The only text-level difference (extra `NOT NULL` on rowid-alias PKs) is provably invisible to Room 2.6.1's `PRAGMA`-based validator.
3. The identical PK pattern already survives Room validation in production on the same device.
4. Blast radius is Skyler's single dev device, not a user base — and `fallbackToDestructiveMigration()`'s silent-wipe failure mode is mitigated by a one-time manual backup.

This is a calculated accept, not a blind one. The live `connectedDebugAndroidTest` run remains the only thing that would make it certain, and it genuinely cannot run in this environment (no device/emulator — Tester confirmed via `adb devices` / `emulator -list-avds`).

---

## Must-fix before Skyler installs (1 item — operational, not code)

1. **Back up the DB before first launch of the v7 build.** Pull `pokedex_binder.db` off the device (or export via the app's publish/GitHub flow so Pokédex + Card History state is recoverable) *before* installing. If the migration DDL is subtly wrong in a way the static analysis missed, `fallbackToDestructiveMigration()` wipes silently rather than crashing — the backup is the safety net. This is a one-command precaution, not a code change.

## Nice-to-have follow-up (not blocking)

- **Run `./gradlew.bat connectedDebugAndroidTest` once an emulator/device is available** (Tester's recommendation). Converts "high-confidence static" into "verified." Do this before any future multi-device distribution; for Skyler's own install, the backup above covers it.
- **Run 1B**: real ConnectingArt/PersonalCollection UI + ViewModels, `refreshPokemon` fetch/merge, hamburgers on remaining pre-existing screens (SecondaryBinder/SlotDetail/Settings) — all correctly deferred, not missing.
- **Pre-existing, out of scope**: 8 unit-test failures (Gemini scanner, AssignCardUseCase, SmartThreshold, BinderRepository, stale-mock CardSearchRepositoryTest) and the non-compiling `PokedexDatabaseTest.kt` all predate this Run (Tester confirmed via `git log`/`git show`). Not introduced here; leave to their own cleanup.
- **Optional tidy** (someday, not now): a one-line DataStore `prefs.remove("show_secondary_binder")` to drop the orphan key. Harmless as-is; spec explicitly excluded it.

---

## Summary

Run 1A implements the shared-infra scope exactly as specified — no scope creep, no missed items, no deviations beyond the documented intentional ones. The single high-risk item (MIGRATION_6_7 under `fallbackToDestructiveMigration()`) is verified to high confidence by static DDL trace + Room-internals reasoning + production corroboration of the same PK pattern. **Ship, with a manual DB backup before Skyler's first install of the v7 build.**
