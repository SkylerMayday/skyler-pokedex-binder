# Handoff — Session 16 (2026-09-17)

## 1. Goals

Continue from session 15's explicit deferral: build the already-approved, already-researched spec
at `docs/specs/2026-09-13-scanner-hash-confidence.md`. Skyler said "continue building from the spec
we left off last sesh" — full `dev-team-pipeline` run (Planner→Coder→Tester→Reviewer, no Debugger
stage needed) against P0 tasks 1-6 + P1 tasks 7-8.

## 2. Current State

### Hash-margin confidence path: shipped, uncommitted, not yet live-tested

Full pipeline run, ship at 95/100, no P0/P1. Full detail: `project-overview.md` item 15,
`gaps.md`'s session-16 section. Summary:

- `PerceptualHasher.computeHash()`'s real bit-mapping bug (session 15's finding) is fixed — 8x8
  resize instead of 16x16, matches `Long`'s real 64-bit capacity, no more `1L shl i` overflow.
- `findBestMatch()` now returns `HashMatchResult(card, distance, margin)` for every candidate count
  including 1 — the old "exactly 1 candidate = auto-trust" short-circuit is gone.
- `SmartThresholdUseCase.evaluate()` gained a margin-gated `HASH_MARGIN` confidence path for when
  Gemini's OCR'd number is null, plus a `ConfidencePath` enum for diagnostics. Two new first-guess
  constants (`HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12`, `HASH_SINGLE_CANDIDATE_MAX_DISTANCE =
  16`) — explicitly uncalibrated, need real-device data.
- 303/303 unit tests pass (fresh XML), `assembleDebug --rerun-tasks` clean.
- One real test-only bug found and fixed during the Tester stage (a wrong fixture-hash assumption
  in `PerceptualHasherTest.kt` — production code was correct throughout).
- One P2 finding deferred, not fixed: a per-candidate download failure can inflate the hash margin
  past the threshold in a specific edge case. Full detail: `gaps.md`.

**Not committed** — Skyler hasn't been asked yet whether to commit; this session ended right after
the Reviewer's ship verdict.

**Not live-tested** — same standing constraint as every constant this scanner's history has shipped
(`GUIDE_FRAME_WIDTH_RATIO`, retry delays, etc.). The two new constants are first-guess values.

### Pre-existing uncommitted TEMP diagnostic block: kept, verified untouched

Skyler explicitly chose to keep the session-15 TEMP diagnostic block in `ScannerViewModel.kt`
(saves scan crops to disk) rather than discard it this session. Both the Tester and Reviewer stages
independently confirmed it was left untouched by this session's changes.

## 3. Active Files

- `app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt` — hash fix + `HashMatchResult`.
- `app/src/main/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCase.kt` — `ConfidencePath` +
  margin-gated confidence logic.
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt` — logging extended;
  pre-existing uncommitted TEMP diagnostic block (~lines 84-94) untouched, still present.
- `app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt`,
  `SmartThresholdUseCaseTest.kt` — updated/new tests.
- `.pipeline/` — full pipeline handoff trail (`specs.md`, `changes.md`, `test-results.md`,
  `review-verdict.md`, `evidence/test-results.log`). Not yet archived to `.pipeline_archive/`.
- `gaps.md`, `project-overview.md`, this file — updated this session.
- Two new lessons written by the Reviewer stage:
  `~/.claude/rules/lessons/subagent-sandbox-blocks-loopback-sockets-try-orchestrator-first.md`,
  `~/.claude/rules/lessons/grep-tool-n-flag-needs-explicit-output-mode.md`.

## 4. Changes Made (commits, chronological)

- **Nothing committed this session.** All 5 changed files (`PerceptualHasher.kt`,
  `SmartThresholdUseCase.kt`, `ScannerViewModel.kt`, `PerceptualHasherTest.kt`,
  `SmartThresholdUseCaseTest.kt`) are uncommitted in the working tree, ship-reviewed at 95/100.

## 5. Failed Attempts

- None on the coding/logic side — the pipeline shipped in one pass, no Debugger stage needed, no
  Reviewer auto-loop needed (95/100 on the first review).
- The Coder-stage subagent hit the standing Gradle daemon IPC wall (`gaps.md`, sessions 10-13) and
  could not run any Gradle task at all. The orchestrator ran the exact same commands directly and
  the wall cleared on the first attempt — 4th occurrence of this exact pattern, now captured as a
  standing lesson so future sessions stop re-diagnosing it from scratch.

## 6. Next Steps

1. **Ask Skyler whether to commit the 5 uncommitted files** — shipped, reviewed, tested, but no
   commit made yet (git safety: never commit without being asked). This is the very next action.
2. **Live-test on the real S26 Ultra** — same standing constraint as every constant this scanner
   history has shipped uncalibrated. Specifically want: (a) a same-species multi-candidate scan
   where Gemini's number comes back null, to see whether `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD =
   12` is in a sane range: too low = false-positive wrong-card assigns, too high = still falls to
   manual-pick too often; (b) a repeat of the Furfrou→Zorua-style single-candidate misread, to
   check whether `HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16` actually catches it.
3. **Archive `.pipeline/` to `.pipeline_archive/2026-09-17-scanner-hash-confidence/`** once Skyler
   confirms the commit (or discard) decision — standing project convention, not yet done.
4. **P2 deferred, not urgent**: `findBestMatch()`'s download-failure fallback can inflate the hash
   margin past threshold in a specific one-candidate-fails edge case. Full detail: `gaps.md`.
   Candidate fix: skip the `HASH_MARGIN` grant when `hashMatch.distance == Int.MAX_VALUE`.
5. **Carried, unchanged**: `GeminiCardScanner.kt`'s unchanged 400/401/403/404 path still has no
   dedicated test (session 13, still not urgent).
6. **binder.json republish** — still gated on Skyler's own confidence that scanning works, per every
   prior session's note. Unchanged.
