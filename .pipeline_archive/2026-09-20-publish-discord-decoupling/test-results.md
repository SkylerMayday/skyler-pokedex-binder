# Test Results — Decouple binder.json upload from Discord/changelog notification

**Deviation disclosed**: run by the orchestrator directly, not `pipeline-tester`. Same recurring
pattern as every other stage this session — the Coder's sandbox hit the Gradle daemon IPC wall,
orchestrator ran the identical commands directly, which cleared immediately.

## Result: 2 real test failures found, root-caused, fixed — now 315/315 passing

First run (`testDebugUnitTest --rerun-tasks`): 315 tests, **2 failures** —
`content-only publish uploads binder json but skips changelog and discord` and
`first publish with zero owned cards uploads binder json but skips changelog and discord`, both
failing at `assertTrue(result is PublishResult.Success)` — the very first assertion in each test.

**Root cause (traced via the fresh JUnit XML's full stack trace, not guessed)**: both failures were
`java.lang.AssertionError` at the `assertTrue` line, meaning `publish()` returned something other
than `Success`. Both tests take the new `else` branch (`diff.hasChanges == false`) of the
`if (diff.hasChanges) { ... } else { Log.i("PublishRepository", "Content-only publish...") }` block
the Coder added. This test file's `@Before` stubs `Log.w` via `mockkStatic(Log::class)` but never
stubbed `Log.i` — an unstubbed static call under MockK throws, and `publish()`'s own
`catch (e: Throwable) { PublishResult.Failure(...) }` silently converted that thrown exception into
a `Failure` result instead of the expected `Success`. **This is a test-infrastructure gap, not a
production logic bug** — the actual gating logic (`contentChanged` vs `hasChanges`, the upload
proceeding, changelog/Discord being skipped) was correct; the test just couldn't observe it because
the new diagnostic log line it added had no stub.

**Fix applied**: added `every { Log.i(any(), any<String>()) } returns 0` alongside the existing
`Log.w` stub in `@Before`. No production code touched.

## Verbatim evidence

Pre-fix (2 failures):
```
PublishRepositoryTest > content-only publish uploads binder json but skips changelog and discord FAILED
    java.lang.AssertionError at PublishRepositoryTest.kt:557
PublishRepositoryTest > first publish with zero owned cards uploads binder json but skips changelog and discord FAILED
    java.lang.AssertionError at PublishRepositoryTest.kt:612
315 tests completed, 2 failed
```
Full stack trace (fresh XML) confirmed the failure was at `assertTrue(result is PublishResult.Success)`
(line 572/627, the first assertion in each test body), not at any of the `contentChanged`/`hasChanges`
or `coVerify` assertions further down — ruling out a logic bug in the gating change itself.

Post-fix:
```
BUILD SUCCESSFUL in 1m 24s
31 actionable tasks: 31 executed
```
Fresh JUnit XML aggregate: `tests=315 skipped=0 failures=0 errors=0` (310 baseline + 5 new).

`assembleDebug lintDebug --rerun-tasks` (post-fix): `BUILD SUCCESSFUL`, 52/52 tasks, no lint errors.

## Acceptance criteria trace (against `.pipeline/specs.md`)

- [x] New unowned PC card → `contentChanged == true`, `hasChanges == false` (covered by the
      content-only publish test, now passing).
- [x] Existing owned-transition tests still show both `contentChanged == true` and
      `hasChanges == true` — no regression (5 existing tests updated with the new assertion).
- [x] `publishedAt`-only difference → `contentChanged == false` (new dedicated test passes).
- [x] Content-only publish at the `publish()` level: binder.json PUT called, changelog GET/PUT and
      Discord NOT called — confirmed via `coVerify(exactly = 0)` assertions, now passing.
- [x] Owned-transition change with webhook configured: binder.json PUT, changelog GET+PUT, and
      Discord webhook ALL called — confirmed passing.
- [x] First publish, zero owned cards: `Success` returned, binder.json PUT called, changelog/Discord
      skipped, `contentChanged == true` and `hasChanges == false` — confirmed passing.
- [x] Existing `no changes returns NoChanges...` test passes unmodified.
- [x] `315/315` tests, fresh XML. `assembleDebug`/`lintDebug --rerun-tasks` clean.
- [x] `git status` shows only the 3 spec-named files — confirmed.

**Status: DONE.** No Stage 4 (Debugger) needed — the 2 failures were a one-line test-mock gap,
root-caused and fixed within this stage, not a production defect requiring separate diagnosis.
