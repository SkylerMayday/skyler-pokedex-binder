# Spec — Card Grading Estimate

Status: draft, pending Skyler approval. **Do not start `dev-team-pipeline` on this until Skyler
explicitly says go** — spec only for now.

## Problem

Skyler wants to take a photo of a card and get an estimated professional grade (PSA/BGS/CGC-style
— centering, corners, edges, surface) as a rough personal reference: organizing/curiosity, **not**
a decision an actual grading-submission choice hinges on. Low stakes, confirmed explicitly — a
wrong estimate costs nothing beyond a mislabeled binder entry.

## Grounding

- `GeminiCardScanner.kt` already sends card photos to Gemini 2.5 Flash vision for identification
  (name/number/HP/artist/dex) — real, working prior art for LLM-vision card analysis. Its image
  pipeline downscales to max 1024px / JPEG quality 80, which is fine for reading printed text but
  too lossy for judging corner whitening or surface scratches — this feature needs its own,
  higher-fidelity capture path, not a reuse of that one.
- `ScannerScreen.kt`'s `detectCardInFrame` does simple contrast-based edge detection against a
  guide frame — relevant prior art for a centering-measurement approach, but it currently only
  detects "is a card present," not the card's actual border proportions.
- A manual prototype pass this session (grading-rubric read of 5 real card photos already in hand,
  varying distance/angle, normal room lighting, no dedicated capture flow) found:
  - **Centering** is genuinely assessable from a decent single photo — border margins are visible
    and measurable, though a straight-on flat shot beats a handheld angled one.
  - **Corners, edges, surface** are not honestly assessable from one general "hold the card up"
    photo — corner/edge wear needs close, sharp framing at a resolution a normal card-sized shot
    doesn't provide; surface defects need controlled/raking light to catch glare and scratches,
    which normal room lighting doesn't reliably give either way (can hide real defects, or fake
    ones from reflections).
  - Confirmed with Skyler: **single-photo capture only**, corners/edges/surface explicitly
    presented in the UI as low-confidence rather than given a multi-shot guided-capture flow. This
    is the deliberate trade-off this spec builds around — full 4-factor estimate, honest about
    which factors are measured vs. guessed.

## Goals

- Photo → estimated grade across all 4 PSA-style factors (centering, corners, edges, surface).
- Centering is computed, not guessed — a real border-ratio measurement, not an LLM impression.
- Corners/edges/surface come from a Gemini vision read against an explicit grading-rubric prompt,
  and the UI visibly marks these three as lower-confidence than centering — never presented with
  false uniformity across all 4 factors.
- Reuses this app's existing Gemini-integration pattern (API key from Settings, same
  request/response shape) rather than inventing a second, divergent LLM client.

## Non-Goals

- No multi-shot guided capture flow (per Skyler's explicit trade-off above) — one photo only.
- No claim of PSA/BGS/CGC parity or submission-readiness — this is a rough personal estimate tool,
  stated as such in the UI, not a substitute for real grading.
- No training of a custom CV/ML model — no labeled dataset, no ML infra in this project; centering
  uses deterministic geometry, corners/edges/surface use the existing off-the-shelf Gemini API.
- No retroactive grading of already-scanned/binder cards — this is a new, opt-in capture flow the
  user runs deliberately per card, not something that runs automatically during normal scanning.
- No persistence/history of estimate results decided yet (see Open Questions) — this spec covers
  the single-photo → estimate flow; where results live long-term is still open.

## Architecture Decision — Hybrid centering (deterministic) + Gemini (corners/edges/surface)

**Task-model fit check** (per `planning` skill's LLM-fit framework):
- Corners/edges/surface: **proceed with LLM.** Subjective judgment against a known rubric,
  natural-language-shaped output, real error tolerance (low-stakes per Goals), no proprietary data
  dependency, domain knowledge (what corner whitening/surface scratches look like) is exactly the
  kind of thing already broadly present in a vision model's training.
- Centering: **do not use an LLM.** This is precise computation (border-ratio measurement) with an
  existing deterministic-geometry precedent already in this codebase (`detectCardInFrame`) —
  reaching for an LLM here would trade a measurable, trustworthy number for an impression, for no
  benefit.

**Alternative considered and rejected:** send the whole grading judgment (all 4 factors) to
Gemini in one holistic prompt, no separate centering computation. Rejected because centering is
the one factor genuinely measurable with confidence — collapsing it into the same LLM call as the
three genuinely-uncertain factors would make the UI's honesty distinction (computed vs.
low-confidence) impossible to represent, undermining the whole point of the manual-prototype
finding above.

## Technical Approach

### New files

| File | Purpose |
|---|---|
| `domain/GeminiCardGrader.kt` | New class, sibling to `GeminiCardScanner`. Sends a higher-resolution image (target ~2048px, JPEG quality ~90 — needs real-device tuning, not guessed) with a grading-rubric prompt covering corners/edges/surface only (not centering — that's computed separately). Returns a `GradingRubricResult` (per-factor qualitative rating + Gemini's own stated reasoning/caveats, not a bare number). |
| `domain/CenteringMeasurer.kt` | New deterministic CV logic. Detects the card's physical outer edge AND the printed border's inner edge (two distinct boundaries — `detectCardInFrame` only ever detected one, the card-against-background edge) from the captured bitmap, computes left/right and top/bottom border-width ratios, returns a `CenteringResult` (ratios + a PSA-style bucket, e.g. "55/45"). This is real, non-trivial new CV work — the hardest technical unknown in this spec, flagged explicitly below. |
| `ui/grading/GradingScreen.kt` + `GradingViewModel.kt` | New capture screen. Deliberate single-photo capture (not the quick-scan auto-capture flow) — user frames the card and taps to capture, no auto-timer, since a grading photo needs to be composed carefully, not grabbed automatically. Displays the 4-factor result: centering as a real measurement, the other 3 explicitly labeled low-confidence. |
| `data/model/GradingEstimate.kt` (or similar) | Data model tying `CenteringResult` + `GradingRubricResult` together for display/potential persistence. |

### Open technical unknowns (real risk, not just detail)

1. **`CenteringMeasurer`'s two-edge detection is the biggest unknown in this spec.** Existing prior
   art (`detectCardInFrame`) only ever solved the easier problem (card vs. background). Finding
   the *printed border* inside the card image reliably, across different card designs/eras/holo
   patterns, is a real open CV question — may need a `dev-team-pipeline` spike/prototype pass
   before the rest of the feature is built, not assumed to just work.
2. **Grading-rubric prompt design** — needs real iteration against real card photos (multiple, not
   just the one Wishiwashi GX sample) to get Gemini's corner/edge/surface read to be useful rather
   than generically hedge-everything vague. Budget real iteration time here, not a one-shot prompt.
3. **Image resolution/quality tuning for the new capture path** — `GeminiCardGrader`'s target
   ~2048px/JPEG-90 is a starting guess, not derived from testing; needs real-device calibration
   against Gemini API request-size limits and actual corner/surface visibility in practice.

## Open Questions (blocking, need Skyler's answer before `dev-team-pipeline` starts)

1. **Where does this live in the app?** A new bottom-nav/menu entry, an action from an existing
   binder slot's detail screen (grade the card already assigned to a slot), or both? Not decided.
2. **Does the estimate get saved anywhere**, or is it a one-shot "photo in, estimate shown, done"
   with nothing persisted? If saved, where (a new field on existing slot data, a standalone
   grading-history list)?
3. **Priority relative to the still-open scanner real-device confirmation** (guide-frame resize,
   `20c74f3`, this same session) — should that be confirmed working first, or is this independent
   enough to proceed in parallel? (Technically independent — different capture flow, different
   screen — but same underlying camera/CameraX code family this session has already touched 4
   times.)
4. **Grading wants to get *closer* than scanning does** (to resolve corner/edge/surface detail),
   which runs straight back into the same 18cm minimum-focus-distance wall this session just spent
   4 fix attempts working around for the scan flow — **researched and resolved, real answer
   below.**

   Confirmed via research (2026-08-27): Samsung's stock camera app doesn't actually get the main
   lens to focus closer than 18cm either — it silently **auto-switches to the ultrawide lens**
   ("Focus Enhancer"/macro mode) whenever the subject is within ~28-30cm, and that's the *only*
   practical close-range option left on this generation (the 5x telephoto's old "telemacro" trick
   is gone — its own minimum focus distance regressed to ~52-80cm across sources, worse than the
   main lens). This also explains why the earlier stock-camera distance test worked even up
   close — it was very likely using the ultrawide, not the main lens.

   `CameraSelector.DEFAULT_BACK_CAMERA` (what both the scan flow and this spec's `GeminiCardGrader`
   capture screen would use by default) is a *fixed* selection — it does not auto-switch lenses
   the way Samsung's own camera app does, so the app never gets the ultrawide's better close-focus
   behavior for free.

   **Real, technically-confirmed path forward:** CameraX supports explicitly selecting the
   ultra-wide physical camera via `Camera2CameraFilter` (experimental API, same
   `androidx.camera.camera2.interop` package already used this session for the diagnostic
   `logCameraAfCapabilities` code — real working precedent in this codebase), filtering by
   `CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS < ~20mm` to identify the ultra-wide
   lens, gated behind `CameraInfo.isLogicalMultiCameraSupported()` (not every device supports
   physical-camera selection on a logical multi-camera). **Recommendation: `GeminiCardGrader`'s
   capture screen should explicitly bind to the ultrawide physical camera, not
   `DEFAULT_BACK_CAMERA`**, to access genuinely closer macro range for corner/edge/surface detail
   — the same mechanism Samsung's own stock camera relies on. Still needs real-device testing
   (ultrawide's own exact minimum focus distance in cm wasn't found in this round of research, and
   ultrawide sensors are typically lower-megapixel than the main sensor, a real trade-off worth
   confirming doesn't undermine the extra closeness).

## Suggested Next Step

Given unknown #1 above (`CenteringMeasurer`'s two-edge detection) is the single biggest technical
risk in this whole feature, recommend a small, cheap **spike** before committing to the full build:
prototype the two-edge detection against 3-5 real card photos (different eras/holo patterns) and
confirm it's actually achievable at useful accuracy before writing the full `dev-team-pipeline`
spec for the rest of the feature. If the spike fails, the honest fallback is dropping centering
down to "also Gemini-estimated, also marked low-confidence" rather than the trustworthy computed
number this spec currently promises — a real scope change worth knowing about early, not
discovered mid-build.
