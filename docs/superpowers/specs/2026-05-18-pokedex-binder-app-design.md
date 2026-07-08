# Pokédex Binder Android App — Design Spec
**Date:** 2026-05-18

---

## Overview

A native Android app for tracking a physical Pokédex card binder. Users can scan physical Pokémon TCG cards with their camera to identify them, then assign those cards to Pokémon slots in their binder. A secondary binder tracks all replaced cards in chronological order.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM + Repository |
| Local DB | Room (SQLite) |
| API Client | Retrofit + OkHttp |
| Card Data | pokemontcg.io API |
| OCR (on-device) | ML Kit Text Recognition |
| Camera | CameraX |
| Image Loading | Coil |
| Dependency Injection | Hilt |

OCR runs fully on-device — no internet required for card scanning. Internet is only needed to fetch card data and images from pokemontcg.io after a match is found.

---

## Architecture

```
UI Layer (Jetpack Compose screens)
    ↕
ViewModel Layer (state management, business logic)
    ↕
Repository Layer (coordinates local DB + remote API)
    ↕
Data Sources
  ├── Room DB (local SQLite — binder state, offline)
  └── Pokémon TCG API (pokemontcg.io — card search + images)
```

---

## Pokémon Slot Scope

The main binder is pre-populated with slots in this fixed order, defined by a curated JSON file bundled with the app:

1. **Base 1025** — all Pokémon by National Pokédex number
2. **Regional / form variants** — Alolan, Galarian, Hisuian, Paldean, etc. — included only if at least one card exists for that variant on pokemontcg.io
3. **Mega Evolutions** — all Mega forms, card or no card
4. **Gigantamax forms** — only those with a visually distinct G-Max design (not just an oversized version of the base form). V-Max cards are treated as the card representation of G-Max forms.

This list is hardcoded at build time — not fetched dynamically — keeping the binder structure stable and fully offline.

---

## Data Layer

### Room Database — `main_binder` table

| Column | Type | Notes |
|---|---|---|
| `pokemon_id` | TEXT (PK) | e.g. `"charizard-mega-x"`, `"pikachu-gmax"` |
| `pokemon_name` | TEXT | Display name |
| `dex_order` | INTEGER | Sort order in binder |
| `assigned_card_id` | TEXT? | pokemontcg.io card ID, null if slot is empty |
| `assigned_card_image_url` | TEXT? | URL of clean card image |

### Room Database — `secondary_binder` table

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER (PK, autoincrement) | Insertion order = chronological order |
| `pokemon_id` | TEXT | Which Pokémon slot this card came from |
| `card_id` | TEXT | pokemontcg.io card ID |
| `card_image_url` | TEXT | Clean card image URL |

---

## Card Scanning Flow

```
1. User opens scanner (camera or manual search)

── CAMERA PATH ──
2. CameraX opens viewfinder
3. User captures photo of card
4. ML Kit OCR extracts text from image (on-device)
5. App parses card name + set number from OCR output
6. App searches pokemontcg.io with parsed text

── MANUAL PATH ──
2. User types card name or set number
3. App searches pokemontcg.io directly

── SHARED FROM HERE ──
7. Results returned from API (list of matching cards)
8. Smart threshold:
   - High confidence (1 result, or top result score >> others)
     → auto-selects, shows card preview with "Looks wrong? Change it" option
   - Low confidence (multiple close matches)
     → shows list for user to pick from
9. User confirms (or picks correct card)
10. App identifies which Pokémon the card belongs to (Tag Team cards with multiple Pokémon default to the first listed; user can manually reassign to the other)
11. Checks main_binder for that Pokémon's slot:
    - Empty → assigns card directly
    - Occupied → shows current card, asks "Replace?"
      → Yes: old card moves to secondary_binder, new card assigned to main_binder
      → No: cancels, nothing changes
```

---

## Screens

### 1. Main Binder (Home)
- 3×3 scrollable grid of Pokémon slots in Pokédex order
- Each slot: assigned card image, or Pokémon sprite + empty indicator if unassigned
- Tap slot → Slot Detail screen
- FAB → Scanner screen

### 2. Slot Detail
- Full-size card image, or "No card assigned" state
- Pokémon name + Pokédex number header
- Buttons: "Scan a card" / "Search manually"
- If occupied: "Replace" button visible

### 3. Scanner Screen
- CameraX viewfinder with capture button
- Tab to switch to Manual Search
- Post-capture: OCR result + matched card preview
- Confirm / Change buttons

### 4. Manual Search Screen
- Text input for card name or set number
- Results list with card thumbnails
- Tap to select → proceeds to assignment flow

### 5. Secondary Binder
- Chronological list of replaced cards (newest first, by `id` DESC)
- Each entry: card image thumbnail, Pokémon name, card name

### Navigation
- Bottom nav bar with 2 tabs: **Main Binder** and **Secondary Binder**
- Scanner launched via FAB on Main Binder screen

---

## Out of Scope
- iOS support
- Pricing data / TCGPlayer integration
- Card collection value tracking
- User accounts or cloud sync
- Multiple binder profiles
