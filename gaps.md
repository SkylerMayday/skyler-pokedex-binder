# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned.

## Refreshed — 2026-08-28 (session 6, cont'd)

### Fixed this session

- ~~**No test coverage on nav route wiring/drawer order; `QuickScanViewModelTest.kt` only covered
  `searchStreaming`.**~~ **Fixed 2026-08-28.** Added `AppNavigationRouteTest.kt` (JVM, 12 tests —
  route uniqueness, exact route strings, `createRoute()` URL-encoding including real production
  edge cases like Unown's `!`/`?` letter IDs and "Mr. Mime") and `AppNavigationScreenTest.kt` (this
  project's **first Hilt-instrumented test** — drawer order, reachability of all 5 destinations,
  confirms the Scan button's default-hidden behavior found earlier this session). Extended
  `QuickScanViewModelTest.kt` for slot-matching/manual-entry/secondary-binder-fallback plus a gap
  the Planner found independently (the `search()` query-routing lambda had never been exercised).
  Went through a real multi-lens review cycle: pass 1 (3 parallel Opus lenses) scored 78/100
  needs-changes — the security lens caught that the new instrumented test ran against the REAL
  on-device database and made real network calls, reversing an explicit prior decision documented
  in `DatabaseModule.kt`/`DatabaseModuleWiringTest.kt` that this is unacceptable. Fixed with a new
  `FakeDatabaseModule.kt` (`@TestInstallIn`-replaced in-memory Room DB, all 5 DAOs) plus
  deterministic settings/cache seeding in the test's own `@Before`. Pass 2 (rendered by the
  orchestrator directly after 3 consecutive Opus-lens spawns died instantly to account spend-limit
  exhaustion — deviation disclosed in `.pipeline/review-verdict.md`, grounded in the Tester's own
  thorough re-verification) scored 97/100, ship. 226/226 unit tests, 0 lint errors, zero production
  files touched. Real bug found and fixed along the way: a Compose UI test API (`assertDoesNotExist`)
  moved from a top-level extension function to an instance member between Compose UI versions —
  root-caused via `javap` on the actual compiled bytecode, not guessed.
  **`AppNavigationScreenTest.kt` has never actually executed — no AVD in this sandbox.** Everything
  above is structural/traced verification against real code. Pending Skyler's own
  `connectedAndroidTest` run to confirm the drawer-order disambiguation logic holds against real
  rendered semantics.

- ~~**Cross-source card duplicates could survive into Personal Collection's permanently cached
  list.**~~ **Fixed 2026-08-27.** Skyler asked to "ensure there are not duplicate entries" in
  Personal Collection before confirming its full-search-cache design was working as intended.
  Root cause: `CardSearchRepository.mergeResults()` (used by `searchByName()`, which
  `PersonalCollectionRepository.refreshPokemon()` calls) deduped cross-source results
  (pokemontcg.io/TCGdex) via an exact `name|number|setName` string match — the two sources format
  set names differently for the same physical card (e.g. a human-readable name vs. an uppercase
  set-code abbreviation), so the key mismatched and the same card could survive as two rows. The
  file already had the correct fix pattern for this exact bug class one function away —
  `isSameCard()` (name+number, setName-agnostic), built for the identical problem on the TCGCSV
  merge path with an explicit comment: "this is what prevents the 'duplicate second Victini'
  entry." `mergeResults` now reuses it. Also benefits the 2 other consumers of `searchByName`
  (Manual Search, QuickScan). 191/191 unit tests, 2 new regression tests (mismatched-setName
  duplicate merges to one; same-name-different-number cards both still survive — proving the fix
  isn't over-aggressive), ship at 98/100.
- **Missing `@OptIn` on this session's diagnostic camera-logging code (`4bd8bac`)** — caught by
  lint, not by the person who wrote it. Used the bare `@ExperimentalCamera2Interop` marker instead
  of `@OptIn(...)`, which propagates the experimental requirement to callers instead of consuming
  it — 4 lint errors, only found because an unrelated pipeline run happened to check `lintDebug`
  fresh (the diagnostic commit itself was only verified with `assembleDebug` + tests, lint was
  never run on it). Fixed: extracted into its own function with the correct
  `androidx.annotation.OptIn(ExperimentalCamera2Interop::class)` — specifically the AndroidX
  Java-interop annotation, not Kotlin's own `kotlin.OptIn`, which this particular Lint check
  (`UnsafeOptInUsageError` from `androidx.annotation.experimental`) doesn't recognize. `lintDebug`
  0 errors, confirmed clean.
- ~~**Scanner guide frame geometrically forced holding cards inside the camera's minimum focus
  distance.**~~ **Fixed 2026-08-27.** Real root cause behind the persistent "still as blurry when
  the card is in frame" report — genuinely different from attempts #1-3 (`d6d5a1e`/`3e427e4`/
  `1a44acb`, all correctly fixed real focus-trigger/timing/false-capture bugs, none of which were
  actually it). Confirmed via 4 convergent, independent signals: (1) the S26 Ultra's main lens has
  a documented 18cm minimum focus distance (up from 8cm last gen — a real hardware regression
  Samsung made for a thinner body); (2) Skyler's own real-device testing in the actual app: "auto
  focus works when the card is placed further away... anything closer, the focus will not
  trigger"; (3) Skyler estimated the real failure threshold at ~15-20cm, matching the documented
  spec almost exactly; (4) a calibration photo (properly EXIF-rotated to portrait, matching the
  app's scan orientation) at that boundary distance, showing the card occupying ~40-45% of frame
  width — vs. the app's guide frame demanding 75%, which geometrically requires ~5-10cm, deep
  inside the failure zone. Fixed: extracted the two independently-hardcoded `0.75f` copies
  (`detectCardInFrame`'s detection geometry, `CardFrameOverlay`'s drawn UI — previously had to be
  kept in sync by hand) into one shared `GUIDE_FRAME_WIDTH_RATIO = 0.32f` constant, derived from
  the calibration photo scaled to a safe ~23cm target (Planner's independent re-derivation landed
  on the same value). Added explicit on-screen distance-guidance text ("Hold card ~9 in / 23 cm
  back") as the precision-independent primary fix — correct even if the exact ratio isn't. Ship at
  98/100; Reviewer caught and disproved a self-contradictory Tester claim about a shrunk text
  margin via direct Compose layout tracing (no real issue, ship as-is).
  **Still needs real-device confirmation — same standing constraint as every scanner fix this
  session.** If blur persists, Reviewer's suggested next move: lower toward 0.30/0.24 (already
  computed bounds) before re-deriving from scratch.

## Refreshed — 2026-08-22 (session 5)

### Fixed this session

- ~~**Publish/Discord diff falsely reported unowned cards as "ADDED."**~~ **Fixed 2026-08-22.**
  Skyler's Discord webhook posted "Pokédex Binder updated: +294 more" listing dozens of Charizard
  TCG prints (BASE1, PL4, GYM2, SM7.5, etc.) he never marked owned — confirmed in-app the cards
  correctly showed as unowned/dimmed, so the bug was purely in what got published/notified, not in
  actual ownership state. Root cause: `PublishRepository.computeDiff()` decided ADDED/REMOVED by
  checking `SnapshotSlot.cardId` nullness — correct for the Pokédex/Card History/Unown binders
  (where `cardId` genuinely is null until owned) but wrong for Personal Collection and Connecting
  Art, both of which by design can have `cardId != null` while unowned (Personal Collection
  publishes its entire search cache, owned and unowned, dimmed if unowned — confirmed intentional
  design from an earlier session). A local reinstall this session wiped the Room DB, the app's own
  `if (cacheCount() == 0) refreshAll()` re-populated Personal Collection's cache fresh from a full
  TCG search, and every newly-cached (still-unowned) row got reported as ADDED since `computeDiff`
  never consulted the separate `owned` field for those branches.
  Fixed by making `SnapshotSlot.owned` the single correctly-populated "counts as filled" signal
  across all 5 binder types — 3 construction sites (Pokédex, Card History, Unown) were silently
  relying on `owned`'s surprising `true` default instead of setting it from `assignedCardId`/`cardId`
  nullability — then rewrote `computeDiff`'s ADDED/REMOVED/REPLACED classification to key off `owned`
  transitions instead of raw `cardId` nullness, for every binder type uniformly. Confirmed with
  Skyler directly: Discord should only say "added" on a genuine ownership change, never on a cache
  refresh surfacing more unowned candidates. `binder.json`'s schema is unchanged (additive field,
  no restructure) — the website consumer is unaffected. 189/189 unit tests (182 baseline + 7 new,
  including a direct regression test reproducing the exact reported scenario), ship at 100/100.

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
- ~~**`Migration8to9Test.kt` (new, added 2026-07-15) has never run on a real device/emulator**~~ —
  the standalone JUnit instrumented test itself was still never formally executed in this sandbox
  (no emulator), but Skyler confirmed 2026-07-16 that every actual test he performs is on his real
  phone — meaning the v8→v9 migration this test covers has already run for real through normal app
  usage/updates since it shipped. Counted as effectively verified; not re-flagging further.

### Test coverage gaps

- **No integration/instrumented test verifies a full publish→restore round trip actually
  preserves Connecting Art / Personal Collection / Unown data in practice.** All three were
  built and unit-tested with mocked repositories this session; the actual GitHub Pages output
  (binder.json shape, whether the website project's shelf-grouping logic renders it correctly)
  has never been checked end-to-end.
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
- ~~No real device/emulator available in this dev sandbox at all.~~ **Resolved 2026-08-22.** Built
  a local AVD (`pokedex_test`, Android 36 Google Play x86_64, WHPX-accelerated) — build/install/
  launch/logcat-crash-check confirmed working end-to-end. Every "verified" claim can now go one
  step further than compile-level (actually launches, doesn't crash, logcat is inspectable) —
  still can't validate real camera/sensor/hardware-dependent behavior (no real AF hardware in an
  emulated camera backend), so on-device passes for anything camera-related are still required.
