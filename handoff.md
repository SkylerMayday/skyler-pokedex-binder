# Handoff — Session 13 (2026-09-10)

## 1. Goals

Continuation from session 12's own next-steps list. Skyler confirmed items 4 (Discord `/card`
fix — working, resolved, not touched here) and 5 (`binder.json` republish — still his to do, once
he can effectively scan a card) directly, no code work needed for either. Asked to build item 2
(Gemini API retry) and fix item 6 (degenerate-`cropRect` P2 guard), then live-test 2 and 3
(`GUIDE_FRAME_WIDTH_RATIO=0.5`) together afterward.

## 2. Current State

### Item 6 — degenerate-`cropRect` P2 guard: fixed, committed (`806242d`), pushed

Cheap, contained, single-file fix — done directly, not through the full pipeline (root cause was
already diagnosed in session 12's own Reviewer pass, nothing left to re-diagnose). `toCroppedBitmap()`
called `guideFrameImageRect(cropWidth, cropHeight, ...)` before its own uncropped-fallback guard
could run; a zero/negative `cropWidth`/`cropHeight` makes `guideFrameImageRect`'s internal
`coerceIn(0, rawWidth - 1)` throw (`rawWidth - 1` becomes `-1`, an empty range). Fixed with an
early-return guard before the call. New regression test caught a real bug in the fix's own log
line before it shipped — `Rect.toString()` is unmocked in this project's non-Robolectric
unit-test stub jar (same class of gotcha `project-overview.md`'s Conventions section already
documents for `Rect` method calls generally). `testDebugUnitTest --rerun-tasks`: 293/293 pass
at the time this fix alone was verified (fresh XML).

### Item 2 — Gemini API retry: fixed, shipped, pushed (`c70d8b6`)

Full `dev-team-pipeline` run against `.pipeline_archive/2026-09-10-gemini-retry-and-croprect-guard/`
(archived from `.pipeline/`). Planner → Coder → Reviewer all ran clean; **Tester subagent hit the
account's session rate limit mid-run and died with no output** — orchestrator substituted directly
(fresh `testDebugUnitTest --rerun-tasks` run + a manual acceptance-criteria trace against the spec's
decision table), disclosed as a process deviation in `.pipeline/test-results.md` rather than
silently absorbed. Reviewer independently re-verified the test evidence from raw on-disk JUnit XML
(not the copied sidecar) and independently reproduced the Gradle daemon wall on its own rerun
attempt — corroborating, not just trusting, the "environment issue, not diff-related" claim.
**Ship at 97/100, 0 blocking findings.**

New `executeWithRetry()` in `GeminiCardScanner.kt` extends the existing 429/`RateLimitException`
branch pattern: retries on a 5xx response or `SocketTimeoutException` only, up to
`MAX_SCAN_RETRIES = 2` additional attempts (3 total), fixed `SCAN_RETRY_DELAY_MS = 500L` delay
between attempts. 429 and other 4xx unchanged — fail immediately, no retry, preserving the
existing rate-limit cooldown UX. A failed 5xx response's body is closed before retrying (connection
leak prevention, explicit acceptance criterion). Both constants are first-pass values, explicitly
retunable like `GUIDE_FRAME_WIDTH_RATIO` — not final calibration.

One minor, non-blocking gap both the orchestrator's trace and the Reviewer independently flagged:
the unchanged 400/401/403/404 path has no dedicated test (code path untouched by this diff, correct
by inspection, just never had test coverage — not in the spec's own task breakdown). Logged in
`gaps.md`, not blocking.

`testDebugUnitTest --rerun-tasks` (both items together, final state): **296/296 pass, 0 failures,
0 errors, fresh XML count.**

### Not touched this session

- **Item 3 — live-testing `GUIDE_FRAME_WIDTH_RATIO=0.5`**: Skyler said "we test 2 and 3 after" —
  intends to live-test both the new retry logic and the ratio bump together on his S26 Ultra. Not
  done yet as of this handoff (session ended on the build/ship/commit side, before his live test).
- **binder.json republish (item 5)**: confirmed still blocked on Skyler, by his own choice — he'll
  do it once he can effectively scan a card, i.e. after item 3's live test lands well.
- **Discord `/card` (item 4)**: confirmed working by Skyler directly. He separately mentioned a
  YouTube-feed error exists in that bot but explicitly said it's not for this session/repo to
  solve — noted, not investigated, not touched (different repo, different concern, his call).

## 3. Active Files

- `app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt` — new
  `executeWithRetry()`, `MAX_SCAN_RETRIES`/`SCAN_RETRY_DELAY_MS` constants, `scan()`'s call site
  updated.
- `app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt` — `SUCCESS_BODY`
  extraction, `scan throws IOException on 500` updated to 3 enqueued responses, 3 new tests
  (503-then-succeeds, timeout-then-succeeds, all-timeouts-exhausted).
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt` — early-return guard
  for degenerate `cropRect` before `guideFrameImageRect` is called.
- `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExtTest.kt` — new regression
  test for the degenerate-`cropRect` case (distinct from the pre-existing degenerate-computed-rect
  test).
- `gaps.md`, `project-overview.md`, this file — updated this session.
- `.pipeline_archive/2026-09-10-gemini-retry-and-croprect-guard/` — archived pipeline handoff
  files (specs.md, changes.md, test-results.md, review-verdict.md, evidence/).

## 4. Changes Made (commits, chronological)

- `806242d` — item 6, degenerate-`cropRect` guard fix (direct, not piped).
- `c70d8b6` — item 2, Gemini API retry (`dev-team-pipeline`, ship at 97/100).
- Both merged fast-forward from a short-lived `scanner-gemini-retry` branch onto `master`
  (deleted after merge), pushed to `origin/master`.

## 5. Failed Attempts

- **The `pipeline-tester` subagent hit the account's session rate limit** (`resets 5am
  Asia/Singapore`) and died with zero output partway through its run — not a code/logic failure,
  an infra/quota wall. Orchestrator compensated inline rather than blindly respawning into the
  same wall (per the standing "hard wall → stop, adapt, disclose" instruction). Worth noting for
  awareness if this recurs: subagent spawns appear to consume the same account-level quota as the
  main session, and a spawn can die silently mid-task with no partial `.pipeline/*.md` written —
  always check what the dead agent actually left on disk before assuming total loss.
- **The intermittent Gradle `Unable to establish loopback connection` wall recurred for the Coder
  subagent** (6 workarounds attempted: 2 JDKs, sandbox on/off, stale-process cleanup, retries — all
  identical failure) — but cleared immediately for the orchestrator's own direct run, and again for
  the Reviewer subagent's own independent rerun attempt (which also hit it once, corroborating it's
  real and environmental). Consistent with `gaps.md` sessions 10-12's existing data point: this wall
  fires more reliably for Tester/Coder-stage subagents than for direct orchestrator-run commands in
  the same session. New sub-finding this session (Coder's own friction note): the stack trace
  consistently points at `sun.nio.ch.WEPollSelectorImpl` → `UnixDomainSockets.connect0` →
  `SocketException: Invalid argument: connect` — the JDK's Windows AF_UNIX-socket-based NIO selector
  self-pipe, not a literal TCP loopback problem (a real TCP loopback self-test on the same machine
  works fine). Next session hitting this could try `-Djdk.net.usePlainSocketImpl=true` or forcing
  the classic Windows selector instead of retrying blindly — not yet tried.

## 6. Next Steps

1. **Skyler: live-test items 2 and 3 together** on the real S26 Ultra — scan several cards, watch
   for (a) whether a transient Gemini 503/timeout now silently retries instead of dead-ending
   (`adb logcat -s ScannerMatch:*` if you want to see the retry firing), and (b) whether
   `GUIDE_FRAME_WIDTH_RATIO=0.5`'s working distance is landing well now that a transient failure
   won't get misread as "the ratio is wrong." This was explicitly the point of testing both
   together — a retry-covered transient failure no longer masquerades as a ratio problem.
2. **If ratio still isn't landing well after a genuine (non-transient) failure**: re-tune from
   0.5, per session 12's own note — the constant's comment is written for exactly this iteration.
3. **binder.json republish** — Skyler's own call, once card scanning is working well enough that
   he trusts a fresh publish reflects real state.
4. **Minor, low-priority, newly logged**: the unchanged 400/401/403/404 path in
   `GeminiCardScanner.kt` has no dedicated test — cheap follow-up whenever that file is next
   touched, not urgent (see `gaps.md`).
5. **Carried, unchanged**: none from session 12's list remain open except items 3 and 5 above,
   both now explicitly gated on Skyler's own live testing rather than more code work.
