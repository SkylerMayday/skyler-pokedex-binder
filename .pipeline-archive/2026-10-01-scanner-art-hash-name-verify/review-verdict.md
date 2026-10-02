# Review verdict: scanner wrong-card fixes (Stage 1 name check, Stage 3 art-region DCT hash)

**Verdict: SHIP** (score 91/100, no P0/P1). Read-only review by reading; Gradle not run. Test evidence: 341 tests / 0 failures (Skyler, `--rerun-tasks`, 31 tasks executed).

## Scope
Reviewed only the task's files (CardSearchRepository, new ArtHash, PerceptualHasher, `cardRectInCrop`, ViewModel ranked log, SmartThresholdUseCase comment, four test files). Session 20's dirty edits (ScannerScreen geometry refactor, GeminiCardScanner, deleted FocalLengthsKeyTestFixture, the ScannerScreenTest deletions shown in `git diff`) were not judged.

## Intent audit (Phase 2)
| Spec item | Status | Evidence |
|---|---|---|
| `matchingParsedName` + wire number+total and broad-number | DONE | `val results = if (label == "number+total") raw.matchingParsedName(name) else raw`; final `byNumber = (...).matchingParsedName(name)` |
| broad-dex untouched, comment | DONE | comment "Not name-filtered: the dex in the query already pins species" |
| Null/blank name unfiltered | DONE | `parsedName?.takeIf { it.isNotBlank() } ?: return this` |
| ArtHash.kt pure JVM, 63 bits, median, orthonormal DCT | DONE | no `android.*` import; see math check below |
| PerceptualHasher rewrite, degenerate -> 0L, no recycle | DONE | `if (width < 1 \|\| height < 1) return 0L // degenerate bitmap` |
| `cardRectInCrop` in-file, constants not duplicated | DONE | uses private `CROP_MARGIN_FACTOR`/`CARD_ASPECT_RATIO` |
| SmartThreshold comment 64->63 | DONE | diff: `out of 63 bits` |
| Task 8 ranked + log | DONE | `"ranked=${hashMatch?.ranked}"` |
| Repository tests 1-6, ArtHash, Hasher, ScannerScreen tests | DONE | see Tests |
| CHANGED (justified): Double ART_* constants | accepted | `const val ART_LEFT = 0.065`; needed so `1000*0.065` truncates to exactly 65 |
| CHANGED: distinct-pattern test uses seeded random, not gradient/checker | accepted | correct call: AC-sparse inputs make a median hash noise-driven |

Scope creep: none in the reviewed files' production code. See F4 for test-file ambiguity.

## Verification of requested items
- **DCT math**: `scale = sqrt((k==0?1:2)/32)`, `cos(pi*(2n+1)k/64)` is the correct orthonormal DCT-II; separable row-then-column pass indexed `out[u*GRID+v]` (u = vertical freq) matches the low-block read `coef[u * GRID + v]`. Test `4.0*sqrt(32)` for coef[1] is correct (horizontal a(1)*16 = 4, vertical DC = sqrt(32)).
- **Hash**: 63 coefs, DC skipped, `sortedArray()[31]` is the true median of 63, strict `>` gives at most 31 bits set, bits 0..62 only (`h ushr 63 == 0` tested).
- **boxDownsample**: bounds safe for any w,h >= 1 (`y0 = dy*h/32 <= h-1`, `y1 = max(y0+1, ...) <= h`); Int math, `Long` accumulator; 1x1 and 7x9 tested.
- **Art geometry**: capture art = `artRect(cardRectInCrop)`, reference art = `artRect(whole image)`, both the same fractions (x 6.5-93.5%, y 11-49%), so same-card regions align provided the card fills the guide frame. `guideW = dispW*0.75`, `*1.30 = 0.975*dispW < dispW`, so the crop width is never clamped by the frame and the `cropW/1.30` inversion holds. Spec-derived numbers hand-check: 974/1.30f -> 749, 749*88/63 -> 1046, (112,157)-(861,1203); artRect -> (160,272,812,669).
- **Filter can't reject valid cases**: blank/null name passes through; species-pinned steps (name/dex queries) are not filtered; `namesMatch` is alnum-lowercase bidirectional contains, so "Charizard ex" vs "Charizard-EX" and "Dark X" vs "X" survive. Only the known, spec-accepted false reject remains (see F2).

## Findings

**F1 (P2, confidence 7, correctness/risk)** Captured-art alignment rests on an unmeasured assumption that the card exactly fills the guide frame.
Quote: `// Assumes the physical card fills the guide frame.` (ScannerScreen.kt ~219). If the user holds the card smaller or off-centre, the capture art box covers border/background while the reference box covers art only, degrading the hash toward the old behaviour. Spec lists this as an open question with `ranked=` logging for calibration; not a defect in the change. Action: Skyler's >=10-scan calibration before touching gate constants.

**F2 (P3, confidence 9, false-reject direction is safe)** "Mega Malamar ex" vs "M Malamar-EX": `namesMatch` yields `"megamalamarex"` vs `"mmalamarex"`; neither contains the other, so species-blind hits for Mega/ex-named cards are dropped and the cascade continues to broad-name/broad-dex. Failure is an empty list or same-species list, never a wrong auto-accept. Spec-accepted; no test pins this behaviour.

**F3 (P3, confidence 8, test gap)** The blank-name branch has no test: `takeIf { it.isNotBlank() }` is exercised only by the null-name tests. A `parsed("  ", ...)` case would lock it.

**F4 (P3, confidence 5, verify)** `CardSearchRepositoryTest` diff also contains default-empty api stubs, two extra label tests (`name+number`, `broad-dex` with only a dex) and a stub added to existing test `searchByParsedInfo still tries name before dex` (`stub("number:\"116\" set.total:172", cardDto("bw-116", "Munna", "116"))`). Spec acceptance says "pre-existing tests pass unchanged". These may be session 20's or this task's; I can't tell from the tree. Harmless (strengthen tests) but note if the diff is split for commit.

**F5 (P3, confidence 6, minor)** Comment "a miss falls through the cascade" is inaccurate for broad-number, the last step (a miss yields `none-matched`). Cosmetic.

**F6 (P3, confidence 6, known limitation)** Near-flat art (solid-colour card art) gives coefficients that are float noise around 0; the median threshold then makes the hash arbitrary. Low real-world impact; no change needed.

No bugs found in: degenerate bitmap handling, unknown-distance path (`Int.MAX_VALUE` preserved), `ranked` population (top 5 from sorted, sort is stable), IO dispatch. `HashMatchResult`'s default `ranked` keeps `SmartThresholdUseCaseTest` compiling. Domain -> ui import of `cardRectInCrop` is the accepted single-source-of-geometry tradeoff.

## Tests assert behaviour
- Repository: Kyurem/Snorlax asserts empty + `none-matched`; match-kept asserts id + label; filtered-then-continue asserts `broad-number` with the right id; broad-dex asserts both cards + label. Real behaviour, not mock echo.
- ArtHashTest: constant image AC ~ 0, known basis lands at coef[1] with analytic value, brightness invariance, 63-bit bound, exact box averages, hand-computed rects.
- PerceptualHasherTest: art-box-ignores-border test is a strong geometry test; `findBestMatch` test builds card1 as the exact crop region so distance is exactly 0 and margin is computed via `ArtHash`, not hard-coded.
- Weak spots: distinct-pattern >10 bits is statistical (seeded, deterministic, so stable); `cardRectInCrop` tests use spec-derived numbers recomputed by hand (verified above). No end-to-end real-bitmap test (Bitmap mocked) - acceptable for JVM tests.

## Score
| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 23/25 | All items traced; 2 documented, justified deviations; -2 for F4 ambiguity about "pre-existing tests unchanged". |
| Correctness & bug-freedom | 23/25 | Math and bounds verified, no bugs; -2 for F1 unmeasured geometry assumption (P2, spec-acknowledged). |
| Security & reliability | 19/20 | No new surface; degenerate input never throws; -1 for F6 flat-art noise hash. |
| Maintainability & simplification | 13/15 | Reuses `namesMatch`, `ImageRect`, constants; small named helper; -1 domain->ui import (accepted), -1 F5 comment. |
| Test evidence quality | 13/15 | 341/0 via `--rerun-tasks` with per-class sidecar log; behaviour-asserting tests; -2 for F3 and no pinning of the Mega-name false-reject. |
| **Total** | **91/100** | Ship threshold 85 met; no P0/blocking finding. First pass, no prior iteration to compare. |

## Friction rollup
Planner/Coder friction notes (Float vs Double constants against exact-integer expectations; gradient hash tests invalid for median-threshold DCT) are generalizable hash-test lessons but narrow; not written as lesson files (one-off to this algorithm, already recorded in changes.md). Nothing new from review.
