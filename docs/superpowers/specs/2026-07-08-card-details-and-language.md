# Spec — Card Details Panel + Per-Card Language (2026-07-08)

**Status:** APPROVED v3 (Skyler, 2026-07-08) — ready for dev-team-pipeline
**Owner:** Skyler
**Scope:** Android app (schema, UI, publish) + web viewer (lightbox)

## Problem Statement

The public binder site shows only a card's image — clicking a card zooms it, nothing more.
Skyler collects printings in multiple languages (SEA/CJK region), but the binder can't record
or display which language a physical card is, nor any card details (artist, set, number,
rarity). Visitors and Skyler himself can't tell what a displayed card actually *is* beyond
its picture.

## Goals

1. Clicking any published card on the website shows: Pokémon name, artist, set name, card
   number, **rarity** ("Illustration Rare", "Special Illustration Rare", "Rare Holo VMAX",
   ...), and language — **all in English**.
2. Skyler can tag any assigned card's language in the app (default English), from a fixed
   SEA+CJK list, with a "version name" (the foreign version's name rendered in English,
   e.g. JP "Lost Abyss" where the EN release is "Lost Origin") that is **auto-suggested from
   a bundled mapping table when a language is picked**, and always editable.
3. All API-derivable details (artist, number, rarity — alongside the existing name/set) fill
   automatically: on new assignment from the response already in hand, and for existing
   cards via the on-launch backfill. Zero manual data entry except the language tag and
   (when the suggestion misses) the version name.

## Non-Goals (v1)

- **Localized/native-script display.** Everything renders in English (Skyler's call,
  2026-07-08). No PokeAPI/TCGdex localization calls — TCGdex verified NOT to expose
  JP/TH/ID/ZH sets under its `en` locale, so English renderings of version names are not
  API-obtainable; the suggestion table is curated data bundled with the app.
- **Per-card scraping for version data.** Bulbapedia knows each card's exact foreign
  printing, but only as wiki pages; scraping at tag-time is fragile and slow. Table is
  set-level, best-effort, editable — not per-card exact.
- **Swapping the card printing/image to the tagged language.** The assignment stays the
  English card; the tag records what Skyler physically owns.
- **Website admin/login or any website-side editing.** The app remains the single writer of
  published data (dual-writer sync conflicts + PAT-in-browser risk).
- **Language-aware card search.** Search stays English; tagging happens after assignment.

## User Stories

- As a site visitor, I click a card and see its name, artist, set, number, rarity, and
  language, so I know what the card is beyond its image.
- As Skyler, I open a filled slot, pick "Japanese", and the version-name field pre-fills
  with "Lost Abyss" (from the mapping table, because the card's set is Lost Origin); I hit
  save and the site shows it after the next publish.
- As Skyler, when the table has no entry (old set, promo, ambiguous mapping), the field
  stays blank or shows the ambiguity, and I type the name myself.
- As Skyler, I never tag most cards — they display as English by default with all details
  auto-filled.
- As a site visitor clicking a card published before this feature, I see the fields that
  exist and "—" for those that don't; nothing breaks.

## Design

### Auto-fill boundaries (what's automatic vs. manual)

| Field | Source | When |
|---|---|---|
| Card name, set name | pokemontcg.io (already persisted) | assignment + existing backfill |
| Artist, number, **rarity** | pokemontcg.io — already in every response; DTO just doesn't declare `rarity`/persist any of them | assignment + backfill (new) |
| Language | manual picker | tag-time |
| Version name | **suggestion table** prefill → manual edit | tag-time |

### Version-name suggestion table

Bundled static asset (JSON in `assets/`), keyed by English set identifier → per-language
version name in English:

```json
{ "Lost Origin": { "JA": "Lost Abyss", "TH": "Lost Abyss", ... },
  "Astral Radiance": { "JA": "Time Gazer / Space Juggler", ... } }
```

- Coverage v1: Sword & Shield era + Scarlet & Violet era EN main sets (~30 sets), compiled
  from Bulbapedia's expansion lists during implementation. Older eras/promos: no entry.
- EN sets merging multiple JP sets (e.g. Astral Radiance ← Time Gazer + Space Juggler) store
  the combined string; Skyler edits down to the right one for his card.
- SEA languages (TH/ID) mirror the JP set structure, so their English renderings are the JP
  names; ZH/KO likewise unless the table says otherwise. The table may simply have fewer
  per-language overrides and fall back to the `JA` entry within a set's record.
- Missing set or language → field stays blank, manual entry. The table is data, not code —
  extending it later is a one-file change.

**Known limitation (accepted):** set-level suggestion can be wrong for cards that came from a
different JP subset than the table's main mapping. The field is a prefill, not a fact — the
editable field is the correction mechanism.

### Language codes

`EN` (default), `JA`, `TH`, `ID`, `ZH-TW`, `ZH-CN`, `KO` — stored as literals; the viewer
maps them to display labels ("Japanese", "Thai", ...).

### Schema (Room v7 → v8)

`main_binder` gains 5 additive columns:
- `assignedCardArtist TEXT` (nullable)
- `assignedCardNumber TEXT` (nullable)
- `assignedCardRarity TEXT` (nullable)
- `cardLanguage TEXT NOT NULL DEFAULT 'EN'`
- `versionName TEXT` (nullable — only meaningful when language ≠ EN)

`secondary_binder` (Card History) gains parity fields — its entries publish to binder.json
too. Connecting Art / Personal Collection follow only if their entries appear in binder.json
(Planner to verify; scope follows the data).

### DTO change

`TcgCardDto` adds `val rarity: String? = null` (pokemontcg.io returns it in every card
object; no query/`select` changes needed — verified 2026-07-08). `TcgCard` domain model
gains `rarity`.

**TCGdex-sourced cards** (`tcgdex_`-prefixed IDs): the brief search DTO has no rarity, and
the backfill's pokemontcg.io-by-ID lookup can't resolve them. v1: their rarity stays null →
"—" on site. P1 adds a TCGdex full-card fetch for these rows in the backfill.

### binder.json (additive fields per slot)

`cardArtist`, `cardNumber`, `cardRarity`, `cardLanguage`, `versionName` — all optional. Old
snapshots without them must render fine. Restore-from-snapshot overlays these alongside the
existing assignment fields, preserving the overlay-not-rebuild rule.

### App UI

- Slot detail screen (filled slots): "Language" row → dialog with the 7 options + version-name
  text field. Picking a non-EN language pre-fills the field from the suggestion table (based
  on the slot's stored set name); field remains editable; switching back to EN clears
  language-specific data. Pure local operation — no network, no spinner.
- No change to assignment/search flows beyond persisting the extra fields.

### Backfill

`BackfillCardNamesUseCase` already fetches each assigned card by ID on launch; extend it to
populate artist/number/rarity where null. Same pacing/race-guard rules. Language/versionName
are never backfilled — manual-only.

### Web lightbox

Detail panel inside the existing lightbox: card/Pokémon name, "Illus. {artist}",
"{set} · {number}", rarity, language pill (always shown, "English" included), and version
name when present ("JP version: Lost Abyss"). Missing data → "—". Display Case styling
(gold accents, `.latest-pill` type scale). Lightbox behavior otherwise unchanged. Applies to
ALL published cards.

## Requirements

### P0 — Must have
- [ ] Room v8 migration adds the 5 columns to `main_binder` (+ `secondary_binder` parity);
      existing rows `cardLanguage='EN'`, nulls elsewhere. Migration test included.
- [ ] `rarity` added to DTO + domain model; new assignments persist artist/number/rarity.
- [ ] Backfill fills artist/number/rarity for already-assigned pokemontcg.io rows;
      idempotent; re-run safe; `tcgdex_` rows skipped without error.
- [ ] Language picker + version-name field on slot detail; suggestion-table prefill on
      language selection; EN default; re-selectable; non-EN data cleared on switch to EN.
- [ ] Suggestion table asset covering SwSh + SV era EN main sets, JA entries minimum
      (TH/ID/ZH/KO fall back to the JA entry unless overridden).
- [ ] Publish carries the new fields; metadata-only changes on the same cardId must NOT
      create REPLACED changelog entries — verify explicitly.
- [ ] Restore overlays the new fields; regression test extends the existing overlay test.
- [ ] Lightbox detail panel on ALL published cards, per-field "—"/"English" fallback; works
      against old binder.json snapshots.
- [ ] Full unit suite green; site verified against real + sample data via preview tools.

### P1 — Nice to have
- [ ] TCGdex full-card fetch in backfill for `tcgdex_`-sourced rows (rarity + artist).
- [ ] Language tag + version name for Card History entries (UI in that screen).
- [ ] Small language badge on the grid card corner for non-English cards (site).

### P2 — Future
- Full printing swap (change image to the tagged language's actual printing) — the language
  column is the hook; schema must not block it.
- Per-card-exact version data (would need community-wiki data; revisit only if set-level
  suggestions prove too wrong in practice).

## Success Criteria

Personal project — no metrics theater. Done means: Skyler tags ≥1 card per non-English
language he owns, at least one version name arrives via auto-suggest and one via manual
entry, publishes, and every detail panel on the live site (including rarity) shows correct
data with correct fallbacks, verified by clicking through on his phone + desktop.

## Open Questions

- None blocking. (Planner to verify: whether Connecting Art / Personal Collection entries
  appear in binder.json — scope follows the data.)

## Timeline / Phasing

Single pass: one dev-team-pipeline run for the app side (schema → capture → backfill →
suggestion table → publish/restore), then the web lightbox panel (small second run), then
unit tests + preview verification. P1 items only if the P0 pass lands cleanly.
