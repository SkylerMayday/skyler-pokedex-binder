# Spec: TCGdex-only + wipe-and-rescan + image sync + Discord toggle

2026-10-03 · Approved (Skyler). Supersedes the archived remap plan in `.pipeline-archive/2026-10-03-ptcg-removal-plan-remap-superseded/`. **No agent runs Gradle**; Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks` + `compileDebugAndroidTestKotlin`. Paths are under `app/src/main/java/com/skyler/pokedexbinder/`; tests mirror in `app/src/test/`. **Pass 2 (2026-10-03):** T1-T3 built (`changes.md`); T4-T7 to build; **T8 added**.

## TL;DR

1. **Delete pokemontcg.io completely** (API, DTOs, DI, `BackfillCardNamesUseCase`, all `POKEMONTCG-IO-FALLBACK`); the scanner cascade keeps its order, labels and confidence rules, TCGdex-only. TCGCSV stays.
2. **Personal Collection (PC)** = TCGdex only, en + ja/ko/zh-tw/zh-cn/th/id.
3. **Wipe** = Settings "Reset collection data": forced export, type-RESET confirm, one Room transaction; keeps slot rows, settings, all keys. **No schema change (v9).**
4. **Image sync**: fills blank image URLs from TCGdex card detail in all 5 image tables; manual button + auto (<=1/24h) on app open.
5. **"Post updates to Discord"** switch, default OFF.
6. **T8**: cached TCGCSV Japan (cat 85) fills blank ja images (M6) at scan/search and in T6 sync; join = set id == group abbreviation + number numerator; never a 459-group fan-out. The TCGCSV User-Agent is blocked today (T8a).

## Problem

ptcg ends 2027-03-01 and still serves PC, manual search, promos, number search, backfill. Skyler chose wipe + rescan over an id remap. PC shows no non-English printings. TCGdex adds images late (`M6` has none) and saved cards keep a blank URL forever. After the wipe the first publish diffs against the old site snapshot: ~1,000 false Discord messages.

**Verified (code + live probes 2026-10-03):**
- `searchByName` (ptcg-first) has one caller, PC `refreshPokemon`; `searchByNameWithJapanese` is already TCGdex en + six languages by dex, so PC can call it. The id flip-flop dies with ptcg. `setOwned` upserts with REPLACE, **resetting `language` to "EN"** (bug; T4).
- TCGdex brief DTO has **no set name or release date**: `setName` is the code ("SV08"); ptcg supplied names and `releaseDate` (PC orders `releaseDate DESC`). `GET /v2/{lang}/sets/{id}` has `releaseDate` (en `sv08` 2024-11-08, ja `M6` 2026-07-31); `GET /v2/en/sets` has names only.
- Card detail has `image` (en `sv08-048` yes, ja `M6-048` none); `TcgdexCardDetailDto` lacks it.
- Probed: `?rarity=Promo&name=Pikachu` (57 hits), `?localId=208&set.id=eq:svp` -> `svp-208`, `?localId=SWSH260|XY29|BW50` hit, `SVP208` empty; `dexId=eq:6`: en 124, ja 44, ko 0, zh-tw 9, zh-cn 1, th 6, id 3 (70-160 ms/call).
- OkHttp's default `maxRequestsPerHost` is 5 and `NetworkModule` doesn't change it [library default, not measured]. No WorkManager (only datastore).
- `computeDiff`: `contentChanged = next.binders != baseline.binders`, so an image-only change re-uploads `binder.json` with no delta. `RestoreRepository` writes snapshot ids/images verbatim.
- **Unverified:** PC row count (~200-350 est.); whether TCGdex gives every non-English card a `dexId` (known gap: JA Black Kyurem ex); TCGdex uptime/rate policy; ptcg image hosts after 2027-03-01; `QuickScanViewModel` exception handling (not read).

## Proposal

### Wipe design: option (a), in-app reset, no migration

Rejected (b) destructive migration: needs v10 (one-way door; a later v9 build wipes via `fallbackToDestructiveMigration`, per `app/CLAUDE.md`), has no confirm/export, drops slot rows.

**Cleared** in one `@Transaction` in new `data/local/CollectionResetDao.kt` (add `collectionResetDao()` to `PokedexDatabase` with no entity change; `@Provides` in `di/DatabaseModule.kt` and androidTest `FakeDatabaseModule.kt`):

- `main_binder`, `unown_binder`: `UPDATE SET assignedCardId/ImageUrl/Name/SetName=NULL, language='EN', remarks=NULL, isLocked=0` (rows stay; seeded from `pokemon_slots.json` / 28 letters).
- `connecting_art_slot`: `UPDATE SET cardId/cardName/cardImageUrl=NULL, owned=0, language='EN'`.
- `secondary_binder` (Card History), `personal_collection_cache`, `personal_collection_entry`: `DELETE` all (PC auto-refreshes since `cacheCount()==0`).

**Kept:** `connecting_art_group` (named grid layouts; see Open questions), all DataStore settings, `settings_secrets` (Gemini, Anthropic), `publish_secrets` (GitHub PAT, Discord webhook), `filesDir/exports/`. Room DAO `@Query`, not raw `execSQL`, so open Flows invalidate.

**Flow** (`ui/settings/ResetCollectionViewModel.kt`, `ResetCollectionDialogs.kt`, "Danger zone" in `SettingsScreen`): `Idle -> Confirm1` (counts from `CollectionResetDao.summary()`, cleared/kept lists) `-> Exporting` (`BackupExporter.export()` + share intent) `-> Confirm2` (only after export `Success`; type `RESET`; "make sure you saved the file") `-> Wiping -> Done | Error`. Export `Failure` -> `Error` with **zero writes**; `Wiping` is unreachable without `Confirm2`. Recovery: Settings -> "Restore from file" with the `.db`; the last 5 exports also stay in `filesDir/exports/` (lost on uninstall, so the shared copy is the real net).

**Restoring a pre-wipe backup/snapshot (ptcg ids) later:** no crash, but rows keep ptcg ids/images (load only while those hosts live), PC owned flags go invisible, sync counts them "not syncable". It undoes the wipe, so **don't**; say so in both restore confirm dialogs.

### Search / PC (TCGdex only)

**Built in pass 1 (T2/T3, details in `.pipeline/changes.md`):** `CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog)` is TCGdex-only with the ported cascade (`tcgdex-*`, `broad-*`, `none-matched`), `searchPromos`/`cardsInSet`/`searchByNumber`, `TcgdexSetCatalog` (`englishName`, `releaseDate(lang,setId)`), `TcgdexIds` (`parse`, `setId`, `imageUrl`), and `TcgdexUnavailableException` + `CallStats` outage handling (`searchStreaming` still awaits TCGCSV). TCGCSV English search stays. Do not rework.

**PC** (`PersonalCollectionRepository.kt`, adds `setCatalog`):
- `refreshPokemon` -> `searchByNameWithJapanese(name)` per name: ~8 calls (name search, dex detail, 6 languages parallel), ~40 for 5 names, <=5 in flight.
- `releaseDate`: reuse the cached row's date, else `setCatalog.releaseDate` (memoised); failure -> `""` (sorts last). All rows `yyyy-MM-dd` (ptcg used `yyyy/MM/dd`; the wipe prevents mixing).
- **Empty guard (new):** merged list empty and section already cached -> keep the cache; an outage throws and the existing per-section `runCatching` in `refreshAll` reports it.
- `Language`: add `TH("Thai")`, `ID("Indonesian")`, `fromCardId(id)` (`tcgdex_ja_`->JA, `ko`->KO, `zh-tw|zh-cn`->ZH, `th`->TH, `id`->ID, else EN). Replace `?: "EN"` at `PersonalCollectionViewModel.kt:68` and `PublishRepository.kt:402`. `setOwned` keeps an existing entry's language; new rows use `fromCardId`.
- Limit: non-English coverage = cards TCGdex tags with the species `dexId`.

### Sync design (images only)

Blank = `NULL` or `''`. Each DAO gets `blankImageCardIds(): List<String>` (DISTINCT) + `fillBlankImage(cardId, url): Int` = `UPDATE ... SET img=:url WHERE idCol=:cardId AND (img IS NULL OR img='')` (no schema change; never overwrites; locks don't block): `main_binder.assignedCardImageUrl`, `unown_binder.assignedCardImageUrl` (key `assignedCardId`); `connecting_art_slot.cardImageUrl`, `secondary_binder.cardImageUrl`, `personal_collection_cache.imageUrl` (key `cardId`; last two NOT NULL).

`domain/SyncCardImagesUseCase.kt` (`@Singleton`; `TcgdexApi`, 5 DAOs, `SettingsRepository`):
```kotlin
data class ImageSyncResult(val checked: Int, val updated: Int, val stillMissing: Int, val unsyncable: Int, val failed: Int)
suspend fun sync(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImageSyncResult // manual, waits on mutex
suspend fun syncIfDue(now: Long = System.currentTimeMillis()): ImageSyncResult?            // auto; null if <24h or running
```
- Distinct blank ids -> `TcgdexIds.parse` (null = unsyncable) -> `tcgdexApi.getCard(lang, id)`: `image` present -> `fillBlankImage` on all 5 DAOs (`updated++` per card); absent -> `stillMissing++`; IOException/HTTP error -> `failed++` (retry next run); `CancellationException` rethrown. `TcgdexCardDetailDto` gains `image: String? = null`.
- Volume: `chunked(4)` + `awaitAll` (+ OkHttp 5/host); auto capped at `MAX_AUTO_IDS = 400`, manual uncapped; no blank ids = no network; `Mutex.tryLock` (auto) / `withLock` (manual).
- Timestamp: `SettingsRepository.getLastImageSyncAt()/setLastImageSyncAt()` (`longPreferencesKey("last_image_sync_at")`, not in `AppSettings`, which holds API keys); stamped unless every call failed; `AUTO_SYNC_INTERVAL_MS` = 24h.
- Auto: `MainBinderViewModel.init` (start destination) replaces the backfill call with `viewModelScope.launch { runCatching { imageSync.syncIfDue() } }`. Manual: `ui/settings/ImageSyncViewModel.kt` (`Idle|Running(done,total)|Done(result)|Error`) + "Card images" section in `SettingsScreen`: "Sync card images", "Checking 14/87...", result "Updated n · Still missing m (TCGdex has no image yet) · Couldn't check k · Not syncable u".
- Publish: no code; the next publish re-uploads `binder.json` with the new URLs (no changelog/Discord entry).

### Discord toggle

`PublishSettingsRepository.kt`: `PublishConfig.postToDiscord = false`; `booleanPreferencesKey("post_to_discord")` in the existing `publishDataStore`, read `?: false` (so fresh and existing installs are OFF; existing installs with a webhook stop posting, and the description says so); `setPostToDiscord(v)`. `PublishRepository.kt` (~line 208): `if (config.postToDiscord && config.discordWebhookUrl.isNotBlank())`, `else` log distinguishes "toggle off" from "no webhook". Changelog PUT unchanged. `SettingsViewModel.setPostToDiscord`; `SettingToggleItem("Post updates to Discord")` above the webhook field (field stays).

### T8: TCGCSV Japan (TCGplayer cat 85) as cached image source

**Probed 2026-10-03** (all 459 cat-85 product lists + 179 TCGdex ja set details; `SM1+..SM5+` skipped by my script's unencoded `+`; scripts uncommitted in the session scratchpad):

| Question | Result |
|---|---|
| Key `abbreviation.lower() == setId.lower()` | 83 of 116 ja sets with cards match (79 fully; partial `M-P` 65/132, `SV-P` 226/288, `S8b`, `SM10`). 33 misses = vintage `E*/neo*/PMCG*/PCG*/VS1/web1`, `SM1p-5p`, `MC`, `SVLS`; group-name prefix/contains recovers **0**. 138 of 459 groups have no abbreviation (misc promos) |
| Cards | ja image-less 8,899 of 12,781; join recovers 5,399 (M 1018/1018, SV 3367/3389, S 1788/1796, SM 2734/3139, vintage 61/3019) |
| Number | `048/076`, promos `001/SV-P`. Denominator == `cardCount.official` **fails** (SM10b 56 vs 054, SM7a 50 vs 060, SV11B 174 vs 086, `037/080` in M4); numerator-only in the group: 100% on the 79 sets |
| Duplicates | 1,594 of 12,238 numerators have >1 product (`[Set of 2]`, `(Poke Ball Pattern)`, `(Master Ball Pattern)`) |
| Right card? | 32 sets x 1 card: 31 art-correlation >=0.99 vs TCGdex; outlier `SV1a-082` (full art) is Quaxwell = ウェルカモ. Small sample |

**D1 join (only rule).** Group = lowercased `abbreviation` equals lowercased set id (several: name starts `"<setId>:"`, else 2 newest). Product = has `imageUrl` and `Number` numerator (`^\D*0*(\d+)`) == `localId` as int; ties: drop names with `[` or ending `)`, then lowest `productId`. Denominator ignored; non-numeric localId/no group -> null. No fallback key; alias table parked.

**T8a User-Agent (S, first).** `Mozilla/5.0 (Linux; Android 13) PokedexBinder` (`NetworkModule.kt:62`) now gets **401 "User-Agent has been blocked"** on `tcgplayer/3/groups` here (so do `okhttp/4.12.0`, `Mozilla/5.0`); `PokedexBinder/1.0` gets 200 on cat 3 and 85. Block appeared after my ~370-request burst; whether it pre-dated it, or the phone is blocked, is **unverified**. Use `PokedexBinder/1.0`. `searchTcgcsvByName` swallows errors, so English TCGCSV may be silently empty.

**D2 cache** `data/remote/TcgcsvJapanSource.kt` (`@Singleton`, ctor `(TcgcsvApi, @ApplicationContext Context)`). `TcgcsvApi` + `@GET("tcgplayer/85/groups") getJapanGroups()`, `@GET("tcgplayer/85/{groupId}/products") getJapanProducts(groupId)` (English untouched); `TcgcsvGroupDto` + nullable `abbreviation`, `publishedOn`. Files in **`cacheDir/tcgcsv_ja/`** (I disagree with filesDir: refetchable, OS may reclaim, Room keeps filled URLs); no Room change.
```kotlin
class FetchBudget(var left: Int)
suspend fun imageUrl(setId: String, localId: String, budget: FetchBudget? = null): String? // never throws
internal var nowMs: () -> Long = System::currentTimeMillis; internal var cacheRoot: File    // tests
```
- `groups.json` `{fetchedAt, groups:[{g,a,n,p}]}` ~33 KB, refetch at age >24h, stale copy kept on failure.
- `g<id>.json` `{fetchedAt, items:[{i,n,t}]}`, numbered products only; `imageUrl` not stored (27,918/27,918 equal `.../product/<i>_200w.jpg`). Measured with url: M6 13.6 KB, SV11B 41.6 KB, all 459 = 3.2 MB; without ~1.1 MB (estimate) = ceiling; cleanup = delete files whose id left `groups.json`.
- Fresh when group `publishedOn` >90 days old (forever; 23 of 459 are newer) else age <24h. A **miss** in a file >24h old refetches once.
- Lookup = groups (0-1 call) -> ONE group file (0-1 call); per-group `Mutex`; budget exhausted -> null, no call. **Budgets:** warm 0; cold set 2; search/scan <=1+3; sync <=1 + distinct ja sets (<=83 matchable); never 459.

**D3 size.** Return `_400w.jpg` (replace `_200w.jpg`; else untouched): 400x559, 62 KB vs 19 KB (200 OK for 3 of 3 products). `_in_1000x1000` (212 KB) exists; `_500x500` 403. numpy port of `ArtHash` (not the Kotlin class), 23 cards, TCGdex vs TCGplayer: median distance 6 at 200w, 2 at 400w; tail (to 26) identical at all sizes, so size is not the limiter. CDN needs no UA/Referer.

**U1 fill.** `CardSearchRepository` ctor + 4th param `japanImages: TcgcsvJapanSource` (update 10 test sites, stub null). In `toCards(lang)`, `lang=="ja"` cards with blank image get `japanImages.imageUrl(TcgdexIds.setId(id), localId, budget)` (`FetchBudget(3)` per public search): scanner, manual and Quick Scan all covered, before `PerceptualHasher`, so `distance=` is a number. **`SmartThresholdUseCase` blank-image rule stays** (33 unmatched sets still blank; a failed real-URL download still rejects). Log `TcgcsvJa M6-048 -> 709201 (cache|fetch)`.

**U2 sync (seam in T6).** T6 adds `domain/BlankImageResolver.kt` (`fun interface { suspend fun resolve(ref: TcgdexRef): String? }`) and `di/ImageResolverModule` `@Multibinds abstract fun resolvers(): Set<BlankImageResolver>`; `SyncCardImagesUseCase` takes the set and, only if `getCard` has no image, tries them (first non-null -> `updated`). T8d adds `JapanTcgcsvResolver` (`lang=="ja"` -> `imageUrl(setId, localId)`) via `@Binds @IntoSet`.

**U3 Japanese backup search: DEFER.** A saved `tcgcsv_ja_<productId>` is orphaned once TCGdex adds the set (the id flip-flop this spec removes); `Language.fromCardId`, `TcgdexIds.parse`, PC, publish assume `tcgdex_` ids; identity would be English name+number+total with a hash that cannot discriminate; gain = sets TCGdex lacks (`M6a`, `MF` today) or outages. Verified: only `CardSearchRepository.kt:519` produces `tcgcsv_` ids, nothing parses them. Sketch: step `tcgcsv-ja+number+total` after `none-matched`, group total = mode of Number denominators, name AND number AND total, single hit, `nameVerified` only, id `tcgcsv_ja_<productId>`, setName `<NAME> (JA)`.

**English (Q4): NO this build.** It is a NAME search over 220 groups (~121 products, ~138 KB each, ~30 MB, 10-group sample); a keyed per-group cache does not help, it needs a daily compact name index. Only T8a touches it. Follow-up.

## User stories

- As Skyler, I reset collection data after a forced export and two confirmations, keeping keys and slot layout.
- As Skyler, scan/search/PC work without pokemontcg.io and PC lists en + ja/ko/zh/th/id printings.
- As Skyler, saved cards get missing images once TCGdex has them, and publishing never posts to Discord unless I turn it on.
- As Skyler, a ja card TCGdex has no image for (M6) shows a picture, gets the hash check when scanned, and Sync fills it.

## Acceptance criteria

- [ ] Failed export -> 0 rows change; wipe needs typed `RESET`; a confirmed reset clears exactly the list above (slot rows, `connecting_art_group`, settings, 4 secrets survive), Room stays v9, open screens update live.
- [ ] ja/ko/zh/th/id/en scans: cascade labels, `identityPinned`/`nameVerified`/`crossLanguage` and confidence equal today's for the same fixtures; `grep -ri "pokemontcg\|PokemonTcgApi\|TcgCardDto" app/src/main app/src/test` -> 0 hits.
- [ ] PC refresh: each section has en + non-English cards (`tcgdex_*`, `tcgdex_<lang>_*`), newest set first; ids and owned flags identical after a second refresh; `setOwned` on a ja card gives JA.
- [ ] TCGdex outage: search/scan show the unreachable message (not "no results"); PC keeps its cache.
- [ ] Sync fills exactly the blank rows (5 tables) whose detail now has `image` with `<image>/high.webp`; non-blank untouched; `tcgcsv_`/legacy = unsyncable; `syncIfDue` within 24h makes 0 calls; an all-failed run isn't stamped.
- [ ] T8: `imageUrl("M6","048")` -> `709201_400w.jpg`, `("M6","021")` -> `709177`; 5 lookups in one set = 1 groups + 1 product call; warm cache answers offline; a ja scan's candidates have non-blank `imageUrl`; resolver runs only after TCGdex shows no image; second Sync makes 0 calls for filled rows; English TCGCSV tests unchanged.
- [ ] `postToDiscord` false on fresh and existing installs; off + owned change uploads `binder.json`+`changelog.json` and never calls `discordApi`; on + webhook posts once.

## Out of scope

Id remap / alias table / old-backup translation; cross-device collection sync; Room schema change; second data source; WorkManager; suppressing changelog churn; pre-filling Language from the scan; TCGCSV fallback inside sync; TCGdex data gaps (zh-cn `CSV9C` empty, ko/zh-cn no images); U3, English name index, ja alias table (T8); Korean/Traditional-Chinese via PTCG-database (parked in `gaps.md`). For Chinese cards TCGdex lacks, the user keeps seeing the "closest English printings" `crossLanguage` fallback; Skyler accepted that.

## Dependencies

TCGdex `/v2/{lang}/cards`, `/cards/{id}`, `/sets`, `/sets/{id}` (probed); tcgcsv.com `tcgplayer/85/*` (identifying User-Agent required) and `tcgplayer-cdn.tcgplayer.com`; `BackupExporter`; Room 2.6.1 + room-ktx; DataStore. No new libraries.

## Open questions

- [ ] Keep `connecting_art_group` layouts on reset? Default **keep** (only slot cards cleared). Skyler, before T5.
- [ ] Should the switch also skip the changelog entry? Default no (webhook only); publish after the rescan so the site changelog records the churn once. Skyler.
- [x] `QuickScanViewModel` exception mapping: confirmed in pass 1.
- [ ] T8: is the phone blocked too (`curl -A "Mozilla/5.0 (Linux; Android 13) PokedexBinder" https://tcgcsv.com/tcgplayer/3/groups` -> 401)? `_400w` or `_200w` (default 400w)? Park U3 + English index (default yes)? Skyler, non-blocking.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Wipe data loss (intended) | Export gate (abort on failure), type-RESET, single transaction, restore-from-file |
| TCGdex outage = no search | Clear error state, PC cache kept, TCGCSV rescues English manual search; **no second source** (revisit if outages are seen) |
| Rate limits (policy unknown) | OkHttp 5/host, PC ~40 calls + memoised set dates, sync chunks of 4 + 400-id auto cap + 24h gate |
| Images missing for new sets (M6, ko, zh-cn) | Sync fills later; TCGCSV backfill at search time; "still missing" shown |
| Discord spam / changelog churn after wipe | Toggle OFF by default; publish after rescan |
| tcgcsv blocks the UA (seen today) | `PokedexBinder/1.0`; <=4 calls per search; stale cache on 401/offline |
| Wrong product joined / CDN hotlink on site | Image only, never identity; 32/32 sample right; no Referer check seen (browser `<img>` unverified) |

## Smallest shippable increment

T1 (Discord toggle) alone is shippable and is the safety net. T1-T4 meet the 2027 deadline. Skyler wants one build, but T5 (wipe) must not ship before T3/T4: the rescan needs TCGdex-only search. T8a is a one-line, independent fix; T8b+c give the M6 image without T4-T7; T8d needs T6.

## Tasks (ordered; each leaves unit tests green)

| # | Size | Work |
|---|---|---|
| T1 | S | Discord toggle (repo, gate, VM, UI); update `PublishRepositoryTest` (webhook cases set `postToDiscord=true`), `PublishSettingsRepositoryTest`; new `SettingsViewModelTest` |
| T2 | M | `TcgdexIds`, `TcgdexSetCatalog`, `TcgdexApi` additions, DTOs (+detail `image`), `Language` TH/ID + `fromCardId` (pure additions) |
| T3 | L | Remove ptcg: `CardSearchRepository` rewrite, `TcgdexUnavailableException`/`CallStats`, streaming outage handling; delete `PokemonTcgApi.kt`, `TcgCardDto.kt`, the unqualified-`Retrofit` + `providePokemonTcgApi` block in `NetworkModule` (confirm nothing else injects it), `BackfillCardNamesUseCase`(+test); update `MainBinderViewModel`(+test) |
| T4 | M | PC repository, VM/Publish language fallback, empty guard, `setOwned` fix |
| T5 | M | `CollectionResetDao` (+DI incl. androidTest fake), use case, VM, dialogs, Settings section, restore-dialog warning |
| T6 | L | 5 DAO method pairs, `SyncCardImagesUseCase`, timestamp, `ImageSyncViewModel`, Settings UI, `MainBinderViewModel` hook, `BlankImageResolver` seam + `@Multibinds` (T8) |
| T7 | S | Docs (`project-overview.md`, `gaps.md`, `handoff.md`, T8 follow-ups); final `pokemontcg` grep |
| T8a | S | UA -> `PokedexBinder/1.0`. Any time |
| T8b | L | `TcgcsvApi`, DTO, `TcgcsvJapanSource` + test. Independent of T4-T7 |
| T8c | M | U1: `CardSearchRepository` ctor + fill, 10 test sites |
| T8d | S | After **T6**: `JapanTcgcsvResolver` + `@Binds @IntoSet`, tests |

## Test plan

Existing style (MockWebServer, mockk, `runTest`, `InMemoryPreferencesDataStore`, `FakeSharedPreferences`). Room SQL can't run on the JVM: extend androidTest `PokedexDatabaseTest` (reset keeps slot/group rows; `fillBlankImage` only blank rows); `compileDebugAndroidTestKotlin` mandatory.

- **`CardSearchRepositoryTest`**: ported in pass 1; only the T8c ctor/stub change in pass 2.
- **`PersonalCollectionRepositoryTest`**: `tcgdex_*`/`tcgdex_ja_*` ids with catalog dates; existing date reused; outage/empty result keep the cache; `setOwned` keeps JA; owned survives a second refresh. **`ResetCollectionViewModelTest`**: export Failure -> `resetAll` never called; Confirm2 needs exactly `RESET`; cancel writes nothing. **`SyncCardImagesUseCaseTest`** (mockk `TcgdexApi`/DAOs): blanks filled in all 5 DAOs; ja/en routing; `tcgcsv_` unsyncable; no-image stillMissing; one failure doesn't stop the rest; shared ids deduped; 24h gate via injected `now`; no stamp when all failed; concurrent auto returns null; auto cap. Also `ImageSyncViewModelTest`, `MainBinderViewModelTest` (init calls `syncIfDue`, survives its exception), `PersonalCollectionViewModelTest`, `ResetCollectionUseCaseTest`.
- **T8** (`TcgcsvJapanSourceTest`: mockk `TcgcsvApi` + `coVerify(exactly=)`, TemporaryFolder, `nowMs`): join (`048`/`48`, `[Set of 2]` skipped, variants -> lowest id, `001/SV-P`, denominator mismatch matches, unknown abbreviation -> 0 product calls, duplicate abbreviation, non-numeric localId); lazy (5 lookups/1 set = 1+1 calls; 3 sets = 1+3; `FetchBudget(2)` -> 3rd null, no call; 5 concurrent cold = 1 product call); freshness (groups +23h 0 / +25h 1; 400-day group +60d 0; 10-day group +23h 0 / +25h 1; miss +25h 1 refetch, again +1h 0); offline (warm + `IOException`/401 answers; cold -> null, nothing written; stale groups served); corrupt file refetched; `_400w` rewrite. `CardSearchRepositoryTest`: ja blank filled, non-blank/en/ko untouched, null keeps blank, label/flags unchanged. Sync tests: resolver only after no-image, first non-null wins, empty set works. `JapanTcgcsvResolverTest`: non-ja -> no source call.
- **Publish**: toggle matrix in `PublishRepositoryTest` (off+webhook, on+blank webhook, on+webhook); default false + persisted in `PublishSettingsRepositoryTest`; `SettingsViewModelTest`.

## Verification procedure (Skyler, phone)

1. PC: `testDebugUnitTest --rerun-tasks` + `compileDebugAndroidTestKotlin` green (fresh XML).
2. Phone, **old build first**: Settings -> Backup, save `.db` + `.json` off-phone. `installDebug`; keys still in Settings.
3. "Reset collection data": read counts, save the exported file, type `RESET`. Slots empty but present, Card History empty, keys + webhook intact.
4. Rescan 3-5 cards incl. a ja one: real set name, not a bare code.
5. Personal Collection refresh: en + ja/zh-tw/th/id printings, newest first, languages right; mark a ja card owned, refresh, still owned.
6. "Sync card images": note counts (an `M6` card = still missing; re-run once TCGdex adds images).
7. Discord switch OFF: Publish now = Discord silent, site updated. ON, change a card, publish = one post.
8. T8a-c: scan Mega Malamar ex / Wishiwashi ex (M6); `adb logcat -s ScannerMatch:* TcgcsvJa:*` shows `TcgcsvJa M6-048 -> 709201 (fetch)` then `perceptualHasher ... distance=<number>`; saved card shows an image. Scan again: `(cache)`, works in airplane mode.
9. T8d: run Sync twice on a saved blank M6 row: first `Updated n`, second `Updated 0` and no `tcgcsv.com` request.

## Friction Notes

- Gradle can't run in the harness, so Room SQL is verifiable only via androidTest or the phone procedure.
- The archived plan missed that ptcg silently supplied set names and PC release dates (needs `TcgdexSetCatalog`).
- tcgcsv.com blocked the UA right after my 370-request probe burst: probe third-party hosts at low concurrency with an identifying UA. Python probes need `PYTHONUTF8=1` here.
