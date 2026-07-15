# Spec — Unown Binder Section + TCGCSV Search Fallback (2026-07-10)

**Status:** APPROVED — ready for dev-team-pipeline
**Owner:** Skyler
**Scope:** Android app (schema, UI, publish/restore) + card search repository

## Problem Statement

Unown (28 letter forms: A-Z, !, ?) has no home — it was originally deferred as 28 slots
crammed into the main Pokédex binder and never built. Separately, some real cards Skyler owns
(e.g. CLB Mr. Mime) exist in neither of the app's two search sources (pokemontcg.io, TCGdex),
so they can't be assigned to a slot at all today.

## Goals

1. Unown gets its own dedicated section in the nav drawer, structurally identical to the main
   Pokédex binder's assignment mechanic (one specific card per slot) but scoped to exactly 28
   fixed slots.
2. Unown participates in publish/restore like the main Pokédex binder — it appears in
   `binder.json` and survives restore-from-snapshot.
3. When a card search returns nothing from pokemontcg.io (and the merged TCGdex results), and
   the user explicitly searches again, the app additionally queries TCGCSV live so cards absent
   from both existing APIs (e.g. CLB Mr. Mime) become assignable.

## Non-Goals

- **Manual card entry** (typed name/photo for unfindable cards) — explicitly rejected by Skyler;
  TCGCSV is the only new source, not a manual fallback.
- **Bundled/cached TCGCSV snapshot** — live fetch only, no static asset, no refresh pipeline.
- **TCGCSV as a first-pass source** — it only runs after pokemontcg.io+TCGdex return nothing
  AND the user retries; it is never queried on the initial search.
- **Unown section in Personal Collection's data model** (auto-populated multi-card sections,
  owned/unowned toggle) — rejected; Unown uses the main-binder single-assignment mechanic.
- **Connecting Art or Personal Collection gaining publish/restore support** — out of scope here;
  this spec only adds Unown to the publish/restore surface, it doesn't touch the other two
  sections' existing app-only status.
- **Card History (secondary_binder) TCGCSV/Unown parity** — not requested; no changes to that
  section.

## User Stories

- As Skyler, I open the nav drawer and see an "Unown" entry alongside Pokédex / Connecting Art
  / Personal Collection / Card History.
- As Skyler, I tap the Unown section and see a 28-slot grid (A-Z, !, ?), each slot empty or
  holding one assigned card, same interaction as the main Pokédex binder (tap empty slot →
  search → assign; tap filled slot → view/reassign/clear).
- As Skyler, I search "Unown A" and assign the card pokemontcg.io returns, same flow as any
  other Pokémon.
- As Skyler, I search for CLB Mr. Mime, pokemontcg.io and TCGdex return nothing, I press search
  again, and the app additionally checks TCGCSV and returns the product so I can assign it.
- As Skyler, after I publish, the Unown binder appears in `binder.json` with its own slots,
  and a later restore-from-snapshot overlays Unown the same way it overlays the main Pokédex
  binder.
- As a site visitor on a future website revision that adds an Unown section, the published data
  is already there waiting — this project just needs to emit it correctly.

## Design

### Unown section — data model

Follows the `main_binder` / `MainBinderEntry` pattern exactly, as a new sibling table, not a
variant of Personal Collection's cache-and-toggle model:

- New entity `UnownBinderEntry` (table `unown_binder`): PK `letterId` (e.g. `"A"`..`"Z"`,
  `"!"`, `"?"` — 28 fixed string IDs), same nullable `assignedCard*` columns as
  `MainBinderEntry` (cardId, name, imageUrl, artist, number — **no rarity/language/versionName
  columns**, since the 2026-07-08 card-details-and-language spec was separately dropped from
  scope; if that feature is revisited later, `unown_binder` gets the same columns added the same
  way `main_binder` would).
- Seeded once at DB creation/first-launch with all 28 letter rows empty, same mechanism as
  `BinderRepository.seedIfEmpty` seeds `main_binder` from a static list (28 hardcoded letter IDs
  needs no JSON asset — a plain `listOf("A".."Z" + "!" + "?")` in code is sufficient, unlike the
  1025-entry Pokédex which needs `pokemon_slots.json`).
- New `UnownBinderDao` mirroring `MainBinderDao`'s shape (observe all, get by id, assign card,
  clear card).
- New `UnownBinderRepository` mirroring `BinderRepository`'s seed/assign/clear methods.

### Unown section — UI

- New `Screen.Unown` route + `UnownBinderScreen.kt` composable, `UnownBinderViewModel.kt` —
  structurally a copy of the main Pokédex binder screen/viewmodel, scoped to 28 slots instead
  of ~1025, no section grouping needed (one flat 28-cell grid, suggest `GridCells.Fixed(4)` or
  similar — Skyler/implementer's call on exact column count).
- New `NavigationDrawerItem("Unown")` in `AppNavigation.kt`, positioned after "Pokédex" and
  before "Connecting Art" (ordering: Pokédex, Unown, Connecting Art, Personal Collection, Card
  History).
- Tap empty slot → existing card search UI (reuse `ManualSearchScreen`/`CardSearchRepository`,
  same assignment flow as main Pokédex binder) → assign. Tap filled slot → existing
  view/reassign/clear pattern.

### Room migration (v7 → v8)

- New `MIGRATION_7_8` in `PokedexDatabase.kt`: `CREATE TABLE IF NOT EXISTS unown_binder (...)`
  matching `UnownBinderEntry`'s columns, following the exact `MIGRATION_6_7` template (raw
  `execSQL` DDL string).
- Add `UnownBinderEntry::class` to the `entities = [...]` list, bump `version = 8`.
- Register `MIGRATION_7_8` in `DatabaseModule.kt`'s `.addMigrations(...)` chain.
- New `Migration7to8Test.kt` mirroring `Migration6to7Test`'s two-method pattern: (1) hand-rolled
  v7-shape DB, replay migration SQL, assert new table's `PRAGMA table_info` matches expected
  columns and no existing data is touched; (2) build post-migration DB via
  `Room.inMemoryDatabaseBuilder`, exercise the real `UnownBinderDao` (seed, assign, clear) to
  confirm the new table is reachable through the DAO/repository layer.
- Seed-on-first-launch: 28 letter rows inserted the first time `UnownBinderRepository` detects
  an empty table (mirrors `BinderRepository.seedIfEmpty`).

### Publish (binder.json)

- New `BINDER_ID_UNOWN` / `BINDER_NAME_UNOWN` constants alongside the existing
  `BINDER_ID_POKEDEX` / `BINDER_ID_CARD_HISTORY`.
- New `PublishConfig.publishUnown: Boolean` toggle (default true, same shape as
  `publishPokedex`/`publishCardHistory`) in `PublishSettingsRepository`.
- `PublishRepository.buildSnapshot(...)` gains a new constructor dependency on
  `UnownBinderDao`/`Repository` and a third `if (config.publishUnown) { ... }` block building a
  `SnapshotBinder` from the 28 `unown_binder` rows — single flat section (no sub-grouping needed,
  unlike the Pokédex binder's `sectionNameFor` grouping), following the exact pattern of the
  existing `publishPokedex`/`publishCardHistory` blocks (`PublishRepository.kt:260-293`).
- `computeDiff` needs no changes — it already generalizes over `binders.flatMap{sections}.flatMap{slots}`.
- Metadata-only republish must not spuriously mark Unown slots REPLACED — same rule as the
  existing binders, verify explicitly in tests.

### Restore-from-snapshot

- `RestoreRepository` currently hardcodes `BINDER_ID_POKEDEX` lookup (line 62) — extend with a
  second lookup for `BINDER_ID_UNOWN`, overlay onto `unown_binder` rows the same way the
  existing code overlays `main_binder` rows (restored/cleared/untouched accounting).
- Extend the existing overlay regression test to cover the Unown binder.

### Card search — TCGCSV fallback

- `CardSearchRepository` gains a new dependency, `TcgcsvApi` (Retrofit interface hitting
  tcgcsv.com's per-category/group JSON product endpoints — exact endpoint shape to be confirmed
  by the implementer against tcgcsv.com's current API during implementation; Pokémon category/
  group IDs referenced in prior research, e.g. group 23323).
- New method `searchTcgcsvByName(name: String): List<TcgCard>` — maps TCGCSV product JSON to the
  existing `TcgCard` domain model (name, number, set name, image URL where available, no rarity
  since TCGCSV product listings don't carry TCG rarity data — leave null/absent). Card IDs
  prefixed `"tcgcsv_"` following the existing `"tcgdex_"` collision-avoidance pattern
  (`CardSearchRepository.kt:136`).
- Wrapped in the same `safeSearch`-style try/catch as the existing sources — a TCGCSV failure
  must not throw, just contribute an empty list.
- **Trigger point**: `ManualSearchViewModel.retry()` (line 58-60) — when `retry()` is called and
  the previous `SearchState.Results` was empty, additionally call
  `cardSearchRepository.searchTcgcsvByName(lastQuery)` and merge those results in. First-pass
  `search(query)` (line 33) is unchanged — it still only queries pokemontcg.io+TCGdex
  concurrently, exactly as today. TCGCSV is retry-on-empty only, never part of the initial
  concurrent fetch.
- Applies to the plain name-search path only (`searchByName`); no changes to
  `searchByParsedInfo`, `searchByNumberAndTotal`, `searchPromosByName`, or dex-number search —
  those are unaffected unless the implementer finds during build that the retry path needs to
  cover them too (flag as open question below if so).

## Requirements

### P0 — Must have
- [ ] Room v8 migration adds `unown_binder` table (28-row seed on first launch). Migration test
      (`Migration7to8Test`) covers DDL correctness + DAO reachability, following the
      `Migration6to7Test` template.
- [ ] `UnownBinderDao`/`Repository`/`ViewModel`/`Screen` implemented, mirroring the main Pokédex
      binder's single-card-per-slot assignment mechanic.
- [ ] "Unown" entry added to the nav drawer, routes correctly.
- [ ] Unown wired into `PublishRepository.buildSnapshot` (new `BINDER_ID_UNOWN`,
      `PublishConfig.publishUnown` toggle) and `RestoreRepository`'s overlay logic.
- [ ] Metadata-only republish does not spuriously REPLACE Unown slots — verified explicitly.
- [ ] Restore overlay regression test extended to cover Unown.
- [ ] `CardSearchRepository.searchTcgcsvByName` implemented, wired into
      `ManualSearchViewModel.retry()` as an empty-results-only fallback, card IDs
      `tcgcsv_`-prefixed.
- [ ] CLB Mr. Mime (or an equivalent currently-missing card) confirmed assignable end-to-end via
      the TCGCSV fallback path, on-device.
- [ ] Full unit suite green; new tests for the TCGCSV merge/fallback logic (mocked API,
      verifying it's NOT called on first search and IS called on retry-after-empty).

### P1 — Nice to have
- [ ] TCGCSV results also considered for `searchByParsedInfo`'s broad-fallback path (scanner/
      quick-scan flows), not just `ManualSearchViewModel`'s explicit retry.
- [ ] Unown slot grid visual treatment (distinct styling from the main Pokédex grid) if Skyler
      wants it to feel visually distinct rather than identical.

### P2 — Future
- Card-details/language columns on `unown_binder` if the 2026-07-08 spec is ever revisited.
- Publish/restore support for Connecting Art and Personal Collection (explicitly out of scope
  here; would be its own future spec).

## Success Criteria

Personal project — no metrics theater. Done means: Unown section exists with 28 assignable
slots, at least one Unown card is assigned and survives a publish + restore cycle, and CLB Mr.
Mime (or equivalent) is successfully found and assigned via the TCGCSV retry fallback — verified
by Skyler on-device.

## Open Questions

- **TCGCSV exact endpoint shape** (engineering, non-blocking) — implementer confirms
  tcgcsv.com's current JSON endpoint structure/category-group IDs at build time; prior research
  noted group 23323 for Pokémon but this should be re-verified live rather than assumed stale.
- **Should TCGCSV fallback extend to scanner/quick-scan flows (`searchByParsedInfo`)?**
  (Skyler, non-blocking) — deferred to P1 above; ask if CLB-Mr.-Mime-style gaps show up via
  scanning, not just manual search.

## Timeline / Phasing

Single dev-team-pipeline pass: schema (Room v8 + Unown entities/DAO/repo) → UI (screen/
viewmodel/nav) → publish/restore wiring → TCGCSV search integration → full unit tests +
on-device verification (migration test requires a real device/emulator per existing precedent).
