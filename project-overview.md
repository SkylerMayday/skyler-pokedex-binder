# Project Overview — PokedexBinderV2

## What This Is

An Android app (Kotlin/Compose/Hilt/Room) for tracking which Pokémon TCG cards you physically
own, slot by slot, with a public web-shareable snapshot. Personal project (Skyler) — no metrics,
no users beyond himself.

## Architecture

- **Local storage**: Room database (`pokedex_binder.db`), currently schema v8. Each nav-drawer
  section is its own independent entity/table set — there is no shared "binder" abstraction.
  A new section means: new entity + DAO + repository + ViewModel + Screen + `Screen` route +
  drawer item, wired by hand in `ui/navigation/AppNavigation.kt`.
- **Card search**: `CardSearchRepository` merges three sources:
  - **pokemontcg.io** (primary) — single fast query, `-set.series:Pocket` filter always applied.
  - **TCGdex** (secondary) — single fast query, English locale only
    (`api.tcgdex.net/v2/en/`); merged into every broad name search via
    `mergeResults`/`dedupeKey` (name+number+setName, lowercased).
  - **TCGCSV** (tcgcsv.com) — no search endpoint exists; the app fans out to all ~217 product
    groups (bounded concurrency, chunks of 8), ~10-13s per scan. **Requires a browser-like
    `User-Agent` header** — tcgcsv.com's bot protection 401s OkHttp's default UA
    (`di/NetworkModule.kt`, `provideTcgcsvRetrofit`). Token-based matching (not whole-phrase
    substring) so a query like "CLB Mr Mime" still finds "Mr. Mime" despite the set-prefix word
    not appearing in the product name.
  - `CardSearchRepository.searchStreaming(tcgcsvQuery, fastSearch)` runs the fast source and
    TCGCSV concurrently, emitting `SearchProgress.Fast` then `SearchProgress.Complete` — fast
    results render immediately, TCGCSV's slower results merge in when ready (new cards
    appended, existing imageless cards backfilled via `isSameCard` name+number matching,
    distinct from `dedupeKey` because cross-source `setName` values differ).
  - **Both call sites that assign cards must use `searchStreaming`, not the old
    `searchByName`/`searchByParsedInfo` directly** — `ManualSearchViewModel` (Connecting Art
    search, Card History add) and `QuickScanViewModel` (main Pokédex binder slot tap) both do.
    `PersonalCollectionRepository.refreshPokemon` intentionally does NOT use TCGCSV (auto-search
    convenience feature, not a manual assign flow — see Personal Collection below).
- **Publish/restore**: `PublishRepository`/`RestoreRepository` read every binder's current state,
  diff against the last published `binder.json` (GitHub Contents API), upload if changed, post a
  Discord embed. Manual-only (no auto-publish — explicitly rejected 2026-07-12). Content-gated
  binders (Connecting Art, Personal Collection, Unown) publish automatically when non-empty, no
  separate Settings toggle — only Pokédex/Card History have toggles (legacy).

## The Six Nav-Drawer Sections

1. **Pokédex** (`main_binder`) — ~1025 fixed slots (National Dex + regional/alt-form/mega/gmax),
   one card each. Tapping an empty/filled slot opens `QuickScanScreen`
   (`ui/quickscan/QuickScanViewModel`), NOT the shared `ManualSearchScreen`.
2. **Connecting Art** (`connecting_art_group`/`connecting_art_slot`) — user-created named grids
   (rows×cols), each cell optionally holding one assigned card + an owned/unowned flag. Search
   via `ManualSearchScreen`.
3. **Personal Collection** (`personal_collection_cache`/`personal_collection_entry`) — 5 FIXED
   named sections (Charizard, Celebi, Leafeon, Tangela, Minccino & Cinccino,
   `ui/personalcollection/PersonalCollectionViewModel.kt`'s `PERSONAL_COLLECTION_SECTIONS`).
   Each section auto-searches its Pokémon name(s) and caches EVERY result (not one slot — can be
   dozens of cards), with a separate owned/unowned toggle per card (`_entry` table is separate
   from `_cache` so a refresh never wipes ownership). `refreshAll()` runs all 5 sections with
   bounded concurrency (`REFRESH_CONCURRENCY = 8`) and **per-section `runCatching` isolation** —
   one section throwing must never cancel its siblings or abort chunks that haven't started yet
   (a real bug found and fixed 2026-07-15, see Gotchas below). Jump-to-section UI: "Jump to
   section" `DropdownMenu` (mirrors the main Pokédex binder's pattern), not a chip row.
4. **Unown** (`unown_binder`) — standalone binder, structurally identical to the main Pokédex
   binder: 28 FIXED slots (A-Z, !, ?), one card each, tap-to-search-and-assign via
   `ManualSearchScreen` pre-filled with the literal query `"Unown"` (broad — user manually picks
   which result matches which letter; NOT `"Unown A"` per-letter, since results were
   inconsistent/sparse for many letters). Own nav-drawer entry, positioned after Personal
   Collection. **This went through several complete architecture reversals in one session
   (2026-07-15) before landing here — see Gotchas, do not re-litigate without reading that
   section first.**
5. **Card History** (`secondary_binder`) — open-ended list, manually added cards, drag-reorder.
6. **Settings** — GitHub PAT/repo, Discord webhook, publish toggles for Pokédex/Card History.

## Why The Big Decisions Were Made

- **Manual publish only, no auto-publish** (2026-07-12): considered and explicitly rejected —
  publish costs a real GitHub commit + Discord webhook post; auto-firing on every card add would
  spam both during a batch-add session.
- **TCGCSV as image-backfill + full-fallback only, never the primary/routine source**
  (2026-07-12): TCGdex is one fast API call; TCGCSV requires scanning all ~217 groups
  (~10-13s). Replacing TCGdex with TCGCSV for routine search would turn every search from
  near-instant into a 10+ second wait.
- **No language/localization display** (2026-07-08, reaffirmed 2026-07-12): TCGdex's `en`-locale
  endpoint never surfaced non-English cards to this app in the first place (a prior
  claim that it did was corrected — the app was always locale-locked). CJK/SEA cross-language
  card matching was investigated live and found unreliable (many searches return zero results
  under non-English TCGdex locales; when they do return results, images are frequently absent).
  Explicitly parked, not being built.
- **Unown is a standalone single-assignment binder, not part of Personal Collection**
  (2026-07-15, final): PC's sections and a fixed-slot binder are fundamentally different data
  models (search-and-cache-many-with-toggle vs. one-slot-one-card). Early in the session Unown
  was merged into PC's model; this was reverted after on-device confusion about why "one binder"
  became "28 tiny binders." See Gotchas for the full reversal history — don't repeat it.
- **Room schema changes are treated as high-risk once any build might be on a real device**
  (2026-07-15, hard-learned): a schema *revert* (v8→v7) triggered `fallbackToDestructiveMigrationOnDowngrade()`
  and wiped Skyler's entire local database, because his device had already installed a build at
  v8. The fix pattern going forward: **check what's plausibly already installed before reverting
  a Room version**, and prefer leaving a schema addition in place (inert, unused) over reverting
  it, if forward-migrating is safe but downgrading isn't.

## Gotchas / Don't Re-Litigate

- **Unown's placement bounced between three different designs in a single session
  (2026-07-15)**: standalone binder → merged into Personal Collection (28 sections, PC's
  search-and-toggle model) → reverted to standalone → (briefly re-merged into PC on a
  miscommunication) → reverted to standalone again, now with its final nav position (after
  Personal Collection, before Card History). **Current, final state: standalone, one card per
  slot, literal `"Unown"` search query.** If asked to change this again, re-read this
  project-overview and the relevant handoff entries before touching code — this has already
  cost significant rework.
- **`QuickScanViewModel` is a separate code path from `ManualSearchViewModel`** — a TCGCSV or
  search-behavior fix applied to one does NOT automatically apply to the other. This was the
  root cause of a multi-round "TCGCSV isn't working" investigation: every TCGCSV fix initially
  went into `ManualSearchViewModel`, but the main Pokédex binder's actual assign flow
  (`QuickScanViewModel`) never called `searchStreaming` at all until this was caught and fixed.
  When touching search behavior, grep for all ViewModels calling `CardSearchRepository` before
  assuming a fix is complete.
- **`PersonalCollectionViewModel.refreshAll()`'s per-section isolation is load-bearing.** An
  earlier version wrapped every section's refresh in one shared `coroutineScope` — one section
  throwing cancelled every sibling AND aborted every chunk not yet started. Since new sections
  get appended to the end of `PERSONAL_COLLECTION_SECTIONS`, an early failure could silently
  prevent later sections from ever being attempted, with no visible error. Fixed via
  `runCatching` per section; do not remove this isolation when touching `refreshAll()`.
- **Card History (`SecondaryBinderScreen`) had no hamburger-menu drawer trigger at all** until
  2026-07-15 — a pre-existing gap unrelated to any of the above, now fixed
  (`onOpenDrawer` param added, wired in `AppNavigation.kt`).
- **`fallbackToDestructiveMigrationOnDowngrade()` is configured in `DatabaseModule.kt`** — this
  means ANY app build that declares a lower Room version than what's already on a device wipes
  that device's entire local database silently, no confirmation. Treat every Room version change
  as a one-way door once any build might have reached a real device.

## Conventions

- Gradle needs explicit env vars in the dev sandbox:
  `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`, `TEMP=TMP=C:\Windows\Temp`. Without these, `gradlew.bat`
  fails at daemon startup with a misleading `Unable to establish loopback connection` error that
  looks like a sandbox/JVM restriction but isn't — always set these before concluding a build
  command is broken.
- Full reinstall required after any code change — Android Studio's "Apply Changes" (hot-swap)
  silently skips new composables/nav routes/DB migrations. This caused multiple "my fix isn't
  showing up" false alarms this session.
- Feature work goes through `dev-team-pipeline` (Planner→Coder→Tester→Reviewer,
  `.pipeline/` handoff files) for anything multi-file/architectural. Contained single-file UI
  fixes and urgent live-debugging are done directly. Archive `.pipeline/` outputs to
  `.pipeline-archive/<date>-<slug>/` before starting a new, unrelated pipeline run.
- No git remote until 2026-07-12 — `github.com/SkylerMayday/skyler-pokedex-binder`, private.
