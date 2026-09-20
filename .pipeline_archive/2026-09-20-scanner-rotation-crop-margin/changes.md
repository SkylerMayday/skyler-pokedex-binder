# Changes — Scanner capture: rotation fix + guide-frame border/margin

Implements `.pipeline/specs.md` exactly (both bugs). No scope creep.

## Files changed

### `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt`
- **Bug 1 fix**: `toCroppedBitmap()` now rotates the cropped bitmap via `Matrix().postRotate(rotation.toFloat())` + the 6-arg `Bitmap.createBitmap(cropped, 0, 0, w, h, matrix, true)` overload, inside the existing `runCatching` block. `rotation == 0` short-circuits to the plain `cropped` bitmap — no `Matrix` constructed, byte-identical to prior behavior.
- Added `import android.graphics.Matrix`.
- Removed the "TEMP DIAGNOSTIC (2026-09-17)" `cropRect=...` `Log.i` block (this block was itself an uncommitted addition from the prior session — removing it reverts this section back to HEAD's shape, confirmed via `git show HEAD:...`).
- Did not touch `guideFrameImageRect()`'s crop-position math (per spec) or the permanent `captured format=...` log line (per spec).

### `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`
- **Bug 2 fix**: added `CROP_MARGIN_FACTOR = 1.10f` constant next to `GUIDE_FRAME_WIDTH_RATIO`, applied *only* inside `guideFrameImageRect()` (via new `boxW`/`boxH` locals used in the `dLeft`/`dTop`/`dRight`/`dBot` computation) — not in `detectCardInFrame()` and not in `CardFrameOverlay`'s drawn box, per spec.
- `CardFrameOverlay`: replaced the 8 `drawLine` corner-bracket calls with a single `drawRect(color = frameColor, topLeft = Offset(left, top), size = Size(guideW, guideH), style = stroke)` — full continuous border, uses guideW/guideH (undrawn/un-inflated), unaffected by `CROP_MARGIN_FACTOR`.
- Removed the 2 "TEMP DIAGNOSTIC (2026-09-17)" blocks in this file: `guideFrameImageRect()`'s trailing `Log.i` trace, and `CardFrameOverlay`'s `onSizeChanged { Log.i(...) }` wrapper (reverted `Box(modifier = modifier.onSizeChanged {...})` back to plain `Box(modifier = modifier)`). Both were also uncommitted prior-session additions — confirmed absent from HEAD, so removing them is a clean revert, not a new behavior change.
- Removed now-unused imports `StrokeCap` and `onSizeChanged` (both only used by the removed code).
- Left `ScannerViewModel.kt`'s separate "TEMP DIAGNOSTIC (2026-09-11)" block untouched, as instructed — not part of this file anyway.

### `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExtTest.kt`
- Updated the two `Bitmap.createBitmap` pixel-literal assertions affected by `CROP_MARGIN_FACTOR`: `(125,476,750,1047)`→`(87,424,825,1151)` and `(225,676,750,1047)`→`(187,624,825,1151)`. Independently re-derived via a from-scratch Python re-implementation of `guideFrameImageRect`'s exact int-truncation arithmetic (not copied blindly from the spec) — matched the spec's precomputed values exactly, so no additional 1px-rounding surprise this time.
- Added 2 new tests per spec's "Edge cases to handle" mocking recipe:
  - `rotation 0 constructs no Matrix` — `mockkConstructor(Matrix::class)` + `verify(exactly = 0) { anyConstructed<Matrix>().postRotate(any()) }`, confirming the no-op path never touches `Matrix`.
  - `nonzero rotation rotates the cropped bitmap via Matrix postRotate` — stubs `cropped.width`/`cropped.height` (unstubbed pre-fix, now read), `anyConstructed<Matrix>().postRotate(90f)`, and the 6-arg `Bitmap.createBitmap` overload returning a distinct `rotated` mock, asserted via `assertSame`.
- Both wrapped in `try/finally { unmockkConstructor(Matrix::class) }` so a failing assertion still cleans up the global constructor mock (this codebase's other tests use `@After` for static mocks; `mockkConstructor` needs its own explicit unwind since it's scoped per-test here, not per-class).

### `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt`
- Updated `guideFrameImageRect` pixel-literal assertions for rotation 0/90/180/270 per the spec's table, cross-checked against the same independent Python re-implementation:
  - rotation 0: `left 500→350, right 3500→3650, width 3000→3300` (top/bottom/height unchanged, already saturated).
  - rotation 90: `left 429→272, top 375→263, right 3571→3728, bottom 2625→2738`.
  - rotation 270: `left 429→272, top 375→262, right 3571→3728, bottom 2625→2737`.
  - rotation 180: `left 500→350, right 3500→3650` (top/bottom unchanged).
- Left the degenerate-extreme-aspect-ratio test and both `offsetBy` tests unchanged (per spec — already-saturated clamp and relative-only assertions respectively, confirmed unaffected by the independent re-derivation too).
- Updated the header comment above the rotation tests and renamed the rotation-270 test (`...lands on the same rect as 90...` → `...lands 1px off rotation 90 once CROP_MARGIN_FACTOR applies (arithmetic, not a bug)`) since the old text asserted 90/270 land on identical numbers, which is no longer true once the margin applies unevenly per rotation.

## Simplification & review pass

- **Reuse**: no existing helper duplicated; `Matrix`/rotation is a one-off, `CROP_MARGIN_FACTOR` follows the exact `GUIDE_FRAME_WIDTH_RATIO` constant convention already established in this file.
- **Quality**: removing 8 `drawLine` calls for 1 `drawRect` call is a net simplification, not just equivalent-but-different; also let `StrokeCap` import go since round line caps were bracket-specific and a full rect border doesn't need caps. No new nesting, no new boolean flags.
- **Efficiency**: rotation only runs on the already-small cropped bitmap (per spec's own risk note), not the raw ~4000x3000 decode; no new allocations beyond the second `Bitmap.createBitmap` call that already existed in intent (spec's own snippet).
- **Correctness**: confirmed both bugs' fixes independently (own Python re-implementation of the float/int arithmetic, not just copying the spec's literals) before writing them into test assertions — this file has a real prior 1px-rounding bug in its history (2026-09-10-ish, referenced in comments), so this was not a formality.
- No dead code, no stray debug prints introduced (checked via `git diff | grep -iE "println|TODO|FIXME|debugger"` — zero matches).

## Verification (Item M — all 7 checks)

Full verbatim output: `.pipeline/evidence/changes-verify.log`.

1. **Typecheck**: N/A to run standalone — this project's typecheck is part of the Gradle build (Kotlin compile), which hit the sandbox wall below (same as checks 2-4). Not silently skipped — see check 4.
2. **Lint**: same as above — Gradle-driven, blocked by the same wall.
3. **Scoped test run** (`testDebugUnitTest --rerun-tasks`): **blocked**, see below.
4. **Production build** (`assembleDebug --rerun-tasks`): **blocked**, see below.

   Ran `gradlew.bat testDebugUnitTest assembleDebug --rerun-tasks` via a real `.ps1` (`JAVA_HOME=D:\jdk17\jdk-17.0.14+7`, `TEMP`/`TMP` set) invoked with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File`, 3 times (2nd also with `--no-daemon`) — all 3 attempts hit the documented sandbox-specific Gradle daemon IPC wall before any compilation started:
   ```
   FAILURE: Build failed with an exception.
   * What went wrong:
   java.io.IOException: Unable to establish loopback connection
   ```
   This is the known intermittent issue flagged in the task brief as hitting subagents more reliably than the orchestrator. Per that guidance, stopping after 3 attempts rather than retrying further — the orchestrator will verify checks 1-4 directly afterward. Every literal in the diff was independently re-derived by hand (Python re-implementation of the exact int-truncation arithmetic, see above) specifically because this build couldn't be used to confirm them locally.

**Update (Tester stage, orchestrator-run — see `.pipeline/test-results.md`)**: the Gradle wall cleared for the orchestrator, as usual. It surfaced a REAL compile error this diff introduced: `ImageProxyExtTest.kt`'s `import io.mockk.anyConstructed` doesn't resolve — `anyConstructed<T>()` is a `MockKMatcherScope` member, not a top-level import, confirmed against this codebase's own working `BackupImporterTest.kt` precedent which uses it without that import. Fixed by removing the bogus import line. Independently re-derived pixel literals were NOT affected — after the import fix, `testDebugUnitTest --rerun-tasks`: **305/305 passing, fresh XML**; `assembleDebug --rerun-tasks`: `BUILD SUCCESSFUL`. The hand-derivation methodology (Python re-implementation) is vindicated by this outcome — the literals were right, only an unrelated import was wrong.
5. **Dev/start server boots**: N/A — Android app, no dev/start server.
6. **No stray debug artifacts**: confirmed clean — `git diff -- <4 changed files> | grep -iE "println|TODO|FIXME|debugger|console\.log"` returned zero matches. No scratch files left in the repo (scratchpad script lives outside the repo).
7. **`git status` shows only intended files**: confirmed —
   ```
   M app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt
   M app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt
   M app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExtTest.kt
   M app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt
   ```
   plus 2 pre-existing items from before this session started (`M gaps.md`, `?? .pipeline_archive/2026-09-17-scanner-hash-confidence/`) — neither touched this session, both present in the conversation's initial `git status` snapshot.

## New-dependency check (Item P)

N/A — no manifest changed, no new dependency added (uses only stdlib Android `graphics.Matrix`/`graphics.Bitmap` and existing test deps already used elsewhere in this file: `mockk`'s `mockkConstructor`/`anyConstructed`/`verify`, all already a transitive part of the existing `io.mockk` test dependency).

## Not done (out of scope, per spec)

- `GUIDE_FRAME_WIDTH_RATIO` unchanged.
- `PerceptualHasher.kt`/`SmartThresholdUseCase.kt` untouched.
- `detectCardInFrame()`'s duplicated geometry not refactored to call `guideFrameImageRect()`.
- Live-device recalibration and the mandatory human-in-the-loop `adb pull` scan check — outside this pipeline's Tester/Reviewer stages per spec, orchestrator coordinates with Skyler after Reviewer.

## Friction Notes

- Hit the documented sandbox Gradle daemon IPC wall ("Unable to establish loopback connection") on all 3 attempts (`--rerun-tasks`, then `--rerun-tasks --no-daemon`, then a bare retry) — 0/3 even got to compilation. Confirms the task brief's warning that this hits subagents more reliably than the orchestrator; the retry budget given (stop after a few, don't grind) was the right call here rather than trying 5+ more variations.
- `powershell.exe -File` writing through `Tee-Object` produces UTF-16LE output by default (BOM included) — needed a manual `iconv`/Python re-decode before the log was usable as a plain-text evidence sidecar. Worth remembering for future PowerShell-sourced evidence files on this project.
- Cross-checking the spec's precomputed pixel literals against an independent from-scratch reimplementation (rather than trusting them or the build) turned out to matter here specifically because the build was unavailable — without that independent check, these test edits would have shipped fully unverified rather than arithmetically-verified.
