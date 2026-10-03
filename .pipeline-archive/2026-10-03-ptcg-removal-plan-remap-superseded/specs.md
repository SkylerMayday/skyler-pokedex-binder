# Spec: Remove pokemontcg.io; TCGdex only

2026-10-03 · Draft for approval · ptcg ends 2027-03-01. **Coder/Tester never run Gradle**; Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks` (plus `connectedDebugAndroidTest` on AVD `pokedex_test` for T1).

## TL;DR

Room v10 adds `card_id_alias`. An idempotent Settings step (dry-run, auto-backup, apply) rewrites stored ptcg ids to `tcgdex_<id>` in place and logs each rewrite. Restore, import and the diff baseline go through the alias, so old backups and the site keep working. Then PC goes TCGdex-only and multilingual, and the ptcg code is deleted. The mapping needs **only TCGdex**.

## Problem

Ids carry a source prefix: ptcg `sv8-48`, TCGdex `tcgdex_sv08-048` / `tcgdex_ja_SV7a-011`, TCGCSV `tcgcsv_<n>`. Owned state, restore matching and diffs key on them, so switching source orphans PC owned flags, makes Card History restore add duplicates, and fakes REPLACED/ADDED deltas. The docs say keys work "through March 1, 2027"; **the app sends no key** (`NetworkModule.provideRetrofit`), so it may lose access sooner [Guessing].

**Live probes 2026-10-03.** Sample = live `binder.json` from GitHub raw, published 2026-08-24. **Not the phone DB.**

| Finding | Value |
|---|---|
| Distinct ptcg ids | **1153** (Pokédex 1138, Card History 21) |
| Auto-mapped (set map + number) | **1151 (99.8%)**: 950 name-exact with **0 mismatches**; 202 with no stored name but species-consistent |
| TCGdex id differs from ptcg id (`sv8-48`->`sv08-048`, `sv3pt5`->`sv03.5`, `mcd19`->`2019sm`) | **627 (54%)**: naive `tcgdex_`+ptcgId is wrong for over half |
| Unmapped | 2: `ecard2-50` Golduck (TCGdex has `50a`/`50b`), `cel25c-60_A` Tapu Lele-GX (renumbered `cel25cc-CC025`) |
| Mapped but no TCGdex image | 69 (6%) |
| ptcg sets -> TCGdex sets | 176/176: 126 identical id, 50 via fixed table below |
| ptcg reliability | `/cards/{id}`: ~11 of 19 tries returned 500/502; `/sets` failed 2 of 3 |
| Image hosts | `images.pokemontcg.io` 1102, `images.scrydex.com` 57; all 200 today, post-shutdown undocumented [Guessing] |
| PC published | 225 rows, **all `tcgdex_en`, 0 owned** |
| Pokédex language | 465 JA / 66 ZH / 17 KO on English-printing ids. **Language is user-set, not id-derived** |

**Latent bug:** `searchByName` (PC refresh) merges ptcg first. When ptcg fails silently (`safeSearch`), the cache flips to TCGdex ids and orphans owned entries, which fits "all tcgdex, 0 owned". **Not verified:** phone DB contents (the dry-run reports them), image CDN after 2027-03-01, TCGdex coverage of sets outside the sample.

## Proposal

**Decision: C+ = in-place remap + alias ledger.**

| Option | Verdict |
|---|---|
| A: remap only | No. Old backups re-inject ptcg ids: Card History dups, PC owned on dead ids, ~1138 REPLACED in Discord |
| B: keep ptcg ids, resolve lazily | No. Every consumer handles two id forms forever (the same bug class); legacy images still die |
| **C+: rewrite + `card_id_alias`** | Yes. One id form at runtime. Alias canonicalizes inbound snapshots and the diff baseline, and is the rollback ledger. Re-run fixes fresh installs |

The remap is **not** inside the Room migration (it needs network). The migration only creates the table.

**Images:** switch to TCGdex `<image>/high.webp` when TCGdex has one; otherwise keep the stored URL and count it. **Name, set name and `language` are never touched.**

**Timeline:** T1–T3 by 10-17 (zero behaviour change). T4 by 10-31, then the emulator rehearsal and the phone apply. T5+T6 by 11-21. **T7 deletes ptcg by 12-15** (hard latest 2027-02-15): the remap needs no ptcg, and keeping ptcg only mints new legacy ids. On 2027-03-02, check the legacy image hosts; if they are dead, T8. Alias table + mapper stay **forever**, so old backups always restore.

### Tasks (ordered; each test-green alone)

Paths are relative to `app/src/main/java/com/skyler/pokedexbinder/`; tests mirror them.

| # | Size | Files | What |
|---|---|---|---|
| T1 | S | `data/local/CardIdAlias.kt`, `CardIdAliasDao.kt` (new); `PokedexDatabase.kt`; `di/DatabaseModule.kt`; androidTest `FakeDatabaseModule.kt`, `Migration9to10Test.kt` (new) | `MIGRATION_9_10`, `SCHEMA_VERSION=10`, `ALL_MIGRATIONS` |
| T2 | M | `domain/LegacyCardIdMapper.kt` (new); `TcgdexApi.kt` +`getSet(lang,id)` (`{lang}/sets/{id}`); `TcgdexCardDto.kt` +`TcgdexSetDto(id,name,cards)` | Pure mapper |
| T3 | M | `publish/CardIdCanonicalizer.kt` (new); `RestoreRepository.applySnapshot` canonicalizes first; `PublishRepository.publish` canonicalizes the baseline before `computeDiff` | No-op while the alias is empty |
| T4 | L | `data/local/CardIdRemapDao.kt` (new, registered in DB); `domain/NormalizeCardIdsUseCase.kt`; `ui/settings/CardIdMigrationViewModel.kt` + `CardIdMigrationDialogs.kt` (new); `SettingsScreen.kt` | Dry-run, backup, apply, re-link list. **After T3** |
| T5 | M | `CardSearchRepository`, `PersonalCollectionRepository`, `Language`, `PersonalCollectionViewModel`, `PublishRepository` | PC TCGdex + 6 langs |
| T6 | M | `CardSearchRepository`, `TcgdexApi` (+`rarity`), `BackfillCardNamesUseCase`, `TcgdexCardDetailDto` (+`set`) | Replace the ptcg-only paths |
| T7 | M | Delete `PokemonTcgApi.kt`, `TcgCardDto.kt`, `NetworkModule` L39-50 and every `POKEMONTCG-IO-FALLBACK` block; fix `CardSearchRepositoryTest` (19 refs) and `BackfillCardNamesUseCaseTest` | 0 `pokemontcg` hits in main |

**ptcg call sites:**
- `CardSearchRepository`: L79, 141, 149, 161 cascade, 186, 193\*, 198 `searchByName`, 211, 259 `searchByNumber` (ManualSearch:47, QuickScan:86), 263\*, 270, 275 `searchPromosByName` (ManualSearch:49), 363 `safeSearch`, 371. (\* = no callers.)
- `BackfillCardNamesUseCase:33` (MainBinderVM:54).

**Signatures**
```kotlin
@Entity(tableName = "card_id_alias") data class CardIdAlias(@PrimaryKey val legacyId: String,
  val canonicalId: String?, val status: String /*MAPPED|NEEDS_RELINK*/, val legacyImageUrl: String?, val migratedAt: Long)
// CardIdAliasDao: getMapped(): List<CardIdAlias> (status='MAPPED'); upsertAll(rows) REPLACE
object LegacyCardIdMapper {                      // pure JVM
  fun isLegacyId(id: String): Boolean           // !"tcgdex_" && !"tcgcsv_" prefix
  fun tcgdexSetFor(ptcgSet: String): String     // PTCG_SET_OVERRIDES[s] ?: s
  fun normalizeNumber(n: String): String        // ^([A-Za-z]*)0*(\d+)([A-Za-z]*)$ -> PREFIX+digits+SUFFIX upper; else upper
  fun decide(ref: LegacyRef, setCards: List<TcgdexCardBriefDto>?): MappingDecision }
data class LegacyRef(val legacyId: String, val storedName: String?, val speciesName: String?, val storedImageUrl: String?)
sealed interface MappingDecision { val ref: LegacyRef
  data class Mapped(override val ref: LegacyRef, val tcgdexId: String, val imageUrl: String?, val byName: Boolean) : MappingDecision
  data class NeedsRelink(override val ref: LegacyRef, val reason: String) : MappingDecision
  data class Transient(override val ref: LegacyRef) : MappingDecision }   // set fetch failed -> touch nothing
@Singleton class CardIdCanonicalizer @Inject constructor(private val aliasDao: CardIdAliasDao) {
  suspend fun canonicalize(s: BinderSnapshot): BinderSnapshot }           // cardId via alias; PC binder slotId too
class NormalizeCardIdsUseCase { suspend fun dryRun(): NormalizeReport; suspend fun apply(r: NormalizeReport): ApplyResult }
```

**`decide`:** set = text before the first `-`; number = text after it. `setCards == null` -> Transient. Unique `normalizeNumber` hit -> verify: if `storedName` is present, normalized names are equal or one contains the other; otherwise the first word of `speciesName` is contained in the card name. Pass -> Mapped; fail -> NeedsRelink. No unique number hit -> unique normalized-name hit in the set -> `Mapped(byName=true)` (`cel25c-60_A`). Else NeedsRelink (no card / ambiguous / unknown set). `imageUrl = image?.plus("/high.webp")`.

**`PTCG_SET_OVERRIDES`** (complete per probe): base6:lc, bp:bog, cel25c:cel25cc, fut20:fut2020, hsp:hgssp, mcd11:2011bw, mcd12:2012bw, mcd14:2014xy, mcd15:2015xy, mcd16:2016xy, mcd17:2017sm, mcd18:2018sm, mcd19:2019sm, mcd21:2021swsh, mcd22:2022swsh, me1..me5:me01..me05, me2pt5:me02.5, me55:30th, me55c:30th-c, pgo:swsh10.5, rsv10pt5:sv10.5w, zsv10pt5:sv10.5b, sm35:sm3.5, sm75:sm7.5, sv1..sv9:sv01..sv09, sv3pt5:sv03.5, sv4pt5:sv04.5, sv6pt5:sv06.5, sv8pt5:sv08.5, swsh12pt5:swsh12.5, swsh12pt5gg:swsh12.5gg, swsh35:swsh3.5, swsh45:swsh4.5, swsh45sv:swsh4.5sv, tk1a:tk-ex-latia, tk1b:tk-ex-latio, tk2a:tk-ex-p, tk2b:tk-ex-m.

**T4 apply:**
1. `BackupExporter.export()`. On Failure, abort with zero writes.
2. `database.withTransaction`: run the SQL below for each Mapped and upsert alias (MAPPED, `legacyImageUrl`). NeedsRelink -> alias row only.
3. Report.

Dry-run reads refs from the 6 columns; the name and species come from the same row. It fetches each set once (concurrency 4). **Re-link list** = legacy ids still in any table, labelled by location. Fix by reassigning through the normal flow; PC orphans get "Remove". Settings shows the legacy count.

**Per Mapped id, inside the transaction:** run `UPDATE <t> SET <id>=:new, <img>=COALESCE(:img,<img>) WHERE <id>=:old` for each of these tables:

| Table | id column | image column |
|---|---|---|
| `main_binder` | `assignedCardId` | `assignedCardImageUrl` |
| `unown_binder` | `assignedCardId` | `assignedCardImageUrl` |
| `connecting_art_slot` | `cardId` | `cardImageUrl` |
| `secondary_binder` | `cardId` | `cardImageUrl` |

Then the two PK tables:
- `personal_collection_cache`: run `UPDATE OR IGNORE` with the same SET, then `DELETE … WHERE cardId=:old`. If the canonical row was already cached, the legacy row is dropped.
- `personal_collection_entry`, in Kotlin: `owned = old || new`; language = the first non-"EN" of (new, old); upsert the merged row; delete the old one.

**T5:**
- `searchTcgdexAllLanguages(name): List<TcgCard>` = TCGdex en name search + `tcgdexAllLangsByDex(resolveDex(..))`, no ptcg. `refreshPokemon` uses it (keep `refreshAll`'s per-section `runCatching`).
- `Language`: add `TH("Thai")`, `ID("Indonesian")` (stored as a string, so no migration). `Language.fromCardId(id)`: `tcgdex_ja_`->JA, `ko`->KO, `zh-tw`/`zh-cn`->ZH, `th`->TH, `id`->ID, else EN. It replaces the `?: "EN"` fallback at `PersonalCollectionViewModel.kt:68` and `PublishRepository.kt:402`.
- `setOwned` must preserve the existing entry's language (today the `upsertEntry` resets it to "EN"); new rows use `fromCardId`.

**T6:**
- `searchByNumber(q)`: `filterCards("en", localId=q)`, exact-filtered. If that is empty and q matches `^(SVP|MEP)(\d+)$`, query set `svp`/`mep` with the digits (probe: `localId=208&set.id=eq:svp` -> `svp-208`).
- `searchPromosByName`: `rarity=Promo&name=`.
- Backfill: `tcgdexApi.getCard("en", id.removePrefix("tcgdex_"))`.

## Data inventory

| Column(s) | Key | slotId in binder.json | If remapped unhandled |
|---|---|---|---|
| `main_binder.assignedCardId`, image | PK pokemonId | pokemonId | diff REPLACED flood |
| `unown_binder.assignedCardId`, image | PK letterId | `unown-X` | REPLACED |
| `connecting_art_slot.cardId`, image | PK auto | `ca-g-i` | REPLACED |
| `secondary_binder.cardId`, image | PK auto | `pokemonId-id` | restore dups |
| `personal_collection_entry.cardId` | **PK** | (owned/lang) | owned orphaned |
| `personal_collection_cache.cardId`, image | **PK** | **= cardId** | REMOVED+ADDED |

All of these columns go into binder.json and the JSON/`.db` exports, so old files keep ptcg ids; T3 and a re-run of T4 handle them. Web `app.js` uses only `cardId` truthiness, `imageUrl` and `slotId` (badges). Prefs/DataStore: no ids.

## Migration design

- Current version **9** -> **10**. `MIGRATION_9_10`: ``CREATE TABLE IF NOT EXISTS `card_id_alias` (`legacyId` TEXT NOT NULL PRIMARY KEY, `canonicalId` TEXT, `status` TEXT NOT NULL, `legacyImageUrl` TEXT, `migratedAt` INTEGER NOT NULL)``. Additive.
- Backup: the additive DDL needs no hook. The T4 rewrite forces `BackupExporter.export()` (`.db` + JSON into `filesDir/exports/`). `BackupImporter` is unchanged: a v9 `.db` import migrates, then its legacy ids appear in the T4 count.
- **One-way door:** once v10 is on the phone, installing any v9 build wipes the DB (`fallbackToDestructiveMigration`).
- Test: `Migration9to10Test` uses the `Migration8to9Test` pattern (`exportSchema=false`, so there is no MigrationTestHelper). The emulator rehearsal on a real DB copy is the real proof.

## Rollback

1. Primary: Settings -> Import -> the pre-apply `.db` from `exports/` (existing atomic swap + safety copy). Skyler shares it off-phone before applying.
2. Surgical: alias rows hold `legacyId`, `canonicalId`, `legacyImageUrl`, enough to reverse by SQL by hand. No rollback UI.
3. Code: revert T3–T7. Keep T1 (the schema stays v10).

## Test plan

JVM: T2 fixtures = the probe cases above (`sv8-48`, `base1-4`, `sv3pt5-166`, `swshp-SWSH260`, `g1-RC3`, `mcd19`, `cel25c-60_A` byName, `ecard2-50` NeedsRelink, name mismatch, null set Transient, non-legacy prefixes, null image). T3–T5 = one test per acceptance criterion, plus for T4: Transient leaves data untouched, the PC entry collision merges, and a second run is a no-op. androidTest (emulator): `Migration9to10Test` + `CardIdRemapDao` SQL on in-memory Room.

## Verification procedure

1. **Phone:** Settings -> Export; share the `.db` + `.json` to the PC. Fallback: `MSYS_NO_PATHCONV=1 adb exec-out run-as com.skyler.pokedexbinder cat databases/pokedex_binder.db > copy.db`, plus the `-wal` and `-shm` files (debug build only).
2. **Emulator `pokedex_test`:**
   - Install the T1–T4 build and import `copy.db`.
   - Dry-run; compare its counts with the Problem table.
   - Apply; spot-check 10 slots (image, name, owned).
   - Publish to a test repo, or with the Discord webhook blanked.
3. **Phone:** export, dry-run, apply, publish. Confirm no REPLACED flood and that the site renders.

## User stories

- As Skyler, I keep every card, image and owned flag when ptcg dies, with a dry-run and auto-backup first.
- As Skyler, unmatched cards are listed for me to re-link and never dropped; old backups still restore.
- As Skyler, PC shows ja/ko/zh/th/id printings, tagged with the right language.

## Acceptance criteria

- [ ] Given a v9 DB, when the T1 build opens it, then the rows are intact and `card_id_alias` exists.
- [ ] Given legacy ids, when the dry-run runs, then 0 writes happen and counts show per table and per outcome.
- [ ] Given Apply, when the export fails, then 0 rows change.
- [ ] Given Apply succeeds, then Mapped ids are replaced in all 6 columns, images change only where TCGdex has one, name/set/language are untouched, and alias rows exist.
- [ ] Given no unique match, then the card keeps its legacy id and image and is listed for re-link with its location.
- [ ] Given the first publish after Apply, then there are 0 remap-caused deltas (Discord/changelog).
- [ ] Given an old backup with ptcg ids, when restored on a remapped device, then there are 0 Card History dups and PC owned lands on TCGdex ids.
- [ ] Given a fresh install plus an old backup, when Normalize runs, then the state equals the remapped device.
- [ ] Given T5, when PC refreshes, then language-tagged non-English printings appear, owned survives, and 0 ptcg calls are made.
- [ ] Given T7, then there are 0 `pokemontcg` hits in main and Skyler's `testDebugUnitTest --rerun-tasks` is green.

## Out of scope

- Image caching/re-hosting for the 6% with no TCGdex image (T8, only if hosts die).
- Remapping `tcgcsv_*` ids.
- Deriving `language` from ids for Pokédex/CA/Unown/Card History (user-set is authoritative).
- A dedicated re-link picker (the existing assign flows cover ~2 cards).
- Scanner tuning beyond removing the ptcg steps.

## Dependencies

- TCGdex `/v2/en/sets/{id}`, `/v2/{lang}/cards` filters `localId`/`dexId`/`rarity`/`set.id` (all probed, working).
- Existing `BackupExporter`, `BackupImporter`, `DatabaseBackupManager`.
- Skyler: every Gradle run, the emulator rehearsal, the phone apply, and the open questions.

## Open questions

- [ ] PC languages: all 6, or a subset? Probe for the 4 sections: ja +99 cards (~80% imageless), zh-tw 21, th 12, id 5, ko 2, zh-cn 1. Default: all 6. **Skyler, before T5.**
- [ ] Does the phone have PC owned toggles? Published data shows 0. The dry-run answers this. **Skyler, at rehearsal.**
- [ ] Delete ptcg in Dec 2026 (recommended), or keep it as a scanner fallback until 2027-02-15? **Skyler, before T7.**
- [ ] Rehearsal publish target: a temp repo, or the webhook blanked? **Skyler, before T4 apply.**

## Risks and mitigations

| Risk | L | I | Mitigation |
|---|---|---|---|
| **Data loss during apply** | Low | **High** | Mandatory export (abort on failure), single transaction, emulator rehearsal on a copy, alias ledger |
| A v9 APK installed after v10 wipes the DB | Med | **High** | One-way-door rule; export before installs; never revert the schema |
| Silent mis-map | Low | Med | Number + name/species check; ambiguous -> re-link; 0 mismatches in 1151 |
| Discord flood on first publish | High w/o T3 | Med | T3 before T4, tested |
| PC owned already orphaned by the flip-flop | Med | Med | Remap maps legacy entries; TCGdex-only PC keeps ids stable |
| ptcg CDN dies (69+ images have no TCGdex copy) | Med | Low | Keep the URL; T8 TCGCSV backfill |
| Phone ids outside the sample, or a TCGdex outage | Med | Low | Dry-run first; unknowns -> re-link; Transient touches nothing |

## Smallest shippable increment

**First commit: T1+T2+T3.** Zero behaviour change, because the alias is empty and canonicalize is a no-op. **MVP = T1–T4:** remap applied, ptcg still present as a fallback. **Future:** T5 (PC multilingual) -> T6 -> T7 (delete ptcg) -> T8 (images, only if needed).

## Friction Notes

- ptcg `/cards/{id}` is mostly 5xx, so the mapper is designed TCGdex-only.
- The GitHub-raw `binder.json` is the best stand-in for the phone DB; the local `web/` copy is stale.
- Side bug found: `setOwned` resets language to "EN" (folded into T5).
