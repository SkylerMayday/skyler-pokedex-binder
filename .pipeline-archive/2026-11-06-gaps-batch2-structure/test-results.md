# Test results: Batch 2 (C + D)

STATUS: PASS. No bugs found, no tests added, no production code touched. Stage 4 not needed.

## 1. Fresh full suite (independent)
Command: `rm -rf app/build/test-results/testDebugUnitTest` then `./run_build.bat testDebugUnitTest --rerun-tasks` (exit 0). Full output: `.pipeline/evidence/test-results.log`.
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 2m 9s
31 actionable tasks: 31 executed
```
Counts summed from the 47 fresh XMLs: `tests=581 skipped=0 failures=0 errors=0` (matches expected 581; baseline 579 - 3 + 5).
Per class: BinderRepositoryTest 12, QuickScanViewModelTest 29, ScannerViewModelTest 12, DatabaseBackupManagerTest 11 (unchanged).

## 2. Mutation re-proof on `BinderRepository.assignWithHistory` (done by me, 2 of 3)
Each: edit, `testDebugUnitTest --tests '*BinderRepositoryTest'`, then `git checkout -- BinderRepository.kt`.
- Mutation A, `if (slot.isLocked) return false` removed. Log `.pipeline/evidence/mut-locked.log`:
```
BinderRepositoryTest > assignWithHistory on locked occupied slot is a no-op and returns false FAILED
    java.lang.AssertionError at BinderRepositoryTest.kt:88
17 tests completed, 1 failed
BUILD FAILED in 52s
```
- Mutation B, `pokemonName = slot.name` changed to `slot.id`. Log `.pipeline/evidence/mut-name.log`:
```
BinderRepositoryTest > assignWithHistory to occupied slot moves old card to card history FAILED
17 tests completed, 1 failed
BUILD FAILED in 49s
```
Each mutation failed exactly one test (the named one). `git diff --stat` before revert showed only BinderRepository.kt, 1 line. After `git checkout`, `git status --short` is empty. The third mutation (`insertAtEnd` removed) was not re-run by me. The Coder's `mut1-red.log` claims it.

## 3. Adversarial review
- `AssignCardUseCase` / `assignCardUseCase`: 0 hits in app/src (main, test, androidTest). Remaining hits are docs only: `gaps.md:36` (Task 4 docs update by orchestrator, still pending), plus historical `docs/superpowers/*`, `.pipeline-archive/*`, `.pipeline_archive/*` (point-in-time records, correct as-is).
- `DatabaseBackupManager(` no-arg: 0 live hits. Remaining hits are `.pipeline_archive/2026-07-16-db-backup/*` (history). All live constructions pass a reader: `DatabaseModule.kt:41` (`FrameworkSqliteVersionReader()`), `:73` (injected), `FakeDatabaseModule.kt:79`, `DatabaseBackupManagerTest.kt:68`, instrumented test `:72,108`.
- DatabaseModule: Hilt path uses `provideSqliteVersionReader()` -> `provideDatabaseBackupManager(versionReader)`, unchanged. `buildDatabase` (called by `provideDatabase` and non-Hilt `DatabaseModuleWiringTest`) builds the manager directly with the same `FrameworkSqliteVersionReader`, so both paths produce equivalent behaviour. Comment at :39-40 explains why. Main compile confirmed by the full run above.
- BinderRepository ctor `(MainBinderDao, SecondaryBinderDao)`: it is `@Inject` constructor-injected. Every `BinderRepository(` construction in src is in `BinderRepositoryTest` (all 2-arg, compiled in the full run). No androidTest code constructs it. `FakeDatabaseModule` provides both `MainBinderDao` (:52) and `SecondaryBinderDao` (:55), and `DatabaseModule` (:76, :79) does too, so Hilt resolves in both graphs. androidTest compile not run (PKIX), verified by reading only.
- Line-by-line equivalence vs `master:domain/AssignCardUseCase.kt`: getSlotByPokemonId null -> false (same); `isLocked` -> false before any write (same); occupied -> `secondaryBinderDao.insertAtEnd(SecondaryBinderEntry(pokemonId=slot.id, pokemonName=slot.name, cardId=assignedCardId!!, cardImageUrl=assignedCardImageUrl!!))` before assign (same order, same name-vs-id); return value = `assignCard(pokemonId, card.id, card.imageUrl, card.name, card.setName)` (same). Only difference: `this.` calls instead of `binderRepository.`, which is the intended merge. One subtle point: in the old design a VM test could stub `getSlotByPokemonId` separately from `assignCard`. Now both are on one class, so a VM-level mock of `binderRepository` returns a relaxed false for `assignWithHistory` (as the old relaxed use-case mock did). `ScannerViewModelTest` uses a non-relaxed mock and still passes in the full run.

## Coverage gaps
- androidTest edits (2 sites in `DatabaseBackupManagerInstrumentedTest`) and `DatabaseModuleWiringTest` unrun: compile and connected tests blocked/forbidden. Skyler to run `compileDebugAndroidTestKotlin`.
- Non-transactional assign (crash between `insertAtEnd` and upsert leaves a Card History duplicate) is not tested; pre-existing, to be recorded in gaps.md.
- `gaps.md:36` still names AssignCardUseCase (pending Task 4).

State: tree clean, no Gradle daemon running (`./run_build.bat --stop`: "No Gradle daemons are running.").

## Friction Notes
- Worktree guard rejects compound Bash and any command pairing `run_build` with a `*` glob plus `;`/`&&`/`echo`; single plain commands then Grep on the log works.
- Grep of whole repo returns large `.pipeline-archive` noise; scope to `app/src` plus the named doc files.
