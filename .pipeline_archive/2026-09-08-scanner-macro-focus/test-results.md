# Test Results — Final Re-Verification Pass (Tester, 3rd pass)

## STATUS: PASS

All 7 verification items requested for this final iteration were independently re-derived from
the actual current source and a fresh full-suite run — not from `changes.md`'s narration. Every
claim in `changes.md`'s "Fix pass 2" section checks out. Nothing in this pass is taken on trust.

This is the pipeline's last allowed automated iteration. Reviewer's blocking item (the `boundVia`
P0) is genuinely closed, both cheap items are genuinely applied, and none of the explicitly
deferred findings were touched. No new defect was introduced by this pass. Recommend proceeding
to the human-facing final report; no further Debugger loop needed on the evidence gathered here.

---

## 1. `boundVia` fix — verified directly against current `ScannerScreen.kt`

Read the live file (`app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`,
lines 682–705). Current logic:

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

- No `MISMATCH`, no `confirmed`, no comparison-based verdict anywhere. Confirmed via
  `grep -n "MISMATCH|confirmed id=" app/src` → **no matches** across the whole `app/src` tree.
- Only two possible `boundVia` strings now exist: `"default-back-camera"` and
  `"ultrawide-physical-requested (...)"`. The string-prefix collision the maintainability lens
  flagged (`"ultrawide-physical"` as a prefix of `"ultrawide-physical-MISMATCH"`) is gone by
  construction — there is no longer a second, opposite-meaning string sharing that prefix. A
  `grep boundVia=ultrawide-physical` (the spec's own suggested on-device check, AC1) still matches
  the one honest string and can no longer be misread as also matching a failure case, since no
  failure-flavored string exists anymore.
- `actualBoundCameraId`'s doc comment (lines 312–319) plainly states it reads the LOGICAL id and
  that the comparison can never be true — matches the fix's stated intent.

**Verdict: genuinely fixed, confirmed from the live code, not from `changes.md`'s description.**

## 2. `logCameraAfCapabilities`'s `requestedPhysicalId` parameter — traced end-to-end

Lines 277–309. When `requestedPhysicalId != null`, the code walks
`camera.cameraInfo.getPhysicalCameraInfos()`, matches the physical camera whose own
`Camera2CameraInfo.from(physicalInfo).cameraId == reqId`, then reads **that physical camera's
own** `LENS_INFO_MINIMUM_FOCUS_DISTANCE` via `Camera2CameraInfo.from(physicalInfo)` — a distinct
`CameraInfo` object from the logical camera's `chars` value computed earlier in the same function
(line 280–282, off `Camera2CameraInfo.from(camera.cameraInfo)`). This is a genuine physical-camera
characteristic lookup, not the logical value relabeled under a new variable name.

**Verdict: genuine fix, confirmed by tracing the actual lookup chain.**

## 3. Restored `captured format=... planes=...` log — present and on the live capture path

`ImageProxyExt.kt` line 26, first line of `toCroppedBitmap()`:
```kotlin
Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")
```
`toCroppedBitmap()` is called from `ScannerViewModel.processImage` (line 69,
`imageProxy.toCroppedBitmap()`), which is itself invoked from both the auto-capture and manual
capture `OnImageCaptureCallback.onCaptureSuccess` call sites in `ScannerScreen.kt` (lines 798,
828) — this is the real, only production capture path, not a dead function.

**Verdict: present and reachable, confirmed.**

## 4. Dropped `cropToGuideFrame` parameter — signature, call sites, and tests all consistent

- `ImageProxyExt.kt` line 25: `fun ImageProxy.toCroppedBitmap(): Bitmap` — no parameter, always
  crops (no `if (!cropToGuideFrame)` branch anywhere in the function body).
- Only production call site, `ScannerViewModel.kt` line 69: `imageProxy.toCroppedBitmap()` — no
  argument.
- Repo-wide grep for `cropToGuideFrame`: the only remaining hits are historical/comment prose in
  `gaps.md`, `ImageProxyExt.kt`'s own header comment, and `P0_2_FreshVerificationTest.kt`'s
  comment — **zero live code references** to the old parameter or old call signature anywhere.
- All 4 test files under `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/` read and
  confirmed consistent: `ImageProxyExtTest.kt` (3 tests, all call `.toCroppedBitmap()` with no
  arg — the 4th, `cropToGuideFrame = false` regression-guard test, is genuinely deleted, not
  merely renamed), `ScannerFocusIndependentVerificationTest.kt` (both `ImageProxyExt`-related
  cases call `.toCroppedBitmap()` with no arg), `P0_2_FreshVerificationTest.kt` (its second test
  calls `proxy.toCroppedBitmap()` with no arg), `ScannerScreenTest.kt` (unaffected — doesn't touch
  `toCroppedBitmap` at all).

**Verdict: clean, no leftover references anywhere in the codebase.**

## 5. Full suite run fresh — `app/build/test-results/testDebugUnitTest/` deleted before rerun

```
$ rm -rf "D:/Claude Projects/PokedexBinderV2/app/build/test-results/testDebugUnitTest/"
deleted
```

Ran via `powershell.exe -NoProfile -ExecutionPolicy Bypass -File`, `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`,
combined `testDebugUnitTest lintDebug assembleDebug --rerun-tasks`:

```
EXITCODE=0
```

Relevant tail of the (UTF-16→UTF-8 converted) build log:
```
> Task :app:lintReportDebug
Wrote HTML report to file:///D:/Claude%20Projects/PokedexBinderV2/app/build/reports/lint-results-debug.html

> Task :app:lintDebug
[Incubating] Problems report is available at: file:///D:/Claude%20Projects/PokedexBinderV2/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 1m 42s
60 actionable tasks: 60 executed
```

`grep -n "FAILED|error:|Error:|BUILD SUCCESSFUL|BUILD FAILED|NewApi|UnsafeOptInUsage"` across the
full log: only one match, `BUILD SUCCESSFUL in 1m 42s` — no `FAILED`, no `error:`, no
`NewApi`/`UnsafeOptInUsage` lint findings anywhere in the run.

**Structural JUnit XML count** (parsed via `xml.etree.ElementTree`, not the Gradle console
summary):
```
files 31 tests 286 failures 0 errors 0 skipped 0
```

Per-file breakdown for the scanner package (parsed directly from the fresh XML):
```
TEST-com.skyler.pokedexbinder.ui.scanner.ScannerScreenTest.xml:                    tests="11" skipped="0" failures="0" errors="0"
TEST-com.skyler.pokedexbinder.ui.scanner.ImageProxyExtTest.xml:                    tests="3"  skipped="0" failures="0" errors="0"
TEST-com.skyler.pokedexbinder.ui.scanner.ScannerFocusIndependentVerificationTest.xml: tests="6"  skipped="0" failures="0" errors="0"
TEST-com.skyler.pokedexbinder.ui.scanner.P0_2_FreshVerificationTest.xml:            tests="2"  skipped="0" failures="0" errors="0"
```

**286 vs. the prior pass's claimed 287 — confirmed as exactly one fewer, matching the one deleted
test.** `ImageProxyExtTest.xml` went from the prior pass's 4 tests to this pass's 3 — exactly the
deleted `cropToGuideFrame = false` regression-guard test, and the only count that changed
anywhere in the scanner package. `ScannerScreenTest` (11), `ScannerFocusIndependentVerificationTest`
(6), and `P0_2_FreshVerificationTest` (2, both tests still present — only one of its two tests was
*edited* in place per `changes.md`, not deleted) are all unchanged from what `changes.md` claimed.
No other test file in the 31-file suite lost or gained a test. **Confirmed: this is the ONLY count
change, nothing else broke.**

## 6. `lintDebug`/`assembleDebug --rerun-tasks` — both clean, part of the same fresh run above

Lint XML parsed structurally (`app/build/reports/lint-results-debug.xml`, not grepped — per this
project's own prior-pass caution about undercounting via grep on lint HTML):
```
{'Warning': 81}
```
Zero `Error`-severity findings — no `Error` key present in the severity counter at all. Exactly
81, matching the prior pass's own reported count (no new warnings introduced). Scanner-package
findings: exactly 1, and it's in `GeminiCardScanner.kt` (`UseKtx`, pre-existing, unrelated file —
not one of this pass's three touched files: `ImageProxyExt.kt`, `ScannerScreen.kt`,
`ScannerViewModel.kt`).

Compile warnings (from the combined run's `compileDebugKotlin`/`compileDebugUnitTestKotlin`
output) are the same two pre-existing lines as the prior pass claimed, confirmed present verbatim
in this fresh run:
```
w: .../ScannerScreen.kt:536:26 'val LocalLifecycleOwner: ProvidableCompositionLocal<LifecycleOwner>' is deprecated. Moved to lifecycle-runtime-compose library in androidx.lifecycle.compose package.
w: .../SlotDetailViewModel.kt:28:10 This declaration needs opt-in. Its usage should be marked with '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)'
```
Plus the same set of pre-existing `ExperimentalCoroutinesApi` opt-in warnings in unrelated
`*ViewModelTest.kt` files (16 occurrences, unchanged file set, unrelated to this pass's diff).

`assembleDebug` succeeded as part of the same `BUILD SUCCESSFUL` run; confirmed a genuinely fresh
APK artifact was produced (not stale/cached):
```
$ ls -la app/build/outputs/apk/debug/*.apk
-rw-r--r-- 1 SkylerMayday 197121 30300255 Sep  4 20:14 app/build/outputs/apk/debug/app-debug.apk
```
(Timestamp matches this verification run's execution time.)

**Verdict: both clean, confirmed fresh, not extrapolated from a prior pass's claim.**

## 7. Deferred findings — spot-checked directly in the current file, confirmed untouched

| Item | Confirmed still present/unchanged |
|---|---|
| Stale "~9 in (23 cm)" distance text | `ScannerScreen.kt:243`, `"Hold card ~9 in (23 cm) back"` — unchanged |
| `88f/63f` triplication | Present at **3** separate locations: `ScannerScreen.kt:76` (`detectCardInFrame`), `:151` (`guideFrameImageRect`), `:190` (`CardFrameOverlay`) — still 3 separate literals, not consolidated |
| `20f` threshold shadowing | `logUltrawideFeasibility` still hardcodes `it < 20f` inline (`ScannerScreen.kt:423`) alongside the separately-declared named constant `ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 20f` (`:326`) used by `selectUltrawidePhysicalCameraId` — still two independent sources of the same magic number |
| Duplicated `sun.misc.Unsafe` test fixture | Confirmed present, byte-for-byte structurally identical reflection helper, in **both** `ScannerScreenTest.kt` and `ScannerFocusIndependentVerificationTest.kt` — still duplicated across the same two files, not consolidated into a shared test helper |
| `GUIDE_FRAME_WIDTH_RATIO` still `0.32f` (Task 5 not attempted) | `ScannerScreen.kt:60` — unchanged |
| Crop-size mismatch, 1px rotation off-by-one | `guideFrameImageRect`'s body (`ScannerScreen.kt:145–175`) read directly — byte-for-byte the same 4-corner-remap algorithm documented in the original Coder pass's `changes.md`, not touched by fix pass 2 |
| Triple-destructuring nit in bind block | `ScannerScreen.kt:640`, `var (preview, capture, analysis) = buildUseCases(ultrawideId)` — still present |

**Verdict: every deferred item spot-checked directly in the live file — genuinely untouched, as
claimed.**

---

## Evidence appendix — raw commands run this pass

```
$ rm -rf "D:/Claude Projects/PokedexBinderV2/app/build/test-results/testDebugUnitTest/"
deleted

$ powershell.exe -NoProfile -ExecutionPolicy Bypass -File tester_final_verify.ps1
  (JAVA_HOME=D:\jdk17\jdk-17.0.14+7; runs `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --rerun-tasks`)
EXITCODE=0

$ grep -n "FAILED|error:|Error:|BUILD SUCCESSFUL|BUILD FAILED|NewApi|UnsafeOptInUsage" tester_final_output_utf8.log
125:BUILD SUCCESSFUL in 1m 42s

$ python3 -c "... parse app/build/test-results/testDebugUnitTest/*.xml ..."
files 31 tests 286 failures 0 errors 0 skipped 0

$ python3 -c "... parse app/build/reports/lint-results-debug.xml ..."
{'Warning': 81}
scanner-related issues: 1
('UseKtx', 'Warning', '...GeminiCardScanner.kt')

$ git status --porcelain
 M app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt
 M app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt
 M app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt
 M docs/specs/2026-09-02-discord-live-lookup-bot.md
 M docs/specs/2026-09-02-scanner-macro-focus.md
 M gaps.md
 M project-overview.md
?? app/src/test/java/com/skyler/pokedexbinder/ui/scanner/

$ grep -n "println|TODO|FIXME|System\.out|debugger" app/src/main/java/com/skyler/pokedexbinder/ui/scanner
(no matches)

$ ls -la app/build/outputs/apk/debug/*.apk
-rw-r--r-- 1 SkylerMayday 197121 30300255 Sep  4 20:14 app/build/outputs/apk/debug/app-debug.apk

$ grep -n "MISMATCH|confirmed id=" app/src (whole tree)
(no matches)

$ grep -n "cropToGuideFrame" (whole repo)
gaps.md:141 (historical prose)
app/src/test/.../P0_2_FreshVerificationTest.kt:98 (comment, past tense)
app/src/main/.../ImageProxyExt.kt:12, :20 (header comment, past tense)
(zero live code references)
```

---

## Coverage gaps in existing test suite (unchanged from prior passes, not new findings)

- Task 2's bind-fallback wiring (the `if (ultrawideSelector != null) { runCatching { ... } } else`
  block, and its two-attempt `runCatching`/`unbindAll` restructure) is still not independently
  unit-testable — it lives inside a Compose `AndroidView` factory lambda, not an extracted
  function. Spec Task 6 test case 16 explicitly flagged this as optional; still not done, still
  an accepted gap per the spec's own framing (fallback logic reviewable by inspection).
- No real-device (`adb logcat -s ScannerFocus:*`) confirmation exists yet that the ultrawide
  physical-camera-id bind actually engages the HAL correctly on Skyler's S26 Ultra — this remains
  the one thing genuinely unreachable from a JVM unit test, as both this pass and the prior review
  correctly note.

## Friction Notes

- None beyond what prior stages already logged (Serena Kotlin LSP failure, PowerShell
  `-ErrorActionPreference Stop` trap) — this pass hit neither issue itself; used plain
  Read/Grep/Bash and a `*>`-redirecting `.ps1` without `-Stop`, consistent with the prior pass's
  own documented fix.
- The scratchpad directory for this session already contained ~40 leftover files from an earlier
  stage's work (build scripts, extracted CameraX source jars, verification logs) — had to pick a
  new filename (`tester_final_verify.ps1`) to avoid the Write tool's "must Read before overwrite"
  guard tripping on a same-named file from a different stage's session. Not a defect in this
  pass's work, just a naming collision worth being aware of when multiple pipeline stages share
  one scratchpad across a long-running session.
