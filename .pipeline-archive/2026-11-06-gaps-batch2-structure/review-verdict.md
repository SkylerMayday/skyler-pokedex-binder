# Review verdict: Batch 2 (C + D), branch gaps-batch2-structure

Verdict: SHIP (score 94/100, no P0/P1)

Scope: `git diff master...HEAD`, 11 files, commits 52b1205 (C) and 0fc8a67 (D). Read specs.md, changes.md, test-results.md, full diff, deleted use case, DI modules, and grepped A/B/E claims in main source.

## Intent audit (Phase 2)
| Spec item | Status | Evidence |
|---|---|---|
| C: no default on reader | DONE | `private val versionReader: SqliteVersionReader,` (DatabaseBackupManager.kt) |
| C: buildDatabase explicit + comment | DONE | `DatabaseBackupManager(FrameworkSqliteVersionReader()).backupIfDestructiveMigrationImminent(` plus "Built directly (not injected)" comment |
| C: androidTest 2 sites | DONE (compile UNVERIFIED) | both `DatabaseBackupManager(FrameworkSqliteVersionReader())`; PKIX blocks compile. Same-package class, so no import needed (spec claim, matches diff: no import added) |
| C: @Provides unchanged | DONE | DatabaseModule diff touches only lines 36-41 |
| D: assignWithHistory | DONE | `suspend fun assignWithHistory(pokemonId: String, card: TcgCard): Boolean` |
| D: use case + test deleted, VMs migrated | DONE | 6 call sites retargeted (4 QuickScan, 2 Scanner) |
| D: 5 new tests, 7 sites updated, count 581 | DONE | test-results.md: 581/0 fail; BinderRepositoryTest 12 |
| D: assignCard signature unchanged | DONE | not in diff |
| A, B, E not a gap | AGREE (below) | |
| Docs (Task 4) | PENDING, orchestrator-owned; `gaps.md:36` still names AssignCardUseCase | not a code defect |

Scope creep: none. Files are exactly the spec's list; no entity/schema/gradle/nav change.

## Behaviour equivalence (assignWithHistory vs deleted use case)
Line-by-line diff of master body vs new body: identical except `binderRepository.getSlotByPokemonId` -> `getSlotByPokemonId` and `binderRepository.assignCard` -> `assignCard`. Same null->false, same lock->false before any write, same insertAtEnd-before-assign order, same `pokemonName = slot.name`, same `!!` unwraps, same return value. Equivalent. One nuance (not a defect): `getSlotByPokemonId` and `assignCard` each hit `mainBinderDao.getByPokemonId`, as before (the old use case also called both on the repo), so DB read count is unchanged.

## Hilt wiring
- `BinderRepository @Inject constructor(MainBinderDao, SecondaryBinderDao)`; `SecondaryBinderDao` provided at DatabaseModule.kt:79 (`fun provideSecondaryBinderDao(db: PokedexDatabase): SecondaryBinderDao = db.secondaryBinderDao()`) and FakeDatabaseModule.kt:55. No cycle: SecondaryBinderDao depends only on the DB. Class stays `@Singleton`, DAO unscoped-per-DB, fine.
- `ScannerViewModel` ctor dropped `AssignCardUseCase`; remaining params all still provided. `assembleDebug` passed (Hilt/KSP ran), which is the strong evidence here.
- Item C: Hilt path `provideSqliteVersionReader()` -> `provideDatabaseBackupManager(versionReader)` untouched. The second construction in `buildDatabase` uses the same `FrameworkSqliteVersionReader`, so no behavioural divergence today; the comment documents why it is not injected.

## Challenge of A / B / E verdicts (read against code)
- A (5 blank-image DAOs): AGREE. Grep confirms five separate `suspend fun fillBlankImage(cardId: String, url: String): Int` in MainBinderDao:353, UnownBinderDao:43, ConnectingArtDao:82, SecondaryBinderDao:42, PersonalCollectionDao:63, called at `SyncCardImagesUseCase.kt:117-121`. Room needs literal SQL per `@Query`; an interface saves ~0 lines. Honest not-a-gap.
- B (ConnectingArtRepository): AGREE. Non-VM consumers confirmed: `BackupExporter.kt:90` `connectingArtRepository.getAllGroups()`, `PublishRepository.kt:120-121`, `RestoreRepository.kt:200,227`. Class cannot be deleted; VM-to-DAO would break the per-section Repository convention.
- E (duplicate screens): AGREE on the spec's reading (not independently re-read line by line; spec describes materially different bodies). Confidence 7/10. I did not open the four screen files; treat as the spec author's finding.

## Findings

### P3-1 Mutation 1 (insertAtEnd removed) not independently re-run (confidence 8)
Quote (test-results.md): "The third mutation (`insertAtEnd` removed) was not re-run by me. The Coder's `mut1-red.log` claims it." Static check: the occupied test asserts `coVerify { secondaryDao.insertAtEnd(SecondaryBinderEntry(pokemonId = "charizard", pokemonName = "Charizard", cardId = "base1-4", cardImageUrl = "https://img.url/base1-4")) }` with a non-relaxed-fixable exact entry, so deletion must turn it red. Low risk. Cost: -1 test-evidence point.

### P3-2 Empty-slot test has a vacuous assertion (confidence 7)
Quote: `coVerify(exactly = 0) { secondaryDao.insert(any()) }` in `assignWithHistory to empty slot does not touch card history`. `assignWithHistory` never calls `insert`, so this line cannot fail. The adjacent `insertAtEnd(any())` exactly-0 line does the real work (and was carried over faithfully from the old test's `insert` check, which was already vacuous). Harmless; could drop the `insert` line. Not blocking.

### P3-3 Locked test cannot distinguish lock guard in `assignCard` from guard in `assignWithHistory` on the upsert side (confidence 8)
Quote: `entry("charizard", "Charizard", cardId = "base1-4", locked = true)` with `coVerify(exactly = 0) { secondaryDao.insertAtEnd(any()) }` and `coVerify(exactly = 0) { mainDao.upsert(any()) }`. Because `assignCard` has its own lock check, removing the `assignWithHistory` guard still leaves upsert uncalled; the test catches the removal only via the `insertAtEnd` assertion (occupied + locked fixture), which is why mutation 2 went red. Fixture is correctly chosen (occupied AND locked). No change needed; noting that the `!!` fixture choice is load-bearing.

### P3-4 Non-transactional assign remains (pre-existing, confidence 9)
Quote: `secondaryBinderDao.insertAtEnd(` followed by `return assignCard(`. A crash between them leaves a Card History duplicate. Spec marks out of scope and requires a gaps.md note; verify the docs task records it.

### Info: androidTest unverified
`DatabaseBackupManagerInstrumentedTest` (2 edits) and `DatabaseModuleWiringTest` not compiled/run (PKIX). Low risk (mechanical, same-package), but Skyler must run `compileDebugAndroidTestKotlin` before relying on it.

Nothing else found in Phase 4 (null handling: `!!` unchanged from original and guarded by `isOccupied`; no async/resource issues; no enum additions).

## What looks good
- Verbatim body move; per-class count table (QuickScan 29->29, Scanner 12->12, BinderRepository 7->12, total 581) matches the spec's equivalence proof exactly.
- Mutations 2 and 3 independently re-proven red by the Tester with named failing tests, restored, tree clean.
- Relaxed `binderRepository` mock in QuickScanViewModelTest returns false for `assignWithHistory`, as the old relaxed use-case mock did; ScannerViewModelTest's strict `mockk()` unchanged in semantics.
- Fresh-XML suite run with `--rerun-tasks`, per project CLAUDE.md lesson.

## Score
| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | All C/D requirements traced; A/B/E verdicts hold. -1: docs Task 4 (gaps.md still names the use case) pending, orchestrator-owned. |
| Correctness & bug-freedom | 25/25 | Line-for-line equivalent; no bugs found; Hilt graph resolves, assembleDebug green. |
| Security & reliability | 19/20 | No new surface. -1: pre-existing non-transactional assign carried over (P3-4, accepted per spec). |
| Maintainability & simplification | 15/15 | Removes a class, a test file and two VM params; reuses existing methods; comment on the C construction is justified. |
| Test evidence quality | 11/15 | Verbatim logs, exact count equality, 2 of 3 mutations independently re-run. -4: mutation 1 only Coder-claimed (P3-1), one vacuous assertion (P3-2), androidTest edits uncompiled. |
| Total | 94/100 | First pass (no prior iteration). |

Ship condition met: score >= 85, no unresolved P0/P1.
Merge-time reminders: run Task 4 docs (incl. non-transactional note), ask Skyler to run `compileDebugAndroidTestKotlin`, per global rules commit wrapcon files and push if a remote exists (none today).
