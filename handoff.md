# Handoff — Session 22 (2026-10-03), final wrapcon

## 1. Goals

Triage `gaps.md`: archive entries that are no longer gaps, then fix every gap that needs no phone or key (ten code
fixes) and merge them to `master`.

## 2. Current State

- **`master` = `4167771`** (code: `e7e0b72` gaps fixes, `c7c9367` hermetic androidTest DataStore module, `4167771` clear scan
  failure message; branches and worktrees deleted). **537/537 unit tests green** and the instrumented suite **22/22 on the
  emulator** (fresh result files). Reviews: 93/92/88, 95, 93.
- **Phone has the build from 2026-10-04 11:42** (includes `e7e0b72`, NOT `c7c9367`/`4167771`). Install again for the clearer
  scan-failure message: `powershell.exe -File scripts\install_debug.ps1`.
- **Scan error seen 2026-10-04 12:02** (`Unable to resolve host "api.anthropic.com"`): cause unknown (log not captured; phone
  was disconnected). The new build shows a clear message and returns the normal no-match screen when Gemini answered empty.
  A real DNS outage is a phone/network issue (Wi-Fi/data, Private DNS, VPN, captive portal).
- **Uncommitted, deliberately:** `gaps.md`, `gaps-archive.md` (new), `handoff.md`, `project-overview.md` (this wrapcon's
  docs), plus Skyler's own `CLAUDE.md` / `app/CLAUDE.md` edits and `scripts/scan_capture.log`.
- **`security-crypto` stays.** Skyler asked to drop it believing the name meant cryptocurrency; a plaintext-storage
  revert was built and reviewed, then backed out before merge. Entry remains under Parked in `gaps.md`.

## 3. Active Files

- `data/remote/TcgcsvJapanSource.kt`, `TcgdexSetCatalog.kt` (failure memory), `domain/CardScanProvider.kt`,
  `CardScanShared.kt`, `GeminiCardScanner.kt`, `ParsedCardInfo.kt` (fallback + breaker), `repository/CardSearchRepository.kt`
  (`nameVerified`, NFC), `SettingsRepository.kt` / `PublishSettingsRepository.kt` (`toString` redaction),
  `ui/scanner/ScannerViewModel.kt`, `ui/settings/SettingsScreen.kt` (label)
- `gaps.md` (session 22 section on top), `gaps-archive.md` (closed entries, history only)

## 4. Changes Made

| # | Change | Why |
|---|---|---|
| 1 | `gaps.md` shrank 1,397 → ~680 lines; 79 struck entries + 8 superseded calibration entries moved to `gaps-archive.md` | Struck = closed; calibration constants (`0.75` / `1.30`) judged working because live scans resolve cards |
| 2 | Failed TCGCSV product downloads remembered per group (15 min, 6 h on 401/403); TCGdex English names remember failure (15 min) | Retry-storm risk |
| 3 | Sync label now "Still missing N (no image from TCGdex or the Japan backup)"; KDoc fixed; 3 weak tests now fail by assertion (mutation-checked) | Gap list |
| 4 | `AppSettings` and `PublishConfig` `toString` redact secrets; Gemini `Response` closed on all branches | Key leak risk |
| 5 | `CancellationException` rethrown in `ScannerViewModel`; `lowConfidenceCards` compares by id | Gap list |
| 6 | `nameVerified` = lone whole-name match (tolerates ex/gx/v/vmax/vstar); `normalizeName` NFC | `ミュウ` matched `ミュウツー` |
| 7 | Empty Gemini reply falls back to Claude; Gemini 429 starts a 10 min breaker (only when a Claude key exists) | Wasted failed call per scan after quota exhaustion |

## 5. Failed Attempts

- **Item 11 (plaintext secrets, removing `security-crypto`)** was built and passed review, then backed out: Skyler had misread
  the library as cryptocurrency-related. Net effect on `master`: zero.
- First `git add -A` in the worktree hit a transient `Permission denied` on `.git/objects`; the retry worked.
- `git worktree remove` failed on "Filename too long" (build dirs); a `\\?\` long-path `Remove-Item` then `git worktree prune` fixed it.
- **Gradle worked from Claude this session** (`./run_build.bat <task>` from Bash) — the loopback wall did not recur.

## 6. Next Steps

1. **Install the latest build and try it:** `powershell.exe -File scripts\install_debug.ps1`. Check Settings shows "Danger zone", "Card images" and the Discord switch.
2. ~~Run `connectedDebugAndroidTest`~~ **Done 2026-10-04 on the emulator** (never the phone: AGP would uninstall the app and wipe its data): now **22/22** incl. the Reset and `fillBlankImage` SQL, after the `FakeSecurityPrefsModule` fix. Next: **Reset collection data** (it exports first) and **rescan**. If a scan fails again, plug the phone in and capture `adb logcat -d -s ScannerMatch` right away.
3. ~~Re-scan Klawf and Mega Malamar~~ **Done 2026-10-04** on the new build: both accepted via `IDENTITY_PINNED` (they never reach `nameVerified`). Still to check: an M6 card shows an image; press **Sync card images** twice and confirm the second fetches nothing.
4. Check the cross-language header on a Palafin / other unmatched Asian-language card.
5. **Rotate the Gemini API key** (still unrotated) — blocked on Skyler.
6. Next code candidates (`gaps.md`): triage sessions 4–15 entries I have not verified stale (e.g. `PerceptualHasher` bit-mapping, single-candidate short-circuit); the small test gaps in the session 22 section.
7. Later, if wanted: PTCG-database for Korean / Traditional Chinese; `security-crypto` replacement (a plaintext revert was rejected-by-misunderstanding — decide deliberately); English TCGCSV name index.

Blocked on Skyler: items 1-4 (phone), 5 (key). Publish only AFTER the rescan is done (the Discord switch is OFF by default).
Commit the four doc files when ready; they are intentionally not committed.
