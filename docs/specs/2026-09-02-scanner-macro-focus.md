# ADR + Spec — Scanner Close-Range Focus via Physical Lens Selection

Status: draft, pending Skyler approval. **Do not start `dev-team-pipeline` on this until Skyler
explicitly says go** — planning only for now.

## Context

Fix #4 in the scanner blur bug family (`20c74f3`, `GUIDE_FRAME_WIDTH_RATIO = 0.32f`) fixed the
original blur by pushing the required working distance out to ~23cm, escaping the S26 Ultra main
lens's documented 18cm minimum focus distance. Real-device confirmation (2026-08-29) found this
traded one bug for another: the captured frame handed to `GeminiCardScanner.scan()` /
`PerceptualHasher` is **not cropped to the guide frame** — it's the full preview frame. Shrinking
the guide box's fill ratio to buy focus distance also shrank the card's actual footprint (and
detail) inside the image both matchers work from, and Skyler now reports the app "keeps giving the
wrong card." Full diagnosis: `gaps.md`, 2026-08-29 entry.

Skyler's direction, confirmed: don't push the user farther back — get the app to focus close the
way the phone's own stock camera app does, scanner-wide (not gated behind the still-parked grading
feature), then re-tune the guide frame back up once close focus works.

This is attempt #5 on `ScannerScreen.kt`'s camera path this bug family. Per the debugging skill's
own 3+-fixes-question-the-architecture signal, this gets a real plan, not another patch — hence
this document.

## Grounding — the answer already exists

`docs/specs/2026-08-27-card-grading-estimate.md`'s Open Question #4 researched this exact problem
for the (still-parked) grading feature and found:

- Samsung's own stock camera app doesn't get the main lens to focus closer than ~18cm either — it
  silently **auto-switches to the ultrawide lens** ("Focus Enhancer"/macro mode) once the subject
  is within ~28-30cm. That's the only practical close-range option left on this generation (the 5x
  telephoto's old telemacro trick regressed to ~52-80cm minimum focus, worse than the main lens).
- `CameraSelector.DEFAULT_BACK_CAMERA` — what `ScannerScreen.kt:371` currently binds — is a
  **fixed** logical-camera selection. It does not auto-switch lenses the way Samsung's own camera
  app does, so the app never gets the ultrawide's closer-focus behavior for free.
- CameraX supports explicitly selecting the ultrawide **physical** camera within the logical
  multi-camera via `Camera2CameraFilter` (experimental `androidx.camera.camera2.interop`, the same
  package `ScannerScreen.kt` already uses for `logCameraAfCapabilities`, `4bd8bac`), filtering by
  `CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS < ~20mm`, gated behind
  `CameraInfo.isLogicalMultiCameraSupported()` (not every device supports physical-camera selection
  on a logical multi-camera).
- Not yet confirmed: the ultrawide's exact minimum focus distance in cm, and its lower megapixel
  count relative to the main sensor (a real trade-off — needs confirming it doesn't undermine the
  extra closeness with a soft/noisy image).

## New risk found during continued planning (2026-09-02) — feasibility itself is unconfirmed

Web research into Samsung's Camera2 implementation found (low-authority Samsung Community forum
reports, not an official spec — [Likely], not [Certain], and genuinely contradictory across
sources) that Samsung devices commonly run Camera2 at a restricted `LIMITED` hardware level for
**third-party apps specifically**, which some reports describe as locking out ultrawide/macro
sensor access entirely for apps like Instagram/Snapchat/WhatsApp, while a separate report claims
wide + ultrawide ARE both accessible via Camera2 on some Samsung multi-camera setups. Neither
source is authoritative, and this isn't confirmed for the S26 Ultra specifically (full detail:
`skyler-phone-specs.md`, 2026-09-02 follow-up). **This is a different, more foundational risk than
"is the min-focus-distance good enough" — it's "can this app even bind the ultrawide as a distinct
physical camera on this device at all."** If `CameraInfo.isLogicalMultiCameraSupported()` returns
false, or the physical-camera filter never yields a usable ultrawide id, the entire Decision above
degrades to the existing fallback path (today's main-lens/23cm behavior) with nothing gained.

**This should be confirmed with a cheap real-device spike before writing any of the rest of the
task breakdown below** — a small standalone check (enumerate `CameraInfo`s, log
`isLogicalMultiCameraSupported()` and whatever `Camera2CameraFilter` actually returns for
`LENS_INFO_AVAILABLE_FOCAL_LENGTHS < 20mm`) run on Skyler's real S26 Ultra, not assumed from either
this spec's confidence or the forum research above.

## Decision

Bind the scanner's `CameraPreview` to the ultrawide physical camera (via `Camera2CameraFilter`)
instead of `CameraSelector.DEFAULT_BACK_CAMERA`, when the device supports physical-camera
selection on a logical multi-camera. Fall back to the current fixed main-lens behavior (today's
`DEFAULT_BACK_CAMERA` + 23cm guidance) when it doesn't.

### Options considered

| Option | Assessment |
|---|---|
| **A. Ultrawide physical-lens selection** (chosen) | Matches how the stock camera actually solves this; reuses an already-proven interop pattern in this file; real risk is unconfirmed min-focus-distance/resolution trade-off on the ultrawide itself. |
| B. Keep main lens, push distance further out again | Already tried (fix #4) and is what caused the current bug — rejected, not re-litigating. |
| C. Digital zoom / crop-in on the main lens | Doesn't change *focus* distance at all — the lens still can't rack focus below 18cm; crops a blurry image tighter, doesn't fix blur. Rejected. |
| D. Vendor-specific Camera2 extension (Samsung's own camera SDK) | Samsung doesn't expose a public third-party Camera2 extension for its "Focus Enhancer" auto-switch — would require reverse-engineering vendor behavior, no public API. Rejected, not maintainable. |

### Consequences

- Fixes close-range focus without another distance-based UI workaround.
- Adds a device-capability branch (multi-camera support varies) — first fallback-path complexity
  in this file's camera selection, previously always a single fixed selector.
- Ultrawide sensors are typically lower-resolution than the main sensor — needs real-device
  confirmation this doesn't hurt Gemini/perceptual-hash accuracy more than it helps.
- Still uses `@OptIn(ExperimentalCamera2Interop::class)` — an experimental API surface CameraX
  could change in a future major version; same risk profile as the existing diagnostic code.

## Scope (confirmed with Skyler, 2026-08-29 AskUserQuestion)

1. **Scanner-wide** — QuickScan/Pokédex capture and manual capture both go through
   `ScannerScreen.kt`'s shared camera path; both benefit automatically.
2. **Re-tune `GUIDE_FRAME_WIDTH_RATIO` back up** once close focus works — re-derive properly for
   the new working distance (a fresh calibration photo at the confirmed working distance), not a
   blind revert to the old `0.75f`.

## Additional fix identified during planning — crop before matching

The root cause chain has a second, independent weak point worth fixing at the same time: the image
handed to `GeminiCardScanner.scan()` / `PerceptualHasher.findBestMatch()` is the **full preview
frame**, not cropped to the guide box. Even with focus fixed and the ratio re-tuned, a user who
doesn't perfectly fill the guide frame still sends background pixels to both matchers. Recommend
cropping the captured bitmap to the guide-frame rect (plus a small margin) before it reaches either
matcher — this is the actual mechanism the diagnosis traced the accuracy loss to, not just a
side effect of the ratio choice, so re-tuning the ratio alone is treating a symptom of this same
gap. Both `imageCapture.takePicture`'s two call sites in `ScannerScreen.kt` (auto-capture and
manual capture button) currently pass the full JPEG straight to `ScannerViewModel`; the crop
belongs in `ImageProxyExt.kt`'s `toBitmap()` path or immediately after, using the same
`GUIDE_FRAME_WIDTH_RATIO`-derived rect `CardFrameOverlay` already draws.

## Requirements

**P0**
- Ultrawide physical-camera selection implemented, gated behind
  `CameraInfo.isLogicalMultiCameraSupported()`, with the current `DEFAULT_BACK_CAMERA` behavior as
  the fallback path (not a hard requirement, a device compatibility branch).
- Real-device calibration: confirm ultrawide minimum focus distance and working distance for a
  filled guide frame; re-derive `GUIDE_FRAME_WIDTH_RATIO` from that (not guessed).
- Captured bitmap cropped to the guide-frame rect before reaching `GeminiCardScanner`/
  `PerceptualHasher`.

**P1**
- On-screen distance guidance text updated to match the new (closer) working distance.
- Diagnostic logging (`logCameraAfCapabilities` pattern) extended to log which physical camera
  actually got bound, so a future "still wrong" report can distinguish "ultrawide bound but still
  inaccurate" from "fell back to main lens, capability check failed."

**P2**
- Graceful UI messaging if a device has no ultrawide / doesn't support physical-camera selection —
  today's 23cm-workaround behavior is already an acceptable fallback, just confirm it isn't silently
  broken by whatever refactor lands the new selection logic.

## Task Breakdown (for `dev-team-pipeline`'s Coder stage, when authorized)

| # | Task | File(s) | Type | Size | Depends on |
|---|---|---|---|---|---|
| 0 | **Feasibility spike** — small diagnostic-only check (log `isLogicalMultiCameraSupported()` + whatever `Camera2CameraFilter` returns for the focal-length filter), run on Skyler's real S26 Ultra, confirmed working BEFORE any of tasks 1-6 are built | `ScannerScreen.kt` (diagnostic-only, same pattern as `logCameraAfCapabilities`) | TEST | S | — |
| 1 | Add `selectUltrawideCameraSelector(provider: ProcessCameraProvider): CameraSelector?` — builds a `Camera2CameraFilter` matching `LENS_INFO_AVAILABLE_FOCAL_LENGTHS < 20mm`, returns null if unsupported/not found | `ScannerScreen.kt` | CORE | M | 0 |
| 2 | Wire into the existing bind call: try ultrawide selector first (behind `isLogicalMultiCameraSupported()` check), fall back to `DEFAULT_BACK_CAMERA` | `ScannerScreen.kt` | CORE | S | 1 |
| 3 | Extend `logCameraAfCapabilities` (or a sibling function) to log which selector was actually used | `ScannerScreen.kt` | CORE | S | 2 |
| 4 | Add guide-frame-rect cropping to the captured bitmap before it reaches the matchers | `ImageProxyExt.kt`, `ScannerViewModel.kt` | CORE | M | — (independent of 1-3) |
| 5 | Real-device calibration pass: confirm ultrawide min focus distance, re-derive `GUIDE_FRAME_WIDTH_RATIO` and the on-screen distance text | `ScannerScreen.kt` | TEST/CORE | S | 1, 2, 4 (needs the real hardware behavior to calibrate against) |
| 6 | Unit tests for the selector-fallback logic (mock `ProcessCameraProvider`/`CameraInfo`) and the crop-rect math | `ScannerScreenTest.kt` (new or existing) | TEST | M | 1-5 |

**Phase rollout:** 1-3 (lens selection) and 4 (cropping) can run in parallel — independent code
paths. 5 (calibration) needs both done first since it needs the real combined behavior to measure
against. 6 last.

## Open Questions (blocking `dev-team-pipeline` start)

- **Whether physical-camera selection works on this device at all** (new, 2026-09-02) — Samsung's
  `LIMITED` Camera2 hardware level may restrict third-party ultrawide access; genuinely unconfirmed
  for the S26 Ultra, contradictory even in the general-pattern research. Task 0's spike answers
  this before anything else in the breakdown is built.
- Ultrawide minimum focus distance in cm — unconfirmed, needs a real-device calibration photo
  (same method used for fix #4's 23cm derivation).
- Ultrawide resolution/noise trade-off — does the lower-megapixel sensor hurt Gemini/
  perceptual-hash accuracy enough to offset the focus win? Needs a real side-by-side comparison,
  not assumed acceptable.
- Does every relevant device Skyler might use (not just the S26 Ultra) support
  `isLogicalMultiCameraSupported()`? If a future phone doesn't, the fallback path (today's
  behavior) needs to still be genuinely acceptable, not just "doesn't crash."

## Timeline

No hard deadline. **Do not start `dev-team-pipeline` until Skyler explicitly says go.**
