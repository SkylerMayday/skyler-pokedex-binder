# Spec: explicit "closest English printing" fallback for non-English scans

2026-10-02 · Planner · builds on `.pipeline-archive/2026-10-02-multilingual-pin-confidence/`

**NO GRADLE: Coder, Tester, Reviewer must NOT run or retry any Gradle task** (loopback failure in this harness, not a code problem). Verify by reading code. Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks`. Uncommitted earlier edits are expected: do not revert. No new dependency, no Room change, no new network call; language-marker logic (`tcgdexLangsFor`, `tcgdex_<lang>_` ids, `(KO)` suffixes) untouched.

## TL;DR
A scan with a non-English language whose results are all English cards (no `tcgdex_<lang>_` id) is a stand-in, not the physical card. Flag it (`ParsedSearchResult.crossLanguage`), make `SmartThresholdUseCase.evaluate` return low confidence for it on every path, label the LowConfidence list, and sort that list by hash distance. One flag, one guard, one pure ordering function.

## Problem
Palafin ex 228/208 (zh-cn; Claude read `language=zh`, dex 964): TCGdex CSV9C has 0 cards, so the scan falls to `broad-name` and returns 4 English printings; hasher distances 26/28/30/34 (noisy). It stayed low only by luck of the hash.

Read in code:
- English cards reach a non-English scan through every non-native step: `tcgdex-en+dex+number+total` (dex loop runs over `langs`, which ends in `en`), `tcgdex-en+name+number+total`, `tcgdex-en+number+total`, the ptcg cascade labels, `broad-name`, `broad-dex`, `broad-number`.
- **Number-match trap confirmed:** `evaluate`'s multi-candidate branch returns high `NUMBER_MATCH` when any candidate's number equals `parsedNumber` (ViewModel passes Claude's OCR'd number). An English card numbered 228 would be auto-accepted. The single-candidate `IDENTITY_PINNED` path (en dex pin) accepts a stand-in too. So the guard belongs in `evaluate`, not only in ViewModel hash handling.
- `ScannerViewModel.kt:131` builds `LowConfidence(candidates)` in search order, ignoring `hashMatch.ranked` (top 5 `Pair<cardId, distance>`, ascending, `Int.MAX_VALUE` = download failed).
- `CardSelectionList` (`ScannerScreen.kt:989`) has no header; only caller is the `LowConfidence` branch (line 912). Screen uses inline strings. No test references `LowConfidence`/`CardSelectionList`. `ScannerViewModelTest` already tests a top-level `internal` pure function (`confidenceCheckInputs`): follow it.

## Proposal (dependency order)

### T1 `domain/SmartThresholdUseCase.kt` [S] - guard
- `ConfidencePath` add `CROSS_LANGUAGE` (no exhaustive `when` over it existed last stage; re-grep).
- `evaluate(..., nameVerified = false, crossLanguage: Boolean = false)`. Right after the `cards.isEmpty()` return, before every branch:
```kotlin
// Stand-in printing from another language than the physical card: never auto-accept by number, pin or hash.
if (crossLanguage) return SearchConfidence(cards, false, cards.first(), ConfidencePath.CROSS_LANGUAGE)
```
Default `false` leaves all existing call sites/tests unchanged. One-line KDoc on the param.

### T2 `repository/CardSearchRepository.kt` [S] - flag
- `ParsedSearchResult` add `val crossLanguage: Boolean = false` (KDoc: scan language non-English but every card is an English printing; stand-in, never auto-accept).
- New top-level next to `isNonEnglishTcgdexId`:
```kotlin
internal fun isCrossLanguageFallback(language: String?, cards: List<TcgCard>): Boolean {
    val base = language?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()
    return base.isNotEmpty() && base != "en" && cards.isNotEmpty() && cards.none { isNonEnglishTcgdexId(it.id) }
}
```
Decision: derive from result card ids, not a step-label allow-list (rejected: labels drift and an allow-list would miss the `tcgdex-en+*` steps, the more dangerous ones). Null/blank language = unknown = false.
- `searchByParsedInfo`: `val result = resolveParsed(info).let { it.copy(crossLanguage = isCrossLanguageFallback(info.language, it.cards)) }`, then the existing `pokemonNames` seeding (its `copy(cards=...)` keeps the flag, including the early `?: return result`). `resolveParsed` untouched.

### T3 `ui/scanner/ScannerViewModel.kt` [M] - state, order, log
- `LowConfidence(val cards: List<TcgCard>, val crossLanguage: Boolean = false)` (default keeps `LowConfidence(emptyList())` compiling).
- New top-level pure fn beside `confidenceCheckInputs`:
```kotlin
internal fun lowConfidenceCards(candidates: List<TcgCard>, ranked: List<Pair<String, Int>>?, crossLanguage: Boolean): List<TcgCard>
```
`if (!crossLanguage || ranked.isNullOrEmpty()) return candidates`. Else head = ranked entries with distance != `Int.MAX_VALUE`, `sortedBy` distance (stable), mapped via `candidates.associateBy { it.id }` (`mapNotNull`, `distinct`); return `head + candidates.filter { it !in head }`. Always a permutation of `candidates`. Failed-download and unranked cards trail in original order. Ceiling: `ranked` is top 5 only (`RANKED_LOG_SIZE`, unchanged).
- Scope: ordering only when `crossLanguage`, so a normal English low-confidence list is unchanged.
- `processImage`: pass `search.crossLanguage` as the last arg of `evaluate`; replace `LowConfidence(candidates)` with `LowConfidence(lowConfidenceCards(candidates, hashMatch?.ranked, search.crossLanguage), search.crossLanguage)`. Add `crossLanguage=${search.crossLanguage}` to the `search candidates` log; the existing `confidence ... path=` log shows `CROSS_LANGUAGE`. No ViewModel-side second guard (single mechanism). TEMP DIAGNOSTIC block untouched; no key/URL in logs.

### T4 `ui/scanner/ScannerScreen.kt` [S] - label
- `CardSelectionList(..., header: String? = null)`: when `header != null && cards.isNotEmpty()`, render `Text(header, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))` before the `LazyColumn`. Empty list keeps the existing "No cards found" text.
- `LowConfidence` branch: `header = if (s.crossLanguage) "No card found in this language - closest English printings of ${s.cards.firstOrNull()?.name ?: "this card"}" else null`. Inline string (screen convention).

### T5 Tests [M] (JUnit4/mockk/`runTest`; read each file's helpers first)
`SmartThresholdUseCaseTest`: `crossLanguage` with (a) single `identityPinned` + distance 5, (b) single `nameVerified` + distance 5, (c) **number-match trap**: 2 candidates, one numbered like `parsedNumber` -> not high, path `CROSS_LANGUAGE`, with a `crossLanguage=false` control proving `NUMBER_MATCH`, (d) 2 candidates, `parsedNumber=null`, margin 40 -> not high, control `HASH_MARGIN`, (e) `hashMatch=null` -> not high, `topCard == cards.first()`.
`CardSearchRepositoryTest`: (1) `isCrossLanguageFallback` table: null/""/"en"/"EN-US"/"en_GB" false; "zh"/"ko"/"fr" with English cards true; "zh" with `tcgdex_zh-cn_X` false; "ja" with `tcgdex_ja_X` false; empty cards false; mixed native+English false. (2) Palafin shape: language `zh`, 228/208, dex 964, name `Palafin ex`, native filters empty, `tcgdexApi.searchCards("Palafin ex")` returns English briefs -> `matchedQuery == "broad-name"`, `crossLanguage` true. (3) language `zh` + single en dex+number+total hit -> `identityPinned` true and `crossLanguage` true. (4) same cards with language `en` and `null` -> false. (5) existing ko native hit -> false.
`ScannerViewModelTest` (reuse its `card(name, number)`): cross + ranked [(b,26),(a,30),(c,34)] over [a,b,c] -> [b,a,c]; `MAX_VALUE` entry after finite ones; unranked after ranked in original order; ranked id not in candidates ignored; result is a permutation; ties stable; `ranked=null`/empty -> unchanged; `crossLanguage=false` with ranked data -> unchanged.

Order: T1 -> T2 -> T3 -> T4, tests alongside. Files: the four above plus `SmartThresholdUseCaseTest.kt`, `CardSearchRepositoryTest.kt`, `ScannerViewModelTest.kt`.

## User stories
- As Skyler, I want the scanner to say no card exists in this language and show the closest English printings, so I know the stand-in is not my physical card.
- As Skyler, I want same-art printings first so I find the English twin at a glance.
- As Skyler recording exact printings, I want a stand-in never auto-accepted, so my collection holds only cards I confirmed.

## Acceptance criteria
- [ ] Non-null non-`en` language (incl. `zh`, `fr`) + all-English result cards -> `crossLanguage == true`; null/blank/`en`/`en-US` or any native-id card -> false.
- [ ] `crossLanguage=true` -> `evaluate` is never high, path `CROSS_LANGUAGE`, for pinned, nameVerified, number-equals-parsed, margin >= 12 and hash <= 20 alike (number-match case tested with a false control).
- [ ] `crossLanguage=false`: `evaluate` unchanged, existing `SmartThresholdUseCaseTest` green untouched.
- [ ] Cross-language LowConfidence with cards shows "No card found in this language - closest English printings of <first card name>" above the list; otherwise no header.
- [ ] `lowConfidenceCards`: finite-distance ranked cards ascending, then the rest in original order (failed downloads last); never drops/adds a card; non-cross or no hash data -> unchanged.
- [ ] Logcat shows `crossLanguage=true` and `path=CROSS_LANGUAGE` for the Palafin scan.
- [ ] No Room/dependency/network change, language-marker code untouched, no Gradle run by any stage; Skyler's `--rerun-tasks` is green.

## Out of scope
A source for missing sets (CSV9C); pre-filling language on assign; cross-language auto-match by set mapping; reordering `broad-name`/`broad-dex`; ordering normal low-confidence lists; showing distances in the UI; ranking beyond top 5; `strings.xml` migration.

## Dependencies
Existing: `HashMatchResult.ranked`, `isNonEnglishTcgdexId` (private top-level, same file as the new function). No external changes.

## Open questions
- [ ] (Skyler, non-blocking) fr/de/es/it/pt scans: no native source exists, so every such card is now low-confidence with the English label even when the English printing is the owned card. Done per your brief (any non-`en`). To narrow to `TCGDEX_ASIAN_LANGS` bases, change `isCrossLanguageFallback` only.
- [ ] (Skyler, non-blocking) Hash-distance order for all LowConfidence lists, not just cross-language? Drop the `crossLanguage` condition in `lowConfidenceCards`. Left scoped.
- [ ] (Reviewer) A ja scan whose ja lookup misses but English dex pin hits was auto-acceptable and is now forced low: intended.

## Risks and mitigations
| Risk | L | I | Mitigation |
|---|---|---|---|
| Claude returns wrong/`en` language for a foreign card, guard bypassed | M | M | Same as today for that case; verification checks logged `language=` |
| Foreign card in a no-data language always needs a tap | H | L | Intended; one-screen confirm |
| Mixed native+English list not flagged | L | L | Cascade emits one source per step; tested |
| `ranked` top-5 only; tail unsorted when >5 candidates | M | L | Documented; closest-art cards are in the head |
| Noisy hash (26-34) makes order near-random | M | L | Label says "closest"; user picks |
| New enum value breaks an exhaustive `when` | L | M | Coder re-greps `ConfidencePath` usages |

## Smallest shippable increment
T1 + T2 + the `evaluate(crossLanguage)` wiring and log line in T3, with tests: delivers the never-auto-accept guarantee. Ordering and label follow independently.

## Verification procedure (Skyler)
1. `.\gradlew.bat testDebugUnitTest --rerun-tasks`: green (no agent runs Gradle).
2. `.\gradlew.bat installDebug`; `adb logcat -s ScannerMatch:*` (adb in `%LOCALAPPDATA%\Android\Sdk\platform-tools`).
3. Rescan Palafin ex 228/208 zh: expect `language=zh`, `query=broad-name`, `crossLanguage=true`, `confidence isHigh=false path=CROSS_LANGUAGE`; screen shows "No card found in this language - closest English printings of Palafin ex" with English printings, lowest `ranked=` distance first.
4. Normal English card: unchanged (high confidence as before, or low with no header and the old order), log `crossLanguage=false`.
5. Black Kyurem ex SV7a-011 (ja): `crossLanguage=false`, still `IDENTITY_PINNED_NAME`.
6. If a cross-language card is ever auto-accepted, copy the `search candidates` and `confidence` log lines back.

## Friction Notes
- Enumerated every cascade step that can emit English cards instead of trusting labels; re-read that list when the cascade changes.
