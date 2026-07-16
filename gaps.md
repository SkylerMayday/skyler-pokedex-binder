# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned.

## Refreshed — 2026-07-15 (session 2)

### Data-integrity / migration risk (highest priority)

- ~~**`fallbackToDestructiveMigrationOnDowngrade()` is a live landmine, not a theoretical risk.**~~
  **Mitigated 2026-07-16.** Confirmed 2026-07-15: reverting the Room schema version from v8 to v7
  wiped Skyler's entire local database (Pokédex, Card History, Connecting Art, Personal
  Collection — everything) with no warning, no confirmation dialog, nothing. Fixed via a new
  `data/local/backup/` package (`SqliteVersionReader`, `MigrationPathResolver`,
  `DatabaseBackupManager`) wired into `DatabaseModule.provideDatabase`: before
  `Room.databaseBuilder(...).build()` runs, it reads the on-disk `PRAGMA user_version` directly
  (no Room involved yet), checks via BFS whether `PokedexDatabase.ALL_MIGRATIONS` has an unbroken
  path to `SCHEMA_VERSION`, and if not — meaning either `fallbackToDestructiveMigration()` (also
  confirmed enabled, upgrade-side) or `fallbackToDestructiveMigrationOnDowngrade()` is about to
  fire — copies the `.db` file plus `-wal`/`-shm` sidecars into `context.filesDir/db_backups/`
  first, rotating to keep the newest 5 sets. Backup failures are logged and swallowed, never block
  startup. Went through the full `dev-team-pipeline` (Planner→Coder→Tester→Reviewer), verdict
  **SHIP**. 14 new JVM unit tests + 1 new instrumented test (compiles, not run — see standing
  no-emulator constraint below); full suite 182/182, no regressions.
  **Known gap, not yet closed:** no test proves the `DatabaseModule.provideDatabase` wiring itself
  is in place — the Tester stage ran a mutation test (removed the backup call, restored it
  afterward, confirmed via `git diff`) and the full suite still passed green with the call
  missing. All 14 new tests validate `DatabaseBackupManager` in isolation; none prove it's actually
  called from production code. A silent future removal of that one line would ship undetected. Fix
  would require an instrumented `@HiltAndroidTest` — blocked by the same no-emulator constraint as
  everything else in this list. Consistent with this project's existing pattern (no Hilt module in
  this codebase has ever had a wiring test), so not treated as a regression, but flagged distinctly
  here since this specific feature exists to prevent a proven data-loss incident.
  **This is a safety net, not a restore feature** — no in-app UI to browse/restore backup files;
  restoring one currently requires manual `adb` file access. Explicitly out of scope per the spec.
- **`Migration7to8Test.kt` still doesn't exist** — accepted, not being pursued (Skyler, 2026-07-16:
  "we will most likely not go back to 7"). Every prior Room migration (4→5, 5→6, 6→7) has a
  paired `androidTest` following the `Migration6to7Test` template. The v7→v8 migration (adding
  `unown_binder`) was built, then the standalone Unown binder went through several rebuilds, and
  the test was never recreated for the final version. `Migration8to9Test.kt` (added this session,
  for the language/lock/remarks columns) does not fill this gap — it's a separate migration.
- ~~**`Migration8to9Test.kt` (new, added 2026-07-15) has never run on a real device/emulator**~~ —
  the standalone JUnit instrumented test itself was still never formally executed in this sandbox
  (no emulator), but Skyler confirmed 2026-07-16 that every actual test he performs is on his real
  phone — meaning the v8→v9 migration this test covers has already run for real through normal app
  usage/updates since it shipped. Counted as effectively verified; not re-flagging further.
- **`Migration5to6Test`/`Migration6to7Test` have never actually executed as standalone JUnit runs**
  — accepted, low priority (Skyler, 2026-07-16: "we will most likely not go back to 7"), same
  reasoning as `Migration7to8Test.kt` above. Oldest open item in the project, kept open only
  because it's cheap to note, not because it's actively being pursued.

### Test coverage gaps

- **No integration/instrumented test verifies a full publish→restore round trip actually
  preserves Connecting Art / Personal Collection / Unown data in practice.** All three were
  built and unit-tested with mocked repositories this session; the actual GitHub Pages output
  (binder.json shape, whether the website project's shelf-grouping logic renders it correctly)
  has never been checked end-to-end.
- **`AppNavigationScreenTest.kt` was deleted during Unown's multiple reworks and never
  recreated** for the current (final) nav layout — no test coverage on route wiring/drawer order
  at all right now.
- **`QuickScanViewModelTest.kt` only covers the new `searchStreaming` wiring** (6 tests added
  this session) — no regression coverage for the rest of `QuickScanViewModel`'s existing
  behavior (slot-matching logic, manual entry flow, secondary-binder fallback).

### Performance / efficiency

- **TCGCSV's ~10-13s full-group-scan now runs on every search, not just retries.** Accepted,
  no action (Skyler, 2026-07-16: "it's fine"). Since `searchStreaming` fires TCGCSV concurrently on
  every `ManualSearchViewModel`/`QuickScanViewModel` search, every single card search does a
  ~217-request background fan-out. Bounded to 8 concurrent connections, wrapped in try/catch,
  doesn't block the fast-path UI. Not being revisited unless it becomes noticeable in practice.

### Cross-repo contract

- **`binder.json` now carries `language`/`remarks`/`isLocked` (as of `7921b82`), but the website
  repo (`skylermayday-site`) doesn't consume them yet** — being worked on in a separate
  `skylermayday-site` session (Skyler, 2026-07-16: "i alr have the website sesh to work on it").
  Not a bug in this repo; tracked here only so the contract gap isn't lost, not as an action item
  for this project.

### Design decisions worth re-flagging as debt, not fully "gaps"

- **Personal Collection publishes its entire search cache (owned AND unowned cards), not just
  what Skyler owns.** Intentional (confirmed decision, 2026-07-12) — the public site shows every
  card the search found for Charizard/Celebi/etc., dimmed if unowned. Worth re-confirming this
  is still the desired public-facing behavior before the next publish, since it means anyone
  looking at the site can see the full search result set, not just Skyler's actual collection.
- **No manual card-entry fallback for Connecting Art or Personal Collection's search flow.**
  Accepted, no action (Skyler, 2026-07-16: "fine to have no manual fallback for CA/PC currently").
  `QuickScanViewModel` has a `ManualEntry` state (type in name/set/number/image URL) for cards
  missing from all 3 APIs; `ManualSearchScreen` (used by Connecting Art + Card History's "Add to
  Secondary") has no equivalent.

### Housekeeping

- ~~Nothing committed since `e42eeb5` (2026-07-10)~~ — **fixed 2026-07-15.** All prior-session work
  committed as `b0686a0`; this session's work committed as `c632c52` and `7921b82`, both pushed to
  `origin/master`.
- **Two independent pre-existing bugs in `PokedexDatabaseTest.kt`** (missing
  `androidTestImplementation(turbine)`, a `MainBinderEntry(...)` call missing `dexOrder`) blocked
  `compileDebugAndroidTestKotlin` for the whole module until Skyler flagged it and it was fixed
  2026-07-15 (commit `7921b82`). Resolved, no longer a gap — noted here only so the fix's history
  is visible; remove this line on the next audit.
- **No real device/emulator available in this dev sandbox at all.** Every "verified" claim this
  session (and the one before it) is build/unit-test/compile-level only — nothing has been
  confirmed by actually running the app. This is a standing constraint, not a one-off gap; keep
  flagging it per-session until Skyler does an on-device pass.
