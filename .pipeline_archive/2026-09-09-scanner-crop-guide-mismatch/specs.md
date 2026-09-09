# Feature Spec: Scanner crop-rect-vs-guide-box geometry fix

**Author:** Planner (dev-team-pipeline)
**Date:** 2026-09-09
**Status:** Draft
**Target ship:** This pipeline run (attempt #6/7 on this bug family)

---

## TL;DR

The scanner's capture crop is ~1.9x larger by area than the guide box drawn on screen, because
the crop math is computed against the full captured-frame dimensions while the on-screen guide box
is drawn against the `PreviewView`'s (`FILL_CENTER`-cropped) displayed dimensions — two different
denominators for the same `GUIDE_FRAME_WIDTH_RATIO` fraction. Fix: constrain preview/capture/
analysis to one shared field of view via a CameraX `UseCaseGroup` + `ViewPort`, then crop against
`ImageProxy.cropRect` (not the full frame) in `ImageProxyExt.kt`, repositioning the result into the
decoded bitmap's coordinate space. Two files touched, no UI/behavior change beyond tighter crop
accuracy; not verifiable against real hardware from this sandbox.

---

## Problem

`guideFrameImageRect(rawWidth, rawHeight, rotationDegrees)` (`ScannerScreen.kt`) sizes the guide
box as a fraction of the *captured ImageProxy's full raw width*. `CardFrameOverlay` draws the same
fraction against the *`PreviewView`'s own displayed width*. `CameraPreview` binds via the plain
`bindToLifecycle(lifecycleOwner, selector, preview, capture, analysis)` vararg overload (2 call
sites, `ScannerScreen.kt` ~676 and ~704) — no `UseCaseGroup`/`ViewPort`, so nothing constrains the
three streams to a shared field of view, and `PreviewView`'s default `FILL_CENTER` scaling
center-crops what it displays independently of what the capture stream delivers. Result: the photo
handed to `GeminiCardScanner`/`PerceptualHasher` includes materially more background than the box
the user aimed the card at — not positional (still concentric), but it undermines the crop
feature's whole purpose, the same root-cause family already fixed once this session for the
"wrong card" symptom (`docs/specs/2026-09-02-scanner-macro-focus.md` Task 4).

Root cause confirmed via decompiled CameraX 1.6.1 bytecode/sources this session (settled, not
re-derived here): `ImageProxy.getCropRect()` defaults to the full frame with no `ViewPort` on the
bind; `ImageProxy.toBitmap()` decodes the full JPEG with no crop applied; `PreviewView.getViewPort()`
returns a real `ViewPort` once laid out, null otherwise; `ProcessCameraProvider.bindToLifecycle
(LifecycleOwner, CameraSelector, UseCaseGroup)` exists on this pinned version — all via javap
against the real `1.6.1` AARs/sources, not guessed.

---

## Proposal

Two independent, additive fixes in the same two-file scope.

**1. Shared field of view (`ScannerScreen.kt`, `CameraPreview`'s `AndroidView` factory closure).**
Local helper builds a `UseCaseGroup` (with `previewView.viewPort` as the shared `ViewPort`) and
binds through the `UseCaseGroup` overload instead of the raw vararg one, at both bind call sites.
Falls back to today's plain vararg bind when `previewView.viewPort` is null (view not yet laid out
— same defensive shape as `requestFocusAndMetering`'s existing `previewView.width == 0` guard).

```kotlin
// Inside future.addListener's lambda, right after `val provider = future.get()`, so it closes
// over `provider` and the factory-level `lifecycleOwner`.
fun bindPreviewCaptureAnalysis(
    preview: Preview,
    capture: ImageCapture,
    analysis: ImageAnalysis
): Camera {
    val viewPort = previewView.viewPort
    return if (viewPort != null) {
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysis)
            .build()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
    } else {
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
    }
}
```

Both `camera = runCatching { provider.bindToLifecycle(...) }` call sites (~676 primary, ~704
physical-camera fallback) replace their inline `bindToLifecycle(...)` call with
`bindPreviewCaptureAnalysis(preview, capture, analysis)`, still inside the existing `runCatching`
blocks — no change to existing error-handling/retry structure. `UseCaseGroup` is already reachable
via the existing `import androidx.camera.core.*` wildcard — **no new import needed**. Recommended
(P1, not required): one `Log.i("ScannerFocus", ...)` line noting whether the group/viewPort path
engaged, matching this file's existing logging density, so a future real-device logcat run can
confirm it.

**2. Crop against `cropRect`, not the full frame (`ImageProxyExt.kt`, `toCroppedBitmap()`).**

```kotlin
fun ImageProxy.toCroppedBitmap(): Bitmap {
    Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")
    val decoded = toBitmap()

    val crop = cropRect
    // Field arithmetic (right-left / bottom-top), NOT crop.width()/crop.height() method calls —
    // see Risks: whether Rect's *methods* execute safely under this project's non-Robolectric
    // unit-test stub jar is unconfirmed. Field access is always safe (direct GETFIELD, not a
    // stubbable virtual call) — this sidesteps the risk rather than betting on it.
    val cropWidth = crop.right - crop.left
    val cropHeight = crop.bottom - crop.top
    val guideRect = guideFrameImageRect(cropWidth, cropHeight, imageInfo.rotationDegrees)
        .offsetBy(crop.left, crop.top)

    if (guideRect.width <= 0 || guideRect.height <= 0) {
        Log.w("ScannerFocus", "degenerate crop rect ($guideRect) for ${width}x$height rot=${imageInfo.rotationDegrees}, using uncropped")
        return decoded
    }
    return runCatching {
        Bitmap.createBitmap(decoded, guideRect.left, guideRect.top, guideRect.width, guideRect.height)
    }.getOrElse {
        Log.w("ScannerFocus", "crop failed, using uncropped", it)
        decoded
    }
}
```

New pure `internal` function alongside `guideFrameImageRect`/`ImageRect` in `ScannerScreen.kt`:

```kotlin
internal fun ImageRect.offsetBy(deltaLeft: Int, deltaTop: Int): ImageRect =
    ImageRect(
        left = left + deltaLeft,
        top = top + deltaTop,
        right = right + deltaLeft,
        bottom = bottom + deltaTop
    )
```

**Why this is correct:** `ImageProxy.getCropRect()` is defined in the same pre-rotation buffer
coordinate space as `width`/`height`, and `toBitmap()`'s decoded bitmap is the FULL pre-rotation
buffer regardless of `cropRect` (confirmed this session — `toBitmap()` never applies a crop).
`guideFrameImageRect(cropWidth, cropHeight, rotation)` returns a rect relative to the crop
region's own origin (0,0); adding `crop.left`/`crop.top` repositions it into `decoded`'s actual
coordinate space. When no `ViewPort` was set, `cropRect` defaults to `Rect(0,0,width,height)`, so
`cropWidth==width`, `cropHeight==height`, `offsetBy(0,0)` is a no-op — **byte-identical to today's
code.** Part 2 is safe and correct independent of whether Part 1 lands first.

---

## User stories

- As Skyler scanning a card, I want the captured photo cropped to what the on-screen guide box
  actually shows, so `GeminiCardScanner`/`PerceptualHasher` see the framing I aimed at, not ~1.9x
  more background.
- As a future debugger of this file, I want the crop math to keep working unchanged when no
  `ViewPort` is available (older devices, pre-layout race), so this fix can't regress the
  already-shipped fallback-safety behavior in `toCroppedBitmap()`.

---

## Acceptance criteria

- [ ] **Given** `previewView.viewPort` is non-null at both bind call sites, **when** `CameraPreview`
      binds, **then** binding goes through `UseCaseGroup.Builder().setViewPort(...)` with all three
      use cases added, via the `bindToLifecycle(LifecycleOwner, CameraSelector, UseCaseGroup)`
      overload.
- [ ] **Given** `previewView.viewPort` is null, **when** `CameraPreview` binds, **then** it falls
      back to today's plain vararg `bindToLifecycle(...)` — no crash, no behavior change from
      pre-fix code.
- [ ] **Given** an `ImageProxy` whose `cropRect` equals the full frame (no-`ViewPort` case),
      **when** `toCroppedBitmap()` runs, **then** the resulting crop rect passed to
      `Bitmap.createBitmap` is identical to what pre-fix `guideFrameImageRect(width, height,
      rotation)` alone would have produced (regression-proof backward compat).
- [ ] **Given** an `ImageProxy` whose `cropRect` is smaller than and offset within the full frame
      (`ViewPort`-constrained case), **when** `toCroppedBitmap()` runs, **then** the resulting crop
      rect is `guideFrameImageRect(cropRect.width, cropRect.height, rotation)` shifted by
      `(cropRect.left, cropRect.top)` — verified against hand-computed expected values.
- [ ] `offsetBy(deltaLeft=0, deltaTop=0)` applied to any `guideFrameImageRect(...)` output returns
      an equal `ImageRect` (proves the no-op/backward-compat case at the pure-function level too).
- [ ] `offsetBy` with nonzero deltas shifts `left`/`top`/`right`/`bottom` by exactly those deltas,
      leaves `width`/`height` unchanged.
- [ ] `.\gradlew.bat testDebugUnitTest --rerun-tasks` — 100% pass, existing suite (286 baseline)
      plus new tests, zero regressions.
- [ ] `.\gradlew.bat assembleDebug --rerun-tasks` — compiles clean, zero new warnings on the two
      touched files.
- [ ] `detectCardInFrame`'s own live-analysis sampling geometry is untouched (still reads
      `imageProxy.width`/`.height` directly, not `cropRect`) — grep-confirmed, not just "didn't
      edit it."

---

## Out of scope

- **`detectCardInFrame`'s live-analysis sampling geometry** — explicitly locked out by this task's
  own instructions; separately tracked, already-flagged duplication. Do not touch.
- **Real-device calibration / re-deriving `GUIDE_FRAME_WIDTH_RATIO`** — `docs/specs/
  2026-09-02-scanner-macro-focus.md` Task 5, explicitly deferred pending Skyler's own hardware
  pass. This fix makes the crop match the guide box; it does not change what ratio the box uses.
- **Task 2's bind-fallback wiring test coverage** — already a known, spec-sanctioned gap (Compose
  `AndroidView` factory closure, not independently unit-testable; declined twice in the prior
  pipeline run per `gaps.md`). `bindPreviewCaptureAnalysis` inherits this, not a new gap.
- **Real-device confirmation** — no real multi-camera hardware in this sandbox. Accepted
  limitation, not a blocker (see Risks).
- **`ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM`/`88f`/`63f` literal cleanup, 1px rounding on other axes,
  `sun.misc.Unsafe` fixture duplication** — separately tracked in `gaps.md`, none touched here.

---

## Dependencies

### Required before development can start
- [x] Root cause confirmed via decompiled CameraX 1.6.1 bytecode/sources this session — no further
      investigation needed before coding.
- [x] `UseCaseGroup`/`ViewPort` mechanism confirmed to exist and behave as expected on the exact
      pinned CameraX version (`1.6.1`) via javap, not guessed.

### External systems or services
- None — pure on-device camera-binding and bitmap-crop logic, no network/API involvement.

### Team dependencies
- Engineering only (Coder → Tester → Reviewer via `dev-team-pipeline`). No design/content/legal
  involvement — internal bug fix, no user-facing surface change beyond crop accuracy.

---

## Open questions

- [ ] **Does constructing a real `Rect(l, t, r, b)` (for a mocked `ImageProxy.cropRect` return
      value) work safely under this project's non-Robolectric `testDebugUnitTest` stub jar?** —
      **Owner:** Coder — **Decision needed by:** writing `ImageProxyExtTest.kt`'s new
      nonzero-crop-offset case. Field *access* should be safe regardless (direct `GETFIELD`, not a
      stubbable method call) — production code deliberately avoids `.width()`/`.height()` calls for
      this reason. Whether the constructor itself throws under the stub jar is unconfirmed
      (`[Guessing]`, not verified this pass). Try the plain constructor first; if it throws,
      escalate to a reflective field-set on a real `Rect` instance, mirroring this file's existing
      precedent (`FocalLengthsKeyTestFixture`'s reflective static-field override).
- [ ] **Should the fallback branch (viewPort null) log a diagnostic line?** — **Owner:** Coder —
      **Decision needed by:** implementation time. Recommended (P1, cheap) but not a hard
      acceptance-criteria requirement.

---

## Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `Rect` method/constructor behavior under the plain JVM unit-test stub jar is unconfirmed (no Robolectric) | Med | Med — could block a clean integration test for the nonzero-offset case | Production code uses field arithmetic only, never `.width()`/`.height()` methods — sidesteps the risk for the shipped path. Test-only risk isolated to one new test case; Open Questions gives a concrete escalation path if the simple constructor fails. |
| No real multi-camera hardware in this sandbox — the on-screen-vs-crop match can't be visually confirmed here | High | Low (accepted) | Not a blocker for this run. Flag in `gaps.md`/`handoff.md` as needing Skyler's real S26 Ultra pass, same standing pattern as attempts #1-#6. |
| `UseCaseGroup` bind could throw for a reason the plain vararg bind wouldn't (stricter shared-FOV validation) | Low | Med | Existing `runCatching`/fallback layers are unchanged and still wrap the new call; a failure degrades exactly like today's physical-camera-bind failure (log + fall back / null camera), not a new failure mode. |
| Six prior fix attempts on this file — risk of this being attempt #7 patching a symptom, not root cause | Low | Med | Scoped to the root geometric mismatch (two denominators for one ratio), confirmed via bytecode-level reading this session, not a guessed patch. |

---

## Smallest shippable increment

This spec's full scope **is** the smallest shippable increment — both fixes are small (one local
helper + two call-site edits in `ScannerScreen.kt`; one function-body edit + one new pure function
in `ImageProxyExt.kt`), independently backward-compatible when the `ViewPort` path can't engage,
and already narrowly scoped by the task's own file lock. Splitting Part 1 (UseCaseGroup) and Part 2
(crop offset) into separate runs is possible (Part 2 is a no-op standalone until `cropRect` is ever
non-full-frame) but not recommended — they're two halves of one diagnosed root cause; reviewing
together is cheaper than two passes.

**Future iterations (NOT in this increment):**
- Real-device calibration re-deriving `GUIDE_FRAME_WIDTH_RATIO` against the now-accurate crop
  (`docs/specs/2026-09-02-scanner-macro-focus.md` Task 5).
- Unifying `detectCardInFrame`'s duplicated live-sampling geometry with `guideFrameImageRect`.
- Test coverage for the `AndroidView` factory closure's full bind-decision tree.

---

## Friction Notes

- Serena's Kotlin language server failed to initialize this session (`Error extracting archive`) —
  fell back to Read/Grep for all code grounding instead of symbolic tools. Worth checking whatever
  is cached under its language-server directory for this project outside a task, since every future
  planning/coding pass on this repo hits the same fallback otherwise.
- The task's "required fix shape" said to use `cropRect.width()`/`.height()` literally; grounding
  against this project's own JVM-unit-test setup (no Robolectric) surfaced a real, non-obvious risk
  that `android.graphics.Rect`'s *methods* (not fields) may not execute safely under AGP's
  unit-test stub jar — worth a general project lesson: prefer field access over method calls on
  `Rect`/similar framework value types in code that must stay JVM-unit-testable here.
