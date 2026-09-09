# Feature Spec: Scanner Close-Range Focus via Physical Ultrawide Lens Selection

**Author:** Planner (dev-team-pipeline)
**Date:** 2026-09-04
**Status:** Approved (Skyler go-ahead, 2026-09-04) — ready for Coder
**Target ship:** This pipeline run

**Source spec (read in full, authoritative for the ADR/Decision/Requirements):**
`docs/specs/2026-09-02-scanner-macro-focus.md`. This document translates that spec's already-approved
Decision and Tasks 1-6 into an exact technical implementation plan grounded in the CURRENT state of
`ScannerScreen.kt` (post-attempt-#5, uncommitted in the working tree) and in direct verification of the
real CameraX 1.6.1 API surface. **Task 0 (feasibility spike) is already built and confirmed working
on-device — not re-planned here.**

**Model tier used for this planning pass:** default (not Opus). Per this pipeline's Item J
model-tiering rule: the source spec's own ADR already resolved the one genuinely open architectural
fork (ultrawide physical-lens selection vs. the three rejected alternatives) before this pass started,
and Task 0's spike already resolved the one open empirical unknown (device feasibility). What remained
for this pass was grounding + API-signature verification against the actual pinned library version —
empirical/verification work, not open-ended design judgment. One correction below (`Camera2CameraFilter`
doesn't exist in this project's pinned CameraX 1.6.1) was found by directly listing classes in the real
`camera-camera2-1.6.1.aar`'s `classes.jar`, not by reasoning about it — the kind of check any tier can
do reliably. If the Coder hits a genuinely ambiguous design fork this plan didn't anticipate, escalate
that specific decision, not the whole task, to Opus.

---

## TL;DR

Bind `ScannerScreen.kt`'s `CameraPreview` to the S26 Ultra's ultrawide physical camera (5cm min focus,
confirmed via Task 0's spike) instead of the fixed `CameraSelector.DEFAULT_BACK_CAMERA` (main lens,
~18-20cm min focus), with a clean fallback to today's behavior on devices that don't support
physical-camera selection. Also crop the captured photo to the on-screen guide-frame rect before it
reaches `GeminiCardScanner`/`PerceptualHasher`, closing the second, independent gap the "wrong card"
symptom traces to. Together these are attempt #6 in this file's camera-path bug family, but the first
one addressing the actual root cause (focus distance) rather than trading it off against recognition
accuracy (attempt #4) or fixing an unrelated gating race (attempt #5).

---

## Problem

Skyler's real-device use of the scanner ("keeps giving the wrong card") traces to two compounding,
now-fully-diagnosed gaps in `ScannerScreen.kt`'s camera path:

1. The main lens's ~18-20cm minimum focus distance forced `GUIDE_FRAME_WIDTH_RATIO` down to 0.32
   (attempt #4) to keep the guide frame reachable without going below that floor — but shrinking the
   guide frame also shrinks the card's actual footprint (and thus detail) in every captured frame,
   directly hurting both `GeminiCardScanner.scan()`'s OCR and `PerceptualHasher`'s image-matching.
2. The captured JPEG/YUV frame handed to both matchers is the **full preview frame**, never cropped to
   the guide box — so even a user who fills the frame reasonably well still sends surrounding
   background pixels into both matchers.

Task 0's spike (this same session, real S26 Ultra) confirmed the ultrawide physical camera (id=2,
2.2mm focal length) is cleanly selectable via CameraX and reports a genuine 5cm hardware minimum focus
distance — more than enough margin to let the guide frame be re-tuned back up toward its original
0.75 fill without violating any focus floor, and to make the crop (item 2) actually matter once the
frame is filled with real card detail again.

### Evidence

- Real logcat, Skyler's S26 Ultra, Task 0 spike (2026-09-04): `isLogicalMultiCameraSupported=true`,
  4 physical cameras enumerated; id=2 focalLengths=[2.2] minFocusDistance=20.0 (diopters → 5cm),
  matching `<20mm focal length` ultrawide-candidate filter. Full detail: source spec's "Open
  Questions" section, `gaps.md` 2026-09-04.
- Skyler, live: "it keeps giving the wrong card" — reported across 7 consecutive scans even after
  attempt #5 (a real, separately-confirmed fix to a THROWN-metering-future bug) shipped, isolating
  the remaining symptom to this crop/distance gap specifically, not the metering-gate bug attempt #5
  fixed.
- `docs/specs/2026-08-27-card-grading-estimate.md` Open Question #4 (prior research): Samsung's own
  stock camera app solves this identical problem by silently switching to the ultrawide ("Focus
  Enhancer") inside ~28-30cm — this spec reuses that same mechanism rather than inventing a new one.

---

## Users

**Primary user:** Skyler, scanning physical Pokémon TCG cards into the app via QuickScan/Pokédex
capture or manual capture. Personal project — no other users, no metrics infrastructure.

**Use cases:**
1. Skyler opens the Pokédex binder, taps an empty slot, and scans a physical card to auto-fill it.
2. Skyler uses the manual "Capture" button when auto-capture doesn't trigger cleanly.
3. Skyler scans a card destined for Card History (global scan, no specific slot).

**Secondary users:** None.
**Anti-users:** None — scanner-wide fix, not gated behind any parked feature (confirmed with Skyler,
2026-08-29 AskUserQuestion).

---

## Proposal

Two independent, parallelizable changes to the shared `CameraPreview`/capture path in
`ScannerScreen.kt`:

1. **Lens selection**: before binding the camera, query available `CameraInfo`s for a physical
   ultrawide camera on the logical back camera (focal length < 20mm, matching Task 0's spike logic).
   If found, bind directly to that physical camera via `CameraSelector.Builder().setPhysicalCameraId(...)`.
   If not found, or if the bind itself throws, fall back to today's `DEFAULT_BACK_CAMERA` behavior —
   first device-capability branch in a file that has always used one fixed selector.
2. **Crop before matching**: compute the on-screen guide-frame rect in the captured image's own
   pixel space (reusing the same rotation-aware mapping `detectCardInFrame` already has) and crop the
   decoded bitmap to it before `ScannerViewModel.processImage` hands it to `GeminiCardScanner.scan()`
   / `PerceptualHasher.findBestMatch()`.

A third, dependent step re-derives `GUIDE_FRAME_WIDTH_RATIO` and its on-screen distance text once (1)
is live and a real calibration photo confirms the ultrawide's actual sharp-focus working distance —
the 5cm figure is a hardware minimum-focus floor, not automatically the ideal framing distance.

### How it works (high level)

1. Camera binds → app silently checks whether this device exposes an ultrawide physical camera.
2. If yes, the preview (and captured photos) come from the ultrawide lens — no visible UI change,
   just closer usable focus.
3. If no, behavior is pixel-for-pixel identical to today (main lens, current guide frame ratio,
   current distance text) until Task 5's calibration lands.
4. On capture, the photo sent to card recognition is cropped to the guide box the user was aiming at,
   not the full frame.

---

## User stories

- As Skyler, I want the scanner to focus sharply when I hold a card close (matching how the stock
  camera app already behaves), so that captured photos are usable for recognition.
- As Skyler, I want the recognized card to actually be the card I scanned, so that I don't have to
  manually correct wrong auto-fills.
- As Skyler, I want this to work automatically on my real device, so I don't have to change how I use
  the scanner.
- As Skyler, I want a device without ultrawide physical-camera support to keep working exactly as it
  does today, so a future phone swap doesn't regress the scanner.

---

## Acceptance criteria

- [ ] **Given** a device that supports `CameraInfo.isLogicalMultiCameraSupported()` and exposes a
  physical camera with focal length < 20mm on the back logical camera, **when** `CameraPreview` binds,
  **then** the bound camera is that physical (ultrawide) camera, confirmed via the extended
  diagnostic log (`boundVia=ultrawide-physical`).
- [ ] **Given** a device that does NOT support logical multi-camera or has no qualifying physical
  camera, **when** `CameraPreview` binds, **then** behavior is identical to today
  (`CameraSelector.DEFAULT_BACK_CAMERA`, confirmed via `boundVia=default-back-camera` in the log) —
  zero regression for such devices.
- [ ] **Given** the ultrawide selector is built successfully but `bindToLifecycle` itself throws
  (per `CameraSelector.Builder.setPhysicalCameraId`'s own documented `IllegalArgumentException` risk),
  **when** that happens, **then** the code retries with `DEFAULT_BACK_CAMERA` in the same bind attempt
  — no crash, no blank preview.
- [ ] **Given** a successful capture (auto or manual), **when** the bitmap reaches
  `ScannerViewModel.processImage`, **then** it has been cropped to the guide-frame rect (plus the
  existing geometry, no separate margin unless the Coder finds one is needed) before
  `GeminiCardScanner.scan()`/`PerceptualHasher.findBestMatch()` see it.
- [ ] **Given** the crop-rect computation produces a degenerate (zero-size or out-of-bounds) rect,
  **when** that happens, **then** the code falls back to the uncropped full bitmap rather than
  crashing capture (mirrors `detectCardInFrame`'s own existing degenerate-frame guard).
- [ ] **Given** a real calibration photo taken at the ultrawide's confirmed sharp-focus working
  distance, **when** Task 5 runs, **then** `GUIDE_FRAME_WIDTH_RATIO` and the on-screen "Hold card ~X
  in / Y cm back" text are re-derived from that photo, not guessed or blind-reverted to the old 0.75.
- [ ] **Given** the full existing focus-lock/auto-capture-timing/false-positive-detection mechanisms
  (attempts #1-5), **when** this feature ships, **then** none of that logic is altered except where a
  task explicitly requires it (camera-selector plumbing only) — `requestFocusAndMetering`,
  `triggerFocus`, `detectCardInFrame`'s contrast heuristic, and the auto-capture hold-timer are
  untouched.
- [ ] `assembleDebug --rerun-tasks` and `lintDebug --rerun-tasks` both clean (0 new warnings/errors).
- [ ] All new logic covered by JVM unit tests (Task 6) — mocked `ProcessCameraProvider`/`CameraInfo`
  for selector-fallback branches, pure-function tests for crop-rect math.

---

## Out of scope

- **Card-grading feature** — explicitly excluded from this pass (per the orchestrating agent's own
  scope statement); this fix is scanner-wide but not gated behind or extended to grading.
- **Vendor-specific Camera2 extensions** (Samsung's own "Focus Enhancer" SDK) — rejected in the source
  spec's ADR (Option D), no public third-party API exists.
- **UI toggle to manually pick a camera lens** — not requested; the selection is fully automatic with
  a silent fallback, matching how the stock camera app behaves (no user-facing lens picker).
- **Digital zoom/crop-only fix on the main lens** — rejected in the source ADR (Option C); doesn't
  change focus distance, only crops an already-blurry image tighter.
- **Ultrawide resolution/noise trade-off deep dive** — flagged as an open question below, not blocking
  this pass; needs a real side-by-side comparison Skyler can do once this ships.
- **Broad multi-device compatibility testing** — only the S26 Ultra is confirmed; the fallback path is
  the only guarantee for other devices, not a promise every device gets the ultrawide behavior.

---

## Dependencies

### Required before development can start
- [x] ADR/architecture decision approved (source spec, 2026-09-04 go-ahead).
- [x] Feasibility confirmed on real hardware (Task 0 spike).
- [ ] None — ready to start.

### External systems or services
- None. No new dependencies — reuses `androidx.camera.camera2.interop` (`ExperimentalCamera2Interop`),
  already a dependency and already used in this exact file for the diagnostic logging functions.

### Team dependencies
- **Skyler's real S26 Ultra**: required for Task 5's calibration photo (working distance can't be
  derived from the hardware-reported 5cm minimum alone — that's a floor, not necessarily the sharp
  point) and for final real-device confirmation of Tasks 1-4. No emulator substitute exists for camera
  AF/lens-selection behavior (standing constraint, `project-overview.md`'s Test Infrastructure
  section).

---

## Success metric

**Primary metric:** Correct-card rate on a real-device test batch immediately after this ships.

**Current baseline:** Qualitatively ~0/7 correct on the last reported real-device batch (post-attempt
#5, pre-this-fix) — no formal count kept, Skyler's own report ("keeps giving the wrong card").

**Target:** ≥9/10 correct matches on a fresh 10-scan real-device batch across a mix of card types
(matches the project's existing informal verification style — no metrics infra, personal project).

**Measurement window:** Immediately after Skyler installs the build; re-test window open-ended (retest
whenever Skyler next uses the scanner).

**Secondary metrics (signals to watch):**
- `adb logcat -s ScannerFocus:*` showing `boundVia=ultrawide-physical` on Skyler's device — confirms
  the selection path actually engaged, not just that recognition improved for unrelated reasons.
- No new `IllegalArgumentException`/crash reports from the physical-camera bind path.

**Negative signals (what would tell us this hurt):**
- Recognition accuracy gets WORSE after the ultrawide switch (would point at the resolution/noise
  trade-off flagged as an open question) — if seen, the fallback path already provides an escape
  hatch (temporarily force `DEFAULT_BACK_CAMERA` by having `selectUltrawideCameraSelector` return
  null, a one-line debug change, not a full revert).
- Any crash on camera bind on Skyler's device — would mean the `IllegalArgumentException` fallback
  path (Task 2) has a gap.

---

## Estimated effort

**Size:** Medium (single scanner-focused pipeline run, no new modules/dependencies)

**Breakdown:**
- Task 1 (selector function): M
- Task 2 (bind wiring + fallback): S
- Task 3 (extended logging): S
- Task 4 (crop-before-match): M
- Task 5 (calibration, human-in-the-loop): S (Coder work) + real-device time (Skyler)
- Task 6 (unit tests): M
- Total: ~1-2 focused sessions, most of it Task 1/4/6.

---

## Priority

Framework: **MoSCoW**, appropriate here since this is prioritizing requirements *within one already-
scoped spec* (per the planning skill's own framework table), not comparing this feature against other
initiatives.

- **Must (P0):** Tasks 1, 2, 4 (lens selection + fallback + crop) — these are the actual fix for the
  reported "wrong card" bug. Task 5's ratio re-derivation is also Must, since a filled-but-uncropped-
  correctly guide frame is only half the fix.
- **Should (P1):** Task 3 (extended logging) — makes future "still wrong" reports diagnosable, doesn't
  itself fix anything.
- **Could (P2):** Explicit UI messaging for the "no ultrawide, fell back" case beyond the existing
  silent-fallback behavior — today's 23cm-workaround UI is already an acceptable degraded experience;
  only needs confirming it isn't newly broken by this refactor (covered by Task 6's fallback tests),
  not new UI work.

**This spec:** P0 (all Must-tier tasks required for the fix to actually close the reported bug).

**Reasoning:** Skyler explicitly redirected an earlier investigation toward this exact fix (handoff.md
§5), gave direct go-ahead this session after the blocking feasibility spike resolved clean, and this
is the last unaddressed piece of a bug family already 5 attempts deep.

---

## Open questions

- [ ] **Ultrawide resolution/noise trade-off** — does the lower-megapixel ultrawide sensor hurt
  Gemini/perceptual-hash accuracy enough to offset the focus win? **Owner:** Skyler (real-device
  side-by-side comparison). **Decision needed by:** after this ships, informs whether the fallback
  escape hatch (see Success Metric) needs to become permanent for this device.
- [ ] **Does `CameraInfo.getPhysicalCameraInfos()` behave identically when queried on a NOT-yet-bound
  `CameraInfo`** (from `provider.availableCameraInfos`, the pre-bind pattern this plan uses for
  `selectUltrawideCameraSelector`) **vs. the already-bound case Task 0's spike proved works?**
  Task 0 only confirmed the post-bind case. **Owner:** Coder, via a one-line log comparison during
  Task 1 implementation, before assuming the pre-bind path behaves the same. **Decision needed by:**
  Task 1 implementation — if it does NOT behave the same (empty/throws), Task 1 must restructure to
  a two-phase bind (bind `DEFAULT_BACK_CAMERA` first solely to query `getPhysicalCameraInfos()`,
  unbind, rebind with the real selector) instead of the single-bind design below — see Task 1's Edge
  Cases.
- [ ] **Does every device Skyler might use in the future (not just the S26 Ultra) support
  `isLogicalMultiCameraSupported()`?** **Owner:** N/A until a device change happens — the fallback
  path is the answer, just needs to keep being genuinely acceptable, not merely non-crashing.
- [ ] **Format of the captured `ImageProxy` in `ImageCapture.OnImageCapturedCallback`** — confirmed via
  direct CameraX source read that `ImageCapture`'s default `INPUT_FORMAT` selection favors JPEG when
  supported, which would normally mean a single-plane JPEG-format `ImageProxy` — yet the *existing*
  `ImageProxyExt.kt.toBitmap()` (predates this spec, already shipped) does 3-plane NV21/YUV
  reconstruction (`planes[2]`), which would throw on a genuinely single-plane JPEG image. Since
  capture is evidently working in production today (Gemini/PerceptualHasher receive decodable bitmaps,
  no crash reports), the actual runtime format must already be YUV_420_888 for this specific
  `ImageCapture.Builder()` config — but this was inferred from absence-of-crash-reports, not directly
  confirmed. **Owner:** Coder, one-line log (`Log.i("ScannerFocus", "captured format=${imageProxy.format} planes=${imageProxy.planes.size}")`)
  at the top of `toBitmap()` during Task 4, purely informational — does **not** block Task 4's crop
  design, since the crop operates on the already-decoded `Bitmap` regardless of which path decoded it.
  **Decision needed by:** informational only, log and move on; only investigate further if it reveals
  the existing decode path is actually broken (separate bug, out of scope for this spec).

---

## Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `setPhysicalCameraId` bind throws `IllegalArgumentException` despite `isLogicalMultiCameraSupported()==true` (documented CameraX behavior — no guarantee API exists) | Low (Task 0 spike found clean 4-camera enumeration on real hardware) | Medium (blank preview if unhandled) | Task 2's explicit catch-and-retry-with-`DEFAULT_BACK_CAMERA` on that exact bind call |
| Pre-bind `getPhysicalCameraInfos()` query behaves differently than Task 0's proven post-bind case | Medium (genuinely unverified — see Open Questions) | Low (selector build fails gracefully, falls back) | `selectUltrawideCameraSelector` wraps its whole body in `runCatching`, returns null on any failure — same fallback path as "no ultrawide found" |
| Ultrawide's lower resolution hurts matcher accuracy more than closer focus helps | Medium (flagged, unconfirmed) | Medium (could trade one accuracy problem for another) | Fallback escape hatch (force selector to return null) is a one-line change, not a revert; real side-by-side comparison is an explicit open question, not silently assumed fine |
| Crop-rect math has an off-by-one or rotation-mapping bug (4-corner remap is new code, not reused verbatim from `detectCardInFrame`) | Low-Medium | Medium (wrong crop = same "background pixels" problem this fix targets) | Task 6's rect-math unit tests cover all 4 `rotationDegrees` cases (0/90/180/270) against known input/output pairs; degenerate-rect fallback prevents a crash even if the math is wrong |
| Task 5's calibration needs Skyler's real phone — can't be fully closed by an automated Coder | High (structural) | Low (doesn't block Tasks 1-4/6 shipping first) | Task 5 explicitly flagged as human-in-the-loop; Tasks 1-4/6 are independently shippable and verifiable first |

---

## Smallest shippable increment

**MVP:** Tasks 1 + 2 + 4 (lens selection + fallback + crop), with Task 5's ratio left at today's 0.32
value temporarily (still correct-ish since 0.32 was derived for a *worse* focus floor than the
ultrawide's actual 5cm — filling less of the frame than necessary, but not violating any new
constraint). This alone should measurably improve recognition (closer sharp focus + correctly-cropped
frame) even before the ratio is re-tuned.

**Future iterations** (still within this spec, just sequenced after MVP):
- Task 3 (extended logging) — cheap, do alongside Task 2, no reason to defer.
- Task 5 (real calibration + ratio re-derivation) — needs Skyler's phone, do once Tasks 1/2/4 are
  confirmed working.
- Task 6 (tests) — do last per the source spec's own phase rollout, but don't skip; this file's bug
  history (5 prior attempts) is itself the argument for real regression coverage this time.

---

## Changelog

| Date | Author | Change |
|---|---|---|
| 2026-09-04 | Planner (dev-team-pipeline) | Initial spec — translates approved source-spec Tasks 1-6 into exact file/function-level implementation plan |

---

# Technical Implementation Plan (Phase 4 — for the Coder stage)

All file paths relative to `D:\Claude Projects\PokedexBinderV2\`. Package for all touched production
files: `com.skyler.pokedexbinder.ui.scanner`.

## Grounding notes (verified this pass, not assumed)

- **`Camera2CameraFilter` does not exist in this project's pinned CameraX 1.6.1.** The source spec's
  Decision/Task 1 description names it as the selection mechanism — this was checked directly (not
  guessed) by listing every class in the real `camera-camera2-1.6.1.aar`'s `classes.jar`
  (`androidx.camera.camera2.interop` package contains only `Camera2CameraControl`, `Camera2CameraInfo`,
  `Camera2CaptureRequestConfigurator`, `Camera2Interop`, `CaptureRequestOptions`,
  `ExperimentalCamera2Interop` — no `Camera2CameraFilter`). **Correct mechanism for this CameraX
  version, confirmed directly in `CameraSelector.java`'s own source (not experimental, no `@OptIn`
  needed for this specific API — it's on the stable `androidx.camera.core` surface, distinct from the
  camera2-interop experimental surface used elsewhere in this file):**
  `CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).setPhysicalCameraId(id).build()`.
  Physical camera discovery still needs `Camera2CameraInfo.from()` (experimental, already used/opted
  into in this file) to read `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` per physical camera.
- **Pre-bind camera enumeration is a documented, supported CameraX pattern** — `CameraSelector.java`'s
  own javadoc for `filter()` shows exactly this: `selector.filter(cameraProvider.getAvailableCameraInfos())`,
  called before any `bindToLifecycle`. This is what Task 1 uses instead of Task 0's post-bind
  discovery — flagged as an open question above since it's a reasonable extrapolation, not a directly
  proven-on-device case like Task 0's post-bind query was.
- **`ImageCapture`'s captured-image `imageInfo.rotationDegrees` uses identical semantics** to the
  analysis-stream frames `detectCardInFrame` already handles (confirmed via `ImageCapture.java`'s own
  javadoc: "rotationDegrees is the rotation to apply to the image to match the display orientation") —
  Task 4's crop-rect math can directly reuse `detectCardInFrame`'s rotation-mapping approach.
- **Only one call site exists for `ImageProxy.toBitmap()`** (`ScannerViewModel.processImage`, line 69)
  — confirmed via project-wide grep. Safe to change its signature without hunting for other callers.
- **No existing test files for `ImageProxyExt.kt` or `ScannerViewModel.kt`** — Task 6 is genuinely
  first coverage for both, not an extension of existing tests. Existing JVM test convention for this
  codebase (confirmed via `PerceptualHasherTest.kt`): `mockk`, `mockkStatic(Bitmap::class)` for
  Android-graphics classes needing behavior (not just relaxed defaults), tests live under
  `app/src/test/java/com/skyler/pokedexbinder/ui/...` mirroring the production package.

---

## Task 1 — `selectUltrawideCameraSelector`

**Type:** CORE · **Size:** M · **Depends on:** Task 0 (done)

**File:** `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt` — new private
top-level function, placed near `logUltrawideFeasibility` (same "Camera focus" section of the file).

**Signature:**
```kotlin
private const val ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 20f  // matches Task 0 spike's own filter

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun selectUltrawideCameraSelector(provider: ProcessCameraProvider): CameraSelector?
```

**Implementation approach:**
1. `val backCameraInfos = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)`
   — pre-bind, per `CameraSelector.filter()`'s own documented pattern.
2. `val logicalInfo = backCameraInfos.firstOrNull() ?: return null`.
3. `if (!logicalInfo.isLogicalMultiCameraSupported()) return null`.
4. For each `physicalInfo` in `logicalInfo.getPhysicalCameraInfos()`: wrap in its own `runCatching`,
   read `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` via `Camera2CameraInfo.from(physicalInfo)`, check
   `focalLengths?.any { it < ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM } == true`, capture the qualifying
   physical camera's id (`camera2Info.cameraId`).
5. If no qualifying id found, return null.
6. Otherwise: `CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).setPhysicalCameraId(id).build()`.
7. Wrap the ENTIRE function body in one outer `runCatching { ... }.onFailure { Log.w(...) }.getOrNull()`
   — any unexpected exception anywhere in this discovery chain degrades to "no ultrawide found," never
   propagates.

**Edge cases (must be explicitly handled, not just theoretically safe):**
- `provider.availableCameraInfos` empty, or no camera matches `DEFAULT_BACK_CAMERA`'s filter → null.
- `isLogicalMultiCameraSupported()` false (confirmed non-Samsung/older-device path) → null.
- `getPhysicalCameraInfos()` empty even though multi-camera is "supported" → null (loop over empty
  list naturally yields no match).
- A per-physical-camera characteristic read throws (matches `logUltrawideFeasibility`'s own existing
  per-camera `runCatching` pattern) → that one candidate is skipped, others still considered.
- Multiple physical cameras satisfy the focal-length filter (not expected on the real device — id=2 is
  the only one under 20mm per Task 0's data — but don't assume single-match structurally): take the
  first match deterministically (`firstNotNullOfOrNull` over the physical-camera list, not `filter` +
  arbitrary pick).
- **Pre-bind query behavior is unverified** (see Open Questions) — if `getPhysicalCameraInfos()`
  returns empty or throws when called on a not-yet-bound `CameraInfo` (unlike Task 0's proven
  post-bind case), this function still returns null safely and the fallback path (Task 2) engages —
  no crash either way, just possibly "always falls back" until this is confirmed. **If real-device
  testing (Task 5's calibration pass) shows the ultrawide is NEVER selected despite Task 0's spike
  proving the device supports it, this pre-bind assumption is the first thing to check** — restructure
  to a two-phase bind (temporarily bind `DEFAULT_BACK_CAMERA`, query `camera.cameraInfo.getPhysicalCameraInfos()`
  exactly as Task 0 did, `provider.unbindAll()`, then do the real bind with the discovered id) instead.

---

## Task 2 — Wire into the bind call

**Type:** CORE · **Size:** S · **Depends on:** Task 1

**File:** same, inside `CameraPreview`'s `AndroidView` factory, replacing the existing bind block
(current lines ~461-468):

```kotlin
provider.unbindAll()
val ultrawideSelector = selectUltrawideCameraSelector(provider)
var boundVia = "default-back-camera"
camera = if (ultrawideSelector != null) {
    runCatching {
        val c = provider.bindToLifecycle(lifecycleOwner, ultrawideSelector, preview, capture, analysis)
        boundVia = "ultrawide-physical"
        c
    }.getOrElse {
        Log.w("ScannerFocus", "ultrawide physical-camera bind threw, falling back to default back camera", it)
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
    }
} else {
    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
}
bindCompletedAtMs = System.currentTimeMillis()
camera?.let { logCameraAfCapabilities(it, boundVia); logUltrawideFeasibility(it) }
```

**Design constraint (surgical, minimal blast radius):** only the ultrawide bind attempt is wrapped in
`runCatching` — both the "no ultrawide found" path and the "ultrawide bind threw, now falling back"
path call the plain, unwrapped `provider.bindToLifecycle(..., DEFAULT_BACK_CAMERA, ...)`, identical to
today's code. If that call itself throws (e.g., genuinely no back camera on the device at all), it
throws exactly as it does today — this task does not introduce new blanket exception-handling for a
failure mode that was already unhandled before this change, per this project's "surgical changes only"
convention.

**Edge cases:**
- `boundVia` must reflect the ACTUAL bound camera, not the attempted one — only set to
  `"ultrawide-physical"` after `bindToLifecycle` returns successfully, inside the `runCatching` block
  that also produces `camera`.
- The retry-with-fallback path re-attempts the same `unbindAll()`-then-bind sequence but does NOT
  re-run `provider.unbindAll()` a second time before the fallback bind — a failed `bindToLifecycle`
  call doesn't leave a partial binding needing a second unbind (CameraX contract: bind either
  succeeds atomically or throws without partially binding). Confirm this assumption holds during
  testing; if a failed bind DOES leave a partial state, add a second `unbindAll()` before the fallback
  call.

---

## Task 3 — Extend diagnostic logging

**Type:** CORE · **Size:** S · **Depends on:** Task 2

**File:** same. Modify `logCameraAfCapabilities`'s signature:

```kotlin
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun logCameraAfCapabilities(camera: Camera, boundVia: String) {
    runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        ...
        Log.i(
            "ScannerFocus",
            "camera id=${info.cameraId} boundVia=$boundVia minFocusDistance=$chars ..."
        )
    }...
}
```

Single new field (`boundVia`) inserted into the existing log line — no new function needed, per the
source spec's own suggestion ("Extend `logCameraAfCapabilities` (or a sibling function)").

**Edge cases:**
- Must fire on BOTH the ultrawide-success and fallback-success paths (already guaranteed by Task 2's
  `camera?.let { logCameraAfCapabilities(it, boundVia); ... }` being outside the if/else) — a future
  "still wrong" report must be able to distinguish "ultrawide bound but still inaccurate" from "fell
  back to main lens, capability check failed," per the source spec's P1 requirement wording verbatim.

**Coder's call (explicitly deferred by the source spec, not resolved here):** `logUltrawideFeasibility`
(Task 0's diagnostic) now logs largely-overlapping information to Task 3's extended
`logCameraAfCapabilities`. Recommended (not mandated): fold its unique value (per-physical-camera
focal-length/min-focus enumeration) into `logCameraAfCapabilities` itself once Task 3 lands, then
delete `logUltrawideFeasibility` — avoids two log-producing functions covering overlapping ground.
Leaving it in place is also acceptable; it's diagnostic-only, zero production behavior risk either way.

---

## Task 4 — Crop captured bitmap to guide-frame rect

**Type:** CORE · **Size:** M · **Depends on:** none (independent of Tasks 1-3, can be built in
parallel)

**Files:**
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt` — new shared geometry
  function (extracted so the crop and `detectCardInFrame` can't drift apart, same lesson this file
  already learned once with `GUIDE_FRAME_WIDTH_RATIO`).
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt` — the actual crop.
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt` — one call-site change.

**New shared type + function (`ScannerScreen.kt`, internal visibility — same package as
`ImageProxyExt.kt`, no need to make it public):**

```kotlin
internal data class ImageRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

// Computes the guide-frame rect in raw image-space (pre-rotation) pixel coordinates, for an image
// of the given raw width/height and CameraX rotationDegrees. Shared by detectCardInFrame's live
// analysis-space sampling and ImageProxyExt's post-capture crop — both must agree on the same guide
// geometry CardFrameOverlay draws, or a captured photo's crop won't match what the user aimed at.
internal fun guideFrameImageRect(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): ImageRect {
    val isRotated = rotationDegrees == 90 || rotationDegrees == 270
    val dispW = if (isRotated) rawHeight else rawWidth
    val dispH = if (isRotated) rawWidth else rawHeight

    val guideW = (dispW * GUIDE_FRAME_WIDTH_RATIO).toInt()
    val guideH = (guideW * 88f / 63f).toInt()
    val dLeft = (dispW - guideW) / 2
    val dTop = (dispH - guideH) / 2
    val dRight = dLeft + guideW
    val dBot = dTop + guideH

    fun toImage(dx: Int, dy: Int): Pair<Int, Int> = when (rotationDegrees) {
        90  -> Pair(dy, rawHeight - 1 - dx)
        270 -> Pair(rawWidth - 1 - dy, dx)
        180 -> Pair(rawWidth - 1 - dx, rawHeight - 1 - dy)
        else -> Pair(dx, dy)
    }

    // Map all 4 corners, not just top-left/bottom-right — a 90/270 rotation swaps which raw-image
    // axis corresponds to display-width vs -height, so the mapped rect must be re-normalized
    // (min/max over all 4 mapped corners) rather than assuming corner order survives the rotation.
    val corners = listOf(toImage(dLeft, dTop), toImage(dRight, dTop), toImage(dLeft, dBot), toImage(dRight, dBot))
    val left = corners.minOf { it.first }.coerceIn(0, rawWidth - 1)
    val top = corners.minOf { it.second }.coerceIn(0, rawHeight - 1)
    val right = corners.maxOf { it.first }.coerceIn(0, rawWidth - 1)
    val bottom = corners.maxOf { it.second }.coerceIn(0, rawHeight - 1)
    return ImageRect(left, top, right, bottom)
}
```

**Recommended (not mandatory) cleanup within this same task:** refactor `detectCardInFrame` to call
`guideFrameImageRect` for its own `dLeft/dTop/dRight/dBot` computation instead of hand-duplicating the
geometry a third time — the function's contrast-sampling logic stays untouched, only the rect
computation is shared. Skip if it risks destabilizing `detectCardInFrame`'s carefully-tuned behavior
(attempts #1-5 all touched this file's timing/detection logic) — the crop works correctly either way
since `guideFrameImageRect` is self-contained.

**`ImageProxyExt.kt` change:**

```kotlin
fun ImageProxy.toBitmap(cropToGuideFrame: Boolean = false): Bitmap {
    // ... existing NV21 decode, unchanged ...
    val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (!cropToGuideFrame) return full
    val rect = guideFrameImageRect(width, height, imageInfo.rotationDegrees)
    if (rect.width <= 0 || rect.height <= 0) {
        Log.w("ScannerFocus", "degenerate crop rect ($rect) for ${width}x$height rot=${imageInfo.rotationDegrees}, using uncropped")
        return full
    }
    return runCatching {
        Bitmap.createBitmap(full, rect.left, rect.top, rect.width, rect.height)
    }.getOrElse {
        Log.w("ScannerFocus", "crop failed, using uncropped", it)
        full
    }
}
```

Default `cropToGuideFrame = false` preserves the existing signature/behavior for any future caller
that doesn't explicitly opt in — but per this project's "explicit over implicit" convention, the one
real call site should NOT rely on the default.

**`ScannerViewModel.kt` change (one line, `processImage`, line 69):**
```kotlin
val bitmap = imageProxy.toBitmap(cropToGuideFrame = true)
```

**Edge cases:**
- Degenerate rect (zero/negative width or height) → falls back to full uncropped bitmap, logged, not
  a crash (mirrors `detectCardInFrame`'s own `if (guideH >= dispH) return false` guard, same file,
  same defensive pattern already established).
- `Bitmap.createBitmap(full, left, top, width, height)` throwing (e.g., `IllegalArgumentException` if
  the rect is out of the source bitmap's bounds due to an off-by-one, or `OutOfMemoryError` on very
  large sensor images) → caught, falls back to uncropped, logged — capture must never hard-fail here.
- **Format ambiguity noted in Open Questions** does not block this task — the crop operates on
  whatever `Bitmap` the existing (pre-this-spec, already-shipped) decode path produces, regardless of
  whether that decode path's own YUV-vs-JPEG assumption is itself correct. Add the one informational
  log line (`captured format=... planes=...`) at the top of `toBitmap()` as a cheap diagnostic, but
  do not gate this task's completion on investigating it further.
- Rotation values other than 0/90/180/270 are not expected from CameraX (`rotationDegrees` is always
  one of these four) — the `when` clause's `else -> Pair(dx, dy)` branch (matching `detectCardInFrame`'s
  existing pattern) handles 0 and any unexpected value identically (no-op mapping), consistent with
  existing code, not a new gap introduced by this task.

---

## Task 5 — Real-device calibration pass

**Type:** TEST/CORE · **Size:** S (Coder work) — genuinely blocked on Skyler's real device for the
input data · **Depends on:** Tasks 1, 2, 4

**File:** `ScannerScreen.kt` — `GUIDE_FRAME_WIDTH_RATIO` constant + its derivation comment, and
`CardFrameOverlay`'s "Hold card ~9 in (23 cm) back" text.

**Process:**
1. Once Tasks 1/2/4 are confirmed shipping (ultrawide bound, crop working), Skyler takes a real
   calibration photo at the ultrawide's confirmed sharp-focus working distance (not just the 5cm
   hardware minimum — that's a floor, the sharp point may be slightly further out, same distinction
   the original 0.32 derivation already respected for the main lens).
2. Coder re-derives `GUIDE_FRAME_WIDTH_RATIO` from that photo using the same method as the existing
   derivation comment documents (card's actual fraction of frame width at the confirmed distance,
   scaled to the target working distance) — not a blind revert to the pre-attempt-#4 `0.75f`.
3. Update the derivation comment (currently lines 51-57) to document the new photo/distance/math, same
   rigor as the existing comment.
4. Update the on-screen text (currently "Hold card ~9 in (23 cm) back") to match the new distance.

**Edge cases:**
- If Skyler's calibration photo shows the ultrawide's sharp point is meaningfully further than 5cm
  (plausible — hardware minimum ≠ optimal), the new ratio should target that confirmed distance, not
  assume 5cm is achievable in practice.
- Both `detectCardInFrame`'s analysis-space usage and `CardFrameOverlay`'s drawn UI already read the
  same `GUIDE_FRAME_WIDTH_RATIO` constant (fixed in attempt #4) — this task changes ONE constant, both
  consumers update automatically, no risk of the two drifting apart again.

---

## Task 6 — Unit tests

**Type:** TEST · **Size:** M · **Depends on:** Tasks 1-5

**New file:** `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt` (first test
file for this package — mirrors existing convention, e.g.
`app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt`, using `mockk`).

Since `selectUltrawideCameraSelector`, `guideFrameImageRect`, and the modified `logCameraAfCapabilities`
are all `private`/`internal` top-level functions in `ScannerScreen.kt` (not members of an exported
class), the test file must live in the same package (`com.skyler.pokedexbinder.ui.scanner`) to call
`internal` functions directly; genuinely `private` functions (`selectUltrawideCameraSelector`) need
either (a) a visibility bump to `internal` for testability — acceptable, matches how `guideFrameImageRect`
is already spec'd as `internal` above — or (b) testing indirectly through a thin `internal`-visible
wrapper. **Recommended: bump `selectUltrawideCameraSelector` to `internal` too**, consistent with
`guideFrameImageRect`, both justified the same way (testability without changing external API surface
— nothing outside this package needs either function).

**Test cases (selector logic, mocked `ProcessCameraProvider`/`CameraInfo` via `mockk`):**
1. No back camera in `availableCameraInfos` → returns null.
2. Back camera found, `isLogicalMultiCameraSupported()` false → returns null.
3. Multi-camera supported, but no physical camera has focal length < 20mm → returns null.
4. Multi-camera supported, exactly one physical camera qualifies (mirrors the real S26 Ultra's actual
   4-camera data: id=2 at 2.2mm qualifies, id=5/6/7 at 6.5/7.0/18.6mm don't) → returns a
   `CameraSelector` with `physicalCameraId == "2"`.
5. A physical camera's characteristic read throws (`Camera2CameraInfo.from` or
   `getCameraCharacteristic` throwing) → that candidate is skipped, doesn't crash the whole function;
   if a LATER candidate in the list qualifies, it's still found.
6. `provider.availableCameraInfos` itself throws → function returns null (outer `runCatching` catches
   it), doesn't propagate.

**Test cases (`guideFrameImageRect`, pure function, no mocking needed given `ImageRect` is a plain
data class):**
7. `rotationDegrees = 0` — rect centered correctly for a known width/height, matches hand-computed
   expected values.
8. `rotationDegrees = 90` — confirms the 4-corner remap produces a correctly axis-swapped rect (not
   just a naive top-left/bottom-right swap, which would be wrong).
9. `rotationDegrees = 270` — same, opposite direction.
10. `rotationDegrees = 180` — confirms mirrored-both-axes case.
11. A degenerate case (e.g. `guideH >= dispH` for extreme aspect ratios) — confirms the rect still
    comes back `coerceIn`-bounded rather than negative/out-of-range (the actual zero/negative-size
    detection and fallback lives in `ImageProxyExt.toBitmap`, tested separately below — this test just
    confirms `guideFrameImageRect` itself doesn't produce out-of-bounds coordinates).

**Test cases (`ImageProxyExt.toBitmap`'s crop path — new file
`app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExtTest.kt`, `mockkStatic(Bitmap::class)`
pattern from `PerceptualHasherTest.kt`):**
12. `cropToGuideFrame = false` (default) → behavior unchanged from before this spec (regression guard
    for the existing, already-shipped decode path).
13. `cropToGuideFrame = true` with a valid rect → `Bitmap.createBitmap` called with the expected
    left/top/width/height derived from a known `ImageProxy` width/height/rotation.
14. `cropToGuideFrame = true` with a degenerate rect (mock `guideFrameImageRect`'s inputs to produce
    one, or directly test via an extreme aspect ratio) → falls back to the uncropped bitmap, no crash.
15. `cropToGuideFrame = true`, `Bitmap.createBitmap` throws → falls back to uncropped bitmap, no crash
    propagates to the caller.

**Test cases (bind-fallback wiring, if feasible to isolate — `CameraPreview`'s bind logic is embedded
in a Compose `AndroidView` factory lambda, not currently an independently-testable function):**
16. **Coder's call, flagged not mandated**: if Task 2's bind-wiring logic (the `if (ultrawideSelector
    != null) { runCatching { ... } } else { ... }` block) is extracted into its own small
    internal-visibility function taking `(provider, lifecycleOwner, preview, capture, analysis,
    ultrawideSelector) -> Pair<Camera?, String>` purely for testability, add tests for: ultrawide bind
    succeeds (`boundVia == "ultrawide-physical"`), ultrawide bind throws then fallback succeeds
    (`boundVia == "default-back-camera"`, log warning fired), `ultrawideSelector == null` from the
    start (`boundVia == "default-back-camera"`, no warning about a throw). If extraction is judged not
    worth the diff size for this already-large task, this coverage gap is acceptable — the fallback
    behavior itself is simple/reviewable by inspection, unlike the crop-rect math which is genuinely
    error-prone geometry.

**Verification commands (per project convention, `.ps1` + `powershell.exe -NoProfile -ExecutionPolicy
Bypass -File`, `JAVA_HOME`/`TEMP`/`TMP` set inside the script):**
```
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat lintDebug --rerun-tasks
.\gradlew.bat assembleDebug --rerun-tasks
```
Delete `app/build/test-results/testDebugUnitTest/` before re-running if a self-reported test count
looks suspicious (existing project lesson, `project-overview.md` Conventions).

---

## Dependency graph / phase rollout

```
Task 0 (done) ──> Task 1 ──> Task 2 ──> Task 3
                                │
Task 4 (independent) ──────────┤
                                ▼
                             Task 5 (needs Skyler's phone)
                                │
                                ▼
                             Task 6 (tests, last)
```

Tasks 1-3 (lens selection) and Task 4 (cropping) can be implemented and reviewed in parallel — no
shared state, different functions/files. Task 5 needs both done first since it needs the real combined
on-device behavior to calibrate against. Task 6 last, covering everything.

---

## Friction Notes

- Serena's Kotlin language server failed to initialize for this project ("Error extracting archive")
  — `get_symbols_overview`/`find_symbol` etc. were unusable for the whole session. Fell back to
  Read/Grep/Glob for all code navigation instead, per the task's own guardrail allowing that fallback.
  Not something this stage can fix (project/environment config issue, not a planning-content issue).
- The source spec (`docs/specs/2026-09-02-scanner-macro-focus.md`) named `Camera2CameraFilter` as the
  selection mechanism, but that class doesn't exist in this project's pinned CameraX 1.6.1 — confirmed
  by extracting and listing the real `camera-camera2-1.6.1.aar`'s `classes.jar` contents directly
  (`androidx.camera.camera2.interop` has no such class). Worth flagging as a general lesson: even an
  already-researched, already-approved ADR can carry a real API-existence error if the original
  research didn't verify against the exact pinned library version — grepping a `-sources.jar`'s actual
  class list caught this in a few tool calls; reasoning from general CameraX knowledge alone would not
  have.
- `jar`/standard JDK archive tools aren't on PATH in this Bash environment; extracting a `-sources.jar`
  or `.aar` required copying it to a `.zip` extension first, then using
  `powershell.exe -Command "Expand-Archive"` (or .NET's `System.IO.Compression.ZipFile` directly for
  listing entries without full extraction, which was faster for a quick "does class X exist" check).
  Worth remembering as the working pattern for this machine rather than re-discovering it next time.
