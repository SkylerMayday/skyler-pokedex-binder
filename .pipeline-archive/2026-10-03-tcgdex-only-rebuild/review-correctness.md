# Code Review: TCGdex-only + reset + image sync + Discord toggle + TCGCSV Japan, re-review pass 2

**Reviewer:** Claude (Lens 1 of 3: correctness + spec trace)
**Date:** 2026-10-03
**Scope:** uncommitted working tree vs `HEAD` 8482561 (45 tracked files, +1045/-741, plus untracked sources and tests), after "Fix pass 1" in `.pipeline/changes.md`. Gradle was not run by me. Test evidence is Skyler's run (`evidence/test-results.log`: "tests 505 skipped 0 failures 0 errors 0").

---

## Summary

Every finding from my pass 1 is fixed, and each new test fails on the old code. I found no regressions in the paths the brief named: the `NameSearchResult.complete` / upsert path, `withJapanImages` placement, `TcgdexIds` consolidation, the groups cooldown, the `syncIfDue` ordering, and the removed fields and queries. One new P2 is a sibling of the old P2-1 that I missed in pass 1: **a failed product-list fetch is retried on every lookup, and the sync resolver passes no budget**, so nothing caps it.

**Verdict: ship.** The score is 89, with no P0/P1. If a pass 3 runs anyway, fix P2-A (it's a few lines). Otherwise log it in `gaps.md`.

---

## Prior finding status

| Prior | Status | Evidence (quoted) | Would the new test fail on the old code? |
|---|---|---|---|
| P2-1 groups retried every lookup | **FIXED** | `if (nowMs() < groupsRetryAt) return@withLock cached`; `groupsRetryAt = nowMs() + if (blocked) GROUPS_BLOCKED_RETRY_MS else GROUPS_RETRY_MS`; success sets `groupsRetryAt = 0L` | Yes. `repeat(5) {...}; coVerify(exactly = 1) { api.getJapanGroups() }`: the old code made 5 calls. The stale-copy test expects `exactly = 2` (the old code made 6), and the 401 test makes 3 calls on the old code where it expects 1 |
| P3-1 Japan fill before filtering | **FIXED** | `toCards(lang)` no longer fills. `withJapanImages(stats)` runs on the final lists in `searchByParsedInfo` (L116), `searchByNameAllLanguages` (L216) and `searchByDexNumber` (L280) | Yes. The test `Japan image budget is spent only on cards that survive...` expects `coVerify(exactly = 1)`, and the old `toCards` filled all 3 blank ja briefs |
| P3-2 PC partial refresh | **FIXED** | `if (complete) dao.replaceCacheForPokemon(...) else dao.upsertCache(...)`; `complete = stats.allSucceeded()` | Yes in behaviour: the old code always called `replaceCacheForPokemon`, and the test asserts `exactly = 0` |
| P3-3 dead `releaseDate` | **FIXED** | `TcgdexSetCatalog` now holds only `englishNames()`. Grep for `getSet(` / `TcgdexSetDetailDto` returns 0 hits | n/a |
| P3-4 missing tests | **FIXED** (one weak test, see P3-C) | concurrent auto `assertNull(auto)` with a `CompletableDeferred` gate; groups failure count; PC second refresh | concurrent auto and groups: yes. PC "owned survives": no (P3-C) |

## Regression hunt (the brief's list)

| Area | Result |
|---|---|
| `searchByNameAllLanguages` / `complete` | Correct. A dex-lookup failure gives `stats.failed()` and an incomplete en-only list, which is upserted, so ja rows survive. An all-failed empty result still throws via `stats.check`, so there's no write. `withJapanImages` never touches `ok/failed`, so a TCGCSV miss can't make a refresh look partial |
| upsert vs replace | No permanent stale row as long as at least one refresh is clean: `replaceCacheForPokemon` is `deleteCacheForPokemon(pokemonKey); upsertCache(cards)`. Owned state lives in `personal_collection_entry`, and neither path writes it. One caveat is under P3-B |
| `withJapanImages` coverage | The scanner (`ScannerViewModel:80` calls `searchByParsedInfo`), manual search and Quick Scan (`searchByNameWithJapanese` calls `searchByNameAllLanguages`, `QuickScanViewModel:85` calls `searchByDexNumber`) all still fill. `searchByNumber` and `searchPromosByName` are en-only, so they had nothing to fill before either. No path lost the fill. `searchStreaming` wraps these same lambdas |
| `TcgdexIds` | The id strings are byte-identical. Old: `(if (isEn) TCGDEX_EN_ID_PREFIX else "${TCGDEX_EN_ID_PREFIX}${lang}_") + id`. New: `if (lang == "en") "$PREFIX$id" else "$PREFIX${lang}_$id"`. `parse` returns null for `tcgcsv_` and legacy ids, so `fromCardId` gives EN. `LanguageTest` iterates `TCGDEX_ASIAN_LANGS` |
| Cooldown | Clock-injected (`nowMs()` in every comparison). `groupsRetryAt` is only read and written inside `groupsLock.withLock`, and the Mutex gives visibility. A first-ever failure with no cache returns null and starts the cooldown (tested). One success clears it |
| `syncIfDue` order | `.sortedBy { ref.lang in LANGS_WITHOUT_TCGDEX_IMAGES }` is a stable false-first sort applied before `.take(limit)`, so it's correct. The test puts 400 ko/zh-cn ids ahead of ja+en |
| Removed `hp/artist/setReleaseDate` | `TcgCard` has no `@JsonClass`/`@Serializable`/`@Parcelize`. The two VMs that matched my serializer grep use `SavedStateHandle` only for `slotId`/`pokemonName`/flags. No reader is left in main |
| Removed `MainBinderDao` backfill | Only `@Query` methods were removed, so there's no schema impact. Their only consumer (`BackfillCardNamesUseCase`) is gone |
| Publish sort removal | Safe. `personalCache = personalCollectionRepository.getAllCache()` (DAO `ORDER BY releaseDate DESC, setName, cardId`), and `groupBy` keeps order |
| Share sheet / CancellationException | `if (current is ResetUiState.Confirm2 && !current.shareSheetShown)` then `onShareSheetShown()`. `catch (e: CancellationException) { throw e }` comes before `catch (e: Exception)` |

---

## Important (P2)

### P2-A. A failed product-list fetch is retried on every lookup, and sync passes no budget (confidence 8, new; a sibling of the old P2-1 that I missed in pass 1)
- **File:** `data/remote/TcgcsvJapanSource.kt` L139-147, L159; `domain/JapanTcgcsvResolver.kt` L83
- **Quoted:** `if (file == null || !isFresh(group, file)) { attempted = true; val downloaded = fetchProducts(group, budget)` … `if (file == null) return@withLock null`. Failure leaves no record (`catch (e: Exception) { null }`). The budget only applies when one is passed: `if (budget != null && !budget.tryTake()) return null`. The resolver passes none: `source.imageUrl(TcgdexIds.setId(ref.id), TcgdexIds.localId(ref.id))`.
- **Scenario:** `groups.json` is warm (or stale and served under the cooldown). TCGdex is reachable and has no image for a ja card. TCGCSV product calls return 401, 5xx or hang. Every blank ja id in a cold group then makes its own `getJapanProducts` call, serialized on that group's `Mutex`. Each call can take up to `.readTimeout(30, TimeUnit.SECONDS)`. One auto sync (up to 400 ids) can send dozens of 401s to a host the spec says blocks after bursts, and it holds the sync mutex for minutes. The spec budget is `sync <=1 + distinct ja sets`. Search paths are safe: `FetchBudget(3)` caps them.
- **Fix (small):** record a per-group failure cooldown, or one host-wide `blockedUntil` that a 401/403 from either call sets, and check it in `findInGroup` before `fetchProducts`. Add one test: `getJapanProducts` throws, and 5 unbudgeted lookups in one group give `coVerify(exactly = 1)`.

## Minor (P3)

- **P3-A (confidence 7, pre-existing, not in the fix pass)** `TcgdexSetCatalog.englishNames()`: `catch (e: Exception) { emptyMap() }` caches nothing on failure. Every en `toCards` call (`val setNames = if (lang == "en") setCatalog.englishNames() else emptyMap()`) then retries `getSets("en")` under `namesLock`. The cascade has up to 6 en steps, so that's up to 6 retries per scan. It's bounded and only fires when `getSets` fails while `filterCards` succeeds, which is rare. Same cooldown idea as P2-A.
- **P3-B (confidence 5, verify)** `if (complete) dao.replaceCacheForPokemon(...) else dao.upsertCache(...)`: if one language call fails on *every* refresh, a section is never pruned. For example, TCGdex might return an error rather than `[]` for an empty `th`/`id` dex filter. Cards TCGdex has dropped would stay forever, and an owned card's flag still displays. Owned data is safe. Check once on the phone: `curl https://api.tcgdex.net/v2/th/cards?dexId=eq:687`. A 200 with `[]` means this is a non-issue.
- **P3-C (confidence 9, test quality)** `a second identical refresh writes the same rows and never touches owned entries` asserts `coVerify(exactly = 0) { dao.upsertEntry(any()) }` against a mocked DAO. `refreshPokemon` never calls entry methods, in the old code or the new, so the test can't fail. The spec's "owned flags identical after a second refresh" is really proved by the design (separate table), not by this test. Leave it, or move it to the androidTest DAO test.

---

## What looks good
- The cooldown is in the single choke point (`loadGroups`) with injected time, and the tests hit both boundaries (14 vs 16 min, 1 h vs 7 h).
- `NameSearchResult` keeps `searchByNameWithJapanese`'s signature, so none of the VM mocks churned.
- The coder caught that the aggregate's M4 wording was inverted against the spec and the code, and implemented the lens text instead (`changes.md` #7).

---

## Score

| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | All T1-T6 and T8b-d trace to code, and the dead release-date path is gone. -1: the sync budget `<=1 + distinct ja sets` is broken on the product-failure path (P2-A) |
| Correctness & bug-freedom | 21/25 | No P0/P1, no regressions from the fix pass. -3 for P2-A, -1 for P3-A/P3-B |
| Security & reliability | 17/20 | No security surface. -3: P2-A sends a retry burst plus serialized 30 s timeouts to a host with a known block-after-burst history, during sync |
| Maintainability & simplification | 14/15 | `TcgdexIds` is now the single id authority and the dead code is removed. -1: two different "failure not remembered" patterns remain (product fetch, set catalog) beside the new groups cooldown |
| Test evidence quality | 13/15 | 505/0 with `--rerun-tasks`, and every new test checks a discriminating call count. -1: P3-C can't fail. -1: the reset/fill SQL is compile-verified only (`compileDebugAndroidTestKotlin`, androidTest not executed), and the evidence is a one-line summary |
| **Total** | **89/100** | **Up 6 from 83** (pass 1). P2-1 and all four P3s are resolved. P2-A is newly found, not a regression |

**Verdict: ship** (89 ≥ 85, no P0/blocking). Recommended: fix P2-A if a pass 3 runs for another lens, otherwise log it in `gaps.md`.
