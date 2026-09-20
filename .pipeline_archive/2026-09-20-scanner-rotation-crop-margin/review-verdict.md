# Code Review: Scanner capture — rotation fix + guide-frame border/margin

**Reviewer:** Claude (review-overall, single-lens)
**Date:** 2026-09-20
**Scope:** `ImageProxyExt.kt`, `ScannerScreen.kt`, `ImageProxyExtTest.kt`, `ScannerScreenTest.kt` (131 insertions/53 deletions, 4 files) — below the 15-file/800-line multi-lens trigger, single-lens review is correct. `gaps.md` also shows modified in `git status` but its diff is pre-existing session-16 hash-margin data untouched this session (confirmed via `git diff -- gaps.md` — content matches the session-16 Furfrou/Castform table, not scanner-geometry related) — out of scope, no action needed.

---

## Intent audit (Phase 2)

Both bugs from `specs.md` map to concrete diff hunks with no scope creep:
- Bug 1 (rotation) → `ImageProxyExt.kt` `toCroppedBitmap()`, exactly the spec's snippet.
- Bug 2 (border + margin) → `ScannerScreen.kt` `CROP_MARGIN_FACTOR` const + `guideFrameImageRect()` + `CardFrameOverlay`'s `drawRect`.
- TEMP diagnostic removal → confirmed clean (see Findings).
- Out-of-scope items (`GUIDE_FRAME_WIDTH_RATIO`, hash-margin pipeline, `detectCardInFrame` de-dup) genuinely untouched — grepped, zero hits.

**Clean. No drift, no missing requirement.**

---

## Summary

Small, surgical, unusually well-verified diff. I independently re-derived the `guideFrameImageRect` pixel-integer arithmetic by hand for all 4 rotations (not trusting the Coder's or spec's numbers) and it matched to the pixel in every case, including the rotation-90-vs-270 1px divergence the spec predicted. Read the actual code (not just descriptions) for both acceptance-criteria-sensitive claims (rotation-0 no-op, margin confinement) and both hold. One process-level gap: the Tester-stage evidence sidecar only archives the pre-fix *failure* run, not the claimed post-fix success — I closed that gap myself via the repo's actual JUnit XML/APK artifacts (below), so the underlying claim is true, but the archival practice itself should improve.

**Recommendation:** Ship

---

## Findings

### Verified correct (no findings — the four "verify carefully" items from the task)

1. **Rotation no-op at `rotation == 0` is provably a no-op.** `ImageProxyExt.kt`:
   ```kotlin
   val cropped = Bitmap.createBitmap(decoded, rect.left, rect.top, rect.width, rect.height)
   val rotation = imageInfo.rotationDegrees
   if (rotation == 0) {
       cropped
   } else { ... Matrix() ... }
   ```
   No `Matrix` is constructed on the `rotation == 0` branch — it returns the same `cropped` reference the pre-diff code returned directly, so it's byte-identical. Confirmed further by the new test asserting `verify(exactly = 0) { anyConstructed<Matrix>().postRotate(any()) }`.

2. **`CROP_MARGIN_FACTOR` confinement.** Grepped all 3 geometry functions in `ScannerScreen.kt`: `detectCardInFrame()` (lines 83-150) computes `dLeft/dTop/dRight/dBot` straight from `guideW/guideH` with no `CROP_MARGIN_FACTOR` reference; `CardFrameOverlay` draws `size = Size(guideW, guideH)` (undilated); only `guideFrameImageRect()` has `val boxW = (guideW * CROP_MARGIN_FACTOR).toInt()` / `val boxH = (guideH * CROP_MARGIN_FACTOR).toInt()` feeding `dLeft/dTop/dRight/dBot`. Confirmed by reading all three call sites, not just the diff hunks.

3. **Pixel-literal re-derivation, independently, by hand** (not copied from spec or Coder): for `guideFrameImageRect(4000, 3000, rotationDegrees)`, working through `guideW=(dispW*0.75).toInt()`, `boxW/boxH=guideW/guideH*1.10`, `dLeft=(dispW-boxW)/2` etc., then the 4-corner rotation remap and `coerceIn` clamps:
   - rotation 0: `left=350, right=3650, width=3300` (top/bottom saturated 0/3000) — matches `ScannerScreenTest.kt`'s `assertEquals(350, rect.left)` etc.
   - rotation 90: `left=272, top=263, right=3728, bottom=2738` — matches.
   - rotation 270: `left=272, top=262, right=3728, bottom=2737` — matches, and **does** differ from rotation 90 by exactly 1px on `top`/`bottom` as the spec's own reasoning predicted (traced through `toImage()`'s corner mapping: the two rotations hit different quadrants of the odd `boxH=3456` split). Not a coincidence — verified via the actual corner-remap arithmetic, not just "both numbers matched so it's fine."
   - rotation 180: `left=350, right=3650` (top/bottom saturated) — matches.
   - Also independently derived `ImageProxyExtTest.kt`'s `guideFrameImageRect(1000, 2000, 0)` → `(87, 424, 825, 1151)` and the 90°-rotation case → `(0, 175, 1000, 1650)` — both match the test file's literals and inline comments exactly.

4. **TEMP diagnostic removals.** `ImageProxyExt.kt` no longer has the `cropRect=...` `Log.i` block; the permanent `Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")` (line 27) is untouched and outside any diff hunk. `ScannerScreen.kt`'s `CardFrameOverlay` is back to plain `Box(modifier = modifier)` (line 237, no `onSizeChanged` wrapper). `ScannerViewModel.kt` (the "2026-09-11" block) isn't in this diff's file list at all (`git diff --stat` confirms only the 4 scanner files + unrelated `gaps.md`). All consistent with the changes.md explanation that the TEMP blocks were themselves uncommitted prior-session additions, so removing them nets to zero diff against HEAD in those specific hunks — verified via `git show HEAD:...ScannerScreen.kt | grep onSizeChanged` returning nothing, i.e. HEAD never had it either.

### Minor (P3) — test-evidence archival gap

- `.pipeline/evidence/test-results.log` (the Tester-stage sidecar) is UTF-16LE and, when decoded, contains **only the pre-fix failure run** (`e: ...ImageProxyExtTest.kt:10:17 Unresolved reference 'anyConstructed'` … `BUILD FAILED in 1m 25s` … `EXITCODE=1`). The claimed post-fix decisive evidence — "305/305 passing, fresh XML" and `assembleDebug`'s `BUILD SUCCESSFUL` — exists only as inline prose/excerpts in `test-results.md`, not archived to a sidecar log, which `test-results.md` itself discloses ("not separately archived to a sidecar this pass"). I independently corroborated the claim myself against the actual build artifacts rather than taking the prose on faith:
  ```
  $ grep "<testsuite " app/build/test-results/testDebugUnitTest/*.xml | ... aggregate
  tests=305 failures=0 errors=0 skipped=0
  ```
  `TEST-...ImageProxyExtTest.xml`: `tests="7"` (5 pre-existing + 2 new), `failures="0" errors="0"`, timestamp `2026-09-20T03:11:39`. `TEST-...ScannerScreenTest.xml`: `tests="13"`, `failures="0" errors="0"`, timestamp `2026-09-20T03:11:42`. `app-debug.apk` freshly built `Sep 20 11:13` same session. Claim holds — but the archival gap (disclosed, not hidden, so not a trust violation) means a future auditor without live repo access would have had to take test-results.md's word for the decisive run. Not blocking; worth a lesson for future Tester passes to `Tee-Object` the full post-fix run too, not just the pre-fix failure.

**No other findings.** No P0/P1/P2 issues found in correctness, security, performance, reliability, or maintainability after reading the actual code for every claim in the task brief.

---

## What looks good

- Genuine root-cause fixes for both bugs, diagnosed with real device evidence (per spec/changes.md), not speculative.
- `drawRect` replacing 8 `drawLine` calls is a real simplification, not just equivalent-but-different (also correctly dropped the now-unused `StrokeCap` import).
- Test additions (`rotation 0 constructs no Matrix`, `nonzero rotation rotates via Matrix postRotate`) verify actual behavior via `assertSame`/`verify(exactly=0)`, not just "didn't throw."
- `CROP_MARGIN_FACTOR`'s doc comment explicitly states what it does NOT affect — matches what the code actually does, checked above.
- No new dependencies, no new abstractions — matches ladder-appropriate scope for a 2-bug surgical fix.

---

## Dimension scorecard

| Dimension | Pass | Issues |
|---|---|---|
| Correctness | ✅ | None — hand-verified arithmetic and no-op path |
| Security | ✅ | N/A — no user input/network surface touched |
| Performance | ✅ | Rotation only on already-small cropped bitmap, per spec's own risk analysis |
| Reliability | ✅ | Existing `runCatching`/`getOrElse` fallback still wraps the new code |
| Maintainability | ✅ | Named constant, documented, net simplification |

---

## Score

- **Spec compliance (25/25):** Every acceptance criterion traced to a concrete diff hunk; TEMP-block removal scope confirmed exact (3 removed, 1 correctly untouched); no scope creep (`gaps.md`'s diff predates this session).
- **Correctness & bug-freedom (25/25):** Zero confirmed bugs after independent hand re-derivation of all changed pixel literals across all 4 rotations and both test files; rotation no-op path verified in code and by test.
- **Security & reliability (20/20):** No security surface; reliability fallback path unchanged and still covers the new rotation code.
- **Maintainability & simplification (15/15):** `drawRect` simplification, documented constant, unused-import cleanup, no new abstractions.
- **Test evidence quality (12/15):** Tests genuinely exercise the claimed behavior (not just "passed"), and I independently corroborated the 305/305 claim via live JUnit XML + APK timestamps — but the sidecar convention for this pass archived only the pre-fix failure, not the decisive post-fix success run. -3 for the archival gap.

**Total: 97/100.** Ship (≥85 threshold met, no unresolved P0/blocking finding).

---

## Sign-off

**Approval status:** Approved
**Date:** 2026-09-20

Outstanding, not blocking: the spec's own human-in-the-loop live-scan gate (Skyler + connected `SM-S948B`, post-Reviewer per spec) — correctly out of this stage's scope.
