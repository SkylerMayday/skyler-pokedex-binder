# Handoff — Session 17 (2026-09-22)

## 1. Goals

Skyler connected his S26 Ultra to **live-test session 16's pending items** (rotation/crop-margin
fix, hash-margin confidence constants) — scanned 4 times, asked for a logcat check.

**Missed this on the first pass**: initially read the log check as a generic health check and only
looked for crashes/errors/warnings, not for what the 4 scans actually produced. Corrected after
Skyler pointed out the actual purpose. Re-checked the same captured log for the match pipeline
(`ScannerMatch` tag, Gemini HTTP calls) and found the real story: **all 4 scans failed at the
Gemini API call** — every one exhausted its 3-attempt retry budget, 11/12 requests got `503
Service Unavailable`, 1 timed out. None ever reached the matching pipeline
(`ScannerViewModel.kt:133`'s catch-all would have shown `ScannerState.Error("Gemini error: 503")`
on screen each time). **Session 16's live-test items are still unverified** — this session
produced zero successful scans to check them against. `503` is Gemini's own overload signal, not
the session-14 `429`/shared-quota gap — different failure mode, not an app bug, not fixed here.

## 2. Current State

Two real bugs root-caused and fixed directly (via the `debugging` skill, not a full
`dev-team-pipeline` run — small, single-file, already root-caused from the log evidence). Third
finding investigated and disputed, not fixed. Build-verified, **not committed, not live-device
re-tested**.

- **Camera unbind race** — `CameraPreview`'s `AndroidView` never unbound the camera provider on
  leaving composition, relying on CameraX's own lifecycle-owner-stop unbind which lagged Compose's
  own teardown. Fixed via `onRelease` calling `provider.unbindAll()` directly.
- **Focus-request cancellation storm** — closes the standing `CameraControl$OperationCanceledException`
  gap open since 2026-09-04. Edge-triggered auto-refocus had no guard against re-firing before the
  previous request settled; normal hand jitter flickering the detection fired overlapping
  `startFocusAndMetering()` calls. Fixed with a `focusInFlight` guard on just that call site.
- **SQLite lock log line** — investigated, disputed as not a bug (single benign occurrence, no
  crash, no app-owned locking code). Not fixed.

Full detail: `project-overview.md` item 18, `gaps.md`'s 2026-09-22 entry.

## 3. Active Files

- [ScannerScreen.kt](app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt) —
  uncommitted changes (both fixes above).

## 4. Changes Made

- `ScannerScreen.kt`: added `cameraProviderHolder` + `AndroidView`'s `onRelease` calling
  `unbindAll()`; added `focusInFlight` boolean guarding the edge-triggered `triggerFocus()` call.
  **Not committed** — Skyler didn't ask for a commit this session.

## 5. Failed Attempts

- `compileDebugKotlin --rerun-tasks` hit the Gradle loopback wall 3/3 times run directly by the
  orchestrator (inline `-Command`, a real `.ps1` with `--no-daemon`, the same `.ps1` with
  `dangerouslyDisableSandbox: true`) — the first time this has failed at orchestrator level since
  the standing lesson was written session 16. Resolved by Skyler running the identical command
  himself via screen share: `BUILD SUCCESSFUL in 31s`. Lesson file updated with this new data point
  (`~/.claude/rules/lessons/subagent-sandbox-blocks-loopback-sockets-try-orchestrator-first.md`).
- Computer-use screen access was denied when first attempted — Skyler was away from the PC at that
  moment, so nobody was there to approve the permission dialog. He came back and screen-shared from
  his phone instead.

## 6. Next Steps (updated after the 2026-09-23 retest — see §2/§4 below)

Retested after installing a fresh build (the phone was still on the pre-fix APK for the first
retest attempt). Results: focus-cancellation storm confirmed fully fixed (0 occurrences). Camera
unbind race much improved, not literally zero (async `unbindAll()` ceiling, not chasing further).
Rotation fix confirmed correct on this session's one successful scan. Crop was visibly too tight on
all 4 edges → bumped `CROP_MARGIN_FACTOR` 1.10 → 1.30, hand-updated the 2 dependent test files
(couldn't run the actual suite — Gradle wall blocked it again).

1. **Get a real test run.** `compileDebugKotlin`/`testDebugUnitTest` failed the Gradle loopback
   wall 5 times combined across today's session (orchestrator-direct every time) — Skyler needs to
   run `.\gradlew.bat testDebugUnitTest --rerun-tasks` himself (same workaround that cleared
   `installDebug` and yesterday's `compileDebugKotlin`) to confirm the hand-recomputed
   `ScannerScreenTest`/`ImageProxyExtTest` values are actually correct, not just carefully
   hand-checked.
2. **One more live scan** to confirm `CROP_MARGIN_FACTOR=1.30` actually captures the full card now
   (not just "less clipped") — pull the new `scan_debug_*.jpg` and eyeball all 4 edges.
3. **Commit, once both of the above hold** — `ScannerScreen.kt` + the 2 test files are currently
   uncommitted.
4. Everything else from session 16's own next-steps list is still open and unchanged (hash-margin
   constants still uncalibrated — one real data point this session, margin=6, still below
   threshold=12; the publish-diff behavior confirmation). Not re-summarized here.

**Session paused 2026-09-23, resuming next session**: Skyler hit Gemini's rate limit mid-session
(after today's earlier `503` overload run — different failure mode, `429` this time) and can't do
step 2 (another live scan) right now. Step 1 (run the tests himself) is independent of Gemini and
doesn't need to wait.

No open policy questions from this session.
