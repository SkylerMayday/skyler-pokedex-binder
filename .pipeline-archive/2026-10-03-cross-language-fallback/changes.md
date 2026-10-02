# changes.md - cross-language "closest English printing" fallback

Spec: `.pipeline/specs.md`. Orchestrator override applied (Open Q1): `isCrossLanguageFallback` is true only for Asian bases ja/ko/zh(any zh-*)/th/id; null, en, fr, de, es, it, pt, unknown = false. Open Q2: ordering only when crossLanguage (as spec). A previous attempt was rate-limit-cut; main-code T1-T4 and SmartThreshold tests were already in the tree and were kept as-is after review; this run added the missing repository + ViewModel tests.

## Files
| File | Change |
|---|---|
| `domain/SmartThresholdUseCase.kt` | `ConfidencePath.CROSS_LANGUAGE`; `evaluate(..., crossLanguage=false)` returns low confidence right after the empty check (guards pin, nameVerified, number-match, margin, hash). |
| `repository/CardSearchRepository.kt` | `ParsedSearchResult.crossLanguage`; `CROSS_LANGUAGE_BASES` + `isCrossLanguageFallback` (Asian bases only, all-English-id cards, non-empty); `searchByParsedInfo` sets the flag, `resolveParsed` untouched. |
| `ui/scanner/ScannerViewModel.kt` | `LowConfidence(cards, crossLanguage=false)`; top-level `lowConfidenceCards` (hash-distance order, cross only, permutation); `evaluate` gets `search.crossLanguage`; `crossLanguage=` in the search-candidates log. |
| `ui/scanner/ScannerScreen.kt` | `CardSelectionList(..., header: String? = null)`; LowConfidence passes "No card found in this language - closest English printings of <name>" when cross. |
| `SmartThresholdUseCaseTest.kt` | 5 tests (pinned, nameVerified, number-match trap with control, margin with control, no hash). Pre-existing in tree. |
| `CardSearchRepositoryTest.kt` | NEW: fallback table (ja/ko/zh/zh-tw/zh_CN/zh-hans/ZH-Hant/th/id true; null/blank/en/EN-US/en_GB/fr/de/es/it/pt/xx false; native/mixed/empty false); Palafin zh broad-name -> true; zh single en dex pin -> pinned+true; en/null/fr same hit -> false; ko native hit -> false. |
| `ScannerViewModelTest.kt` | NEW: ascending order, MAX_VALUE/unranked trail, unknown ranked id ignored + permutation, ties stable, null/empty ranked and non-cross unchanged. |

No dependency, Room, network or language-marker change (new-dependency check: N/A, manifest untouched).

## Simplification/review pass (inline, desk)
- Reuse: `isNonEnglishTcgdexId` reused; no duplicate helper. Single guard in `evaluate`, no ViewModel second guard.
- Correctness: `ConfidencePath` has no exhaustive `when` anywhere (grep). Call-site grep (log lines 1-20): `evaluate(` only in ScannerViewModel:119 (positional, 7th arg = crossLanguage, matches signature order after nameVerified); `LowConfidence(` at :106 (default compiles) and :131; `CardSelectionList(` single caller with named args; `ParsedSearchResult(` sites all use defaults; no test constructs `LowConfidence`/`CardSelectionList`. `TcgCard` positional ctor in new test matches existing usage (6 args); `card(name,number)` id is `name-number`, matching the `"a-1"` ids in tests.
- Nothing needed fixing.

## 7-check verification (full output: `.pipeline/evidence/changes-verify.log`)
| # | Check | Result |
|---|---|---|
| 1 | Typecheck | Not run - Gradle cannot run in harness; desk-checked all call sites/signatures (above). Skyler: `.\gradlew.bat testDebugUnitTest --rerun-tasks` |
| 2 | Lint | N/A (no lint task run; same reason) |
| 3 | Scoped tests | Not run - Skyler runs testDebugUnitTest |
| 4 | Production build | Not run - Gradle unavailable; Skyler runs assembleDebug/installDebug |
| 5 | Dev server boot | N/A - Android app, no server |
| 6 | No stray debug output | Pass - no println/debugger/Log.d added; only existing `Log.i("ScannerMatch")` lines plus the `crossLanguage=` field; no key/URL logged (grep in log) |
| 7 | git status | Pass - changes confined to the 7 files above on top of expected earlier uncommitted edits; untracked entries are pre-existing earlier-stage files (listed in log). Not reverted. |

Residual risk: tests uncompiled. Likeliest compile risk: `assertTrue(it, ...)` with `String` message in `forEach` over `List<String>` (fine) and `it.toString()` for nullable (fine).

## Friction Notes
- Gradle unavailable again; compile correctness rests on desk-check.
