# Spec — Connecting Art + Personal Collection Publish Support, Unown Nav Move (2026-07-12)

**Status:** APPROVED — ready for dev-team-pipeline
**Owner:** Skyler
**Scope:** Android app (publish, restore, nav/UI) — no schema/migration changes

## Problem Statement

Connecting Art and Personal Collection are fully built and used in the app, but neither has
ever appeared in `binder.json` — `PublishRepository.buildSnapshot()` only knows about Pokédex,
Card History, and Unown. The website project already has a second shelf reserved for "anything
that isn't pokedex/cardHistory," so real published data is the only missing piece. Separately,
Unown's standalone nav-drawer entry should move — Skyler wants it reachable from the Personal
Collection screen instead of its own drawer item.

## Goals

1. Connecting Art groups and Personal Collection sections publish to `binder.json` when Skyler
   taps Publish, and appear on the public site's second shelf.
2. A binder with zero cards in it never appears on the site — no empty shelf clutter.
3. Personal Collection's publish shows every card the search cache found (not just owned ones),
   with an owned/unowned flag per card so the site can dim unowned cards exactly like the app
   does today.
4. Unown is reachable from the Personal Collection screen instead of the nav drawer, with zero
   change to its underlying one-card-per-slot data model, migration, or TCGCSV search flow.

## Non-Goals

- **Auto-publish on card add/toggle** — explicitly rejected by Skyler this round. Publish stays
  manual-only (Settings → Publish button), exactly as it works today. No debounce mechanism, no
  WorkManager, no new scheduling code.
- **Per-binder Settings toggles for Connecting Art / Personal Collection** — rejected in favor
  of automatic content-based inclusion (see Design). No new `SettingToggleItem` rows.
- **Room schema/migration changes** — Connecting Art and Personal Collection already have their
  tables from v7. This spec is publish/restore wiring plus one additive, backward-compatible
  field on the existing `SnapshotSlot` model (see Design) — no `PokedexDatabase` version bump.
- **Rebuilding Unown's data model** — explicitly rejected. Unown keeps its Room v8 table, DAO,
  repository, screen, and TCGCSV fallback exactly as built. Only its nav entry point moves.
- **Inline-rendering Unown's 28-slot grid inside PersonalCollectionScreen's card grid** —
  rejected as an engineering trade-off (different interaction models: fixed-slot-assign vs.
  search-cache-with-toggle). Unown stays its own full screen, just reached differently.
- **Website-side changes** — out of this project's scope entirely (separate repo/project); this
  spec only ensures correct binder IDs/shapes so the already-built second-shelf logic picks
  them up with no further coordination.

## User Stories

- As Skyler, when I tap Publish, any Connecting Art group with at least one assigned card and
  any Personal Collection section with at least one cached card appear on the public site,
  automatically, no extra settings to configure.
- As Skyler, if I haven't put anything in Connecting Art or Personal Collection yet, publishing
  doesn't create empty/junk entries on the site.
- As a site visitor, I see every card Personal Collection's search found for a given Pokémon,
  with owned ones full-brightness and unowned ones dimmed — same as Skyler sees in the app.
- As Skyler, I tap "Unown" from the Personal Collection screen (not the nav drawer) and land on
  the exact same 28-slot assign screen that exists today.
- As a site visitor viewing a binder published before this feature shipped, restore and re-view
  work fine — old snapshots without these binders don't break anything.

## Design

### Publish — content-gated inclusion, no toggle

`PublishRepository.buildSnapshot()` gains two new unconditional branches (no `PublishConfig`
field, no Settings UI) — each only emits a `SnapshotBinder` if it has at least one non-empty
section:

- **Connecting Art** (`BINDER_ID_CONNECTING_ART` = `"connectingArt"`): one section per
  `ConnectingArtGroup`, slots from that group's `ConnectingArtSlot` rows (grid position → slot
  id, assigned card fields mirror the main Pokédex binder's slot shape). A group with zero
  assigned slots is skipped entirely; if ALL groups are empty, the whole binder is omitted.
- **Personal Collection** (`BINDER_ID_PERSONAL_COLLECTION` = `"personalCollection"`): one
  section per `PokemonSection` (Charizard, Celebi, Leafeon, Tangela, Minccino & Cinccino),
  slots built from that section's full `PersonalCollectionCache` rows (not filtered to owned) —
  slot id = `cardId` (no fixed slot count, unlike Pokédex/Unown). A section with zero cache rows
  is skipped; if all 5 are empty, the whole binder is omitted.

Planner to verify exact `ConnectingArtSlot`/`PersonalCollectionCache` field names by reading the
entities directly (grid position field, cache row shape) before wiring `buildSnapshot`.

### Schema addition — `SnapshotSlot.owned`

Add `owned: Boolean = true` to the existing `SnapshotSlot` data class (`publish/model/
BinderSnapshot.kt`). Default `true` is backward-compatible — every existing binder (Pokédex,
Card History, Unown) has assignment-equals-ownership semantics, so their slots always publish
`owned = true` implicitly, no call-site changes needed there. Only Personal Collection's new
`buildSnapshot` branch sets `owned` from the real `PersonalCollectionEntry.owned` flag per card
(defaulting `false` for cache rows with no matching entry row). This is additive JSON, not a
schema/migration change — old snapshots without the field deserialize fine (Moshi default).

### Restore

`RestoreRepository` gains two new overlay blocks, following the exact overlay-not-rebuild
pattern already used for Pokédex/Unown:

- Connecting Art: overlay onto existing `ConnectingArtSlot` rows by matching group+position:
  snapshot has a card → assign it; snapshot slot empty but local has a card → clear it. Only
  runs if the snapshot actually contains a `"connectingArt"` binder (old snapshots: skip
  cleanly, no crash, existing local data untouched).
- Personal Collection: overlay the `owned` state onto `PersonalCollectionEntry` rows by
  `cardId` from the snapshot's slots (this is a restore of *ownership state*, not the cache
  itself — the cache is always network-refreshed separately via pull-to-refresh, restore should
  not fabricate cache rows for cards the live search no longer returns). Only runs if the
  snapshot contains a `"personalCollection"` binder.
- Extend the existing overlay regression test suite to cover both new binders, mirroring the
  Unown overlay test added this session.

### Diff / metadata-only republish

`computeDiff` already generalizes over `binders.flatMap{sections}.flatMap{slots}` — no changes
needed. Verify explicitly (existing P0 pattern from prior specs) that toggling a Personal
Collection card's owned state between two publishes produces the correct diff entry type (this
is a genuine content change, unlike the "metadata-only, don't REPLACE" rule that applied to the
card-details spec — an owned-state flip is a real state change and SHOULD show in the diff).

### Unown nav placement

- Remove the standalone `"Unown"` `NavigationDrawerItem` from `AppNavigation.kt`'s drawer
  content.
- Add a 6th entry to `PersonalCollectionScreen`'s section list/jump-chip row (built this
  session), alongside Charizard/Celebi/Leafeon/Tangela/Minccino & Cinccino. Tapping it does NOT
  expand an inline card grid like the other 5 sections — it navigates to the existing
  `Screen.Unown` route (the full 28-slot grid screen), exactly as the drawer item used to.
  Visually distinguish it from the other 5 (e.g. a trailing chevron/arrow icon indicating
  "opens a new screen" rather than "expands in place") so the different interaction pattern
  isn't a surprise.
- `Screen.Unown`/`Screen.UnownSearch` routes, `UnownBinderScreen`, `UnownBinderViewModel`,
  `UnownBinderRepository`/`Dao`/`Entity`, and the TCGCSV search integration are **unchanged**.

## Requirements

### P0 — Must have
- [ ] `buildSnapshot()` emits a `connectingArt` binder when at least one Connecting Art slot is
      assigned anywhere; omits it entirely when empty.
- [ ] `buildSnapshot()` emits a `personalCollection` binder when at least one cache row exists
      in any of the 5 sections; omits it entirely when empty.
- [ ] Personal Collection's published slots include ALL cached cards (owned and unowned), each
      with an accurate `owned` boolean.
- [ ] `SnapshotSlot.owned` added with default `true`; existing binders' JSON output unaffected
      (still implicitly owned, no explicit-false regression for Pokédex/Card History/Unown).
- [ ] Restore overlays Connecting Art assignments and Personal Collection owned-state
      correctly; old snapshots without these binders don't crash and leave local data
      untouched.
- [ ] No new Settings UI, no new `PublishConfig` fields, no auto-publish/debounce code anywhere.
- [ ] "Unown" removed from the nav drawer; added as a navigate-away entry within
      `PersonalCollectionScreen`. Unown's own screen/data/search flow unchanged and still fully
      functional when reached this way.
- [ ] Full unit suite green, including new tests for: content-gated inclusion (empty → omitted,
      non-empty → included), the `owned` flag round-tripping through publish+restore, and the
      Unown nav-entry-point relocation.

### P1 — Nice to have
- None identified — this is a tightly-scoped wiring task.

### P2 — Future
- Auto-publish (explicitly deferred this round, not designed here — would need its own spec if
  revisited).
- Per-binder publish toggles, if content-gating alone proves insufficient in practice.

## Success Criteria

Personal project — no metrics theater. Done means: Skyler adds a card to a Connecting Art group
and a card to a Personal Collection section, taps Publish, and both appear correctly on the
live site (Personal Collection showing the dimmed/undimmed state accurately); an empty
Connecting Art group or Personal Collection section with zero results doesn't appear at all;
Unown is reachable and fully functional from the Personal Collection screen with the drawer
item gone — all verified by Skyler on-device.

## Open Questions

- None blocking. (Planner to verify exact `ConnectingArtSlot` grid-position field name and
  `PersonalCollectionCache`/`PersonalCollectionEntry` join shape while implementing.)

## Timeline / Phasing

Single dev-team-pipeline pass: publish wiring (two new buildSnapshot branches + SnapshotSlot.owned)
→ restore wiring (two new overlay blocks) → nav change (remove drawer item, add PC entry point)
→ full unit tests. No migration, no device-only step beyond the usual on-device publish/restore
verification Skyler already does for every binder.
