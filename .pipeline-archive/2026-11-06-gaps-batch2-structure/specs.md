# Spec: Batch 2 - Structure cleanup (behaviour-preserving)

Worktree `.claude/worktrees/gaps-batch2`, branch `gaps-batch2-structure`, base master 5c765e0. Paths are under `app/src/`; `com/skyler/pokedexbinder/` is abbreviated `pkg/`. Source plan: `C:\Users\SkylerMayday\.claude\plans\spicy-wiggling-fern.md` (Batch 2); gaps.md session-24 "Deferred cuts".

## TL;DR

Every item was read against the code (all callers grepped). Verdicts:

| Item | Verdict | Work |
|---|---|---|
| A. 5 blank-image DAO pairs | NOT A GAP | none |
| B. ConnectingArtRepository pass-throughs | NOT A GAP | none |
| C. SqliteVersionReader default | FIX | S, 3 files |
| D. AssignCardUseCase -> BinderRepository | FIX, last, revert trigger defined | M, ~8 files |
| E. Duplicate screens (both pairs) | NOT A GAP | none |

Code changes: C then D only. A/B/E are reported with evidence and recorded in gaps.md (Task 4).

## Problem

gaps.md carries these five as "deferred cuts" (the screen pair marked "unread"). The owner wants each concluded. Two are real (a duplicate binding; a use case that is one method over two collaborators both ViewModels already hold). Three cost more churn than they save once read, and one (B) would worsen layering.

## Proposal

### Item A - NOT A GAP

- The ten queries differ in table AND columns, so no SQL is shareable: `main_binder`/`unown_binder` use `assignedCardId`+`assignedCardImageUrl`; `secondary_binder` and `connecting_art_slot` use `cardId`+`cardImageUrl`; `personal_collection_cache` uses `cardId`+`imageUrl`. Room needs literal SQL per `@Query`, so SQL stays x5 regardless.
- A shared `BlankImageDao` interface would only let `SyncCardImagesUseCase.kt:67-69,117-121` loop over a list (about 0 net lines), adds a type and five re-annotated overrides, and relies on Room handling overridden `@Query` suspend methods, which only a Gradle build can reveal (sandbox Gradle is unreliable).
- Pinned today by `SyncCardImagesUseCaseTest` (all five DAOs mocked, fill verified on each, lines 44-55) and androidTest `PokedexDatabaseTest.fillBlankImageOnlyTouchesBlankRows` (main table only).
- Action: none.

### Item B - NOT A GAP

- `repository/ConnectingArtRepository.kt` (40 lines) has three non-VM consumers of `getAllGroups/getAllSlots/updateSlots`: `data/local/backup/BackupExporter.kt:90-91`, `publish/PublishRepository.kt:120-121`, `publish/RestoreRepository.kt:200,227`. It cannot be deleted.
- "VM calls a real method directly" means injecting `ConnectingArtDao` into `ui/connectingart/ConnectingArtViewModel.kt`. Every other section's ViewModel goes through a Repository; this would break that for about 12 trivial lines.
- Not all are pure pass-throughs: `reorderGroups` (loop), `updateLanguage` (`language.name`), and four renames (`createGroup`->`createGroupWithSlots`, `deleteGroup`->`deleteGroupById`, `removeCard`->`clearSlot`, `getAllGroups`->`getGroups`).
- `ConnectingArtViewModelTest` mocks the repo (14+ sites); moving it to the DAO rewrites them for zero behaviour or size win.
- Action: none.

### Item C - FIX: single `SqliteVersionReader` binding

Today: `data/local/backup/DatabaseBackupManager.kt:12` has `versionReader: SqliteVersionReader = FrameworkSqliteVersionReader()`; `di/DatabaseModule.kt:66` `@Provides provideSqliteVersionReader()` and `:70` `provideDatabaseBackupManager(versionReader)`. `BackupImporter.kt:99-100` is the only injection site of `DatabaseBackupManager`. `androidTest/di/FakeDatabaseModule.kt:74-79` already passes the reader explicitly.

Edits:
1. `DatabaseBackupManager.kt:12` -> `private val versionReader: SqliteVersionReader,` (no default; `maxBackups` default stays).
2. `di/DatabaseModule.kt:39` -> `DatabaseBackupManager(FrameworkSqliteVersionReader()).backupIfDestructiveMigrationImminent(`. Not a second DI binding: `buildDatabase` runs inside `provideDatabase` and is called directly by `androidTest/.../di/DatabaseModuleWiringTest`, so it has no injector. Add a one-line comment saying so. The `@Provides` stays the only Hilt binding.
3. `androidTest/.../data/local/backup/DatabaseBackupManagerInstrumentedTest.kt:72,108` -> `DatabaseBackupManager(FrameworkSqliteVersionReader())` (same package, no import needed).
4. No other no-arg construction exists (`DatabaseBackupManagerTest.kt:68` already passes `versionReader =`).

Proof: `compileDebugKotlin` + `compileDebugUnitTestKotlin` fail on a missed call site; `DatabaseBackupManagerTest` and `BackupImporterTest` stay green; `grep -rn "DatabaseBackupManager()" app/src` returns 0. androidTest cannot be compiled here (PKIX), so list its two edits as "compile unverified, Skyler runs `compileDebugAndroidTestKotlin`".

### Item D - FIX (last): merge `domain/AssignCardUseCase.kt` into `BinderRepository`

Current `AssignCardUseCase.assign(pokemonId: String, card: TcgCard): Boolean`: `getSlotByPokemonId` (null -> false); locked -> false; if occupied -> `secondaryBinderDao.insertAtEnd(SecondaryBinderEntry(pokemonId = slot.id, pokemonName = slot.name, cardId = slot.assignedCardId!!, cardImageUrl = slot.assignedCardImageUrl!!))`; then `return binderRepository.assignCard(pokemonId, card.id, card.imageUrl, card.name, card.setName)`.

Callers (both already hold `binderRepository` and `secondaryBinderDao`): `ui/quickscan/QuickScanViewModel.kt` (ctor line 42; calls 141,151,162,196) and `ui/scanner/ScannerViewModel.kt` (ctor line 47; calls 146,155). Nothing else references the class.

Design (new name, because the existing 5-arg `BinderRepository.assignCard` keeps its signature; other tests pin it):
- `repository/BinderRepository.kt`: constructor `BinderRepository @Inject constructor(private val mainBinderDao: MainBinderDao, private val secondaryBinderDao: SecondaryBinderDao)`; add `suspend fun assignWithHistory(pokemonId: String, card: TcgCard): Boolean` with the body above verbatim, calling `this.getSlotByPokemonId` / `this.assignCard`. KDoc: returns false if slot missing or locked; moves a displaced card to Card History.
- Delete `domain/AssignCardUseCase.kt` and `test/.../domain/AssignCardUseCaseTest.kt`.
- `QuickScanViewModel`, `ScannerViewModel`: drop the `assignCardUseCase` ctor param and import; call `binderRepository.assignWithHistory(...)`.
- Hilt: no module change (constructor injection; `SecondaryBinderDao` already `@Provides`; no cycle).

Tests (the three files):
1. `AssignCardUseCaseTest` (3 tests) -> move into `test/.../repository/BinderRepositoryTest.kt` with the same arrange/act/assert flows: empty slot -> no `secondaryDao.insert*`, assignCard effect verified; occupied -> `insertAtEnd(SecondaryBinderEntry("charizard","Charizard","base1-4","https://img.url/base1-4"))` and new card assigned; missing slot -> neither. `assignWithHistory` calls `this.assignCard`, so use a real `BinderRepository(mainDao, secondaryDao)` with relaxed mock DAOs, stub `mainDao.getByPokemonId(...)` to return a `MainBinderEntry`, and verify `mainDao.upsert(...)` instead of the old `binderRepo.assignCard(...)` verify. Add two tests: locked slot -> false, no `insertAtEnd`, no upsert; proceeding assignment returns true.
2. Existing 7 `BinderRepository(mainDao)` sites in `BinderRepositoryTest` (lines 29,42,50,75,90,114,124) -> `BinderRepository(mainDao, secondaryDao)` with a relaxed `secondaryDao` field.
3. `test/.../ui/QuickScanViewModelTest.kt`: delete the `assignCardUseCase` mock (line 45) and ctor arg (line 67); the verifies at lines 264,306,332,348,370,440 become `binderRepository.assignWithHistory(...)` (same args). `binderRepository` there is already a relaxed mock, so a relaxed Boolean return is false exactly as the old relaxed use-case mock was.
4. `test/.../ui/scanner/ScannerViewModelTest.kt:177`: delete `assignCardUseCase = mockk(),`.

Equivalence proof: (a) Task 1 records per-class counts for `AssignCardUseCaseTest`, `QuickScanViewModelTest`, `ScannerViewModelTest`, `BinderRepositoryTest`; (b) after D the total must be exactly 579 - 3 + 5 = 581, with QuickScan/Scanner counts unchanged and the 7 old BinderRepository tests unchanged; (c) mutation checks on `assignWithHistory`, each shown red then restored: delete the `insertAtEnd` call -> occupied test fails; delete the `isLocked` guard -> locked test fails; swap `pokemonName = slot.name` for `slot.id` -> occupied test fails.

ABANDON trigger (revert the D commit, report "deferred" with the reason) if any of: a VM test cannot stay equivalent without weakening an assertion; the post-change total is not exactly 581; a mutation does not turn a test red; compile/Hilt fixes need files beyond those listed. D is its own commit so revert is one command, and starts only after C is committed and green.

### Item E - NOT A GAP

Both pairs read in full.
- `ui/secondarybinder/SecondaryBinderScreen.kt` (180 lines) vs `ui/unown/UnownBinderScreen.kt` (186): Secondary = reorderable 3-col grid, FAB + add sheet, per-tile delete/edit overlay, no lock. Unown = fixed 4-col letter grid, tap-to-assign navigation, lock badge, `blockedByLock` dialog, action sheet. Only the Scaffold/TopAppBar/menu icon and an `EditCardDetailsDialog` call (different args) coincide, which is a few lines of boilerplate, not identical bodies.
- `ui/connectingart/ConnectingArtScreen.kt` (317) vs `ui/personalcollection/PersonalCollectionScreen.kt` (264): ConnectingArt = reorderable LazyColumn of group sections, create-group sheet, delete confirm, slot tiles. PersonalCollection = pull-to-refresh, collapsible sectioned grid, jump-to-section dropdown, scroll effects. Shared: top bar, an Edit Details dialog, a bottom sheet with Back; their sheets differ in buttons and state.
- A shared composable would need about 10 slot/lambda parameters, more code than it removes. Action: none.

## User stories

- As the maintainer, I want the backup manager's reader dependency explicit so no second construction path can silently differ from the DI one.
- As the maintainer, I want assign-with-history logic in the repository so two ViewModels each lose a dependency.
- As the owner, I want each deferred item concluded with evidence so gaps.md has no "unread" entries.

## Acceptance criteria

Global
- [ ] `./run_build.bat testDebugUnitTest --rerun-tasks` green after C (579/579) and after D (581/581), with `app/build/test-results` deleted first so XMLs are fresh.
- [ ] `git diff --stat` shows no change to entities, `PokedexDatabase.kt`, `schemas/`, `AppNavigation.kt`, `gradle/`; no new dependency.
- [ ] No `connectedDebugAndroidTest` on the phone. Emulator only with `ANDROID_SERIAL=emulator-5554`; if none is available, report "not run".

Item C
- [ ] `grep -rn "DatabaseBackupManager()" app/src` -> 0 hits; `DatabaseBackupManager.kt:12` has no default.
- [ ] `provideSqliteVersionReader` and `provideDatabaseBackupManager` unchanged.
- [ ] `DatabaseBackupManagerTest`, `BackupImporterTest` pass unchanged.

Item D
- [ ] `grep -rn AssignCardUseCase app` -> 0 hits; both files deleted.
- [ ] 5 new `BinderRepositoryTest` tests; 3 mutation checks shown red then restored.
- [ ] Suite exactly 581; `assignCard(...)` signature unchanged.
- [ ] Or reverted and reported "deferred" per the abandon trigger.

Items A, B, E
- [ ] Final report says "not a gap" with the evidence above; no source change.

## Out of scope

Room entity/DAO/schema changes or migration; AppNavigation routes; the seven-piece nav wiring; deleting `ConnectingArtRepository`; a `BlankImageDao` interface; shared screen composables; changing `BinderRepository.assignCard` semantics; `MAX_AUTO_IDS` (Batch 1). Making assign transactional is also out (the use case was not): record in gaps.md that a crash between `insertAtEnd` and the upsert leaves a duplicate in Card History.

## Dependencies

- JDK 17 `D:\jdk17\jdk-17.0.14+7` via `run_build.bat`. On "Unable to establish loopback connection", ask Skyler to run the command in his shell; never claim green on an unrun build.
- Order: Task 1 -> 2 (C) -> 3 (D) -> 4 (docs).

## Tasks (dependency-ordered)

1. S - Baseline: delete `app/build/test-results`; `./run_build.bat testDebugUnitTest --rerun-tasks`; record 579 and the four per-class counts. If not 579, stop and report.
2. S - Item C edits (3 files); build + full suite; commit "refactor: drop SqliteVersionReader constructor default".
3. M - Item D: add `assignWithHistory` and the ctor change; write the new/moved `BinderRepositoryTest` tests; migrate the two VMs and their tests; delete the use case and its test; full suite (expect 581); run the three mutations; commit "refactor: merge AssignCardUseCase into BinderRepository". Revert per trigger.
4. S - Docs: `gaps.md` remove fixed entries (C, and D if kept) from "Deferred cuts"; mark A/B/E "closed: not a gap, with reason" and move them to `gaps-archive.md`; delete the "unread" sentence; add the non-transactional-assign note. Update `handoff.md` and `project-overview.md` (`BinderRepository.assignWithHistory`, no use case). `app/CLAUDE.md` does not mention the use case; leave it.

## Open questions

- None blocking. Owner-only: if he wants B done anyway, the only form is injecting the DAO into the VM; this spec advises against.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| D: relaxed mocks hide a changed return value | Exact test-count equality (581) plus mutation checks; abandon trigger |
| D: a `BinderRepository(...)` construction site missed | Compiler via `compileDebugUnitTestKotlin`; 7 known sites |
| C: androidTest edits cannot be compiled here | Only 2 sites, grep-verified; Skyler runs `compileDebugAndroidTestKotlin` |
| Gradle sandbox failure | Skyler runs the command; do not claim green |

## Smallest shippable increment

Tasks 1 + 2 (Item C) ship alone. D is a separate, independently revertable commit. A/B/E ship as documentation.

## Friction Notes

- `.pipeline/` did not exist in the worktree; created it. Compound Bash heredoc was rejected by the worktree guard; used the Write tool instead.
