# Handoff — Session 26, grid layout + `security-crypto` dropped (2026-11-11)

## 1. Goals

Pokédex binder to 4 across for base + regional, 3 across for alt forms / megas / VMax; then drop `security-crypto` once the Keystore migration was proven on the phone.

## 2. Current State

- **Done, committed, pushed to `origin/master`:** `22ec8c5` (grid: 12 columns, BASE/REGIONAL span 3, ALTERNATE_FORM/MEGA/GMAX span 4; Card History, Personal Collection, Connecting Art stay 3x3, Unown already 4) and `ab85185` (`security-crypto` + `migrateLegacySecrets` + its 12 tests + legacy backup excludes removed).
- Verified: unit tests **595/595** fresh, `lintDebug`, `compileDebugAndroidTestKotlin`. Phone (SM-S948B): migration `migrated 2 of 2` x2, only `*_v2.xml` remain, no plaintext prefixes in them, 4 keys visible; relaunch after the drop shows no `SecurityPrefs` warnings and unchanged `_v2` files.
- **Not verified:** how the 4-across tiles look (Skyler to eyeball); drawer tap-through after the dependency bumps; "Back up now"; emulator `connectedDebugAndroidTest`.
- Work was done inline on `master` (no pipeline, no worktree): small removal plus a layout tweak.

## 3. Active files

`ui/mainbinder/MainBinderScreen.kt`, `di/SecurityPrefsModule.kt`, `gaps.md`.

## 4. Changes made

See section 2. Search results stay a flat list, so mixed tile sizes can appear in one row while searching.

## 5. Failed attempts / skipped on purpose

A `cat` of the `_v2` prefs files was blocked by the auto-mode classifier (it would print secrets); replaced by counting plaintext-prefix matches. The old `_androidx_security_master_key_` Keystore entry was left in place (optional).

## 6. Next Steps

1. Skyler: look at the Pokédex grid; tell Claude if search-result rows should use one tile size, or if the web snapshot should mirror 4/3.
2. Phone tap-through of every drawer section (nav / Room / lifecycle bumps have no UI tests); press "Back up now" once.
3. Emulator run: `$env:ANDROID_SERIAL="emulator-5554"; .\gradlew.bat connectedDebugAndroidTest` (emulator only; Claude can run it on request).
4. Remaining debt is in `gaps.md`; most of it is deferred on purpose.

Blocked on Skyler: items 1-2.

---
Previous handoff (session 25) follows.

## (s25) 1. Goals

Clear all of `gaps.md` and tech debt (Gemini key rotation dropped). Four sequential batches, each through `dev-team-pipeline`: 1 scanner behaviour + tests, 2 structure cleanup, 3 replace `security-crypto` with direct Keystore AES-GCM, 4 dependency bumps. Plan: `C:\Users\SkylerMayday\.claude\plans\spicy-wiggling-fern.md`. Deferred on purpose (recorded, not built): Korean/TC PTCG-database source, English image fallback, Japanese backup search (U3), `accompanist-permissions` / `material` removal (need a device look), art-hash 20-45 band (needs calibration data), TCGdex data gaps (external), `claude_scan_bakeoff.py`.

## 2. Current State

- **Batch 1 merged** (ff) at `9cf4fb6` (3-lens Opus review 90 / 90 / 91). **Batch 2 merged** (ff) from `gaps-batch2-structure` (`3e72b19`, review 94): `SqliteVersionReader` constructor default removed; `AssignCardUseCase` merged into `BinderRepository.assignWithHistory`; the five DAO pairs, `ConnectingArtRepository` and the duplicate screens closed as not a gap with evidence. Pipeline artifacts in `.pipeline-archive/2026-11-06-gaps-batch{1-scanner,2-structure}/`.
- **Batch 3 merged** (ff) from `gaps-batch3-keystore` (`e53921e`; 3-lens Opus review 90 / 87 / 92 + review-fix pass): `security-crypto` replaced by `KeystoreSecretStore` (AES-GCM over AndroidKeyStore, per-key AAD, files `*_v2`), verified fail-safe migration from the old encrypted prefs, `createOrResetOnDecryptFailure` deleted (unreadable values read as empty, nothing is wiped), Gemini plaintext-key migration guarded on commit result. **`security-crypto` is still in the build** (the migration reads the old store with it); the drop change waits on Skyler's checks below.
- Unit tests **607/607** on `master` (fresh XMLs, `--rerun-tasks`, recounted).
- **Batch 4 merged** (dependency bumps, one commit per library on `master`): coil 2.7.0, core-ktx 1.17.0, lifecycle 2.10.0, navigation-compose 2.9.8, room 2.8.5, each capped by the toolchain (AGP 8.9.1, compileSdk 36); details in `gaps.md`. Verified on `master`: `testDebugUnitTest --rerun-tasks` 607/607, `lintDebug`, `compileDebugAndroidTestKotlin` all pass.
- **Sandbox PKIX fixed:** `export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` before `./run_build.bat <task>` lets Gradle download uncached artifacts (recorded in root `CLAUDE.md`).
- **Not verified:** anything on the phone or a real AndroidKeyStore; `connectedDebugAndroidTest` / `KeystoreSecretStoreInstrumentedTest` (compiles, never run); `DatabaseModuleWiringTest`; the drawer-section tap-through after the nav/room/lifecycle bumps.

## 3. Active files

`domain/CardScanShared.kt`, `CardScanProvider.kt`, `ClaudeCardScanner.kt`, `GeminiCardScanner.kt`, `SyncCardImagesUseCase.kt`, `ui/scanner/ScannerScreen.kt`, `ScannerViewModel.kt`, `data/remote/TcgcsvJapanSource.kt`, new test helper `repository/ToStringGuard.kt`, `gaps.md`, `gaps-archive.md`.

## 4. Changes made (Batch 1)

- Per-attempt `attempt provider= n= ms= outcome=` log; 429 body logged (key redacted, 300 chars); `scan provider=` log after the result.
- `ScanProvider` public enum used in `RateLimited` / `RateLimitException`; console URL per provider; `MalformedReplyException` + `fromReply` (Moshi parse errors no longer read as "check your internet"); fixed wording for Gemini key rejection and Gemini-only failures; `EmptyScanException` removed.
- `retryDelayMs` helper + `RETRY_MS` / `BLOCKED_RETRY_MS`; auto image sync window rotates daily (`SyncCardImagesUseCase`).
- 27 new tests (ViewModel error mapping, hasher cancellation `<= 4`, Tcgcsv cooldown/budget/403/503, toString guard, sync rotation); mutation-checked.

## 5. Failed attempts / skipped on purpose

- Item 11 (`connectedDebugAndroidTest` exit 1 with `OK (22 tests)`): the sandbox emulator run died at dependency download (PKIX, since fixed) before any test; cause still unexplained, not retried yet.
- Share-sheet rotation gate Compose test: deferred (emulator-only `ActivityScenario.recreate()` test).
- Worktree tool branched from stub `origin/main`; reset to `master` before work. Check the base of any new worktree.

## 6. Next Steps

1. **Phone tap-through of every drawer section** after installing master (nav, Room and lifecycle bumps have no UI tests); revert the one library commit that breaks a section.
2. After the Keystore checks pass (items 3-5 below), the "drop `security-crypto`" change (checklist in `gaps.md` batch 3 section).
3. Emulator run: `$env:ANDROID_SERIAL="emulator-5554"; .\gradlew.bat connectedDebugAndroidTest` (emulator only; Claude can run it on request).
4. **Phone check for the Keystore migration:** write down your 4 secrets (Gemini key, Anthropic key, GitHub PAT, Discord webhook) first; `installDebug`; confirm all 4 still present in Settings/Publish; logcat tag `SecurityPrefs` shows `migrated N of N`; only `*_v2.xml` remain in `shared_prefs`. Do not downgrade the APK afterwards without exporting secrets (a rotated PAT can be lost).
5. Install, scan, watch `adb logcat -s ScannerMatch` for the new `attempt ... ms=` lines and any `outcome=http400`; press "Back up now" once.

Blocked on Skyler: items 3-5 (the drop-`security-crypto` change waits on item 4).
