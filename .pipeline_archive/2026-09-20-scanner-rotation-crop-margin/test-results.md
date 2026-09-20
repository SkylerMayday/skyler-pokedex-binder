# Test Results — Scanner capture: rotation fix + guide-frame border/margin

**Deviation disclosed**: run by the orchestrator directly, not the `pipeline-tester` subagent. Reason:
same as this project's own precedent (session 13, session 16) — the Coder hit the standing Gradle
daemon IPC wall and could not run any Gradle task. Orchestrator ran the same commands directly.

## Result: 1 real compile error found, root-caused, fixed — now 305/305 passing

First run (`testDebugUnitTest --rerun-tasks`): **compile failure**, not a test failure —
`ImageProxyExtTest.kt:10:17 Unresolved reference 'anyConstructed'`.

**Root cause**: the Coder added `import io.mockk.anyConstructed` to the new rotation test. That
import doesn't resolve because `anyConstructed<T>()` isn't a top-level function in MockK — it's a
member of `MockKMatcherScope`, available implicitly inside an `every { }` block, not importable on
its own. Confirmed against this codebase's own existing working precedent
(`BackupImporterTest.kt:24-27,338,365`), which uses `anyConstructed<Intent>()` successfully with
`mockkConstructor` imported but **no** `anyConstructed` import at all. This is a Coder mistake
(a plausible-looking but wrong import), not a spec error, and not a production-code defect.

**Fix applied**: removed the bogus import line from `ImageProxyExtTest.kt`. No other change needed —
the test body's usage of `anyConstructed<Matrix>()` inside `every {}` was already correct.

## Verbatim evidence

Full command output (both runs) not separately archived to a sidecar this pass — captured directly
in this orchestrator's own tool output. Decisive excerpts:

Pre-fix (compile failure):
```
e: file:///D:/Claude%20Projects/PokedexBinderV2/app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExtTest.kt:10:17 Unresolved reference 'anyConstructed'.
FAILURE: Build failed with an exception.
> A failure occurred while executing ... GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details
BUILD FAILED in 1m 25s
```

Post-fix:
```
BUILD SUCCESSFUL in 1m 34s
31 actionable tasks: 31 executed
```

Fresh JUnit XML summary, post-fix (`app/build/test-results/testDebugUnitTest/*.xml`):
```
tests=305 skipped=0 failures=0 errors=0
```
(305 = 303 pre-existing + 2 new rotation tests from this diff.)

`assembleDebug --rerun-tasks` (production build, post-fix): `BUILD SUCCESSFUL in 1m 15s`, 41/41
tasks executed.

## Acceptance criteria trace (against `.pipeline/specs.md`)

- [x] Rotation fix: `toCroppedBitmap()` rotates via `Matrix().postRotate()` for non-zero rotation,
      no-ops (no `Matrix` constructed) at `rotation == 0` — both paths covered by new tests, both pass.
- [x] `CardFrameOverlay` draws one continuous `drawRect` border, corner brackets removed.
- [x] `CROP_MARGIN_FACTOR = 1.10f` applied only inside `guideFrameImageRect()`, not
      `detectCardInFrame()` or the drawn box — confirmed by reading the diff, only one call site changed.
- [x] All 3 "TEMP DIAGNOSTIC (2026-09-17)" blocks removed; `ScannerViewModel.kt`'s separate
      "TEMP DIAGNOSTIC (2026-09-11)" block confirmed untouched (not in this diff's file list at all).
- [x] `testDebugUnitTest --rerun-tasks`: 305/305, fresh XML. `assembleDebug --rerun-tasks`: clean.
- [ ] **Human-in-the-loop live-scan gate — NOT run yet.** Per spec, this is outside Tester/Reviewer
      scope; orchestrator coordinates with Skyler after the Reviewer stage completes.

**Status: DONE** (automated scope). No Stage 4 (Debugger) needed — the one failure found was a
one-line bad-import fix, root-caused and corrected within this stage, not a production defect
requiring separate root-cause diagnosis.
