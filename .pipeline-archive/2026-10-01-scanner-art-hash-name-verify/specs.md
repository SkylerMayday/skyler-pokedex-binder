# Spec: Scanner wrong-card fixes (Stage 1 name verification, Stage 3 art-region DCT hash)

**BUILD/TEST RULE (Coder, Tester, Reviewer):** Gradle cannot run in this harness (loopback failure). Do NOT run or loop on Gradle. Write code + tests, verify by reading/reasoning, then hand off. Skyler runs `.\gradlew.bat testDebugUnitTest` himself. Session 20's uncommitted changes are in the tree (GeminiCardScanner.kt, ScannerScreen.kt, several tests, docs) - do not revert or reformat them; make surgical edits only.

## TL;DR
- Stage 1: in `CardSearchRepository.searchByParsedInfo`, results of `number+total` and `broad-number` must pass a name check against Gemini's parsed name, else treated as empty (cascade continues). `broad-dex` unchanged (see decision). No model/DTO change.
- Stage 3: replace 8x8 whole-bitmap mean hash with art-region-only 32x32 DCT pHash (63 bits) in pure Kotlin. Captured side derives the card rect from the crop using the shared guide constants; reference side uses the whole image. Art box = x 6.5-93.5%, y 11-49% of the card.
- Known unfixed: confidence gate (`HASH_SINGLE_CANDIDATE_MAX_DISTANCE=16`) will still reject correct cards (distances 20, 24). Deferred.

## Problem
Live logcat 2026-10-01: Japanese Black Kyurem (11/64, dex 646) -> name/dex queries empty -> species-blind `number:"11" set.total:64` returned Jungle Snorlax #11 as sole candidate -> offered. Mega Malamar ex (48/76, dex 687) -> `broad-dex` returned 22 Malamar cards -> hasher picked Malamar #52. Separately the hash itself is worse than random: whole capture (hand/monitor background included) squashed to 8x8; true card ranked 84th and 75th of 100. Prototype (`C:\Users\SkylerMayday\AppData\Local\Temp\h\t.py`) art-crop + 32x32 DCT pHash ranked truth 2nd and 1st.

## Proposal

### Stage 1 (repository) - file `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt`

Grounding: `TcgCard` (`data/model/TcgCard.kt`) has NO dex field; `TcgCardDto` has none either; `pokemonNames` holds first word per `" & "` segment (for "Mega Malamar ex" -> "Mega", useless for this). Existing private `namesMatch(a,b)` (alnum-lowercased, bidirectional contains) is reusable.

**Decision: name check only, no dex field.** Reasons: (a) dex check in `number+total`/`broad-number` is redundant - the `dex+number(+total)` queries already ran earlier in the cascade and were empty whenever dex was known; (b) adding `nationalPokedexNumbers` to DTO + TcgCard + 3 mappers (tcgdex/tcgcsv don't supply it) is a wider change for zero added coverage; (c) in `broad-dex` the query itself pins dex, so a dex filter is tautological.

Change:
```kotlin
// new private helper in CardSearchRepository
private fun List<TcgCard>.matchingParsedName(parsedName: String?): List<TcgCard> {
    val n = parsedName?.takeIf { it.isNotBlank() } ?: return this   // nothing to verify against
    return filter { namesMatch(it.name, n) }
}
```
- `specificQueries` loop: for label `"number+total"` only, `val results = safeSearch(query).let { if (label == "number+total") it.matchingParsedName(name) else it }`; empty after filter -> continue loop. (Other labels already pin species via name/dex in the query; leave untouched.)
- Final block: `val byNumber = (if (number != null) safeSearch("number:\"$number\"") else emptyList()).matchingParsedName(name)`; label logic unchanged (`none-matched` when empty).
- Update the cascade comment to say species-blind steps are name-verified.
- **`broad-dex`: no code change.** Document in a comment: a dex hit proves species, not card; 22 same-species candidates go to the hasher, and `SmartThresholdUseCase` decides. Do not mark it confident. (It is already not auto-accepted: `evaluate` only goes high-confidence via NUMBER_MATCH, margin path (needs parsedNumber==null), or single-candidate hash <=16.)
- Name-null behaviour: if Gemini returned no name, species-blind steps are unfiltered (nothing to verify; dropping them would kill the number-only scan path; existing tests rely on it).
- Known false-reject: `namesMatch("M Malamar-EX" style card, "Mega Malamar ex")` fails (Mega vs M prefix). Failure direction is safe (empty -> `LowConfidence(emptyList())` -> manual search). Do not add fuzzy matching.

Expected outcomes: Black Kyurem -> number+total Snorlax filtered, broad-name empty, broad-dex empty, broad-number Snorlax filtered -> `none-matched`, empty list. Malamar: unchanged behaviour (broad-dex; hasher + gate decide).

### Stage 3 (hash) - files below

**New file `app/src/main/java/com/skyler/pokedexbinder/domain/ArtHash.kt`** - pure JVM, no `android.*` imports:
```kotlin
internal data class ArtRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left; val height get() = bottom - top }

internal object ArtHash {
    const val GRID = 32                 // DCT input is GRID x GRID
    const val LOW = 8                   // low-frequency block edge
    const val HASH_BITS = LOW * LOW - 1 // 63, DC dropped
    const val ART_LEFT = 0.065f; const val ART_RIGHT = 0.935f
    const val ART_TOP = 0.11f;   const val ART_BOTTOM = 0.49f

    /** Art rect inside a card rect (same coord space). Edges via (card.left + card.width*frac).toInt(). */
    fun artRect(card: ArtRect): ArtRect
    /** Box (area) average of w*h grey values down to GRID*GRID row-major. Each dst cell averages its src span (>=1 px). */
    fun boxDownsample(grey: IntArray, w: Int, h: Int): FloatArray
    /** Separable orthonormal 32x32 DCT-II; precomputed cos table (lazy val, 32*32 floats). in/out row-major size 1024. */
    fun dct32(grey: FloatArray): FloatArray
    /** 63-bit hash: low 8x8 block (row-major), skip index 0 (DC); bit i set if coef > median of the 63. */
    fun hashFromCoefficients(coef: FloatArray): Long
    /** Convenience: boxDownsample -> dct32 -> hashFromCoefficients. */
    fun hashFromGrey(grey: IntArray, w: Int, h: Int): Long
}
```
Notes: orthonormal scaling is fine (only median threshold matters, DC excluded). Median of 63 = 32nd sorted value; strict `>` so that coefficient's own bit is 0. Bits used: 0..62 (fits Long; bit 63 always 0). Grey per pixel = `(r*299 + g*587 + b*114)/1000` (same weights as today and OpenCV).

**Edit `app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt`** - keep `computeHash(bitmap: Bitmap): Long`, `hammingDistance`, `findBestMatch(candidates, capturedBitmap): HashMatchResult?`. Internals:
```kotlin
/** card = rect of the card within bitmap; null = whole bitmap IS the card (reference images). */
internal fun computeHash(bitmap: Bitmap, card: ArtRect?): Long {
    val c = card ?: ArtRect(0, 0, bitmap.width, bitmap.height)
    val a = ArtHash.artRect(c) // coerce into bitmap bounds; if width/height <1 -> return 0L? see edge cases
    val px = IntArray(a.width * a.height)
    bitmap.getPixels(px, 0, a.width, a.left, a.top, a.width, a.height)
    return ArtHash.hashFromGrey(px.map greyOf, a.width, a.height)
}
fun computeHash(bitmap: Bitmap): Long = computeHash(bitmap, null)   // reference-card path
```
- `findBestMatch`: captured hash = `computeHash(capturedBitmap, cardRectInCrop(capturedBitmap.width, capturedBitmap.height).toArtRect())`; candidate images hashed via `computeHash(bitmap)` (whole image = card). Remove `recycle` of scaled bitmap (no longer created). Keep IO/null/MAX_VALUE/margin logic identical.
- Update class KDoc/comments: 63 meaningful bits; remove the 8x8/`1L shl` history comment (replace with one-line pointer).

**Captured-card geometry - edit `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`** (next to `guideFrameImageRect`; constants stay `private`, geometry not duplicated):
```kotlin
// Card rect inside a guideFrameImageRect-sized, upright crop bitmap. Assumes the physical card fills
// the guide frame. Width derives from the crop: cropW = guideW*CROP_MARGIN_FACTOR -> guideW = cropW/factor;
// height = guideW*CARD_ASPECT_RATIO (NOT cropH/factor: boxH is clamped to the frame on 4:3 sensors, so cropH is unreliable).
internal fun cardRectInCrop(cropWidth: Int, cropHeight: Int): ImageRect {
    val cardW = (cropWidth / CROP_MARGIN_FACTOR).toInt().coerceAtMost(cropWidth)
    val cardH = (cardW * CARD_ASPECT_RATIO).toInt().coerceAtMost(cropHeight)
    val left = (cropWidth - cardW) / 2; val top = (cropHeight - cardH) / 2
    return ImageRect(left, top, left + cardW, top + cardH)
}
```
`PerceptualHasher` imports `ui.scanner.cardRectInCrop`/`ImageRect` (layer smell: domain -> ui; acceptable because geometry must have one source. Do not make the constants `internal` and copy the math.) Sanity check vs the prototype's hand-measured captures (490x600 thumbs): predicted art box x 81-409, y 95-295; measured 100-385 x, 112-295 y. Bottom edge matches; predicted box is slightly looser on top/sides (measured box was hand-drawn tight inside the art). Acceptable; flag for calibration (Open questions).

**Other edits**
- `domain/SmartThresholdUseCase.kt` line 18 comment: "out of 64 bits" -> "out of 63 bits". No constant changes.
- Optional S (recommended, enables calibration): `HashMatchResult` gets `val ranked: List<Pair<String, Int>> = emptyList()` (top 5 `card.id to distance`), populated in `findBestMatch`; `ScannerViewModel.kt` line ~116 log appends `ranked=...`. No `android.util.Log` inside PerceptualHasher (unmocked in JVM tests).

### Tests

`app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt` (existing helpers `stub`, `parsed`, `cardDto`; default-empty mocks; assert `matchedQuery`):
1. Black Kyurem repro: `parsed("Black Kyurem","11","64",646)`, stub only `number:"11" set.total:64` -> Snorlax; assert `cards.isEmpty()` and `matchedQuery=="none-matched"`.
2. number+total kept when name matches: stub returns `cardDto("s-11","Black Kyurem","11")` -> kept, label `number+total`.
3. number+total filtered then cascade continues: stub number+total -> Snorlax; stub `name:*Black*Kyurem*` path untouched (empty); stub `number:"11"` -> Black Kyurem card -> label `broad-number`, id matches.
4. broad-number filtered: only `number:"11"` -> Snorlax, name "Black Kyurem" -> `none-matched`, empty.
5. name null -> species-blind unfiltered (existing tests at lines ~172 and ~219 already cover; they must still pass - `parsed(null,"116","172",351)`).
6. broad-dex unchanged: `parsed("Mega Malamar ex","48","76",687)`, stub `nationalPokedexNumbers:687` -> 2 Malamar cards; assert label `broad-dex`, both returned.
Check that existing tests using names still pass: test at line 119 (`dex+number+total` label) is unaffected.

`app/src/test/java/com/skyler/pokedexbinder/domain/ArtHashTest.kt` (NEW, plain JUnit, no mockk/Bitmap):
- `dct32 of constant image has zero AC`: all 100f -> coef[0] ~= 3200 (orthonormal: 32*100), every other |coef| < 1e-2.
- `dct32 known pattern`: `v(x,y)=cos(pi*(2x+1)*1/64)` (k=1 horizontal basis) -> energy in coef index 1 (row 0, col 1) dominant; all others < 1e-2 relative.
- `hash invariant to brightness shift`: fixed pseudo-random 1024-value grey (seeded Random) vs +40 (no clipping, values <=200) -> equal hashes.
- `hash distinguishes different patterns`: horizontal gradient vs vertical gradient vs checker -> pairwise hamming > 10. (Coder: pick patterns, verify by hand/reasoning the threshold is safely met; if unsure use `assertNotEquals` plus >5.)
- `hash uses at most 63 bits`: `hash ushr 63 == 0L`.
- `boxDownsample` of a 64x64 image with 2x2 constant blocks -> exact block values; non-divisible size (e.g. 50x70) does not throw and is constant-preserving.
- `artRect math`: card `ArtRect(0,0,1000,1400)` -> left 65, top 154, right 935, bottom 686. Card `ArtRect(112,157,861,1203)` -> hand-compute with `(left + width*frac).toInt()`.

`app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt` (existing file, dirty - append only): `cardRectInCrop(974,1361)` = ImageRect(112,157,861,1203) (cardW=(974/1.30f).toInt()=749; cardH=(749*88f/63f).toInt()=1046; left=(974-749)/2=112; top=(1361-1046)/2=157). Clamp case: cropHeight smaller than computed cardH -> bottom <= cropHeight. Coder must recompute these numbers, not trust them blindly.

`app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt` (REWRITE the Bitmap-dependent tests; it currently mocks `createScaledBitmap(…,8,8,false)` which no longer exists):
- Keep `hammingDistance identical is zero`; change all-zeros-vs-all-ones test to still 64 (it is a Long op, not hash width) but rename/comment "raw Long op; hash itself uses 63 bits".
- Helper `fakeBitmap(w,h,f:(x,y)->Int)`: `mockk<Bitmap>()` with `every{width}`, `every{height}`, and `every{getPixels(any(),any(),any(),any(),any(),any(),any())} answers` that fills `firstArg<IntArray>()` using args (offset, stride, x, y, w, h) per `Bitmap.getPixels(pixels, offset, stride, x, y, width, height)` semantics. No createScaledBitmap mocking.
- `computeHash same for identical bitmaps`; `computeHash different for two clearly different bitmaps` (left-white/right-black vs top-white/bottom-black, in the art region); `computeHash(bitmap, card) ignores pixels outside the art box` (same art interior, different borders -> equal hashes).
- `findBestMatch` tests (empty list null; single candidate MAX_VALUE margin; two candidates closest wins + margin): rebuild with `fakeBitmap`; expected `distance`/`margin` values must be computed from the synthetic patterns via `ArtHash` (use `hasher.hammingDistance(hasher.computeHash(a, cardRect), hasher.computeHash(b))`), not hard-coded magic. Captured bitmap sizing: use w/h where `cardRectInCrop` is non-degenerate (e.g. 974x1361).

### Dependency-ordered tasks
| # | Task | Size | Depends |
|---|---|---|---|
| 1 | `matchingParsedName` + wire two steps + comments in CardSearchRepository | S | - |
| 2 | Repository tests 1-6 | S | 1 |
| 3 | `ArtHash.kt` (artRect, boxDownsample, dct32, hashFromCoefficients, hashFromGrey) | M | - |
| 4 | `ArtHashTest.kt` | M | 3 |
| 5 | `cardRectInCrop` in ScannerScreen.kt + ScannerScreenTest case | S | - |
| 6 | PerceptualHasher rewrite (computeHash overloads, findBestMatch) + `SmartThresholdUseCase` comment | M | 3, 5 |
| 7 | Rewrite PerceptualHasherTest | M | 6 |
| 8 | (optional) `HashMatchResult.ranked` + ViewModel log | S | 6 |

### Edge cases
- Art rect must be clamped to bitmap bounds; if width or height < 1 (degenerate/tiny bitmap) return a fixed hash `0L` and let the distance comparison behave (do not throw; a thrown exception here becomes `ScannerState.Error`).
- `toCroppedBitmap()` falls back to the UNCROPPED decoded bitmap on degenerate crop/exception (ImageProxyExt.kt); then the card-rect assumption is wrong. Accept (rare); do not add plumbing.
- Reference images that fail to download keep `Int.MAX_VALUE` distance exactly as today. Reference decode via `BitmapFactory.decodeByteArray` may return null -> same path.
- Different reference aspect (promo/oversized/TCGdex `high.webp`) - assumed standard card; no special casing.
- Very large capture (e.g. 1000x1400 art ~ 870x530 px): `getPixels` of ~460k ints is fine; do box-average manually (not `createScaledBitmap` bilinear, which aliases heavily at >10x downscale).
- Float determinism: hashes from same pixels must be identical across calls (no randomness; table built once).

## User stories
- As Skyler scanning a Japanese card with no English equivalent, I want the app to show "no match" rather than a random same-numbered card, so I don't confirm the wrong card.
- As Skyler scanning a card whose species has many prints, I want the visual hash to rank the real print near the top, so the right card is offered first.

## Acceptance criteria
- [ ] Black Kyurem 11/64 case returns empty candidates with label `none-matched`; ViewModel shows LowConfidence(empty). (unit test)
- [ ] `number+total` and `broad-number` results whose name does not match parsed name never reach the hasher; name-null keeps old behaviour. (unit tests)
- [ ] All pre-existing `searchByParsedInfo` tests still pass unchanged.
- [ ] `ArtHash` has no `android.*` import; its tests run on plain JVM.
- [ ] DCT of constant image: AC coefficients ~0; known basis pattern lands in the expected coefficient.
- [ ] Hash identical under uniform brightness shift; clearly different patterns differ by >10 bits; hash < 2^63.
- [ ] `artRect` and `cardRectInCrop` math tests pass with hand-computed values.
- [ ] No reference to "64 bits" remains for the hash (grep `64` in PerceptualHasher*, SmartThresholdUseCase comments); no new Gradle dependency.
- [ ] Public API unchanged: `computeHash(Bitmap): Long`, `hammingDistance(Long,Long): Int`, `findBestMatch(List<TcgCard>, Bitmap): HashMatchResult?`; `ScannerViewModel` compiles unmodified (unless task 8).
- [ ] Skyler-run: `testDebugUnitTest` green; device re-scan shows improved ranking (procedure below).

## Verification procedure (Skyler runs; Gradle not runnable by agents)
1. `.\gradlew.bat testDebugUnitTest` (if the loopback error recurs, run in his own PowerShell).
2. `.\gradlew.bat installDebug`, then `adb logcat -c; adb logcat -s ScannerMatch:*`.
3. Scan (a) a known English card (e.g. Castform swsh9-116, Furfrou swsh6-126), (b) the Japanese Black Kyurem, (c) Mega Malamar ex. Read lines: `gemini parsed`, `search candidates=N query=<label>`, `perceptualHasher best=... distance=... margin=...`, `confidence isHigh=... path=...`.
4. Expected: (b) `query=none-matched candidates=0`; (a) truth card is best/near best with distance roughly 20-25 (prototype), `isHigh=false` is EXPECTED until the gate rework (LowConfidence list shows it); (c) still `broad-dex`, hasher choice logged.
5. Calibration: each scan also saves `scan_debug_<ts>.jpg` (TEMP DIAGNOSTIC in ScannerViewModel); pull via `adb` from the app external files dir. Record per confirmed scan: true card id, its distance, best wrong distance, margin. With task 8 the `ranked=` list gives top-5 distances directly. Collect >=10 confirmed scans before touching `HASH_SINGLE_CANDIDATE_MAX_DISTANCE` / `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD`. The prototype `t.py` can re-score saved captures offline.

## Out of scope
- Language detection (Japanese vs English) - deferred.
- Confidence-gate rework - deferred. **Known issue:** even with the art hash, correct cards scored distance 20 and 24 of 63; `SmartThresholdUseCase.HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16` will still reject them, so a correct single-candidate scan lands in LowConfidence (user confirms from list) - a safe failure, not silently fixed here. Also `evaluate`'s NUMBER_MATCH path grants high confidence to any multi-candidate list containing a same-numbered card regardless of species; Stage 1 reduces but does not close this. Revisit after more confirmed scans.
- Dex field on `TcgCard`/DTO; fuzzy/Mega-prefix name matching; OpenCV/SIFT/any new dependency; parallelising candidate image downloads; hash caching; cropping precision (card detection/perspective correction).

## Dependencies
No new libraries. Internal: `namesMatch` (repository), `guideFrameSize`/`CROP_MARGIN_FACTOR`/`CARD_ASPECT_RATIO` (ScannerScreen.kt), `ImageRect`. Test stack already present: JUnit4, mockk, coroutines-test, MockWebServer.

## Open questions
- (Skyler, non-blocking) Captured-art box assumes the physical card fills the guide frame; real framing varies. If device rankings are poor, tune `CROP_MARGIN_FACTOR` and/or shrink the art box inward a few % (e.g. ART_* constants), calibrated from saved captures.
- (Skyler, non-blocking) Should `broad-dex` results ever be auto-accepted? Current answer: no; leave to the deferred gate rework.

## Risks and mitigations
| Risk | Mitigation |
|---|---|
| Card-rect assumption wrong on real scans -> art hash degrades toward old behaviour | Prototype used a hand-measured box; predicted box differs mildly (see sanity check). Device procedure + `ranked=` logging; constants are named for retune. |
| Gate still rejects correct cards (20/24 > 16) | Documented, deferred; failure is LowConfidence list, not a wrong auto-accept. |
| Name filter false-rejects (Mega/ex naming, Gemini translation of JP names) | Safe direction (empty -> manual search); accepted; no fuzzy matching. |
| domain -> ui import for `cardRectInCrop` | Single source of geometry beats duplicated constants; revisit if domain/ui layering is ever enforced. |
| Existing Bitmap-mock tests break (createScaledBitmap 8x8 gone) | Task 7 rewrites them with `fakeBitmap` on `getPixels`/`width`/`height`. |
| Gradle unrunnable for agents -> untested compile | Coder self-reviews imports/signatures; Skyler runs build+tests; Reviewer checks by reading. |

## Smallest shippable increment
Stage 1 alone (tasks 1-2): tiny, independent, fixes the Black Kyurem/Snorlax class immediately. Stage 3 (tasks 3-7) ships next; task 8 is optional.

## Friction Notes
- Planner could not run Gradle or the prototype; all numbers (predicted art box, unit-test constants) are hand-computed and flagged for the Coder to recompute.
