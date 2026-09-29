# Handoff — Session 18 (2026-09-29)

## 1. Goals

Build the spec `docs/specs/2026-09-24-search-query-species-guard.md`: stop the scanner's
species-blind `number+total` query from matching a wrong Pokémon (live: Castform scan → `Munna
#116`) and make the winning query visible in the `ScannerMatch` log.

## 2. Current State

**Shipped and merged to `master` (`bd55bfa`, fast-forward): P0 reorder + P1 `query=` logging.**
Unit-tested (user ran `testDebugUnitTest --rerun-tasks` in the worktree: 331 tests, 0 failures,
fresh JUnit XML), Reviewer verdict ship 92/100. **Not live-device tested.** P2 (drop `number+total`
entirely) deliberately not done — needs live data.

Not run as a full pipeline: Planner rejected by Skyler mid-run (spec already was the plan), Coder
work done inline by the orchestrator, then Tester (BLOCKED by the Gradle loopback wall) and
Reviewer subagents. Details in `project-overview.md` item 19.

## 3. Active Files

- [CardSearchRepository.kt](app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt)
- [ScannerViewModel.kt](app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt)
- [CardSearchRepositoryTest.kt](app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt) (7 new tests, 20 total)

## 4. Changes Made

- Cascade reordered; `searchByParsedInfo` now returns `ParsedSearchResult(cards, matchedQuery)`;
  log line gained `query=<label>`.
- Housekeeping: old `.pipeline/` moved to `.pipeline_archive/2026-09-20-testable-settings-repos/`.
  Worktree `.claude/worktrees/search-query-species-guard` and branch
  `claude/search-query-species-guard` still exist (kept, already merged — safe to remove).
- Worktree was created manually from `master`, not the tool default: `origin/main` is a stale
  "first commit", so a default worktree would have branched from the wrong base.

## 5. Failed Attempts

- Gradle loopback wall blocked 4/4 runs by the orchestrator and the Tester subagent (including
  outside the sandbox). Cleared only when Skyler ran the identical command in his own PowerShell.
  Third consecutive session this has needed his hands.
- Tester subagent could not run `powershell.exe -File` (harness worktree guard refused it); it ran
  `gradlew.bat` from Git Bash instead, which does start Gradle. So the project CLAUDE.md line "Bash
  cannot invoke the wrapper directly" is wrong. **Not edited — Skyler's call.**

## 6. Next Steps

1. **Live scan** on the S26 Ultra with `adb logcat -s ScannerMatch:*` — confirm `query=` shows on
   the `search candidates=` line, ideally a Castform (dex 351) scan resolving via `dex+number+total`.
   Watch for Reviewer F1: a `dex+number` hit pre-empting a correct `number+total` one.
2. **Gemini retry-budget change (`GeminiCardScanner.kt` + `GeminiCardScannerTest.kt`: 4 attempts,
   exponential backoff) is committed but has never been test-run** (session 18's test run was on
   the worktree, without it). Run `.\gradlew.bat testDebugUnitTest --rerun-tasks` in the main
   folder to confirm. Still untracked: `scripts/install_debug.ps1`, `scripts/test_gemini_retry.ps1`,
   `scripts/scan_capture.log`.
3. Carried from session 17, unchanged: confirm `CROP_MARGIN_FACTOR=1.30` with a live scan (pull
   `scan_debug_*.jpg`, check all 4 edges); hash-margin constants still uncalibrated; publish-diff
   confirmation.

Blocked on Skyler: everything above needs the phone or his own PowerShell for Gradle.
