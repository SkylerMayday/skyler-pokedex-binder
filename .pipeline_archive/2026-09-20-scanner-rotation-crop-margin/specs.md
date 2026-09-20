# Feature Spec: Scanner capture — rotation fix + guide-frame border/margin

**Author:** Planner (dev-team-pipeline)
**Date:** 2026-09-20
**Status:** Draft

---

## TL;DR

Two independent, empirically-diagnosed bugs in the scanner capture path (`ImageProxyExt.kt`,
`ScannerScreen.kt`): (1) captured bitmaps are never pixel-rotated, so every image ever sent to
Gemini/saved for diagnostics is sideways; (2) the guide box has no continuous edge and too little
real-world tolerance (87.7% of canvas height, corner-brackets only), so real scans clip the card's
top/bottom even when the user judges it "in frame." Both are surgical fixes in already-hot files —
no new abstractions, no `GUIDE_FRAME_WIDTH_RATIO` change.

---

## Problem

Confirmed this session via direct device evidence, not guessed:

- 4 real on-device debug capture JPEGs (`adb pull` from the existing kept TEMP diagnostic block in
  `ScannerViewModel.kt`, the same bitmap Gemini sees) are every one sideways. `toCroppedBitmap()`
  (`ImageProxyExt.kt`) uses `imageInfo.rotationDegrees` only to pick which pre-rotation rectangle to
  crop — it never rotates the resulting pixels.
- 5 real scans (Furfrou x2, Castform x2, Elgyem x1) this session all clipped both the card's
  name/HP header (top) and printed number/illustrator strip (bottom), despite the user judging each
  as "fully in frame." Root cause: the guide box already consumes 87.7% of canvas height
  (`GUIDE_FRAME_WIDTH_RATIO=0.75`) and `CardFrameOverlay` draws only 4 corner brackets — no
  continuous edge, almost no real-world margin before overflow.

Both bugs are independent of each other and of the already-shipped hash-margin confidence work
(session 16) — not a regression from that work.

---

## Proposal

### Bug 1 — rotate the cropped bitmap (`ImageProxyExt.kt`)

In `toCroppedBitmap()`, inside the existing `runCatching` block, after `Bitmap.createBitmap(decoded,
rect.left, rect.top, rect.width, rect.height)` produces `cropped`, rotate before returning:

```kotlin
return runCatching {
    val cropped = Bitmap.createBitmap(decoded, rect.left, rect.top, rect.width, rect.height)
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) {
        cropped
    } else {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
    }
}.getOrElse {
    Log.w("ScannerFocus", "crop failed, using uncropped", it)
    decoded
}
```

Add `import android.graphics.Matrix`. Rotate the small already-cropped bitmap, not the full raw
decode. Do not modify `guideFrameImageRect()`'s crop-position math for this bug — correct, unit-
tested for all 4 rotation cases.

**Downstream consumers checked, all tolerate swapped width/height (90/270 cases):**
`GeminiCardScanner.scaleBitmap()` scales by `maxOf(w, h)` (orientation-agnostic);
`PerceptualHasher.computeHash()` always resizes to a fixed 8x8; `ScannerViewModel.kt`'s log line and
kept TEMP diagnostic save just format/compress whatever they get.

### Bug 2 — continuous border + larger real-world crop margin (`ScannerScreen.kt`)

**Decision: full border, and inflate only the capture crop (not the drawn box or the presence
detector) via a new `CROP_MARGIN_FACTOR`.** Rejected reducing the ratio (0.75→~0.65): session 14's
own history found a *smaller* box gave Gemini less usable card detail and drove the ratio up to 0.75
in the first place — shrinking it again risks that regression. Inflating only the crop keeps the
drawn box a confident visual target while adding slack only where clipping actually happens.

1. **Full border** — in `CardFrameOverlay`'s `Canvas`, replace the 8 `drawLine` corner-bracket calls
   with one `drawRect(color = frameColor, topLeft = Offset(left, top), size = Size(guideW, guideH),
   style = stroke)` using the existing `stroke` object. Simpler than border-plus-brackets and covers
   "unambiguous reference for all 4 edges" alone.
2. **New constant**, next to `GUIDE_FRAME_WIDTH_RATIO`, same "first-guess, not final" comment
   convention:
   ```kotlin
   // First-guess, uncalibrated — same convention as GUIDE_FRAME_WIDTH_RATIO's history. Grows the
   // ACTUAL CAPTURE crop beyond the drawn guide box (same center) so a card judged "in frame"
   // against the tight visual target doesn't get header/footer clipped. Does NOT affect
   // CardFrameOverlay's drawn box or detectCardInFrame's presence heuristic — both must keep
   // matching what's on screen exactly.
   private const val CROP_MARGIN_FACTOR = 1.10f
   ```
3. **Apply only inside `guideFrameImageRect()`** — used solely by `ImageProxyExt.toCroppedBitmap()`
   and its own unit tests, NOT by `detectCardInFrame`/`CardFrameOverlay` (each has separate
   geometry), so the "must stay consistent with what's drawn" requirement holds trivially. Insert
   after `guideW`/`guideH`, before `dLeft`/`dTop`:
   ```kotlin
   val boxW = (guideW * CROP_MARGIN_FACTOR).toInt()
   val boxH = (guideH * CROP_MARGIN_FACTOR).toInt()
   ```
   Use `boxW`/`boxH` in the `dLeft`/`dTop`/`dRight`/`dBot` computation only — the rotation
   corner-mapping code below is untouched, and the existing `coerceIn` calls already handle a box
   overflowing raw bounds, so no new degenerate-case handling is needed.
4. Remove the 3 "TEMP DIAGNOSTIC (2026-09-17)" blocks as part of this edit (two sit inside the lines
   already being touched) — see below.

### TEMP diagnostics — remove the 3 tagged "2026-09-17", keep everything else

**Remove** (purpose fully served — this is literally how Bug 2 was found this session):
`ImageProxyExt.kt`'s `cropRect=...` `Log.i` block + comment (~lines 37-44); `ScannerScreen.kt`
`guideFrameImageRect()`'s `"guideFrameImageRect raw=..."` `Log.i` block + comment (~lines 204-212);
`ScannerScreen.kt` `CardFrameOverlay`'s `onSizeChanged { Log.i(...) }` wrapper + comment (~lines
239-246) — revert to `Box(modifier = modifier) { ... }`.

**Do NOT touch**: `ScannerViewModel.kt`'s "TEMP DIAGNOSTIC (2026-09-11)" `scan_debug_*.jpg` block —
this run's own mandatory live-verification step depends on it. Also leave the untagged, permanent
`Log.i("ScannerFocus", "captured format=...")` line in `ImageProxyExt.kt` alone.

### Task breakdown (dependency order)

| # | Task | Size | Files |
|---|---|---|---|
| 1 | Bug 1: rotation fix in `toCroppedBitmap()` | S | `ImageProxyExt.kt` |
| 2 | Add rotation test(s) to `ImageProxyExtTest.kt` (see Edge cases) | S | `ImageProxyExtTest.kt` |
| 3 | Bug 2: border + `CROP_MARGIN_FACTOR` + remove 3 TEMP blocks | M | `ScannerScreen.kt` |
| 4 | Recompute pixel-literal assertions touched by Task 3 | M | `ScannerScreenTest.kt`, `ImageProxyExtTest.kt` |
| 5 | `testDebugUnitTest --rerun-tasks` + `assembleDebug --rerun-tasks`, both clean | S | build only |
| 6 | Human-in-the-loop live scan + `adb pull` verification | — | device, post-Reviewer |

1→2 and 3→4 dependent (literals depend on final code's float arithmetic); 5 depends on 1-4; 6 is
outside this pipeline's Tester/Reviewer stages entirely.

### Edge cases to handle

- **Rotation test mocking**: this codebase's non-Robolectric stub jar throws `not mocked` on real
  `Matrix`/`Rect` method calls. A `rotation != 0` test needs `mockkConstructor(Matrix::class)` +
  `every { anyConstructed<Matrix>().postRotate(any()) } returns true`, `every { cropped.width }
  returns X` / `every { cropped.height } returns Y` (unstubbed today — no path reads them
  pre-fix), and a stub for the 6-arg `Bitmap.createBitmap(cropped, 0, 0, w, h, any<Matrix>(), true)`
  overload returning a distinct rotated-bitmap mock, asserted via `assertSame`.
- **`rotation == 0` no-op**: existing 5 `ImageProxyExtTest.kt` cases all use `rotationDegrees = 0` —
  unaffected by Task 1.
- **Degenerate-crop tests unaffected by Task 3**: `width = 1` and invalid-`cropRect` cases collapse
  to a zero-size box either way (`0 × 1.10 = 0`) — no literal changes.
- **`offsetBy` tests are relative** (`rect.left + 100 == shifted.left`), not hardcoded — unaffected.
- **Degenerate-extreme-aspect-ratio test (2000x50)** is already fully saturated (`top=0`/`bottom=50`)
  before margin applies — inflating further can't change an already-maximal clamp, no update needed.
- **Literals that DO need recomputing** (verified this session against a from-scratch float32
  reimplementation, cross-checked bit-for-bit against this file's own existing committed baseline
  values before trusting it — still re-derive by running the real test, don't copy blindly, given
  this function's prior real 1px-rounding bug, session 10):
  - `ScannerScreenTest.kt`, rotation 0 (`4000x3000`): `left` 500→**350**, `right` 3500→**3650**,
    `width` 3000→**3300** (`top`/`bottom`/`height` unchanged, already saturated at 0/3000/3000).
  - Same file, rotation 180: `left` 500→**350**, `right` 3500→**3650** (`top`/`bottom` unchanged).
  - Same file, rotation 90: `left` 429→**272**, `top` 375→**263**, `right` 3571→**3728**, `bottom`
    2625→**2738**.
  - Same file, rotation 270: `left` 429→**272**, `top` 375→**262**, `right` 3571→**3728**, `bottom`
    2625→**2737** (90/270 no longer land on identical numbers once margin applies — arithmetic, not
    a bug).
  - `ImageProxyExtTest.kt`, `` `valid rect crops the decoded bitmap via Bitmap createBitmap` ``:
    `Bitmap.createBitmap(decodedBitmap, 125, 476, 750, 1047)` → `(decodedBitmap, 87, 424, 825,
    1151)`.
  - `ImageProxyExtTest.kt`, `` `nonzero cropRect offset shifts guideFrameImageRect by cropRect
    origin` ``: `(decodedBitmap, 225, 676, 750, 1047)` → `(decodedBitmap, 187, 624, 825, 1151)`.

---

## User stories

- As Skyler, I want a scanned card's captured photo right-side-up, so Gemini's OCR sees the card as
  actually printed, not sideways.
- As Skyler, I want the guide box to show a full outline, so I can align a card's edges precisely
  instead of guessing from 4 corner marks.
- As Skyler, I want real-world slack in framing, so a card I judge "in frame" doesn't get its
  header/footer clipped out of the actual captured image.

---

## Acceptance criteria

- [ ] **Given** `toCroppedBitmap()` receives `rotationDegrees` in `{90, 180, 270}`, **when** it
      returns, **then** the bitmap's pixels are upright and `width`/`height` are swapped relative to
      the pre-rotation crop for 90/270.
- [ ] **Given** `rotationDegrees == 0`, **when** it runs, **then** no `Matrix` is constructed and the
      result is byte-identical to today's behavior.
- [ ] **Given** `CardFrameOverlay` renders, **then** a single continuous border traces the guide box
      (corner brackets removed, not both).
- [ ] **Given** the same guide box, **then** the actual capture crop is `CROP_MARGIN_FACTOR` (1.10x
      linear) larger, same center, clamped to raw bounds by the existing `coerceIn` logic.
- [ ] **Given** the 3 "TEMP DIAGNOSTIC (2026-09-17)" blocks, **then** all 3 are removed and
      `ScannerViewModel.kt`'s "TEMP DIAGNOSTIC (2026-09-11)" block is untouched.
- [ ] `testDebugUnitTest --rerun-tasks` passes 100% (fresh XML) and `assembleDebug --rerun-tasks` is
      clean.
- [ ] **Human-in-the-loop gate, separate from the above, NOT completable by Tester/Reviewer**: after
      this pipeline's Reviewer stage, Skyler performs a real scan on the connected `SM-S948B`; the
      orchestrator `adb pull`s the `scan_debug_*.jpg` and confirms (a) right-side-up and (b) both the
      name/HP header and printed number/illustrator strip are visible. Automated stages verify code
      correctness and build readiness only — no real camera hardware exists in this dev sandbox.

---

## Out of scope

- Any change to `GUIDE_FRAME_WIDTH_RATIO` — rejected in favor of the crop-margin approach.
- The hash-margin confidence pipeline (`PerceptualHasher.kt`, `SmartThresholdUseCase.kt`) — unrelated
  session-16 work.
- `detectCardInFrame()`'s duplicated geometry — stays as-is, not refactored to call
  `guideFrameImageRect()` (pre-existing `gaps.md` "Task 4" note).
- Recalibrating `CROP_MARGIN_FACTOR`/`GUIDE_FRAME_WIDTH_RATIO` against real data — Skyler's
  post-live-test call.
- Gradle/env-setup fixes — use documented conventions (`.ps1` + `powershell.exe -File`,
  `--rerun-tasks`).

---

## Dependencies

### Required before development can start
- [x] Both bugs diagnosed with real device evidence this session — no further investigation needed.

### External systems or services
- None — both fixes are local, no network/API surface touched.

### Team dependencies
- Skyler: the live scan (last acceptance criterion) — only he can hold a physical card to the
  phone; orchestrator coordinates after the Reviewer stage.

---

## Open questions

- [ ] Is `CROP_MARGIN_FACTOR = 1.10` (10% linear, ~21% by area) the right first-guess magnitude given
      how tight 0.75 already was? **Owner:** Skyler, informed by the live-test gate. **Decision
      needed by:** after the first post-fix live scan; not a ship blocker.
- [ ] Should `detectCardInFrame()` eventually call `guideFrameImageRect()` instead of duplicating its
      geometry (pre-existing `gaps.md` note)? **Owner:** Skyler. **Decision needed by:** not
      blocking, future session.

---

## Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Recomputed float32 test literals are subtly wrong (prior 1px-rounding bug here) | Med | Low | Task 4 requires running the modified function to confirm, not trusting spec numbers blindly |
| Rotation briefly triples live bitmaps for 90/270 captures | Low | Low | Already a small cropped bitmap, not the raw ~4000x3000 frame; no recycling added, matches existing style |
| `CROP_MARGIN_FACTOR=1.10` too small (recurs) or too large (background hurts OCR, session-14 style) | Med | Med | First-guess, comment-flagged re-tunable; mandatory live-test gate catches this |
| Removing the 3 TEMP blocks makes a recurrence harder to re-diagnose | Low | Low | Permanently-kept `scan_debug_*.jpg` block is the real evidence mechanism; cheap to re-add if needed |

---

## Smallest shippable increment

**MVP is the whole spec** — both bugs are small, independent, surgical diffs (~2 production files, 2
test files, no new dependencies, no migration). Splitting further (e.g. Bug 1 alone) isn't
meaningfully smaller — Bug 2 touches a disjoint region of the same file family with no risk of
blocking Bug 1. Ship both together.

**Future iterations (NOT in this spec):** live-device recalibration of `CROP_MARGIN_FACTOR` (and
possibly `GUIDE_FRAME_WIDTH_RATIO`) once real scan data exists; the `detectCardInFrame()`/
`guideFrameImageRect()` de-duplication noted in Open questions.

---

## Friction Notes

- Serena's Kotlin language server failed to initialize this session (archive extraction error) —
  fell back to `Read`/`Grep` for Kotlin investigation. If persistent, future Planner runs here
  should skip straight to `Read`/`Grep` rather than re-attempt Serena's symbol tools first.
- Deriving exact pixel literals for float-arithmetic test assertions was only made safe by
  cross-validating a from-scratch float32 reimplementation against this file's own existing
  committed values first — never trust a script-derived literal for float-sensitive pixel math
  without first reproducing an already-known-good value the same way.
