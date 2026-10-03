# Changes

## Pass 1 (T1-T3)

Spec: `.pipeline/specs.md`. No earlier partial edits were found (only pre-existing ScannerViewModel/CLAUDE.md changes, left untouched). Gradle was not run (harness loopback failure); everything below is desk-checked. Raw output: `.pipeline/evidence/changes-verify.log`.

### T1 - Discord toggle
| File | Change |
|---|---|
| `repository/PublishSettingsRepository.kt` | `PublishConfig.postToDiscord=false`; `post_to_discord` boolean key in the existing publish DataStore, read `?: false`; `setPostToDiscord` |
| `publish/PublishRepository.kt` | gate `config.postToDiscord && webhook.isNotBlank()`; separate log line for "toggle off" vs "no webhook". Changelog PUT untouched (orchestrator default) |
| `ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt` | `setPostToDiscord`; "Post updates to Discord" switch above the webhook field (description states existing installs stop posting until enabled) |
| tests | `PublishRepositoryTest` (webhook cases now `postToDiscord=true`; new off+webhook case: binder+changelog uploaded, `discordApi` never called), `PublishSettingsRepositoryTest` (default false, round-trip), new `ui/settings/SettingsViewModelTest` |

### T2 - TCGdex plumbing (pure additions)
| File | Change |
|---|---|
| `data/remote/TcgdexIds.kt` (new) | `TcgdexRef`, `TcgdexIds.parse/setId/imageUrl`; `TCGDEX_ASIAN_LANGS` moved here from `CardSearchRepository.kt` (same `internal` visibility, new package `data.remote`) |
| `data/remote/TcgdexSetCatalog.kt` (new, `@Singleton`) | `englishNames()` (one memoised `GET en/sets`, failure -> empty, retried next call); `englishName`/`releaseDate` later removed in fix pass 1 |
| `data/remote/TcgdexApi.kt` | `searchPromos(name, rarity="Promo")`, `cardsInSet(lang, setId, localId)`, `getSets(lang)`, `getSet(lang,id)`; `filterCards` unchanged |
| `data/remote/TcgdexCardDto.kt` | `TcgdexCardDetailDto.image`, `TcgdexSetBriefDto`, `TcgdexSetDetailDto` (reflection Moshi like the existing TCGdex DTOs) |
| `data/model/Language.kt` | `TH`, `ID`, `fromCardId` |
| tests | new `TcgdexIdsTest`, `TcgdexSetCatalogTest`, `LanguageTest` |

### T3 - pokemontcg.io removal
- Deleted: `PokemonTcgApi.kt`, `TcgCardDto.kt`, `BackfillCardNamesUseCase.kt` + test. `NetworkModule`: removed the unqualified `Retrofit` provider and `providePokemonTcgApi` (grep: no other injection point of an unqualified `Retrofit`; `Retrofit` import still used by the qualified providers).
- `CardSearchRepository` ctor is now `(tcgdexApi, tcgcsvApi, setCatalog)`. Removed `api`, `ptcgSpecificCascade`, `ptcgBroadNumber`, `safeSearch`, `nameQuery`, ptcg `toDomain`, `POCKET_EXCLUSION`, `searchByName`, `searchByNameAndNumber`, `searchByNumberAndTotal`, `mergeResults`.
- `resolveParsed` order per spec: dex+number+total -> en name+number+total -> lang number+total (printedName) -> en number+total (name-verified) -> `tcgdex-en+name+number` -> `tcgdex-<lang>+dex+number` -> `broad-name` -> `broad-dex` -> `broad-number` (only if a name exists, name-verified) -> `none-matched`. Flags untouched; the two ported steps set no flags.
- `searchByNumber` = exact-filtered `filterCards("en", Q.uppercase())`, else `^(SVP|MEP)(\d+)$` -> `cardsInSet("en","eq:svp"|"eq:mep",digits)`. `searchPromosByName` -> `searchPromos`. `searchByDexNumber` drops ptcg.
- Outage: `TcgdexUnavailableException` + `internal CallStats` (atomic ok/failed). Every public search owns one; empty result with `failed>0 && ok==0` throws. `searchStreaming`: fast source wrapped in `Result` inside its `async` (a throwing async child would otherwise cancel the TCGCSV sibling); on failure it still awaits TCGCSV, emits a lone `Complete` if non-empty, else rethrows.
- English results now get real set names via `setCatalog.englishNames()` (fallback: bare code); image URL via `TcgdexIds.imageUrl`.
- `MainBinderViewModel`: backfill dep + call removed (ctor now `(binderRepo, settingsRepo, context)`); `MainBinderViewModelTest` updated at all 3 construction sites.
- **Forced minimal change in `PersonalCollectionRepository.refreshPokemon`**: `searchByName(name)` -> `searchByNameWithJapanese(name)` (+ KDoc wording). Nothing else. Consequence for pass 2: `releaseDate` is now `card.setReleaseDate ?: ""` and TCGdex cards carry no date, so PC rows sort unordered until T4 wires `setCatalog.releaseDate`; `setOwned` language bug and the empty-cache guard are still T4.
- `QuickScanViewModel` exception handling confirmed by reading: `runSearch` catches `Exception` -> `QuickScanState.Error(e.message)`; `ManualSearchViewModel` and `ScannerViewModel` do the same, so the unreachable message surfaces everywhere.
- Test data/comments: `img.pokemontcg.io` fixture strings in `BinderRepositoryTest`/`UnownBinderRepositoryTest` -> `img.example.test`; comment mentions updated so the acceptance grep is clean.

**Tests:** `CardSearchRepositoryTest` ptcg cascade tests ported to TCGdex stubs with the new labels; outage/Unavailable, `searchByNumber`/promo, `searchStreaming` rescue cases added. Deletion grep (sidecar): 0 hits for every removed ptcg symbol over main/test/androidTest. Review pass: `CallStats.check(list)` simplification, plain `if` in `searchByNumber`. Verification: typecheck/tests/build not run (Gradle), lint N/A, server N/A, debug + git status pass. Skyler's later `testDebugUnitTest` run: 490/0.

## Friction Notes
- Bash heredocs containing quoted Kotlin/regex text intermittently failed to parse in this harness ("unexpected EOF"); writing the script to a file with the Write tool and running it was reliable.
- The spec's `resolveParsed` order puts name-less `number+total` ahead of `dex+number`, which differs from the old ptcg order; ported tests were re-targeted to the spec order rather than the old one.

## Pass 2 (T4-T6)

No earlier partial T4-T6 edits existed (grep of `CollectionResetDao|BlankImageResolver|syncIfDue|blankImageCardIds` was empty at start). Pass 1 untouched. Gradle not run (harness loopback failure); everything desk-checked. Raw output: `.pipeline/evidence/changes-verify.log`.

### T4 - Personal Collection on TCGdex
Skyler's decision applied: **no release-date ordering**, so no `setCatalog` dependency in `PersonalCollectionRepository` (ctor unchanged, no test-site churn).
| File | Change |
|---|---|
| `repository/PersonalCollectionRepository.kt` | `setOwned` keeps an existing entry's language, new rows use `Language.fromCardId` (fixes the REPLACE-resets-to-EN bug). `refreshPokemon`: `releaseDate = ""`; **empty guard**: empty merged result + section already cached -> keep cache. De-dupe by card id already via `LinkedHashMap.putIfAbsent` across names and languages (ids are language-prefixed, so a card appears once per printing) |
| `data/local/PersonalCollectionDao.kt` | order `releaseDate DESC, setName, cardId` (deterministic; the 3 cache queries); `cacheCountForPokemon(key)` for the guard |
| `ui/personalcollection/PersonalCollectionViewModel.kt`, `publish/PublishRepository.kt` | `?: "EN"` -> `?: Language.fromCardId(row.cardId).name` (+ import) |
| tests | `PersonalCollectionRepositoryTest` +4 (guard keeps cache; guard writes when no cache; `setOwned` keeps DE; new ja card -> JA), `PersonalCollectionViewModelTest` +1 (language from id) |

Outage case: `searchByNameWithJapanese` throws `TcgdexUnavailableException` (pass 1) before the guard, so the cache stays and `refreshAll`'s per-section `runCatching` reports it.

### T5 - Reset collection data
| File | Change |
|---|---|
| `data/local/CollectionResetDao.kt` (new) | `@Dao`: 5 count queries, `@Transaction summary(): ResetSummary`, 6 clearing `@Query`s, `@Transaction resetAll()`. Main/unown: assignment cols NULL, `language='EN'`, `remarks` NULL, `isLocked=0` (rows stay); `connecting_art_slot`: card cols NULL, `owned=0`, `language='EN'`; DELETE `secondary_binder`, `personal_collection_cache`, `personal_collection_entry`. `connecting_art_group`, DataStore, secrets, `filesDir/exports/` untouched |
| `data/local/PokedexDatabase.kt`, `di/DatabaseModule.kt`, androidTest `di/FakeDatabaseModule.kt` | `collectionResetDao()` + `@Provides` in both modules. **No entity change, version stays 9** |
| `domain/ResetCollectionUseCase.kt` (new) | thin `summary()` / `reset()` over the DAO |
| `ui/settings/ResetCollectionViewModel.kt` (new) | `Idle -> Confirm1(summary) -> Exporting -> Confirm2(shareIntent) -> Wiping -> Done / Error`. Export `Failure` -> `Error`, no write. `confirmReset(typed)` is a no-op unless state is `Confirm2` and `typed == "RESET"` exactly; `dismiss()` ignored while Exporting/Wiping |
| `ui/settings/ResetCollectionDialogs.kt` (new) | dialogs per state (Confirm1 lists cleared/kept; Confirm2 typed-RESET field, "Share backup again", Reset button enabled only on exact match); `PRE_RESET_RESTORE_WARNING` constant |
| `ui/settings/SettingsScreen.kt`, `BackupDialogs.kt` | "Danger zone" section + button; share sheet opens on entering Confirm2; warning text appended to the snapshot-restore confirm and both import (json/db) confirms |
| tests | new `ResetCollectionViewModelTest` (6: export failure never resets; exact word only; unreachable from Idle/Confirm1; cancel writes nothing; failure -> Error), `ResetCollectionUseCaseTest`; androidTest `PokedexDatabaseTest` +2 (reset keeps slot rows + groups and clears the rest; `fillBlankImage` only blank rows) |

Open-question default applied: `connecting_art_group` kept.

### T6 - Image sync
| File | Change |
|---|---|
| 5 DAOs (`MainBinder`, `UnownBinder`, `ConnectingArt`, `SecondaryBinder`, `PersonalCollection`) | `blankImageCardIds()` (DISTINCT, id not null, img NULL or '') + `fillBlankImage(cardId,url): Int` (never overwrites). No schema change |
| `repository/SettingsRepository.kt` | `getLastImageSyncAt()/setLastImageSyncAt()`, `longPreferencesKey("last_image_sync_at")`, not in `AppSettings` |
| `domain/BlankImageResolver.kt` (new) | `fun interface BlankImageResolver { suspend fun resolve(ref: TcgdexRef): String? }` + `ImageResolverModule` with empty `@Multibinds Set<BlankImageResolver>` (T8d adds `@Binds @IntoSet`) |
| `domain/SyncCardImagesUseCase.kt` (new, `@Singleton`) | `sync(onProgress)` (mutex `withLock`, uncapped), `syncIfDue(now)` (null if <24h or `tryLock` fails; `MAX_AUTO_IDS=400`). Distinct ids -> `TcgdexIds.parse` (null = unsyncable) -> `getCard`; `image` -> `<image>/high.webp` -> all 5 DAOs; absent -> resolvers (first non-null) else stillMissing; any non-cancellation exception -> failed; `chunked(4)` + `awaitAll`; stamp unless every call failed (empty run stamps). The set is injected as `@JvmSuppressWildcards Set<BlankImageResolver>` (Kotlin would otherwise emit `Set<? extends ...>` and Dagger would not match the multibinding) |
| `ui/settings/ImageSyncViewModel.kt` (new), `SettingsScreen.kt` | `Idle/Running(done,total)/Done/Error`; "Card images" section: "Sync card images", "Checking n/m...", result line per spec wording |
| `ui/mainbinder/MainBinderViewModel.kt` | ctor `(binderRepository, settingsRepository, imageSync, context)`; init `viewModelScope.launch { runCatching { imageSync.syncIfDue() } }`. `MainBinderViewModelTest`: 3 sites updated + 1 test (init calls `syncIfDue`, survives its exception) |
| tests | new `SyncCardImagesUseCaseTest` (11: 5-DAO fill + url, shared id looked up once and routed by lang, tcgcsv/legacy unsyncable with 0 calls, no-image missing, one failure does not stop the rest, resolver only after no-image + first wins, 24h gate, stamp, all-failed not stamped, auto cap vs manual, progress), `ImageSyncViewModelTest` (2) |

Not implemented by design: T7 docs, all T8 (no `JapanTcgcsvResolver`, no TCGCSV Japan source).

### Review pass (reuse / quality / efficiency / correctness)
Fixed: reworded a KDoc that contained the banned `pokemontcg` string (acceptance grep); made the `try ... ?: return` in `syncOne` an explicit `found` local to avoid precedence doubt. Checked, no change: no existing helper duplicated (`TcgdexIds`, `Language.fromCardId`, `BackupExporter` reused); `dismiss()` guard prevents closing mid-wipe; Room default `@Transaction` interface methods follow the existing `replaceCacheForPokemon` pattern. Known limit: a 404 on a card counts as `failed` and retries each run (spec behaviour).

Verification (pass 2): typecheck/tests/build not run (Gradle), lint N/A, server N/A, debug + git status pass (sidecar log).

## Friction Notes (pass 2)
- The spec's PC `setCatalog.releaseDate` wiring was dropped per Skyler; the spec text still describes it (amend at T7).

---

# Pass 3 (T8b-T8d): cached Japanese TCGCSV image source

T8a (User-Agent) skipped by instruction; `NetworkModule` untouched (log confirms the UA line is unchanged). U3 and English caching stay deferred. No earlier partial attempt existed (grep found no `TcgcsvJapanSource`/`JapanTcgcsvResolver`/`FetchBudget` in `app/src`).

| File | Change |
|---|---|
| `data/remote/TcgcsvApi.kt` | + `getJapanGroups()` (`tcgplayer/85/groups`), `getJapanProducts(@Path groupId)`; English cat-3 methods untouched |
| `data/remote/TcgcsvDto.kt` | `TcgcsvGroupDto` + nullable `abbreviation`, `publishedOn` (defaults null, so English callers/tests compile unchanged). Products reuse `TcgcsvProductDto` (`Number` read from `extendedData`) |
| `data/remote/TcgcsvJapanSource.kt` (new) | `@Singleton (TcgcsvApi, @ApplicationContext Context)`; `FetchBudget(var left)` with synchronized `tryTake`; `imageUrl(setId, localId, budget?)` never throws. Cache `cacheDir/tcgcsv_ja/`: `groups.json` (only groups with an abbreviation; refetch >24h; stale copy on failure; empty response = failure; orphan `g<id>.json` deleted) and `g<id>.json` (numbered products with a standard `_200w` image, numerator `t`, URL rebuilt as `_400w`). Group chosen by lowercase abbreviation == set id (several: name prefix `"<setId>:"`, else 2 newest). Fresh = group published >90 days ago (forever) else file age <24h; a miss in a file >=24h old refetches once. Ties: drop names with `[` or ending `)` (only if a plain one exists), lowest productId. Per-group `Mutex`, atomic temp-file write, IO on `Dispatchers.IO`. `nowMs`/`cacheRoot`/`log` are `internal var` for tests; logger writes `TcgcsvJa M6-048 -> 709201 (cache\|fetch)` (no keys) |
| `repository/CardSearchRepository.kt` | ctor + 4th param `japanImages: TcgcsvJapanSource`; `CallStats.japanBudget = FetchBudget(3)` (one per public search, shared by parallel calls); `toCards(lang, stats)` fills blank-image `ja` cards from the source before the hasher sees them. Label, flags, confidence untouched. `SmartThresholdUseCase` untouched |
| `domain/JapanTcgcsvResolver.kt` (new) | `lang=="ja"` -> `source.imageUrl(TcgdexIds.setId(id), localId)`; else null with no source call. `JapanTcgcsvResolverModule`: `@Binds @IntoSet` into pass 2's `@Multibinds` set |
| `domain/BlankImageResolver.kt` | KDoc only ("set may be empty") |
| tests | new `TcgcsvJapanSourceTest` (14: join/`_400w`, 5 lookups = 1+1 calls, variants + lowest id + `001/SV-P`, denominator mismatch/unknown set/non-numeric = no product call, duplicate abbreviation, 3 sets = 1+3 with `FetchBudget(2)`, 5 concurrent = 1 call, groups 23h/25h, young vs settled file 23h/25h, miss refetch once, offline warm/stale, cold offline writes nothing, corrupt files, orphan cleanup); new `JapanTcgcsvResolverTest` (2); `CardSearchRepositoryTest`: all 10 `CardSearchRepository(...)` sites now pass `japanImages` (mock, default null) + 2 tests (ja blank filled with flags unchanged; ja with image / en untouched, source never called) |

### Review pass
Fixed during self-review: first draft of `findInGroup` had dead fetch-bookkeeping lines (rewritten to `attempted`/`fetched` flags so a failed refresh is not retried within the same lookup); `JAPAN_IMAGE_FETCHES_PER_SEARCH` moved to a top-level private const because `CallStats` is top-level. Checked, no change: no existing disk-cache helper to reuse; Moshi generated adapters are used with a plain `Moshi.Builder()` (internal `@JsonClass` classes); `Files.move` needs API 26 (minSdk 26).
Known limits: the TCGdex ja card number is taken as `localId.toIntOrNull()` so alphanumeric ids (promos like `SV-P` letters) return null; sets outside the 83 matchable ones stay blank (SmartThreshold still rejects them).

Verification (pass 3): typecheck/tests/build not run (Gradle), lint N/A, server N/A, debug + git status pass. Phone check for Skyler: scan an M6 card, `adb logcat -s TcgcsvJa:*`.

## Friction Notes (pass 3)
- Moshi codegen on `internal` cache classes and `Mutex.withLock` non-local returns are only provable by a real compile.

---

# Fix pass 1 (review loop 1 of 2)

Input: `.pipeline/review-verdict.md` (aggregate 80) + the 3 lens reports. Earlier uncommitted work kept; T7 not started. Gradle not attempted (harness loopback failure). Raw output: `.pipeline/evidence/changes-verify.log` (section "FIX PASS 1").

## Per finding

| # | Finding | Status | What changed |
|---|---|---|---|
| 1 | TCGCSV groups failure retried on every lookup | **fixed** | `TcgcsvJapanSource.loadGroups`: a failed refresh sets `groupsRetryAt` (15 min; **6 h on HTTP 401/403**). Until then it serves the stale copy or null with no network call. Success clears the cooldown. Field is only touched under `groupsLock`. **Deviation:** no budget token is taken for the groups call. The cooldown already caps groups calls at 1 per window process-wide, which is stricter than 1 per search. Spending a token from `FetchBudget(3)` would cut product downloads to 2 and break "1+3". KDoc on `FetchBudget` says this. Tests: 5 budgeted lookups with groups failing = exactly 1 groups call, the 14-min/16-min boundary, a stale copy served with no refetch under cooldown, and 401 backing off 6 h |
| 2 | PC partial refresh drops a language's cached rows | **fixed** | New `CardSearchRepository.searchByNameAllLanguages(name, dex?) : NameSearchResult(cards, complete)`. `complete = CallStats.allSucceeded()`, so it is false when any call failed (dex lookup or one language). `searchByNameWithJapanese` delegates to it (same signature, callers unchanged). `PersonalCollectionRepository.refreshPokemon` replaces the section only when complete, otherwise `upsertCache` (an existing DAO method, PK `cardId`), so rows from the failed call survive. Tests: partial result upserts and never replaces; the `complete` flag for all-ok, one-language-fail and dex-fail |
| 3 | Japan image fill ran before the number/name filters | **fixed** | The fill moved out of `toCards` into `withJapanImages(stats)`, which runs on each public search's final list (`searchByParsedInfo`, `searchByNameAllLanguages`, `searchByDexNumber`; number and promo searches are en-only, as before). Test: a ja number+total step returning 3 cards (one substring-number, one wrong name) makes exactly 1 `imageUrl` call, for the surviving card. The old code made 3 |
| 4 | M1 ptcg leftovers | **fixed** | Removed `TcgCard.hp/artist/setReleaseDate` (zero readers or writers; the `hp`/`artist` grep hits are all `ParsedCardInfo`/`GeminiCardResult`), `BinderRepository.getRowsNeedingBackfill/backfillCardNameSet`, `MainBinderDao.getRowsNeedingCardBackfill/backfillCardNameSet` (`@Query` only, no schema change), and the stale `hp/artist/setReleaseDate` comment |
| 5 | M2 dead release-date path | **fixed** | Removed `TcgdexSetCatalog.releaseDate` + `dates`, `englishName(setId)` (test-only), `TcgdexApi.getSet`, `TcgdexSetDetailDto`, and their 2 tests. The 2 `englishName` tests were rewritten against `englishNames()`. The `PublishRepository` PC `sortedByDescending { releaseDate }` was removed: its only input is `getAllCache()`, which is already `ORDER BY releaseDate DESC, setName, cardId`, so the stable sort was a no-op. The false comment was replaced. The `releaseDate` column is kept (schema) |
| 6 | M3 id scheme in 4 places | **fixed** | `TcgdexIds` gains `cardId(lang,id)`, `isNonEnglish(cardId)` and `localId(id)`. `Language.fromCardId` is now `when (TcgdexIds.parse(id)?.lang)`. `CardSearchRepository` dropped `TCGDEX_EN_ID_PREFIX` and `isNonEnglishTcgdexId`, and builds/parses ids via `TcgdexIds` (`toDomain`, `resolveDex`, cross-language and pokemonNames seeding). `JapanTcgcsvResolver` uses `localId`. Guard: `LanguageTest` iterates `TCGDEX_ASIAN_LANGS` and fails if any maps to EN, so a new language cannot silently fall back. `TcgdexIdsTest` covers the cardId round trip for all 7 languages, `isNonEnglish` and `localId` |
| 7 | M4 lost cascade-order coverage | **fixed** | New test stubs dex+n+t, name+n+t and species-blind n+t together: it asserts `tcgdex-en+dex+number+total` and that the name step is never called, so swapping dex and name fails it. The aggregate's wording ("name ... before dex") is inverted against the code and spec (dex first, per the `resolveParsed` comment and M4's own fix text). I implemented M4's text. Re-added "continues to broad-number after number+total is name-filtered out" with total 64 |
| 8 | M5 + correctness tests | **fixed** | `SyncCardImagesUseCaseTest`: an auto run during a manual sync (manual parked on a `CompletableDeferred` inside `blankImageCardIds`) returns null with no extra DAO read or lookup. TCGCSV failure-path call counts: see #1. PC: a second identical refresh writes the same ids and never calls `upsertEntry/deleteEntry/updateLanguage`. The 2-newest-groups branch is covered (3 groups share an abbreviation; the oldest is never fetched) |
| 9a | `runCatching` swallowing CancellationException | **fixed, no test** | `MainBinderViewModel` init now uses `try { syncIfDue() } catch (CancellationException) { throw } catch (Exception) {}`. The existing test (exception survives, `syncIfDue` called once) still applies. There is no failing-first test because the launched job is private, so swallowing versus rethrowing a cancellation is not observable from outside |
| 9b | `.take(limit)` starvation | **fixed (smallest change)** | Targets are stably sorted so `ko`/`zh-cn` (no TCGdex images) go last before `take(MAX_AUTO_IDS)`. A `ponytail:` comment records the ceiling: more than 400 image-less ids in resolvable languages could still starve; rotate an offset then. Test: 400 ko/zh-cn blanks ahead of 1 ja + 1 en; the auto run looks up both. The old code never did |
| 9c | Reset share sheet reopens on rotation | **fixed** | `Confirm2(shareIntent, shareSheetShown = false)` + `ResetCollectionViewModel.onShareSheetShown()`. `SettingsScreen` opens the sheet only when `!shareSheetShown`, then marks it, so a recreated screen sees `true`. "Share backup again" is unchanged. Tests: one-shot flag + reset still reachable; no-op outside Confirm2 |
| 9d | `FetchBudget.left` public var | **fixed** | `class FetchBudget(initial: Int) { var left = initial; private set }` (the test only reads it) |
| 10 | Leave-alone list | **respected** | `Migration8to9Test` fixtures, 5 per-DAO queries, `CallStats` name, UA, T7 docs untouched. M12 (`AppNavigationScreenTest:79` "pokemontcg.io" comment) is not in items 1-9, so it is left for T7 |

## Deleted-symbol reference check (main + test + androidTest, `*.kt`; sidecar has full output)
```
getRowsNeedingCardBackfill 0 | getRowsNeedingBackfill 0 | backfillCardNameSet 0 | getSet( 0
TcgdexSetDetailDto 0 | releaseDate( 0 | englishName( 0 | setReleaseDate 0
isNonEnglishTcgdexId 0 | TCGDEX_EN_ID_PREFIX 0 | it.hp/card.hp 0 | budget.left = 0
.artist 15 (all ParsedCardInfo / GeminiCardResult / prompt text - not TcgCard)
pokemontcg 5 (Migration8to9Test fixtures = leave-alone; AppNavigationScreenTest:79 comment = T7)
```
All `TcgCard(` constructor calls (26 sites in main+test, none in androidTest) pass only the 6 remaining params (each read). Every mock of `searchByNameWithJapanese` (QuickScan/ManualSearch VM tests) still works, because the method still exists. Only `PersonalCollectionRepositoryTest` was moved to `searchByNameAllLanguages`, the method the repository now calls.

## Review pass (reuse / quality / efficiency / correctness)
- **Reuse:** `upsertCache` (existing DAO) instead of a new merge query. `TcgdexIds.parse` backs every new helper. The `Response.error(...)` HttpException pattern was copied from `PublishRepositoryTest`.
- **Quality:** `withJapanImages` uses an explicit null check rather than relying on `?.lang != "ja"` smart casts. Removed a double blank line in `FetchBudget`. The concurrency test uses `yield()` instead of the experimental `runCurrent()`.
- **Efficiency:** the fill now does fewer lookups (filtered list only). The cooldown removes N-1 groups calls per cold failing list.
- **Correctness:** `fromCardId` changes only on the degenerate `tcgdex_ja_` (empty id): JA becomes EN, a case no real id produces. Publish order is unchanged (input is already DAO-ordered).

## Verification (7 checks)
| # | Check | Result |
|---|---|---|
| 1 | Typecheck | **not run** (Gradle cannot run here; not attempted). Desk-checked: every deleted symbol has 0 references (table above); `NameSearchResult` and the new `TcgdexIds` members are public/same-module; `retrofit2.HttpException` in main, and `okhttp3`/`retrofit2.Response` in test (both already on the classpath via `PublishRepositoryTest`); `Language.kt` -> `data.remote.TcgdexIds` import; `TCGDEX_ASIAN_LANGS` is `internal` in the same module; the `return@map` inside an extension lambda; `secondArg<List<PersonalCollectionCache>>()` in mockk `answers`; the `Confirm2` default param keeps the existing `Confirm2(intent)` assertions valid |
| 2 | Lint | **N/A** - needs Gradle |
| 3 | Tests | **not run - Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks` + `compileDebugAndroidTestKotlin`**. Expected count: 490 - 2 (catalog releaseDate) + 4 (TcgcsvJapanSource) + 2 (TcgdexIds) + 1 (Language) + 4 (CardSearchRepository) + 2 (PC) + 2 (Sync) + 2 (Reset VM) = **505** |
| 4 | Production build | **not run** - Skyler (`assembleDebug`) |
| 5 | Dev-server boot | **N/A** - Android app, no server |
| 6 | Stray debug/scratch | pass: no `println`/`debugger`/`Log` added in this pass (the one added-line `Log.i` hit is pass-1 T1 Discord). No key is logged. No scratch files in the repo (temp output went to `/tmp`) |
| 7 | `git status` | pass: no new untracked files (same set as the start of the pass). Tracked edits are only the files in the table above. Nothing committed or staged by this pass |

## Friction Notes (fix pass 1)
- `grep -rn -- "$pat" dirs --include=*.kt` treats `--include` as a path after `--`; use `-e "$pat"` instead (first evidence run had to be redone).
- Aggregate item 7's wording contradicted the lens report and the code order. When they disagree, the lens report's concrete fix text is the safer source.
