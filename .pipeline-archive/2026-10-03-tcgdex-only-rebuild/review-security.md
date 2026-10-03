# Review: Lens 2, Security + Reliability + Data Safety (re-review pass 2)

**Verdict: ship.** Score **94/100**, up 14 from 80. No open P0 or P1. One new P3, which existed before this pass and was found this time.

## Scope
Fix pass 1 (`changes.md`, "Fix pass 1") on top of the pass-1 working tree. I re-read these files in full: `TcgcsvJapanSource.kt`, `PersonalCollectionRepository.kt`, `PersonalCollectionDao.kt`, `ResetCollectionViewModel.kt`, the reset code in `SettingsScreen.kt`/`ResetCollectionDialogs.kt`, `SyncCardImagesUseCase.kt`, and `JapanTcgcsvResolver.kt`. I also read the relevant parts of `CardSearchRepository.kt` and the diffs of `MainBinderDao`/`MainBinderViewModel`. I did not run Gradle (per the brief). The evidence is `test-results.md` plus `evidence/test-results.log`: `tests 505 skipped 0 failures 0 errors 0`.

## Prior finding status
| ID | Finding | Status | Evidence |
|---|---|---|---|
| P1-1 | Groups fetch has no failure backoff | **FIXED** | `if (nowMs() < groupsRetryAt) return@withLock cached`; `groupsRetryAt = nowMs() + if (blocked) GROUPS_BLOCKED_RETRY_MS else GROUPS_RETRY_MS`; success sets `groupsRetryAt = 0L`; constants `15L * 60 * 1000` and `6L * 60 * 60 * 1000`; `val blocked = e is HttpException && (e.code() == 401 \|\| e.code() == 403)` |
| P2-1 | PC partial refresh shrinks a section | **FIXED** | `if (complete) dao.replaceCacheForPokemon(...) else dao.upsertCache(...)`; `complete = CallStats.allSucceeded()` = `failed.get() == 0` |
| P3-1 | `runCatching` swallows cancellation | **FIXED** | `MainBinderViewModel`: `} catch (e: CancellationException) { throw e } catch (e: Exception) {` |
| P3-2 | 400-cap starvation | **FIXED (ceiling documented)** | `.sortedBy { (_, ref) -> ref.lang in LANGS_WITHOUT_TCGDEX_IMAGES }.take(limit)`, plus a `ponytail:` comment naming the remaining ceiling |
| P3-3 | Reset share sheet reopens on rotation | **FIXED** | `if (current is ResetUiState.Confirm2 && !current.shareSheetShown) { context.startActivity(...); resetVm.onShareSheetShown() }` |
| P3-4 | androidTest reset/fill SQL never executed | **OPEN** (unchanged, needs a device) | `test-results.md` still shows only "`compileDebugAndroidTestKotlin` BUILD SUCCESSFUL" |

## P1-1 verification: worst-case request volume
`groupsRetryAt` is a plain `var`, but it is only read and written inside `groupsLock.withLock`, and the Mutex gives the happens-before ordering. So it is safe without `@Volatile`.

- **First failure, empty cache, concurrent lookups.** The first caller holds `groupsLock`, makes the one call, fails, and sets the cooldown. Callers queued on the lock then see `nowMs() < groupsRetryAt` and return `cached` (null) with no network call. So exactly 1 groups request goes out per window per process, however many concurrent lookups there are. With a stale cache the stale copy is served. A hung host costs one timeout while the others wait on the lock, then nothing for 15 minutes.
- **Per scan/search:** at most 1 groups call (and only if outside the cooldown and the list is stale or cold) plus at most 3 product calls (`FetchBudget(JAPAN_IMAGE_FETCHES_PER_SEARCH)`). The fill now runs only on the final list (`stats.check(result).withJapanImages(stats)`).
- **PC `refreshAll`:** at most 1 groups call per window across all sections, plus at most 3 product calls per name search.
- **Auto sync:** at most 1 groups call per window, but product calls are unbounded when they fail. See P3-5.
- **Groups call not charged to `FetchBudget`:** I agree with this choice. The cooldown is process-wide and stricter than one call per search. Charging the budget would cut search to 2 product calls. The only gap is that the cooldown lives in memory, so it resets on process restart. That means at most one blocked request per app launch, which is acceptable.
- **Tests:** `a failed groups refresh is not retried by the next lookups until the cooldown ends` (14 min → `exactly = 1`, then 16 min → `exactly = 2`), the stale-served test, and `a 401 from the groups call backs off for hours, not minutes`. There is no concurrent-first-failure test, but the lock makes that path the same as the sequential one.

## P2-1 verification: data safety of `refreshPokemon`
- **complete=false never deletes anything.** It only calls `upsertCache` (`@Insert(onConflict = OnConflictStrategy.REPLACE)`, PK `cardId`), and there is no `DELETE` on that path. Owned entries live in `personal_collection_entry`, which neither path touches.
- **complete=true still removes cards that are gone.** `replaceCacheForPokemon` runs `deleteCacheForPokemon(pokemonKey)` and then `upsertCache(cards)` inside `@Transaction`.
- **Empty-result guard is intact.** `if (merged.isEmpty() && dao.cacheCountForPokemon(pokemonKey) > 0) return` runs before both writes.
- **Total outage:** the en search fails, `check` throws (`result.isEmpty() && failed.get() > 0 && ok.get() == 0`), `refreshPokemon` throws, nothing is written, and the VM records the section as failed.
- **Multi-name sections:** `if (!search.complete) complete = false` makes one failing name downgrade the whole section to upsert. That is correct.
- **Residual (acceptable):** a card TCGdex really removed during an outage stays cached until the next fully successful refresh. This is the right trade-off.

## Reset flow re-check
- **Wipe still needs export success and the exact word.** `Confirm2` is built only in `is BackupExportResult.Success -> ResetUiState.Confirm2(result.shareIntent)`. `confirmReset` still guards with `_state.value !is ResetUiState.Confirm2 || typed != RESET_CONFIRM_WORD`.
- **The new flag cannot open a path to `Wiping`.** `onShareSheetShown` is `val current = _state.value as? ResetUiState.Confirm2 ?: return; _state.value = current.copy(shareSheetShown = true)`, so it can only move Confirm2 to Confirm2.
- **Recreation:** `var typed by rememberSaveable { mutableStateOf("") }` survives the copy and the rotation. Process death brings the screen back as `Idle`, so no write happens.
- **Edge:** if `startActivity` throws, the flag is not set and the effect retries on the next state change. That is UX only.

## Other checks (no finding)
- **Schema and DAO wiring:** `const val SCHEMA_VERSION = 9`. The deleted `MainBinderDao` methods are `@Query`-only, and grep finds 0 references to `getRowsNeedingCardBackfill|backfillCardNameSet|BackfillCardNames`. `collectionResetDao()` is still provided in `PokedexDatabase`, `DatabaseModule:89` and `FakeDatabaseModule:67`.
- **Cancellation:** every new catch rethrows it (`TcgcsvJapanSource.loadGroups`, `MainBinderViewModel`). `PersonalCollectionViewModel` `runCatching { repository.refreshPokemon(...) }` existed before this change (that file's diff is one `Language.fromCardId` line), so it is out of scope.
- **Secrets:** the fix pass adds no logging. The only log in this area is `log("$setId-$localId -> ${hit.item.i} ...")`.

## New findings

### P3-5: A failed product-list fetch is not remembered, so the uncapped image sync repeats it once per Japanese card (confidence 7)
`TcgcsvJapanSource.findInGroup`/`fetchProducts`:
```kotlin
if (budget != null && !budget.tryTake()) return null
...
} catch (e: Exception) {
    null
}
```
plus `if (file == null) return@withLock null // cold and offline/over budget: nothing written`. The sync passes no budget: `source.imageUrl(TcgdexIds.setId(ref.id), TcgdexIds.localId(ref.id))`.

**How it fails:** the groups list is warm (fetched less than 24h ago) and the host starts rejecting product calls (401 block, 5xx, timeout). Each image-less ja card in a cold or stale group then sends its own failing `getJapanProducts` call: up to 400 per auto run and no limit on a manual run, 4 at a time. That is the same burst shape P1-1 was about, one call lower. It needs a warm groups list plus a product-only failure, and the orchestrator confirmed the current UA gets HTTP 200, so the trigger is narrow. Hence P3, not P1.

**Fix:** after a failure, stop calling the network for that group for a while. Either a `ConcurrentHashMap<Int, Long>` retry-at per group set in the catch, or reuse `groupsRetryAt` for any 401/403. Add one test: 5 lookups in one group with products failing must give `coVerify(exactly = 1) { api.getJapanProducts(any()) }`.

## Score
| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | 25/25 | Every data-safety requirement traces to code. The D2 "<=1+3" search budget now holds in failure cases too (groups at most 1 per window, products at most 3) |
| Correctness & bug-freedom | 24/25 | P2-1 is fixed in both branches with tests. The starvation ceiling is documented, not removed (-1) |
| Security & reliability | 18/20 | P1-1 is closed, with the cooldown verified for concurrent and cold-cache cases. P3-5: product failures under the uncapped sync are still retried per card (-2) |
| Maintainability & simplification | 15/15 | The fix is small and reuses `upsertCache` and the existing lock. KDoc on `FetchBudget` now explains the groups exemption, which resolves the -1 from the prior pass |
| Test evidence quality | 12/15 | 505/505 after `--rerun-tasks`, with targeted call-count, boundary, 401, partial-upsert and one-shot share tests. The reset/fill SQL has still never run on a device (P3-4), and the log is a one-line summary (-3) |
| **Total** | **94/100** | **+14 vs pass 1 (80).** ≥85 with no P0/P1, so **ship**. Before using Reset for real: run `connectedDebugAndroidTest` for `PokedexDatabaseTest` (P3-4). P3-5 can be fixed later |
