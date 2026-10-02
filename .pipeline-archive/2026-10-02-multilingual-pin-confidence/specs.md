# Spec: (A) trust metadata-pinned matches, (B) TCGdex ko / zh-tw / zh-cn / th / id

2026-10-01 · Planner · ready for Coder

**NO GRADLE: Coder, Tester and Reviewer must NOT run or retry any Gradle task** (loopback failure in this harness, not a code problem). Verify by reading code. Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks`. Many uncommitted edits from earlier sessions are in the tree: expected, do not revert. No new dependency, no Room change.

## TL;DR
(A) A single TCGdex candidate pinned by metadata is accepted unless the art hash is a real number > `PINNED_HASH_CONTRADICTION_DISTANCE = 45`. Two rules: dex+number+total (existing `identityPinned`) and ja/ko/zh/th/id number+total verified against `printedName` (new `nameVerified`, own path label). (B) Generalize the ja/en cascade to ja, ko, zh-tw, zh-cn, th, id: language-to-TCGdex-code mapping, `tcgdex_<lang>_<id>` ids, `(KO)`-style setName markers, manual search appends all six languages by dexId. Prompt wording only changes in `CardScanShared.kt`.

## Problem
**(A) Evidence, device logs 2026-10-01.** Castform swsh9-116: found correctly by `tcgdex-en+dex+number+total pinned=true`, rejected at hash distance 34 (> `HASH_SINGLE_CANDIDATE_MAX_DISTANCE`=20; an earlier scan of the same card scored 18, so the hash is noisy under glare/angle). Black Kyurem ex SV7a-011: found correctly by `tcgdex-ja+number+total` (pinned=false: TCGdex ja has no dexId for it), hash 28, rejected. Prior data: wrong / not-in-DB picks scored 24-30. Correct cards: 8, 18, 28, 34. **The distributions overlap, so the hash cannot separate right from wrong in the 20-45 band.** For pinned/name-verified cards the metadata is the evidence; the hash is demoted to a gross-failure net (random unrelated art averages ~31 of 63 bits, sd ~4, so 45 is ~3.4 sd out: it only catches a blank/black/inverted crop, not a wrong card). Be honest about that in the constant's KDoc.

**(B) Live TCGdex probes, run 2026-10-01 by Planner** (`https://api.tcgdex.net/v2/{lang}/...`):
| lang | sets | cards | cards with image | `dexId=eq:N` list filter | `localId` + `set.cardCount.official=eq:N` |
|---|---|---|---|---|---|
| ja | 184 | 12781 | 30% | works (dex25: 63, dex6: 44) | works |
| ko | 95 | **239** | **0%** | works but sparse (dex25/6/964: 0; dex388: 1) | works (SV5K-004 found) |
| zh-tw | 98 | 7436 | 29% | works (dex25: 14) | works |
| zh-cn | 57 | **877** | **0%** | works but sparse (dex25: 1, dex964: 1) | works |
| th | 72 | 2921 | 42% | works (dex25: 7) | works |
| id | 70 | 2788 | 41% | works (dex25: 5) | works |
- Lang code `zh` returns **404**: must send `zh-tw` / `zh-cn`. Both are valid path segments.
- `localId` is a substring match in every language (`localId=4` returns 004, 014, 074...): the client-side `numbersMatch` exact filter is mandatory everywhere (already done in `tcgdexFilter`). `eq:` works for total and dex in every language.
- `ja` list also returns several sets with the same number+total (SV7a-011, SM11a-011, SV6a-011): species-blind, needs `printedName`.
- Detail endpoint returns `dexId` for ko/zh/th/id cards too, but the list filter finds only cards that carry it.
- **Palafin ex 228/208 (zh-cn, CSV9C) cannot be found by any number+total query:** set `CSV9C` (and `CSV9.5C`) exist with `official: 208` but contain **0 cards** in TCGdex. `zh-cn localId=228` returns an unrelated SV8a-228. The scan falls to `broad-name` (en substring name search wins before `broad-dex`) and shows wrong English printings, as observed. (B) does NOT fix that specific card; it is a TCGdex data gap. (B) fixes cards in populated sets (e.g. zh-tw SV6, th, id, ja). Do not promise otherwise in logs, UI, or the final report.
- `normalizeName` uses `isLetterOrDigit`: Thai vowel/tone marks are Unicode Mn (non-letters) and get stripped, so Thai names reduce to a few base consonants and the substring `namesMatch` over-matches. With `nameVerified` now granting acceptance, that matters (T6).

## Proposal (technical layer, dependency order)

### T1 Confidence constants + evaluate [S] (`domain/SmartThresholdUseCase.kt`)
```kotlin
enum class ConfidencePath { NUMBER_MATCH, HASH_MARGIN, SINGLE_CANDIDATE_HASH, IDENTITY_PINNED, IDENTITY_PINNED_NAME, NONE }
const val PINNED_HASH_CONTRADICTION_DISTANCE = 45   // in companion; KDoc carries the Castform 34 / Kyurem 28 / wrong 24-30 evidence above
fun evaluate(cards, parsedName, parsedNumber, hashMatch = null, identityPinned = false, nameVerified = false): SearchConfidence
```
Single-candidate branch, in this order:
1. `visuallyConfirmed` (hash <= 20) -> `SINGLE_CANDIDATE_HASH` (unchanged, wins so logs still show hash confirmations).
2. `pinned = identityPinned || nameVerified`; `hashOk = hashMatch != null && hashMatch.card.id == single.id`.
   - `hashOk && distance == Int.MAX_VALUE`: accept **only if `single.imageUrl.isBlank()`** (unchanged F2 guard).
   - `hashOk && distance != Int.MAX_VALUE && distance <= PINNED_HASH_CONTRADICTION_DISTANCE`: accept.
   - Path: `if (identityPinned) IDENTITY_PINNED else IDENTITY_PINNED_NAME` (dex pin is the stronger rule, label it first).
3. Else `NONE` with `topCard = single`, not high. `hashMatch == null` stays reject (conservative, as today).
Multi-candidate logic untouched.

**Decision on `Int.MAX_VALUE` with a non-blank image (download/decode failed):** keep rejecting. Reasoning: there is no visual evidence either way, and the earlier F2 rule exists because MAX_VALUE is also an artifact; none of the observed failures involve a failed download (both had real distances 34 / 28), so widening buys nothing today. Cost of the conservative choice: a pinned card whose image CDN is down lands in LowConfidence and Skyler confirms by tap. One-line widening if logs show it (`pinned` alone with MAX_VALUE). State this in the KDoc.

### T2 Repository result flag [S] (`repository/CardSearchRepository.kt`)
`ParsedSearchResult` add `val nameVerified: Boolean = false` (KDoc: single ja/ko/zh/th/id `number+total` hit that matched Gemini/Claude's `printedName`). Existing `identityPinned` unchanged. Set it only in the printedName step (T3) when `results.size == 1`.

### T3 Language mapping + cascade [L] (`repository/CardSearchRepository.kt`)
New top-level (same file), pure and unit-testable:
```kotlin
internal val TCGDEX_ASIAN_LANGS = listOf("ja", "ko", "zh-tw", "zh-cn", "th", "id")  // also the manual-search append order
internal const val MAX_TCGDEX_LANGS = 3   // cap on languages tried in any one cascade step
internal fun tcgdexLangsFor(language: String?): List<String>
```
`tcgdexLangsFor` (input trimmed, lowercased, `_` -> `-`; `ParsedCardInfo.language` is already lowercase):
| input | result |
|---|---|
| null, blank, `ja` | `[ja, en]` (today's behaviour, includes null) |
| `ko` / `th` / `id` | `[ko, en]` / `[th, en]` / `[id, en]` |
| starts with `zh`, contains token `cn`, `hans` or `sg` (e.g. `zh-cn`, `zh-hans`, `zh-hans-cn`) | `[zh-cn, zh-tw, en]` |
| other `zh*` (`zh`, `zh-tw`, `zh-hant`, `zh-hk`) | `[zh-tw, zh-cn, en]` |
| anything else (`en`, `fr`, `de`, ...) | `[en, ja]` (today's behaviour) |
**Unknown/null does NOT fan out to ko/zh/th/id.** Decision: null means the model could not tell; trying 4 more languages on every failed scan costs 4+ calls for a rare gain, and `broad-*` + ptcg fallbacks still run. Bounded call budget: `langs.size <= MAX_TCGDEX_LANGS`; worst case on a total miss for `zh` = dex step 3 + name 1 + printedName 2 + en number+total 1 = 7 sequential, early-exit on first hit (typical hit = 1 call); for ja/ko/th/id/en worst case = 5 (today's). No parallel fan-out (keeps the early-exit and call count minimal).

`resolveParsed` changes (replace the inline `langs` line and the hard-coded ja step; everything else verbatim):
- `val langs = tcgdexLangsFor(info.language)`.
- Dex step: unchanged loop over `langs`; label `tcgdex-$lang+dex+number+total`; `identityPinned = results.size == 1`.
- printedName step: loop `for (lang in langs.filter { it != "en" })`: `tcgdexFilter(lang, number, total, null, null).matchingParsedName(printedName)`; first non-empty -> `ParsedSearchResult(results, "tcgdex-$lang+number+total", nameVerified = results.size == 1)`. Still skipped when `printedName == null`.
- `broad-dex`: replace `tcgdexFilter(langs[0], ...)` with first non-empty over `langs` (<= 3 calls), then the existing ptcg fallback.
- `searchByParsedInfo`'s `pokemonNames` seeding: replace `startsWith(TCGDEX_JA_ID_PREFIX)` with `isNonEnglishTcgdexId(id)`.

Language marker (T3b), in `TcgdexCardBriefDto.toDomain(lang)`:
- en: unchanged (`tcgdex_$id`, setName = set code).
- other: id `"tcgdex_${lang}_$id"` (e.g. `tcgdex_zh-tw_SV6-036`), setName `"$setCode (${lang.uppercase()})"` giving `(JA) (KO) (ZH-TW) (ZH-CN) (TH) (ID)`. `ja` output is byte-identical to today, so saved `tcgdex_ja_` ids and `M6 (JA)` tests hold.
- Replace `TCGDEX_JA_ID_PREFIX` with `private fun isNonEnglishTcgdexId(id: String) = TCGDEX_ASIAN_LANGS.any { id.startsWith("tcgdex_${it}_") }`; `resolveDex` uses it instead of the `TCGDEX_JA_ID_PREFIX` exclusion.
- **Grep done by Planner:** nothing outside `CardSearchRepository.kt` and its tests parses `tcgdex_ja_`. `BackfillCardNamesUseCase` skips every `tcgdex_*` id (verified, line 25). English ids unchanged.

### T4 Manual search / dex search [M] (same file)
Keep the name `searchByNameWithJapanese` (3 call sites + `QuickScanViewModelTest` mocks; renaming is churn). Update its KDoc: "appends non-English printings (ja, ko, zh-tw, zh-cn, th, id) of the species". Behaviour: after `en` and `dex` resolve, replace the single ja call with
```kotlin
private suspend fun tcgdexAllLangsByDex(dex: Int): List<TcgCard>   // coroutineScope + TCGDEX_ASIAN_LANGS.map { async { tcgdexFilter(it, null, null, dex, null) } }.awaitAll().flatten()
```
Bound: exactly 6 parallel requests, no pagination, each already exception-safe (`tcgdexFilter` never throws, still rethrows `CancellationException`). Order: en, then ja, ko, zh-tw, zh-cn, th, id (list order is preserved by `awaitAll`). `pokemonNames` seeding via `copy(pokemonNames = names)` and `distinctBy { it.id }` as today. `searchByDexNumber`: use the same helper in place of the inline `ja` async (en + all six, then ptcg merged as today). Cost note: Pikachu (dex 25) now appends ~90 non-English cards (ja 63, zh-tw 14, th 7, id 5, zh-cn 1); accepted, no cap in v1. Many ko / zh-cn cards have no image (blank thumbnail).

### T5 Logging [S] (`ui/scanner/ScannerViewModel.kt`)
Pass `search.nameVerified` to `evaluate`. Extend the `search candidates` log: `pinned=${search.identityPinned} nameVerified=${search.nameVerified}`. The existing `confidence ... path=` log already distinguishes `IDENTITY_PINNED` vs `IDENTITY_PINNED_NAME`. Leave the TEMP DIAGNOSTIC block alone.

### T6 Name normalization [S] (`normalizeName`, same file)
Keep Unicode combining marks: `filter { it.isLetterOrDigit() || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() } }`. Fixes Thai over-matching and decomposed kana. English behaviour unchanged.

### T7 Prompt wording [S] (`domain/CardScanShared.kt` ONLY)
Change only the `language` value text, same JSON field set: `"language":"ISO 639-1 code of the language the card is printed in (e.g. en, ja, ko, th, id, fr); for Chinese cards return zh-CN for Simplified characters or zh-TW for Traditional characters, or null"`. `toParsedCardInfo` already trims + lowercases (`zh-CN` -> `zh-cn`); no mapping change. `scripts/claude_scan_bakeoff.py:48` holds a copy of the old wording: leave it (Skyler's script), mention in the report. Existing `ClaudeCardScannerTest` compares against the constant, so it stays green.
Is the current prompt already usable? Yes for ja/ko/th/id/en (ISO 639-1 is unambiguous); the live Palafin card returned bare `zh`, which `tcgdexLangsFor` handles via the both-scripts order. Adding the examples/hint only improves script ordering.

### T8 Tests [M] (mockk + `runTest`, existing helpers `brief(...)`, `parsedFull(...)`, `repo()`)
`SmartThresholdUseCaseTest` (**two existing tests flip**, update them):
- `pinned single card whose image disagrees is still rejected` (distance 30): now ACCEPTED. Rewrite: distance 34 -> high, `IDENTITY_PINNED` (Castform). Add: distance 46 -> low `NONE`; distance 45 -> high; distance 20 and 21 -> `SINGLE_CANDIDATE_HASH` / `IDENTITY_PINNED`.
- Keep `pinned ... real image failed to download is rejected` (MAX_VALUE + non-blank image) unchanged, green.
- Add: `nameVerified` + distance 28 -> high `IDENTITY_PINNED_NAME`; `nameVerified` + 46 -> low; `nameVerified` + MAX_VALUE + image -> low; `nameVerified` + MAX_VALUE + blank image -> high; both flags -> path `IDENTITY_PINNED`; neither flag + distance 34 -> low; `nameVerified` with 2 cards -> existing multi path; `hashMatch == null` + pinned -> low.
`CardSearchRepositoryTest` (default `filterCards` stub already returns empty):
1. Existing Kyurem test: also `assertTrue(result.nameVerified)`; existing M6 test: `assertFalse(result.nameVerified)`.
2. `tcgdexLangsFor` table test: null, "", `ja`, `ko`, `th`, `id`, `zh`, `zh-tw`, `zh-hant`, `zh-cn`, `zh-hans`, `ZH_CN`, `en`, `fr`.
3. `zh` with dex+number+total: `zh-tw` empty, `zh-cn` hit -> label `tcgdex-zh-cn+dex+number+total`; assert `zh-tw` called before `zh-cn`; `zh-cn` language value goes `zh-cn` first (`coVerifyOrder`).
4. ko/th/id: stub `filterCards("ko","4","eq:71",null,null)` returning `brief("SV5K-004","004","수풀부기")` with printedName `수풀부기` -> id `tcgdex_ko_SV5K-004`, setName `SV5K (KO)`, label `tcgdex-ko+number+total`, `nameVerified` true. Same shape for th (`SV6 (TH)`) and id.
5. printedName mismatch -> empty / falls through; two printedName matches -> `nameVerified` false.
6. Thai marks: `normalizeName`-driven: names that differ only in vowel marks do not match (use `searchByParsedInfo` with two th briefs, only the exact printedName kept).
7. Call budget: `language="zh"`, every TCGdex call empty -> `coVerify` total `filterCards` calls <= 7, and no call with lang `ko`/`th`/`id`/`ja`. Null language -> only `ja`/`en` calls (no ko/zh/th/id).
8. `searchByNameWithJapanese("Malamar", 687)` with stubs for all six langs -> order en, ja, ko, zh-tw, zh-cn, th, id; each lang filter called exactly once; one lang throws -> others still returned. Existing ja tests stay green (`getCard` still called once for resolve).
9. `searchByDexNumber` includes all six.
10. `pokemonNames` seeding applies to a `tcgdex_th_` card from `searchByParsedInfo`; English `tcgdex_` id is not seeded.
`GeminiCardScannerTest`: add one test that `"language":"zh-CN"` parses to `zh-cn`.

## User stories
- As Skyler, I want Castform-style scans (right card found by dex+number+total) accepted so that I stop re-confirming a card the metadata already proves.
- As Skyler scanning a Korean, Thai, Indonesian or Traditional-Chinese card, I want it matched in its own language and marked `(KO)` etc. so that I assign the exact printing.
- As Skyler searching by name, I want printings in every TCGdex language listed, English first, so that I can assign the one I own.

## Acceptance criteria
- [ ] **Given** a single `identityPinned` candidate with real hash 34, **when** evaluate runs, **then** high via `IDENTITY_PINNED`. Real hash 46 -> low. Hash 45 -> high.
- [ ] **Given** a single `nameVerified` candidate with hash 28, **then** high via `IDENTITY_PINNED_NAME`; `ScannerMatch` logs show `path=IDENTITY_PINNED_NAME` (vs `IDENTITY_PINNED`) and `nameVerified=true`.
- [ ] `Int.MAX_VALUE` with a non-blank image stays rejected; with a blank image accepted (pinned or nameVerified).
- [ ] Multi-candidate behaviour unchanged (existing multi-candidate tests green untouched).
- [ ] `tcgdexLangsFor` matches the table; `zh` queries zh-tw then zh-cn; zh-cn first only for simplified indicators; null keeps `[ja, en]` and never queries ko/zh/th/id.
- [ ] Non-English ids are `tcgdex_<lang>_<id>`, setName suffix `(JA)`, `(KO)`, `(ZH-TW)`, `(ZH-CN)`, `(TH)`, `(ID)`; English ids and existing `tcgdex_ja_` / `M6 (JA)` outputs byte-identical.
- [ ] Any one scan makes <= 7 TCGdex calls; ja/en/ko/th/id <= 5.
- [ ] Manual search appends the six languages (6 parallel requests max) in the stated order; a failing language never loses the others.
- [ ] Prompt change is wording-only in `CardScanShared.kt`; JSON field set identical.
- [ ] No Room/dependency change; no Gradle run by any stage. Skyler's `--rerun-tasks` run is green.

## Out of scope
Pre-filling/saving the card's Language; removing pokemontcg.io (all `POKEMONTCG-IO-FALLBACK` tags stay); Gemini 429 circuit breaker; pre-fetching images for ko/zh-cn; fr/de/es/it/pt TCGdex languages; a per-language result cap in manual search; reordering `broad-dex` before `broad-name` for non-English scans (see Open questions); the `scripts/claude_scan_bakeoff.py` prompt copy.

## Dependencies
- TCGdex public API (no key); behaviour as in the probe table. ko/zh-cn data is sparse and imageless: matches there rely on `printedName` + metadata.
- Order: T1 -> T2 -> T3 (+T6) -> T5 -> T4 -> T7; T8 alongside each. T1 and T4 are independent of each other.

## Open questions
- [ ] (Skyler, non-blocking) For a scan with a detected non-English language, should `broad-dex` (non-English species cards) run **before** `broad-name` (English name search)? It would have shown zh-cn Palafin SV8a-045 instead of English printings, but it changes today's order for ja too. Left as is.
- [ ] (Skyler, non-blocking) Widen the guard so `Int.MAX_VALUE` + non-blank image is also accepted when pinned? Left conservative; revisit only if `ranked=` logs show failed downloads on pinned cards.
- [ ] (Reviewer to confirm) 45 is a sanity ceiling, not a discriminator (Risks row 1). If a wrong pinned card is ever accepted, tighten `PINNED_HASH_CONTRADICTION_DISTANCE`, not the 20 ceiling.

## Risks and mitigations
| Risk | L | I | Mitigation |
|---|---|---|---|
| Accepting hash 21-45 on pinned cards lets a wrong card through (hash cannot separate 28 from 34) | L | M | Pin requires exact dex + exact number + exact total (or `printedName` match) and a single candidate; the ceiling only catches gross failures; log labels show which rule fired |
| `nameVerified` false accept from a short or script-collapsed name (`namesMatch` is substring) | M | M | T6 keeps combining marks; requires `size == 1`; flag only set by the printedName step; test 6 |
| Simplified/Traditional printedName vs the other script's data mismatches | M | L | Fails closed (no match, falls through); both zh codes are queried |
| zh-cn / ko data sparse and imageless: many cards still unfindable (Palafin CSV9C: 0 cards) | H | M | Documented, not fixed; ptcg + broad fallbacks unchanged; verification uses populated sets |
| More sequential calls on a total miss (7 worst case for zh) | M | L | Early exit, `MAX_TCGDEX_LANGS`, no fan-out for null/unknown language |
| Manual-search list grows (~90 extra cards for Pikachu) | H | L | Appended after en/ja, ordered, no cap yet; add per-language cap if it feels noisy |
| 6 parallel requests per manual search hit TCGdex limits | L | L | Each call is exception-safe and returns empty; same free keyless API as today |
| Gemini returns `zh-tw`/`zh-cn` variants or unknown codes | M | L | Mapping is tolerant (`zh*`), unknown codes use today's `[en, ja]` |

## Smallest shippable increment
T1 + T2 + T5 (+ T8 tests for them): fixes the two observed rejections (Castform via `identityPinned`, Black Kyurem via `nameVerified` on the existing ja step). T3 + T3b + T6 + T7 (languages in the scanner) and T4 (manual search) can follow independently.

## Verification procedure (Skyler)
1. `.\gradlew.bat testDebugUnitTest --rerun-tasks`: all green (flipped test rewritten, new tests pass).
2. `.\gradlew.bat installDebug`, then `adb logcat -s ScannerMatch:*` (adb in `%LOCALAPPDATA%\Android\Sdk\platform-tools`).
3. Rescan Castform swsh9-116: expect `query=tcgdex-en+dex+number+total pinned=true`, `confidence isHigh=true path=IDENTITY_PINNED` even at distance 20-45.
4. Rescan Black Kyurem ex SV7a-011: expect `query=tcgdex-ja+number+total ... nameVerified=true`, `path=IDENTITY_PINNED_NAME`.
5. Zh-tw / th / id / ko card from a populated set (check the set exists in TCGdex first): expect `language=zh-tw` (or `zh-TW` lowercased), `query=tcgdex-<lang>+...`, setName suffix `(ZH-TW)` etc. in the result.
6. Palafin ex 228/208 (zh-cn): expect it still is NOT matched exactly (CSV9C has 0 cards in TCGdex); confirm it logs `language=zh-cn` (or `zh`) and tries zh-cn/zh-tw before falling to `broad-name`. This is expected, not a regression.
7. Manual search "Pikachu": English first, then `(JA)`, `(KO)`/`(ZH-TW)`/`(TH)`/`(ID)` printings.
8. If a wrong card is auto-accepted, copy the `ranked=` and `path=` lines back for tuning.

## Friction Notes
- TCGdex `zh` is a 404 and `CSV9C` exists with 0 cards: set listing success does not mean card data exists. Probe the card list, not the set record.
- Windows Python needs `PYTHONIOENCODING=utf-8` to print CJK/Thai probe output.
