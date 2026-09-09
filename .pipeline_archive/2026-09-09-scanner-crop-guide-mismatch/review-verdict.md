# Review Verdict: Scanner crop-rect-vs-guide-box geometry fix

**Scope:** `git diff master` — 6 files, 111 insertions / 8 deletions (2 production: `ScannerScreen.kt`,
`ImageProxyExt.kt`; 4 test files + 1 new test fixture `RectTestFixture.kt`). Single-lens review
(diff is small, not security/auth/payment-sensitive). First pass — no prior `review-verdict.md`.

## Intent audit (Phase 2) — clean

Every spec requirement traces to an actual diff line:
- `bindPreviewCaptureAnalysis` (`ScannerScreen.kt` ~673-694) matches the spec's own code sketch
  near-verbatim, both bind call sites (~719, ~745) route through it.
- `ImageProxyExt.kt`'s `toCroppedBitmap()` derives `cropWidth`/`cropHeight` via field arithmetic
  (`crop.right - crop.left`, `crop.bottom - crop.top`) exactly as specced, not `.width()`/
  `.height()` method calls.
- `ImageRect.offsetBy` is the exact pure function from the spec.
- No new import (`import androidx.camera.core.*` wildcard already present, confirmed at line 12).
- `detectCardInFrame` untouched — grep-confirmed still reads `imageProxy.width`/`.height` directly
  (lines 69-70), no `cropRect` reference anywhere in that function.
- No scope creep: `RectTestFixture.kt` (new) is pre-authorized by the spec's own Open Questions
  section as the anticipated escalation path if `Rect`'s constructor didn't behave under the
  stub jar — it didn't (silently zeroed fields instead of throwing), and the fixture is the
  documented fix, not an unplanned addition.
- The untracked `.pipeline_archive/2026-09-08-scanner-macro-focus/` directory predates this
  session (changes.md) and is unrelated to this diff — noted, not a finding.

All 9 acceptance criteria verified against actual code (see below) — none partial/not-done.

## Findings

### P2 — degenerate-`cropRect` fallback path is bypassed by an earlier throw (confidence 7/10)

`ImageProxyExt.kt`:
```kotlin
val rect = guideFrameImageRect(cropWidth, cropHeight, imageInfo.rotationDegrees)
    .offsetBy(crop.left, crop.top)

if (rect.width <= 0 || rect.height <= 0) {
    Log.w("ScannerFocus", "degenerate crop rect ($rect) for ...")
    return decoded
}
```
`guideFrameImageRect`'s call is **not** inside the `runCatching` block below it (that only wraps
`Bitmap.createBitmap`). Inside `guideFrameImageRect` (`ScannerScreen.kt`):
```kotlin
val left = corners.minOf { it.first }.coerceIn(0, rawWidth - 1)
```
If `cropWidth` is ever `0` (i.e. `crop.right == crop.left` — a degenerate `ViewPort`-derived
`cropRect`), `rawWidth - 1` is `-1` and `coerceIn(0, -1)` throws
`IllegalArgumentException: Cannot coerce value to an empty range` — the exact failure signature
`changes.md` documents hitting during test-writing. Pre-fix, this call always received
`ImageProxy.width`/`.height` directly, which are never zero for a real captured frame, so this
throw path was previously unreachable; deriving the dimensions from `cropRect` instead makes the
degenerate-input case newly reachable, and it now fires *before* the `rect.width <= 0` guard that
exists specifically to handle degenerate rects gracefully (uncropped fallback).

**Impact, contained:** `ScannerViewModel.kt`'s `processImage` wraps the whole call in
`catch (e: Exception) { _state.value = ScannerState.Error(e.message ?: "Scan failed") }` (line 90),
so this can't crash the app — it surfaces as a cryptic `ScannerState.Error` instead of the intended
graceful "uncropped fallback" behavior the `rect.width <= 0` check exists for. Likelihood is low: a
real `ViewPort` on a laid-out `PreviewView` should never compute a zero-area `cropRect` under normal
CameraX operation, and no test in this diff exercises a zero-width/zero-height `cropRect` input.
Not a spec violation (not covered by any acceptance criterion) and not a regression from working
behavior (this exact input was structurally impossible before). **Recommend: defer** — log as a
`gaps.md` follow-up (one-line guard, e.g. an early return when `cropWidth <= 0 || cropHeight <= 0`
before calling `guideFrameImageRect`), not a blocker for this diff.

## What looks good

- Field-arithmetic-only access to `Rect` (never `.width()`/`.height()`) is followed consistently in
  both production code and the new test fixture, per the spec's own stub-jar risk callout.
- Backward-compat no-op case is proven at two levels: a dedicated pure-function test
  (`offsetBy(0,0)` equality) and the pre-existing `ImageProxyExtTest` suite continuing to pass
  unmodified in expected output.
- Nonzero-offset test case is independently hand-verifiable, not just asserted: `cropRect(100,200,
  1100,2200)` → `cropWidth=1000, cropHeight=2000` → `guideFrameImageRect(1000,2000,0)` computes
  `guideW=(1000*0.32f).toInt()=320`, `guideH=(320*88f/63f).toInt()=446`, `dLeft=340`, `dTop=777` →
  offset by `(100,200)` → `Bitmap.createBitmap(decoded, 440, 977, 320, 446)`, matching the test's
  asserted call exactly — re-derived independently during this review, not just re-quoted.
- Fallback/error handling unchanged in shape: both `runCatching` blocks around the bind calls are
  untouched, only the inner call swapped for the new helper.
- Evidence in `.pipeline/evidence/changes-verify.log` is real, not asserted: `testDebugUnitTest
  --rerun-tasks` → `BUILD SUCCESSFUL in 1m 49s`, fresh XML sum `total tests: 289 failures: 0 errors:
  0` (286 baseline + 3 new, arithmetic checks out); `assembleDebug --rerun-tasks` → `BUILD
  SUCCESSFUL in 1m 29s`; `lintDebug --rerun-tasks` → `BUILD SUCCESSFUL`, `lint-results-debug.xml`
  error count `0`.
- Test-results.md's documented Tester BLOCKED/orchestrator-PASS history reviewed per instructions —
  not re-litigated; independently spot-checked the underlying evidence log myself rather than
  taking either report at face value, and it corroborates the PASS claim with real numbers.

## Score

| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | 25/25 | All 9 acceptance criteria traced to actual diff content with evidence; zero scope creep; out-of-scope items (`detectCardInFrame`, real-device calibration) correctly left untouched. |
| Correctness & bug-freedom | 22/25 | One real P2 edge case (degenerate-`cropRect` throw bypasses the intended graceful-fallback guard) — verified by reading code, not speculation, but low likelihood and already contained by an outer `catch(Exception)` (no crash). Deducted 3. |
| Security & reliability | 18/20 | No security surface. Reliability generally strong (defensive `runCatching`/fallback shapes preserved); same P2 above costs 2 — a genuinely new (if narrow) failure path that skips the file's own degenerate-rect fallback design. |
| Maintainability & simplification | 15/15 | Reuses `ImageRect`, matches existing file conventions (comment density, `Log.i` pattern), `testRect` fixture shared across 3 test files rather than duplicated, no unneeded abstraction. |
| Test evidence quality | 14/15 | Verbatim, independently-checkable evidence (fresh XML counts, hand-verified math, lint error count) — not just "tests passed." Minor deduction: no test covers the zero-area-`cropRect` edge case above. |
| **Total** | **94/100** | |

## Verdict: SHIP

No P0/blocking finding. Score clears the ≥85 bar. The one P2 finding is a narrow, low-likelihood
edge case already contained by existing outer error handling (surfaces as a scan error, not a
crash or data-corruption) — recommend logging it to `gaps.md` as a cheap one-line follow-up guard
rather than blocking this fix on it.
