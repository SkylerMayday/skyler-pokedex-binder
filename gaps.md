# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned.

## Refreshed — 2026-08-21 (session 4)

### Open, needs real-device confirmation

- **Scanner camera never actually focused — no `Camera` handle, no tap-to-focus, no metering
  point tied to the guide frame.** Skyler reported "camera seems to be very blurry and unable to
  focus on card when card is placed within the frame of the OCR." Root cause:
  `CameraPreview` (`ui/scanner/ScannerScreen.kt`) discarded the `Camera` object
  `bindToLifecycle(...)` returns, so nothing could ever call `cameraControl.startFocusAndMetering()`
  — the camera was running on default continuous AF with zero metering hint, at typical card-scan
  distance (~10-15cm) where that alone often can't lock. Confirmed via CameraX 1.6.1 release notes
  check this predates today's `camerax` version bump — identical gap at 1.3.4, not a regression.
  Fixed via a new `requestFocusAndMetering` helper: initial focus-and-meter on bind completion
  (targeting the guide-frame center, geometrically confirmed equal to
  `previewView.width/2f, previewView.height/2f`), edge-triggered refocus on the card-detection
  analyzer's not-detected→detected transition, plus tap-to-focus as a manual fallback. Every call
  site guarded (`camera?.let`, zero-size early-return, `runCatching`), bounded 4s auto-cancel —
  worst case on real hardware is a no-op, not a crash. Ran through `dev-team-pipeline`
  (Planner→Coder→Tester→Reviewer), verdict **ship at 95/100**; all 10 new CameraX API call sites
  verified twice independently against the actual `camerax:1.6.1` `-sources.jar` in the Gradle
  cache (not guessed, not from possibly-stale docs).
  **Attempt #1 confirmed insufficient on real hardware (Skyler, same day): "camera doesnt fix the
  blur... i cant tap it in time - it will just say card detected and take the snap."**

  **Attempt #2, same day:** root cause was a race, not a missing feature — `onCardPresenceChanged`
  (which drives `ScannerScreen`'s 1500ms auto-capture countdown) fired the instant raw
  contrast-detection succeeded, the *same instant* attempt #1's refocus request went out, and
  attempt #1 explicitly discarded the `ListenableFuture<FocusMeteringResult>` that call returns —
  nothing gated the countdown on focus actually converging. CameraX AF convergence at macro
  distance routinely exceeds 1500ms on budget hardware; the shutter fired mid-defocus, exactly
  matching what Skyler saw (card "detected" almost instantly, no time to even test tap-to-focus).
  Fixed by gating `onCardPresenceChanged(true)` on BOTH card-detected AND the most recent
  focus-metering request having settled (via the previously-discarded future), guarded by a
  monotonic `focusRequestId` token so a stale/superseded request's late completion can never
  incorrectly mark a newer request as locked — entirely inside `CameraPreview`, `ScannerScreen`'s
  own timer logic untouched. Went through a full Reviewer auto-loop this time: pass 1 caught a
  real single-frame ordering bug (`focusLocked` read before the same-frame `triggerFocus()` reset
  it — self-corrects next frame, doesn't reintroduce the blur, but violated spec AC1), scored
  82/100; Coder fixed it with a `justTransitioned` guard, pass 2 verified the fix via independent
  frame-trace + boolean-algebra proof, scored 99/100, **ship**.
  **Attempt #2 confirmed insufficient on real hardware too — but a different failure mode, not the
  blur returning (Skyler, same day): "now it instantly says card detected and take the shot, before
  i even place the card into the frame."** Not a phone-hardware issue (confirmed explicitly — this
  is new code added this session, would happen identically on any phone running this exact build,
  including his prior S23 Ultra). Root cause: attempt #1/#2's `FocusMeteringAction` speculatively
  included `FLAG_AE` (auto-exposure) alongside `FLAG_AF`, never actually needed for the blur fix.
  Triggering AE on camera bind (before any card is present) shifts exposure right as
  `detectCardInFrame`'s crude contrast heuristic (`totalContrast/count > 20f`, border-edge luma
  difference only, no real card-shape validation) samples frames — false "card detected" trip,
  confirmed by Skyler to happen **regardless of background** (ruling out a texture-specific
  detector flaw, pointing squarely at the exposure trigger). Because background/empty-scene
  autofocus converges fast (easy target vs. macro-focusing a close card), attempt #2's
  focus-lock gate settled quickly against the background too, not meaningfully delaying the false
  capture.

  **Attempt #3, same day:** (1) removed `FLAG_AE`, `FLAG_AF`-only now — reverts the scope-creep
  addition that's the confirmed-plausible trigger, no downside since AE was never needed for
  sharpness. (2) Added defense-in-depth independent of that hypothesis: `POST_BIND_DETECTION_DELAY_MS
  = 2000L` unconditionally blocks `onCardPresenceChanged(true)` for 2s after camera bind regardless
  of detector/focus-lock state — directly addresses "before I even place the card" literally, not
  contingent on the AE theory being the whole explanation. Reviewer traced the interaction with the
  pre-existing 1500ms `holdDurationMs` continuous-detection hold and confirmed an unbypassable floor
  of `bindCompletedAtMs + 2000ms + 1500ms ≈ 3.5s` before any capture can fire — every `false` report
  during the grace window resets `ScannerScreen`'s `detectionStartMs`, so the two timers can't be
  raced against each other. Ship at 99/100, single-pass (no auto-loop needed this time).

  **Still not yet verified on real hardware — no emulator in this sandbox, same standing constraint
  as attempts #1 and #2.** 182/182 unit tests, clean build, `FLAG_AE` confirmed fully removed
  (grepped whole file), timer-interaction proof independently traced twice (Tester + Reviewer) — but
  zero evidence yet either failure mode (blur, false-capture) is actually resolved in practice.
  **Skyler needs another full reinstall + real-device test, this build in isolation from any other
  scanner change.** Per the Reviewer's explicit framing: attempt #2's bug and attempt #3's bug are
  NOT the same recurring failure — attempt #2 genuinely fixed the race it targeted and, in doing so,
  exposed a distinct second bug (the AE-triggered false capture) that attempt #3 is the first real
  attempt at. **If attempt #3 also fails on this exact symptom** (instant/early false capture), that
  would point at `detectCardInFrame`'s heuristic itself needing real work, not another gating patch
  — per the spec's escalation path, the next step should be either a properly `planning`-first
  rebuild of the detector with real card-shape/rectangle detection, or falling back to
  manual-capture-only (the existing "Capture" button) with auto-capture disabled until a more
  robust detector exists — not a 4th blind software patch. If instead the ORIGINAL blur returns
  with AE removed, that's the separate escalation path already noted: check actual camera hardware
  AF capability (`CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE`).

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
  ~~**Known gap: no test proved the `DatabaseModule.provideDatabase` wiring itself is in
  place**~~ — **closed 2026-07-16.** The Tester stage had run a mutation test (removed the backup
  call, restored it afterward, confirmed via `git diff`) and found the full suite still passed
  green with the call missing — none of the 14 tests proved `DatabaseBackupManager` was actually
  called from production code, only that it worked correctly in isolation. Fixed without needing
  full Hilt test infra (this project has none — no `hilt-android-testing` dependency, no
  `HiltTestApplication`): extracted `DatabaseModule.provideDatabase`'s body into an
  `internal fun buildDatabase(context, dbFileName)` that `provideDatabase` now one-line-delegates
  to, then added `di/DatabaseModuleWiringTest.kt` (androidTest, 2 tests) which calls
  `DatabaseModule.buildDatabase` directly — the exact function `provideDatabase` delegates to, not
  a reimplementation — against a test-only file name (never the real on-device `pokedex_binder.db`,
  since `provideDatabase` itself still hardcodes that and was deliberately left uncalled by the
  test). A future accidental removal of the backup call from `buildDatabase` now fails this test.
  Residual, accepted limitation: doesn't cover the one-line delegation in `provideDatabase` itself
  (e.g. if someone bypassed `buildDatabase` entirely) — judged not worth chasing further, since
  that's a much less likely mistake than dropping the backup call, which is the actual risk this
  guards against. Compiles; not executed live (same standing no-emulator constraint as every other
  instrumented test in this project).
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
- **No real device/emulator available in this dev sandbox at all.** Every "verified" claim this
  session (and the one before it) is build/unit-test/compile-level only — nothing has been
  confirmed by actually running the app. This is a standing constraint, not a one-off gap; keep
  flagging it per-session until Skyler does an on-device pass.
