# Spec — Perceptual-Hash Confidence Path for the Scanner Match Pipeline

Source: `docs/specs/2026-09-13-scanner-hash-confidence.md` (approved by Skyler — build now). This
file is the Coder-facing subset. P0 tasks 1-6 + P1 tasks 7-8 only. **P2 (network-download timing
re-eval) is explicitly out of scope for this build.**

## TL;DR

`PerceptualHasher.computeHash()` has a live bit-mapping bug (`1L shl i` over 256 pixels wraps into
64 bits, causing real hash collisions between different cards). Fix it, then use the now-trustworthy
hash to add a second path to high confidence (margin-gated, for when Gemini can't OCR the card
number) and to stop blindly trusting a single search candidate without a visual check.

## Problem

Two live, root-caused failures (session 14/15, real device):
1. Furfrou misread as "Hisuian Zorua" — `SmartThresholdUseCase.evaluate()` and
   `PerceptualHasher.findBestMatch()` both auto-trust a search result with exactly 1 candidate,
   skipping any visual check. Gemini's wrong species name narrowed the search to 1 wrong candidate.
2. Furfrou/Castform repeatedly got `parsedNumber = null` (Gemini read the species, not the tiny
   printed number) → falls to a manual-pick list every time, because `evaluate()` never consults
   the perceptual hash at all when the number is missing.
3. Independently found while investigating #2: `computeHash()` (`PerceptualHasher.kt:18-33`) loops
   over 256 pixels of a 16x16 downsample but does `1L shl i` — JVM `Long.shl` masks the shift to
   its low 6 bits, so pixel `i` and `i+64/128/192` OR onto the same bit. Verified empirically
   against 16 real card images from `api.pokemontcg.io`: several genuinely different cards
   (multiple Pikachu prints, Eevee, Detective Pikachu) hashed to the identical
   `0xffffffffffffffff`. Silently degrading every hash-based pick since `498d035` (2026-05-20). An
   8x8-resize (64 pixels → real 64-bit capacity, no wraparound) fixed it in the same empirical
   replay: avg pairwise distance 6.0/64 → 21.5/64, zero exact collisions.

## Proposal

1. Fix `computeHash()` by resizing to 8x8 (64 pixels, matches `Long`'s real 64-bit capacity) instead
   of 16x16 — smallest correct fix, no hash-width redesign needed.
2. Restructure `findBestMatch()` to compute Hamming distance for **every** candidate (not just
   `minByOrNull` the winner), exposing both the winning distance and the margin to the runner-up.
   This naturally removes the `candidates.size == 1` short-circuit (P1 task 7) — the single-candidate
   case flows through the same distance computation instead of a special case.
3. Add a margin-gated high-confidence path to `SmartThresholdUseCase.evaluate()`, and narrow its
   own `cards.size == 1` short-circuit to an absolute-distance check, both driven by the new
   `HashMatchResult` from step 2.
4. Extend `ScannerMatch` logging to show the margin/distance and which confidence path fired.

### Exact changes

**`app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt`**

```kotlin
data class HashMatchResult(val card: TcgCard, val distance: Int, val margin: Int)
```
Top-level, above the class. `margin` = runner-up distance minus best distance; `Int.MAX_VALUE`
sentinel when there's no runner-up (exactly 1 candidate).

- `computeHash`: `createScaledBitmap(bitmap, 16, 16, false)` → `(bitmap, 8, 8, false)`,
  `IntArray(256)` → `IntArray(64)`, `getPixels(pixels, 0, 16, 0, 0, 16, 16)` →
  `getPixels(pixels, 0, 8, 0, 0, 8, 8)`. `foldIndexed` body unchanged — `i` now safely ranges 0..63,
  no wraparound. One-line comment pointing at
  `docs/specs/2026-09-13-scanner-hash-confidence.md` for the empirical writeup.
- `findBestMatch(candidates, capturedBitmap): HashMatchResult?` — remove the
  `candidates.size == 1` short-circuit entirely. New body: compute `capturedHash` once, then inside
  `withContext(Dispatchers.IO)` map every candidate to `card to distance` (failed download →
  `Int.MAX_VALUE`, same fallback as today), `sortedBy` distance, `sorted[0]` = best,
  margin = `sorted[1].second - sorted[0].second` if `sorted.size >= 2` else `Int.MAX_VALUE`. Return
  `HashMatchResult(best.first, best.second, margin)`, or `null` only when `candidates.isEmpty()`.
- `downloadBitmap`, `hammingDistance` unchanged.

**`app/src/main/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCase.kt`**

```kotlin
enum class ConfidencePath { NUMBER_MATCH, HASH_MARGIN, SINGLE_CANDIDATE_HASH, NONE }

data class SearchConfidence(
    val cards: List<TcgCard>,
    val isHighConfidence: Boolean,
    val topCard: TcgCard?,
    val matchPath: ConfidencePath   // new — see task 6, ScannerMatch logging
)

class SmartThresholdUseCase @Inject constructor() {
    companion object {
        // First-guess, not calibrated beyond the 16-card sample in docs/specs/2026-09-13-
        // scanner-hash-confidence.md. Margin = runner-up distance minus best, out of 64 bits.
        // Not a blocker to ship — re-tune once Skyler runs real same-species multi-candidate
        // scans (same convention as GUIDE_FRAME_WIDTH_RATIO's history).
        const val HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12

        // First-guess, calibrated against exactly one real data point (Furfrou/Zorua misread).
        // Absolute Hamming-distance ceiling for the single-candidate case (no runner-up to build
        // a relative margin against). Not a blocker to ship — re-tune on real device.
        const val HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16
    }

    fun evaluate(
        cards: List<TcgCard>,
        parsedName: String,
        parsedNumber: String?,
        hashMatch: HashMatchResult? = null
    ): SearchConfidence {
        if (cards.isEmpty()) return SearchConfidence(cards, false, null, ConfidencePath.NONE)

        if (cards.size == 1) {
            val single = cards.first()
            // No runner-up to build a margin against — absolute distance ceiling instead.
            // hashMatch == null (caller skipped hashing) stays conservative (reject).
            val visuallyConfirmed = hashMatch != null && hashMatch.card.id == single.id &&
                hashMatch.distance <= HASH_SINGLE_CANDIDATE_MAX_DISTANCE
            return if (visuallyConfirmed) {
                SearchConfidence(cards, true, single, ConfidencePath.SINGLE_CANDIDATE_HASH)
            } else {
                SearchConfidence(cards, false, single, ConfidencePath.NONE)
            }
        }

        val normNumber = parsedNumber?.trimStart('0')?.ifEmpty { "0" }
        val numberMatch = normNumber?.let { n ->
            cards.firstOrNull { it.number.trimStart('0').ifEmpty { "0" } == n }
        }
        if (numberMatch != null) {
            return SearchConfidence(cards, true, numberMatch, ConfidencePath.NUMBER_MATCH)
        }

        // Deliberately gated on parsedNumber == null, not "numberMatch == null" — a number that
        // was read but matched nothing is a stronger negative signal than nothing read at all,
        // and must NOT fall through to the hash-margin path.
        if (parsedNumber == null && hashMatch != null &&
            hashMatch.margin >= HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD
        ) {
            return SearchConfidence(cards, true, hashMatch.card, ConfidencePath.HASH_MARGIN)
        }

        return SearchConfidence(cards, false, cards.first(), ConfidencePath.NONE)
    }
}
```

**`app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt`** (tasks 4, 6)

⚠️ **Do not touch or remove the existing UNCOMMITTED block at ~lines 84-94** ("TEMP DIAGNOSTIC
(2026-09-11)", saves the scan crop to disk). Kept per Skyler's explicit instruction — a live
investigation aid, not dead code. Edit around it: don't reformat, move, or fold it into your diff.

In `processImage`, the `best`/log/`evaluate` block (~lines 110-125) becomes:
```kotlin
val hashMatch = perceptualHasher.findBestMatch(candidates, bitmap)
val best = hashMatch?.card ?: candidates.first()
android.util.Log.i(
    "ScannerMatch",
    "perceptualHasher best=${best.name} #${best.number} distance=${hashMatch?.distance} " +
        "margin=${hashMatch?.margin} (from ${candidates.size} candidates)"
)
val (checkName, checkNumber) = confidenceCheckInputs(parsed, best)
val confidence = smartThresholdUseCase.evaluate(candidates, checkName, checkNumber, hashMatch)
android.util.Log.i(
    "ScannerMatch",
    "confidence isHigh=${confidence.isHighConfidence} path=${confidence.matchPath} " +
        "topCard=${confidence.topCard?.name} #${confidence.topCard?.number}"
)
```
`confidenceCheckInputs` (bottom of file) is unchanged.

## User stories

- As Skyler scanning a card whose printed number Gemini can't read, I want the app to still assign
  high confidence when the visual hash clearly picks one candidate, so I don't hit a manual-pick
  list on every hard-to-OCR card.
- As Skyler scanning a card where the name search returns exactly one (possibly wrong) candidate, I
  want that candidate visually checked against the photo before auto-assignment, so a misidentified
  species doesn't silently assign the wrong card.
- As the next person debugging a "still wrong" scanner report, I want the `ScannerMatch` log to show
  which confidence path fired and the actual margin/distance, so I don't have to re-derive it.

## Acceptance criteria

- [ ] `computeHash()` resizes to 8x8; two distinguishably-different images never hash identically;
      the same image (or a re-encoded copy) hashes to the same value or a very small distance.
- [ ] `findBestMatch()` returns `HashMatchResult(card, distance, margin)`; `margin` is
      `Int.MAX_VALUE` for exactly one candidate, else `runner-up distance − best distance`; `null`
      only for an empty list.
- [ ] `evaluate()` takes an optional `HashMatchResult?` (default `null`, backward compatible); still
      honors `numberMatch` unchanged (existing passing tests must not regress); grants
      `HASH_MARGIN` only when `parsedNumber == null` and margin clears the threshold; grants
      `SINGLE_CANDIDATE_HASH` only when the single candidate's own hash distance clears the ceiling
      and `hashMatch.card.id` matches it; otherwise `NONE`/low confidence (including
      number-read-but-unmatched, and `hashMatch == null`).
- [ ] `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD` (12) and `HASH_SINGLE_CANDIDATE_MAX_DISTANCE` (16) are
      named constants, comment-flagged first-guess/pending-re-tune, not a ship blocker.
- [ ] `ScannerViewModel.kt`'s uncommitted TEMP diagnostic block (~lines 84-94) is untouched.
- [ ] `ScannerMatch` logs show hash distance, margin, and `matchPath` in addition to today's fields.
- [ ] Existing `PerceptualHasherTest.kt` tests updated for the 8x8 resize — their mocked
      `createScaledBitmap`/`getPixels` currently hard-code `16, 16`/256-length arrays; leaving them
      would mean they either fail to compile or silently stop matching the real call.
- [ ] New regression test: two distinguishably-different images never hash identically.
- [ ] New `evaluate()` tests: number-match still wins even when a hash margin is also present;
      hash-margin wins when number is null and margin is wide; falls through when margin is narrow
      or `hashMatch` is null.
- [ ] New single-candidate tests: a visually-dissimilar single candidate
      (`distance > HASH_SINGLE_CANDIDATE_MAX_DISTANCE`) is now rejected instead of auto-accepted; one
      within the ceiling is accepted.
- [ ] `.\gradlew.bat testDebugUnitTest` passes (see Dependencies for the invocation quirk).

## Out of scope

- P2 from the source spec (network-download timing re-eval) — informational only.
- Proving this would have caught the actual Furfrou→Zorua misread — that exact photo was never
  saved (see Open Questions); this build makes the code path capable of catching that class of
  error, not verified against that specific case.
- Any change to `GeminiCardScanner`, prompt engineering, or Gemini model/API key setup.
- Any change to `ScannerScreen.kt` camera/focus/crop logic (unrelated, already shipped).
- Removing the TEMP diagnostic block in `ScannerViewModel.kt` — explicitly kept, not this task.

## Dependencies

- Task order: 1 → 2, 1 → 3 → 4 → 5, 4 → 6, (1,3) → 7 → 8. Tasks 1-2 independently shippable; 3-6
  depend on 1; 7-8 depend on 1 and 3.
- Files: `domain/PerceptualHasher.kt`, `domain/SmartThresholdUseCase.kt`,
  `ui/scanner/ScannerViewModel.kt`, `test/.../PerceptualHasherTest.kt`,
  `test/.../SmartThresholdUseCaseTest.kt` (all under `app/src/main/java/com/skyler/pokedexbinder/`
  or `app/src/test/...`). No new dependencies, no Room/schema change.
- Build/test environment: full detail already in this repo's `CLAUDE.md`/`app/CLAUDE.md`
  (Windows-only `gradlew.bat`, `.ps1`-via-`powershell.exe` invocation, `--rerun-tasks`
  verification, `build_debug.bat`/`run_build.bat` broken). Two extras those files don't cover:
  Serena's Kotlin language server fails to init here ("Error extracting archive") — fall back to
  Read/Grep/Glob, don't retry. Existing MockK conventions to follow, don't replace:
  `mockkStatic(Bitmap::class, BitmapFactory::class)`/`unmockkStatic(...)` in `try/finally`;
  `MockWebServer` via `PerceptualHasher.baseUrl`'s test seam.

## Open questions

- `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12` and `HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16` are
  first-guess values (no calibration data beyond one 16-card sample, and one real incident for the
  ceiling). Per this project's established pattern (`GUIDE_FRAME_WIDTH_RATIO`), ship as named,
  clearly-commented constants; re-tune once Skyler generates real on-device scans. Not a blocker.
- Whether tasks 7/8 would have caught the real Zorua case is unverified — that captured photo was
  never saved. The kept TEMP diagnostic block exists to catch the next occurrence. (Owner: Skyler,
  next live-test session.)

## Risks and mitigations

- Uncalibrated margin/ceiling constants → named, commented first-pass, isolated to one
  `companion object` — re-tune is a one-line change, not a re-architecture.
- `findBestMatch()`'s return-type change (`TcgCard?` → `HashMatchResult?`) is breaking → confirmed
  via grep exactly one production call site (`ScannerViewModel.processImage`) plus the one test
  file this task already updates.
- Removing the single-candidate short-circuit adds a network round-trip to a path that previously
  returned instantly → same per-candidate download cost the multi-candidate path already always
  pays; timing re-measurement is P2, explicitly out of scope here.
- Editing `ScannerViewModel.kt` alongside its own uncommitted TEMP diagnostic block → the exact
  before/after above shows the diagnostic block's line range untouched; diff the final file against
  it before calling task 6 done.
- `evaluate()`'s new `hashMatch` param defaults to `null` so any caller that omits it keeps today's
  exact behavior (minus the two intentionally-changed short-circuits) — limits the blast radius.

## Smallest shippable increment

Tasks 1-2 alone (fix `computeHash()` + regression test) are independently correct and shippable —
the source spec's own "Consequences" section notes this fixes `findBestMatch`'s existing
same-species ranking regardless of whether the margin-gate ships. If time runs out, stop after 1-2
and defer 3-8; do not ship 4-8 without 1-2, since the margin/ceiling gates are meaningless against
the currently-broken hash.

## Friction Notes

- Serena's Kotlin language server failed to initialize here ("Error extracting archive") on the
  first `get_symbols_overview` call — fell back to Read/Grep/Glob per the existing
  `fallback-when-language-server-tool-fails-init` lesson. Worth a one-line note in this project's
  `project-overview.md` if it recurs, so future sessions don't re-spend a call discovering it.
- `Grep` with `-n: true` but no explicit `output_mode` silently returns `files_with_matches` only
  (line numbers/content dropped, no warning) — cost two throwaway calls before switching to
  `output_mode: "content"` explicitly.
