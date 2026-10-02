# Changes: TCGdex primary card source (scanner + manual search, incl. Japanese)

Implements `.pipeline/specs.md` in order T1 -> T2 -> T3 -> T5 -> T4, tests alongside. No Gradle run (harness loopback failure). Session 20/21 uncommitted edits left untouched.

## Files (all under `app/src/`)

| File | Change |
|---|---|
| `main/.../di/NetworkModule.kt` | TCGdex base URL `.../v2/en/` -> `.../v2/` (T1) |
| `main/.../data/remote/TcgdexApi.kt` | `searchCards` path `en/cards`; new `filterCards(lang, localId, officialTotal, dexId, name)` and `getCard(lang, id)` (T1). Nullable `@Query` params, no defaults |
| `main/.../data/remote/TcgdexCardDto.kt` | New `TcgdexCardDetailDto(id, name, dexId: List<Int>? = null)` |
| `main/.../domain/ParsedCardInfo.kt` | Appended `language: String? = null`, `printedName: String? = null` (T2) |
| `main/.../domain/GeminiCardScanner.kt` | `GeminiCardResult` + prompt JSON get `language`/`printedName`; mapped with `trim().lowercase().takeIf{isNotEmpty}` / `takeIf{isNotBlank}` |
| `main/.../repository/CardSearchRepository.kt` | T3 cascade + T4 (see below) |
| `main/.../domain/SmartThresholdUseCase.kt` | `ConfidencePath.IDENTITY_PINNED`; `evaluate(..., identityPinned = false)`; size==1 branch rescues only `distance == Int.MAX_VALUE` when pinned and hash card id matches (T5) |
| `main/.../ui/scanner/ScannerViewModel.kt` | passes `search.identityPinned`; logs `language=`/`printedName=` and `pinned=`. TEMP DIAGNOSTIC block untouched |
| `main/.../ui/manualsearch/ManualSearchViewModel.kt` | both `searchByName(trimmed)` -> `searchByNameWithJapanese(trimmed)`; promo branch unchanged |
| `main/.../ui/quickscan/QuickScanViewModel.kt` | `searchForSlot(name, dexNumber)` -> `searchByNameWithJapanese(name, dex.takeIf{>0})` (PokemonSlot.dexNumber is a non-null Int, 0 = unset); `search()` calls swapped |
| `test/.../repository/CardSearchRepositoryTest.kt` | default `filterCards` stub + `coVerify` import; 10 new tests (spec cases 1-8; case 3 split in two, case 7 split in two) |
| `test/.../domain/GeminiCardScannerTest.kt` | missing-fields test also asserts language/printedName null; new `scan lowercases language and keeps printedName` |
| `test/.../domain/SmartThresholdUseCaseTest.kt` | 4 new tests: pinned+MAX -> high IDENTITY_PINNED; pinned+30 -> low NONE; unpinned+MAX -> low; pinned with 2 cards -> NUMBER_MATCH path |
| `test/.../ui/QuickScanViewModelTest.kt` | Existing stubs/verifies for `searchByName` renamed to `searchByNameWithJapanese` (required: the ViewModel no longer calls `searchByName`, so the old stubs would never match and the routing tests would fail). Test names unchanged |

### CardSearchRepository details
- `ParsedSearchResult.identityPinned` (default false).
- `searchByParsedInfo` = `resolveParsed` + post-step that copies `pokemonNames` from `info.cardName` onto `tcgdex_ja_` cards (so `confirmCard` slot match works on English names).
- `resolveParsed` order: TCGdex dex+num+total (langs[0] then langs[1], pinned iff size==1) -> en name+num+total -> ja num+total verified by `printedName` (skipped when null) -> en num+total with lenient name match -> `ptcgSpecificCascade` (verbatim original queries/labels) -> broad-name (`mergeResults(tcgdex, ptcg)`, TCGdex first) -> broad-dex (`tcgdexFilter(langs[0], dex)` else ptcg) -> `ptcgBroadNumber` (name-verified, `broad-number`/`none-matched`).
- `langs` = `[ja, en]` when language is null or `ja`, else `[en, ja]`.
- `tcgdexFilter`: catches all exceptions -> empty; drops `/tcgp/`; sends localId with leading zeros stripped, `eq:` on total and dex; exact-filters by `numbersMatch`. `sendableTotal` returns null for non-numeric totals, so steps that need a total are skipped rather than loosened.
- `toDomain(lang = "en")`: en unchanged (`tcgdex_<id>`, setName = set code); ja `tcgdex_ja_<id>`, setName `"<SET> (JA)"`.
- New `searchByNameWithJapanese(name, dexNumber = null)` + private `resolveDex` (first en `tcgdex_` card name-matching, `getCard("en", ...)`, errors -> null). Returns `(en + ja).distinctBy{id}`.
- `searchByDexNumber`: en + ja TCGdex dex (concurrent) merged ahead of ptcg `nationalPokedexNumbers:`.
- `searchByName` body unchanged (ptcg-first), so `PersonalCollectionRepository.refreshPokemon` cardIds are stable and the `xyevo-25` test holds.
- Constants `TCGDEX_EN_ID_PREFIX`, `TCGDEX_JA_ID_PREFIX`; helper `pokemonNamesOf` (same " & " / substringBefore(" ") rule, now used by the TCGdex mapper and ja seeding).
- 14 `// POKEMONTCG-IO-FALLBACK: remove before 2027-03-01` tags: `api` ctor param, every `safeSearch(...)` call in the new code, `ptcgSpecificCascade`, `ptcgBroadNumber`, `searchByNameAndNumber`, `searchByNumber`, `searchByNumberAndTotal`, `searchPromosByName`, `safeSearch`, `TcgCardDto.toDomain`, `searchByName` primary. Note `searchByNameAndNumber` and `searchByNumberAndTotal` have no callers (pre-existing; left in place per spec).

## Call-site audit (grep, evidence in sidecar)
- `TcgdexApi` used only by `CardSearchRepository` (+ DI module and its test mock). New interface methods have no default args, so the mockk mock needs the `filterCards` default stub (added).
- `ParsedCardInfo(` constructed in `GeminiCardScanner` (named args), `CardSearchRepositoryTest`, `ScannerViewModelTest` (all named, new fields defaulted).
- `SmartThresholdUseCase.evaluate` callers: `ScannerViewModel` (updated) and its tests (new param defaulted).
- `ConfidencePath` is only referenced in the use case and its test (no exhaustive `when`).
- `ParsedSearchResult(` constructed only in `CardSearchRepository` (new param defaulted).
- Remaining `searchByName(` callers: `PersonalCollectionRepository` (intentional), internal none.
- Room: no entity/version change. Manifest: no new dependency (New-dependency check N/A).

## Simplification / review pass
Done inline (no subagents available). Findings and fixes:
- Replaced `::sendableTotal` / `::pokemonNamesOf` / `Char::isDigit` method references with lambdas (avoid bound-reference resolution risk in unverified compile).
- Dropped an unused `info` param from `ptcgSpecificCascade`.
- Reuse: existing `namesMatch`, `numbersMatch`, `mergeResults`, `matchingParsedName`, `safeSearch` reused; no new utilities duplicated.
- Behavior-preservation: ptcg cascade queries/labels are the original code moved verbatim; `searchByName` body identical.
- Known behavioral notes (per spec, not changed): broad-dex with `ja` first can return many candidates (hasher downloads); `searchByNameWithJapanese` adds at most 2 extra TCGdex calls (detail + ja dex).

## Verification (7 checks) - raw output in `.pipeline/evidence/changes-verify.log`

| # | Check | Result |
|---|---|---|
| 1 | Typecheck | not run - Gradle cannot run in this harness. Desk-checked: imports (`coVerify`, `data.remote.*` wildcard covers `TcgdexCardDetailDto`), nullability of `filterCards` params vs mockk `any()`, smart-casts on local `val`s inside `async`, `return@coroutineScope` labels (function is expression-body `coroutineScope {}`), every call site of changed signatures via grep |
| 2 | Lint | N/A - no lint task configured/runnable |
| 3 | Scoped tests | not run - Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks` |
| 4 | Production build | not run - Skyler runs `assembleDebug` |
| 5 | Dev server boot | N/A - Android app, no server |
| 6 | No stray debug / scratch files | PASS - grep of added lines for println/console.log/debugger/TODO/FIXME: none. Only Log.i lines are the spec-mandated extensions of the existing `ScannerMatch` logs. I created no temp files in the repo (scratch block lived in the session scratchpad dir) |
| 7 | git status only intended files | PASS - new changes limited to the 14 files listed above; all other entries (CLAUDE.md, app/CLAUDE.md, gaps/handoff/overview, PerceptualHasher*, ScannerScreen*, ArtHash*, Scanner* tests, deleted FocalLengthsKeyTestFixture, `.pipeline-archive/`, `scripts/scan_capture.log`) are the pre-existing session 20/21 edits |

## Risks for Skyler's test run (things to look at first if it fails)
- `CardSearchRepositoryTest`: mockk matching of `null` args in `filterCards(...)` stubs, and the Japanese strings in the test file (source must be read as UTF-8; existing Gemini test already contains non-ASCII).
- `GeminiCardScannerTest` new test: inner JSON escaping copied from the existing missing-fields test style.
- `QuickScanViewModelTest`: if the repo mock is strict, the `searchForSlot` init-path stubs use `searchStreaming(any(), any())` so the lambda is never invoked there; no change needed.

## Fix pass 1 (review-verdict.md, 83/100)

| Finding | Status | Change |
|---|---|---|
| F1 (P2) substring name picks wrong species dex | fixed | `CardSearchRepository`: new private `List<TcgCard>.bestNameMatch(name)`: exact `normalizeName` match first, `namesMatch` substring only as fallback. Used by `resolveDex`, and also by the `names` seeding line in `searchByNameWithJapanese`. That line had the same root cause: "Mew" ja printings would have been seeded with `["Mewtwo"]`. `normalizeName` was extracted from `namesMatch`, and `namesMatch` behaves the same as before. |
| F2 (P2) pin accepts a failed download | fixed | `SmartThresholdUseCase`: `pinnedNoImage` now also requires `single.imageUrl.isBlank()`. |
| F3 (P3) broad-dex language | deferred (per orchestrator) | no change |
| F4 (P3) swallowed cancellation | fixed | `catch (e: CancellationException) { throw e }` added before `catch (e: Exception)` in `tcgdexFilter`, `resolveDex` and `tcgdexSearchByName`. This is the same style as `ManualSearchViewModel` and uses the `kotlinx.coroutines.CancellationException` import. `safeSearch` is untouched. |

Tests:
- `SmartThresholdUseCaseTest`: the existing pinned-accept test now uses `.copy(imageUrl = "")`. Without it, the test would have failed under F2, because the `card()` helper sets an image. New test: pinned + image present + `Int.MAX_VALUE` gives NONE.
- `CardSearchRepositoryTest` (+3 tests):
  - Mew vs Mewtwo. Mewtwo is listed first, and the test asserts the ja `eq:151` printing with `pokemonNames == ["Mew"]`. It fails on the old code.
  - Null language goes ja-first. Checked with `coVerifyOrder` on ja then en `filterCards`.
  - `filterCards` throws `IOException`. The ptcg `dex+number+total` still resolves, and the result is not pinned.
  - New imports: `io.mockk.coVerifyOrder`, `java.io.IOException`.

Desk-check (Gradle not run):
- Every new symbol is referenced only inside `CardSearchRepository`. The grep is in the sidecar.
- `TcgdexCardDetailDto(id, name, dexId)` matches the existing positional test use.
- `ParsedCardInfo.language` defaults to null.
- mockk `coVerifyOrder` allows other calls in between, so the extra steps don't break it.
- No IDENTITY_PINNED test elsewhere relies on an imaged pinned card.

7 checks, fix pass:

| # | Check | Result |
|---|---|---|
| 1 | Typecheck | not run (Gradle is not runnable here). Desk-checked as above. |
| 2 | Lint | N/A |
| 3 | Scoped tests | not run. Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks` |
| 4 | Production build | not run. Skyler runs `assembleDebug` |
| 5 | Server boot | N/A (Android app) |
| 6 | Stray debug | PASS: none in the 4 touched files (sidecar) |
| 7 | git status | PASS: no new paths. The fix pass only touched 4 files that were already modified. |

## Friction Notes
- Bash tool rejected long heredocs containing quotes/Japanese text ("unexpected EOF"); worked around with the Write/Edit tools.
- Spec said `searchByNumber` was tagged as if fallback-only; it is still used by ManualSearch/QuickScan promo branches, so it is tagged but not treated as dead.
