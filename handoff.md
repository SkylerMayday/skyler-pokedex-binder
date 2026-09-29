# Handoff — Session 19 (2026-09-29)

## 1. Goals

Live-test the scanner after session 18 (species-guard `query=` log, Gemini retry budget, crop
margin 1.30). Live testing found the real blocker: saved captures were zoomed in vs the preview and
cut off the collector number. Goal became: make the still match what the preview shows.

## 2. Current State

- **Capture fix is in and live-verified on the image only.** Committed `5c9f27e` (build succeeded
  on Skyler's machine, `installDebug`). A Castform scan after the fix shows the whole card with
  margin on all four sides, `116/172` visible.
- **Nothing downstream of the camera has been live-tested.** Gemini returned 429 ("daily quota") on
  every scan after the fix, so the `query=` label, Castform via `dex+number+total`, number reading,
  and the 4-attempt retry are all still unexercised on-device. Skyler says Gemini is back online.
- Unit tests were run by Skyler earlier this session (passed, before the zoom change). Not re-run
  since; the zoom change didn't touch tested code paths.
- Commits this session: `5c9f27e` zoom fix · `6a0ff73` stop logging request URLs · `e101080` drop
  `okhttp-logging`. Both TEMP diagnostics (crop geometry, stream diag) were added and removed
  again, never committed.

## 3. Active Files

- [ScannerScreen.kt](app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt) — `ultrawideId = null`, `ZoomState` observer after bind; `selectUltrawidePhysicalCameraId` now unused
- [NetworkModule.kt](app/src/main/java/com/skyler/pokedexbinder/di/NetworkModule.kt) — logging interceptor removed
- `ScannerScreenTest.kt` — still tests the now-unused `selectUltrawidePhysicalCameraId`

## 4. Changes Made

- Physical-camera-id selection disabled; logical camera bound plainly, min zoom (0.6) applied on
  first `ZoomState`. Detail: `project-overview.md` item 20.
- Removed OkHttp `HttpLoggingInterceptor` (it logged `?key=<Gemini API key>`), dropped the dependency.
- Uncommitted, not mine: edits to `CLAUDE.md` / `app/CLAUDE.md`; untracked `scripts/scan_capture.log`.

## 5. Failed Attempts

- Gradle loopback wall again: 2/2 from Claude (Bash + PowerShell). Skyler ran Gradle himself. 5th
  consecutive session needing his hands.
- First hypothesis (crop aspect 0.816 vs card 0.716 is the bug) was wrong — it's a harmless clamp.
- Stream-diagnostic `CaptureCallback` never saw still-capture results, so it couldn't show which
  lens the still used; the fix was validated by outcome (image) instead.
- Paired preview/JPEG capture failed twice (auto-capture fired before my screencap) until a
  background `screencap` loop was used.

## 6. Next Steps

1. **Scan several cards now that Gemini is back**, with `adb logcat -s ScannerMatch:*` (adb is at
   `%LOCALAPPDATA%\Android\Sdk\platform-tools`). Confirm: `query=` label on `search candidates=`,
   Castform (dex 351) resolves via `dex+number+total`, collector number now parses, no
   `number=null`. Watch Reviewer F1 (`dex+number` pre-empting a correct `number+total`).
2. **If that scan works:** delete `selectUltrawidePhysicalCameraId` and its tests, run
   `.\gradlew.bat testDebugUnitTest --rerun-tasks` in his own PowerShell.
3. **Rotate the Gemini API key** (it was printed in logcat and in this session's transcript), then
   consider moving it from the URL query to the `x-goog-api-key` header in `GeminiCardScanner.kt`.
4. Check guide framing on a few cards — `GUIDE_FRAME_WIDTH_RATIO=0.75` was tuned before the preview
   moved to the ultrawide at 0.6x.
5. Carried: hash-margin constants uncalibrated; publish-diff confirmation.

Blocked on Skyler: everything above needs the phone or his own PowerShell for Gradle.
