# Handoff — Session 25, Batch 1 merged (2026-11-06)

## 1. Goals

Clear all of `gaps.md` and tech debt (Gemini key rotation dropped). Four sequential batches, each through `dev-team-pipeline`: 1 scanner behaviour + tests, 2 structure cleanup, 3 replace `security-crypto` with direct Keystore AES-GCM, 4 dependency bumps. Plan: `C:\Users\SkylerMayday\.claude\plans\spicy-wiggling-fern.md`. Deferred on purpose (recorded, not built): Korean/TC PTCG-database source, English image fallback, Japanese backup search (U3), `accompanist-permissions` / `material` removal (need a device look), art-hash 20-45 band (needs calibration data), TCGdex data gaps (external), `claude_scan_bakeoff.py`.

## 2. Current State

- **Batch 1 merged** (ff) into `master` at `9cf4fb6`; pipeline artifacts archived in `.pipeline-archive/2026-11-06-gaps-batch1-scanner/`. Worktree `.claude/worktrees/gaps-batch1-scanner` and branch `worktree-gaps-batch1-scanner` still exist (remove once pushed).
- Unit tests **579/579** on `master` (fresh XMLs, `--rerun-tasks`, recounted). 3-lens Opus review 90 / 90 / 91.
- **Not verified:** anything on the phone; `lintDebug`; `compileDebugAndroidTestKotlin`; `connectedDebugAndroidTest` (sandbox PKIX wall on uncached artifacts).
- Batches 2-4 not started.

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

1. **Push** `master` (done in this session if the push line below says so), then run Batch 2 (structure cleanup) through the pipeline.
2. Then Batch 3 (Keystore replacement, Opus planner), Batch 4 (dependency bumps; Skyler runs the builds).
3. In your own shell: `./run_build.bat lintDebug`, `./run_build.bat compileDebugAndroidTestKotlin`, and `$env:ANDROID_SERIAL="emulator-5554"; .\gradlew.bat connectedDebugAndroidTest`. Install, scan, watch `adb logcat -s ScannerMatch` for the new `attempt ... ms=` lines and any `outcome=http400`; press "Back up now" once.

Blocked on Skyler: item 3.
