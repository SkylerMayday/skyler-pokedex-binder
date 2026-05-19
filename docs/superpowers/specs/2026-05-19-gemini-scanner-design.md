# Gemini AI Scanner — Design Spec
**Date:** 2026-05-19  
**Project:** PokedexBinderV2  
**Status:** Approved

---

## Overview

Replace the existing ML Kit OCR scanning engine with Gemini 2.5 Flash vision AI. The scanner extracts structured card data from a photo, queries the Pokémon TCG API with multiple fields, and uses on-demand perceptual hashing to disambiguate among close candidates. The binder, assignment, and text search flows remain untouched.

---

## Architecture

### Files Deleted
| File | Reason |
|---|---|
| `domain/OcrCardParser.kt` | Replaced by Gemini |
| `domain/CardNameTranslator.kt` | Gemini handles all languages natively |

### Files Added
| File | Purpose |
|---|---|
| `domain/GeminiCardScanner.kt` | Calls Gemini Vision API, returns `ParsedCardInfo` |
| `domain/PerceptualHasher.kt` | On-demand hash comparison among TCG API candidates |

### Files Modified
| File | Change |
|---|---|
| `ui/scanner/ScannerViewModel.kt` | Swap ML Kit for Gemini; absorb slot assignment logic |
| `ui/scanner/ScannerScreen.kt` | New error states; Retry + Search Manually buttons |
| `ui/settings/SettingsScreen.kt` | Add Gemini API key input field |
| `repository/SettingsRepository.kt` | Persist API key in DataStore |
| `ui/navigation/AppNavigation.kt` | Wire `ScannerScreen` into nav; add scan/search choice on slot tap |
| `ui/secondarybinder/SecondaryBinderScreen.kt` | Add scan option to the add card entry point |

### Untouched
`QuickScanScreen`, `QuickScanViewModel`, `CardSearchRepository`, `AssignCardUseCase`, `BinderRepository`, all database/Room code, `ManualSearchScreen`.

---

## Entry Points

### Main Binder — Empty Slot Tap
Tapping an empty slot shows a bottom sheet with two options:
- **Scan Card** → navigates to `ScannerScreen` with `slotId` + `pokemonName` as nav args
- **Search Manually** → navigates to existing `QuickScanScreen` (unchanged)

Occupied slot tap behaviour is unchanged (goes to `SlotDetailScreen`).

### Secondary Binder — Add Card
The add card button opens a bottom sheet with two options:
- **Scan Card** → navigates to `ScannerScreen` without a `slotId` (result added directly to secondary binder)
- **Search Manually** → navigates to existing `ManualSearchScreen` (unchanged)

---

## Scan Flow

```
Camera preview → user taps Capture
        ↓
GeminiCardScanner.scan(imageBitmap)
  → Gemini 2.5 Flash extracts: name, number, setTotal, hp, artist (nulls for unreadable fields)
        ↓
CardSearchRepository multi-field query
  → returns 0–N candidate TcgCard objects (each has an imageUrl)
        ↓
PerceptualHasher.findBestMatch(candidates, capturedBitmap)
  → downloads candidate images on-demand
  → resizes to 16×16 grayscale, computes 64-bit average hash
  → returns candidate with lowest Hamming distance
        ↓
High confidence  → CardConfirm state: show card image + details, Confirm / Wrong Card
Low confidence   → CardSelection state: scrollable list of candidates
        ↓
User confirms → AssignCardUseCase.assign(slotId, card)
             → navigate back to binder (or add to secondary if no slotId)
```

---

## Gemini Integration

**Model:** Gemini 2.5 Flash  
**Transport:** REST via existing Retrofit instance (no Android SDK)  
**Image encoding:** Base64 in the request body  

**Prompt contract — response is strict JSON:**
```json
{
  "name": "Pikachu",
  "number": "025",
  "setTotal": "185",
  "hp": "60",
  "artist": "Atsuko Nishida"
}
```
Any field Gemini cannot read returns `null`. More non-null fields = more precise TCG API query.

**API key:** User enters their Gemini API key in Settings. Stored in DataStore via `SettingsRepository`. If the key is absent when the camera is opened, the scanner shows a prompt to go to Settings before the viewfinder appears.

**Rate limits (Gemini 2.5 Flash free tier):** 500 requests/day. Scanning the full 1025-Pokémon dex takes ~3 days at this limit, which is acceptable for a personal binder app.

---

## Perceptual Hashing

- No pre-built database. All hashing is on-demand per scan.
- Only the 3–5 candidates returned by the TCG API are fetched and hashed.
- Implementation uses Android's `Bitmap` only — no third-party library.
- Algorithm: average hash (aHash) — resize to 16×16 grayscale, compare each pixel to mean, produce 64-bit fingerprint.
- Hamming distance threshold determines high vs low confidence.

---

## Error States

| Scenario | State shown | Actions |
|---|---|---|
| API key not set | Prompt: "Set your Gemini API key in Settings" | Go to Settings |
| Gemini call fails (non-429) | Generic error message | Retry / Search Manually |
| Rate limited (HTTP 429) | "You've hit today's scan limit. Try again tomorrow." | Search Manually only |
| No cards found | Not found message | Search Manually |
| Completely unreadable image | Generic error | Retry / Search Manually |

---

## Settings Screen

A new "Gemini API Key" field is added to `SettingsScreen`. Input is obscured (password field). The key is saved to DataStore on change. A short note below the field tells the user where to get a free key (Google AI Studio).

---

## Constraints

- **Free tier:** 500 Gemini scans/day — no in-app rate limiting enforced; 429 response is handled gracefully.
- **Internet required:** Both Gemini and the TCG API require connectivity. Offline use is not supported.
- **Personal use:** No multi-user considerations. API key belongs to the user running the app.
