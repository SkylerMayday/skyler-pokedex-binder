# Handoff — Session 12 (2026-09-09/10, continuation of session 11)

## 1. Goals

Skyler's session-10-handoff review named 4 outstanding items and asked to work items 2 and 4:
2) crop-rect-vs-guide-box scanner mismatch (this repo), 4) `/card` Discord slash command still
broken (`DiscordBot` repo — cross-repo work, done from this session since it was reachable and
directly related to this repo's own publish/Discord-embed surface). After both shipped, Skyler
tested the scanner live on his real S26 Ultra — this turned into a live root-cause session on the
scanner's actual card-matching accuracy, well beyond the original 2 items, driven entirely by real
adb logcat evidence gathered turn-by-turn rather than guessed.

## 2. Current State

### Item 2 — crop-rect-vs-guide-box mismatch: fixed, shipped, pushed (`fe3e89d`)
Root cause confirmed via decompiled CameraX 1.6.1 bytecode/sources (javap on the real `.aar`s —
not guessed): the camera bind used the plain vararg `bindToLifecycle` overload with no
`UseCaseGroup`/`ViewPort`, so `PreviewView`'s `FILL_CENTER` scaling and the captured-frame crop
math were computing against two different denominators for the same ratio. Fixed via
`UseCaseGroup`+`ViewPort` (shares field of view across Preview/ImageCapture/ImageAnalysis) and
cropping against `ImageProxy.cropRect` instead of the full frame. Full `dev-team-pipeline` run,
ship at 94/100. One P2 left open (deferred by Reviewer's own call): a degenerate `cropRect` can
throw before `toCroppedBitmap()`'s own graceful-fallback guard — contained by an outer `catch`,
logged in `gaps.md`, still open.

### Item 4 — `/card` Discord slash command: real gaps found and fixed in `DiscordBot`, not here
See that repo's own handoff.md/gaps.md for full detail. Summary: the leading "SERVER_ID unset"
hypothesis was disproven by reading the actual boot code; the real root cause (found live in
Railway's own deploy logs after the first push) was a 101-char command description crashing slash
registration for every command, plus a missing DB migration spamming SQLite errors. Both fixed and
pushed (`54368fd`), plus a SERVER_ID/OWNER_ID value-validation guard added first (`080f37d`) —
genuinely useful defense-in-depth even though it wasn't the actual root cause this time.

### Real, live scanner debugging — 3 more genuine bugs found and fixed on real hardware tonight

Skyler tested item 2's fix live, immediately reported "same issue, still not detecting the right
card" — a DIFFERENT, deeper problem than the crop-size mismatch item 2 fixed. Root-caused via
`adb logcat` turn-by-turn with Skyler actually scanning cards, not guessed:

1. **Zero visibility into the match pipeline.** `ScannerViewModel.kt` had no logging at all on the
   Gemini/perceptual-hash/confidence path — added a `ScannerMatch` log tag (cropped bitmap size,
   Gemini's parsed read, candidate count, hasher's pick, final confidence) so the actual pipeline
   became inspectable. Diagnostic-only, no behavior change.
2. **The real bug** (`ff0134c`): `SmartThresholdUseCase.evaluate()` exists to check whether
   Gemini's own OCR'd card number pins down which print you're holding, among same-named
   candidates — its own doc comment says so. The call site was passing `best.name`/`best.number`
   (the perceptual hasher's ALREADY-CHOSEN candidate's own fields) instead of
   `parsed.cardName`/`parsed.cardNumber` (what Gemini actually read). Since `best` is always a
   member of `candidates`, this made the number-match check trivially self-referential —
   `isHighConfidence` was always `true` whenever there was more than one candidate, regardless of
   whether the hasher's guess was right. **Confirmed via git archaeology**: correct at
   `455178a` (original implementation), silently broken by `498d035` (2026-05-20, "upgrade
   ScannerViewModel to use Gemini + slot assignment", which introduced the perceptual hasher in
   the same diff). Live-reproduced: scanning a real Castform, Gemini correctly read `name=Castform
   number=62`, but the app confidently returned "Castform Sunny Form #20" — a different print,
   because #62 was never actually compared against anything. Matches the "right Pokémon, wrong
   print" symptom and explains the accuracy drop since changing phones — the bug's been live since
   May, but how often it surfaces depends on how often the hasher's own guess happens to be wrong,
   which a different camera/crop pipeline changes. Fixed via a new pure, testable
   `confidenceCheckInputs(parsed, best)` function so the call site can't silently drift back.
   3 new regression tests.
3. **`GUIDE_FRAME_WIDTH_RATIO` was stale for the lens actually in use** (`643978c`). The 0.32
   ratio was calibrated for the MAIN lens's 18cm minimum focus distance — but since 2026-09-08 the
   scanner binds the physical ULTRAWIDE lens instead, whose real minimum focus distance (checked
   live tonight, verified against the real Android API docs — not assumed): 20.0 diopters →
   1/20.0 = **5cm**. Skyler's own demo photo (card filling most of frame, sharp, number trivially
   legible) plus the live "Gemini reads the species but can't read the tiny printed number often
   enough" symptom both confirmed the card was needlessly small in frame. Bumped 0.32 → 0.5
   (~15cm target, still 3x the lens's real floor for margin) — first live-tested value, explicitly
   flagged as re-tunable, not a final calibration. On-screen hint text updated to match.

After the ratio bump, two more scans (Castform, Furfrou) hit Gemini 503s and timeouts.
**Diagnosed as external Gemini API flakiness, not a regression from tonight's changes** — the
image sent to Gemini is capped at 1024px longest side either way (`GeminiCardScanner.kt`'s
`scaleBitmap`), so the bigger crop doesn't mean a bigger upload. **Not fixed this session** — a
real, separate, legitimate gap: zero retry on a transient 5xx/timeout, one failed call surfaces
straight to the user as an Error. Flagged, not built — see Next Steps.

### binder.json republish (item 3 from Skyler's original 4-item list) — not touched this session

## 3. Active Files

**This repo:**
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt` —
  `bindPreviewCaptureAnalysis` helper (`UseCaseGroup`+`ViewPort`), new `ImageRect.offsetBy`,
  `GUIDE_FRAME_WIDTH_RATIO` 0.32→0.5, hint text updated.
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt` — `toCroppedBitmap()`
  crops against `cropRect`.
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt` — new `ScannerMatch`
  diagnostic logging, new `confidenceCheckInputs()` pure function + its call-site fix, new
  `ParsedCardInfo` import.
- `ScannerScreenTest.kt`, `ImageProxyExtTest.kt`, `P0_2_FreshVerificationTest.kt`,
  `ScannerFocusIndependentVerificationTest.kt`, `RectTestFixture.kt`, new `ScannerViewModelTest.kt`
  — test coverage for all of the above, including recomputed rotation-geometry expectations at
  the new ratio.
- `gaps.md`, `project-overview.md`, this file — updated this session.

**`DiscordBot` repo (cross-repo, see that repo's own handoff.md/gaps.md for full detail):**
`src/index.js`, `src/utils/env.js`, `src/events/ready.js`, `src/modules/moderation.js`,
`src/utils/database.js`, `__tests__/env.test.js`, that repo's `gaps.md`/`handoff.md`/
`project-overview.md`.

## 4. Changes Made (commits, chronological)

- **This repo**: `fe3e89d` (item 2 crop fix) → `3b83ec9` (docs) → `ff0134c` (confidence-check
  regression fix) → `643978c` (guide-frame ratio bump). All merged to `master`.
- **`DiscordBot` repo**: `080f37d` (SERVER_ID/OWNER_ID guard) → `38eb175` (docs) → `54368fd` (real
  root cause: description-length crash + QOTD migration) → `adc9bfb` (docs). All merged to
  `master`, pushed.

**Not yet pushed this repo**: `ff0134c` and `643978c` — pushed up through `3b83ec9` earlier in the
session; the two live-debugging fixes after that are committed and merged to local `master` but
not yet pushed to `origin/master`. Ask Skyler before pushing (per standing convention).

## 5. Failed Attempts

None on the code side across any of the 5 real fixes this session — each root-caused before
fixing, each verified with fresh test evidence. Two real environment/external hiccups, not fix
failures: (1) the Gradle `IOException: Unable to establish loopback connection` wall recurred
mid-pipeline during item 2's Tester stage, cleared on its own again — `--no-daemon` confirmed NOT
a real fix (this repo's `gradle.properties` already sets `org.gradle.daemon=false`); (2) Gemini API
503s/timeouts during live testing, external to this codebase, not investigated further as a code
issue (see Next Steps for the one real gap it exposed: no retry logic).

## 6. Next Steps

1. **Skyler: decide whether to push `ff0134c`/`643978c`** to `origin/master` — both committed,
   merged locally, tested, and already live-tested on the real device tonight (that's how they
   were found and verified). Not pushed only because the session ended before asking.
2. **Add retry-once on a transient Gemini 5xx/timeout** (`GeminiCardScanner.kt`) — real gap found
   live tonight, explicitly deferred to next session, not built. Currently one failed HTTP call
   surfaces straight to the user as an Error with no retry.
3. **Keep live-testing the ratio bump** — 0.5 is a first value, not a final calibration (its own
   comment says so). If it's still not landing well after the retry-logic gap is closed (so a
   real attempt isn't confused with a transient 503), re-tune from there — the constant and its
   derivation comment are set up for exactly this iteration.
4. **Skyler: confirm item 4's `/card` fix** — watch `DiscordBot`'s Railway logs (already
   redeployed once this session, found the description-length bug from those exact logs) for the
   `[SlashRegister] Registered N slash command(s)...` line after the latest push, confirm `/card`
   itself now shows up.
5. **Carried, unchanged, still blocked on Skyler**: republish from the app (`binder.json`
   staleness, item 3 from the original 4-item list — not touched this session).
6. **New, cheap, not urgent**: the degenerate-`cropRect` P2 from item 2's Reviewer pass (`gaps.md`)
   — one-line early-return guard, whenever `ImageProxyExt.kt` is next touched.
