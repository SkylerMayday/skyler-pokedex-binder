# Review Verdict (aggregated, PASS 2) — Scanner Ultrawide Lens Selection + Guide-Frame Crop

**Aggregation:** orchestrator-assembled from 3 parallel lens re-reviews, iteration 2 of 2 (the
pipeline's auto-loop cap — this is Reviewer evaluation #2 of the 3 allowed total).

- `.pipeline/review-correctness.md` — **needs-changes, 72/100** (was 34, +38)
- `.pipeline/review-security.md` — **needs-changes (numeric gate) / no P0, no blocking defect, 79/100** (was 43, +36)
- `.pipeline/review-maintainability.md` — **ship, 87/100** (was 86, +1)

**Aggregate score = min(72, 79, 87) = 72/100.** No lens used the literal verdict `reject`. Aggregate
verdict: **needs-changes**.

---

## Both iteration-1 P0s are genuinely fixed — independently re-derived by all three lenses, not trusted

All three lenses re-extracted the real CameraX 1.6.1 sources themselves this pass (not reusing each
other's or the Coder's citations) and independently confirmed:

- **P0-1's functional half is fixed.** `Camera2Interop.Extender(builder).setPhysicalCameraId(id)`
  applied to all three use-case builders is genuinely consumed by CameraX 1.6.1 — traced end-to-end
  through `Camera2Interop.kt` → `CameraUseCaseAdapter.kt` → `CameraGraphConfigProvider.kt`, and (the
  correctness lens went one step further) disassembled the `camera-camera2-pipe` AAR directly since
  no sources exist for the final link, landing on the real framework
  `OutputConfiguration.setPhysicalCameraId` call. This is a live path, categorically different from
  the dead `CameraSelector.physicalCameraId` route the original P0-1 used. The `@RequiresApi(28)` vs.
  `minSdk 26` guard and the same-id-on-all-three-builders constraint are both correctly and
  structurally enforced (verified directly, not assumed).
- **P0-2 is fixed, structurally.** `ImageProxy.toCroppedBitmap()` now decodes via CameraX's own
  JPEG-safe `toBitmap()` member; the dead NV21 decode is gone by name and by body
  (`grep -rn "fun ImageProxy.toBitmap" app/src/main` returns nothing) — the shadowing bug class is
  now structurally impossible to repeat, not merely avoided by convention.
- **Important #1 (unwrapped fallback bind) — fully resolved**, including the two-levels-deep case
  both the security and correctness lenses specifically re-traced (a throwing fallback now yields
  `camera = null`, safely no-op'd downstream; no path to an uncaught throwable on the main Looper).
- **Important #2 (missing second `unbindAll()`) — present, and mostly effective**, though the
  security lens corrected its own iteration-1 severity here: the call is key-scoped and is a no-op in
  the exact first-bind-fails scenario it targets (the key is only registered *after* a successful
  bind), but the state-corruption risk it was meant to close is smaller than originally scored — one
  field, invisible to every consumer, self-heals on the next unbind. Downgraded from Important to
  Minor by the lens that raised it originally.

## One new P0, found independently by two lenses — the diagnostic fix inverted the failure direction

**`boundVia` compares a physical camera id against a logical camera id — structurally, by Android's
own API contract, these can never be equal.** Both the correctness lens and the security lens found
this independently, via different source-tracing paths, converging on the identical root cause:

```kotlin
private fun actualBoundCameraId(camera: Camera): String? =
    runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }.getOrNull()
...
actualId == ultrawideId -> "ultrawide-physical (confirmed id=$actualId)"
else -> "ultrawide-physical-MISMATCH (requested id=$ultrawideId, actually bound id=$actualId ...)"
```

`camera.cameraInfo.cameraId` (from a `DEFAULT_BACK_CAMERA` bind) is always the **logical** camera's
id. `ultrawideId` is sourced from `getPhysicalCameraInfos()` — by definition a *different*, physical
camera id. The comparison is unsatisfiable, so the `when` falls to the `MISMATCH` branch on **every
device**, including one where the physical-camera-id stream binding worked perfectly. This is
iteration 1's defect with the sign flipped: instead of always claiming success, the log now always
claims failure. Since this exact log line is the spec's sole named on-device confirmation signal
(`specs.md`'s Success Metric: *"`boundVia=ultrawide-physical` on Skyler's device — confirms the
selection path actually engaged"*), a real-device test of a working fix would read `MISMATCH` and
could send the next debugging round down a false trail — or worse, cause the working fix to be
reverted via the spec's own stated escape hatch.

**Severity note, both lenses agree:** this does not break the shipped feature's actual behavior — the
physical-camera binding itself is correct. It breaks the *verification signal* for a bug family
already 6 attempts deep, which both lenses treat as serious enough to block on, but both also
independently characterize as small and well-scoped (~5-15 lines, one function, no architecture
change). The security lens's explicit framing: *"If the loop is out of iterations, my recommendation
is ship with Important #1 fixed inline... it is one edit away from being impossible [to misread]."*

**Suggested fix, both lenses converge on the same shape:** stop asserting a pass/fail verdict from a
comparison that structurally cannot succeed. Either (a) log both ids honestly without a judgment
verdict (e.g. *"requested physical id=X; bound logical camera id=Y (expected to differ — a physical
id does not change the logical id)"*), or (b) get a genuine runtime readback via
`Camera2Interop.Extender.setSessionCaptureCallback` + `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`
(API 29, needs its own guard above the existing API-28 gate). Fix `logCameraAfCapabilities`'s
`minFocusDistance` source in the same edit — it also reads the logical camera, so it will report the
main lens's focus floor even when the ultrawide is genuinely bound.

## Small additional findings surfaced this pass (non-blocking, cheap, worth folding in)

- **The success string is now a substring of the failure string** (maintainability lens) —
  `"ultrawide-physical"` is a literal prefix of `"ultrawide-physical-MISMATCH"`. Even after the above
  fix changes the *values*, a naive `grep boundVia=ultrawide-physical` (the spec's own suggested
  on-device check) would match both outcomes. Fix in the same edit as the P0.
- **The spec-mandated `captured format=... planes=...` diagnostic was silently dropped** (both
  security and maintainability lenses independently flagged this) — `changes.md`'s "Deliberately NOT
  touched" list doesn't mention its removal. Defensible (the Open Question it existed to answer was
  definitively closed by this pass's own P0-2 investigation) but should either be restored (cheap,
  and this project's whole on-device debugging loop is this logcat tag) or explicitly noted as
  deliberately obsolete.
- **A new, milder version of the same trap P0-2 just eliminated** (maintainability lens) —
  `toCroppedBitmap(cropToGuideFrame: Boolean = false)`'s default now silently returns an *uncropped*
  bitmap despite the function's name, and no production caller ever passes `false`. Two-line fix:
  drop the now-vestigial parameter entirely.
- Carried, still open, still correctly out of scope for this pass: the ~1.4× crop-size mismatch vs.
  the drawn guide box, the stale "~9 in (23cm)" text, the 1px rotation off-by-one, the `88f/63f`
  triplication, the `20f` threshold-shadowing, the duplicated `sun.misc.Unsafe` test fixture (now
  duplicated across the same two files, not a new third copy).

---

## Aggregated Score (iteration 2)

| Dimension | Correctness lens | Security lens | Maintainability lens |
|---|---|---|---|
| Spec compliance | 20/25 | 20/25 | 23/25 |
| Correctness & bug-freedom | 15/25 | 21/25 | 23/25 |
| Security & reliability | 15/20 | 15/20 | 19/20 |
| Maintainability & simplification | 11/15 | 12/15 | 9/15 |
| Test evidence quality | 11/15 | 11/15 | 13/15 |
| **Lens total** | **72** | **79** | **87** |

**Aggregate score: 72/100** (min of the three). Ship gate not met (< 85, one unresolved P0).

**Score history across this pipeline run: 34 → 72.** This is the pipeline's loop iteration 1 of 2
complete (Reviewer evaluation #2 of the 3 allowed). One more loop iteration (Coder fix → Tester →
Reviewer evaluation #3) remains within the cap.

---

## Verdict: **needs-changes** — proceeding with the final allowed auto-loop iteration

Not a reject, per all three lenses' own explicit framing — the security lens goes as far as saying
this lens has *"no P0 and no blocking security/reliability defect"* and would ship with the fix
applied inline. The remaining P0 is diagnostic-only (doesn't affect the feature's actual runtime
behavior, only whether the verification log can be trusted), small, and both lenses that found it
independently converged on the same fix shape. Given the cap allows one more iteration and the fix is
well-understood and narrow, looping once more to close it cleanly rather than stopping short.

**Blocking before ship (one item):**
1. Fix `boundVia`'s comparison — stop asserting an outcome the code structurally cannot observe from
   a logical-vs-physical id comparison. Fix `logCameraAfCapabilities`'s `minFocusDistance` source in
   the same edit (same root cause). Break the success/failure string-prefix collision.

**Cheap, fold in if scope allows:**
2. Restore the spec-mandated `captured format=... planes=...` log line, or note its removal
   explicitly in `changes.md`.
3. Drop `toCroppedBitmap`'s now-vestigial `cropToGuideFrame` parameter (2 lines, deletes 2 tests that
   exercise a value production never passes).

**Not blocking, explicitly deferred, do not touch this pass:** the crop-size mismatch, stale distance
text, rotation off-by-one, `88f/63f` triplication, `20f` shadowing, duplicated test fixture — all
confirmed genuinely untouched by this pass, all still correctly out of scope.

**Process note, unchanged from iteration 1:** real on-device confirmation
(`adb logcat -s ScannerFocus:*`) remains the only way to close the final gap (does the S26 Ultra's
HAL actually honor the physical-camera-id option for this stream configuration) — nothing further is
reachable from the JVM. Once the `boundVia` fix lands, that log line becomes trustworthy for the
first time in this bug family's 6-attempt history.
