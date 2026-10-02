# Changes: (A) trust metadata-pinned matches, (B) TCGdex ko / zh-tw / zh-cn / th / id

2026-10-01 · Coder · spec `.pipeline/specs.md` implemented T1-T8 in stated order. No Gradle run (harness loopback failure, per brief). No new dependency (Item P: N/A), no Room change.

## Files

| File | Change |
|---|---|
| `domain/SmartThresholdUseCase.kt` | T1. `ConfidencePath.IDENTITY_PINNED_NAME`; `PINNED_HASH_CONTRADICTION_DISTANCE = 45` with KDoc (Castform 34 / Kyurem 28 / wrong 24-30 evidence, hash is a gross-failure net only, MAX_VALUE + non-blank image stays rejected, widening is one line). `evaluate(..., nameVerified = false)`. Single-candidate: hash<=20 -> `SINGLE_CANDIDATE_HASH` first; else `pinned = identityPinned \|\| nameVerified`, accept if hashMatch is for this card and (MAX_VALUE -> blank image only, else distance <= 45); path `IDENTITY_PINNED` if identityPinned else `IDENTITY_PINNED_NAME`. Multi-candidate untouched. |
| `repository/CardSearchRepository.kt` | T2/T3/T3b/T4/T6. `ParsedSearchResult.nameVerified`. Top-level `TCGDEX_ASIAN_LANGS`, `MAX_TCGDEX_LANGS`, `tcgdexLangsFor()`, private `isNonEnglishTcgdexId()`. `resolveParsed`: `langs = tcgdexLangsFor(info.language)`; printedName step loops non-en langs, label `tcgdex-$lang+number+total`, `nameVerified = results.size == 1`; `broad-dex` uses new `firstNonEmptyByDex(langs, dex)` (<=3 calls). `toDomain(lang)`: ids `tcgdex_<lang>_<id>`, setName `<SET> (<LANG>)` (ja byte-identical). `TCGDEX_JA_ID_PREFIX` removed (grep: 0 refs left). `searchByNameWithJapanese` / `searchByDexNumber` use new `tcgdexAllLangsByDex` (6 parallel, ordered). `normalizeName` keeps Mn/Mc marks. Name `searchByNameWithJapanese` kept; KDoc updated. |
| `ui/scanner/ScannerViewModel.kt` | T5. Passes `search.nameVerified` to `evaluate`; `search candidates` log adds `nameVerified=`. TEMP DIAGNOSTIC block untouched. No API key in any log line. |
| `domain/CardScanShared.kt` | T7. Prompt `language` wording only (adds ko/th/id examples and the zh-CN / zh-TW instruction). JSON field set unchanged. |
| `test/.../SmartThresholdUseCaseTest.kt` | T8. Flipped test rewritten (distance 34 -> high `IDENTITY_PINNED`). Added: 45 accept / 46 reject, 20 vs 21 path split, nameVerified 28 / 46 / MAX_VALUE+image / MAX_VALUE+blank, both flags, unpinned 34, pinned with null hashMatch, nameVerified multi-candidate. Existing MAX_VALUE+image rejected test untouched. |
| `test/.../CardSearchRepositoryTest.kt` | T8 items 1-10 (see below). |
| `test/.../GeminiCardScannerTest.kt` | `"language":"zh-CN"` parses to `zh-cn`. |

New CardSearchRepositoryTest tests: `tcgdexLangsFor` table (all 14 spec inputs + size<=MAX), zh dex step order (zh-tw before zh-cn via `coVerifyOrder`), zh-cn-first with no zh-tw call, ko hit (`tcgdex_ko_SV5K-004`, `SV5K (KO)`, nameVerified), th and id hits, printedName mismatch + two matches -> not verified, Thai mark test (`กี` vs `กา`), zh call budget, null language never queries ko/zh/th/id, manual search order + each lang exactly once, one-lang failure keeps others, `searchByDexNumber` all six, pokemonNames seeding th yes / en no. Existing Kyurem test also asserts `nameVerified`; M6 test asserts `!nameVerified`.

## Spec deviation to flag (budget claim)

Spec says any one scan makes <= 7 TCGdex calls (zh) and 5 (ja/en/ko/th/id). Those counts omit `broad-dex`, which the spec itself changes to walk up to `MAX_TCGDEX_LANGS` (3) languages (today it is 1 call). Real worst case on a total miss with a dex: zh = 7 number+total-bearing calls + 3 broad-dex = 10; ja/en/ko/th/id = 5 + 2 = 7 (today 6). Implemented as the spec's T3 text says; the budget test asserts the two groups separately (<=7 with number, <=3 broad-dex, none for ko/th/id/ja on zh). Skyler: if 10 is too many, cap `broad-dex` at the first language only (one-line change in `firstNonEmptyByDex`).

## Simplification / review pass (done inline, no subagents per global rule)

- Reuse: `tcgdexFilter`, `matchingParsedName`, `pokemonNamesOf` reused; only new helpers are the three spec-named ones plus `firstNonEmptyByDex` (replaces an inline `langs[0]` call; needed to express the loop, used once).
- `MAX_TCGDEX_LANGS` is referenced only by tests (spec-mandated constant; list sizes are <=3 by construction).
- Desk-check compile/behaviour:
  - Every `evaluate(` call site: only `ScannerViewModel` (updated); new param defaulted so tests/others compile. Every `ParsedSearchResult` construction: only `CardSearchRepository` (named args); tests only read it. `ConfidencePath` has no exhaustive `when` anywhere (grep).
  - `HashMatchResult.distance` is `Int`; smart-cast of `hashMatch` inside the `&&` chain holds (local param, non-null checked first). `Character.getType(Char)` returns Int, `NON_SPACING_MARK.toInt()` ok. Top-level `private` helper is visible to the class in the same file; `internal` top-level symbols are visible to the unit-test source set.
  - Retrofit: `lang` is `@Path` so `zh-tw`/`zh-cn` are legal segments; no annotation/DTO change. No Moshi change.
  - mockk matcher mixes (`any()`, literal, `isNull()`, `match{}`) use only forms mockk allows within one call.
  - Existing tests re-read: ja dex, Kyurem, M6 label, `searchByNameWithJapanese` (ids/order unchanged: `tcgdex_ja_M6-048`), Mew/Mewtwo, null-language order `ja` then `en`, `broad-dex` tests (default empty stubs) all still satisfy the new code paths.
- Not done (out of scope per spec): `scripts/claude_scan_bakeoff.py:48` still holds the OLD prompt wording (Skyler's script, left as instructed). Palafin CSV9C (zh-cn) remains unmatchable (TCGdex data gap); this change does not fix it.

## 7-check verification (raw output: `.pipeline/evidence/changes-verify.log`)

| # | Check | Result |
|---|---|---|
| 1 | Typecheck | Not run - Gradle unavailable. Desk-checked as above. Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks`. |
| 2 | Lint | N/A - no lint task run (Gradle unavailable). |
| 3 | Scoped tests | Not run - Skyler runs `testDebugUnitTest`. |
| 4 | Production build | Not run - Skyler runs it. |
| 5 | Dev/start server boot | N/A - Android app, no server. |
| 6 | No stray debug output | PASS. Grep of added diff lines for `println(`, `console.log`, `debugger`, `TODO()`: no hits (log exit 1). `TCGDEX_JA_ID_PREFIX` leftover grep: no hits. New log lines contain no key/URL. |
| 7 | git status only intended files | PASS for this stage: this stage touched only the 7 files in the table (`SmartThresholdUseCase.kt`, `CardSearchRepository.kt`, `ScannerViewModel.kt`, `CardScanShared.kt`, and 3 test files) plus `.pipeline/changes.md` and `.pipeline/evidence/`. Tree also holds the expected uncommitted edits from earlier sessions (see log); nothing reverted, no scratch files created. |

## Friction Notes
- Spec's call-budget numbers (<=7 / <=5) omit `broad-dex`; real totals are 10 / 7 once it walks 3 languages. Planner should count every cascade step when stating a budget.
- Git warns "LF will be replaced by CRLF" on many files; noise only, not acted on.
