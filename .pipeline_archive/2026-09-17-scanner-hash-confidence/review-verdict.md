# Review Verdict — Perceptual-Hash Confidence Path

**Scope**: single-lens review (diff below multi-lens thresholds: 5 files, 254+/44- vs 15-file/800-line
trigger). Reviewed working-tree diff vs HEAD (`af89772`), `.pipeline/specs.md`,
`.pipeline/changes.md`, `.pipeline/test-results.md`. No prior `review-verdict.md` — first pass.

## Intent Audit (Phase 2)

Clean. Every spec requirement traces to an actual diff line:
- `computeHash()` 16x16/256→8x8/64 resize: `PerceptualHasher.kt:25-27`, matches spec's exact-changes text.
- `HashMatchResult(card, distance, margin)` top-level data class: `PerceptualHasher.kt:12`.
- `findBestMatch`'s `candidates.size == 1` short-circuit removed, unified distance computation:
  `PerceptualHasher.kt:43-59` — `"val distances = candidates.map { ... }"` then `sortedBy`, matches spec verbatim.
- `ConfidencePath` enum + `SearchConfidence.matchPath`: `SmartThresholdUseCase.kt:6-13`.
- Two named constants, comment-flagged first-guess: `SmartThresholdUseCase.kt:21,26` — exact spec values (12, 16).
- `evaluate()`'s three paths (single-candidate ceiling, `NUMBER_MATCH`, `HASH_MARGIN` gated on
  `parsedNumber == null` not `numberMatch == null`): `SmartThresholdUseCase.kt:37-67`, logic and even
  the comments match the spec's proposed body near-verbatim.
- `ScannerViewModel.kt` log lines extended with distance/margin/matchPath: lines 110-123, matches
  spec's literal replacement text.
- No scope creep — `git diff --stat` confirms exactly the 5 spec-named files, nothing else touched.

**TEMP DIAGNOSTIC block verification** (specifically requested): `ScannerViewModel.kt` lines 84-94
show as added (`+`) in the diff because the block was uncommitted before this session started (no
commit or stash captures a "before" snapshot to diff against directly — this is a real limitation,
disclosed rather than glossed over). Corroborated independently via `handoff.md:65-69` and
`gaps.md:46-48`, both committed at `af89772` (session 15, *before* this Coder session ran): they
describe "`ScannerViewModel.kt` — **uncommitted**, temporary diagnostic block added (saves every
scan's exact crop to `Android/data/com.skyler.pokedexbinder/files/scan_debug_*.jpg`, logs the path).
Added `Context` injection to support it. Explicitly marked 'TEMP DIAGNOSTIC ... remove once resolved'"
— this matches the current block's content (`Context` injection at `ScannerViewModel.kt:29`, same
filename pattern, same "TEMP DIAGNOSTIC (2026-09-11)... remove once resolved" phrasing at line 84-89)
exactly. No hunk anywhere in the diff touches lines *inside* that block independently of the
session's own new code below it. Confidence: high but not absolute proof (no byte-level pre-session
snapshot exists) — flagging this as the honest ceiling on this specific check, not a defect.

## Quality Pass (Phase 3-4)

**Correctness**: Traced `computeHash`'s bit math by hand for the new collision regression test
(`PerceptualHasherTest.kt:66-97`) — checkerboard (`0x5555555555555555`) vs top-half-white
(`0x00000000FFFFFFFF`) genuinely differ, not a coincidental-pass test. `findBestMatch`'s margin
math (`PerceptualHasher.kt:54-56`) is correct for the normal case and matches spec's pseudocode
exactly.

One real edge case, inherited directly from the spec's own proposed algorithm rather than a
Coder deviation: `"val distance = cardBitmap?.let { ... } ?: Int.MAX_VALUE"` (`PerceptualHasher.kt:50-51`)
means a multi-candidate scan where exactly one candidate's image download fails produces an
artificially huge margin (`Int.MAX_VALUE - realDistance`), which would clear
`HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD` and falsely report `HASH_MARGIN` high-confidence — precisely
in the `parsedNumber == null` case this path exists to help. This is spec-verbatim behavior (spec
text: `"margin = sorted[1].second - sorted[0].second"`, no failure-path carve-out), not something the
Coder introduced or was free to fix unilaterally, and no test covers it either way. Confidence 7/10
(concrete code path, not yet observed in the wild) — surfacing as a **defer**, not a blocker: it's a
one-candidate-download-failure scenario layered on top of an already-uncalibrated threshold the spec
explicitly plans to re-tune on real-device data. Recommend Skyler track it alongside the
constants' pending re-tune, e.g. skip the `HASH_MARGIN` grant when `hashMatch.distance == Int.MAX_VALUE`.

**Security/Reliability**: No new I/O, secrets, or auth surface. `runCatching` around the download
path is pre-existing and unchanged (`PerceptualHasher.kt:61-67`). No new resource leaks — `Bitmap.recycle()`
calls preserved unchanged.

**Maintainability**: Named constants over magic numbers, enum over stringly-typed path tracking,
comments explain *why* (the overflow bug, the `parsedNumber == null` vs `numberMatch == null`
distinction) not just *what*. No duplication introduced.

**Agent-Native Architecture**: N/A — no MCP/agent-facing surface touched, skipped per Phase 3's
conditional trigger.

**Bug hunt (Phase 4)**: aside from the margin/download-failure edge case above, nothing found.
Null handling in `evaluate()`'s single-candidate path correctly rejects `hashMatch == null`
(`SmartThresholdUseCase.kt:41`, `"hashMatch != null && hashMatch.card.id == single.id"`). No
unreachable code, no missing `await`-equivalent, no resource leaks in the new code.

## Test Evidence (per `test-results.md` + `.pipeline/evidence/test-results.log`)

Tester's fixed bug (`PerceptualHasherTest.kt`'s `whiteBlack` fixture wrongly assumed distance 64
instead of the arithmetically-correct 32) is a genuine test-only fixture-assumption fix, verified by
hand-computing the mean/threshold math myself: 32-white/32-black pixels, mean 127.5, only the 32
white pixels clear the mean → hash `0x00000000FFFFFFFFL`, Hamming distance vs. all-gray's `0L` is
`bitCount` of 32 set bits = 32. Confirms the Tester's fix, not just its claim. Verbatim decisive
evidence quoted in `test-results.md`: `"tests=303 skipped=0 failures=0 errors=0"` and
`assembleDebug --rerun-tasks: BUILD SUCCESSFUL`, both attributed to `.pipeline/evidence/test-results.log`
sidecar rather than asserted bare. New tests directly exercise each acceptance-criterion branch
(single-candidate ceiling accept/reject/no-hashMatch, number-match-wins-over-wide-margin,
margin-wins-when-null, margin-too-narrow, number-read-but-unmatched-doesn't-fall-through) — traced
each against `evaluate()`'s actual control flow above, all correct.

## What Looks Good

- Faithful, line-for-line implementation of an unusually precise, pre-vetted spec — no
  interpretation gaps.
- The `parsedNumber == null` vs `numberMatch == null` distinction (a real subtlety the spec called
  out) is implemented and tested correctly, not glossed over.
- Root-cause bug fix (the `1L shl i` overflow) has a real, hand-verifiable regression test, not just
  an assertion that trusts the implementation.
- TEMP diagnostic block genuinely left alone — verified via corroborating pre-session docs, not just
  taken on faith.

## Findings Summary

| # | Severity | Dimension | Confidence | Status |
|---|----------|-----------|------------|--------|
| 1 | P2 | Correctness/Reliability | 7/10 | Defer — spec-inherited, one-candidate-download-failure can inflate margin past threshold; not a Coder deviation, no blocker |

No P0/P1 findings.

## Score

- **Spec compliance (25/25)**: every acceptance-criterion line traced to a concrete diff change; zero scope creep (`git diff --stat` confirms exactly the 5 named files); TEMP block preserved.
- **Correctness & bug-freedom (21/25)**: hand-verified hash math and control-flow traces all correct; one real P2 edge case (download-failure-inflated margin) inherited from the spec's own algorithm, uncovered by tests either way — costs a few points, not a P0 since it requires a specific partial-failure condition and is layered on already-uncalibrated, pending-re-tune constants.
- **Security & reliability (20/20)**: no new I/O/auth/secrets surface; resource handling (`recycle()`, `runCatching`) unchanged and correct.
- **Maintainability & simplification (15/15)**: named constants, enum over strings, comments explain why, no duplication, reuses existing `withContext`/`runCatching` conventions.
- **Test evidence quality (14/15)**: verbatim decisive evidence quoted and independently hand-verified (the Tester's fixture fix checks out under my own arithmetic); one point held back because the margin/download-failure edge case (finding #1) has no test either direction.

**Total: 95/100**

## Verdict: SHIP

No unresolved P0/blocking finding; score comfortably clears the 85 threshold. Finding #1 is a
legitimate defer — track it alongside the spec's already-planned constant re-tune, don't block on it.
