# Multi-Binder System Design
**Date:** 2026-05-24
**Status:** Approved

---

## Overview

Expand the app from a single Pokédex binder + one hidden secondary binder into a four-binder system accessible via a navigation drawer. Two new binders are added — Connecting Art and Personal Collection. Connecting Art is fully user-managed: the user creates groups, assigns card slots via search, then confirms ownership. Personal Collection is API-driven: slots are pre-populated from pokemontcg.io by Pokémon name, greyed out until the user confirms ownership.

---

## Binders

| # | Name | Type |
|---|---|---|
| 1 | Pokédex | Existing — unchanged |
| 2 | Connecting Art | New — user-managed groups, card search per slot |
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

The connecting art landscape spans hundreds of groups across the entire TCG history with no reliable API tag to identify them. This binder is therefore fully user-managed: the user defines each group themselves after browsing collector resources, then assigns cards to slots via the existing card search.

### Creating a Group
A FAB ("+" button) opens a bottom sheet where the user:
1. Enters a group name (e.g. "2024 Teeziro Panoramic")
2. Selects grid dimensions from a set of presets: 1×2, 1×3, 1×4, 2×2, 3×3

This creates the group with empty slots and appends it to the bottom of the binder.

### Screen Layout
Vertically scrollable list of sections. Each section has:
- A header label (the group name) with a delete button
- A grid of card slots sized exactly `rows × cols`, rendered in row-major order
- Groups can be reordered via long-press drag (same mechanism as Card History)

### Slot States & Behaviour
- **Empty:** Placeholder tile with a "+" icon. Tap → opens card search screen → selecting a card assigns it to the slot (greyed, unowned)
- **Assigned / Unowned:** Card image with dimming overlay. Tap → bottom sheet: "Mark as Owned", "Remove Card", "Back"
  - "Mark as Owned" → slot lights up full colour
  - "Remove Card" → slot returns to empty
- **Owned:** Card image at full colour. Tap → bottom sheet: "Mark as Unowned", "Remove Card", "Back"

### Data
Room tables:

`connecting_art_group`:
```
id:       Int (PK, autoGenerate)
name:     String
rows:     Int
cols:     Int
position: Int
```

`connecting_art_slot`:
```
id:           Int (PK, autoGenerate)
groupId:      Int (FK → connecting_art_group.id)
slotIndex:    Int  -- row-major, 0-based
cardId:       String?
cardName:     String?
cardImageUrl: String?
owned:        Boolean (default false)
```

No API fetching required — card metadata (name, image URL) is stored directly in the slot row when the user assigns a card via search. Deleting a group cascades to delete all its slots.

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
- `connecting_art_group`
- `connecting_art_slot`
- `personal_collection_cache`
- `personal_collection_entry`

### New DAOs
- `ConnectingArtDao` — CRUD for groups and slots, cascade delete, reorder positions
- `PersonalCollectionDao` — cache management + insert/query/update owned state

### New Repositories
- `ConnectingArtRepository` — manages group/slot CRUD, delegates card search to existing `CardSearchRepository`
- `PersonalCollectionRepository` — fetches cards by Pokémon name (with Pocket filter), manages cache and owned state

---

## New Screens & ViewModels

| Screen | ViewModel | Notes |
|---|---|---|
| `ConnectingArtScreen` | `ConnectingArtViewModel` | User-managed groups; create/reorder/delete groups, assign cards to slots, mark owned |
| `PersonalCollectionScreen` | `PersonalCollectionViewModel` | Renders sections per Pokémon, triggers cache refresh |

---

## Navigation Routes Added

```kotlin
object ConnectingArt : Screen("connecting_art")
object PersonalCollection : Screen("personal_collection")
```

---

## Out of Scope

- Additional custom binder types beyond the four defined here
- Sorting or filtering within Personal Collection sections
- Offline fallback for initial Personal Collection population (requires network on first open)
- Any changes to the Pokédex binder or Card History binder behaviour
