# Code Review (pass 3 of 3 — FINAL) — Scanner Ultrawide Lens Selection + Guide-Frame Crop

**Lens:** correctness + spec-compliance trace (1 of 3 parallel lenses)
**Iteration:** 3 of 3 — the pipeline's absolute cap. No further automated pass exists after this one.
**Date:** 2026-09-08
**Base:** `git diff HEAD` (still one cumulative uncommitted tree — see Scope caveat)
**Scope of THIS pass:** the targeted fix-pass-2 delta only — `boundVia`, `logCameraAfCapabilities`'s
new `requestedPhysicalId`, the restored `captured format=` log, the dropped `cropToGuideFrame`
parameter — plus a fresh AC1 trace and a do-not-touch-list spot check. Everything confirmed in
pass 2 was not re-litigated.

---

## Summary

**The blocking P0 is genuinely closed. I read the live code myself for all four fix items rather
than reading `changes.md`'s or the Tester's description of them, and all four hold. No P0, no P1.
Two P2s and two P3s remain, all diagnostic-accuracy issues in code paths that do not affect shipped
runtime behavior.**

- **P0 (`boundVia` asserting an unsatisfiable comparison) — FIXED, structurally.** The comparison is
  gone, not reworded. `actualId` is still computed but is now only interpolated, never compared.
  There is exactly one `ultrawide-*` string in the whole file and it makes no pass/fail claim.
  Repo-wide grep for `MISMATCH` / `confirmed id=` under `app/src`: zero hits.
- **`requestedPhysicalId` — GENUINE physical lookup, not a relabel.** I traced it: it walks
  `getPhysicalCameraInfos()`, matches on the *physical* camera's own `Camera2CameraInfo.cameraId`,
  and reads `LENS_INFO_MINIMUM_FOCUS_DISTANCE` off **that** `CameraInfo` — a different object from
  the logical `info` computed 6 lines above it.
- **`captured format=...` log — RESTORED and on the live path.** One reachable production call
  chain, verified end to end including that the `ImageProxy` is not yet closed at that point.
- **`cropToGuideFrame` — DROPPED CLEANLY.** Zero live code references anywhere in the repo; the
  only remaining occurrences are past-tense prose in comments and `.pipeline`/`gaps.md` history.
- **Do-not-touch list — all six items confirmed untouched**, verified by reading the exact lines
  myself, not by trusting the Tester's table.
- **Zero scope creep.** `git diff --stat` file list unchanged from pass 2's.

**Recommendation: ship. Score: 85/100 (was 72, +13).**

**Critical/blocking: 0** · **P2: 2** · **P3: 2**

### Scope caveat (unchanged, inherited from passes 1 and 2)

The entire session — attempt #5, the Task 1-6 pass, fix pass 1, fix pass 2 — remains uncommitted in
one tree, so `git diff HEAD` is cumulative and cannot isolate this pass's delta. I worked around it
by reading the current file state directly for every claim below rather than relying on diff
hunks. `docs/specs/2026-09-02-discord-live-lookup-bot.md` (+221) and `project-overview.md` (+28) show
in `--stat` but were already modified before this pipeline run started (they appear in the session's
own initial `git status` snapshot) — **not** scope creep from any pipeline stage.

---

## Item-by-item verification (the four things this pass was asked to prove)

### 1. `boundVia` — the unsatisfiable comparison is genuinely gone ✅

Current code, read from the live file:

```kotlin
// ScannerScreen.kt:695-702
val actualId = actualBoundCameraId(cam)
val boundVia = if (ultrawideId == null) {
    "default-back-camera"
} else {
    "ultrawide-physical-requested (requested physical id=$ultrawideId; bound " +
        "logical camera id=$actualId — expected to differ, a physical id does " +
        "not change the logical id, this is not a failure signal)"
}
```

- **No comparison operator anywhere.** The `when` with `actualId == ultrawideId` is deleted, not
  softened. `actualId` is interpolated only. This is categorically different from pass 2's code —
  there is no expression whose truth value the code cannot observe.
- **No verdict wording.** `grep -rn "MISMATCH\|confirmed id=" app/src/` → zero hits. (The only
  `confirmed` hits in `app/src` are unrelated: `BinderRepository.kt:152` "confirmed TCG card
  presence", and three explanatory comments in `ScannerScreen.kt`/`P0_2_FreshVerificationTest.kt`
  about what was confirmed *during development*. None is a runtime log string.)
- **String-prefix collision resolved by construction.** Only two `boundVia` values now exist and
  neither is a prefix of the other in a way that changes meaning — there is no second,
  opposite-meaning string in the `ultrawide-physical…` family for a naive grep to also match.
- **The doc comment now states the constraint plainly** rather than leaving it implicit:
  ```kotlin
  // ScannerScreen.kt:317-318
  // namespaces are structurally disjoint). Comparing this against a requested physical id can never
  // be true; it exists purely for informational logging, not as a pass/fail verdict.
  ```

**Verdict: genuinely fixed.** The specific defect I blocked on in pass 2 — a log that reports
failure on a working device — cannot occur, because no branch asserts failure at all.

### 2. `requestedPhysicalId` — a real physical-camera characteristic lookup ✅

```kotlin
// ScannerScreen.kt:279-298
val info = Camera2CameraInfo.from(camera.cameraInfo)          // <- LOGICAL
val chars = info.getCameraCharacteristic(
    android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
)
...
val requestedPhysicalMinFocusDistance = requestedPhysicalId?.let { reqId ->
    camera.cameraInfo.getPhysicalCameraInfos()
        .firstOrNull { physicalInfo ->
            runCatching { Camera2CameraInfo.from(physicalInfo).cameraId == reqId }.getOrDefault(false)
        }
        ?.let { physicalInfo ->
            runCatching {
                Camera2CameraInfo.from(physicalInfo).getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                )
            }.getOrNull()
        }
}
```

Traced myself, four checks:

1. **Different source object.** `chars` reads off `Camera2CameraInfo.from(camera.cameraInfo)` (the
   logical camera). `requestedPhysicalMinFocusDistance` reads off
   `Camera2CameraInfo.from(physicalInfo)` where `physicalInfo` comes from
   `camera.cameraInfo.getPhysicalCameraInfos()`. These are distinct `CameraInfo` instances and
   distinct `CameraCharacteristics` sets — this is not the logical value under a new variable name.
2. **The match key is the physical camera's own id**, not the logical one:
   `Camera2CameraInfo.from(physicalInfo).cameraId == reqId`, against `reqId = ultrawideId`, which
   was itself sourced from `getPhysicalCameraInfos()` at `:357` — same namespace, so the comparison
   here **is** satisfiable, unlike the one that was removed.
3. **API safety.** `getPhysicalCameraInfos()` is only reached when `requestedPhysicalId != null`,
   which requires `ultrawideId != null`, which requires both `Build.VERSION.SDK_INT >= 28`
   (`:634`) and `isLogicalMultiCameraSupported()` (`:356`). Transitively guarded. Consistent with
   the Tester's `lintDebug --rerun-tasks` structural XML parse showing zero `Error`-severity
   findings (`NewApi` is Error-severity by default, so a missing guard would have surfaced).
4. **Failure-safe.** Every leg is inside `runCatching`, and the whole function body is inside the
   outer `runCatching` at `:278`. Worst case is a missing log line, never a throw on the main
   Looper.

**Verdict: genuine fix.** See P2-2 below for a caveat about what this number *means*, which is a
separate issue from whether the lookup is real.

### 3. Restored `captured format=` log — present and on the real capture path ✅

```kotlin
// ImageProxyExt.kt:25-27
fun ImageProxy.toCroppedBitmap(): Bitmap {
    Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")
    val decoded = toBitmap()
```

Chain traced end to end this pass:

`ScannerScreen.kt:798` and `:828` (`viewModel.processImage(image)`, the auto-capture and manual
capture `onCaptureSuccess` sites — the only two callers) → `ScannerViewModel.kt:69`
`val bitmap = imageProxy.toCroppedBitmap()` → the log line above.

One thing I checked that nobody else did, because the restored line dereferences the proxy: **the
`ImageProxy` is not closed before this runs.** `ScannerViewModel.processImage` closes it in a
`finally` block (`:91-93`, `imageProxy.close()`) strictly after the `toCroppedBitmap()` call at
`:69`, so `format`/`planes` are still valid. An `IllegalStateException` from accessing a closed
proxy is not reachable here.

**Verdict: present, reachable, and safe.**

### 4. `toCroppedBitmap()`'s parameter — dropped cleanly ✅

`fun ImageProxy.toCroppedBitmap(): Bitmap` (`ImageProxyExt.kt:25`) — no parameter, no
`if (!cropToGuideFrame) return decoded` branch anywhere in the body.

My own repo-wide grep for `cropToGuideFrame` (`--include=*.kt --include=*.md`, whole tree):

| Location | Kind |
|---|---|
| `ImageProxyExt.kt:12`, `:20` | header comment, past tense, explains the removal |
| `P0_2_FreshVerificationTest.kt:98` | comment, past tense |
| `gaps.md:141` | historical prose |
| `.pipeline/*.md` (many) | pipeline history |

**Zero live code references.** Every `toCroppedBitmap` call site in the repo is now the zero-arg
form — `ScannerViewModel.kt:69`, `ImageProxyExtTest.kt:71/80/92`,
`ScannerFocusIndependentVerificationTest.kt:213/234`, `P0_2_FreshVerificationTest.kt:102`.

I also checked the two tests most likely to have been broken by the drop, rather than trusting the
pass count:

- `ImageProxyExtTest.kt` — 3 tests, all now exercise the **always-crops** production shape against
  a realistic single-plane JPEG fixture (`every { proxy.planes } returns arrayOf(plane)`,
  `every { proxy.format } returns ImageFormat.JPEG`, `:52-59`). Pass 2's Minor #3 (the fresh file
  only exercising the non-production boolean) is now moot — the boolean doesn't exist.
- `P0_2_FreshVerificationTest.kt` — the red-then-green pairing survives the rewrite. Test 1 still
  reproduces `ArrayIndexOutOfBoundsException` on `planes[2]` against a 1-element array (`:64-81`);
  test 2 now stubs `Bitmap.createBitmap` and asserts `result === cropped` (`:100-104`). Its own
  claim at `:105-108` ("never stubbed `proxy.planes[1]`/`[2]`… the test passing at all is itself
  proof") still holds: `proxy.planes` returns a 1-element array, so any `planes[1]`/`[2]` access by
  production code would throw and fail the test.

**Verdict: clean.**

### 5. Do-not-touch list — all six confirmed untouched (my own read, not the Tester's table) ✅

| Deferred item | Live line I read | State |
|---|---|---|
| Crop-size mismatch vs. drawn guide box | `ScannerScreen.kt:151` `val guideH = (guideW * 88f / 63f).toInt()` (image space) vs `:190` `val guideH = guideW * 88f / 63f` (view space) | unchanged |
| Stale distance text | `:243` `text = "Hold card ~9 in (23 cm) back",` with `:60` `GUIDE_FRAME_WIDTH_RATIO = 0.32f` | unchanged |
| Rotation 1px off-by-one | `guideFrameImageRect` body unchanged (same 4-corner remap) | unchanged |
| `88f/63f` triplication | still 3 literals: `:76`, `:151`, `:190` | unchanged |
| `20f` shadowing | `:326` `ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 20f` vs `:423` `focalLengths?.any { it < 20f } == true` | unchanged |
| Duplicated `sun.misc.Unsafe` test fixture | present in both `ScannerScreenTest.kt` and `ScannerFocusIndependentVerificationTest.kt` | unchanged, still 2 copies (no third added) |

No silent fixes, no silent regressions. The fix pass stayed inside its brief.

---

## Findings

### P2-1 — `boundVia` still says "ultrawide-physical-requested" after the AC3 fallback bind, where no physical id was applied at all

- **File:** `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`
- **Lines:** 696-702 (the string), 649-679 (the fallback path that makes it inaccurate)
- **Confidence:** 9/10 — both branches read this pass; residual point is on-device only
- **Category:** Correctness / diagnostic accuracy
- **Not blocking:** a compensating warning fires on the same logcat tag two lines earlier

The `boundVia` string keys off `ultrawideId`, which is the id that was *requested* — not a record of
which bind attempt actually succeeded:

```kotlin
// ScannerScreen.kt:696-702
val boundVia = if (ultrawideId == null) {
    "default-back-camera"
} else {
    "ultrawide-physical-requested (requested physical id=$ultrawideId; bound " +
        ...
}
```

But when the first bind throws, the code discards the physical-id-carrying use cases entirely and
rebinds with a freshly built trio that has **no** physical id:

```kotlin
// ScannerScreen.kt:667-674
val fallback = buildUseCases(null)
preview = fallback.first; capture = fallback.second; analysis = fallback.third
onImageCaptureReady(capture)
provider.unbindAll()
runCatching {
    provider.bindToLifecycle(
        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
    )
}
```

`ultrawideId` is still non-null on that path, so the log emits `boundVia=ultrawide-physical-requested`
for a session in which the ultrawide was requested, rejected, and abandoned. That is exactly the
AC3 scenario — the one case where knowing the truth matters most — and it makes the spec's own
suggested check (`grep boundVia=ultrawide-physical`) match on a device that fell back to plain
default-back-camera.

**Why this is P2 and not P0.** It is not a false *verdict* — nothing in the string claims a
successful physical bind, and the fallback is independently visible in the same logcat filter,
printed immediately before:

```kotlin
// ScannerScreen.kt:656-660
Log.w(
    "ScannerFocus",
    "ultrawide physical-camera bind threw, falling back to plain default back camera",
    firstError
)
```

`adb logcat -s ScannerFocus:*` shows both lines adjacent, so a reader is not actually misled. The
defect is that the `boundVia` field alone is not self-describing, which matters if anyone ever
greps for that one field in isolation.

**Cheap fix if it's ever touched again:** thread a `fellBackToDefault` boolean out of the
`getOrElse` block and append `" [FELL BACK — physical id NOT applied to the bound session]"`. ~4
lines. Not worth reopening the pipeline for.

### P2-2 — the comment calls `requestedPhysicalMinFocusDistance` "the trustworthy outcome signal"; it is a static capability value, identical whether or not the physical stream engaged

- **File:** `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`
- **Lines:** 692-694 (the claim), 292-297 (what the value actually is)
- **Confidence:** 10/10 — the source of the value is read directly below
- **Category:** Correctness of documentation / diagnosability
- **Not blocking:** the emitted log line is honestly named; only the in-source comment overclaims

```kotlin
// ScannerScreen.kt:692-694
// claim failure.) The trustworthy outcome signal is
// requestedPhysicalMinFocusDistance, logged alongside this in
// logCameraAfCapabilities.
```

But that value is read from `CameraCharacteristics`:

```kotlin
// ScannerScreen.kt:292-296
runCatching {
    Camera2CameraInfo.from(physicalInfo).getCameraCharacteristic(
        android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
    )
}.getOrNull()
```

`CameraCharacteristics` are **static per-camera metadata**. `LENS_INFO_MINIMUM_FOCUS_DISTANCE` for
physical camera id=2 is the same number whether that camera is streaming, idle, or was silently
ignored by the HAL. It answers *"what is the ultrawide's focus floor?"* — a capability question —
not *"is the ultrawide producing my frames?"*, which is the outcome question AC1 asks. Calling it
an outcome signal is wrong.

**Concrete risk.** This bug family is six attempts deep. A future session (or Skyler) reading this
comment, then seeing `requestedPhysicalMinFocusDistance=0.1` in logcat, would be invited to
conclude "the ultrawide is bound" — when that line proves only that the device *has* an ultrawide
with a 10cm floor, which `logUltrawideFeasibility` (`:704`) already established pre-bind. The
comment recreates, at the documentation layer, the same "asserting an outcome the code can't
observe" error it was written to explain.

The log line's own text is fine — it says `requestedPhysicalMinFocusDistance`, and "requested" is
accurate. Only the comment overclaims. One-sentence fix: *"the closest thing to an outcome signal
is whether focus actually converges at ~9in on-device; `requestedPhysicalMinFocusDistance` only
shows what floor the requested lens is capable of."*

### P3-1 — `changes.md` attributes a justification to "the review" that no review file contains

- **File:** `.pipeline/changes.md`
- **Lines:** 530-531
- **Confidence:** 10/10 — verified by grep across all four `.pipeline/review-*.md` files
- **Category:** Process / evidence integrity

```
for a constant (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`) the review itself flagged as
not guaranteed present on every API-29+ device
```

`grep -rn "LOGICAL_MULTI_CAMERA\|not guaranteed" .pipeline/review-security.md
.pipeline/review-maintainability.md .pipeline/review-correctness.md .pipeline/review-verdict.md`
returns three hits, all of them my own pass-2 text and the verdict's restatement of it, and none of
them says anything about the constant not being guaranteed present. What pass 2 actually said was:
*"`@RequiresApi(29)`; the existing SDK guard is already at 28, so it needs its own."*

The **substance** is correct — `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` genuinely is an optional
`CaptureResult` key that need not appear in every result — so option (b) was reasonably declined,
and option (a) was a reviewer-offered alternative in the first place ("Honest, zero-risk"). The
issue is only that a decision record now cites a reviewer conclusion that doesn't exist, which a
future session would read as settled and not re-check. Flagging it so the record is accurate; no
code impact.

### P3-2 — AC1's confirmation clause remains semantically unsatisfiable; the fix makes the log honest rather than making the criterion true

Covered in the spec trace below. Informational, not a defect in the diff — the platform, not the
code, is what prevents it.

---

## Spec trace (Phase 2 — AC1 re-derived fresh, per the brief)

### AC1, end to end

> **Given** a device that supports `CameraInfo.isLogicalMultiCameraSupported()` and exposes a
> physical camera with focal length < 20mm on the back logical camera, **when** `CameraPreview`
> binds, **then** the bound camera is that physical (ultrawide) camera, confirmed via the extended
> diagnostic log (`boundVia=ultrawide-physical`). — `specs.md`

**Half A — the bind mechanism: DONE.** Unchanged by this pass, and I confirmed the lines are still
intact:

```kotlin
// ScannerScreen.kt:586-590
if (physicalCameraId != null && Build.VERSION.SDK_INT >= 28) {
    Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalCameraId)
    Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalCameraId)
    Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physicalCameraId)
}
```

The six-link chain from here to the real framework `OutputConfiguration.setPhysicalCameraId` was
derived from primary sources in pass 2 (including disassembling `camera-camera2-pipe`'s AAR,
because no sources exist for the final link) and independently by the security lens. Not re-derived
this pass — the lines are byte-identical and nothing in fix pass 2 touched `buildUseCases`.

**Half B — the confirmation: PARTIAL, and now honestly so.**

- The literal string check the spec names **does match**. `logCameraAfCapabilities` emits
  `"camera id=… boundVia=$boundVia …"` (`:301`), and with an ultrawide found, `$boundVia` starts
  with `ultrawide-physical-requested`. So `adb logcat -s ScannerFocus:* | grep
  boundVia=ultrawide-physical` matches — the spec's stated on-device check is satisfiable.
- **What it no longer does is lie.** Pass 2's blocker was that this line printed `MISMATCH` on a
  device where the fix worked perfectly. That is gone.
- **What it still does not do is confirm.** The string means "a physical id was requested and
  applied to all three use-case builders," not "frames are coming from that physical camera." The
  genuine readback (option (b): `setSessionCaptureCallback` +
  `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`, API 29) was consciously declined, with
  reasoning I find substantively sound even though its citation is wrong (P3-1).

**My judgment: this does not block, and further automated iteration cannot improve it.** The
blocking condition in pass 2 was an *actively false* signal that would misdirect the next debugging
round. That condition is resolved. What remains is a spec criterion asking for a confirmation the
platform doesn't cheaply expose — a spec/reality mismatch, now correctly documented in-code at
`:683-694` and `:312-319`, and closable only by Skyler installing the APK and observing whether
focus actually converges at ~9in. That is the real acceptance test for this feature anyway.

### Remaining ACs

| # | Acceptance criterion | Status | Evidence (re-verified this pass where the fix could have touched it) |
|---|---|---|---|
| AC2 | No ultrawide → identical to today, `boundVia=default-back-camera` | **DONE** | `:634-638` yields `ultrawideId = null` → `buildUseCases(null)` skips the `Extender` block (`:586` guard) → `:697` `"default-back-camera"`, exact-match on the spec's string. Byte-identical runtime behavior to pre-diff. |
| AC3 | Bind throws → retry with `DEFAULT_BACK_CAMERA`, no crash | **DONE (CHANGED)** | `:643-680`. Both binds wrapped in `runCatching`; total failure yields `camera = null` and degrades. Carried caveat now recorded as **P2-1** (the `boundVia` label doesn't distinguish this path). |
| AC4 | Cropped bitmap reaches Gemini/PerceptualHasher | **DONE** | Re-traced this pass because the signature changed: `:798`/`:828` → `ScannerViewModel.kt:69` `imageProxy.toCroppedBitmap()` → `ImageProxyExt.kt:25-43` → `ScannerViewModel.kt:72` `geminiCardScanner.scan(bitmap, apiKey)` and `:80` `perceptualHasher.findBestMatch(candidates, bitmap)`. **Strengthened by this pass:** the function now always crops, so the "someone calls it with the default and silently gets an uncropped bitmap" failure mode is structurally impossible, not merely unexercised. |
| AC5 | Degenerate rect → uncropped fallback, no crash | **DONE** | `ImageProxyExt.kt:30-36` (degenerate guard, returns `decoded`) + `:37-42` (`runCatching`/`getOrElse`). Both covered by live tests (`ImageProxyExtTest.kt:76-95`). |
| AC6 | Task 5 calibration from a real photo | **NOT DONE (spec-sanctioned)** | `:60` and `:243` unchanged — human-in-the-loop, correctly deferred. |
| AC7 | Attempt #1-5 focus/detection logic untouched | **DONE** | Established in pass 2 by positive-list context-line check; fix pass 2 touched only `:277-322`, `:683-703`, and `ImageProxyExt.kt` — none of which is in the focus/detection region (`:596-623`). |
| AC8 | `assembleDebug`/`lintDebug --rerun-tasks` clean | **DONE** | Tester's third pass, structurally parsed: `EXITCODE=0`, `BUILD SUCCESSFUL in 1m 42s`, `60 actionable tasks: 60 executed`, lint XML `{'Warning': 81}` with **zero** `Error` severity. Not re-run by me this pass — the fix delta is ~40 lines and the Tester deleted `test-results/` before rerunning, which is the check this project's own CLAUDE.md demands. |
| AC9 | New logic covered by JVM unit tests | **DONE** | `files 31 tests 286 failures 0 errors 0 skipped 0`, and the 287→286 delta is fully accounted for: `ImageProxyExtTest` 4→3, exactly the deleted `cropToGuideFrame = false` test, with every other file's count unchanged. I verified the surviving 3 tests read correctly and cover the production shape. |
| **Task 4 diagnostic** | `captured format=… planes=…` | **DONE (was missing)** | `ImageProxyExt.kt:26`. A spec item that was genuinely absent in pass 2 is now present and reachable. |

**Scope creep: none.** `git diff --stat` shows the same seven paths as pass 2, three of which
(`docs/specs/*`, `project-overview.md`) predate the pipeline run.

**Agent-Native dimension: does not apply.** Android camera/UI code — no MCP server, no
agent-callable API, no skill/agent definition, and `specs.md` makes no agent/AI-integration claim.
Skipped deliberately, stated rather than silently omitted.

---

## Phase 4 bug hunt (fix delta only) — nothing found

Ran the categorized pass over the ~40 changed lines:

- **Logic** — no off-by-one, no inverted boolean, no unreachable branch. The `if/else` at `:696`
  is exhaustive over `ultrawideId`'s nullability.
- **Null handling** — `requestedPhysicalId?.let`, `firstOrNull`, `?.let`, `getOrNull()`,
  `getOrDefault(false)` — every dereference guarded. `requestedPhysicalMinFocusDistance` printing
  `null` is a valid, informative outcome.
- **Error handling** — nothing swallowed silently; the outer `runCatching` has an
  `.onFailure { Log.w(...) }` (`:309`).
- **Resource management** — the new `planes`/`format` access at `ImageProxyExt.kt:26` happens
  strictly before `imageProxy.close()` in `ScannerViewModel`'s `finally` (`:91-93`). Verified, not
  assumed.
- **Async/concurrency** — all of this runs on the main executor inside the `addListener` callback;
  no new shared mutable state introduced.
- **Edge cases** — `getPhysicalCameraInfos()` returning an empty set, or no id matching `reqId`,
  both land on `firstOrNull() == null` → `null` logged. No throw.

**No bugs found in the delta.** Stating that plainly rather than manufacturing something to look
thorough.

---

## What's correct (verified this pass)

- **The fix is the right shape.** Removing an assertion the code cannot support is strictly better
  than replacing it with a different assertion the code cannot support. The two prior failure modes
  (always-success, then always-failure) were both "assert an unobservable outcome"; this one
  asserts nothing.
- **The doc comments now carry the constraint, not just the fix.** `:312-319` and `:683-694`
  explain *why* the comparison is impossible, in enough detail that a future session won't
  reintroduce it. That is the durable part of this fix — attempt #7 is now protected from
  re-deriving the same broken diagnostic. (P2-2 is one overreaching sentence inside an otherwise
  excellent comment block.)
- **Dropping the parameter genuinely eliminated a trap**, not just a lint nit: a default of `false`
  on a function named `toCroppedBitmap` was one careless call site away from silently shipping
  uncropped bitmaps to Gemini.
- **The Tester's third pass is the strongest of the three.** It accounted for the 287→286 delta
  test-by-test instead of waving at "tests pass," parsed lint XML structurally rather than grepping
  it, and checked the APK's timestamp to prove the artifact was fresh. I spot-verified its
  claims 1-4 and 7 against the live files myself and found no discrepancy.

---

## Score (correctness + spec-trace lens)

| Dimension | Points | Reasoning |
|---|---|---|
| **Spec compliance** | **22** / 25 | Up from 20. AC2/AC3/AC4/AC5/AC7/AC8/AC9 all DONE and traced to concrete lines; AC6 spec-sanctioned; zero scope creep; and Task 4's `captured format=… planes=…` diagnostic — genuinely missing at pass 2 and deducted for by two lenses — is now present and reachable (`ImageProxyExt.kt:26`). AC1's mechanism half is DONE. Deducted 2 for AC1's confirmation half, which remains semantically PARTIAL: the spec's literal grep string matches, but "requested" is not "confirmed bound," and the only route to genuine confirmation was consciously declined. Deducted 1 more for P2-1 — `boundVia` emits an AC1-matching string on the AC3 fallback path, which slightly blunts AC2/AC3's own log-distinguishability. |
| **Correctness & bug-freedom** | **21** / 25 | Up from 15. The P0 is gone structurally, not cosmetically — the comparison is deleted, and I confirmed by grep that no verdict wording survives anywhere under `app/src`. The `requestedPhysicalId` lookup is a genuine physical-camera characteristic read, traced object-by-object rather than taken from `changes.md`. The parameter drop is clean with zero live references repo-wide. A full Phase 4 bug hunt over the delta found nothing, including the resource-ordering check (`planes` accessed before `close()`) nobody else ran. Deducted 4 for the two P2s — both real, both quotable, both confined to diagnostic accuracy with no shipped-behavior impact and, in P2-1's case, a compensating adjacent log line. |
| **Security & reliability** | **18** / 20 | Up from 15. Pass 2's entire −5 was for observability regressing into a false negative; that is resolved — the primary diagnostic no longer emits a wrong verdict, and the AF-capability log now sources the ultrawide's own focus floor instead of the main lens's, which was the second half of the same finding. Crash-safety from the prior pass is intact (both binds wrapped, second `unbindAll()` present, total failure degrades to `camera = null`). No security surface in the delta: no I/O, no network, no permissions, no secrets logged. Deducted 2 for P2-1 — the one remaining path where a log field, read in isolation, misdescribes the session state. |
| **Maintainability & simplification** | **12** / 15 | Up from 11. This pass is net-subtractive in the right way: a 3-branch `when` asserting a verdict became a 2-branch `if` asserting nothing, and a boolean parameter nobody varied is gone along with the test that existed only to cover its dead value. The comment blocks at `:312-319` and `:683-694` explain the mechanism rather than the code, which is exactly what protects this from a seventh attempt. Deducted 3: the standing duplication debt is unchanged by agreement (`88f/63f` × 3, `20f` shadowing, the duplicated `sun.misc.Unsafe` fixture — all correctly out of scope), and P2-2 puts a factually wrong sentence inside the file's best comment block. |
| **Test evidence quality** | **12** / 15 | Up from 11. The Tester's 287→286 delta accounting is the specific thing that earns the point: it identified the one removed test by name and file, showed every other file's count was unchanged, and did so from freshly regenerated XML after deleting `test-results/`. Lint parsed structurally, APK timestamp checked for freshness. I independently read the three surviving `ImageProxyExtTest` cases and `P0_2_FreshVerificationTest`'s rewritten green case and confirmed the red-then-green pairing still proves what it claims. Deducted 3 because the code carrying both remaining P2s — the `boundVia` construction and `logCameraAfCapabilities`, inside `CameraPreview`'s `AndroidView` factory closure — still has **zero** test reach, the same untestable-because-it-lives-in-a-closure pattern that produced all three defects this pipeline has found in this feature. Test case 16's extraction has now been declined three times. |
| **TOTAL** | **85** / 100 | |

**Prior iteration: 72/100. Delta: +13.** (Pass 1 → 2 → 3: 34 → 72 → 85.) Every dimension improved
or held: spec 20→22, correctness 15→21, security/reliability 15→18, maintainability 11→12, test
evidence 11→12.

**Ship gate:** 85 ≥ 85 ✅ **and** zero unresolved P0/blocking findings ✅. Both conditions met
independently — the no-P0 condition is not carried by the score, and the score is not carried by
the absence of a P0.

**On landing exactly at 85.** I derived each dimension against pass 2's own recorded deductions
before summing, not backwards from the threshold. The arithmetic is what it is. But I'll state the
tiebreak explicitly so it isn't hidden: if the score had come out at 84, I would still ship, because
the rubric's second condition — no unresolved blocking finding — is the one that actually encodes
"is this safe to release," and the residual 15 points are almost entirely standing debt this pass
was explicitly forbidden from touching plus a spec criterion the Android platform won't cheaply
satisfy. Neither is a reason to stop for human review.

---

## Verdict

**ship**

The one blocker I raised in pass 2 is genuinely and structurally closed, and I confirmed that from
the live code rather than from anyone's description of it. All four targeted fixes do what they
claim: the unsatisfiable comparison is deleted rather than reworded, the physical-camera lookup
reads the physical camera's own characteristics, the restored diagnostic sits on the real capture
path with the proxy still open, and the vestigial parameter is gone with zero live references
anywhere in the repo. Nothing on the do-not-touch list was disturbed — I checked all six myself.

**Nothing blocks.** The two P2s are diagnostic-accuracy issues: one field that under-describes a
fallback path but is disambiguated by an adjacent warning on the same logcat tag, and one comment
sentence that overclaims what a static characteristic value proves. Neither affects a single line
of shipped runtime behavior; neither could produce a wrong card scan, a crash, or a blank preview.

**What this hands to Skyler.** Everything reachable from the JVM has now been settled three times
by three passes from primary sources. The remaining question is on-device and always was: does the
S26 Ultra's HAL actually honor `SESSION_PHYSICAL_CAMERA_ID_OPTION` for this three-stream
configuration? Install the APK, run `adb logcat -s ScannerFocus:*`, and read:

- `boundVia=…` — tells you whether an ultrawide was found and requested. If it says
  `default-back-camera`, no qualifying physical camera was detected and nothing else matters.
- `requestedPhysicalMinFocusDistance=…` — the ultrawide's focus floor **capability**, not proof it
  is in use (P2-2). A value well under the main lens's ~0.18-0.20m means the hardware could help
  if the bind engaged.
- `captured format=… planes=…` — closes the spec's own Open Question about the real capture format.
- Then the actual test: hold a card at ~9in and see whether it focuses. That is the only genuine
  confirmation, and it always was.

**If focus still fails on-device,** the next debugging round should start at option (b) —
`Camera2Interop.Extender.setSessionCaptureCallback` reading
`CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29, needs its own guard above the
existing API-28 gate) — which is the only true runtime readback of which physical camera is
producing frames. It was reasonably declined this pass as extra complexity for a signal that isn't
needed to fix a false log, but it becomes the right next move the moment the on-device result is
ambiguous.

**Optional 5-minute polish, not required to ship:** append a fell-back marker to `boundVia` on the
fallback path (P2-1), and correct the "trustworthy outcome signal" sentence at `:692-694` (P2-2).

---

## Sign-off

**Approval status:** Approved — ship
**Lens:** correctness + spec-compliance trace
**Iteration:** 3 of 3 (final) · **Score:** 85/100 (was 72, +13; run history 34 → 72 → 85)
**Blocking findings:** 0 · **P2:** 2 · **P3:** 2
**Date:** 2026-09-08
