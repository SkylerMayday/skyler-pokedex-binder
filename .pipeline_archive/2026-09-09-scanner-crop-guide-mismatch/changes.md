# Changes: Scanner crop-rect-vs-guide-box geometry fix

Spec: `.pipeline/specs.md`. No `.pipeline/review-verdict.md` existed at start — first pass, not a
re-invocation.

## Summary

Two independent, additive fixes, exactly as specced — no scope creep, `detectCardInFrame`'s live
sampling geometry untouched (grep-confirmed below).

1. **Shared field of view** (`ScannerScreen.kt`): local `bindPreviewCaptureAnalysis` helper builds
   a `UseCaseGroup` with `previewView.viewPort` as the shared `ViewPort` when non-null, falling
   back to today's plain vararg `bindToLifecycle` when it's still null (view not yet laid out).
   Both bind call sites (~676, ~704 pre-fix) now call this helper instead of inlining
   `bindToLifecycle`.
2. **Crop against `cropRect`, not the full frame** (`ImageProxyExt.kt`): `toCroppedBitmap()` now
   reads `cropRect`'s width/height via field arithmetic (not `.width()`/`.height()` methods),
   computes `guideFrameImageRect(cropWidth, cropHeight, rotation)`, and repositions it into the
   decoded bitmap's coordinate space via a new `ImageRect.offsetBy(deltaLeft, deltaTop)` pure
   function (`ScannerScreen.kt`, alongside `guideFrameImageRect`/`ImageRect`).

## Files changed

- **`app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`** — added
  `bindPreviewCaptureAnalysis` closure (inside `CameraPreview`'s `AndroidView` factory, right
  after `val provider = future.get()`) and `ImageRect.offsetBy`. Both bind call sites now call the
  helper. One `Log.i("ScannerFocus", ...)` line per branch (group-vs-fallback), matching this
  file's existing logging density — the spec's P1 recommendation.
- **`app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt`** —
  `toCroppedBitmap()` now derives `cropWidth`/`cropHeight` from `cropRect`'s fields and offsets the
  computed guide rect by `crop.left`/`crop.top` before cropping. No-`ViewPort` case is
  byte-identical to pre-fix code (`cropRect` defaults to `Rect(0,0,width,height)`,
  `offsetBy(0,0)` is a no-op).
- **`ScannerScreenTest.kt`** — 2 new tests for `ImageRect.offsetBy` (zero-delta no-op, nonzero
  delta shifts all 4 bounds and preserves width/height).
- **`ImageProxyExtTest.kt`** — `jpegImageProxy` fixture now stubs `cropRect` (default full-frame,
  keeping all 3 pre-existing tests on the no-op path); added 1 new test for the nonzero-offset
  case (1200x2400 frame, 1000x2000 crop at (100,200) → expects `Bitmap.createBitmap(decoded, 440,
  977, 320, 446)`, hand-computed from `guideFrameImageRect(1000,2000,0)` + the offset).
- **`P0_2_FreshVerificationTest.kt`**, **`ScannerFocusIndependentVerificationTest.kt`** — both
  call `toCroppedBitmap()` on a mocked `ImageProxy` that didn't stub `cropRect`; added the stub
  (full-frame) so these pre-existing, unrelated tests keep passing against the new code path.
- **`RectTestFixture.kt` (new)** — shared by all 3 test files above. See "Real, unplanned finding"
  below for why this exists.

## Real, unplanned finding: `Rect`'s constructor silently zeroes under this stub jar

The spec's own Open Questions flagged this as unconfirmed and pre-authorized an escalation path if
the plain `Rect(l,t,r,b)` constructor "throws." It doesn't throw — worse, it silently succeeds
with every field left at its JVM-default `0`, discovered only because the resulting
`cropWidth=0`/`cropHeight=0` tripped `guideFrameImageRect`'s own `coerceIn(0, rawWidth - 1)` guard
at test runtime (`IllegalArgumentException: Cannot coerce value to an empty range: maximum -1 is
less than minimum 0`), reproduced on the first real test run (see sidecar log, first `testDebugUnitTest`
block). Fixed per the spec's own pre-authorized escalation shape (mirrors
`FocalLengthsKeyTestFixture`'s existing precedent, though simpler — public instance fields need
only a direct assignment, not `sun.misc.Unsafe`): `RectTestFixture.kt`'s `testRect(l,t,r,b)`
builds a blank `Rect()` then sets `left`/`top`/`right`/`bottom` directly (PUTFIELD, not a
stubbable method call — same safety argument production code already relies on). All 4 test files
that construct a `Rect` for a mocked `ImageProxy.cropRect` use it. Not scope creep — the spec's own
acceptance criteria require a working nonzero-offset test case, which needed this to be reachable
at all.

## Simplify & review pass

- **Reuse**: `offsetBy` reuses `ImageRect`'s existing shape; `bindPreviewCaptureAnalysis` reuses
  the existing `provider`/`lifecycleOwner`/`previewView` already in closure scope, no new state.
  `testRect` is shared across 3 test files rather than duplicated (mirrors the project's own
  `FocalLengthsKeyTestFixture` precedent for shared test-only Android-stub workarounds).
- **Quality**: no nested-conditional growth, no new parameters beyond what the spec's own code
  sketch specified. Diff matches the spec's proposed code near-verbatim (variable-name-level only
  differences).
- **Efficiency**: `bindPreviewCaptureAnalysis` doesn't change any hot-path — it's a one-time bind.
  `offsetBy`/`cropRect` field reads are O(1), no new allocations beyond one `ImageRect` (was
  already being allocated pre-fix).
- **Correctness**: verified the no-`ViewPort` / full-frame path is a true no-op via both a
  dedicated pure-function test (`offsetBy` zero-delta) and the pre-existing `ImageProxyExtTest`
  suite continuing to pass unmodified in behavior (same expected `Bitmap.createBitmap` args as
  before, cropRect stub just makes the now-required field read resolvable).
- `detectCardInFrame` grep-confirmed still reads `imageProxy.width`/`.height` directly (line 69-70
  in `ScannerScreen.kt`), never touched.

No fixes needed beyond the Rect-stub-jar test-infra finding above — the production code matches
the spec's own pre-vetted sketch too closely to have introduced new issues.

## Verification (7 checks)

Full verbatim output: `.pipeline/evidence/changes-verify.log` (236 lines — `testDebugUnitTest`
initial-failure run, `testDebugUnitTest` clean re-run, `assembleDebug`, `lintDebug`, git status,
fresh-XML test totals, lint XML error count).

1. **Typecheck** — `compileDebugKotlin` (part of both `testDebugUnitTest` and `assembleDebug`
   runs below): PASS, only 2 pre-existing warnings unrelated to this diff (`LocalLifecycleOwner`
   deprecation at the now-shifted line 571, `SlotDetailViewModel.kt` opt-in) — zero new warnings
   on either touched file, confirmed by comparing against the diff.
2. **Lint** — `.\gradlew.bat lintDebug --rerun-tasks`: `BUILD SUCCESSFUL`, `severity="Error"`
   count in `lint-results-debug.xml` = **0**, zero mentions of `ScannerScreen.kt`/
   `ImageProxyExt.kt` in the report at all.
3. **Scoped tests** — `.\gradlew.bat testDebugUnitTest --rerun-tasks` (stale
   `app/build/test-results/testDebugUnitTest/` deleted first per this repo's own lesson). First
   run caught the real `Rect`-stub-jar finding above (7 failures, all `IllegalArgumentException`
   from `guideFrameImageRect`'s `coerceIn`, not a false start — a genuine bug in my own test
   fixtures). After the `testRect` fix, clean re-run:
   ```
   BUILD SUCCESSFUL in 1m 49s
   31 actionable tasks: 31 executed
   ```
   Fresh XML totals (summed across all `TEST-*.xml`, not Gradle's own summary line, per this
   repo's "delete stale results dir" lesson): **289 tests, 0 failures, 0 errors** — exactly the
   spec's stated baseline (286) + 3 new tests (2 `offsetBy` + 1 nonzero-cropRect-offset).
4. **Production build** — `.\gradlew.bat assembleDebug --rerun-tasks`: `BUILD SUCCESSFUL in 1m 29s`,
   41/41 tasks executed (forced fresh, no `UP-TO-DATE` results).
5. **Dev/start server** — N/A. Android app, no dev server; `assembleDebug` (check 4) is this
   project's closest equivalent and already covers compile/package correctness.
6. **Stray debug statements / scratch files** — none. The 2 new `Log.i("ScannerFocus", ...)` lines
   are intentional (spec's own P1 recommendation, matching this file's existing logging
   convention/density — every other branch in this function already logs). No `console.log`/
   `debugger`/scratch files in the diff; scratch `.ps1` scripts used to invoke Gradle live only in
   the session scratchpad, never in the repo.
7. **git status** — matches the diff exactly: 6 modified files (2 main, 4 test) + 1 new test
   fixture (`RectTestFixture.kt`). One unrelated untracked directory
   (`.pipeline_archive/2026-09-08-scanner-macro-focus/`) pre-dates this session (files timestamped
   2026-09-08, one day before today) — not created or touched by this pass.

## Acceptance criteria — status

All 9 checked, including the two pure-`offsetBy` criteria and the `detectCardInFrame` grep-confirm.
Real-device confirmation explicitly out of scope per spec (no multi-camera hardware in this
sandbox) — flagged for Skyler's next real S26 Ultra pass, consistent with every prior attempt in
this bug family.

## Friction Notes

- Serena's Kotlin language server is still broken this session (`Error extracting archive`,
  identical to the friction note already logged in `.pipeline/specs.md`) — confirmed again via a
  direct `get_symbols_overview` call before falling back to Read/Grep/Edit for the whole pass.
  Second independent confirmation this same condition persists; still worth checking the cached
  language-server archive outside a task.
- The spec's own Open Question about `Rect` construction under the stub jar predicted the right
  *shape* of risk (escalate to reflective/direct field-set) but the actual failure mode was
  "succeeds silently with zeroed fields," not "throws" — worth generalizing the lesson beyond
  `Rect` specifically: an Android-stub-jar constructor call not throwing is not proof it worked:
  a first real test run against the concrete class is what caught the difference here.
