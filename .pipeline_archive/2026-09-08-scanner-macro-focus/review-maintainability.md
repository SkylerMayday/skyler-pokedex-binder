# Code Review — PASS 3 (FINAL): Scanner Ultrawide Bind Restructure + Decode/Crop Split
## Lens: Maintainability + Simplification

**Reviewer:** Reviewer stage (dev-team-pipeline), maintainability + simplification lens
**Iteration:** 3 of 3 — the pipeline's absolute cap. No further automated pass exists after this one.
**Prior passes (this same file):** pass 1 **86/100 ship**, pass 2 **87/100 ship**.
**Date:** 2026-09-08
**Base:** uncommitted working tree on `master`, diffed against `HEAD` (`2751b12`)
**Scope of THIS pass:** `.pipeline/changes.md` § "Fix pass 2" (lines 500–700) — the `boundVia`
rewrite, `logCameraAfCapabilities`'s new `requestedPhysicalId` parameter, the restored
`captured format=...` diagnostic, and the dropped `cropToGuideFrame` parameter. Plus a hostile
sweep for new debt and a direct spot-check of the do-not-touch list.

**Read directly this pass, not trusted from `changes.md` or `test-results.md`:**
`ImageProxyExt.kt` in full (all 43 lines), `ScannerScreen.kt:255–330`, `:395–439`, `:515–724`,
`ImageProxyExtTest.kt` in full, `P0_2_FreshVerificationTest.kt` in full,
`ScannerFocusIndependentVerificationTest.kt:160–241`, `git diff` on all three production files,
and every do-not-touch item grepped in the live file.

---

## Summary

**All three of my pass-2 findings landed, and landed well.** P2-4 (the vestigial `cropToGuideFrame`
parameter) was dropped cleanly with zero residue. P3-4 (the string-prefix collision) is fixed *by
construction*, not relocated. P3-5 (the dropped format diagnostic) was restored to the one correct
place. That closes the only P2 my lens had open against this diff and two of its four P3s.

The pass introduced no new P2 and no bug. It did introduce **five P3 nits**, all of the
"redundancy and comment-drift" class, four of them consequences of the fix itself and one
(NEW-3) a genuine interaction defect between the pass's own two cheap fixes — a comment that is now
factually false about the code beneath it. None block.

**Score: 90/100 (was 87). Delta +3.** Ship, with full confidence — this is a clean landing, and
I am not holding back a verdict on the strength of five nits.

**Recommendation:** **Approve — ship.**

**Critical issues (P0/P1):** 0
**Important (P2):** 3 — all carried, all byte-for-byte unchanged, all correctly out of scope
**Minor (P3):** 8 — 3 carried, 5 new
**Resolved since pass 2:** 3 (the exact three this pass was asked to close)

---

## 1. P2-4 — was `cropToGuideFrame` dropped cleanly?

**Yes. Zero residue.** This is the cleanest of the three fixes.

Current signature and body, read in full:

```kotlin
fun ImageProxy.toCroppedBitmap(): Bitmap {
    Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")
    val decoded = toBitmap()

    val rect = guideFrameImageRect(width, height, imageInfo.rotationDegrees)
```
(`ImageProxyExt.kt:25–29`)

The `if (!cropToGuideFrame) return decoded` line is gone, not commented out. Call site:

```kotlin
val bitmap = imageProxy.toCroppedBitmap()
```
(`ScannerViewModel.kt:69` — `git diff` confirms the whole ViewModel change is this one line, `2 +/-`)

Residue check, done by grep across `app/src/main` and `app/src/test`, not trusted from
`test-results.md`:

| Residue class | Result |
|---|---|
| Live code references to `cropToGuideFrame` | **Zero.** Only hits are past-tense prose in `ImageProxyExt.kt:12`/`:20`, `P0_2_FreshVerificationTest.kt:97`, `gaps.md`. |
| Dead branch / unreachable code | **None.** The `if (!cropToGuideFrame)` guard is deleted, not defanged. |
| Stale comment referencing the old parameter | **None that misleads** — all three remaining mentions are explicitly historical and say so. |
| Inconsistent test updates | **None.** All three call sites in `ImageProxyExtTest.kt` (`:71`, `:80`, `:92`), both in `ScannerFocusIndependentVerificationTest.kt` (`:213`, `:234`), and the one in `P0_2_FreshVerificationTest.kt` (`:102`) call `.toCroppedBitmap()` with no argument. Read all six directly. |
| Coverage lost by deleting the `false`-path test | **No.** The uncropped-return outcome is still asserted by `degenerate rect falls back to uncropped decoded bitmap` (`ImageProxyExtTest.kt:77–83`) and its rotation-90 sibling (`ScannerFocusIndependentVerificationTest.kt:207–218`). The deleted test covered a path that no longer exists; deleting it was correct, not a coverage cut. |

The removal is also documented at the point of danger rather than only in `changes.md`:

```kotlin
// [review-verdict.md, iteration 2] `cropToGuideFrame` used to default to `false` and was never
// called that way in production — the only real call site (ScannerViewModel.kt) always passed
// `true`. A boolean parameter nobody varies, defaulting to the value that silently contradicts
// the function's own name, is the same-shaped trap the header above just eliminated for the
// decode step. Dropped: this function always crops, matching its only real caller.
```
(`ImageProxyExt.kt:20–24`)

That is a why-not-what comment stating the trap class, sitting directly above the signature a
future editor would be re-adding the parameter to. Exactly the right placement. (One nit on its
citation — NEW-4 below.)

**Verdict: fully resolved, no residue.**

---

## 2. P3-4 — is the string-prefix collision genuinely fixed, or relocated?

**Genuinely fixed, and fixed structurally — the collision cannot recur because the colliding
string no longer exists.** This is stronger than what I asked for.

The current `boundVia`, read at `ScannerScreen.kt:696–702`:

```kotlin
val boundVia = if (ultrawideId == null) {
    "default-back-camera"
} else {
    "ultrawide-physical-requested (requested physical id=$ultrawideId; bound " +
        "logical camera id=$actualId — expected to differ, a physical id does " +
        "not change the logical id, this is not a failure signal)"
}
```

The complete value set is now exactly two strings, and `"default-back-camera"` shares no prefix
with `"ultrawide-physical-requested"`. My pass-2 finding was that `"ultrawide-physical"` was a
prefix of `"ultrawide-physical-MISMATCH"` — the `MISMATCH` branch is deleted, so there is no
second, opposite-meaning value for a `grep boundVia=ultrawide-physical` to also match. Confirmed
independently by grepping `MISMATCH|confirmed id=` across the whole `app/src` tree: no hits.

I specifically checked whether the ambiguity was *relocated* rather than removed — three ways:

- **Into the `boundVia` string body?** No. `"requested"` is the only outcome word, and the
  parenthetical states plainly that differing ids are *"not a failure signal."*
- **Into `logCameraAfCapabilities`'s own log line?** No. `:301–307`'s other fields
  (`camera id=`, `minFocusDistance=`, `requestedPhysicalId=`, `requestedPhysicalMinFocusDistance=`)
  are all bare values, no verdict wording.
- **Into a new comparison elsewhere?** No. `actualId` is now interpolated only, never compared —
  grep confirms `actualId` appears at `:695` and `:700` and nowhere else.

The design lesson was also written into the code, not just the artifact:

```kotlin
// Comparing this against a requested physical id can never
// be true; it exists purely for informational logging, not as a pass/fail verdict. Never throws;
// null just means "couldn't determine."
```
(`ScannerScreen.kt:317–319`)

**One honest residue, and it is a spec-labelling gap rather than a code defect** — see NEW-5.

**Verdict: fixed by construction, not relocated.**

---

## 3. P3-5 — was the format diagnostic restored cleanly, or bolted on?

**Restored cleanly, in the right place, with the right ordering.** Not bolted on.

```kotlin
fun ImageProxy.toCroppedBitmap(): Bitmap {
    Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")
    val decoded = toBitmap()
```
(`ImageProxyExt.kt:25–27`)

Four things I checked rather than assumed:

1. **Location.** The spec asked for it *"at the top of `toBitmap()`"* (`specs.md:301–304`). That
   function no longer exists; `toCroppedBitmap()` is its structural successor and the first line
   of it is the equivalent position. Correct translation, not a convenient one.
2. **Ordering — the load-bearing detail.** The log fires *before* `toBitmap()`. If CameraX's decode
   ever throws on an unexpected format, the format has already been printed. Logging after the
   decode would have been the useless version of this diagnostic. Whether by intent or not, this is
   the ordering that makes the line worth restoring at all.
3. **Not log spam.** Grepped every call site: one production caller, `ScannerViewModel.kt:69`, on
   the capture path. The per-frame analysis path uses `detectCardInFrame(imageProxy)`
   (`ScannerScreen.kt:597`), a different function that never reaches this line. One log per capture,
   not per frame. This was the main risk of restoring it and it does not materialise.
4. **No new test breakage or hidden fixture coupling left unmet.** The line reads `format` and
   `planes` off the mock, so every fixture calling `toCroppedBitmap()` must now stub both plus
   `Log.i`. All three do — `ImageProxyExtTest.kt:53,56` + `:25`,
   `ScannerFocusIndependentVerificationTest.kt:180,183` + `:193`,
   `P0_2_FreshVerificationTest.kt:42,50` + `:87`. Read each directly. Nothing was left to luck.

The restore does create one real cost, which is NEW-3 below: it silently falsified a comment in
`P0_2_FreshVerificationTest.kt` that the pass's *other* cheap fix had just edited.

**Verdict: cleanly restored, correctly placed, correctly ordered.**

---

## 4. New maintainability findings introduced by this fix pass

Five, all P3, none blocking. Listed most-to-least worth acting on.

### NEW-3 (P3) — `P0_2_FreshVerificationTest`'s justification comment is now factually false about the code beneath it

- **File:** `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/P0_2_FreshVerificationTest.kt:105–108`
- **Confidence:** 9/10 — both the comment and the production line it describes quoted below, read
  in full this pass.
- **This is the one genuine interaction defect the pass produced**, and it is exactly the "pressure
  produced new debt" case the brief asked me to hunt for.

The comment:

```kotlin
// Never stubbed proxy.planes[1]/[2] access paths, and mockk's default relaxed-off
// proxy would throw MockKException on any un-stubbed call the production code
// actually made -- the test passing at all is itself proof toCroppedBitmap's decode
// path never touches planes.
```

The production code it makes that claim about, after this same pass's *other* cheap fix landed:

```kotlin
Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")
```
(`ImageProxyExt.kt:26`)

`toCroppedBitmap` now **does** touch `planes`. Two separate inaccuracies:

1. *"never touches planes"* is false as written. The test's **name** is still accurate — `new
   toCroppedBitmap never indexes planes and does not throw...` (`:84`) — and indexing is what
   actually matters, so the test itself is sound. Only the comment overstates.
2. **The named safety mechanism is now the wrong one.** The comment credits mockk strictness, but
   `proxy.planes` *is* stubbed (`:42`, `every { proxy.planes } returns arrayOf(onlyPlane)`). What
   would actually catch a `planes[2]` read is the real one-element array throwing
   `ArrayIndexOutOfBoundsException` — array bounds, not mockk. The guard still works; the
   explanation of *why* it works is wrong.

Root cause is structural, not careless: the comment was correctly updated for the parameter drop
(`:96–98` explicitly notes *"review-verdict.md iteration 2 dropped the cropToGuideFrame arg"*) and
not re-checked after the format-log restore landed in the same pass. Each fix documented itself
against the other's pre-change state. No test fails, no lint fires — nothing mechanical catches it.

**Suggested fix (one comment, ~3 lines):** replace with *"`singlePlaneJpegProxy()` returns a real
one-element array, so any `planes[1]`/`planes[2]` read in the production path throws
`ArrayIndexOutOfBoundsException` and fails this test. The diagnostic log's `planes.size` read is
safe on a one-element array."*

### NEW-1 (P3) — `actualBoundCameraId`'s value is now fully redundant with the log line that consumes it; the helper could be deleted

- **File:** `ScannerScreen.kt:312–322` (the helper + its 8-line doc comment), `:695`, `:699–701`
  (the only use), `:279`/`:301` (the duplication)
- **Confidence:** 9/10 — all four sites quoted, `actualId`'s use grepped repo-wide (two hits only).

`actualBoundCameraId` computes:

```kotlin
private fun actualBoundCameraId(camera: Camera): String? =
    runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }.getOrNull()
```
(`:321–322`)

Its only consumer interpolates it into `boundVia`, which is passed to `logCameraAfCapabilities` —
which independently computes and prints **the same value**:

```kotlin
val info = Camera2CameraInfo.from(camera.cameraInfo)          // :279 — identical expression
...
"camera id=${info.cameraId} boundVia=$boundVia minFocusDistance=$chars ..."   // :301
```

So the emitted line carries every value twice:

```
camera id=<L> boundVia=ultrawide-physical-requested (requested physical id=<P>; bound logical
camera id=<L> — ...) minFocusDistance=... requestedPhysicalId=<P> requestedPhysicalMinFocusDistance=...
```

`<L>` appears as both `camera id=` and `bound logical camera id=`; `<P>` as both `requested
physical id=` (inside `boundVia`) and `requestedPhysicalId=` (from `:303–304`).

This is a direct consequence of the fix, not pre-existing: before this pass `actualId` was
load-bearing — it fed the `actualId == ultrawideId` comparison. With the comparison deleted, a
whole private function plus an 8-line doc comment now exists solely to produce a string the
consuming log line already prints two fields later. Related nit, folded in rather than listed
separately: `val actualId = actualBoundCameraId(cam)` (`:695`) is computed unconditionally but
unused on the `ultrawideId == null` branch.

**Suggested fix:** delete `actualBoundCameraId` and `actualId`; shorten `boundVia`'s else-branch to
`"ultrawide-physical-requested (requested physical id=$ultrawideId — the bound logical camera id is
logged separately as camera id=; a physical id does not change it, so they are expected to differ)"`.
Net −12 lines with no information lost from the log.

**Counter-argument, stated honestly:** the helper's doc comment (`:312–319`) is genuinely
high-value — it is the artifact preventing someone from re-introducing the comparison. Deleting the
function should move that comment to `boundVia`'s site, not discard it. That caveat is why this is
a P3 and not a P2.

### NEW-2 (P3) — the "physical id ≠ logical id" rationale is now stated three times in one file

- **File:** `ScannerScreen.kt:270–275`, `:312–319`, `:683–694`
- **Confidence:** 9/10 — all three blocks read in full this pass.

The same mechanism is explained at length in three places:

```kotlin
// [review-verdict.md, iteration 2] minFocusDistance above is always the LOGICAL camera's own
// floor — a session-level physical-camera-id option (see selectUltrawidePhysicalCameraId's doc
// comment) never changes which CameraInfo the bound Camera exposes...
```
(`:270–272`)

```kotlin
// A session-level physical
// camera id selects which physical stream is opened per output config; it never changes which
// (logical) CameraDevice is opened...
```
(`:313–315`)

```kotlin
// [review-verdict.md, iteration 2] NOT outcome-based, deliberately: actualId
// (the bound LOGICAL camera id) and ultrawideId (the REQUESTED PHYSICAL camera
// id) live in structurally disjoint id namespaces — see actualBoundCameraId's
// doc comment above.
```
(`:683–686`)

What makes this worth flagging rather than shrugging at: **two of the three already contain the
cross-reference that would make the restatement unnecessary** — `:271` says *"see
selectUltrawidePhysicalCameraId's doc comment"* and `:685` says *"see actualBoundCameraId's doc
comment above"* — and then restate the content in full anyway. That is the worst of both: the
pointer exists *and* the prose is triplicated, so a future correction to the mechanism has three
places to land and two of them look authoritative.

Given this file's history (six attempts at one bug family), over-documenting is a defensible bias,
which is why this is P3 and not higher. But if the CameraX behaviour is ever restated more
precisely, all three need editing.

**Suggested fix:** keep `:312–319` (the natural home, attached to the function that reads the id) in
full; cut `:270–275` and `:683–694` to one sentence each plus the cross-reference they already have.

### NEW-6 (P3) — `logCameraAfCapabilities` grew a four-level nested lookup with a duplicated `Camera2CameraInfo.from()` call

- **File:** `ScannerScreen.kt:286–298`
- **Confidence:** 9/10 — read in full; the double `from(physicalInfo)` is on `:289` and `:293`.

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

Four nesting levels (`?.let` → `firstOrNull { runCatching }` → `?.let { runCatching }`), all inside
the function's own outer `runCatching { }` at `:278`. `Camera2CameraInfo.from(physicalInfo)` is
constructed twice on the same object — once to match, once to read.

The added value is real and I credit it under §6 — the logical camera's `minFocusDistance` is
useless for answering "did the close-focus lens engage," so reading the physical camera's own
characteristic is the right addition. The cost is that a diagnostic-only function went from ~15
lines to 33 and now needs a second read to follow.

**Suggested fix:** extract `private fun physicalMinFocusDistance(camera: Camera, physicalId: String):
Float?`. Flattens the nest to one level, names the intent, and lets `from()` be called once.

*I explicitly considered and dismissed a related concern:* that this duplicates
`selectUltrawidePhysicalCameraId`'s `getPhysicalCameraInfos()` iteration (`:363–378`). It does not —
that one queries `provider.availableCameraInfos` pre-bind with a focal-length predicate, this one
queries `camera.cameraInfo` post-bind with an id predicate. Different sources, different objects,
different questions. The String round-trip between them is API-imposed, not a design mistake. Not a
finding.

### NEW-4 (P3, nit) — three new source comments cite `[review-verdict.md, iteration 2]`, an artifact `.pipeline/` overwrites every run

- **Files:** `ImageProxyExt.kt:20`, `ScannerScreen.kt:270`, `ScannerScreen.kt:683` (+
  `P0_2_FreshVerificationTest.kt:97` in prose)
- **Confidence:** 8/10 — all four grepped; `.pipeline_archive/` listed directly.

`.pipeline/review-verdict.md` is overwritten by the next pipeline run. `.pipeline_archive/` already
holds three prior runs (`2026-07-16-db-backup`, `2026-08-28-nav-quickscan-tests`,
`2026-09-04-binder-backup`), so the document survives archiving — but "iteration 2" doesn't say
*which* run, and a reader following the citation from source lands on a *different, unrelated*
verdict file.

**Mitigating, and it matters:** this is a pre-existing project convention, not new debt —
`domain/BackfillCardNamesUseCase.kt:15` already cites `.pipeline/specs.md` the same way. And the
line directly above the worst offender shows the better pattern in the same file:

```kotlin
// [gaps.md, 2026-09-04] This file used to declare its own `ImageProxy.toBitmap()` extension
```
(`ImageProxyExt.kt:7` — durable file, dated, unambiguous.)

**Suggested fix (if ever worth the keystrokes):** re-tag as `[gaps.md, 2026-09-04]` or
`[review 2026-09-04]`. Cosmetic. Listed for completeness because this is the last pass.

### NEW-5 (P3, nit) — AC1's Success Metric is no longer literally satisfiable, and that is nowhere recorded

- **Files:** `.pipeline/specs.md:141` vs `ScannerScreen.kt:696–702`; `.pipeline/changes.md:549–552`
- **Confidence:** 8/10 — both quoted.

The spec's AC1 and Success Metric name a *confirmation*:

> confirmed via the extended diagnostic log (`boundVia=ultrawide-physical`)

`changes.md` argues the new value still satisfies it:

> The new string still satisfies AC1's named confirmation substring (`ultrawide-physical`) since it
> reachably logs "the selection path engaged"

The substring does match. But the new value reports that a physical id was **requested**, not that
it **engaged** — which is precisely the honest thing to do, given the code structurally cannot
observe engagement. The gap is one of labelling, not correctness: `changes.md` frames a genuine
scope reduction ("we can confirm the request, not the outcome") as full satisfaction, and neither
`specs.md` nor `changes.md` records that the on-device Success Metric now needs a different signal
(`requestedPhysicalMinFocusDistance`, from `:304`) to actually answer its question.

**Suggested fix:** one clause in `changes.md` or `specs.md` stating that AC1's confirmation is
downgraded to "request confirmed" and that `requestedPhysicalMinFocusDistance` is the outcome
proxy. Costs nothing; keeps the next real-device session from re-litigating what the log means.

---

## 5. Do-not-touch list — spot-checked directly in the live files, not trusted

I checked **all seven** rather than the two the brief asked for. Every one grepped in the current
file this pass:

| Item | Live-file evidence | Verdict |
|---|---|---|
| `88f/63f` triplication | `ScannerScreen.kt:76`, `:151`, `:190` — `grep -n "88f\|63f"` returns exactly these three | **Untouched** |
| `20f` threshold shadowing | `:326` (`ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 20f`) vs `:423` (`focalLengths?.any { it < 20f }`) and `:428` (`"(<20mm focal length..."`) | **Untouched** |
| Duplicated `sun.misc.Unsafe` fixture | `grep -rln "sun.misc.Unsafe" app/src/test` → exactly two files (`ScannerScreenTest.kt`, `ScannerFocusIndependentVerificationTest.kt`). **No third copy added.** | **Untouched** |
| Stale "~9 in (23 cm)" text | `:243`, `text = "Hold card ~9 in (23 cm) back"` | **Untouched** |
| `ImageRect`'s untrue "exclusive" comment | `:131`, `// Plain rect in raw image-space (pre-rotation) pixel coordinates — right/bottom exclusive,` | **Untouched** |
| `var` Triple + semicolon-chained reassignment (my pass-2 P3-2) | `:640` `var (preview, capture, analysis) = ...`; `:668` `preview = fallback.first; capture = fallback.second; analysis = fallback.third` | **Untouched** |
| `GUIDE_FRAME_WIDTH_RATIO` / Task 5 | `:60`, still `0.32f` | **Untouched** |

Diffstat corroborates the narrow scope: `ScannerViewModel.kt | 2 +-` (exactly the one call-site
line), and only the three declared production files changed at all.

**Under a third consecutive round of review pressure, the Coder still did not touch a single
adjacent known-imperfect line.** That is the discipline finding of this pipeline run and it is
worth more than any of my five nits.

---

## 6. What looks good in this fix pass

- **The fix chose the honest option over the impressive one.** `changes.md:525–534` rejects the
  `setSessionCaptureCallback` + `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` readback on the grounds
  that it *"would add a second `@RequiresApi` branch (API 29, on top of the existing API-28 gate) to
  an already-complex bind path... for a gain that isn't needed to fix the actual defect (a false
  verdict, not a missing one)."* That is the correct call on a file every lens has flagged for
  accumulating complexity — and it is the simplification-lens answer, chosen without being asked for
  it.
- **The prefix collision was fixed by deleting the colliding value, not by renaming it.** Structural
  fixes don't regress; renames do.
- **`requestedPhysicalMinFocusDistance` is a genuinely better signal than the one it supplements.**
  It reads a different `CameraInfo` object (`:293`) than the logical one (`:279`) — verified by
  tracing the lookup, not by trusting the variable name.
- **Net line count went *down* in `ImageProxyExt.kt` again** — a parameter and a branch deleted, one
  log line added.
- **The trap-class reasoning is recorded where the trap is**, not only in the pipeline artifact
  (`ImageProxyExt.kt:20–24`, `ScannerScreen.kt:312–319`).
- **The Tester's count reconciliation is the best evidence in this whole run.** 286 vs the prior
  287, with a per-file XML breakdown proving `ImageProxyExtTest.xml` went 4→3 and *no other file in
  the 31-file suite changed count* (`test-results.md:126–141`). That independently proves the
  deletion claim instead of accepting it — the correct way to verify "we deleted exactly one test."

---

## Score (this lens: maintainability + simplification) — PASS 3, FINAL

| Dimension | Score | Reasoning |
|---|---|---|
| Spec compliance | **24** / 25 | **+1 from pass 2's 23.** Both of pass 2's spec deductions are closed: the spec-mandated `captured format=... planes=...` diagnostic (`specs.md:301–304`) is genuinely restored and genuinely reachable on the only production capture path (`ImageProxyExt.kt:26` ← `ScannerViewModel.kt:69`), and AC1's literal verification substring no longer also matches its own failure case. Zero scope creep — verified item-by-item against the do-not-touch list in the live files (§5), not trusted from `changes.md`. Remaining −1: the pass-1 −2 for Task 4's stated anti-drift purpose (the `88f/63f` triplication survives) partially persists, offset by the diagnostic restore; plus NEW-5, where a genuine downgrade of AC1 from "confirmed engaged" to "confirmed requested" is argued as full satisfaction rather than recorded as the honest scope reduction it is. |
| Correctness & bug-freedom | **23** / 25 | **Flat at 23, and flat is correct.** I did not deduct for the `boundVia` logical-vs-physical defect in pass 2 — my lens scored it as a P3 string-shape issue, not the P0 the other two lenses found — so I cannot now award points back for its repair without double-counting their finding as mine. What I *can* confirm from my own reading: no new functional bug in this pass, the crop path's degenerate-rect and throw-safety guards are byte-identical to pass 2 (`ImageProxyExt.kt:30–42`), and the new physical-camera lookup is `runCatching`-bounded at three independent levels so it cannot break the diagnostic it extends. −2 remains for the two latent edges carried since pass 1 (the 1-px shift on mirrored rotations, the missing fit guard at `:143` vs `:75`) — both still inert, both still correctly deferred. NEW-3 is a comment defect, not a correctness one; the test it sits in still genuinely guards. |
| Security & reliability | **20** / 20 | **+1 from 19, and this dimension is now clean.** Pass 2's sole −1 was diagnosability: *"`ultrawide-physical` being a prefix of `ultrawide-physical-MISMATCH` means the spec's own grep-level success check can't distinguish them."* That is closed by construction (§2) — one string, no collision, `grep MISMATCH` across `app/src` returns nothing. Diagnosability also improved on two further axes I checked directly: the capture-format line is back and correctly ordered *before* the decode that might throw, and `requestedPhysicalMinFocusDistance` adds the one number that can actually answer the on-device question. No reliability regression: the added lookup is triple-`runCatching`-bounded, and the restored log is on the capture path (one call per capture via `ScannerViewModel.processImage`), not the per-frame analysis path — checked by grepping every call site, since per-frame log spam was the real risk of restoring it. |
| **Maintainability & simplification** | **10** / 15 | **+1 from 9.** The pass closed my only open P2 (the vestigial `cropToGuideFrame` flag — the last silent-wrong-result trap in this diff) with zero residue across six call sites and five files, and closed two of four P3s. Against that it opened five new P3s: one real comment-drift defect (NEW-3, where the pass's own two fixes falsified each other's documentation), a now-redundant helper the fix itself made vestigial (NEW-1), triplicated rationale prose (NEW-2), a four-level nest in a diagnostic (NEW-6), and an ephemeral-artifact citation (NEW-4). Severity-weighted that is a clear net gain — a P2 trap traded for five nits with zero behavioral risk — but only +1, because the −6 that dominates this dimension is the three carried Important findings (`88f/63f` triplication, `20f` shadowing, duplicated `Unsafe` fixture), all still byte-for-byte unchanged and all still correctly out of scope. This diff's polish debt is unchanged; only its trap count went down. |
| Test evidence quality | **13** / 15 | **Flat at 13, with the composition shifted again.** **Up:** the deletion claim was proved rather than asserted — 286 vs 287 reconciled through a per-file XML breakdown showing `ImageProxyExtTest.xml` 4→3 and every other file in the 31-file suite unchanged (`test-results.md:126–141`), which is the right way to verify "exactly one test removed." Lint parsed structurally again (`{'Warning': 81}`, zero `Error`), full suite rerun with `--rerun-tasks` after deleting the results dir. **Down, three ways, unchanged from pass 2 plus one new:** the bind-wiring block still has zero automated coverage — and this iteration's entire blocking fix lived inside it, so the P0's repair is verified by *inspection*, not execution (the Tester is honest about this, `test-results.md:16–44` is explicitly a read-the-file verification); `guideFrameImageRect degenerate...` still names its input rather than its result; and `P0_2_FreshVerificationTest`'s self-documentation drifted this pass (NEW-3) on top of its first test still being labelled a *"permanent regression guard"* while asserting on a private reconstruction of deleted code. The count-reconciliation win offsets the new drift; 13 holds. |
| **Total** | **90** / 100 | ≥85, zero P0/blocking findings from this lens → **ship**. |

**Delta from pass 2: +3 (87 → 90). Full run on this lens: 86 → 87 → 90.**

Unlike pass 2's +1, this delta is genuinely mine to claim: all three closed items — the vestigial
parameter, the prefix collision, the dropped diagnostic — were findings *my* lens raised, so the
movement reflects my own axes rather than borrowing another lens's repair. (Per
`~/.claude/rules/lessons/re-review-score-moves-only-for-your-own-lens.md`, which is why pass 2 was
correctly near-flat and this one correctly is not.)

---

## Sign-off

**Approval status:** **Approved — ship** (this lens; the aggregate is the orchestrator's, taking
the minimum across all three).

**Nothing here blocks the commit, and I am not hedging that.** The brief asked me to be exhaustive
since no automated pass follows, so five P3 nits are written up at length — but every one of them
is a comment, a redundant field in a log line, or a nesting level. Zero affect runtime behavior.
Zero would survive contact with a hostile reviewer as a reason to hold a commit.

If Skyler wants a cleanup pass before `wrapcon`, in value order — all zero-behavior-change:

1. **Fix `P0_2_FreshVerificationTest.kt:105–108`'s comment** (NEW-3). ~3 lines. The only new finding
   that actively misinforms a future reader, and it misinforms them about the exact bug family this
   whole run existed to fix.
2. **Name the card-aspect constant**, substitute the three `88f / 63f` sites (P2-1, carried since
   pass 1 — the largest single maintainability item left in this file).
3. **Replace `it < 20f` at `:423` with `ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM`** and interpolate it
   into the adjacent log string at `:428` (P2-2, carried).
4. **Delete `actualBoundCameraId` and `actualId`** (NEW-1), moving its doc comment to `boundVia`'s
   site. Net −12 lines, no information lost from the log.
5. **One clause recording AC1's honest downgrade** (NEW-5) — worth doing *before* the real-device
   logcat read, since that read is the whole point.

Follow-up-pass work, not this-commit work: the shared `Unsafe` fixture and JPEG-proxy mock builder
(P2-3 + P3-6), the `UseCases` data class (P3-2), extracting `physicalMinFocusDistance` (NEW-6),
de-duplicating the triplicated rationale comment (NEW-2), and renaming
`P0_2_FreshVerificationTest` / annotating its frozen-reconstruction test for what it actually is.

**Process note for the human report:** the one thing still genuinely unreachable from the JVM is
unchanged — only `adb logcat -s ScannerFocus:*` on the real S26 Ultra can confirm the HAL honors the
physical-camera-id option. What *has* changed is that the log line is now trustworthy for the first
time in this bug family's six attempts: it no longer asserts an outcome the code cannot observe, and
`requestedPhysicalMinFocusDistance` gives that read an actual answer to look for.

**Date:** 2026-09-08
