# Review verdict: cross-language "closest English printing" fallback

**Verdict: SHIP** (score 94/100, no P0/P1). Desk review only; Gradle not run by me. Skyler's run: 428 tests, 0 failures, `--rerun-tasks`.

## Scope / intent audit
Reviewed SmartThresholdUseCase, CardSearchRepository, ScannerViewModel, ScannerScreen and the 4 test files. Orchestrator override (Asian-only) is implemented and is the intended divergence from spec Open Q1 / AC1 ("incl. fr"). All T1-T5 items traced DONE. The tree is coherent: one definition each of `isCrossLanguageFallback`, `CROSS_LANGUAGE_BASES`, `lowConfidenceCards`, `crossLanguage`. No leftover TODOs in the touched code (grep). No dead helpers. No scope creep, dependency, Room or network change. No keys logged: the new log field is `crossLanguage=${search.crossLanguage}`.

## Requested checks
1. **Never high via any path: PASS (conf 10).** `SmartThresholdUseCase.kt:57` `if (crossLanguage) return SearchConfidence(cards, false, cards.first(), ConfidencePath.CROSS_LANGUAGE)` sits directly after the empty check at :54 and before the `cards.size == 1` branch (:59), NUMBER_MATCH (:87) and HASH_MARGIN (:101). There is no other `evaluate(` caller in main (`ScannerViewModel.kt:119`), and the 7th positional arg is `search.crossLanguage`. Tests (15 occurrences) cover pinned, nameVerified, number-match with a false control, margin with a false control, and null hash.
2. **Flag derivation: PASS (conf 10).** `CardSearchRepository.kt:73-74`: `val base = language?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()`, then `base in CROSS_LANGUAGE_BASES && cards.isNotEmpty() && cards.none { isNonEnglishTcgdexId(it.id) }`. Hand-traced: zh_CN, zh-Hans, ZH-Hant and ja-JP give ja/zh, so true with English-only cards. null, "", en, EN-US, fr, de and xx give false. A `tcgdex_ja_` or `tcgdex_zh-cn_` card gives false, and so does a mixed list. `ja-JP` is not in the test table, but the logic is identical to the covered `zh-hans` path (see F3). The flag is set at `:84`, and the later `copy(cards=...)` keeps it (including the early return). `resolveParsed` is untouched.
3. **lowConfidenceCards: PASS with one edge (see F1).** `ScannerViewModel.kt:232` `if (!crossLanguage || ranked.isNullOrEmpty()) return candidates`. `MAX_VALUE` entries are filtered out of the head, so they trail in original order. Unknown ranked ids are dropped by `mapNotNull`. `sortedBy` is stable, and `distinct` guards duplicate ranked ids.
4. **UI: PASS (conf 10).** `CardSelectionList(..., header: String? = null)` and the `if (header != null && cards.isNotEmpty())` guard at `ScannerScreen.kt:1000`. `LowConfidence(emptyList())` at VM:106 compiles via the default. The only caller passes the header only when `s.crossLanguage`. The empty-list text is preserved.
5. **No behavior change for English/European: PASS.** The flag is false for them, so `evaluate` follows the pre-existing code unchanged, `lowConfidenceCards` returns `candidates` as-is, and there is no header.
6. **Tests assert behavior: PASS.** The repository tests exercise the real cascade with mockk for Palafin (zh, broad-name), a zh single en dex pin (pinned and cross), en/null/fr false, and a ko native hit false. The VM tests check the ordering, the MAX_VALUE trail, the permutation and ties. Both would fail if the guard or the flag logic were reverted.

## Findings
| # | Sev | Conf | Finding |
|---|---|---|---|
| F1 | P3 | 7 | `ScannerViewModel.kt:238` `return head + candidates.filter { it !in head }`: if `candidates` ever held two equal `TcgCard`s (same id and data), `associateBy` collapses them and the `filter` drops both copies from the tail, so the list is no longer a strict permutation. The cascade appears to dedupe by id, so this is unlikely in practice. A one-line fix is to filter by `it.id !in headIds`. Not blocking. |
| F2 | P3 | 8 | Spec's ScannerViewModelTest list is satisfied, but no test pins the 5-candidate / `ranked`-top-5 tail ordering (documented ceiling). Optional. |
| F3 | P3 | 6 | Test table omits `ja-JP` (named in the review brief). Covered by the same code path as `zh-hans`; verify-only, add if wanted. |
| F4 | Info | - | Residual doc drift: `.pipeline/specs.md` AC1 still says "incl. `fr`", while changes.md records the override. Not a code issue. |

## Good
- A single guard in `evaluate`, with no ViewModel-side second mechanism.
- The default params keep every old call site and test untouched.
- The `isNonEnglishTcgdexId` helper is reused, not duplicated.
- Tests include false controls for the number-match trap, which was the spec's key risk.

## Score
| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | Every requirement is traced. The Asian-only narrowing is the documented override, and the spec text was not updated (-1). |
| Correctness | 23/25 | The guard is verified before all paths. F1 is a theoretical duplicate-card edge (-2). |
| Security & reliability | 20/20 | No new network or logging risk, no keys, and the guard fails safe (low confidence). |
| Maintainability | 14/15 | Minimal, reuses the helpers, named constant. The VM comment is clear. F1 is a small simplification (-1). |
| Test evidence | 13/15 | 428/0 failures from a real `--rerun-tasks` run by Skyler, and the tests assert behavior with controls. No explicit `ja-JP`/th/id end-to-end test, and I read the result summary only, not a verbatim log (-2). |
| **Total** | **94/100** | First pass of this review, so no prior score to compare against. |

Friction rollup: nothing new that is generalizable. Gradle-unavailable and rate-limit-resume are already known. No lesson file written.
