# Handoff — Session 23 (2026-11-04)

## 1. Goals

Check which models do card scanning; move to current models; audit the codebase for fixes, redundancy and improvements; apply them.

## 2. Current State

- **Models:** Gemini `gemini-3.8-flash` (free tier, same price as 3.6), Claude fallback `claude-sonnet-5-5`. Committed on `master` as `6a2225b`. **Not tried on the phone.**
- **Hygiene batch** on branch `hygiene-2026-11-04` (worktree `.claude/worktrees/hygiene-2026-11-04`), uncommitted/unmerged until Skyler says merge: backup exclusions for the encrypted secret prefs; scan `callTimeout` 10 s; parallel hash downloads (max 4); provider-aware rate-limit text; `GeminiCardResult` renamed `ScannedCardJson`; 10 dead-code removals; `scripts/test_gemini_retry.ps1` deleted; `docs/superpowers/README.md` added.
- **Verified:** 547/547 unit tests (fresh), `assembleDebug` green, mutation-checked F2/F3/F4, 3-lens review 92/89/88 (ship).
- **Not verified:** `compileDebugAndroidTestKotlin`, `lintDebug` (sandbox TLS/PKIX failure on uncached Gradle artifacts), anything on-device.

## 3. Next Steps

1. Run `./run_build.bat compileDebugAndroidTestKotlin` and `./run_build.bat lintDebug` in your own shell (inside the worktree or after merge).
2. Merge `hygiene-2026-11-04` into `master` (then push; `origin` exists), install, and do a few scans. Watch `adb logcat -s ScannerMatch`: `provider=gemini` = 3.8 works; `provider=claude fallbackReason=...` = Gemini failed. Watch for timeouts: if scans fail near 10 s, raise `SCAN_CALL_TIMEOUT_MS`.
3. Press "Back up now" once after installing so Google's stored backup stops containing the encrypted prefs.
4. Rotate the Gemini API key (still unrotated; blocked on Skyler).
5. Candidates (`gaps.md`, session 23 section): elapsed-ms logging per scan attempt; recovery when `EncryptedSharedPreferences` decrypt fails; the `ScannerScreen.kt` split; the older session-22 items.

Blocked on Skyler: items 1-4.
