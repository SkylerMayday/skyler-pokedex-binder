# Test Results — Session 16 Gap-Audit Batch (5 fixes)

**Deviation disclosed**: run by the orchestrator directly, not `pipeline-tester`. Reason: same
recurring pattern as this project's other pipeline runs — the Coder's sandbox hit the Gradle daemon
IPC wall on every attempt (including a bare `gradlew help`), and the orchestrator ran the identical
commands directly, which cleared immediately.

## Result: clean first pass, no bugs found this stage

`testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL`. Fresh JUnit XML:
```
tests=310 skipped=0 failures=0 errors=0
```
310 = 305 baseline (post rotation/crop-margin session) + 5 new (1 `SmartThresholdUseCaseTest`
regression, 1 `GeminiCardScannerTest`, 3 `SettingsRepositoryTest`).

`SettingsRepositoryTest` (new, Fix 1's migration-gate coverage), all 3 pass:
```
non-empty encrypted value means migration should not run — pass
null encrypted value means migration should run — pass
empty encrypted value means migration should run — pass
```

`assembleDebug lintDebug --rerun-tasks`: `BUILD SUCCESSFUL`, 52/52 tasks executed, no lint errors
(build would fail on Error-severity lint findings by default; it didn't).

## Manual review of the highest-risk change (Fix 1)

Read the full rewritten `SettingsRepository.kt`. Confirmed:
- `getGeminiApiKey()`/`setGeminiApiKey()` signatures unchanged — all 3 call sites need no edits.
- Migration order is exactly read-legacy → write-encrypted → clear-plaintext, never reversed.
- `settings` Flow's `map` block reads `securePrefs` synchronously with no dispatcher wrapping,
  matching `PublishSettingsRepository.config`'s own established precedent for the identical pattern
  — not a new risk this fix introduces.
- Clearing the plaintext DataStore key inside `migrateGeminiKeyIfNeeded()` (itself called from
  `settings`'s own `map` block) triggers one extra re-emission of `context.dataStore.data`, which
  re-invokes `migrateGeminiKeyIfNeeded()` again — but `geminiKeyMigrated` is already `true` by then,
  so it returns immediately. Confirmed this terminates, not an infinite loop.
- `PerceptualHasher.kt` has zero diff (Fix 3 constraint) — confirmed via `git status`.

## Acceptance criteria trace (against `.pipeline/specs.md`)

- [x] Fix 1: signatures unchanged, encrypted storage, migration order correct, gate function
      unit-tested (3/3 cases), `assembleDebug`/`testDebugUnitTest` clean.
- [x] Fix 2: both XML files updated, `domain="sharedpref" path="backup_import_state.xml"` present
      in all 3 required blocks (2 in `data_extraction_rules.xml`, 1 in `full_backup_content.xml`).
- [x] Fix 3: `HASH_MARGIN` branch gated on `hashMatch.distance != Int.MAX_VALUE`; new regression
      test passes; existing "wide margin wins" test passes unmodified; `PerceptualHasher.kt` zero-diff.
- [x] Fix 4: new 404-no-retry test passes; `GeminiCardScanner.kt` not in the diff at all.
- [x] Fix 5: `BackupImporter.kt` diff is comment-only (confirmed via reading the diff).
- [x] 310/310 tests (baseline 305 + 5 new), fresh XML. `assembleDebug`/`lintDebug --rerun-tasks` clean.
- [x] No dependency version changed (`gradle/libs.versions.toml` not in the diff).
- [x] No scanner geometry file (`ScannerScreen.kt`, `ImageProxyExt.kt`) in the diff.

**Status: DONE (first pass).** No Stage 4 (Debugger) needed — first pass was clean, no failures to
root-cause.

## Re-verification after Reviewer's P1 finding (auto-loop, score-threshold)

Reviewer found a real durability bug in the migration's encrypted write (`.apply()` vs `.commit()`
race — see `.pipeline/review-verdict.md`). Coder respawned, fixed the one line + comment, touched
nothing else (`git diff --stat` confirms only `SettingsRepository.kt` changed since the first pass).

Orchestrator re-ran directly:
- `testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL`, fresh XML **310/310, 0 failures** (same
  count as before — the fix changes timing/durability, not test-observable behavior, consistent
  with the Reviewer's own note that this path has no test coverage in this batch).
- `assembleDebug lintDebug --rerun-tasks`: `BUILD SUCCESSFUL`, 52/52 tasks, no lint errors.
- `git status`: same 8 files as the first pass, nothing extra touched.

**Status: DONE.**
