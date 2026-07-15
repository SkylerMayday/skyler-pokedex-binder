# Tester Stage — Independent Verification Results

Source spec: `docs/superpowers/specs/2026-07-12-unown-as-pc-sections.md`
Re-verified independently from Coder's `changes.md`. All checks below were performed by re-reading
production/test source directly and re-running the build/test toolchain fresh — none of the
Coder's reported numbers were trusted without reproduction.

---

## 1. Grep verification gate — PASS (re-run independently)

```
grep -rniE "unown" app/src
```

Hits (17 lines across 6 files), all expected/benign:

- `publish/PublishRepository.kt:353` — comment "Publish ALL cached cards (owned + **unowned**)" —
  pre-existing English word, not the feature.
- `publish/SnapshotSections.kt:60` — new mirror-list entry `"unown_$letter" to "Unown $letter"`.
- `ui/common/DimmableCardImage.kt:16,18` — pre-existing doc comments using "unowned" as an
  adjective (owned/unowned card state), unrelated to the deleted feature.
- `ui/connectingart/ConnectingArtScreen.kt:112,270,282,283` — pre-existing "Mark as Unowned"
  action in Connecting Art, unrelated to the deleted feature.
- `ui/personalcollection/PersonalCollectionViewModel.kt:18,19,27,29-31` — new `UNOWN_LETTERS`
  constant and the 28 new section key/title/query-name entries.
- `app/src/main/res/raw/pokemon_slots.json:1403-1404` — pre-existing Pokédex species #201 "Unown"
  slot data, unrelated.
- `app/src/test/java/.../publish/RestoreRepositoryTest.kt:497,498,517` — pre-existing "unowned"
  test-fixture naming (cardId `"unownedCard"`), unrelated to the deleted feature.
- `app/src/test/java/.../ui/personalcollection/PersonalCollectionViewModelTest.kt` — pre-existing
  "unowned" wording in two existing test names plus the 4 new Unown-section tests.

**Zero hits** in `di/`, `ui/navigation/`, `ui/settings/`, `data/local/`, or `repository/`. No
dangling `ui.unown.*`, `UnownBinderRepository`, `UnownBinderEntry`, or `UnownBinderDao` references
anywhere. Confirms Coder's claim; the Coder's report additionally missed listing the
`ConnectingArtScreen.kt`/`DimmableCardImage.kt`/`RestoreRepositoryTest.kt` "unowned"-word hits as
explicitly benign, but they are — verified by reading each in context.

## 2. Room database revert — PASS

Read `app/src/main/java/com/skyler/pokedexbinder/data/local/PokedexDatabase.kt` directly:
- `entities = [MainBinderEntry, SecondaryBinderEntry, ConnectingArtGroup, ConnectingArtSlot, PersonalCollectionCache, PersonalCollectionEntry]` — 6 entities, `UnownBinderEntry` absent.
- `version = 7` (confirmed reverted from 8).
- No `unownBinderDao()` abstract accessor.
- `MIGRATION_7_8` object entirely absent. `MIGRATION_4_5`, `MIGRATION_5_6`, `MIGRATION_6_7`
  present and byte-for-byte unchanged (CA/PC tables created in `MIGRATION_6_7`, untouched).

Read `app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt` directly:
- `.addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)` — correct chain, `MIGRATION_7_8`
  removed.
- `.fallbackToDestructiveMigration()` followed by `.fallbackToDestructiveMigrationOnDowngrade()` —
  both present per spec §2 insurance rationale.
- No `provideUnownBinderDao`. Remaining 4 DAO providers (main/secondary/connectingArt/
  personalCollection) all intact and unchanged.

## 3. PersonalCollectionViewModel — PASS

Read `PersonalCollectionViewModel.kt` directly. `PERSONAL_COLLECTION_SECTIONS` = 5 original
entries + `UNOWN_LETTERS.map { ... }` where `UNOWN_LETTERS = ('A'..'Z').map{it.toString()} +
listOf("!","?")` → 28 entries, keys `unown_A`..`unown_Z`, `unown_!`, `unown_?`, titles
`"Unown <letter>"`, `queryNames = listOf("Unown <letter>")`. No key collisions with the 5 original
keys (`charizard`/`celebi`/`leafeon`/`tangela`/`minccino_cinccino` — disjoint namespace via the
`unown_` prefix) and no collisions within the 28 (each letter/symbol distinct). `refreshAll`,
`toggleOwned`, `uiState` all iterate `PERSONAL_COLLECTION_SECTIONS` generically — no
Unown-specific special-casing anywhere in the file.

## 4. PublishRepository.kt / RestoreRepository.kt — PASS

Read both files in full.

**PublishRepository.kt**: constructor takes 8 params (gitHubApi, discordApi, moshi,
publishSettingsRepository, binderRepository, secondaryBinderDao, connectingArtRepository,
personalCollectionRepository) — no `unownBinderRepository`. No `BINDER_ID_UNOWN`/
`BINDER_NAME_UNOWN`/`SECTION_UNOWN` constants. `buildSnapshot(...)` signature has no
`unownEntries` param. No `if (config.publishUnown)` block anywhere. The Pokédex, card-history,
Connecting Art (content-gated on ≥1 assigned slot per group), and Personal Collection
(content-gated on ≥1 cache row per section, `PERSONAL_COLLECTION_SECTION_ORDER`-driven, owned
flag sourced from `personal_collection_entry`) blocks are present, structurally identical to what
the CA/PC publish work built earlier — untouched by this removal.

**RestoreRepository.kt**: constructor takes 5 params (publishRepository,
publishSettingsRepository, binderRepository, connectingArtRepository,
personalCollectionRepository) — no `unownBinderRepository`. No `BINDER_ID_UNOWN` constant. No
Unown overlay block. Pokédex overlay (assignment-fields-only, preserves structural fields),
Connecting Art overlay (matched by `ca-<groupId>-<slotIndex>` key, restores cardId/cardName/
cardImageUrl/owned), and Personal Collection overlay (matched by cardId, restores/clears
owned-only via `setOwned`/`removeOwned`, never touches the cache table) are all present and
unchanged in logic. Shared `restored`/`cleared`/`skipped` counters intact and correctly threaded
through all three overlays.

**Conclusion**: Unown removal was surgical — no collateral damage to the CA/PC publish/restore
logic built earlier today.

## 5. PersonalCollectionScreen.kt — PASS

Read the file in full. Composable signature is `(onOpenDrawer, viewModel)` — no `onOpenUnown`
param. The jump-chip `Row` and the `LazyVerticalGrid` both do a single
`PERSONAL_COLLECTION_SECTIONS.forEach { section -> ... }` loop with no per-section branching —
all 33 sections (5 original + 28 Unown) render identically through the same collapsible-header +
grid + action-sheet code path. No standalone "6th chip" or bottom Unown row remains. No leftover
`ArrowForward` import or usage (was only used by the two removed blocks — confirmed absent from
the file's import list and body).

## 6. PersonalCollectionViewModelTest.kt new cases — PASS, substantive

Read the 4 new tests (`section list contains the 5 originals plus 28 unown letters`,
`each unown section auto-searches with its Unown letter query name`,
`refreshAll refreshes every one of the 33 sections`,
`uiState surfaces cards for an unown section keyed by its section key`). These assert real,
falsifiable behavior: exact section count (33), exact key set (incl. `!`/`?` symbol handling),
key uniqueness (collision guard), per-letter query-name mapping, an exact `coVerify(exactly = 33)`
call-count assertion on `refreshPokemon` (would fail if the loop double-counted or skipped a
section), and that cache rows keyed by `unown_A` surface correctly through the `groupBy` in
`uiState`. Not tautological — each would fail under a plausible regression (e.g. a typo'd letter
key, an off-by-one in `UNOWN_LETTERS`, or a `!`/`?` string-equality bug in the map lookup).

## 7. Surviving PublishRepositoryTest.kt / RestoreRepositoryTest.kt tests — PASS, no dead-code coverage

Read both files in full (30 + 17 = 47 tests).

- `PublishRepositoryTest`: `setUp()` constructs `PublishRepository` with the current 8-arg
  constructor — matches production. Spot-checked the CA/PC-specific tests (`buildSnapshot
  includes connectingArt binder when a group has an assigned slot`, `...omits connectingArt binder
  when no slots assigned`, `...skips empty connectingArt group but keeps a non-empty one`,
  `...connectingArt slotId encodes group id and slot index`, `...includes personalCollection
  binder with all cached cards and owned flags`, `...omits personalCollection binder when cache
  empty`, `...skips empty personalCollection section`, `...personalCollection section name uses
  display title`, `computeDiff owned flip on same card is REPLACED`, `...connectingArt and
  personalCollection slots do not inflate pokedexComplete`) — every one calls the real
  `buildSnapshot`/`computeDiff` and asserts on real `SnapshotBinder`/`SnapshotSection`/
  `SnapshotSlot` output; none reference a removed API or dead branch.
- `RestoreRepositoryTest`: `setUp()` constructs `RestoreRepository` with the current 5-arg
  constructor. Spot-checked the CA/PC overlay tests (`connecting art overlay restores and clears
  slots by group and position`, `...counts snapshot slots with no local match as skipped`, `old
  snapshot without connecting art binder leaves connecting art untouched`, `personal collection
  overlay restores owned by cardId`, `...restores owned for a card with no local entry yet`,
  `...never writes the cache`, `old snapshot without personal collection binder leaves owned
  untouched`, `owned flag round-trips through publish then restore` — this last one constructs a
  second, real `PublishRepository` inline with the current 8-arg constructor and no
  `unownBinderRepository`, confirming the round-trip test itself compiles/runs against the live
  arity, not a stale one). All exercise genuine CA/PC code paths still present in production.

No test in either file references a removed constant, removed constructor param, or a deleted
code branch.

## 8. Fresh full unit test suite run — PASS

```
export JAVA_HOME="D:\jdk17\jdk-17.0.14+7"
export TEMP="C:\Windows\Temp"
export TMP="C:\Windows\Temp"
./gradlew.bat testDebugUnitTest --console=plain --rerun-tasks
```

```
BUILD SUCCESSFUL in 1m 15s
31 actionable tasks: 31 executed
```

Per-suite counts, read directly from `app/build/test-results/testDebugUnitTest/*.xml` (17 suite
files, this run):

| Suite | tests | skipped | failures | errors |
|---|---|---|---|---|
| PokemonSlotTest | 3 | 0 | 0 | 0 |
| AssignCardUseCaseTest | 3 | 0 | 0 | 0 |
| BackfillCardNamesUseCaseTest | 6 | 0 | 0 | 0 |
| GeminiCardScannerTest | 4 | 0 | 0 | 0 |
| PerceptualHasherTest | 6 | 0 | 0 | 0 |
| SmartThresholdUseCaseTest | 4 | 0 | 0 | 0 |
| DiscordEmbedBuilderTest | 6 | 0 | 0 | 0 |
| **PublishRepositoryTest** | **30** | 0 | 0 | 0 |
| **RestoreRepositoryTest** | **17** | 0 | 0 | 0 |
| BinderRepositoryTest | 3 | 0 | 0 | 0 |
| CardSearchRepositoryTest | 11 | 0 | 0 | 0 |
| MainBinderViewModelTest | 3 | 0 | 0 | 0 |
| ManualSearchViewModelTest | 4 | 0 | 0 | 0 |
| SlotDetailViewModelTest | 1 | 0 | 0 | 0 |
| ConnectingArtViewModelTest | 9 | 0 | 0 | 0 |
| **PersonalCollectionViewModelTest** | **12** | 0 | 0 | 0 |
| PublishViewModelTest | 3 | 0 | 0 | 0 |
| **TOTAL** | **125** | **0** | **0** | **0** |

Matches the Coder's reported counts exactly (30/17/12 for the three touched suites) — independently
reproduced, not trusted from the report. Zero failures, zero errors across all 17 suites (125
tests, up from the Coder's originally-cited 60 — that 60 referred only to the 3 directly-touched
files; the full suite is 125 and is fully green).

Only pre-existing warnings surfaced (`compileSdk = 36` AGP support warning, `ExperimentalCoroutinesApi`
opt-in warnings, Moshi kapt deprecation, `ImageProxy`/`LocalLifecycleOwner` deprecations) — none
introduced by this change, none related to Unown.

---

## No gaps found

No dead code, no collateral regression in CA/PC publish logic, no tautological tests, no build
issue. The Coder's implementation and verification claims all reproduced independently. Nothing
required fixing at the Tester stage.

---

## Explicit P0 pass/fail (per spec §Requirements)

| # | P0 Requirement | Verdict |
|---|---|---|
| 1 | All 28 Unown letters appear as PC sections, auto-searching + owned/unowned toggle exactly like the existing 5 | **PASS** — verified via §3 (ViewModel section list, generic `refreshAll`) and §5 (Screen renders all 33 sections through one uniform loop, no special-casing). Programmatically confirmed by `PersonalCollectionViewModelTest` §6. On-device visual confirmation is still Skyler's manual step per spec §7 item 5 (not testable in this stage). |
| 2 | Standalone Unown feature fully removed: no dead code, no orphaned nav routes, no unused Room entities/DAOs, no leftover publish/restore branches referencing the old binder id | **PASS** — verified via §1 (grep gate, zero hits outside expected), §2 (Room entities/DAO/migration gone), §4 (Publish/Restore repositories have no Unown branch or constant) |
| 3 | Room migration path clean — fresh install and upgrade-from-v7 both work without crashing | **PASS** (by code inspection) — verified via §2: entity set at v7 matches original v7 schema exactly (only `UnownBinderEntry` removed, nothing else changed), so a v7 device migrates via zero migrations (identity hash match) and a fresh install builds v7 cleanly. `fallbackToDestructiveMigrationOnDowngrade()` added as insurance for the v8-edge-case. Not device-tested (no emulator/device run in this stage) — code-level guarantee only, consistent with the Planner's justification in specs.md §2. |
| 4 | Publish/restore continues working correctly for the original 5 PC sections (regression check) | **PASS** — verified via §4 (production code unchanged in shape) and §7 (all CA/PC-specific tests in both suites still exercise real, current code paths and pass) |
| 5 | Full unit suite green | **PASS** — verified via §8: fresh `--rerun-tasks` run, 125/125 tests pass, 0 failures, 0 errors, independently reproduced (not trusted from Coder report) |

**Overall: PASS.** No production-code fix required. This stage found no gap requiring Stage 4
(Debugger). Item 3 (on-device migration test) and the Success Criteria's on-device visual/publish
verification remain Skyler's manual step per spec — outside what a unit-test-stage Tester can
exercise.
