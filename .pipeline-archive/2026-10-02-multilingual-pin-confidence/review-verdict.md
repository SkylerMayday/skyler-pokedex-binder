# Review verdict: pinned-match trust (A) + TCGdex ko/zh/th/id (B)

**Reviewer:** Stage 5 (single reviewer) · 2026-10-02 · Spec `.pipeline/specs.md` · Tests: 411 pass / 0 fail (Skyler, `--rerun-tasks`, `.pipeline/test-results.md`)
**Scope:** SmartThresholdUseCase, CardSearchRepository, ScannerViewModel (logging/args only), CardScanShared `language` wording, their tests. Earlier uncommitted work in the same files (CardScanProvider swap, `ranked=` log, ptcg `matchingParsedName`, POKEMONTCG-IO-FALLBACK tags) not reviewed.

## Verdict: SHIP (score 88/100, no P0/P1)

## Spec trace (all DONE)
| Req | Evidence |
|---|---|
| T1 evaluate rules, path labels, KDoc | `SmartThresholdUseCase.kt:63-66` `val pinned = identityPinned \|\| nameVerified` ... `(if (hashMatch.distance == Int.MAX_VALUE) single.imageUrl.isBlank() else hashMatch.distance <= PINNED_HASH_CONTRADICTION_DISTANCE)` |
| T2 flag | `ParsedSearchResult(... val nameVerified: Boolean = false)`, set only in printedName step |
| T3/T3b mapping, ids, markers | `tcgdexLangsFor`, `toDomain(lang)`: `"${TCGDEX_EN_ID_PREFIX}${lang}_"`, `"$setCode (${lang.uppercase()})"` |
| T4 manual search | `tcgdexAllLangsByDex` = `coroutineScope { ...map { async {...} }.awaitAll().flatten() }` |
| T5 logging | `nameVerified=${search.nameVerified}`, arg passed to `evaluate` |
| T6 normalizeName | Mn/Mc kept |
| T7 prompt | only the `"language":...` value text changed; field set identical |
| T8 tests | all 10 repo groups + threshold + Gemini zh-CN present |
No scope creep in this feature's lines, no new dependency, no Room change, no key/URL in any new log.

## Verification of the brief's 8 points
1. **Threshold rules: correct.** 20 -> `visuallyConfirmed` wins (`SINGLE_CANDIDATE_HASH`); 21..45 pinned -> accept; 46 -> NONE. MAX_VALUE: blank image accepted, non-blank rejected (both flags). Unpinned keeps `<= 20` (visuallyConfirmed is the only other accept path). `hashMatch == null` rejects. Multi-candidate block (`cards.size == 1` returns first) untouched. Tests cover 20/21/34/45/46, both MAX_VALUE cases, both flags, unpinned 34, multi-candidate ignoring flags. Wrong-card path: pinned needs exact dex+`numbersMatch`+`eq:total` single hit (`identityPinned = results.size == 1`) or exactly one printedName match (`nameVerified = results.size == 1`); see F2 for the residual hole.
2. **Mapping: matches the table**, incl. `zh-hans-cn`/`sg` tokens, `_`->`-`, null/blank -> `[ja,en]`, unknown -> `[en,ja]`. Budget: see F1.
3. **Error handling: correct.** `tcgdexFilter`, `tcgdexSearchByName`, `resolveDex` all `catch (e: CancellationException) { throw e } catch (e: Exception)`; a zh 404 (Retrofit HttpException) becomes empty and falls through. `/tcgp/` filter on both TCGdex paths: `.filterNot { it.image?.contains("/tcgp/") == true }`. Cancellation inside `async` children propagates via the scope.
4. **Markers:** grep of `app/src/main` for `tcgdex_`: only `BackfillCardNamesUseCase.kt:25` (`startsWith("tcgdex_")`, skips all, unaffected). `TCGDEX_JA_ID_PREFIX` gone. en ids unchanged; ja output byte-identical.
5. **normalizeName:** precomposed accents (é) are letters, unchanged; Mew/Mewtwo exact-first logic untouched. Thai marks now retained; test `กี` vs `กา` would fail on old code. See F3 for the NFD edge.
6. **Manual search:** `coroutineScope` + `async` + `awaitAll` is structured, no leaked jobs; list order preserved (en, ja, ko, zh-tw, zh-cn, th, id); each child cannot throw except cancellation, so one failing language keeps the rest (test `keeps the other languages when one fails`).
7. **Prompt:** wording-only (verified at `CardScanShared.kt:38`).
8. **Tests:** assert behaviour (labels, ids, setName, order, call counts). One weak (F1).

## Findings
**F1 (P3, conf 8, Reliability/Spec) real worst-case call count is above both the spec's and the Coder's number.** Quote: `val results = firstNonEmptyByDex(langs, dex)` plus `val tcgdex = async { tcgdexSearchByName(name) }`. A zh total miss (name+number+total+dex all present) is dex 3 + name 1 + printedName 2 + en n+t 1 = 7, then `broad-name` `tcgdexSearchByName` (+1, not counted by the Coder's 10), then `broad-dex` up to 3 = **11 TCGdex requests** (ja/en/ko/th/id: 5+1+2 = 8). Only on a total miss, early exit on any hit, each call is a small list query; tolerable for a hobby app but the spec acceptance "<= 7 / <= 5 per scan" is not literally met. Recommendation: accept; if scans feel slow on misses, cap `firstNonEmptyByDex` to `langs.take(1)` (one line, as the Coder noted). The test `zh scan on a total miss stays inside...` splits the budget (`atMost = 7` number-bearing, `atMost = 3` dex) so it cannot catch a regression to 11; add a single total assertion if the cap is ever chosen.
**F2 (P3, conf 6, Correctness: verify) `nameVerified` rides on bidirectional substring `namesMatch`.** Quote: `na == nb || na.contains(nb) || nb.contains(na)`. A short printedName (e.g. `ミュウ`) matches a longer card name (`ミュウツー`); if a single same-number+total hit in another set is the longer name, it is accepted at hash <= 45 with no image evidence. Requires exact number+total and exactly one hit, so rare; spec Risk row 2 already accepted this. Mitigation if it ever happens: require normalized equality (or equality after stripping a trailing `ex`/`V` suffix) for the `nameVerified` flag only.
**F3 (P3, conf 5, verify) NFD vs NFC asymmetry.** Quote: `Character.getType(it)... NON_SPACING_MARK` keep. Before, a decomposed `e` + U+0301 normalised to `e` and matched a precomposed `é` (letter, kept) only by accident of dropping the mark... now they differ. Both TCGdex and Gemini emit NFC in practice, so low risk; `java.text.Normalizer.normalize(raw, NFC)` first would close it. Not blocking.

No P0/P1/P2 found. Wrong-card risk of the new path is bounded as the spec's Risks row 1 states; the 45 ceiling is honestly documented as a gross-failure net only.

## What looks good
KDoc on the constant is candid about the overlapping distributions; label precedence (`IDENTITY_PINNED` before `_NAME`) matches spec; `tcgdexLangsFor` is pure and table-tested; every network edge is exception-safe with CancellationException rethrown; manual search uses one small helper rather than per-language code.

## Score (iteration 1; no prior)
| Dimension | Pts | Reason |
|---|---|---|
| Spec compliance | 23/25 | All T1-T8 and acceptance items traced; -2 for the "<= 7 / <= 5 per scan" acceptance not literally true (F1, disclosed by Coder, also undercounted) |
| Correctness | 22/25 | Boundaries and fall-through verified; -3 for F2/F3 edge cases |
| Security & reliability | 18/20 | No keys/URLs logged, no injection surface (numbers digit-checked via `sendableTotal`), cancellation correct; -2 for 11-call worst case |
| Maintainability | 13/15 | Reuse good, small helpers; `MAX_TCGDEX_LANGS` used only by tests (spec-mandated, harmless); -2 for that and the broad-dex loop doc mismatch |
| Test evidence | 12/15 | 411/0 on `--rerun-tasks` from the real run, new tests assert labels/ids/order and would fail on old code; -3 because the budget test cannot detect the real total and no verbatim per-test log is in `test-results.md` (4 lines only) |
| **Total** | **88/100** | >= 85 and no blocking finding: **ship** |

## Friction rollup
Planner/Coder both note the per-step call-budget undercount (spec omitted broad-dex, Coder omitted broad-name's name search). Lesson candidate "count every cascade step incl. broad fallbacks when stating a call budget" already captured in changes.md friction notes; not generalizable beyond this repo, no lesson file written.
