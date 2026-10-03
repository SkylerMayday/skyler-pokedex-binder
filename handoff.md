# Handoff — Session 21 (2026-10-01 → 10-03), final wrapcon

## 1. Goals

Live-test the scanner, fix wrong cards for Japanese/other-language cards, replace the useless art hash, survive
Gemini's free-tier quota, then move the whole app to TCGdex (the `pokemontcg.io` API shuts down 2027-03-01) with a
wipe-and-rescan instead of a migration.

## 2. Current State

- **Unit tests 505/505 green**, `compileDebugAndroidTestKotlin` green (Skyler ran both; fresh XML read by me).
  Final multi-lens review: 89 / 94 / 94 (min 89, ship).
- **Phone has an OLDER build** (installed 2026-10-03 00:32). Verified live on that build: Mega Malamar ex, Klawf, Black
  Kyurem, Wishiwashi ex accepted automatically; a Simplified-Chinese Palafin fell to the "closest English printings"
  list; Gemini worked, Claude Sonnet fallback worked earlier (7 of 8 scans on 2026-10-01).
- **NOT on the phone / never run on a device:** TCGdex-only search, Personal Collection with all languages, Reset
  collection data, Card-images sync, the Discord toggle, `TcgcsvJapanSource` (M6 images), the Palafin header, any
  Thai / Indonesian / Korean / Traditional-Chinese card, the androidTest SQL cases.
- **Committed:** the scanner overhaul (earlier commits `dcfe2d5`, `8eaf3b9`, `8482561`) and, in this wrapcon, the
  TCGdex-only rebuild (see git log). Skyler's own `CLAUDE.md` / `app/CLAUDE.md` edits and `scripts/scan_capture.log`
  are deliberately NOT committed.

## 3. Active Files

- `repository/CardSearchRepository.kt` (TCGdex-only), `data/remote/` — `TcgdexApi`, `TcgdexIds` (id-scheme source of truth),
  `TcgdexSetCatalog`, `TcgcsvJapanSource` (+ DTOs, `TcgcsvApi` category-85 calls)
- `domain/` — `SyncCardImagesUseCase`, `BlankImageResolver` seam, `JapanTcgcsvResolver`, `CardScanProvider`, `SmartThresholdUseCase`
- `data/local/CollectionResetDao.kt`, `ui/settings/` (Danger zone, Card images, Discord switch), `PersonalCollectionRepository`
- `.pipeline-archive/2026-10-0*` — specs, change logs, review verdicts of every run

## 4. Changes Made (since the first wrapcon today)

| # | Change | Why |
|---|---|---|
| 1 | `pokemontcg.io` removed (API, DTOs, backfill use case, every fallback step); scanner/manual search/Quick Scan/PC are TCGdex-only; `TcgdexUnavailableException` when TCGdex is entirely down | API shuts down 2027-03-01, no Japanese data |
| 2 | Personal Collection covers English + ja/ko/zh-tw/zh-cn/th/id, de-duplicated by id; partial refreshes never drop a language's cached rows | Skyler: PC includes non-English printings |
| 3 | Settings **Reset collection data** (forced export → type `RESET` → one transaction; keeps slots, settings, keys, connecting-art layouts; no Room change) | Skyler OK'd wiping; he will rescan everything |
| 4 | **Card images sync** (manual button + once/24 h auto at app start, 400-id cap) fills blank image URLs in 5 tables | M6 has no TCGdex images yet; saved cards kept blank forever |
| 5 | **TcgcsvJapanSource** (TCGplayer category 85): cached join by set abbreviation + number numerator, `_400w` images, `FetchBudget(3)`, 15 min / 6 h failure cooldown | Fills the M6 gap; 83 of 116 JA sets join |
| 6 | **Post updates to Discord** switch, default OFF | First publish after rescan would spam ~1,000 false "replaced" messages |
| 7 | Debug-capture saving removed from `ScannerViewModel` | Was writing ~3 MB per scan |
| 8 | Review fixes: id scheme consolidated in `TcgdexIds`, dead ptcg/release-date code deleted, restored cascade-order tests | Three-lens review |

Docs: `gaps.md` (session 21 part 2), `project-overview.md` (card-search + Scanner Matching Pipeline sections).

## 5. Failed Attempts

- **Gradle loopback wall** (8th session): Claude could not build; Skyler ran every build/test.
- **Opus rate limits** cut off three planner/reviewer/coder runs; each was re-run (`resets` times shown in the error).
- **SIFT** as a hash alternative: no better, needs OpenCV. Dropped.
- **TCGdex zh-cn sets `CSV9C`/`CSV9.5C`** exist with 0 cards: Palafin ex 228/208 cannot be matched there. No code fix.
- **tcgcsv.com blocked the app's User-Agent (401) once** during a 370-request probe burst, then recovered; UA left unchanged.
- `scripts/claude_scan_bakeoff.py` never run (Skyler: "stick to sonnet", so not needed).
- Git Bash mangles adb paths (`MSYS_NO_PATHCONV=1`) and Bash heredocs with quoted Kotlin fail intermittently (use Write).

## 6. Next Steps

1. **Install the latest build and try it:** `powershell.exe -File scripts\install_debug.ps1`. Check Settings shows "Danger zone", "Card images" and the Discord switch.
2. **Run `connectedDebugAndroidTest` with the phone connected** (executes the Reset / fillBlankImage SQL tests that have only been compiled), then use **Reset collection data** (it exports first) and **rescan** the collection.
3. Check an M6 card (Mega Malamar ex / Wishiwashi ex) shows an image and the hash `distance=` is a real number; press **Sync card images** and confirm a second run fetches nothing.
4. Check the cross-language header on a Palafin / other unmatched Asian-language card.
5. Fix the one open P2 (`gaps.md`): remember failed TCGCSV Japan product downloads (per-group retry-at + test).
6. **Rotate the Gemini API key** (printed in logcat and archived transcripts earlier; still unrotated) — blocked on Skyler.
7. Later, if wanted: PTCG-database for Korean / Traditional Chinese (`gaps.md`); `security-crypto` replacement; English TCGCSV name index.

Blocked on Skyler: items 1-4 (phone), 6 (key). Publish only AFTER the rescan is done (the first publish after a wipe writes a
big changelog; the Discord switch is OFF by default).
