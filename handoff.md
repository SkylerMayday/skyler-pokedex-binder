# Handoff — 2026-07-15

## Goals (this session)

Started from "check handoff, do what's needed" and expanded into a long, iterative on-device
debugging session covering: a new Unown binder feature, TCGCSV search integration, Connecting
Art/Personal Collection publish support, and multiple rounds of real bug fixes discovered only
through actual on-device testing (which this session had none of directly — all "on-device"
findings came from Skyler's live reports).

## Current State

**Nothing has been committed since `e42eeb5` (2026-07-10).** The entire session's work — several
complete features plus multiple real bug fixes — is sitting uncommitted in the working tree.
Skyler has NOT explicitly said "commit this" yet this session; do not commit without asking.

All code compiles clean and the full unit suite is green (`./gradlew.bat testDebugUnitTest`,
verified fresh multiple times this session with `JAVA_HOME=D:\jdk17\jdk-17.0.14+7` and
`TEMP=TMP=C:\Windows\Temp` set). The debug APK (`app/build/outputs/apk/debug/app-debug.apk`) was
last rebuilt at 2026-07-15 12:22 SGT with everything below included.

**Database is at schema v8 on Skyler's device**, reached via a real `MIGRATION_7_8` (not a
destructive wipe) — do not revert the Room version below 8 without reading the Gotchas section
in `project-overview.md` first; a prior revert this session wiped his entire local database.

## Active Files (most-touched this session)

- `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt` — TCGCSV
  integration, `searchStreaming`, token-based matching.
- `app/src/main/java/com/skyler/pokedexbinder/ui/quickscan/QuickScanViewModel.kt` +
  `QuickScanScreen.kt` — wired onto `searchStreaming` (was previously bypassing TCGCSV entirely).
- `app/src/main/java/com/skyler/pokedexbinder/ui/unown/` +
  `data/local/UnownBinderEntry.kt`/`UnownBinderDao.kt` +
  `repository/UnownBinderRepository.kt` — standalone Unown binder (current, final design).
- `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionViewModel.kt` —
  per-section `runCatching` isolation in `refreshAll()` (real fix, keep it).
- `app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt` — drawer order,
  Unown routes, Card History `onOpenDrawer` wiring.
- `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt` +
  `RestoreRepository.kt` — Connecting Art + Personal Collection + Unown publish/restore, all
  content-gated (no Settings toggle for any of the three).
- `app/src/main/java/com/skyler/pokedexbinder/di/NetworkModule.kt` — TCGCSV `User-Agent`
  interceptor (tcgcsv.com bot-blocks OkHttp's default UA).

## Changes Made (chronological, condensed — full detail in the session transcript)

1. Unown binder v1 (standalone, Room v8) + TCGCSV search fallback — built via
   `dev-team-pipeline`, shipped.
2. Personal Collection: collapsible sections + jump-to-binder chips (later replaced by a
   dropdown, see below).
3. Connecting Art + Personal Collection publish/restore support (content-gated, no toggle) —
   built via `dev-team-pipeline`. This resolves the 2026-07-11 open question below: both are now
   wired into `buildSnapshot()`.
4. **Bug found & fixed**: TCGCSV requests were silently 401'd by tcgcsv.com's bot protection
   (OkHttp's default User-Agent). Added a browser-like UA header scoped to the TCGCSV client
   only.
5. Concurrent 3-source search (`searchStreaming`) + image backfill — built via
   `dev-team-pipeline`, replacing the old retry-on-empty TCGCSV button.
6. **Bug found & fixed**: TCGCSV's group-scan filter required the WHOLE query to be a literal
   substring of the product name — "CLB Mr Mime" failed to match "Mr. Mime" because "CLB" isn't
   in the product's own name field. Changed to token-based matching (any word ≥3 chars matches).
7. Unown reworked to live inside Personal Collection as 28 sections (Charizard/Celebi-style
   auto-search + owned toggle) — per explicit request, later reversed.
8. **Bug found & fixed**: `PersonalCollectionViewModel.refreshAll()`'s concurrent refresh wrapped
   all sections in one `coroutineScope` — one section throwing cancelled every sibling and
   aborted every unstarted chunk, which could silently prevent later-listed sections (like
   Unown's 28 letters) from ever being attempted. Fixed with per-section `runCatching`.
9. Unown reverted to a standalone binder (fallback request), simplified to a broad `"Unown"`
   search query instead of per-letter queries.
10. **Serious incident**: reverting the Room schema from v8 back to v7 (to undo step 7's
    migration) triggered `fallbackToDestructiveMigrationOnDowngrade()` because Skyler's device
    was already at v8 — wiped his entire local database (Pokédex, Card History, Connecting Art,
    Personal Collection, all gone). Recovered via his last GitHub publish snapshot (he'd
    published before, so Pokédex/Card History came back via Restore).
11. `QuickScanViewModel` found to never have used `searchStreaming`/TCGCSV at all — every TCGCSV
    fix up to this point only applied to `ManualSearchViewModel`, which doesn't back the main
    Pokédex binder's actual slot-tap assign flow. Fixed: wired `QuickScanViewModel` onto
    `searchStreaming` too.
12. Unown briefly re-merged into Personal Collection on a miscommunication, then reverted back
    to standalone once the actual data-model difference (one-slot-one-card vs.
    many-cards-with-toggle) was explained and confirmed. **This is the final state.**
13. Personal Collection's chip row replaced with a "Jump to section" `DropdownMenu` (mirrors the
    main Pokédex binder's existing pattern) — the chip row became impractical once section count
    grew.
14. Unown drawer position moved to directly after Personal Collection (was between Pokédex and
    Connecting Art).
15. Card History (`SecondaryBinderScreen`) found to have never had a hamburger-menu button at
    all — unrelated pre-existing gap, fixed.
16. Session-end: `project-overview.md` and `gaps.md` created (didn't exist before), this
    `handoff.md` rewritten, session archived to Digital Brain.

## Failed Attempts / Reversed Decisions

- Unown-inside-Personal-Collection (28 auto-search sections) — built twice, reverted both times.
  Root cause of the confusion: "put Unown in PC" was interpreted as adopting PC's data model,
  when what was actually wanted was Unown's own model just reachable from a different place in
  the UI. Final answer: standalone binder, own nav-drawer entry.
- Reverting Room from v8 to v7 to "clean up" the schema after undoing the PC-merge — caused a
  real data-loss incident (see Changes Made #10). Going forward: don't revert a Room version once
  any build might have reached the device; leave unused columns/tables in place instead.
- CJK/SEA language card-art matching via TCGdex name-search — investigated live, found genuinely
  unreliable (many zero-result searches, sparse images even on hits). Confirmed not worth
  building; explicitly parked by Skyler.

## Next Steps

1. **Ask Skyler whether to commit.** This is the single biggest open item — a huge amount of
   verified, tested work is sitting uncommitted.
2. **On-device re-verification of the very latest build** (12:22 SGT APK) — confirm: Unown shows
   in the drawer after Personal Collection with 28 working slots; Card History has a working
   hamburger menu; TCGCSV/CLB Mr. Mime works via the main Pokédex binder's QuickScan flow, not
   just Connecting Art search.
3. `Migration7to8Test.kt` (androidTest) was never recreated after the standalone Unown binder's
   final rebuild — flagged in `gaps.md`.
4. Decide whether Connecting Art/Personal Collection/Unown's publish data has ever actually been
   verified end-to-end on the live site (published, then checked on the website project) — this
   session built and unit-tested the wiring but never confirmed the real GitHub Pages output.
5. **Carried over from 2026-07-08/2026-07-12, still not started:**
   - Custom domain (CNAME + DNS) and og:image for the GitHub Pages viewer — low urgency, and
     that viewer is in maintenance-only mode pending the website project's redesign anyway.
   - Recent-pulls feed idea (2026-07-12): a stream-facing "latest additions" view driven by
     `changelog.json`, tying the binder into on-stream pack openings. App side is effectively
     done (changelog data already published) — this is almost entirely the website project's
     feature to build. Not yet specced.
   - "Braincheck" (Skyler's term, used 2026-07-08) — still undefined; re-ask if it recurs.

## Environment Notes (still true)

- `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`; `TEMP=TMP=C:\Windows\Temp` for Gradle runs.
- No Android emulator in this sandbox — every on-device fact this session came from Skyler's
  live reports, not direct observation. Treat "should work" with extra suspicion until he
  confirms; this session had several rounds where code was verified correct in isolation but the
  actual on-device symptom persisted because the fix was in the wrong file (see `QuickScanViewModel`
  gotcha in `project-overview.md`).
- Android Studio's "Apply Changes" does not pick up new nav routes/composables/migrations — a
  full Run/Debug or `adb install -r` is required after every code change, confirmed as the root
  cause of at least two "my fix isn't showing up" false alarms this session.
- Session JSONL for this project: `C:\Users\SkylerMayday\.claude\projects\D--Claude-Projects-PokedexBinderV2\`.
