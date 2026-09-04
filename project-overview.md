# Project Overview — PokedexBinderV2

## What This Is

An Android app (Kotlin/Compose/Hilt/Room) for tracking which Pokémon TCG cards you physically
own, slot by slot, with a public web-shareable snapshot. Personal project (Skyler) — no metrics,
no users beyond himself.

## Architecture

- **Local storage**: Room database (`pokedex_binder.db`), currently schema v9. Each nav-drawer
  section is its own independent entity/table set — there is no shared "binder" abstraction.
  A new section means: new entity + DAO + repository + ViewModel + Screen + `Screen` route +
  drawer item, wired by hand in `ui/navigation/AppNavigation.kt`.
- **Per-card metadata** (2026-07-15): every card-holding entity has a `language` column (string,
  `data/model/Language.kt` enum, default `"EN"`). `main_binder` and `unown_binder` additionally
  have `remarks` (nullable text) and `isLocked` (boolean). A locked Pokédex/Unown slot shows a
  lock icon overlay and `BinderRepository`/`UnownBinderRepository`'s `assignCard`/`clearCard`
  refuse to act on it until unlocked. `ui/components/EditCardDetailsDialog.kt` is the shared
  dialog (language dropdown everywhere; remarks + lock toggle only on Pokédex/Unown), wired into
  each binder's existing per-card action surface (bottom sheets, `SlotDetailScreen`, or a small
  edit icon for Card History which has no sheet). Full spec:
  `docs/specs/2026-07-15-card-language-lock-remarks.md`.
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
  separate Settings toggle — only Pokédex/Card History have toggles (legacy). **Card History's
  toggle (`publishCardHistory`) defaults OFF** (`PublishSettingsRepository.kt`) — cards already in
  Card History locally will NOT appear in `binder.json`/the website until this is manually
  enabled in Settings and a publish is run. Card History publishes under binder id `"cardHistory"`,
  which the website's `SHELF_1_BINDER_IDS` allowlist places on shelf 1 alongside Pokédex — the
  plumbing works fine, this is purely a default-off toggle catching people out (caught 2026-07-16:
  Skyler had cards in Card History but nothing showing on the site).
  `publish/model/BinderSnapshot.kt`'s `SnapshotSlot` carries `language`/`remarks`/`isLocked` as of
  2026-07-15 (additive fields, no `SNAPSHOT_SCHEMA_VERSION` bump — same pattern as the earlier
  `owned` field addition). Card History has no restore path at all (by design, append-only), so
  its `language` field publishes but never restores.

## The Six Nav-Drawer Sections

1. **Pokédex** (`main_binder`) — ~1025 fixed slots (National Dex + regional/alt-form/mega/gmax),
   one card each. Tapping an empty/filled slot opens `QuickScanScreen`
   (`ui/quickscan/QuickScanViewModel`), NOT the shared `ManualSearchScreen`.
2. **Connecting Art** (`connecting_art_group`/`connecting_art_slot`) — user-created named grids
   (rows×cols), each cell optionally holding one assigned card + an owned/unowned flag. Search
   via `ManualSearchScreen`.
3. **Personal Collection** (`personal_collection_cache`/`personal_collection_entry`) — 4 FIXED
   named sections (Charizard, Celebi, Tangela, Minccino & Cinccino — Leafeon removed 2026-07-15,
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
- **Adding a DB column is not the same as publishing it.** The 2026-07-15 language/lock/remarks
  feature shipped a full Room migration + UI in one pipeline run, but nothing in that run touched
  `PublishRepository`/`RestoreRepository` — the public `binder.json` didn't carry the new fields
  until a second, separate pipeline run. When a feature's data needs to reach the website, check
  the publish layer explicitly; it is not automatically in scope of "add a DB field."
- **`PokedexDatabaseTest.kt` had two independent pre-existing compile bugs** (fixed 2026-07-15,
  commit `7921b82`, predate any recent schema work per `git show HEAD`): `turbine` was only
  declared as `testImplementation`, not `androidTestImplementation`, so `import app.cash.turbine.test`
  didn't resolve in the androidTest source set; and two `MainBinderEntry(...)` calls used
  3-arg positional construction that predates the `dexOrder: Int` field being added (no default),
  leaving it unfilled. Both fixed directly (named-arg construction, added the missing dependency)
  — if `compileDebugAndroidTestKotlin` ever breaks again in this file, check these two classes of
  issue first.

## Native Dependencies & Version Alignment (2026-08-22, 16KB page-size fix)

Bumped for Google Play's 16KB memory-page-size compliance and to fix a real "not 16KB
compatible" warning on debuggable builds. **These specific versions were chosen deliberately,
not just "latest" — do not casually bump further without re-checking:**

- `camerax = "1.6.1"` — 16KB fix landed in CameraX 1.4.0; 1.6.1 is current stable, no known
  regressions. AAR metadata hard-requires `agp >= 8.9.1` (forced the AGP bump below).
- `datastore = "1.1.7"` — **deliberately NOT the "latest stable" (1.2.x).** `androidx.datastore`
  1.2.0+ re-broke 16KB alignment for `libdatastore_shared_counter.so` (confirmed via
  `flutter/flutter#182898`, still unresolved as of the 1.2.1 stable release). 1.1.7 is the last
  version confirmed 16KB-compatible with no other known issues.
- `composeBom = "2026.06.00"` — an 18-month jump from `2024.12.01`. This repo already has one
  cautionary tale about BOM bumps: commit `28aa261` bumped a smaller composeBom jump *and*
  Kotlin/KSP in lockstep, guessed a KSP version suffix that didn't exist on Maven Central, and
  had to be reverted same-day (`e1a628a`). **Lesson applied and confirmed correct this time:**
  since Kotlin 2.0+, the Compose Compiler Gradle plugin decouples compiler version from Kotlin
  language version — `kotlin`/`ksp` were deliberately left untouched at `2.1.20`/`2.1.20-1.0.32`,
  verified build-error-driven (no metadata-mismatch error occurred).
- `agp = "8.9.1"` — forced by `camerax 1.6.1`'s AAR metadata floor, not chosen speculatively.
- **`androidx.graphics:graphics-path` still resolves to `1.0.1`, not the hoped `1.1.0+`**,
  transitively via `composeBom 2026.06.00`'s own `androidx.compose.ui:ui-graphics`. Means
  `libandroidx.graphics.path.so` (1 of the 3 originally-flagged natives) may still show the
  16KB warning. Not force-overridden against what the BOM itself resolves — open, low-priority.
- **A BOM bump verified only against `compileDebugKotlin` (main sources) is not fully verified.**
  This exact composeBom bump compiled main sources cleanly, but broke `compileDebugAndroidTestKotlin`
  weeks later — `assertDoesNotExist()` moved from a top-level Compose UI test extension function
  to an instance member of `SemanticsNodeInteraction` between the old and new Compose UI versions.
  Nobody caught it because no androidTest Compose UI code existed yet to exercise it. **When
  bumping a BOM/umbrella version, compile every source set it can reach** (`compileDebugKotlin`,
  `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`), not just main.

## Scanner Camera Architecture (2026-08-26/27, 4 real fix attempts, same session)

`ui/scanner/ScannerScreen.kt`'s `CameraPreview` had a real, multi-layered bug family — each fix
attempt below addressed a genuinely different root cause, not the same bug guessed at repeatedly:

1. **No focus triggering at all** (`d6d5a1e`) — `bindToLifecycle(...)`'s returned `Camera` object
   was discarded, so nothing could ever call `cameraControl.startFocusAndMetering()`. Fixed:
   `requestFocusAndMetering` helper, initial focus on bind + edge-triggered refocus on card
   detection + tap-to-focus fallback.
2. **Auto-capture raced ahead of focus convergence** (`3e427e4`) — the fix above's completion
   future was discarded, so the pre-existing 1500ms auto-capture timer wasn't gated on focus
   actually locking. Fixed: gate `onCardPresenceChanged(true)` on the focus-metering future
   settling, via a monotonic `focusRequestId` token guarding against stale-request races.
3. **False-positive instant capture** (`1a44acb`) — attempt #2 speculatively added
   `FocusMeteringAction.FLAG_AE` alongside `FLAG_AF`; triggering auto-exposure on bind shifted
   brightness right as the crude contrast-based card detector (`detectCardInFrame`) was sampling,
   tripping false "card detected" on the empty background. Fixed: `FLAG_AF` only (AE was never
   needed), plus an unconditional `POST_BIND_DETECTION_DELAY_MS = 2000L` grace period as
   independent defense-in-depth.
4. **Guide frame forced too-close positioning** (`20c74f3`) — the REAL root cause behind
   persistent real-device blur reports, confirmed via 4 convergent signals: the phone's main
   lens has an 18cm documented minimum focus distance (see
   `../Digital Brain/wiki/lifestyle/tech/skyler-phone-specs.md`), the old 75%-width guide frame
   geometrically required ~5-10cm to fill, Skyler's own real-device testing confirmed the
   in-app failure threshold at ~15-20cm, and a calibration photo pinned the correct new ratio.
   Fixed: `GUIDE_FRAME_WIDTH_RATIO = 0.32f` (was a hardcoded `0.75f` duplicated in two places —
   now one shared constant), plus explicit on-screen distance-guidance text as the
   precision-independent primary fix.

**Still pending real-device confirmation as of 2026-08-28.** If the blur persists after fix #4,
the next step is checking actual hardware AF capability or reworking `detectCardInFrame`'s crude
contrast heuristic — not a 5th blind software patch. Full diagnostic history in `gaps.md`.

**Diagnostic-only logging exists in `ScannerScreen.kt`** (`4bd8bac`) — `Log.i("ScannerFocus", ...)`
per focus request (elapsed time + `isFocusSuccessful`) and a one-time camera AF-capability log at
bind (`LENS_INFO_MINIMUM_FOCUS_DISTANCE`, `CONTROL_AF_AVAILABLE_MODES`). Filter with
`adb logcat -s ScannerFocus:*` when diagnosing further.

## Publish Diff Semantics (2026-08-22, `9768632`)

`PublishRepository.computeDiff()` now keys ADDED/REMOVED/REPLACED off `SnapshotSlot.owned`
transitions uniformly across all 5 binder types, not raw `cardId` nullness. Previously, Personal
Collection and Connecting Art — both of which can have `cardId != null` while genuinely unowned
by design (Personal Collection publishes its *entire* search cache, owned and unowned, dimmed if
unowned) — got every cache-refresh-surfaced unowned card reported as "ADDED" to Discord/the
changelog. **If touching `computeDiff` or any `SnapshotSlot` construction site again: `owned`
must be the single source of truth for "counts as filled," set correctly per binder type
(`assignedCardId != null` for Pokédex/Card History/Unown, the real toggle for CA/PC) — do not
revert to checking `cardId` nullness directly.**

## Binder Backup: Card History Restore Gap + Local Export/Import (2026-09-03/04, shipped uncommitted)

Full `dev-team-pipeline` run (Planner→Coder→Tester→Debugger→re-Tester→3-lens Reviewer, 2 score-loop
iterations) against `docs/specs/2026-08-09-binder-backup-export-import.md`. Score history:
54 → 83 → **90/100, ship** (all three lenses — correctness 94, security 94, maintainability 90).
Full detail: `.pipeline/changes.md`, `.pipeline/review-verdict.md`.

- **Part A**: `RestoreRepository.restore()` refactored to extract a shared `applySnapshot()`
  (decomposed into per-binder `applyPokedex`/`applyConnectingArt`/`applyPersonalCollection`/
  `applyUnown`/`applyCardHistory` functions), now used by both cloud Restore and a new
  `restoreFromSnapshot()` local-import entry point. Card History restore was previously silently
  dropped entirely — now inserts by `cardId`-not-already-present (append-only, idempotent), the
  correct semantics for an entity with no stable per-device ID to overwrite-match against.
- **Part B**: new "Local Backup" Settings section — `BackupExporter`/`BackupImporter`
  (`data/local/backup/`), `BackupEnvelope` (`publish/model/`), `BackupViewModel`/`ImportViewModel`
  + extracted `BackupDialogs.kt` (`ui/settings/`), a new `FileProvider`
  (`exported="false"`, narrow `files-path` scope) + `provider_paths.xml`. Export produces a JSON
  envelope (reuses `PublishRepository.buildSnapshot`) + a raw `.db` copy, shared via
  `ACTION_SEND_MULTIPLE`. Import accepts either format: JSON runs through the same
  `applySnapshot()` cloud Restore uses; `.db` does a WAL-checkpointed, table-identity-gated,
  lower-bound-schema-gated, **atomic** swap (`.importing` temp + `ATOMIC_MOVE`) with a
  `DatabaseBackupManager.backupNow` pre-swap safety copy, then forces the same process-restart
  path on both success AND failure (Room's own contract: no supported reopen after a manual
  `close()` — a failed swap used to leave the shared singleton DB permanently closed with no
  recovery; fixed in the final loop iteration by routing every post-close exit through
  `restartProcess()`, persisting the failure message via a new `PendingImportError`
  `SharedPreferences` object for `MainActivity.onCreate` to surface via `Toast` after the forced
  cold restart).
- New shared `BackupRotation.keepNewest` — reused by both `db_backups/` (pre-migration safety
  copies) and the new `exports/` directory, which also gained an Auto Backup domain exclusion.
- **A real, separate pre-existing bug found and deliberately NOT fixed** (out of scope for this
  pipeline run, flagged not folded in): `DatabaseModule.kt`'s
  `.fallbackToDestructiveMigration()` + `.fallbackToDestructiveMigrationOnDowngrade()` combination
  — per Room 2.6.1's own source, the second call sets `requireMigration = true`, cancelling the
  graceful-recreate behavior the first call is supposed to provide. Live-reproduced: an on-disk
  unmigrable-version database crashes the app on every cold start (`IllegalStateException: A
  migration from 3 to 9 was required but not found`) instead of recreating tables. This makes the
  new `.db` import's lower-bound gate the only thing standing between a mis-picked file and a
  bricked install. Tracked in `gaps.md`, not fixed here.
- **Residual, non-blocking items from the final review pass** (all P2-P4, explicitly not treated
  as blockers by the lenses that found them): a flaky first-run `NoClassDefFoundError` on this
  iteration's two new regression tests (`[Likely]` a Windows KSP-regeneration race, passes on
  re-run, production path independently proven live); `PendingImportError` uses raw
  `SharedPreferences` rather than this codebase's `@Singleton`/DataStore convention for persisted
  state (technically correct choice — DataStore has no synchronous write and would race the
  process kill — but undocumented at the point of deviation); the new `SharedPreferences` file
  isn't excluded from Android's Auto Backup domain. Full detail in `gaps.md`.
- **`connectedDebugAndroidTest` cannot run in this dev sandbox at all** — confirmed via a
  throwaway worktree at `HEAD` (`92c5ef9`, containing none of this feature's code):
  `hiltJavaCompileDebugAndroidTest` dies on `IllegalStateException: Unable to read Kotlin metadata
  due to unsupported metadata version`. Pre-existing infra issue, not caused by this feature —
  every claim in this pipeline that needed real-device proof used direct `adb`/live-emulator
  verification instead (device-fixture manipulation, `pidof` process-kill proof, byte-level cache
  diffing), not the instrumented test harness.

## Cross-Source Card Dedup (2026-08-27, `9aefc38`)

`CardSearchRepository.mergeResults()` (used by `searchByName`, called by Personal Collection,
Manual Search, and QuickScan) now dedupes cross-source (pokemontcg.io/TCGdex) results via the
existing `isSameCard()` matcher (name+number, setName-agnostic) instead of an exact
`name|number|setName` string key — the two sources format set names differently for the same
physical card, so the old key let real duplicates through, most visibly in Personal Collection's
permanently-cached list. `isSameCard` was already the correct pattern (built for the identical
bug class on the TCGCSV merge path) — reused, not reinvented.

## Test Infrastructure (2026-08-28)

- **First Hilt-instrumented test added** (`AppNavigationScreenTest.kt`, `92c5ef9`) — required
  `CustomTestRunner.kt` (swaps in `HiltTestApplication` via `AndroidJUnitRunner`) and the
  `hilt-android-testing` dependency. **Any future Hilt-instrumented test that touches Room must
  use `di/FakeDatabaseModule.kt`'s pattern** (`@TestInstallIn(replaces = [DatabaseModule::class])`,
  in-memory Room DB, all 5 real DAOs mirrored) — a real prior mistake here (caught by review, not
  shipped) had an instrumented test running against the actual on-device `pokedex_binder.db` file,
  which two other files in this codebase (`DatabaseModule.kt`, `DatabaseModuleWiringTest.kt`)
  already explicitly say is unacceptable.
- **Screens whose ViewModel eagerly fetches over the network on `init` need their cache
  pre-seeded in a Hilt-instrumented test's `@Before`**, or the test risks a real network call and
  a Compose-idle-sync hang on an indefinite loading spinner (found via review: Personal
  Collection's `refreshAll()`-on-empty-cache path). Seed via the real DAO, not a mock.
- **A local Android emulator now exists in the dev sandbox** — AVD name `pokedex_test`, Android 36
  Google Play x86_64, WHPX-hardware-accelerated (~15s cold boot). Confirmed working end-to-end
  (build → install → launch → logcat crash-check) 2026-08-27. **Cannot help verify real camera/AF
  behavior** — no real autofocus hardware in an emulated camera backend — but closes the standing
  "everything is compile-level only" gap for everything else (crash checks, UI flow verification,
  running instrumented tests that don't need real camera hardware).
- **`connectedDebugAndroidTest` fully works now (2026-09-04)** — the standing "can't run
  instrumented tests in this sandbox" limitation is gone. Root cause was never the
  emulator/hardware: `hiltJavaCompileDebugAndroidTest` died because Dagger 2.51.1's bundled
  Kotlin-metadata reader couldn't parse Kotlin 2.1.20's metadata format
  (`IllegalStateException: Unable to read Kotlin metadata due to unsupported metadata version`).
  Fixed by bumping `hilt` to `2.57` (`gradle/libs.versions.toml`) — Dagger's own changelog confirms
  2.57 "unshades the Kotlinx Metadata to support Kotlin 2.2.0." Deliberately not bumped to latest
  stable (2.60.1 at the time) — that jump drops multidex support and raises min SDK, real
  unrelated risk this project's own BOM-bump history says to avoid; 2.57's only breaking change
  (generated `Factory`/`MembersInjector` constructors going private) is harmless here. Verified in
  an isolated git worktree before touching the real checkout, then confirmed live on the real
  `pokedex_test` AVD: `AppNavigationScreenTest` and `DatabaseModuleWiringTest` executed and passed
  for the first time ever. **Gradle's own task-level pass/fail is separately unreliable** in this
  sandbox (`"Failed to receive the UTP test results"`, a UTP↔Gradle IPC glitch, reproduced twice) —
  read `app/build/outputs/androidTest-results/connected/debug/.../test-result.textproto` directly
  to check real per-test results, don't trust Gradle's exit code alone. Full detail: `gaps.md`.

## Conventions

- Gradle needs explicit env vars in the dev sandbox:
  `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`, `TEMP=TMP=C:\Windows\Temp`. Without these, `gradlew.bat`
  fails at daemon startup with a misleading `Unable to establish loopback connection` error that
  looks like a sandbox/JVM restriction but isn't — always set these before concluding a build
  command is broken.
- **First-time dependency fetches may fail with a `PKIX path validation failed` TLS error** —
  this machine's antivirus/security software does TLS inspection and installs its interception
  root CA into Windows' trust store but not the JDK's own `cacerts`. Workaround (session-only,
  no repo/JDK files touched): `$env:GRADLE_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"`.
  Recurs on every new not-yet-cached dependency; consider fixing at the machine level (import the
  real interception cert into the JDK's `cacerts`, or make the `GRADLE_OPTS` permanent) if it
  keeps resurfacing.
- **No dedicated PowerShell tool exists in this harness** — only Bash (Git Bash). To run
  `gradlew.bat`: write a real `.ps1` script file (setting `$env:JAVA_HOME`/`$env:TEMP`/`$env:TMP`
  inside it) and invoke via Bash with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File
  "<path>.ps1"`. **Prefer a real `.ps1` file over an inline `-Command "..."` string** — inline
  `$env:VAR` assignments get mangled through Bash's own `$`-expansion before reaching PowerShell.
- **When re-verifying another stage's/agent's build claim, force `--rerun-tasks`** — a bare
  `UP-TO-DATE` result only proves someone built it successfully at some earlier point (possibly
  the very claim under review), not that this verification pass actually executed anything.
- **When a self-reported test count looks suspicious, delete
  `app/build/test-results/testDebugUnitTest/` before re-running**, don't trust `--rerun-tasks`
  alone to invalidate a stale/partial read from an earlier interrupted invocation in the same
  session — count directly from the freshly-regenerated XML, not the runner's own summary.
- **For "is this symbol a top-level extension function or an instance member" questions**
  (matters for whether an import is valid), a `-sources.jar` alone can't answer it — extract the
  compiled AAR/`classes.jar` and run `javap -p <Class>.class`. Kotlin extension functions compile
  into a synthetic `<File>Kt` class, invisible to a text read of the `.kt` source alone.
- Full reinstall required after any code change — Android Studio's "Apply Changes" (hot-swap)
  silently skips new composables/nav routes/DB migrations. This caused multiple "my fix isn't
  showing up" false alarms this session.
- Feature work goes through `dev-team-pipeline` (Planner→Coder→Tester→Reviewer,
  `.pipeline/` handoff files) for anything multi-file/architectural. Contained single-file UI
  fixes and urgent live-debugging are done directly. Archive `.pipeline/` outputs to
  `.pipeline_archive/<date>-<slug>/` before starting a new, unrelated pipeline run.
- **This repo's `origin/HEAD` points at a stale, near-empty `main` branch** — all real work lives
  on `master`. `EnterWorktree`'s default `baseRef: fresh` branches from `origin/<default-branch>`,
  which resolves to the broken `main`, landing on a first-commit-only checkout. Skip worktree
  isolation for pipeline runs in this repo; branch directly off `master` in the real checkout
  instead.
- No git remote until 2026-07-12 — `github.com/SkylerMayday/skyler-pokedex-binder`, private.
