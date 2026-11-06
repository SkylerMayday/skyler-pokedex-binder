# Changes: Batch 2 (C + D landed; A/B/E no source change)

Branch gaps-batch2-structure, base 5c765e0. Two commits, docs excluded (orchestrator). Raw logs: `.pipeline/evidence/changes-verify.log` (concatenation) plus per-step logs `baseline/c-tests/d-tests/mut1-red/mut2-red/mut3-red/mut-green/assemble/lint/androidtest.log` in the same dir.

## Commit 52b1205 - refactor: drop SqliteVersionReader constructor default (Item C)
- `data/local/backup/DatabaseBackupManager.kt`: `versionReader` has no default.
- `di/DatabaseModule.kt`: `buildDatabase` uses `DatabaseBackupManager(FrameworkSqliteVersionReader())` plus a comment why it is not injected. `@Provides` unchanged.
- `androidTest/.../DatabaseBackupManagerInstrumentedTest.kt` (2 sites): pass `FrameworkSqliteVersionReader()`. Compile UNVERIFIED (see check 3b).

## Commit 0fc8a67 - refactor: merge AssignCardUseCase into BinderRepository (Item D)
- `repository/BinderRepository.kt`: ctor adds `SecondaryBinderDao`; new `assignWithHistory(pokemonId, card)` (body verbatim from the use case, calling this.getSlotByPokemonId/assignCard). `assignCard` signature unchanged.
- Deleted `domain/AssignCardUseCase.kt` and its test.
- `QuickScanViewModel`, `ScannerViewModel`: dropped ctor param + import; 4 + 2 calls now `binderRepository.assignWithHistory`.
- Tests: `BinderRepositoryTest` 7 sites -> `(mainDao, secondaryDao)`, +5 tests (empty slot, occupied, missing, locked, returns true); `QuickScanViewModelTest` mock/ctor arg removed, 6 verifies retargeted; `ScannerViewModelTest` arg removed.
- No Hilt module change; no entity/schema/gradle/AppNavigation change (diff vs base: 11 files, all the above).

## Equivalence evidence
| Class | Baseline | After D |
|---|---|---|
| Total | 579 | 581 (579-3+5) |
| AssignCardUseCaseTest | 3 | gone |
| QuickScanViewModelTest | 29 | 29 |
| ScannerViewModelTest | 12 | 12 |
| BinderRepositoryTest | 7 | 12 |
After C the total was 579. Abandon trigger not hit.

Mutations on `assignWithHistory` (each applied, run `--tests '*BinderRepositoryTest'`, reverted via git checkout):
1. `insertAtEnd` disabled (`if (false)`): RED - `BinderRepositoryTest > assignWithHistory to occupied slot moves old card to card history FAILED` (17 tests completed, 1 failed)
2. `isLocked` guard removed: RED - `BinderRepositoryTest > assignWithHistory on locked occupied slot is a no-op and returns false FAILED` (1 failed)
3. `pokemonName = slot.name` -> `slot.id`: RED - `... to occupied slot moves old card to card history FAILED` (1 failed)
Restored: GREEN (`mut-green.log`: BUILD SUCCESSFUL), `git status` clean after.

## Item M verification
1. Typecheck: PASS - Kotlin compile of main + unit tests inside `testDebugUnitTest --rerun-tasks` and `assembleDebug`, no `e:` lines.
2. Lint: N/A - `lintDebug` fails in sandbox: `Execution failed for task ':app:generateDebugAndroidTestLintModel' ... PKIX path validation failed ... Could not download ui-test.aar (androidx.compose.ui:ui-test-android:1.11.3)`.
3. Tests: PASS - `./run_build.bat testDebugUnitTest --rerun-tasks` after deleting `app/build/test-results`: baseline 579, after C 579, after D 581 (XML tests sum), BUILD SUCCESSFUL, 0 failures.
3b. androidTest compile: NOT RUN/FAIL - `compileDebugAndroidTestKotlin` fails on the same `PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors` (not retried). Skyler to run it locally; no `connectedDebugAndroidTest` run.
4. Production build: PASS - `assembleDebug` BUILD SUCCESSFUL.
5. Dev server: N/A - Android app, no server (no emulator launched).
6. No stray debug output: PASS - added lines contain no println/Log.d/debugger; `grep AssignCardUseCase` and `DatabaseBackupManager()` in app (excl. build) -> 0 hits.
7. git status: PASS - clean tree after both commits (`.pipeline/` is git-ignored); `git diff 5c765e0 --stat` = 11 files, none of entities/PokedexDatabase/schemas/gradle.

## Simplification/review pass
Reuse: nothing duplicated; `assignWithHistory` reuses `getSlotByPokemonId`/`assignCard`. Quality: no dead code left; the VM `SecondaryBinderDao` params are still used elsewhere in both VMs. Correctness: relaxed `binderRepository` mock in QuickScan returns false for `assignWithHistory`, as the old relaxed use-case mock did; ScannerViewModelTest's non-relaxed `mockk()` unchanged in semantics. No fixes needed. No new dependencies (Item P skipped). Items A/B/E: no source change per spec. Non-transactional assign remains (spec says record in gaps.md - orchestrator).

## Friction Notes
- Compound Bash (`cd && ...`, python heredoc) is rejected by the worktree guard; single commands only.
- An early `Read` of BinderRepository.kt dumped ~550 lines; use offset/limit next time.
