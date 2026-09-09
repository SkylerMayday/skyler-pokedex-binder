# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned.

## Refreshed — 2026-09-09 (session 12, mid-session — full wrapcon pending)

### Fixed this session (build-confirmed via `dev-team-pipeline`, ship at 94/100)

- ~~**Crop rect is ~1.4x larger linearly (~1.9x by area) than the guide box `CardFrameOverlay`
  draws on screen**~~ (carried from session 7/9, open across attempts #1-6). **Fixed.** Root cause
  confirmed via decompiled CameraX 1.6.1 bytecode/sources (javap on the real `camera-core-1.6.1.aar`
  / `camera-lifecycle-1.6.1.aar`, javadoc from `camera-view-1.6.1-sources.jar` — not guessed):
  `CameraPreview`'s `bindToLifecycle` calls used the plain vararg overload with no `UseCaseGroup`/
  `ViewPort`, so nothing constrained Preview/ImageCapture/ImageAnalysis to a shared field of view;
  `PreviewView`'s default `FILL_CENTER` scaling then center-crops what's actually displayed, while
  `guideFrameImageRect` computed the crop fraction against the captured JPEG's FULL raw width/
  height — a larger denominator than what's on screen, for the same `GUIDE_FRAME_WIDTH_RATIO`
  fraction. Fixed: `bindPreviewCaptureAnalysis` (`ScannerScreen.kt`) builds a `UseCaseGroup` with
  `previewView.viewPort` as the shared `ViewPort` (falls back to the old vararg bind when
  `viewPort` is null — view not yet laid out), and `ImageProxyExt.kt`'s `toCroppedBitmap()` now
  crops against `ImageProxy.cropRect` (ViewPort-aligned) instead of the full frame, repositioning
  via a new `ImageRect.offsetBy`. No-`ViewPort` path is a provable no-op (byte-identical to pre-fix
  behavior). `testDebugUnitTest --rerun-tasks`: 289/289 pass (286 baseline + 3 new), fresh XML.
  `assembleDebug --rerun-tasks`: clean. **Still needs Skyler's real S26 Ultra to visually confirm**
  the crop now matches the guide box on screen — no real multi-camera hardware in this sandbox to
  check that visually, same standing constraint as every other scanner fix. Full detail:
  `.pipeline_archive/2026-09-09-scanner-crop-guide-mismatch/` (pending archive).

### New, found during this session's Reviewer pass (P2, not fixed — deferred by Reviewer's own call)

- **`ImageProxyExt.kt`'s `toCroppedBitmap()` can throw `IllegalArgumentException` on a degenerate
  (zero-width/height) `cropRect`, bypassing the function's own graceful-fallback guard.** New
  failure surface introduced by the crop-rect fix above: `guideFrameImageRect(cropWidth, cropHeight,
  ...)` is called outside the `runCatching` block, and `ScannerScreen.kt`'s internal
  `coerceIn(0, rawWidth - 1)` throws if `cropWidth == 0` (`rawWidth - 1` becomes `-1`,
  `coerceIn(0, -1)` is an empty range). Pre-fix this was unreachable (`ImageProxy.width`/`.height`
  are never zero for a real frame); a `ViewPort`-derived `cropRect` is structurally new input.
  Contained — `ScannerViewModel.kt:90`'s outer `catch(Exception)` prevents a crash, just surfaces a
  confusing `ScannerState.Error` instead of the intended uncropped-fallback behavior. Likelihood
  low (a real `ViewPort` on a laid-out `PreviewView` shouldn't compute a zero-area `cropRect` under
  normal CameraX operation) — Reviewer's own call was ship-as-is, defer. Cheap fix when picked up:
  early-return before calling `guideFrameImageRect` if `cropWidth <= 0 || cropHeight <= 0`.

### Recurred this session (same signature as session 10/11, still not root-caused, still
self-resolving — now with one added data point)

- **The Gradle `java.io.IOException: Unable to establish loopback connection` wall came back this
  session**, mid-pipeline, then went away again without any fix applied — genuinely intermittent,
  not deterministic. New data point: it failed 5/5 times for a Tester-stage subagent while
  succeeding twice in direct orchestrator-run commands in the same session, same repo state, same
  `.ps1`/env-var invocation pattern — rules out anything about the diff or the command itself,
  points at some session/process-level resource contention (Tailscale adapter confirmed `Up` both
  times it was checked, still unconfirmed as the actual cause, not acted on). **New, confirmed-false
  lead: `--no-daemon` is not a fix** — this repo's `gradle.properties` already sets
  `org.gradle.daemon=false` project-wide, so the flag is a no-op here; a session that appeared to
  "fix" it with `--no-daemon` was almost certainly just catching the intermittent window, not
  actually changing anything. Don't credit `--no-daemon` as a real fix if this recurs again.

## Refreshed — 2026-09-09 (session 11)

### Fixed this session (build-confirmed, not just code-reviewed)

- ~~**Session 10's Gradle loopback wall blocked all compilation/test execution.**~~ **Resolved —
  gone this session, no fix needed.** `testDebugUnitTest --rerun-tasks` ran clean first try. Not
  investigated further since it self-resolved; flag for awareness if it recurs.
- ~~**All 6 of session 10's fixes (2 DB tests + 4 scanner cleanups) were code-reviewed/hand-verified
  only, never actually compiled or run.**~~ **Now build-confirmed.** `testDebugUnitTest
  --rerun-tasks`: 286/286, 0 failures (fresh XML count). `connectedDebugAndroidTest --rerun-tasks`
  on the real `pokedex_test` AVD: 20/20, 0 failures (fresh `test-result.textproto`, not Gradle's own
  unreliable task exit code).
- **New (found this session): `ScannerScreenTest`'s degenerate-aspect-ratio test (added session 10,
  never actually executed until now) had a wrong assertion** — expected `rect.bottom == 49` for a
  50px-tall degenerate crop, but `ImageRect.bottom` is documented as an exclusive bound matching
  `Bitmap.createBitmap`'s `(left, top, width, height)` contract, so `50` is correct and maximal, not
  off-by-one. Test-only bug, not a production bug — the implementation was right. Fixed the
  assertions to match the documented contract
  ([ScannerScreenTest.kt:216-221](app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt:216)).
- **New (found this session): `Migration6to7Test`'s raw `INSERT INTO connecting_art_slot` predated
  the `language` NOT NULL column** (2026-07-15 feature) and threw `SQLITE_CONSTRAINT_NOTNULL` the
  first time this specific test method ever actually ran (its own header comment already admitted
  it had never executed live). `ConnectingArtSlot.kt:28`'s `val language: String = "EN"` is a
  Kotlin-side default only — no `@ColumnInfo(defaultValue=...)`, so there's no SQL-level default to
  fall back on. Fixed by adding `language` to the insert
  ([Migration6to7Test.kt:169](app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration6to7Test.kt:169)).
  Audited every other raw `INSERT`/`execSQL` in `androidTest/` for the same stale-pre-column-add
  pattern (`Migration5to6Test`, `Migration8to9Test`, `DatabaseModuleWiringTest`,
  `DatabaseBackupManagerInstrumentedTest`) — all pass, this was isolated, not systemic.
- **New environment fix (not a codebase gap): the `pokedex_test` AVD hung indefinitely on launch**
  (near-zero CPU, no adb/console port ever bound). Root cause in the emulator's own stdout log:
  host-OpenGL GPU init failed to load `opengl32sw`, fell back to software GL, which hung the QEMU
  main loop behind a crash-consent dialog no headless launch can ever click. Fixed by launching with
  `-gpu swiftshader_indirect -no-window` instead of the bare `-avd pokedex_test -no-snapshot-load`
  used in every prior session — boots clean in ~15-20s. **The plain launch command that worked in
  earlier sessions is no longer reliable in this sandbox; use the swiftshader flags going forward.**
- **Confirmed, not fixed (not a bug): `connectedDebugAndroidTest`'s own Gradle task-level result is
  still unreliable.** Reproduced the documented `"Failed to receive the UTP test results"` UTP↔
  Gradle IPC glitch a 3rd time this session (task reports FAILED, real textproto shows 0 failures).
  This project's existing gaps.md/project-overview.md guidance — read the textproto directly, don't
  trust Gradle's exit code — is confirmed still correct, not stale advice.

## Skills applicable to open gaps (audited 2026-09-09, none run here yet)

- **`security-review`** — no security-focused skill pass has ever run here, despite a public web-shareable snapshot feature (`binder.json` export) existing. The one concrete data-exposure item found in this file (Personal Collection publishing the full search cache) is confirmed intentional, not a bug — this is a precautionary first pass, not gap-driven.
- **`dependency-management`** — Room/Hilt/Compose versions have no audit cadence on record (unlike MobileStream, which has one).

## Refreshed — 2026-09-09 (session 10)

### Fixed this session

**Caveat covering every item below**: none of these were compiled or test-executed this session — the sandbox-wide Gradle wall (see Environment note below) blocked even plain compilation, not just test runs. "Fixed" here means code-reviewed and, for the rounding bug, hand-verified by arithmetic — not build-confirmed. Treat as unverified until `.\gradlew.bat testDebugUnitTest --rerun-tasks` (JVM) and a real `connectedDebugAndroidTest` run (the two DB tests) actually go green.

- ~~**`Migration6to7Test`'s expected column lists for `connecting_art_slot`/`personal_collection_entry` were stale.**~~ **Fixed.** Root cause (five-whys, not just "add the missing column"): the test's `CREATE TABLE IF NOT EXISTS` statements for the 4 new tables are no-ops, because `db = Room.inMemoryDatabaseBuilder(...).build()` in `setUp()` already creates every table at the CURRENT entity schema before the test's raw-SQL block ever runs — so `columnsOf("connecting_art_slot")` was always reading Room's real current `ConnectingArtSlot` entity shape, not MIGRATION_6_7's own output in isolation. Both tables gained a `language` column from the 2026-07-15 language/lock/remarks feature, added after this test was written. **Not a systemic pattern** — checked the other two migration tests: `Migration8to9Test` correctly suffixes every table it touches (`main_binder_v8_shape`, etc.) to avoid exactly this collision; `Migration5to6Test` has no column-list assertions at all. This was Migration6to7Test's own inconsistency (suffixed only the one pre-existing table it seeded, not the 4 new ones), not a bug shared across the migration-test suite.
- ~~**`PokedexDatabaseTest.secondaryBinderOrdersByIdDesc` failed live, root cause unconfirmed.**~~ **Fixed — root cause was the test, not the DAO.** It called the raw `insert()` DAO method (bypassing `insertAtEnd()`), leaving `position` at the entity default (0) for both rows, then asserted "newest first." Traced the DAO's only real caller (`SecondaryBinderViewModel.addCard`) — always `insertAtEnd()`, which assigns the next `position` — confirming the actual intended design is append-to-end (oldest-first), matching the drag-to-reorder Card History grid (`SecondaryBinderScreen.kt`'s `ReorderableItem`/`longPressDraggableHandle`), not a recency feed. Rewrote the test to exercise `insertAtEnd()` and assert the real order.
- ~~**`88f/63f` card-aspect literal triplicated.**~~ **Fixed.** Extracted `CARD_ASPECT_RATIO`, 3 call sites in `ScannerScreen.kt`.
- ~~**`ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM` shadowed by a hardcoded `20f` in `logUltrawideFeasibility`.**~~ **Fixed** — now reads the named constant, log message included.
- ~~**~120 lines of `sun.misc.Unsafe` reflection test fixture duplicated across `ScannerScreenTest.kt`/`ScannerFocusIndependentVerificationTest.kt`.**~~ **Fixed** — extracted to `FocalLengthsKeyTestFixture.kt`.
- ~~**1px rounding shift on mirrored rotation axes (90/180/270) in `guideFrameImageRect`.**~~ **Fixed — turned out to be a real bug, not just a stale doc comment.** `dRight`/`dBot` are exclusive bounds (one-past-last-pixel) but were fed straight into the rotation corner-map, which treats its inputs as literal pixel coordinates — silently shifting the mapped rect by 1px on any axis a rotation flips. Verified by hand: a guide box centered in the frame must map onto itself exactly under a 180° rotation; before this fix it came out 1px off on every edge (339/776/659/1222 instead of 340/777/660/1223). Fixed by converting to inclusive coordinates before mapping, back to exclusive after. `ScannerScreenTest`'s 3 rotation assertions updated to the hand-derived correct values. **Not test-executed** — see Environment note below.

### Still open (untouched this session, deliberately)

- **Crop rect is ~1.4x larger linearly (~1.9x by area) than the guide box `CardFrameOverlay` draws on screen** (carried from session 9, unchanged) — needs real-device calibration data per its own note below, not a blind code patch. See session-9 entry.

### Environment note (not a codebase gap — a tooling limitation, recorded here so it isn't rediscovered from scratch)

- ~~**Every Gradle task — including plain compilation, not just test execution — failed in this session's sandbox with `java.io.IOException: Unable to establish loopback connection`.**~~ **Gone as of session 11 (2026-09-09).** `testDebugUnitTest --rerun-tasks` ran clean on the first try; every fix this wall had blocked is now build-confirmed (see session 11 entry above). Not root-caused why it cleared — not investigated further since it stopped reproducing. Worth a fast sanity check (`gradlew.bat testDebugUnitTest --rerun-tasks`) at the start of any future session before assuming either state.

## Refreshed — 2026-09-09 (session 9, cont'd)

### Found this session, no code fix — a workflow/UX gap, not a bug

- **Nothing warns Skyler (in-app or otherwise) when `binder.json` has gone stale.** Found via the
  new `/card` Discord command reporting a card as "Owned" that isn't — traced to `binder.json`
  being 16 days old (`publishedAt: 2026-08-24`, confirmed by fetching the live file). The publish
  logic itself is correct — `PublishRepository.kt`'s `toSnapshotSlot()` computes `cardId` and
  `owned` from the identical `assignedCardId` expression, so they can't diverge from a fresh
  publish; this was stale data from before the app was next used to publish, not a live bug.
  But there's no mechanism — a "last published N days ago" indicator in Settings, a reminder, an
  automatic republish trigger — telling Skyler the public site/Discord bot are answering from old
  data. Every consumer of `binder.json` (the public website, now also the Discord `/card` command)
  inherits this same staleness contract silently. Cheap fix if picked up: surface the app's already-
  stored last-publish timestamp somewhere Skyler actually looks (Settings screen, most likely).

## Refreshed — 2026-09-09 (session 9)

### Fixed this session

- ~~**Scanner attempt #6: physical ultrawide lens selection + guide-frame crop**~~ (session 7's
  parked item, below, resolved). **Committed `4715adf`, not pushed.** Full `dev-team-pipeline` run,
  3 review iterations (34 → 72 → 84/100), hit the pipeline's cap with zero remaining P0. Two real,
  live bugs caught and fixed mid-pipeline — both would have shipped broken despite a fully green
  test suite:
  - `CameraSelector.Builder().setPhysicalCameraId()` was a **silent no-op** for this project's
    `bindToLifecycle` overload (proven by reading the real CameraX 1.6.1 source, one reviewer
    disassembling a library AAR with no available sources for the final link) — the bind always
    succeeded on the main lens while the diagnostic log asserted success. Fixed by moving the
    physical id onto the use-case builders (`Camera2Interop.Extender`) instead.
  - The crop change accidentally un-shadowed a dead, hand-rolled 3-plane NV21 decoder (dead since
    CameraX 1.3.4→1.6.1 added a same-named `ImageProxy.toBitmap()` member) that would throw
    `ArrayIndexOutOfBoundsException` on every real single-plane-JPEG capture. Fixed by deleting the
    dead decode and delegating to CameraX's own member.
  Two small diagnostic-accuracy items applied directly after the pipeline's review cap (`boundVia`
  now reflects the actually-bound camera on the fallback path, not the attempted one; removed an
  overclaiming comment). Full detail: `project-overview.md`'s "Scanner Camera Architecture" §6,
  `.pipeline/` (pending archive to `.pipeline_archive/2026-09-08-scanner-macro-focus/`).
  **Needs Skyler's real S26 Ultra to confirm** — nothing further reachable from this sandbox (no
  real multi-camera hardware in the JVM/emulator). 286/286 tests, lint, build all clean.

### Open, newly logged this session (from attempt #6, deliberately deferred — none touched across 3 review iterations)

- **Crop rect is ~1.4x larger linearly (~1.9x by area) than the guide box `CardFrameOverlay` draws
  on screen** — `guideFrameImageRect` takes a fraction of the *image's* display-space width;
  `CardFrameOverlay` draws the same fraction of the *view's* width, and `PreviewView` defaults to
  `FILL_CENTER` scaling (never overridden), so image and view aspect ratios differ. Not a
  positional error (still concentric, never clips the card) — just admits more background than the
  user aimed at, the exact risk the crop feature exists to close. Should fold into Task 5's
  real-device calibration pass, calibrated against the crop's actual behavior, not the drawn box.
  **(Session 10: deliberately left out of that session's cleanup pass — real geometry fix, not a
  cheap one, and needs real-device numbers to pick correct values.)**
- ~~**1px rounding shift on mirrored rotation axes** (90/180/270) in `guideFrameImageRect`~~ —
  **Fixed session 10** (see that section above) — turned out to be a real 1px bug, not just a
  stale doc comment.
- ~~**`88f/63f` card-aspect literal triplicated**~~ — **Fixed session 10.**
- ~~**`ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM` (20f) is shadowed by a hardcoded `20f`**~~ — **Fixed
  session 10.**
- ~~**~120 lines of `sun.misc.Unsafe` reflection test fixture duplicated verbatim**~~ — **Fixed
  session 10** (`FocalLengthsKeyTestFixture.kt`).
- **Task 2's bind-fallback wiring has zero automated test coverage** — it lives inside a Compose
  `AndroidView` factory closure, not an independently-testable function. Spec-sanctioned gap
  (Task 6 test case 16, explicitly optional, declined twice now across the pipeline). This is also
  the exact code class both of attempt #6's real bugs lived in — worth reconsidering the "not worth
  the diff size" call if this file needs a 7th attempt.
- **`CameraControl$OperationCanceledException` — repeated focus requests cancel each other**
  (carried over from 2026-09-04, unchanged): multiple `triggerFocus()` calls firing in quick
  succession (edge-triggered refocus and/or tap-to-focus overlapping a metering retry) cancel each
  other's in-flight requests. Every observed sequence still eventually reaches a real
  `isFocusSuccessful=true` — not currently believed to block focus lock outright, still just
  flagged for awareness.

## Refreshed — 2026-09-04 (session 8, cont'd)

### Fixed this session

- ~~**No Card History restore path; no local (GitHub-independent) backup/restore option.**~~
  **Shipped 2026-09-03/04, committed `eaed99a`.** Full `dev-team-pipeline` run, score history
  54 → 83 → 90/100 (ship, all 3 lenses). Full detail: `project-overview.md`'s new "Binder Backup"
  section, `.pipeline_archive/2026-09-04-binder-backup/`. Not yet pushed to `origin`.

### Fixed this session (cont'd)

- ~~**`DatabaseModule.kt`'s `.fallbackToDestructiveMigration()` + `.fallbackToDestructiveMigrationOnDowngrade()`
  combination cancels the graceful-recreate behavior the first call is supposed to provide.**~~
  **Fixed 2026-09-04.** Root-caused directly against Room 2.6.1's actual source
  (`RoomDatabase.kt:1110-1126`, extracted from the real `room-runtime-2.6.1-sources.jar` in the
  Gradle cache, not guessed): `fallbackToDestructiveMigration()` alone already sets BOTH
  `requireMigration = false` (upgrades) AND `allowDestructiveMigrationOnDowngrade = true`
  (downgrades) — it covers both directions by itself. `fallbackToDestructiveMigrationOnDowngrade()`
  unconditionally resets `requireMigration = true`, clobbering the first call's intent.
  `DatabaseConfiguration.isMigrationRequired()` (`DatabaseConfiguration.kt:666-679`) confirms the
  exact failure path: for an upgrade (`fromVersion < toVersion`), the downgrade short-circuit never
  fires, so it falls straight to `return requireMigration && ...` — `true` after both calls run in
  sequence, exactly reproducing the reported crash. Fix: removed the redundant/conflicting second
  call ([DatabaseModule.kt](app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt) —
  `.fallbackToDestructiveMigration()` alone now, with a comment explaining why the second call must
  never be re-added). **Verified live on-device**, not just compiled: the pre-existing
  (never-before-run) `DatabaseModuleWiringTest.buildDatabase_backsUpBeforeDestructiveRebuild_whenNoMigrationPathExists`
  test — seeds a v3 on-disk DB with no migration path to v9, the exact reported scenario — now
  passes on the real emulator; logcat shows `DatabaseBackupManager: Destructive migration imminent
  (on-disk v3 -> target v9, no path) — backing up first` firing cleanly with no crash. 264/264 JVM
  unit tests still pass (fresh XML count).
- ~~**`connectedDebugAndroidTest` confirmed still unable to run in this dev sandbox.**~~
  **Fixed 2026-09-04.** Root cause was never the emulator/hardware — `hiltJavaCompileDebugAndroidTest`
  died because Dagger 2.51.1's bundled Kotlin-metadata reader
  (`dagger.internal.codegen.kotlin.KotlinMetadata`) can't parse the metadata format Kotlin 2.1.20
  emits (`IllegalStateException: Unable to read Kotlin metadata due to unsupported metadata
  version`, reproduced directly via `hiltJavaCompileDebugAndroidTest --stacktrace`). Confirmed via
  Dagger's own GitHub release notes (not guessed): 2.53/2.53.1 bumped the bundled
  `kotlinx-metadata-jvm` for Kotlin 2.0 support, and 2.57 explicitly "unshades the Kotlinx Metadata
  to support Kotlin 2.2.0" — comfortably past our Kotlin 2.1.20, with its only breaking change
  (generated `Factory`/`MembersInjector` constructors going public→private) harmless since this
  codebase never calls Dagger's generated classes directly. Chose 2.57 over latest stable (2.60.1)
  deliberately — 2.60 drops multidex support and bumps min SDK, real unrelated risk for a 9-version
  jump this project's own BOM-bump history says to avoid. **Tested in an isolated git worktree
  before touching the real checkout** (`hilt = "2.57"` in `gradle/libs.versions.toml`), confirmed
  `hiltJavaCompileDebugAndroidTest`/`compileDebugKotlin`/`testDebugUnitTest` all pass there, then
  applied to the real tree. **Verified live**: ran the actual `pokedex_test` AVD end-to-end —
  `AppNavigationScreenTest` (all 7 cases) and `DatabaseModuleWiringTest` (both cases) now execute
  and pass for the first time ever in this project's history (previously compile-only, per session
  6/8 notes). A full unfiltered `connectedDebugAndroidTest` run also surfaced 2 genuine pre-existing
  test bugs, both newly-discovered because this task type could never execute before — see below,
  not fixed here (out of scope for this fix, flagged not folded in).
- **Binder Backup's new regression tests for the DB-closed-before-restart fix failed on a clean
  first `--rerun-tasks` run** (`NoClassDefFoundError: Hilt_MainActivity`), passed on immediate
  re-run, `[Likely]` a Windows KSP-regeneration race rather than a real test-logic bug (the
  production path was independently proven correct live on-device, separate from this test). The
  regression guard for this specific fix can go red for reasons unrelated to the code — if
  `BackupImporterTest`'s two newest cases (the ones asserting `Process.killProcess`/`startActivity`
  after a simulated swap failure) ever fail again, re-run clean before assuming a real regression.
- **`PendingImportError` (new, `BackupImporter.kt`) uses raw `SharedPreferences` rather than this
  codebase's `@Singleton`/DataStore convention for persisted state** (`SettingsRepository.kt`,
  `PublishSettingsRepository.kt`). The technical choice is correct — DataStore has no synchronous
  write and would race the forced `Process.killProcess()` — but the deviation isn't documented at
  the point it diverges, unlike a precedented similar deviation already in
  `PublishSettingsRepository.kt`. Low priority, cosmetic/consistency only.
- **The new `shared_prefs/backup_import_state.xml` (`PendingImportError`'s backing file) isn't
  excluded from Android's Auto Backup domain** — `data_extraction_rules.xml`/`full_backup_content.xml`
  only exclude the `exports/` directory. An unconsumed pending-error message could theoretically
  ride a cloud backup or device-transfer onto a fresh install and show a stale Toast there. Low
  severity (no data exposure, just a confusing one-shot message), two-line fix when picked up.
- ~~**Scanner's `requestFocusAndMetering()` treated a THROWN metering future identically to a
  completed-but-unsuccessful one, defeating the real-focus-convergence gate near-instantly after
  bind.**~~ **Fixed 2026-09-04, found via the Task 0 feasibility spike's diagnostic logging
  (unrelated purpose — a side effect, not what the spike was looking for) then confirmed as the
  root cause of two real-device symptoms Skyler reported live in this same session: auto-capture
  firing with no card in frame ("it just says card detected and takes the photo"), and wrong-card
  results on the 7 scans immediately preceding the fix.** Real logcat evidence
  (`adb logcat -s ScannerFocus:*`, Skyler's S26 Ultra, model SM-S948B): the first
  `startFocusAndMetering()` call after every camera bind throws
  `IllegalArgumentException: None of the specified AF/AE/AWB MeteringPoints is supported on this
  camera`, with the future resolving (failing) in ~10-20ms — a second attempt at the identical
  point then succeeds normally ~430-440ms later. Root cause traced directly in
  `requestFocusAndMetering()`'s `future.addListener` callback: `onSettled()` (which sets
  `focusLocked = true`) fired unconditionally whether `future.get()` succeeded OR threw — by
  original design (attempt #2, `3e427e4`: "a completed-but-unsuccessful result must still unblock
  capture"), intended for a genuine hardware AF attempt that completes with
  `isFocusSuccessful=false`. A THROWN future is categorically different — metering never actually
  ran — but the code didn't distinguish the two cases, so `focusLocked` flipped `true` within
  milliseconds of every bind, regardless of whether real focus ever converged. Combined with
  `detectCardInFrame`'s crude contrast-only heuristic (no real card-shape validation, by its own
  code comment) being able to trip on background/ambient conditions once
  `POST_BIND_DETECTION_DELAY_MS` (2000ms) elapses, this explains both reported symptoms: capture
  firing on an empty frame, and — since a capture that fires this way has no real focus lock
  regardless of whether a card is present — every subsequent legitimate scan also being
  effectively unfocused. Fixed: `requestFocusAndMetering()` now distinguishes a thrown future from
  a completed one — on throw, retries the same metering request up to `MAX_METERING_RETRIES = 2`
  times (150ms apart, `METERING_RETRY_DELAY_MS`) before falling back to `onSettled()`, preserving
  the original "never block capture forever" invariant while no longer treating an instant hard
  failure as equivalent to a real settled attempt. `assembleDebug --rerun-tasks` and
  `lintDebug --rerun-tasks` both clean (0 new warnings, 0 findings on the file) —
  **Confirmed working on real hardware, same session, immediately after deploy**: fresh logcat
  shows the retry path firing correctly (`future failed, retrying (attempt N/2)`, then either a
  real success shortly after or a clean `giving up after 2 retries` fallback) — the exception-
  defeats-the-gate bug is genuinely closed. **However, the wrong-card symptom persisted across 2
  further scans after this fix deployed** — confirms this was a real, necessary fix but not the
  (or not the only) cause of the wrong-card matching problem. Root cause for the remaining symptom
  is not new — it's the already-diagnosed gap in
  `docs/specs/2026-09-02-scanner-macro-focus.md` (2026-08-29 finding, predates this session): the
  captured photo handed to the card matcher is not cropped to the guide frame, and the guide frame
  itself is sized for a working distance derived from a since-disproven 18cm min-focus assumption
  (today's spike measured 10cm on the actual bound sensor). That fix was parked pending exactly the
  feasibility question this session's Task 0 spike answered. **Not treated as a 6th blind patch to
  this same gating mechanism — a spec-level gap, per the debugging skill's own guidance to name
  that explicitly rather than force another code-level fix onto it.**
- **New, secondary finding (2026-09-04, same logcat)**: repeated
  `CameraControl$OperationCanceledException: Cancelled by another startFocusAndMetering()` —
  multiple `triggerFocus()` calls firing in quick succession (likely edge-triggered refocus and/or
  tap-to-focus overlapping a retry from the fix above) cancel each other's in-flight metering
  requests. Every observed sequence in the log still eventually reaches a real
  `isFocusSuccessful=true`, so this is **not** currently believed to be blocking focus lock
  outright — flagged for awareness, not treated as the primary suspect for the wrong-card symptom.
  Worth a closer look if the macro-focus pipeline's own focus-request logic (which will replace
  large parts of this function) doesn't incidentally resolve it.
- ~~**`ImageProxyExt.kt`'s own hand-rolled `ImageProxy.toBitmap()` extension silently went dead,
  then silently un-shadowed itself, with zero compiler warning either time.**~~ **Found and fixed
  2026-09-04, via a 3-lens Opus review pass (`.pipeline/review-verdict.md`, P0-2).** Symptom: the
  extension read a 3-plane NV21 buffer (`planes[2]`), but every capture callback this project uses
  actually delivers single-plane JPEG (CameraX's own `ImageCapture.OnImageCapturedCallback`
  javadoc) — so any code path reaching the NV21 decode throws `ArrayIndexOutOfBoundsException` on
  every real capture. Why it's invisible: CameraX 1.6.1 added its own zero-arg default
  `ImageProxy.toBitmap(): Bitmap` member sometime after this project's same-named extension was
  first written (confirmed via `git log -L` on `libs.versions.toml`: the extension predates the
  1.6.1 bump). Kotlin always resolves a member over a same-named extension, so the extension went
  dead silently the moment the library added its own — no warning, no error, just quietly-dead
  code. A later diff (`ScannerViewModel.processImage`, the guide-frame crop feature) called
  `.toBitmap(cropToGuideFrame = true)` — a named argument only the extension's signature has —
  which forced Kotlin back onto the extension, reviving the dead NV21 path with no compiler signal
  that anything had changed. Confirmed reproducible: re-ran the exact pre-fix code against a
  realistic single-plane JPEG-shaped `ImageProxy` mock and it threw
  `ArrayIndexOutOfBoundsException` as predicted (see `.pipeline/changes.md` for the run). Structural
  fix applied this same pass: renamed the extension to `toCroppedBitmap` (a name that can't collide
  with any current or future CameraX member) and split it into two steps — decode via CameraX's own
  proven `ImageProxy.toBitmap()` member, crop separately via `Bitmap.createBitmap` — so this
  member-shadowing failure mode can't recur under this name. New regression test
  (`ImageProxyExtTest.kt`) uses a single-plane JPEG-shaped mock, not the 3-plane YUV mock every
  test in this area used before this fix (which could never have caught this class of bug).

### Open, newly discovered this session (2026-09-04, cont'd) — surfaced only now that `connectedDebugAndroidTest` can finally execute

- ~~**`PokedexDatabaseTest.secondaryBinderOrdersByIdDesc` fails live**~~ — **Root-caused and fixed
  session 10 (2026-09-09).** Not a test-data-setup ordering assumption as guessed here — the test
  bypassed the DAO's real `insertAtEnd()` call path entirely and asserted a "newest first" order
  the DAO was never designed to produce. See session 10's own entry above for the full trace.
  **Unverified this session** — see session 10's build-wall caveat.
- ~~**`Migration6to7Test.migration6to7SqlIsValidAgainstV6ShapeAndPreservesExistingData` fails live**~~
  — **Fixed session 10 (2026-09-09)**, `language` added to both affected expected column lists
  (`connecting_art_slot` AND `personal_collection_entry` — the latter has the identical drift,
  not called out here originally). **Unverified this session** — see session 10's build-wall caveat.
- **Gradle's own `connectedDebugAndroidTest` task-level pass/fail is unreliable in this sandbox,
  separate from whether tests actually pass** — `"Failed to receive the UTP test results"` (a UTP↔
  Gradle IPC glitch) makes the Gradle task report `FAILED` even on a run where every single test in
  the JUnit XML/textproto output shows `PASSED` (reproduced twice this session: identical 9/9-pass
  textproto both times, Gradle task FAILED both times). **When verifying a `connectedDebugAndroidTest`
  claim in this sandbox, read `app/build/outputs/androidTest-results/connected/debug/.../test-result.textproto`
  directly — don't trust Gradle's own exit code/summary alone.**

## Refreshed — 2026-08-29 (session 7)

### Fixed (2026-09-08/09, attempt #6 — see session 9 above)

- ~~**Scanner still misreads cards on real hardware ("keeps giving the wrong card"), root cause
  traced past fix #4's blur patch to a distance/resolution trade-off it introduced.**~~ Skyler's
  report: OCR/capture fires, but at the distance the app now demands, it reads the wrong card.
  Diagnosis (not yet coded): `GUIDE_FRAME_WIDTH_RATIO = 0.32f` (fix #4, `20c74f3`) fixed blur by
  pushing the working distance out to ~23cm — past the S26 Ultra main lens's 18cm minimum focus
  floor — but the captured frame sent to `GeminiCardScanner.scan()`/`PerceptualHasher` is NOT
  cropped to the guide frame, so shrinking the guide box's fill ratio also shrank the card's actual
  footprint (and thus detail) inside the image both matchers work from. The fix that made focus
  work made recognition worse — same file, two competing constraints, not yet reconciled.
  **Real fix, per Skyler's direct instruction:** don't push the user farther back — make the app
  focus at close range the way the phone's own stock camera app does (physical/logical
  multi-camera lens switching to the ultrawide's shorter minimum focus distance, not just the main
  lens's default AF). This is the exact open question already researched (not built) in
  `docs/specs/2026-08-27-card-grading-estimate.md`, Open Question #4 ("ultrawide auto-switch
  mechanism via Camera2 `Camera2CameraFilter` physical-lens selection").
  **Scope confirmed with Skyler (2026-08-29, AskUserQuestion):**
  1. Fix applies **scanner-wide** (QuickScan/Pokédex capture, manual capture) — not gated behind
     the still-parked grading feature. Both consumers share `ScannerScreen.kt`'s camera path.
  2. Once close focus actually works, **re-tune `GUIDE_FRAME_WIDTH_RATIO` back up** toward the old
     0.75 fill (re-derive properly for the new working distance, don't just revert the constant) —
     more of the frame filled with card = more detail for Gemini/perceptual-hash to read.
  **Explicitly parked — do not start `planning`/`dev-team-pipeline` on this without a fresh
  go-ahead.** This would be the 5th change to the scanner camera path this bug family (attempts
  #1-4 all in `project-overview.md`'s "Scanner Camera Architecture" section) — per the debugging
  skill's own 3+-fixes-question-the-architecture signal, this one needs a real `planning` pass
  (physical-camera-selection feasibility on this exact hardware, not another patch) rather than a
  direct code change, when picked back up.

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

### Open, newly logged this audit pass

- ~~**`androidx.graphics:graphics-path` still resolves to `1.0.1`, not the 16KB-compliant
  1.1.0+.**~~ **Fixed 2026-09-02.** Added `configurations.all { resolutionStrategy {
  force("androidx.graphics:graphics-path:1.1.0") } }` to `app/build.gradle.kts` (1.1.0 confirmed to
  actually exist as the latest stable release via Google's Maven metadata before pinning — not
  guessed, per this project's own KSP-version-guess lesson). First-time fetch of the new version
  hit the known TLS-interception `PKIX path validation failed` error (project-overview.md's
  Conventions) — resolved with the documented `$env:GRADLE_OPTS =
  "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"` workaround, same as always.
  **Verified at the actual binary level, not just log text**: extracted
  `libandroidx.graphics.path.so` from the built debug APK for all 4 ABIs (arm64-v8a, armeabi-v7a,
  x86, x86_64) and parsed each ELF's `PT_LOAD` program headers directly — every one now reports
  `p_align = 0x4000` (16384), confirming real 16KB alignment. (1.1.0's own changelog doesn't call
  this out in prose, so the ELF check was the only way to actually confirm the fix works rather
  than assume it from the version bump alone.) Also checked the other 3 originally-flagged libs
  (`libdatastore_shared_counter.so`, `libimage_processing_util_jni.so`,
  `libsurface_util_jni.so`) the same way — all already 16KB-aligned across all 4 ABIs, confirming
  the earlier `datastore`/`camerax` version bumps held. The `stripDebugDebugSymbols` task's
  "Unable to strip the following libraries" build-log message still names all 4 libs — confirmed
  this is an unrelated debug-symbol-stripping notice, not a page-size-alignment warning; don't
  mistake it for the 16KB warning returning.
  226/226 unit tests pass (fresh XML count, not the runner's own summary), `lintDebug` 0 errors /
  75 warnings (baseline, no new warnings from the pin). **Not yet committed** — sitting in the
  working tree alongside this session's other uncommitted changes.
- **`skylermayday-site`'s "Other" nav tile possibly swallows Personal Collection/Connecting
  Art/Unown** — noticed in passing during this session's live-site check, not investigated.
  Different repo, out of this project's scope to fix; tracked here only per this file's existing
  cross-repo-contract precedent (see `binder.json` entry below) so the observation isn't lost.
  Action, if any, belongs in a `skylermayday-site` session, not this one.

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
