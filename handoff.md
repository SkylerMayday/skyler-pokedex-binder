# Handoff — Session 25, Batches 1-2 merged (2026-11-06)

## 1. Goals

Clear all of `gaps.md` and tech debt (Gemini key rotation dropped). Four sequential batches, each through `dev-team-pipeline`: 1 scanner behaviour + tests, 2 structure cleanup, 3 replace `security-crypto` with direct Keystore AES-GCM, 4 dependency bumps. Plan: `C:\Users\SkylerMayday\.claude\plans\spicy-wiggling-fern.md`. Deferred on purpose (recorded, not built): Korean/TC PTCG-database source, English image fallback, Japanese backup search (U3), `accompanist-permissions` / `material` removal (need a device look), art-hash 20-45 band (needs calibration data), TCGdex data gaps (external), `claude_scan_bakeoff.py`.

## 2. Current State

- **Batch 1 merged** (ff) at `9cf4fb6` (3-lens Opus review 90 / 90 / 91). **Batch 2 merged** (ff) from `gaps-batch2-structure` (`3e72b19`, review 94): `SqliteVersionReader` constructor default removed; `AssignCardUseCase` merged into `BinderRepository.assignWithHistory`; the five DAO pairs, `ConnectingArtRepository` and the duplicate screens closed as not a gap with evidence. Pipeline artifacts in `.pipeline-archive/2026-11-06-gaps-batch{1-scanner,2-structure}/`.
- Unit tests **581/581** on `master` (fresh XMLs, `--rerun-tasks`, recounted).
- **Not verified:** anything on the phone; `lintDebug`; `compileDebugAndroidTestKotlin` (incl. the two `DatabaseBackupManagerInstrumentedTest` edits and `DatabaseModuleWiringTest`); `connectedDebugAndroidTest` (sandbox PKIX wall on uncached artifacts).
- Batches 3-4 not started.

## 3. Active files

`domain/CardScanShared.kt`, `CardScanProvider.kt`, `ClaudeCardScanner.kt`, `GeminiCardScanner.kt`, `SyncCardImagesUseCase.kt`, `ui/scanner/ScannerScreen.kt`, `ScannerViewModel.kt`, `data/remote/TcgcsvJapanSource.kt`, new test helper `repository/ToStringGuard.kt`, `gaps.md`, `gaps-archive.md`.

## 4. Changes made (Batch 1)

- Per-attempt `attempt provider= n= ms= outcome=` log; 429 body logged (key redacted, 300 chars); `scan provider=` log after the result.
- `ScanProvider` public enum used in `RateLimited` / `RateLimitException`; console URL per provider; `MalformedReplyException` + `fromReply` (Moshi parse errors no longer read as "check your internet"); fixed wording for Gemini key rejection and Gemini-only failures; `EmptyScanException` removed.
- `retryDelayMs` helper + `RETRY_MS` / `BLOCKED_RETRY_MS`; auto image sync window rotates daily (`SyncCardImagesUseCase`).
- 27 new tests (ViewModel error mapping, hasher cancellation `<= 4`, Tcgcsv cooldown/budget/403/503, toString guard, sync rotation); mutation-checked.

## 5. Failed attempts / skipped on purpose

- Item 11 (`connectedDebugAndroidTest` exit 1 with `OK (22 tests)`): emulator run died at dependency download (PKIX) before any test; cause still unexplained.
- Share-sheet rotation gate Compose test: deferred (emulator-only `ActivityScenario.recreate()` test).
- Worktree tool branched from stub `origin/main`; reset to `master` before work. Check the base of any new worktree.

## 6. Next Steps

1. Run Batch 3 (Keystore AES-GCM replacing `security-crypto`; Opus planner, fail-safe migration) through the pipeline.
2. Then Batch 4 (dependency bumps; Skyler runs the builds).
3. In your own shell: `./run_build.bat lintDebug`, `./run_build.bat compileDebugAndroidTestKotlin`, and `$env:ANDROID_SERIAL="emulator-5554"; .\gradlew.bat connectedDebugAndroidTest`. Install, scan, watch `adb logcat -s ScannerMatch` for the new `attempt ... ms=` lines and any `outcome=http400`; press "Back up now" once.

Blocked on Skyler: item 3.
