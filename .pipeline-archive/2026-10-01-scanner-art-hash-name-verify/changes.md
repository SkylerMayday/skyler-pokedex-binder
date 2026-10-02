# Changes: scanner wrong-card fixes (Stage 1 name check, Stage 3 art-region DCT hash)

Spec: `.pipeline/specs.md`. Gradle NOT run (harness loopback failure); all verification by reading/grep. Session 20's uncommitted edits left untouched.

## Files

| File | Change |
|---|---|
| `repository/CardSearchRepository.kt` | New private `List<TcgCard>.matchingParsedName(parsedName)` (reuses `namesMatch`; null/blank name = unfiltered). Applied to `number+total` label in the specific-query loop and to the final `broad-number` step. Cascade comment updated; `broad-dex` unchanged, with a comment explaining why (dex proves species not card; hasher + SmartThresholdUseCase decide). |
| `domain/ArtHash.kt` (NEW) | Pure JVM: `ArtRect`, `ArtHash` (`artRect`, `boxDownsample`, `dct32` orthonormal with lazy cos table, `hashFromCoefficients` 63 bits median-threshold, `hashFromGrey`). No `android.*`. |
| `domain/PerceptualHasher.kt` | `computeHash(Bitmap)` kept (delegates); new `internal computeHash(Bitmap, ArtRect?)` clamps art rect to bitmap, returns `0L` if degenerate, getPixels + grey (299/587/114) -> `ArtHash.hashFromGrey`. `findBestMatch` hashes the capture with `cardRectInCrop(w,h)` card rect; refs hashed whole. Scaled-bitmap/recycle removed. `HashMatchResult` gained `ranked: List<Pair<String,Int>> = emptyList()` (top 5) - task 8. |
| `ui/scanner/ScannerScreen.kt` | Added `internal fun cardRectInCrop(cropWidth, cropHeight): ImageRect` after `guideFrameImageRect`; uses the private constants in-file (geometry not duplicated). |
| `domain/SmartThresholdUseCase.kt` | Comment only: "64 bits" -> "63 bits". |
| `ui/scanner/ScannerViewModel.kt` | Log line appends `ranked=${hashMatch?.ranked}` (task 8). |
| `test/.../CardSearchRepositoryTest.kt` | +5 tests: Kyurem/Snorlax -> none-matched; number+total kept on name match; filtered number+total continues to broad-number; broad-number all-mismatch -> none-matched; broad-dex not filtered (Malamar). Spec test 5 (name null) already covered by existing tests (lines ~172, ~219), unchanged. |
| `test/.../ArtHashTest.kt` (NEW) | constant-image zero AC; k=1 basis lands in coef[1]; brightness-shift invariance; distinct patterns >10 bits; <63 bits; boxDownsample 2x2 blocks + non-divisible/tiny constants; artRect math. |
| `test/.../ScannerScreenTest.kt` | Appended 2 `cardRectInCrop` tests (append only). |
| `test/.../PerceptualHasherTest.kt` | Rewritten around `fakeBitmap` (mocks width/height/getPixels per Bitmap.getPixels semantics); no `createScaledBitmap`. Expected distances computed via `ArtHash`/`computeHash`, not magic numbers. |

## Decisions / deviations (please review)

1. **Art fractions are `Double`, not `Float`** (spec sketched `0.065f`). Reason: spec's own expected `artRect(0,0,1000,1400) = 65,154,935,686` depends on truncation landing exactly on integers; float constants could yield 64.9999. Checked by rounding-error bounds that Double products land on the integers (margins small but valid). Private to `ArtHash`; no API impact.
2. **Distinct-pattern test uses seeded random greys, not gradient/checker.** Gradients have almost-all-zero AC, so the median threshold is float noise (hash meaningless). Random images give 31-set-bits hashes; distance <=10 is ~4 sigma, so >10 is safe.
3. `findBestMatch` test builds card1's reference bitmap as the exact crop region of the captured pattern, so distance is exactly 0; margin asserted against a computed value.
4. Task 8 (ranked + ViewModel log) included, as recommended.

## Hand-recomputed numbers
- `cardRectInCrop(974,1361)`: cardW=(974/1.30f).toInt()=749, cardH=(749*88f/63f).toInt()=1046, left=112, top=157 -> `ImageRect(112,157,861,1203)` (matches spec). Clamp case `(974,900)` -> `(112,0,861,900)`.
- `artRect(ArtRect(112,157,861,1203))` = left 112+48.685->160, top 157+115.06->272, right 112+700.315->812, bottom 157+512.54->669 -> `(160,272,812,669)`.
- Art window on card1 reference (749x1046 at origin) is (48,115,700,512) = captured window minus (112,157): pixel-identical, so distance 0.

## Simplification / review pass (self, by reading)
- Reuse: `namesMatch`, `ImageRect`/crop constants, existing grey weights reused; no new helper duplicates an existing one.
- Quality: no dead code; `matchingParsedName` kept as a named helper (two call sites). No comments restating code.
- Efficiency: box-average single pass over the art region only (<=~460k px); cos table built once; no bitmap scaling/recycle.
- Correctness: all call sites of changed APIs grepped (see log): `computeHash(Bitmap)`, `findBestMatch`, `HashMatchResult(3-arg)` in `SmartThresholdUseCaseTest` still compile via the default `ranked`; ViewModel only reads `.ranked`. `ArtRect` is `internal`, used only by internal `computeHash` overload (public signatures unchanged). `for` loop as last statement in mockk `answers` lambda returns Unit - fine.
- Known/accepted (per spec): domain -> ui import of `cardRectInCrop`; confidence gate (16) still rejects correct cards (deferred); "Mega X ex" vs "M X-EX" name false-reject fails safe.

## 7-check verification (raw output: `.pipeline/evidence/changes-verify.log`)

| # | Check | Result |
|---|---|---|
| 1 | Typecheck | NOT RUN - Gradle unrunnable in harness. Desk-checked imports, signatures, nullability, visibility (internal/public), every call site (grep in log). |
| 2 | Lint | N/A - no lint task run (Gradle). |
| 3 | Scoped tests | NOT RUN - Skyler runs `.\gradlew.bat testDebugUnitTest`. New/changed: ArtHashTest, PerceptualHasherTest, ScannerScreenTest, CardSearchRepositoryTest. |
| 4 | Production build | NOT RUN - Gradle. |
| 5 | Dev/start server boot | N/A - Android app, no server. |
| 6 | No stray debug/scratch | PASS - grep of new/edited hasher+ArtHash files: no println/debugger/Log calls added (the ViewModel `Log.i` is pre-existing; only its message extended). `scripts/scan_capture.log` untracked is session 20's, not mine. |
| 7 | git status only intended | PASS - my files: CardSearchRepository.kt, PerceptualHasher.kt, SmartThresholdUseCase.kt, ScannerScreen.kt, ScannerViewModel.kt (main); CardSearchRepositoryTest, PerceptualHasherTest, ScannerScreenTest (test); new ArtHash.kt, ArtHashTest.kt. Remaining dirty/untracked entries are session 20's pre-existing tree. |

Spec acceptance greps: no `android.*` in ArtHash; no "64" left in PerceptualHasher/SmartThresholdUseCase; no new Gradle dependency (new-dependency check: N/A, manifest untouched).

## Risks for Skyler's run
- Most likely compile-time snags (unverifiable here): mockk `arg<Int>(n)` usage in `fakeBitmap`; `assertEquals(List<Pair<..>>, ...)` generics in PerceptualHasherTest.
- Numeric tests depending on float behavior: brightness-shift invariance and distinct-pattern distance >10 (statistically safe, not proven by execution).

## Friction Notes
- Spec's Float `ART_*` constants risk truncation off-by-one against its own hand-computed expected values; Planner specs giving exact integer expectations should say which numeric type.
- Spec's "gradient vs checker differ by >10 bits" test idea does not work with a median-threshold DCT hash (AC-sparse inputs -> noise-driven bits); worth a spec-template note for hash tests.
