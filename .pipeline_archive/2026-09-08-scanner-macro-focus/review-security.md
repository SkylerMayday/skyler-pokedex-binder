# Code Review: Scanner Ultrawide Lens Selection + Guide-Frame Crop — **PASS 3 (FINAL, fix-pass-2 re-review)**

**Reviewer:** Reviewer (dev-team-pipeline) — lens: **security + reliability**
**Date:** 2026-09-08
**PR or commit:** uncommitted working tree, `master` @ `2751b12` base
**Author:** Coder (dev-team-pipeline), fix pass 2
**Scope:** `ScannerScreen.kt`, `ImageProxyExt.kt`, `ScannerViewModel.kt` (production);
`ScannerScreenTest.kt`, `ImageProxyExtTest.kt`, `ScannerFocusIndependentVerificationTest.kt`,
`P0_2_FreshVerificationTest.kt` (tests).
**Loop iteration:** **3 of 3 — the pipeline's absolute cap. No further automated pass exists.**
Prior passes, this lens: 43/100 (iter 1) → 79/100 (iter 2). Aggregated iter-2 verdict: 72/100.

---

## Summary

**The predicted one-line-away fix landed, and it is genuinely better — but it is not the clean
close I forecast.** The P0 I raised in pass 2 (a `when` asserting `MISMATCH` on a working bind) is
fully gone: no comparison, no verdict, honest wording, and the success/failure string-prefix
collision eliminated by construction. That part matches what I recommended, exactly.

**Two things the fix pass got wrong, both found by reading the code rather than the artifacts, and
both in the same failure family the pass existed to close — a diagnostic asserting something the
code did not observe:**

1. `boundVia` is computed from `ultrawideId == null` alone (`:696`), so when the ultrawide bind
   **throws and the fallback succeeds**, the log still reads `boundVia=ultrawide-physical-requested`
   for a camera bound from `buildUseCases(null)`. `specs.md:472` requires the opposite, verbatim:
   *"`boundVia` must reflect the ACTUAL bound camera, not the attempted one."* The code has the
   information — it is one `var` away.
2. The in-code comment at `:692-694` designates `requestedPhysicalMinFocusDistance` as
   *"The trustworthy outcome signal."* It is not an outcome signal of any kind — it is
   `LENS_INFO_MINIMUM_FOCUS_DISTANCE` read off physical camera 2's own immutable
   `CameraCharacteristics`, a constant of the hardware that reads `20.0` whether or not the
   physical stream ever bound. It also duplicates a number `logUltrawideFeasibility` already prints
   one line later at `:704`.

Neither is a functional defect and neither can crash. The *logged strings* are honest; the wrong
claims live in a comment and in a control-flow branch that only fires after an adjacent, loud
`Log.w`. But this is the pipeline's last pass, so I am recording them at full detail rather than
waving them through.

**Security surface: re-verified nil this pass, not carried forward.** Diff-grep for the full I/O /
network / permission / credential pattern set returns **zero** matches (pass 2 had one comment
hit; that comment was rewritten). No manifest, gradle, or properties file appears in
`git diff --name-only HEAD` at all.

**New reliability risk from the fix itself: none that can escape.** I traced the new
`getPhysicalCameraInfos()` + `Camera2CameraInfo.from()` lookup specifically, as asked. It sits
inside `logCameraAfCapabilities`'s pre-existing outer `runCatching`, so nothing new reaches the
main Looper; and it forces the same `by lazy` set that `logUltrawideFeasibility` already forces one
line later on the same `CameraInfo` instance, so it adds no new class of main-thread work.

**Do-not-touch list: clean, verified item by item myself.**

**Recommendation:** Approve with comments. Numeric gate: **needs-changes at 84/100 (one point
short)**. Substance: **no P0, no blocking security or reliability defect, no functional bug** —
ship after a ~4-line inline edit. See Sign-off for the exact edit.

**Critical issues:** 0
**Important issues:** 2
**Minor issues:** 3
**Suggestions:** 2

---

## Critical issues (blockers)

**None.** Pass 2's Important #1 (the `MISMATCH`-on-success P0, promoted to P0 in the aggregated
verdict) is genuinely and completely resolved. Stated with the evidence, since "no blockers" needs
the same proof as a finding.

`ScannerScreen.kt:695-702`, the entire replacement:
```kotlin
                    val actualId = actualBoundCameraId(cam)
                    val boundVia = if (ultrawideId == null) {
                        "default-back-camera"
                    } else {
                        "ultrawide-physical-requested (requested physical id=$ultrawideId; bound " +
                            "logical camera id=$actualId — expected to differ, a physical id does " +
                            "not change the logical id, this is not a failure signal)"
                    }
```
- **No false verdict.** The three-way `when` with `actualId == ultrawideId` is gone. There is no
  comparison left to be unsatisfiable, so there is no branch that can claim success or failure from
  it. This is precisely the shape I asked for.
- **Both ids logged honestly.** `requested physical id=$ultrawideId` and
  `bound logical camera id=$actualId` appear side by side, with the reason they differ stated
  inline. A reader cannot now conclude "failed" from the two ids being different.
- **The physical camera's own characteristics are looked up separately**, off its own `CameraInfo`
  and not the logical one — `ScannerScreen.kt:286-298`:
  ```kotlin
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
  This is a genuine second `CameraInfo`, distinct from `Camera2CameraInfo.from(camera.cameraInfo)`
  at `:279`. Not the logical value relabeled. Confirmed against
  `CameraInfoAdapter.kt:105-113`, which builds each physical `CameraInfo` with its own
  `CameraConfig(physicalCameraId)` and its own awaited metadata.
- **The string-prefix collision is gone by construction.** Only two `boundVia` values now exist and
  no failure-flavored string shares the `ultrawide-physical` prefix. Verified: `grep -rn "MISMATCH"`
  across `app/src` returns nothing.

**Answering the brief's Q2 directly — does the new lookup add a throw surface?** No, and I checked
each link rather than assuming:
- `camera.cameraInfo.getPhysicalCameraInfos()` at `:287` is **not** inside an inner `runCatching`,
  but the whole function body is inside the outer one — `ScannerScreen.kt:278` / `:309`:
  ```kotlin
    runCatching {
  ...
    }.onFailure { Log.w("ScannerFocus", "could not read camera AF characteristics", it) }
  ```
  so any throw is contained and cannot reach the main-Looper `Runnable`. (Consequence noted as
  Minor #1 below — containment is not the same as harmlessness for a diagnostic.)
- The one `getPhysicalCameraInfos()` implementation that unconditionally throws is
  `PhysicalCameraInfoAdapter.kt:138-140`:
  ```kotlin
    override fun getPhysicalCameraInfos(): Set<CameraInfo> {
        throw UnsupportedOperationException("Physical camera doesn't support this function")
    }
  ```
  **Not reachable here** — the receiver at `:287` is `camera.cameraInfo`, the *logical* camera's
  info (`CameraUseCaseAdapter.java:1285-1287`, `return mCameraInternal.getCameraInfo()`), which
  resolves to `CameraInfoAdapter.kt:128`, `override fun getPhysicalCameraInfos(): Set<CameraInfo> =
  _physicalCameraInfos`. Checked, not assumed.
- **No new main-thread cost class.** `_physicalCameraInfos` is `by lazy`
  (`CameraInfoAdapter.kt:105`) and its construction is what awaits per-physical metadata
  (`cameraProperties.metadata.awaitPhysicalMetadata(physicalCameraId)`, `:111`).
  `logUltrawideFeasibility(cam)` at `:704` already forces that same lazy on the same
  `CameraInfo` object one line later. The fix moves the first touch one line earlier; it does not
  add one. *(Honest limit: `awaitPhysicalMetadata` lives in `camera-camera2-pipe`, which ships no
  sources jar — I cannot confirm from the JVM whether it blocks. Whatever it costs, it was already
  being paid on this path before this fix pass.)*

---

## Important issues

### 1. `boundVia` still reports the *attempted* camera, not the bound one, on the fallback path — a verbatim, quotable spec requirement, and the code already has the information

- **File:** `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`
- **Line:** 667-669 (the fallback bind), 696-703 (the reporting)
- **Confidence:** 10/10 — pure control-flow read, no library behavior involved.

**The spec, verbatim.** `.pipeline/specs.md:472-474`:
> - `boundVia` must reflect the ACTUAL bound camera, not the attempted one — only set to
>   `"ultrawide-physical"` after `bindToLifecycle` returns successfully, inside the `runCatching`
>   block that also produces `camera`.

and `.pipeline/specs.md:726-727` names the exact case and expected value:
> ultrawide bind throws then fallback succeeds (`boundVia == "default-back-camera"`, log warning
> fired)

**The code.** The fallback genuinely rebuilds without the physical id — `ScannerScreen.kt:667-669`:
```kotlin
                        val fallback = buildUseCases(null)
                        preview = fallback.first; capture = fallback.second; analysis = fallback.third
                        onImageCaptureReady(capture)
```
but the reporting never learns that happened — `ScannerScreen.kt:696-703`:
```kotlin
                    val boundVia = if (ultrawideId == null) {
                        "default-back-camera"
                    } else {
                        "ultrawide-physical-requested (requested physical id=$ultrawideId; bound " +
                            "logical camera id=$actualId — expected to differ, a physical id does " +
                            "not change the logical id, this is not a failure signal)"
                    }
                    logCameraAfCapabilities(cam, boundVia, ultrawideId)
```
`ultrawideId` is still non-null in the fallback branch, so both the `boundVia` string **and** the
`requestedPhysicalId` argument report the ultrawide for a camera that was demonstrably bound
without it.

**What the on-device log actually reads in that case:**
> `W ScannerFocus: ultrawide physical-camera bind threw, falling back to plain default back camera`
> `I ScannerFocus: camera id=0 boundVia=ultrawide-physical-requested (requested physical id=2; bound logical camera id=0 …) … requestedPhysicalId=2 requestedPhysicalMinFocusDistance=20.0`

**Why this matters at this lens's severity.** `specs.md:222`'s Success Metric is a grep for
`boundVia=ultrawide-physical` — which matches here, on a bind that fell back. This is the *same*
class of defect as the P0 this pass was created to fix (a diagnostic claiming a state the code did
not observe), narrowed to the bind-throw branch. `specs.md:508-511` states the requirement's whole
purpose: *"a future 'still wrong' report must be able to distinguish 'ultrawide bound but still
inaccurate' from 'fell back to main lens, capability check failed'"* — from `boundVia` itself,
which it currently cannot.

**Why it is nonetheless not blocking, stated honestly:** the `Log.w` at `:656-660` fires
immediately before, in the same `Runnable`, and `adb logcat -s ScannerFocus:*` shows W and I
alike. A reader following the prescribed procedure sees the fallback. Runtime behavior is
unaffected. That mitigation is real, and I am not inflating this to a blocker on it.

**Suggested fix (3 lines, no behavior change, no new test surface):**
```kotlin
var boundPhysicalId = ultrawideId              // near :640
...
boundPhysicalId = null                          // inside the fallback branch, next to :667
...
val boundVia = if (boundPhysicalId == null) { … }               // :696
logCameraAfCapabilities(cam, boundVia, boundPhysicalId)         // :703
```
This closes `specs.md:472` exactly and makes `requestedPhysicalId` truthful in the same edit.

### 2. `requestedPhysicalMinFocusDistance` is a static hardware constant, not an outcome signal — and the code comment tells the next reader to trust it as one

- **File:** `ScannerScreen.kt`
- **Line:** 692-694 (the claim), 286-298 (what it actually reads), 704 (where the same number is
  already printed)
- **Confidence:** 9/10 — the code path is certain; the one judgment call is how much weight the
  comment's framing will actually carry into the on-device read.

**The claim.** `ScannerScreen.kt:692-694`:
```kotlin
                    // claim failure.) The trustworthy outcome signal is
                    // requestedPhysicalMinFocusDistance, logged alongside this in
                    // logCameraAfCapabilities.
```

**What it reads.** `ScannerScreen.kt:293-295` — `LENS_INFO_MINIMUM_FOCUS_DISTANCE` off the
*physical camera's own* `CameraCharacteristics`. `CameraCharacteristics` are immutable per camera
id; this value is a property of physical camera 2's lens, evaluated by enumerating
`getPhysicalCameraInfos()` — a query that never touches capture results, session state, or the
bound stream config. It will print the same number on a device where the physical bind engaged and
on one where the HAL silently ignored the option. **It cannot distinguish bound from not-bound,
which is the only question the fix exists to answer.**

**It also duplicates an existing line.** `logUltrawideFeasibility(cam)` runs at `:704`, one line
after, and already prints per physical camera:
```kotlin
                    "feasibility spike: physical camera id=${camera2Info.cameraId} " +
                        "focalLengths=${focalLengths?.toList()} minFocusDistance=$minFocusDistance " +
```
So `requestedPhysicalMinFocusDistance=20.0` for id=2 is the same value already emitted as
`feasibility spike: physical camera id=2 … minFocusDistance=20.0`. The new lookup adds nesting,
an extra set traversal, and `2N+1` `Camera2CameraInfo.from()` calls for information already in the
log.

**Why I am raising this rather than letting it pass.** The aggregated verdict asked for the
ultrawide's own focus floor to be visible, and the Coder delivered exactly that — the
*implementation* is correct and does what was requested. The defect is the **label**. If the
human-facing final report repeats *"the trustworthy outcome signal is
requestedPhysicalMinFocusDistance,"* Skyler reads `20.0` on his S26 Ultra and concludes the fix
engaged — which is the over-claiming failure this bug family has now produced twice (pass 1's
false `confirmed`, pass 2's false `MISMATCH`), in a third costume. The honest statement is:
**after this pass there is still no field in the `ScannerFocus` log that confirms the physical
stream bound.** The only genuine confirmation available is behavioral — does close focus actually
work at ~5cm — or the API-29 `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` readback the
Coder deliberately declined (with sound, stated reasoning I agree with for this diff's size).

**Suggested fix:** comment-only. Replace "The trustworthy outcome signal is…" with "No field in
this log confirms the physical stream actually bound; `requestedPhysicalMinFocusDistance` is a
static characteristic of the requested lens, logged as the comparison baseline against the logical
camera's `minFocusDistance`. Real confirmation is behavioral." Zero code change, zero risk.

---

## Minor issues

### 1. The new lookup sits inside the same `runCatching` as the primary `Log.i`, so a throw there now costs the entire AF-capability line

- **File:** `ScannerScreen.kt`
- **Line:** 287 (new, unguarded relative to the inner handlers), 299 (the log it precedes)
- **Confidence:** 10/10 on the ordering; low on the throw actually occurring — stated as a split
  rather than rounded into one number.

`:287`'s `camera.cameraInfo.getPhysicalCameraInfos()` is the only new call *not* wrapped by an
inner `runCatching` — the two inner ones at `:289` and `:292` guard `Camera2CameraInfo.from` and
`getCameraCharacteristic`, but not the set retrieval itself. Because it executes **before** the
`Log.i` at `:299`, a throw there routes to `:309`'s `could not read camera AF characteristics` and
takes `boundVia`, `camera id`, `minFocusDistance` and `afAvailableModes` down with it — fields that
were reliably printed before this pass. No crash (verified above), but on the one device session
this log exists for, an unrelated physical-metadata failure would now blank the primary diagnostic
rather than just the new field.

Realistic probability is low: the reachable implementation is `CameraInfoAdapter.kt:128`'s cached
lazy, and the same call succeeds one line later in `logUltrawideFeasibility` on every device where
it matters. Recording it because "contained" and "harmless" are different claims and this is the
last pass. **Cheapest fix:** wrap `:286-298` in its own `runCatching { … }.getOrNull()`, or emit
the physical-camera field as a second `Log.i` after the primary one.

### 2. `.cameraId` (JvmField) used at `:289`, against this same file's own documented convention 160 lines above

- **File:** `ScannerScreen.kt`
- **Line:** 289 (new), vs. the convention stated at ~`:450`
- **Confidence:** 10/10 — verified against the library declaration, not inferred.

New code, `:289`:
```kotlin
                    runCatching { Camera2CameraInfo.from(physicalInfo).cameraId == reqId }.getOrDefault(false)
```
The same file explicitly warns against this form in `selectUltrawidePhysicalCameraId`:
```kotlin
                    // getCameraId() (method), not the .cameraId JvmField, deliberately — only the
                    // method is interceptable by mockk in ScannerScreenTest.kt; both return the
                    // identical value (Camera2CameraInfo.kt: getCameraId() = cameraId).
                    camera2Info.getCameraId()
```
Confirmed in `camera-camera2-1.6.1-sources.jar`, `Camera2CameraInfo.kt:35` and `:73`:
```kotlin
    @JvmSynthetic @JvmField public val cameraId: String = cameraProperties.cameraId.value
...
    public fun getCameraId(): String = cameraId
```
Identical values in production — **not a bug**. But it means anyone who later tries to unit-test
`logCameraAfCapabilities`'s physical-id match will hit exactly the mockk trap the Coder documented
and worked around elsewhere, with the warning comment nowhere near the offending line. One-word
fix: `.getCameraId()`.

### 3. Carried forward, unchanged, still correct to leave: the key-scoped second `unbindAll()` is a no-op on a first-bind failure

- **File:** `ScannerScreen.kt:670`
- **Confidence:** 8/10 (unchanged from pass 2, where I traced
  `LifecycleCameraProviderImpl.kt:257-262` / `:695-702` and `LifecycleCameraRepository.java:513-521`
  and then corrected my own iteration-1 severity downward).

Untouched this pass, as it should be. The line is free, works on every subsequent bind, and the
state it targets is one field that self-heals. No action.

---

## Security

Re-checked this pass in full, not carried forward. **Answering the brief's Q3 by verification, not
assumption.**

- **No manifest, gradle, or properties file in the diff.** `git diff --name-only HEAD | grep -Ei
  "manifest|gradle|properties|\.pro$"` → **zero matches**. No new permission, no `minSdk`/`targetSdk`
  change, no dependency. `changes.md`'s Item-P claim of N/A is correct.
- **No new I/O, network, exec, or credential surface.** Grepped every added line
  (`git diff HEAD -- .../ui/scanner/ | grep '^+'`) against
  `http|url|okhttp|retrofit|File\(|openFile|getExternal|SharedPreferences|permission|Intent|exec\(|Runtime\.|Cipher|apiKey|token|secret|Base64|writeText|FileOutputStream|contentResolver`
  → **zero matches** (pass 2 had one hit, the substring "intent" in a comment; that comment was
  rewritten this pass). Every added line is a `Log` call, a camera-characteristics read, or
  control flow.
- **Log hygiene, all new/changed lines read individually.** The three new/changed emissions carry
  hardware identifiers and geometry only: `camera id=…/boundVia=…/minFocusDistance=…/
  requestedPhysicalId=…` (`ScannerScreen.kt:299-308`), and the restored
  `Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")`
  (`ImageProxyExt.kt:26`) — an `ImageFormat` int and a plane count. **No image bytes, no PII, no
  credentials.**
- **The API key is still never logged, confirmed structurally.** `grep -n "Log\.\|apiKey"
  ScannerViewModel.kt` returns `apiKey` at `:64`/`:72` and **no `Log.` call anywhere in the file** —
  the only file in the diff that holds the secret contains no logging at all.
- **Experimental-API scope unchanged from pass 2.** `@androidx.annotation.OptIn(
  ExperimentalCamera2Interop::class)` covers the same set of functions; this pass added the
  annotation to nothing new. Lint reports zero `UnsafeOptInUsage*` findings (parsed structurally
  from `lint-results-debug.xml`: `{'Warning': 81}`, no `Error` key).
- **Agent-Native Architecture dimension:** checked, **not applicable**. No MCP server, no
  agent-callable API, no skill/agent definition in the diff; `specs.md` names no agent/AI
  integration (Gemini is invoked unchanged through an existing repository). Skipped explicitly per
  the conditional rule, not silently.

**Security verdict: clean. Zero findings, zero deductions — third pass running, and re-derived
each time rather than inherited.**

---

## Do-not-touch audit (brief's Q4) — spot-checked myself, every item

| Item | Status | Evidence |
|---|---|---|
| `88f/63f` triplication | Untouched, still 3 literals | `ScannerScreen.kt:76`, `:151`, `:190` |
| `20f` threshold shadowing | Untouched, still 2 sources | `:128` and `:423` (`it < 20f`) vs. named `ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 20f` at `:326` |
| Stale distance text | Untouched | `:243`, `text = "Hold card ~9 in (23 cm) back"` |
| `GUIDE_FRAME_WIDTH_RATIO` (Task 5 not attempted) | Untouched | `:60`, `= 0.32f` |
| Crop-size mismatch / 1px rotation off-by-one | Untouched | `guideFrameImageRect` body, `:145-175`, same 4-corner remap |
| `Triple` destructuring nit | Untouched | `:640`, `var (preview, capture, analysis) = buildUseCases(ultrawideId)` |
| Duplicated `sun.misc.Unsafe` fixture | Untouched, still 2 copies | `ScannerScreenTest.kt:47`, `ScannerFocusIndependentVerificationTest.kt:44` |
| Scope of the diff overall | Clean | `git status --porcelain`: 3 production files + 3 pre-existing doc files + the same untracked test dir. **No new file added this pass**; `ScannerScreenTest.kt` mtime `15:21` predates the whole fix pass (`20:02-20:04`), i.e. genuinely not edited. |

**No scope creep. Nothing on the deferred list was touched.**

---

## Test evidence — re-derived, not accepted

I re-ran the structural counts rather than reading `test-results.md`'s numbers:

```
$ python3 (ElementTree over app/build/test-results/testDebugUnitTest/*.xml)
files 31 tests 286 failures 0 errors 0 skipped 0
```
Matches the Tester's claim exactly. **And the run genuinely covers the current code** — a check I
did not see either artifact make explicitly: every touched source's mtime precedes the artifacts.
```
ImageProxyExt.kt      2026-09-04 20:02:38
ScannerViewModel.kt   2026-09-04 20:02:47
ScannerScreen.kt      2026-09-04 20:03:18
(test sources)        2026-09-04 20:04:03 – 20:04:34
app-debug.apk         2026-09-04 20:14:41
TEST-*.xml            2026-09-04 20:15
```
Test files read in full: `ImageProxyExtTest.kt` (3 tests, `Log.i`/`Log.w` stubs present so the
restored log line is genuinely exercised, not bypassed) and `P0_2_FreshVerificationTest.kt` (its
edited second test still proves the real claim — `singlePlaneJpegProxy()` never stubs
`planes[1]`/`planes[2]`, so mockk's strict default would fail if the production decode touched
them; the added `Bitmap.createBitmap` stub does not weaken that).

**The gap, stated plainly:** nothing tests the bind block, which is where **both** of this pass's
Important findings live — and the Tester's §7 deferred-item audit did not catch either, because it
checked the code that changed rather than the branch that didn't.

---

## What looks good

- **The predicted fix landed exactly as specified.** No false verdict, both ids logged honestly,
  physical characteristics looked up off their own `CameraInfo`. Three for three on the brief's Q1,
  with the one caveat that "requested" is not reported accurately on the fallback branch.
- **The prefix collision was eliminated structurally, not patched.** Removing the `MISMATCH` branch
  entirely means there is no second string to collide with — a stronger fix than renaming one.
- **Cheap item 1 restored correctly and on the live path.** `ImageProxyExt.kt:26`'s
  `Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")` reads `planes.size`
  without indexing — it cannot resurrect the P0-2 `planes[2]` failure it sits next to.
- **Cheap item 2 is a genuine simplification, not a rename.** `fun ImageProxy.toCroppedBitmap():
  Bitmap` with the `cropToGuideFrame` boolean gone removes a parameter whose default silently
  contradicted the function name — the same trap class as P0-2, deleted rather than documented.
  Repo-wide grep confirms zero live references remain.
- **The exception path is still fully contained, re-verified rather than assumed.**
  `ScannerViewModel.kt:87-94` still has `catch (e: Exception)` plus `finally { imageProxy.close() }`,
  and `ImageProxyExt`'s crop is `runCatching`-wrapped (which catches `Throwable`, so even an
  `OutOfMemoryError` from `Bitmap.createBitmap` degrades to the uncropped bitmap instead of
  propagating). No `ImageProxy` leak on any path I can construct.
- **Both binds remain wrapped; the double-failure end state still degrades to a handled
  `ERROR_INVALID_CAMERA` capture error, not a crash.** Unchanged from pass 2 and re-confirmed at
  `:644-680`.
- **The `changes.md` reasoning for choosing option (a) over option (b) is sound and I agree with
  it** — adding an API-29 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` readback branch on top of the
  existing API-28 gate, in the one file every lens has flagged for accumulating complexity, is not
  worth it for this diff. Declining it was the right call; the mistake was then describing a static
  characteristic as if it filled the gap.

---

## Suggestions for follow-up

- Apply the two edits in Sign-off before install. Combined they are ~4 lines and carry no
  behavioral risk.
- Gate `logUltrawideFeasibility` (`:704`) behind `BuildConfig.DEBUG` once the spike retires, and
  fold the now-duplicated `requestedPhysicalMinFocusDistance` lookup into it rather than keeping two
  functions printing the same physical-camera number. Carried unchanged from passes 1 and 2; still
  not urgent, now slightly more earned.

---

## Dimension scorecard

| Dimension | Pass | Issues |
|---|---|---|
| Correctness | ☑ | No functional bug. The P0's false verdict is gone; one diagnostic branch still reports the attempted rather than bound camera |
| Security | ☑ | Zero new surface, re-verified by diff-grep + manifest absence + per-line log read + ViewModel `Log.` absence |
| Performance | ☑ | New lookup forces a `by lazy` already forced one line later; no new class of main-thread work. Redundant traversal noted |
| Reliability | ☑ | No new throw surface; containment traced link by link. Deducted for the fallback-path misreport and the AF-line-loss ordering |
| Maintainability | ☑ | Two genuine simplifications (verdict collapse, vestigial param). Offset by a 3-level nested lookup that duplicates an adjacent log line |

---

## Score (security + reliability lens)

| Dimension | Score | Reasoning |
|---|---|---|
| Spec compliance | **21** / 25 | Task 4's mandated `captured format=… planes=…` line is restored and on the live capture path (closing one of pass 2's two deductions); zero scope creep, do-not-touch list verified item by item. Deducted 4: `specs.md:472`'s *"`boundVia` must reflect the ACTUAL bound camera"* is unmet on the fallback branch and was achievable in 3 lines the pass had every reason to write; and AC1's `boundVia=ultrawide-physical` remains a *reachable* string rather than a *confirmation* — correctly reasoned as a hardware-observability limit, but still unmet as written. |
| Correctness & bug-freedom | **22** / 25 | No functional bug reachable from this lens. Bug hunt: no null deref (`camera?.let`, `?: return`, `?.let` chain terminates in `null` cleanly), no unclosed resource (`finally { imageProxy.close() }` intact), no concurrency issue (all bind work on one main-thread `Runnable`), no off-by-one (`planes.size` read, never indexed), no unreachable `getPhysicalCameraInfos()` throw path (`PhysicalCameraInfoAdapter`'s throwing override is not the receiver here). Deducted 3 for the fallback-path misreport (a real logic gap in the diagnostic) and the carried key-scoped `unbindAll` no-op. Up 1 from pass 2: the `when` bug is gone, a narrower one replaced it. |
| Security & reliability | **17** / 20 | Security half clean, 0 deductions, verified three passes running rather than inherited. Reliability improved materially this pass: the "MISMATCH on a working fix" trap — my single heaviest pass-2 deduction — is fully gone, and I traced the new lookup's containment link by link rather than trusting the `runCatching`. Deducted 3: the fallback branch can still misattribute a bind (mitigated by the adjacent `Log.w`, hence 3 not 5), the new call's placement can now cost the whole AF line on an unrelated failure, and the residual unwrapped statements in the same `Runnable` are unchanged. |
| Maintainability & simplification | **12** / 15 | Two real simplifications: the three-way stringly verdict collapsed to an honest two-way `if`, and a vestigial boolean parameter deleted rather than documented. The `actualBoundCameraId` doc comment now states the disjoint-namespace fact plainly, which stops a future reader "fixing" it back into a comparison. Deducted 3: `logCameraAfCapabilities` gained a 3-level nested lookup producing a number `logUltrawideFeasibility` already prints one line later; the `.cameraId` JvmField contradicts the file's own documented convention; `logUltrawideFeasibility` remains verbose and un-gated on every production bind. |
| Test evidence quality | **12** / 15 | Strongest of the three passes: the Tester deleted the results dir before rerunning, parsed JUnit and lint XML structurally rather than grepping, spot-checked all 7 deferred items in the live file, and separated code-reads (§1-4) from executed evidence (§5-6) instead of blending them as pass 2 did. I reproduced the 286/0/0 count and additionally confirmed source mtimes precede the artifacts — a staleness check neither artifact made. Deducted 3: the bind block is still untested, which is precisely where both Important findings live, and §7's audit checked what changed rather than what should have. |
| **Total** | **84 / 100** | |

**Score history, this lens: 43 → 79 → 84.** Delta this pass: **+5**. The improvement is real but
smaller than pass 2's +36, which is the expected shape for a small targeted fix — the ceiling was
never far away.

**Ship gate:** 84 < 85 → **not ship on the numeric rule, by one point.** I am reporting 84 rather
than rounding to the gate: the two Important findings are genuine, one of them is a verbatim
unmet spec line, and a manufactured 85 would be less useful to Skyler than an honest 84 plus the
exact edit that earns it.

**No unresolved P0. No blocking security or reliability defect. No functional bug.**

---

## Sign-off

**Approval status:** Approve with comments / Changes requested (numeric gate, by 1 point)
**Pipeline verdict:** **needs-changes** (numeric) · **ship-with-inline-fix** (substance)
**Date:** 2026-09-08

**This is the final iteration, so I am giving the orchestrator an unambiguous answer rather than
two.** My pass-2 forecast — *"ship with Important #1 fixed inline"* — was right in shape and
slightly wrong in size: it was two edits away, not one, because the fix pass closed the branch it
was pointed at and left the sibling branch and the framing untouched.

**The exact edits, both ~2 lines, neither behavioral, neither needing a new test:**

1. **`ScannerScreen.kt` — make `boundVia` reflect the actual bind.** Declare
   `var boundPhysicalId = ultrawideId` near `:640`, set `boundPhysicalId = null` inside the fallback
   branch beside `:667`'s `val fallback = buildUseCases(null)`, then read `boundPhysicalId` at
   `:696` and `:703` instead of `ultrawideId`. Closes `specs.md:472` and `:726-727` exactly.
2. **`ScannerScreen.kt:692-694` — delete the "trustworthy outcome signal" claim.** Nothing in this
   log confirms the physical stream bound. `requestedPhysicalMinFocusDistance` is a static
   characteristic of the requested lens, useful as a baseline, not as confirmation.

**If neither edit is applied, shipping anyway is still defensible** — no crash path, no security
surface, no functional bug, and the fallback branch emits an adjacent `Log.w` that a careful reader
will see. **But one thing must not happen:** the human-facing final report must not tell Skyler
that `requestedPhysicalMinFocusDistance` confirms the fix engaged. It does not. State instead that
the `ScannerFocus` log now reports honestly what was *requested*, and that confirmation is
behavioral — does close focus actually work at ~5cm on the S26 Ultra.

**Carried into the on-device step, unchanged and still the highest-value next action:**
`adb logcat -s ScannerFocus:*` after install, reading `camera id=`, `boundVia=`,
`minFocusDistance=` and `requestedPhysicalMinFocusDistance=` on the same line, **plus** the
`W ... bind threw, falling back` line if present — that warning is currently the only reliable way
to tell a fallback bind from a real one. Nothing further is reachable from the JVM.

**Friction rollup (Item F):** performed once for this pipeline run at iteration 1 and confirmed at
iteration 2; **not duplicated here.** I re-checked the fresh Friction Notes from `changes.md`'s Fix
pass 2 and `test-results.md`'s third pass against `~/.claude/rules/lessons.md` — both named points
are already captured: the Serena/Kotlin language-server init failure by
`fallback-when-language-server-tool-fails-init.md` (indexed line 76), and the
`$ErrorActionPreference = "Stop"` native-stderr trap by
`erroractionpreference-stop-truncates-native-command-output.md` (indexed line 69). The Tester's
scratchpad filename collision is a one-off of a long shared session, not a generalizable rule — not
written. **One housekeeping item I cannot fix (read-only): the ErrorActionPreference rule exists
twice on disk** — `lessons/erroractionpreference-stop-truncates-native-command-output.md` (indexed)
and `lessons/no-erroractionpreference-stop-around-gradle.md` (not indexed), both written from the
same 2026-09-04 incident in this repo. One should be deleted.
