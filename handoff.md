# Handoff — Session 21 (2026-10-01 → 10-03)

## 1. Goals

Live-test the scanner on the phone, then fix what it showed: wrong cards for Japanese/other-language
cards, a useless art hash, and Gemini's free-tier quota blocking scans.

## 2. Current State

- **Unit tests 428/428 green** (Skyler ran `testDebugUnitTest --rerun-tasks` after each stage; fresh XML
  read by me: 0 failures, 0 errors, 0 skipped).
- **Latest build is on the phone** (installed 2026-10-03 00:32). Live-verified that night: Mega Malamar ex
  (`M6-048`), Klawf (`SV1S-088`), Black Kyurem (`SV7a-011`), Wishiwashi ex (`M6-021`) all accepted
  automatically; a Simplified-Chinese Palafin ex fell to the English fallback, forced low confidence
  (`path=CROSS_LANGUAGE`). Gemini answered all five (quota had reset).
- **Not live-tested:** Castform/Furfrou under the new confidence rule, any Thai / Indonesian / Korean /
  Traditional-Chinese card, manual search with the extra languages, whether the "closest English
  printings" header actually renders (only the log line `crossLanguage=true` was seen).
- Committed in two commits this wrapcon (code+tests, then docs/scripts/pipeline archives). Skyler's own
  `CLAUDE.md` / `app/CLAUDE.md` edits and `scripts/scan_capture.log` were deliberately left out.

## 3. Active Files

- `repository/CardSearchRepository.kt` — TCGdex-first cascade, language handling, name verification, cross-language flag
- `domain/` — `ArtHash.kt` (new), `PerceptualHasher.kt`, `SmartThresholdUseCase.kt`, `CardScanProvider.kt` / `ClaudeCardScanner.kt` / `CardScanShared.kt` (new), `GeminiCardScanner.kt`
- `data/remote/TcgdexApi.kt` (+ DTO, `NetworkModule.kt`), `ui/scanner/ScannerViewModel.kt` / `ScannerScreen.kt`, settings (Anthropic key field)
- `scripts/claude_scan_bakeoff.py` — Haiku-vs-Sonnet accuracy/cost test; **never run** (see Failed Attempts)
- Pipeline archives for the four runs: `.pipeline-archive/2026-10-01-*`, `2026-10-02-*`

## 4. Changes Made

| # | Change | Why (evidence) |
|---|---|---|
| 1 | Name verification on `number+total` / `broad-number` (`matchingParsedName`) | Black Kyurem 11/64 resolved to Jungle Snorlax #11 via the species-blind query |
| 2 | Art-region DCT pHash (`ArtHash.kt`, 63 bits) replaces 8x8 whole-image hash; capture art rect via `cardRectInCrop` | Whole-crop hash ranked the true card 84th/75th of 100 (it hashed the hand/background); art-crop ranked it 2nd/1st |
| 3 | TCGdex is the primary source; `pokemontcg.io` kept as a fallback tagged `POKEMONTCG-IO-FALLBACK: remove before 2027-03-01` | `pokemontcg.io` shuts down 2027-03-01 (docs.pokemontcg.io); it has no Japanese cards |
| 4 | Gemini/Claude return `language` + `printedName`; six languages queried (ja, ko, zh-tw, zh-cn, th, id); ids `tcgdex_<lang>_<id>`, set names `<SET> (<LANG>)` | Japanese cards were absent or wrong |
| 5 | Confidence: `IDENTITY_PINNED` (dex+number+total), `IDENTITY_PINNED_NAME` (printedName-verified), ceiling `PINNED_HASH_CONTRADICTION_DISTANCE=45`, single-candidate hash limit 16→20 | Correct cards scored hash 8–34, wrong 24–30: the hash cannot discriminate, identity rests on metadata |
| 6 | Dual provider: Gemini first, any non-cancellation error → Claude Sonnet (`claude-sonnet-4-6`); Anthropic key field in Settings | Free-tier Gemini 429'd after ~17 scans; 7 of 8 scans then ran on Claude fine |
| 7 | `crossLanguage` flag: an Asian-language scan with only English results is forced low confidence, labelled, hash-ordered | A stand-in English printing is not the card owned; never auto-accept |
| 8 | TCG Pocket (`/tcgp/`) cards dropped from TCGdex results | Mobile-game cards polluted candidates |

Docs: `gaps.md` (session 21 section), `project-overview.md` (new "Scanner Matching Pipeline" section).

## 5. Failed Attempts

- **Gradle loopback wall again** (7th session): Claude could not build; Skyler ran every build.
- **SIFT feature matching** tested as an alternative to the hash: no better (Furfrou rank 2, wrong card on top) and would need OpenCV. Dropped.
- **TCGdex zh-cn set `CSV9C`/`CSV9.5C`** exist with `official: 208` but contain 0 cards, so Palafin cannot be matched there. No code fix possible.
- `scripts/claude_scan_bakeoff.py` failed on Skyler's Python (`ModuleNotFoundError: anyio`); fix is `python -m pip install --upgrade anthropic pillow`. Not rerun.
- Coder agent hit the Claude session rate limit once mid-task; the retry resumed from the partial tree.
- Git Bash mangles `/storage/...` adb paths (needs `MSYS_NO_PATHCONV=1`).

## 6. Next Steps

1. **Live-scan the untested cases** (see Current State) and read `adb logcat -s ScannerMatch:*`. Confirm the cross-language header renders on screen.
2. **Rotate the Gemini API key** (printed in logcat and archived transcripts earlier; still unrotated). Blocked on Skyler.
3. **Plan the `pokemontcg.io` removal before 2027-03-01.** `searchByName` is still ptcg-first because Personal Collection stores owned status by card id; switching sources changes ids (`xyevo-25` → `tcgdex_xyevo-25`) and would drop Skyler's owned toggles. Needs an id-remap migration (`planning` + pipeline).
4. **Remove the TEMP DIAGNOSTIC debug-capture save** in `ScannerViewModel.kt` (every scan writes a 2-3 MB JPEG; 47 files / ~100 MB had accumulated) once calibration scans are done.
5. Optional: run the bake-off (needs `ANTHROPIC_API_KEY` in Skyler's own shell) to choose Haiku vs Sonnet on cost.
6. Small fixes parked in `gaps.md`: `lowConfidenceCards` filters tail by equality not id; trim the dex step's language fan-out if misses feel slow.

Blocked on Skyler: items 1–2, and the decision on item 3's scope (should Personal Collection include non-English printings?).
