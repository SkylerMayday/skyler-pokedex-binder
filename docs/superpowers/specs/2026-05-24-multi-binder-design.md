# Multi-Binder System Design
**Date:** 2026-05-24
**Status:** Approved

---

## Overview

Expand the app from a single Pokédex binder + one hidden secondary binder into a four-binder system accessible via a navigation drawer. Two new binders are added — Connecting Art and Personal Collection — each with pre-populated, API-backed card slots that are greyed out until the user confirms ownership.

---

## Binders

| # | Name | Type |
|---|---|---|
| 1 | Pokédex | Existing — unchanged |
| 2 | Connecting Art | New — curated catalogue + API |
| 3 | Personal Collection | New — API-driven by Pokémon name |
| 4 | Card History | Existing secondary binder — unchanged |

---

## Global Rule: Exclude Pocket Cards

Every pokemontcg.io API query across the entire app appends `-set.series:Pocket` to exclude Pokémon TCG Pocket cards. These are digital-only and cannot be physically collected. This filter is applied in `CardSearchRepository` (and any new repository methods) so it is enforced consistently everywhere — manual search, quick scan, personal collection population, all of it.

---

## Navigation

### Drawer
A `ModalNavigationDrawer` wraps the root `Scaffold`. A hamburger icon in each screen's top app bar opens it. The drawer lists all 4 binders; tapping one navigates to it and closes the drawer. The active binder is highlighted.

### Bottom Bar
The bottom nav retains **Pokédex** and **Scan** (Scan only when camera scanner is enabled in Settings). The Secondary Binder tab is removed — Card History is accessed via the drawer only.

### Start Destination
Pokédex remains the default start destination.

---

## Settings Changes

- `showSecondaryBinder` toggle removed from `SettingsScreen`, `SettingsViewModel`, `AppSettings`, and `SettingsRepository`. Card History is always present in the drawer.

---

## Connecting Art Binder

### Catalogue
A Kotlin object `ConnectingArtCatalogue` defines all known connecting art groups. Each group:

```kotlin
data class ConnectingArtGroup(
    val name: String,        // e.g. "SV: 151 — Legendary Birds Trio"
    val rows: Int,           // e.g. 1 for a horizontal strip, 3 for a 3×3
    val cols: Int,           // e.g. 3 for a trio, 3 for a 3×3
    val cardIds: List<String> // pokemontcg.io card IDs, row-major order
)
```

Groups are ordered top-to-bottom on screen. New sets are added to this file and shipped via app update.

### Screen Layout
Vertically scrollable list of sections. Each section has:
- A header label (the group name)
- A grid of card slots sized exactly `rows × cols`
- Cards rendered in row-major order matching `cardIds`

### Slot Behaviour
- **Unowned:** Card image displayed with a dimming overlay (greyed out)
- **Owned:** Card image at full colour, no overlay
- **Tap (unowned):** Bottom sheet with "Add Card" and "Back"
  - "Add Card" → marks owned, sheet dismisses
  - "Back" → sheet dismisses, no change
- **Tap (owned):** Bottom sheet with "Remove Card" and "Back"

### Data
Room tables:

`connecting_art_card_cache`:
```
cardId:      String (PK)
name:        String
imageUrl:    String
setName:     String
```

`connecting_art_entry`:
```
cardId: String (PK)
owned:  Boolean
```

Card metadata is fetched from pokemontcg.io by ID on first open and persisted in `connecting_art_card_cache`. The catalogue can span 200–400+ card IDs across all groups; caching avoids cold-fetching on every launch and makes the binder usable offline after first load. No pull-to-refresh — the catalogue is static and the cache is considered permanent until an app update changes the catalogue.

---

## Personal Collection Binder

### Pokémon Sections
Five sections, in this order:
1. Charizard
2. Celebi
3. Leafeon
4. Tangela
5. Minccino & Cinccino

### Card Population
On first open (and on manual refresh), the app queries pokemontcg.io with `name:<pokemon> -set.series:Pocket` for each Pokémon. Results are cached in Room so subsequent opens are instant. The cache is refreshed via a pull-to-refresh gesture on the Personal Collection screen (standard `PullRefreshIndicator` / `pullRefresh` modifier). New cards released after the cache was populated will appear after a refresh.

For the Minccino & Cinccino section, both names are queried and their results are merged and displayed together under one section header.

### Screen Layout
Vertically scrollable list of sections. Each section has a sticky header with the Pokémon name and a 3-column grid of cards, sorted by set release date descending (newest first).

### Slot Behaviour
Identical to Connecting Art: greyed overlay until owned, same bottom sheet with "Add Card" / "Remove Card" / "Back".

### Data
Room tables:

`personal_collection_cache`:
```
cardId:      String (PK)
pokemonKey:  String  -- e.g. "charizard", "minccino_cinccino"
name:        String
imageUrl:    String
setName:     String
releaseDate: String
```

`personal_collection_entry`:
```
cardId: String (PK)
owned:  Boolean
```

---

## Data Layer Changes

### Database Migration
Version 5 → 6. Adds four new tables:
- `connecting_art_card_cache`
- `connecting_art_entry`
- `personal_collection_cache`
- `personal_collection_entry`

### New DAOs
- `ConnectingArtDao` — insert/query/update owned state for connecting art entries
- `PersonalCollectionDao` — cache management + insert/query/update owned state

### New Repository
- `ConnectingArtRepository` — fetches card details from pokemontcg.io by ID, merges with local owned state
- `PersonalCollectionRepository` — fetches cards by Pokémon name (with Pocket filter), manages cache and owned state

---

## New Screens & ViewModels

| Screen | ViewModel | Notes |
|---|---|---|
| `ConnectingArtScreen` | `ConnectingArtViewModel` | Renders sections from catalogue + DB owned state |
| `PersonalCollectionScreen` | `PersonalCollectionViewModel` | Renders sections per Pokémon, triggers cache refresh |

---

## Navigation Routes Added

```kotlin
object ConnectingArt : Screen("connecting_art")
object PersonalCollection : Screen("personal_collection")
```

---

## Out of Scope

- User-created custom binders (not in this version)
- Sorting or filtering within Personal Collection sections
- Offline fallback for initial Personal Collection population (requires network on first open)
- Any changes to the Pokédex binder or Card History binder behaviour
