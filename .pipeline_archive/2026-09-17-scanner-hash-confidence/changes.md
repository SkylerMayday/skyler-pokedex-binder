# Changes — Perceptual-Hash Confidence Path for the Scanner Match Pipeline

Spec: `.pipeline/specs.md` (P0 tasks 1-6 + P1 tasks 7-8; P2 out of scope). Implemented exactly as
specified — no scope creep.

## Files changed

### `app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt`
- Added top-level `data class HashMatchResult(val card: TcgCard, val distance: Int, val margin: Int)`.
- `computeHash()`: resize `16x16`/`IntArray(256)` → `8x8`/`IntArray(64)` (matches `Long`'s real
  64-bit capacity, fixes the `1L shl i` overflow that silently collided different cards' hashes).
  `foldIndexed` body unchanged. Added a comment pointing at the spec doc for the empirical writeup.
- `findBestMatch()`: removed the `candidates.size == 1` short-circuit. New body computes
  `capturedHash` once, maps every candidate to `card to distance` inside `withContext(Dispatchers.IO)`
  (failed download still falls back to `Int.MAX_VALUE`, unchanged), sorts by distance, returns
  `HashMatchResult(best, bestDistance, margin)` where `margin = runner-up - best` or
  `Int.MAX_VALUE` for a single candidate. Returns `null` only for an empty list.
  `downloadBitmap`/`hammingDistance` untouched.

### `app/src/main/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCase.kt`
- Added `enum class ConfidencePath { NUMBER_MATCH, HASH_MARGIN, SINGLE_CANDIDATE_HASH, NONE }`.
- `SearchConfidence` gained a `matchPath: ConfidencePath` field.
- Added `companion object` constants `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12` and
  `HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16`, both comment-flagged first-guess/pending-re-tune.
- `evaluate()` gained an optional `hashMatch: HashMatchResult? = null` param (default preserves
  today's behavior for any caller that omits it, minus the two short-circuits below).
  - `cards.size == 1` short-circuit narrowed from "always high confidence" to an absolute
    Hamming-distance ceiling check against `hashMatch` (rejects if `hashMatch == null`, the card
    id doesn't match, or distance exceeds the ceiling).
  - New `HASH_MARGIN` path: only when `parsedNumber == null` (not merely "no number match" — a
    read-but-unmatched number is a stronger negative signal and must not fall through) and the
    margin clears the threshold.
  - `numberMatch` path unchanged in logic, now tagged `ConfidencePath.NUMBER_MATCH`.

### `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt`
- Only the `processImage` confidence-check block (~lines 110-123) changed, exactly per the spec's
  literal replacement text: captures `hashMatch` from `findBestMatch`, derives `best` from
  `hashMatch?.card`, passes `hashMatch` into `evaluate()`, and extends both `ScannerMatch` log
  lines with `distance`/`margin`/`matchPath`.
- **The pre-existing uncommitted TEMP DIAGNOSTIC block (~lines 84-94) is untouched** — verified via
  `git diff` (full diff in the sidecar log, Check 7): the block's lines appear in the diff only
  because they predate this session's starting commit, not because this task modified them.
  `confidenceCheckInputs` unchanged.

### `app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt`
- Updated all mocked `createScaledBitmap`/`getPixels` calls from `16, 16`/256-length arrays to
  `8, 8`/64-length arrays (would otherwise fail to compile against the new signature).
- Added `computeHash never collides for two distinguishably-different images` — the hash-collision
  regression test the spec's acceptance criteria requires (checkerboard vs. top-half-white 8x8
  patterns must not hash identically).
- Updated `findBestMatch` tests for the new `HashMatchResult` return type; renamed the
  single-candidate test to assert `margin == Int.MAX_VALUE`; the multi-candidate test now also
  asserts `distance` and `margin` on the returned result.

### `app/src/test/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCaseTest.kt`
- Replaced the old "single result is high confidence" test (asserted the now-intentionally-removed
  auto-accept) with three: within-ceiling accepted, beyond-ceiling rejected, no-`hashMatch` stays
  rejected — directly matching the acceptance criteria's single-candidate requirement.
- Added: number-match still wins even with a wide hash margin present; hash-margin wins when
  number is null and margin clears the threshold; falls through when margin is too narrow; a
  number that was read but matched nothing does not fall through to the hash-margin path (the
  spec's explicitly-called-out edge case). All existing number-match/empty-list assertions kept,
  now also asserting `matchPath`.

## Simplification & review pass (Phase 5)

- **Reuse**: no new utility needed; both files continue using the existing
  `withContext(Dispatchers.IO)` / `runCatching` conventions already in the codebase.
- **Quality**: named constants (no magic numbers), enum instead of stringly-typed path tracking,
  no nested conditionals beyond 2 levels, no dead code/unused imports added.
- **Efficiency**: single `sortedBy` pass over candidates (no double iteration); confirmed against
  the spec's own risk note that removing the single-candidate short-circuit adds a network round
  trip to that path — explicitly accepted, timing re-measurement is P2/out of scope.
- **Correctness/consistency fix during review**: simplified the new single-candidate `findBestMatch`
  test to use one combined `mockkStatic(Bitmap::class, BitmapFactory::class)` block instead of two
  nested try/finally blocks, matching the existing convention already used by the file's other
  MockWebServer-backed test — pure readability change, same assertions, same mocked behavior.
- Grepped for every other call site of `findBestMatch`/`evaluate`/`SmartThresholdUseCase`/
  `SearchConfidence` in `app/src`: only the one production call site
  (`ScannerViewModel.processImage`) and the two test files already updated here — no other
  breakage.

## Verification (7 checks — full logs: `.pipeline/evidence/changes-verify.log`)

1. **Typecheck** — BLOCKED, same environment wall as check 3 (no separate typecheck step outside
   Gradle's Kotlin compile tasks). See check 3.
2. **Lint** — BLOCKED, same wall. No standalone ktlint/detekt configured; AGP's `lintDebug` needs
   the same Gradle daemon.
3. **Scoped tests** (`.\gradlew.bat testDebugUnitTest --rerun-tasks`) — **BLOCKED** by a
   pre-existing, previously-documented (`gaps.md` sessions 10-12, and the 2026-09-10 archived
   pipeline run) Gradle daemon IPC failure, not caused by this diff:
   ```
   java.io.IOException: Unable to establish loopback connection
     ... WEPollSelectorImpl/WindowsSelectorImpl -> UnixDomainSockets.connect0 ->
     SocketException: Invalid argument: connect
   ```
   6 distinct workarounds tried (default, `--no-daemon`, `usePlainSocketImpl`, second JDK,
   forced classic `WindowsSelectorProvider`, sandbox-disabled) plus 2 plain retries — identical
   failure every time. `gradlew.bat --version` (no daemon needed) succeeds, confirming
   JAVA_HOME/wrapper are fine and the wall is specific to daemon-requiring tasks. Forcing the
   classic selector still bottomed out at the same `UnixDomainSockets.connect0` call, proving the
   failure sits below the Selector abstraction and isn't fixable via JVM flags from here.
   Substituted a full manual trace of every new/changed test against `evaluate()`'s and
   `findBestMatch()`'s actual control flow (detailed in the sidecar log) since automated execution
   was unavailable.
4. **Production build** (`assembleDebug`) — BLOCKED, identical wall (same daemon dependency).
5. **Dev/start server boots** — N/A, Android app with no dev/start server.
6. **No stray debug output / scratch files** — clean. `git diff` on the three main-source files
   grepped for `println|console.log|debugger|TODO|FIXME|Log\.d\(|Log\.v\(`: zero matches. No
   scratch files added to the repo (verification scripts/logs live in the session scratchpad and
   the gitignored `.pipeline/evidence/`).
7. **`git status` shows only intended files** —
   ```
    M app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt
    M app/src/main/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCase.kt
    M app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt
    M app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt
    M app/src/test/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCaseTest.kt
   ```
   Exactly the 5 files the spec named. `ScannerViewModel.kt`'s diff includes the pre-existing
   uncommitted TEMP diagnostic block only because it predates this session's baseline commit —
   confirmed via full `git diff` (sidecar log) that those specific lines are unchanged content.

**Update (Tester stage, orchestrator-run — see `.pipeline/test-results.md`)**: the Gradle wall that
blocked this stage's own verification cleared when run directly by the orchestrator (consistent
with `project-overview.md`'s documented pattern that this wall fires far more reliably for
subagents than direct orchestrator commands). `testDebugUnitTest --rerun-tasks` found 1 real
failure — a test-only wrong-fixture-assumption bug in `PerceptualHasherTest.kt`, not a production
defect — fixed, then **303/303 passing, fresh XML**. `assembleDebug --rerun-tasks` also confirmed
`BUILD SUCCESSFUL`. Checks 1-4 below are superseded by that stage; left in place for the Coder
stage's own record of what it attempted and why.

**Net result (superseded, see above)**: implementation complete and matches every acceptance
criterion in the spec, hand-verified line-by-line against `evaluate()`/`findBestMatch()`'s actual
control flow in lieu of automated test execution. Automated typecheck/lint/test/build could not run
this session due to a pre-existing, previously-documented, environment-level Gradle daemon IPC
failure — not caused by this diff, not resolved by 6 workaround attempts including the two specific
fixes `gaps.md` flagged as "not yet tried" (`usePlainSocketImpl`, forced classic
`WindowsSelectorProvider`), both now confirmed-false leads.

## New dependency check

N/A — no new dependency added to any manifest. No `build.gradle.kts`/`gradle/libs.versions.toml`
changes in this diff.

## Friction Notes

- The Gradle `Unable to establish loopback connection` wall (documented in `gaps.md` since session
  10, previously described as self-resolving) did **not** self-resolve here across 8 total attempts
  (6 distinct workarounds + 2 plain retries) — third occurrence now (sessions 10-12, the 2026-09-10
  archived pipeline run, and this session). Both of `gaps.md`'s "not yet tried" candidate fixes
  (`-Djdk.net.usePlainSocketImpl=true`, forcing `sun.nio.ch.WindowsSelectorProvider`) are now
  confirmed-false leads — worth updating `gaps.md` to close that open thread and stop suggesting
  them, and worth escalating from "retry and hope" to an actual host-level fix (e.g. a JDK-version
  swap that predates the WEPoll/UnixDomainSockets-based Pipe implementation, or investigating
  whatever host-level condition differs between subagent-run and orchestrator-run shells, since
  prior sessions' data consistently shows subagents hit this far more reliably than direct
  orchestrator commands).
- Confirmed (again) that `Grep` needs an explicit `output_mode: "content"` to show line
  content/numbers — matches the existing note in `.pipeline/specs.md`'s own Friction Notes.
