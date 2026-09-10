# Handoff — Session 14 (2026-09-10)

## 1. Goals

Continuation from session 13's own next-steps list: Skyler live-tested items 9 (`GUIDE_FRAME_
WIDTH_RATIO=0.5`) and 10 (Gemini retry) together on his real S26 Ultra and reported back: "furfrou:
one wrong result, gave me a list once / castform: gave me a list twice." Diagnosed via real device
logcat (not guessed), fixed the ratio, shipped, installed to device — then live-testing further was
blocked by a Gemini API quota wall, root-caused down to a shared Google Cloud project rather than
anything in this app, and deferred to tomorrow by Skyler's own choice.

## 2. Current State

### Item 9 re-tune — `GUIDE_FRAME_WIDTH_RATIO` 0.5 -> 0.75: fixed, committed (`79b8417`), pushed, installed

Diagnosed from real logcat pulled off Skyler's connected S26 Ultra: 3 genuine (non-transient)
failures in the 0.5 live test — Furfrou misread as "Hisuian Zorua" (a different species entirely),
and Furfrou + Castform both twice failed to OCR the printed card number (`parsed.cardNumber` came
back `null` all 3 times, `search candidates=20`/`22` each time, low confidence, manual-pick list
shown). No 5xx/timeout occurred, so item 10's retry logic got zero exercise this test — still
unconfirmed either way.

Root cause of the number-OCR failures isolated **directly, not guessed**: at Skyler's own
suggestion, replayed a manually-taken close-up photo of the same physical Castform card through
the app's exact Gemini call (same model/prompt, same 1024px/JPEG-q80 pre-send downscale from
`GeminiCardScanner.kt`, run via a scratch PowerShell script using Skyler's API key) — Gemini read
the number correctly (`116/172`) at that framing. Proved Gemini's OCR is not the bottleneck; the
app's own capture at 0.5's ~15cm distance was giving Gemini less usable card detail than a tight,
confident shot does.

Fixed: bumped `GUIDE_FRAME_WIDTH_RATIO` to 0.75 (~10cm target, still 2x margin over the ultrawide's
real 5cm focus floor). Test fixtures updated to match — not just the numbers, but the comments:
`ScannerScreenTest.kt`'s 4000x3000 rotation-0/180 test dims now saturate into the same clamp path
the extreme-aspect-ratio test already covers (real captures never use rotation 0/180 per this
file's own "camera delivers landscape frames" comment), and 90/270 now land on identical numbers
(even split, no 1px rounding gap) instead of the old 1px-apart pair — both correct, the stale
"clean unclamped swap" comments were rewritten rather than left claiming something no longer true.
Cross-validated the new expected values against real JUnit failure output (first-assertion actuals)
before trusting the hand-derived corner-remap math for the rest. `testDebugUnitTest --rerun-tasks`:
296/296 pass (fresh XML). `assembleDebug --rerun-tasks`: clean. Installed to the real device
(`installDebug`, confirmed "Installed on 1 device" against `SM-S948B`).

**Not yet live-device-confirmed** at 0.75 — blocked on tomorrow's test (see Next Steps).

### Zorua species-misread bug — root-caused, NOT fixed, deferred pending Skyler's call

Separate, distinct root cause from the number-OCR issue above. `SmartThresholdUseCase.evaluate()`
(line 18) and `PerceptualHasher.findBestMatch()` (line 39) both short-circuit to auto-high-
confidence whenever the search returns exactly 1 candidate — skipping the perceptual-hash visual
check entirely, including on that 1-candidate path. Live-reproduced: Gemini's own species OCR
misread the Furfrou card as "Hisuian Zorua"; the search-by-name query (already built from that
wrong species) returned exactly 1 candidate; the app confidently assigned it. Nothing in the
pipeline ever compared the captured photo against a card image, because the search was already
scoped to the wrong Pokémon before any visual check could run. Fix would need computing the
perceptual hash distance even at `candidates.size == 1` and gating confidence on a hamming-distance
threshold — a new, uncalibrated constant, same class of decision as `GUIDE_FRAME_WIDTH_RATIO`. One
data point so far (n=1). Logged to `gaps.md`, not built — Skyler's call on whether to spend the
diff now or wait for it to recur.

### Gemini quota wall — root-caused, not an app bug

After installing the 0.75 build, live-testing hit "Too many scans at once" (a real `429` from
Google — confirmed via code trace, `RateLimitException` only ever thrown on an actual 429, no
client-side throttle exists before the network call) twice in a row, escalating to the app's own
"you may have hit your daily Gemini quota" hedge-text. Skyler generated a brand-new API key and
still hit an immediate 429 on its first-ever request — ruled out "this specific key personally used
up its own budget." Confirmed via Skyler's own aistudio.google.com usage dashboard: the key lives
under a shared "Default Gemini Project" also used by `Claude-mem` (an unrelated Claude Code
plugin) — both consumers show real traffic and real errors (`429`/`503`) concentrated on today's
date, at a request volume far above any prior day's. Not a code inefficiency in this app (confirmed
only one Gemini call site exists, no retry-on-429, auto-capture is properly guarded against
double-firing — traced explicitly to rule this out). Logged to `gaps.md` as an environment/workflow
gap with a fix path (separate GCP project + key for pokedex-binder) if Skyler wants it later.

## 3. Active Files

- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt` —
  `GUIDE_FRAME_WIDTH_RATIO` 0.5 -> 0.75, on-screen distance text "~6 in (15 cm)" -> "~4 in (10 cm)",
  updated derivation comment.
- `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt` — 4 rotation-test
  expected values + comments updated for the new ratio.
- `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExtTest.kt` — 2 crop-rect
  expected values + comments updated for the new ratio.
- `gaps.md`, `project-overview.md`, this file — updated this session.
- Session transcript archived: `D:\Claude Projects\Digital Brain\raw-sources\conversations\
  2026-09-10-423b5118.md` (not ingested — purely operational session, no new personal/preference
  knowledge per `conversation-archive.md`'s own ingest criteria).

## 4. Changes Made (commits, chronological)

- `79b8417` — `GUIDE_FRAME_WIDTH_RATIO` 0.5 -> 0.75 + test fixture updates. Committed and pushed
  directly (same precedent as the 0.32 -> 0.5 tune) — single-constant, contained, not
  pipeline-worthy. Then `installDebug`'d to Skyler's connected S26 Ultra.

## 5. Failed Attempts

- **claude-mem's observation timeline had no entries for this session's own work yet** when
  checked for this handoff (DB count is non-zero globally — 145 — so the gate passed, but a
  `timeline` query scoped to this session's actual topics returned nothing, since compression
  hadn't caught up to this still-in-progress session). Handoff above is transcript-derived, not
  observation-cross-checked, for that reason — noted per the wrapcon gate's own instruction not to
  silently treat this as "nothing happened."
- No other dead ends this session — the ratio fix, test updates, and quota root-cause all landed
  on the first real attempt, backed by either fresh test-run output or Skyler's own dashboard
  screenshot rather than guessed values.

## 6. Next Steps

1. **Skyler: live-test the 0.75 ratio tomorrow**, once the Gemini quota resets — scan several
   cards at the new ~10cm distance, watch for (a) whether the number-OCR failures are actually
   gone now, (b) whether the ultrawide's autofocus starts hunting/failing to lock at this closer
   distance (the one real risk of pushing the ratio further — not yet observed, but not yet tested
   at 0.75 either), and (c) whether item 10's Gemini retry logic ever actually fires (still zero
   real-world exercise as of this session).
2. **If 0.75 still isn't landing well after a genuine (non-transient) failure**: re-tune again from
   here, same pattern as before.
3. **Zorua-class species-misread bug**: still open, needs Skyler's decision on whether to build the
   hamming-distance confidence gate now (uncalibrated first-guess constant) or wait for more data
   points. Not blocking — rare enough that it may not recur before genuine calibration data exists.
4. **Gemini quota**: blocked on tomorrow (daily reset) or Skyler creating a separate GCP
   project/key for pokedex-binder if he wants scanning to stop depending on Claude-mem's own
   traffic. His call, not app code.
5. **binder.json republish** — still gated on Skyler's own confidence that scanning works, per
   every prior session's note. Unchanged.
6. **Carried, unchanged from session 13**: the unchanged 400/401/403/404 path in
   `GeminiCardScanner.kt` still has no dedicated test — cheap follow-up whenever that file is next
   touched, not urgent.
