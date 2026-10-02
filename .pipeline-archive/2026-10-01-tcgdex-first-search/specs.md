# Spec: TCGdex primary card source (scanner + manual search, incl. Japanese)

2026-10-01 · Planner · ready for Coder

## TL;DR
Make TCGdex the first source for the scanner cascade and for manual search. It queries `ja` and `en` by exact number, set total and dex. pokemontcg.io (shuts down 2027-03-01) is demoted to a grep-marked fallback. No schema change, no new dependency.

**NO GRADLE:** Coder, Tester and Reviewer must not run or retry any Gradle task (loopback failure in this harness). Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks`. The uncommitted session 20/21 edits (ArtHash, name-verify, …) are expected. Do not revert them.

## Problem
Gemini reads the card, but lookups only hit English data. Japanese cards return nothing (Mega Malamar ex, Wishiwashi ex), or the wrong card via the species-blind `number+total` (Black Kyurem 11/64 → Jungle Snorlax). pokemontcg.io has no Japanese cards and dies 2027-03-01.

### Evidence: live TCGdex probes, re-run by Planner (these drive the design)
| Query | Result | Consequence |
|---|---|---|
| `ja/cards?dexId=6` | 2399 cards | filters are **substring** matches. Send dexId as `eq:<n>` (`eq:6` gives 44 Charizard) |
| `en/cards?localId=16&set.cardCount.official=eq:172` | 016, 116, 160-169 | send localId plain, then **exact-filter client-side** (`numbersMatch`). `localId=eq:011` returns [] |
| `set.cardCount.official=eq:76` | works | send total as `eq:<n>` |
| `ja/cards?localId=048&set.cardCount.official=76&dexId=eq:687` | [M6-048] | pinned step works |
| `ja/...localId=011&official=64&dexId=eq:646` | **[]** | SV7a-011 has **no dexId** in ja data |
| `ja/...localId=011&official=64` | SV7a-011, SM11a-011, SV6a-011 | species-blind. It needs a ja-script verifier → `printedName` |
| `en/cards/swsh9-116` | `"dexId":[351]` | detail endpoint resolves dex |
| `assets.tcgdex.net/ja/SV/SV6a/034/high.webp` | 200. M6 cards have no `image` | hasher → `Int.MAX_VALUE` |

## Proposal (technical layer, dependency order)

### T1 TcgdexApi [S]
- `di/NetworkModule.kt` `provideTcgdexRetrofit`: change the base URL to `https://api.tcgdex.net/v2/`.
- `data/remote/TcgdexApi.kt`:
```kotlin
@GET("en/cards") suspend fun searchCards(@Query("name") name: String): List<TcgdexCardBriefDto> // path only changed
@GET("{lang}/cards") suspend fun filterCards(
    @Path("lang") lang: String,
    @Query("localId") localId: String?,
    @Query("set.cardCount.official") officialTotal: String?, // "eq:76"
    @Query("dexId") dexId: String?,                          // "eq:687"
    @Query("name") name: String?
): List<TcgdexCardBriefDto>   // null @Query omitted by Retrofit. No default args on the interface
@GET("{lang}/cards/{id}") suspend fun getCard(@Path("lang") lang: String, @Path("id") id: String): TcgdexCardDetailDto
```
- `TcgdexCardDto.kt`: add `data class TcgdexCardDetailDto(val id: String, val name: String, val dexId: List<Int>? = null)`. Moshi ignores unknown fields.
- Grep-verified: `TcgdexApi` is used only by `CardSearchRepository`. `BackfillCardNamesUseCase` uses only `PokemonTcgApi` and skips `tcgdex_*` ids.

### T2 Gemini `language` + `printedName` [S]
- `ParsedCardInfo`: append `val language: String? = null` and `val printedName: String? = null`.
- `GeminiCardScanner`:
  - Add both fields to `GeminiCardResult` (default null).
  - Add to the prompt JSON: `"language":"ISO 639-1 code of the language the card is printed in (e.g. en, ja, fr), or null"` and `"printedName":"card name exactly as printed, in its printed language and script, or null"`.
  - Map as `language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }` and `printedName?.takeIf { it.isNotBlank() }`.
- Why printedName: Japanese names can't be compared with Gemini's English `name`, and some ja cards lack dexId.

### T3 Scanner cascade [L] (`repository/CardSearchRepository.kt`)
- `ParsedSearchResult` gets `val identityPinned: Boolean = false`.
- Helper. It catches like `safeSearch`, applies the tcgp filter on every TCGdex path, and exact-filters the number:
```kotlin
private suspend fun tcgdexFilter(lang: String, number: String?, total: String?, dex: Int?, name: String?): List<TcgCard>
// filterCards(lang, number?.trimStart('0')?.ifEmpty{"0"}, total?.let{"eq:$it"}, dex?.let{"eq:$it"}, name)
//  .filterNot { it.image?.contains("/tcgp/") == true }.map { it.toDomain(lang) }
//  .filter { number == null || numbersMatch(it.number, number) }
```
  - Send total only if it is all digits after `trimStart('0')`.
- `TcgdexCardBriefDto.toDomain(lang = "en")`:
  - en is unchanged (id `tcgdex_$id`, setName = set code, so existing ids stay stable).
  - ja: id `tcgdex_ja_$id` and `setName = "$setCode (JA)"`. This is the JA marker. It shows in all 8 existing `"${card.setName} · #${card.number}"` UI sites and in the saved `assignedCardSetName` with **zero UI edits**.
- `langs` = ja-first (`["ja","en"]`) when `info.language` is `"ja"` or null. It is `["en","ja"]` otherwise.
- Order (first non-empty wins):

| # | Needs | Query | Post-filter | Label | pinned |
|---|---|---|---|---|---|
| 1 | dex,num,total | `langs[0]` dex+num+total | exact num | `tcgdex-<lang>+dex+number+total` | size==1 |
| 2 | same | `langs[1]` | exact num | same pattern | size==1 |
| 3 | name,num,total | en name+num+total | exact num | `tcgdex-en+name+number+total` | no |
| 4 | num,total,**printedName** | ja num+total | exact num + `matchingParsedName(printedName)`; **skip if printedName null** | `tcgdex-ja+number+total` | no |
| 5 | num,total | en num+total | exact num + existing lenient `matchingParsedName(name)` | `tcgdex-en+number+total` | no |
| 6 | — | **existing ptcg cascade verbatim** (same labels/verify) | as today | unchanged | no |
| 7 | name | broad-name: `mergeResults(tcgdexSearchByName, ptcg)`, **TCGdex first** | — | `broad-name` | no |
| 8 | dex | `tcgdexFilter(langs[0], dex only)`, else ptcg `nationalPokedexNumbers:` | — | `broad-dex` | no |
| 9 | num | unchanged ptcg | name-verify | `broad-number`/`none-matched` | no |

- `namesMatch` works on kana (`isLetterOrDigit`). Never compare ja names to the English `name`.
- Scanner ja results get `copy(pokemonNames = <info.cardName split by the existing " & "/substringBefore(" ") rule>)` when cardName is non-null. Otherwise `confirmCard`'s global-scan slot match (English slot names) always misses and dumps ja cards into Card History.
- **Isolation:** put step 6, step 9 and the ptcg halves of 7-8 in one private function or clearly delimited block. Tag every pokemontcg.io touchpoint in the file (`api`, `safeSearch`, `TcgCardDto.toDomain`, `searchPromosByName`, `searchByNumber`, the unused `searchByNameAndNumber`/`searchByNumberAndTotal`) with the exact comment `// POKEMONTCG-IO-FALLBACK: remove before 2027-03-01`.

### T5 Confidence: pinned, no image [S]
- `SmartThresholdUseCase`: add `ConfidencePath.IDENTITY_PINNED`, and change the signature to `evaluate(cards, parsedName, parsedNumber, hashMatch = null, identityPinned: Boolean = false)`. In the `size == 1` branch, after `visuallyConfirmed`:
```kotlin
if (identityPinned && hashMatch != null && hashMatch.card.id == single.id && hashMatch.distance == Int.MAX_VALUE)
    return SearchConfidence(cards, true, single, ConfidencePath.IDENTITY_PINNED)
```
  - It only rescues the **no-image** case. A real image with distance > 20 is still rejected (the photo contradicts the metadata). Multi-candidate logic is unchanged.
- `ScannerViewModel`:
  - Pass `search.identityPinned` to `evaluate`.
  - Add `language=`/`printedName=` to the `gemini parsed` log and `pinned=` to the `search candidates` log.
  - Leave the TEMP DIAGNOSTIC block alone.
- **PerceptualHasher: no change** (confirmed). An empty imageUrl makes `Request.Builder().url("")` throw inside `runCatching`, which gives `Int.MAX_VALUE`. `.webp` decodes via BitmapFactory.

### T4 Manual search [M]
- New `CardSearchRepository` function:
```kotlin
/** Manual search + Quick Scan: TCGdex en first, ptcg merged as fallback, then ja printings of the species. */
suspend fun searchByNameWithJapanese(name: String, dexNumber: Int? = null): List<TcgCard>
```
  1. `en = mergeResults(tcgdexSearchByName(name), safeSearch(nameQuery(name)))`, concurrent via `async`.
  2. `dex = dexNumber ?: resolveDex(en, name)`. `resolveDex` takes the first en card with a `tcgdex_` id (not `tcgdex_ja_`) and `namesMatch(it.name, name)`, then calls `getCard("en", id.removePrefix("tcgdex_")).dexId?.firstOrNull()`. It catches errors and returns null.
  3. If dex is set: `ja = tcgdexFilter("ja", null, null, dex, null).map { it.copy(pokemonNames = seed?.pokemonNames ?: split(name)) }`.
  4. Return `en + ja` (ids distinct).
- `searchByDexNumber(dex)`: en dex + ja dex via `tcgdexFilter`, merged via `mergeResults` with ptcg `nationalPokedexNumbers:` as fallback.
- **`searchByName` is deliberately unchanged** (ptcg-first). Its other caller, `PersonalCollectionRepository.refreshPokemon`, keys owned state by `cardId`. Flipping the primary turns `xyevo-25` into `tcgdex_xyevo-25` and orphans owned toggles. The existing test asserting `xyevo-25` must stay green.
- Callers:
  - `ManualSearchViewModel`: both `searchByName(trimmed)` calls become `searchByNameWithJapanese(trimmed)`. Promo branch unchanged.
  - `QuickScanViewModel`:
    - `searchForSlot` receives `slot.effectiveSearchName` and `slot.dexNumber`, and calls `searchByNameWithJapanese(name, dex)`.
    - `search()`'s `searchByName(trimmed)` calls become `searchByNameWithJapanese(trimmed)`.
- `searchStreaming`/TCGCSV untouched. JA names never collide in `isSameCard`.

### T6 Tests [M] (mockk + `runTest`; MockWebServer for Gemini)
- `CardSearchRepositoryTest`: add the default stub `coEvery { filterCards(any(), any(), any(), any(), any()) } returns emptyList()` to the `tcgdexApi` mock. Cases:
  1. `lang ja, 48, 76, 687`, stub `filterCards("ja","48","eq:76","eq:687",null)` → M6-048 (no image). Expect id `tcgdex_ja_M6-048`, setName `M6 (JA)`, label `tcgdex-ja+dex+number+total`, and `identityPinned`.
  2. Number "16", stub returns 016/116/160. Only 016 is kept.
  3. Dex step empty, ja num+total returns 3 ja-named cards. With printedName `ブラックキュレムex`, only SV7a-011 is kept (`tcgdex-ja+number+total`). With printedName null, the step is skipped.
  4. With lang en, the en dex step runs before ja (assert label).
  5. All TCGdex steps empty: ptcg `dex+number+total` wins with its old label. All existing cascade tests stay green.
  6. A `/tcgp/` image is dropped on `filterCards` paths.
  7. `searchByNameWithJapanese("Malamar")`: `getCard` gives [687], so ja results are appended with `(JA)`. If `getCard` throws, only en is returned.
  8. `searchByNameWithJapanese("Castform", 351)` never calls `getCard` (`coVerify(exactly = 0)`).
- `GeminiCardScannerTest`:
  - `"language":"JA","printedName":"…"` gives `language == "ja"`.
  - Extend the missing-fields test: both fields null.
- `SmartThresholdUseCaseTest`:
  - pinned + MAX_VALUE → high `IDENTITY_PINNED`.
  - pinned + 30 → low `NONE`.
  - unpinned + MAX_VALUE → low.
  - pinned with 2 cards → existing path.

## User stories
- As Skyler scanning a Japanese card, I want the exact JA printing found so that I stop getting the wrong card.
- As Skyler searching by name, I want JA printings listed and marked (JA) so that I can assign the one I own.

## Acceptance criteria
- [ ] **Given** Gemini parses `ja, 48, 76, dex 687`, **when** scanning, **then** the result is `[tcgdex_ja_M6-048]`, label `tcgdex-ja+dex+number+total`, pinned (test 1).
- [ ] **Given** a single pinned candidate with no image, **when** evaluate runs, **then** it is high via `IDENTITY_PINNED`. With an image at distance > 20, it is low.
- [ ] **Given** the ja dex step is empty, **when** printedName matches, **then** only SV7a-011 is returned. When printedName is null, there is no unverified ja species-blind result.
- [ ] Every TCGdex result is exact-number filtered, and there are no `/tcgp/` cards on any TCGdex path.
- [ ] All pre-existing tests in the three test files pass, with only default stubs added. `searchByName` output is unchanged.
- [ ] Manual search and slot tap show JA printings with `(JA)`.
- [ ] `grep POKEMONTCG-IO-FALLBACK` lists every ptcg touchpoint in `CardSearchRepository.kt`.
- [ ] No Room entity/version change, no new dependency, no Gradle run by any stage. Skyler's `--rerun-tasks` run is green.

## Out of scope
- **Pre-filling saved Language** (P1 follow-up). It needs **no migration**: add `TcgCard.language`, then `updateDetails` / `SecondaryBinderEntry.language` in `confirmCard` and the assign flows. That is 4+ paths plus locked/remarks semantics, so it gets its own spec. The `(JA)` setName covers visibility for now.
- Personal Collection TCGdex-first/JA (cardId stability, see Open questions).
- Removing pokemontcg.io, Korean/Chinese, fr/de/other TCGdex langs, prices, `BackfillCardNamesUseCase`.
- ja `dex+number` without total. It adds wrong-set candidates and isn't needed for the reported cards.

## Dependencies
- TCGdex public API (no key), filter semantics as in the Evidence table. The ptcg fallback lives until 2027-03-01.
- Order: T1 → T2 → T3 → T5 → T4. T6 tests are written alongside each task.

## Open questions
- [ ] When ptcg is deleted, every Personal Collection `cardId` changes. Needs an id-remap of `personal_collection_entry` before 2027-03-01. Should PC also include JA? **Owner: Skyler**, before the removal spec.
- [ ] Null language goes ja-first (per brief). Flip to en-first if live logs show English cards returning `language=null`. Owner: live `query=` logs, non-blocking.

## Risks and mitigations
| Risk | L | I | Mitigation |
|---|---|---|---|
| Substring filters let the wrong card through | H | H | `eq:` on dex/total plus client exact-number filter (test 2) |
| JA data gaps: no dexId (SV7a-011), no images (M6) | H | M | printedName step 4. `IDENTITY_PINNED` only when there's no image. ptcg and broad steps follow |
| Gemini misreads printedName/language | M | M | Step 4 fails closed. Language only changes the order, since both languages are always tried |
| Single free, keyless API: rate limit/outage | L | M | Every call is wrapped and returns empty, falling through to ptcg. Manual search adds at most 2 calls. Nothing heavier |
| ja broad-dex returns many candidates, so many hasher downloads | M | L | Same as today's ptcg broad-dex, and only after the pinned steps miss |
| Flipping the merge primary orphans PC owned state | — | H | Avoided: `searchByName` is unchanged and test-locked |

## Smallest shippable increment
T1+T2+T3+T5: the scanner finds and accepts JA cards. T4 (manual-search JA) is independent and can follow.

## Friction Notes
- TCGdex list filters substring-match by default (`dexId=6` returned 2399). The brief's query checks passed only because of the specific numbers used. Probe with a short number (`localId=16`) before trusting any TCGdex filter.
- The brief assumed dexId pins Black Kyurem. ja SV7a-011 has no dexId, which forced `printedName`.
