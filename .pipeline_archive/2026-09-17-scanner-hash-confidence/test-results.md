# Test Results — Perceptual-Hash Confidence Path

**Deviation disclosed**: this stage was run by the orchestrator directly, not the `pipeline-tester`
subagent. Reason: the Coder stage hit this project's own previously-documented Gradle daemon IPC
wall (`Unable to establish loopback connection`, `gaps.md` sessions 10-13) and could not execute
any Gradle task. Per `project-overview.md`'s own Conventions ("if a subagent reports this wall, try
the same command directly as the orchestrator before concluding the build is actually broken"), the
orchestrator ran the exact same commands directly — the wall cleared immediately, consistent with
every prior session's data on this issue. Same precedent as session 13's Tester-substitution
disclosure.

## Result: 1 real failure found, root-caused, fixed — now 303/303 passing

First run (`testDebugUnitTest --rerun-tasks`, fresh JVM daemon): 303 tests, **1 failure** —
`PerceptualHasherTest > findBestMatch picks card whose hash is closest to captured bitmap and
reports margin`.

**Root cause (traced against `PerceptualHasher.computeHash()`'s actual arithmetic, not guessed):**
the test's own inline comment assumed the `whiteBlack` fixture (first 32 of 64 sampled pixels
white, last 32 black) hashes to `-1L` (all 64 bits set). It doesn't — only the 32 pixels above the
mean (127.5) set their bit, so the real hash is `0x00000000FFFFFFFFL` (32 bits set). Against
`allGray`'s hash of `0L`, the real Hamming distance is `bitCount(32 set bits) = 32`, not the
assumed 64. This is a **test-only bug** (wrong fixture-value assumption baked into the test's
comments/assertions), not a production bug — `computeHash()`/`findBestMatch()` implement the spec
correctly; same class of issue as this project's precedented `ScannerScreenTest`/`Migration6to7Test`
stale-assumption bugs (session 11, `project-overview.md`).

**Fix applied** (`PerceptualHasherTest.kt`): corrected the comment to describe the real hash value,
and changed `assertEquals(64, result?.margin)` → `assertEquals(32, result?.margin)`. No production
code touched by this fix.

## Verbatim evidence

Full command output (both runs, plus the fresh JUnit XML class-level result for
`PerceptualHasherTest` after the fix): `.pipeline/evidence/test-results.log`.

Decisive excerpt — fresh XML summary across all classes, post-fix:
```
tests=303 skipped=0 failures=0 errors=0
```

`PerceptualHasherTest` class-level, post-fix (all 7 cases, including the one that failed pre-fix):
```
<testsuite name="com.skyler.pokedexbinder.domain.PerceptualHasherTest" tests="7" skipped="0" failures="0" errors="0" .../>
```

## Additional check run (not required by spec's acceptance criteria, run anyway for confidence)

`assembleDebug --rerun-tasks`: `BUILD SUCCESSFUL` (production build compiles clean with the new
`HashMatchResult`/`ConfidencePath` types). Verbatim in `.pipeline/evidence/test-results.log`.

## Acceptance criteria trace (against `.pipeline/specs.md`)

- [x] `computeHash()` resizes to 8x8; regression test (`computeHash never collides for two
      distinguishably-different images`) passes.
- [x] `findBestMatch()` returns `HashMatchResult(card, distance, margin)`; single-candidate case
      (`findBestMatch returns only card with MAX_VALUE margin when list has one element`) passes;
      multi-candidate margin case (fixed above) now passes with the correct expected value.
- [x] `evaluate()` optional `HashMatchResult?` param, `NUMBER_MATCH`/`HASH_MARGIN`/
      `SINGLE_CANDIDATE_HASH`/`NONE` paths — all `SmartThresholdUseCaseTest` cases pass (part of the
      303 total; class-level breakdown available in the fresh XML at
      `app/build/test-results/testDebugUnitTest/`).
- [x] Named, comment-flagged first-guess constants present (verified in source, not just tests) —
      `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12`, `HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16`.
- [x] `ScannerViewModel.kt`'s uncommitted TEMP diagnostic block untouched — confirmed via `git diff`
      (Coder's own changes.md check 7; independently re-confirmed here, same result).
- [x] `.\gradlew.bat testDebugUnitTest --rerun-tasks` passes: **303/303, fresh XML.**

**Status: DONE.** No Stage 4 (Debugger) needed — the one failure found was root-caused and fixed
within this stage as a one-line test correction, not a production defect requiring separate
root-cause diagnosis.
