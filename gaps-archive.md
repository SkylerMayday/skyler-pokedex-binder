# Gaps Archive — PokedexBinderV2

Entries moved out of `gaps.md` on 2026-10-03 because they are struck through there (fixed, or closed as not a gap).
History only. Cross-references like "see above" inside entries point at sections that may no longer be in `gaps.md`.

# Gaps — PokedexBinderV2

## Refreshed — 2026-10-03 (session 21, part 2 — TCGdex-only rebuild, wipe/reset, image sync)

### Fixed this part (verified by tests + review, not on device)

- ~~**`pokemontcg.io` shuts down 2027-03-01; `searchByName` (Personal Collection) still ptcg-first**~~ **Removed
  entirely.** Skyler chose wipe-and-rescan over an id-remap migration, so there is no alias table and no migration.
  Old backups / published snapshots with ptcg ids are NOT translated: restoring one will not crash but shows missing
  names/images (warning added to the three restore/import confirmations).

- ~~**Saved cards keep a blank image forever (M6 has no TCGdex art)**~~ **Image sync** (Settings button + daily auto)
  plus `TcgcsvJapanSource` as a second resolver.

- ~~**Retry storm when the TCGCSV groups fetch fails**~~ 15 min cooldown (6 h on 401/403), tested.

- ~~**TEMP DIAGNOSTIC debug-capture save**~~ removed; ~100 MB of old `scan_debug_*.jpg` may remain in the phone's app
  `files/` dir (delete via adb or Clear storage).

- ~~**Session 20 header change unverified**~~ `x-goog-api-key` worked live (Gemini answered scans 2026-10-03).

## Refreshed — 2026-10-03 (session 21 — scanner matching overhaul)

### Fixed this session (verified)

- ~~**Wrong card accepted from species-blind `number+total`** (Black Kyurem → Snorlax)~~ **Fixed**, live-verified:
  Black Kyurem resolves to `SV7a-011`. Five-whys: wrong card shown -> weak step ran last with no name check ->
  name was only a search filter, never verified -> cascade assumed English numbering -> language was never
  detected. Root fix = language detection + name/printedName verification.

- ~~**8x8 whole-crop hash worse than random**~~ **Replaced** with art-crop DCT (`ArtHash.kt`); 2 samples, not a
  statistical calibration.

- ~~**Japanese / other-language cards unfindable**~~ **Fixed for ja** (live), implemented but not live-tested
  for ko/zh-tw/th/id.

- ~~**Gemini 429 blocks scanning**~~ **Mitigated** by Claude fallback; live-verified 7 of 8 scans on 2026-10-01.

### New, not fixed

- ~~**`pokemontcg.io` shuts down 2027-03-01; `searchByName` (Personal Collection) is still ptcg-first.**~~ **Closed
  2026-10-03 (part 2 above):** ptcg removed; collection to be wiped and rescanned instead of remapped.

- ~~**TEMP DIAGNOSTIC debug-capture save still ships**~~ **Removed 2026-10-03** (uncompiled at the time of writing);
  old `scan_debug_*.jpg` files (~100 MB) may still sit in the phone's `files/` dir.

## Parked — 2026-09-30 (session 20, from the security/dependency pass) — circle back after the current fixes are test-green

- ~~**Inert physical-camera-id plumbing in `CameraPreview`**~~ **Removed session 20** (uncompiled):
  `ultrawideId`, `buildUseCases(physicalCameraId)`, `boundPhysicalId`, the fallback-bind branch,
  `logCameraAfCapabilities`' `requestedPhysicalId`, `actualBoundCameraId`, and the `Camera2Interop`
  / `Build` imports. `boundVia` is now always `"default-back-camera"`.

- ~~**`CardFrameOverlay` float geometry**~~ **Not a gap, closed.** Ratios are already shared
  constants; the remainder is a two-line float multiplication in draw code.

- ~~**Session 20's code changes are unit-test-green, not live-tested.**~~ **Closed 2026-10-03:** the live matching
  path and the `x-goog-api-key` header both worked on the phone (Gemini answered scans; Japanese cards resolved).

## Refreshed — 2026-09-30 (session 20 — cleanup batch)

### Fixed this session (unit tests 324/324, not live-tested)

- ~~**Gemini API key in the URL query** (`GeminiCardScanner.kt:83`)~~ **Moved to the
  `x-goog-api-key` header**, one new test. **The key itself is still unrotated** (Skyler's action).

- ~~**Dead code `selectUltrawidePhysicalCameraId` + tests**~~ **Deleted**, with the inert
  physical-id plumbing in `CameraPreview` (see the parked section above for the itemised list).

- ~~**`detectCardInFrame` duplicates `guideFrameImageRect` geometry** (deferred 3 times)~~ **Shared**
  via `guideFrameSize`/`displayToImage`; existing geometry tests unchanged and green.

- ~~**`CardSearchRepositoryTest` depended on `safeSearch`'s broad catch (F3); "name before dex" test
  passed on the old order and `name+number`/`broad-dex` labels untested (F2)**~~ **Fixed.**

## Refreshed — 2026-09-29 (session 19 — capture zoom mismatch, key-in-logcat)

### Fixed this session

- ~~**Still capture ~1.35x more zoomed than the preview; collector number cut off**~~ **Fixed,
  live-verified on the saved JPEG only** (`5c9f27e`). Five-whys: number=null -> number not in image
  -> crop clipped bottom -> stream itself too narrow (crop math proven fine) -> `ImageCapture`
  not on the preview's lens -> `setPhysicalCameraId` unreliable on Samsung multi-lens (CameraX
  thread). Fix: logical min zoom. Detail: `project-overview.md` item 20.

- ~~**Gemini API key printed to logcat** (`HttpLoggingInterceptor.Level.BASIC` logs the URL, key is
  in `?key=`)~~ **Logging removed** (`6a0ff73`, `e101080`). **The key itself is not fixed** — see below.

- ~~**Gemini retry-budget change never test-run**~~ Skyler ran the unit tests this session and
  reported them passing (his report, not a XML I read).

- ~~**Project `CLAUDE.md` wrongly said Bash cannot run `gradlew.bat`**~~ Corrected earlier in `b6ec0fb`
  (root `CLAUDE.md` now documents the Bash invocation).

### New, not fixed

- ~~**Dead code: `selectUltrawidePhysicalCameraId` + its `ScannerScreenTest` cases**~~ **Deleted
  session 20.**

## Refreshed — 2026-09-29 (session 18 — species-guard query reorder)

### Fixed this session (`bd55bfa`, unit-tested 331/331, Reviewer 92/100; not live-tested)

- ~~**`searchByParsedInfo`'s species-blind `number+total` query ran before the dex-pinned queries,
  so a wrong-species card could be the single candidate** (Castform → `Munna #116`, live 2026-09-24)~~
  **Fixed.** Full detail: `project-overview.md` item 19. The single-candidate hash ceiling remains a
  backstop, no longer the only guard.

## Refreshed — 2026-09-22 (session 17 — logcat check, direct debugging skill fix)

### Fixed this session (root-caused via `adb logcat`, not a full pipeline run)

- ~~**Repeated focus requests still cancel each other**
  (`CameraControl$OperationCanceledException`, open since 2026-09-04)~~ **Fixed.** Real root cause,
  confirmed live: the edge-triggered auto-refocus had no in-flight guard, so normal hand jitter
  flickering the card-detection transition fired overlapping `startFocusAndMetering()` calls (9
  cancellations in <2s in the captured log) — not the vague "delicate camera code" risk this entry
  carried for 3+ deferrals. New `focusInFlight` boolean gates just the edge-triggered call site;
  manual tap-to-focus untouched. Build-verified, **not yet live-device re-tested** — see below.

### Second pass this session (2026-09-23) — live-retest after a fresh install

- ~~**Item 18's two fixes (camera unbind + focus in-flight guard) are build-verified only, not
  live-device re-tested**~~ **Re-tested after a fresh `installDebug`** (device was still running
  the pre-fix build — `lastUpdateTime` confirmed it predated the source edit). Focus-cancellation
  storm: **fully gone**, 0 occurrences (was 9). Camera-unbind race: **much improved, not literally
  zero** — collapsed from a 150-300ms burst of `queueBuffer`/`dequeueBuffer` (CameraX actively
  still producing new frames for a dead surface) to under 30ms, mostly benign `cancelBuffer`
  cleanup, one real straggler frame per teardown at most. Reasonable ceiling given `unbindAll()`
  is itself async — not chasing further.

- ~~**`CROP_MARGIN_FACTOR=1.10` was a first guess pending live data**~~ **First live data says 1.10
  was clearly insufficient — bumped to 1.30.** The successful scan's debug image was clipped on
  all 4 edges (text cut mid-word left and right, no card header/HP visible at top at all), not
  just header/footer as originally assumed when 1.10 was chosen. `ScannerScreen.kt`'s constant
  bumped; `ScannerScreenTest.kt` (4 rotation cases) and `ImageProxyExtTest.kt` (3 cases) updated.
  **First hand-recomputation pass was wrong** — 6 test failures on Skyler's own run, all a
  1px-off pattern: `1.30f` isn't exactly representable in binary float32, so `boxW`/`boxH`
  truncate 1 lower than exact-decimal arithmetic predicts. Root-caused by simulating Kotlin's real
  float32 semantics in Python against the actual JVM failure output (matched exactly), then fixed
  all 6 values from that simulation. **Re-run confirmed: `testDebugUnitTest --rerun-tasks` BUILD
  SUCCESSFUL**, all failures gone, only pre-existing unrelated coroutines opt-in warnings. Value
  itself is still first-guess/uncalibrated — **needs one more live scan** to confirm 1.30 actually
  captures the full card, not just "less wrong" — same standing pattern as
  `GUIDE_FRAME_WIDTH_RATIO`'s entire history.

## Refreshed — 2026-09-20 (session 16, cont'd — testable settings repos)

### Fixed this session (full `dev-team-pipeline` run, ship at 98/100)

- ~~**No real test coverage on `EncryptedSharedPreferences`-backed repositories
  (`SettingsRepository`, `PublishSettingsRepository`)**~~ **Fixed.** Rejected Robolectric (unreliable
  AndroidKeyStore shadow support, would test the third-party library not this app's logic).
  Dependency-injected `DataStore`/`SharedPreferences` instead (new `di/SecurityPrefsModule.kt` for
  production, real-not-mocked fakes for tests), no new dependency. Full detail: `project-overview.md`
  Test Infrastructure section.

## Refreshed — 2026-09-20 (session 16, gap-audit batch)

### Fixed this session (full `dev-team-pipeline` run, score 77 → 95/100 after one auto-loop)

- ~~**`findBestMatch()`'s per-candidate download-failure fallback could inflate the hash margin
  past threshold**~~ (session 16's original entry, below) **Fixed.** `SmartThresholdUseCase.
  evaluate()`'s `HASH_MARGIN` branch now also requires `hashMatch.distance != Int.MAX_VALUE`.
  `PerceptualHasher.kt` untouched. Full detail: `project-overview.md` item 17.

- ~~**`GeminiCardScanner.kt`'s 400/401/403/404 path has no dedicated test**~~ (carried since
  session 13) **Fixed.** New test, zero production change.

- ~~**`shared_prefs/backup_import_state.xml` not excluded from Android Auto Backup**~~ (session 8)
  **Fixed.** Excluded in both `data_extraction_rules.xml` and `full_backup_content.xml`.

- ~~**`PendingImportError` uses raw `SharedPreferences`, undocumented deviation**~~ (session 8)
  **Fixed (documentation only).** Comment now explains the deliberate choice (DataStore has no
  synchronous write, would race the forced `Process.killProcess()`).

### New this session — found by the security audit, not on the original list

- ~~**Gemini API key stored in plaintext `DataStore`, inconsistent with the GitHub PAT/Discord
  webhook's `EncryptedSharedPreferences` storage (same threat model, same app)**~~ **Fixed.** Moved
  to `EncryptedSharedPreferences` with a migration preserving Skyler's existing key.
  **Reviewer caught a real P1 in the first pass**: the migration's encrypted write used `.apply()`
  (async) immediately before a durable-blocking plaintext clear — a process death in that window
  could lose the key from both stores. Fixed via `.commit()`, re-reviewed clean at 95/100. Full
  detail: `project-overview.md` item 17.

### Still open — needs Skyler, not fixable blind

- ~~**Repeated focus requests still cancel each other** (`CameraControl$OperationCanceledException`,
  open since 2026-09-04)~~ **Fixed session 17 (2026-09-22)** — see that session's entry at the top
  of this file. Root cause turned out to be a missing in-flight guard on the edge-triggered
  auto-refocus, confirmed via a real logcat capture, not the "delicate, needs live hardware" risk
  this entry carried for 3 prior deferrals.

- ~~**Personal Collection publishes its entire search cache (owned + unowned) — confirm still
  desired**~~ **Answered same session: yes, confirmed and clarified.** Skyler's exact spec: show
  all cards, grayscale until owned (already correct in-app), and — the real gap this surfaced —
  Discord/changelog should only fire on an actual owned-card change, decoupled from whether
  binder.json itself uploads. Built and shipped same session, see `project-overview.md`'s "Publish
  Diff Semantics" section, 2026-09-20 entry.

## Refreshed — 2026-09-17 (session 16)

### Fixed this session (full `dev-team-pipeline` run, ship at 95/100)

- ~~**`PerceptualHasher.computeHash()` bit-mapping bug**~~ (session 15's entry, below) **Fixed.**
  Resized 16x16→8x8, matching `Long`'s real 64-bit capacity. Regression test proves two
  distinguishably-different images never collide. Full detail: `project-overview.md` item 15.

- ~~**`SmartThresholdUseCase.evaluate()` structurally couldn't mark high confidence when Gemini's
  number is null**~~ (session 15's entry, below) **Fixed.** New margin-gated `HASH_MARGIN` path,
  `ConfidencePath` enum for diagnostics. Full detail: `project-overview.md` item 15.

- ~~**`candidates.size == 1` short-circuit skipped all visual verification**~~ (session 14's entry,
  below) **Fixed.** `findBestMatch()` now computes a real distance even for a single candidate;
  `evaluate()` gates it on `HASH_SINGLE_CANDIDATE_MAX_DISTANCE` instead of auto-accepting.

### New this session — P2, deferred, not fixed

- ~~**`findBestMatch()`'s per-candidate download-failure fallback (`Int.MAX_VALUE` distance,
  `PerceptualHasher.kt:50-51`) can inflate the margin past `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD`
  if exactly one candidate's image download fails mid-scan**~~ **Fixed same day (2026-09-20 gap-audit
  batch, above).** `evaluate()`'s `HASH_MARGIN` branch now also requires `hashMatch.distance !=
  Int.MAX_VALUE`. `PerceptualHasher.kt` itself untouched, as originally proposed here.

### Still open, unchanged from session 15

- ~~`GeminiCardScanner.kt`'s unchanged 400/401/403/404 path still has no dedicated test~~ **Fixed
  2026-09-20 (gap-audit batch).** New test, zero production change.

## Refreshed — 2026-09-10 (session 14)

### Fixed (environment/workflow, not a codebase change) — resolved 2026-09-23

- ~~**The Gemini API key configured in the app's Settings shares a Google Cloud project
  ("Default Gemini Project" on aistudio.google.com) with an unrelated tool, `Claude-mem`**~~
  **Resolved.** Skyler moved `Claude-mem` to its own separate Google Cloud project — same effect
  as splitting pokedex-binder off would have had (either side moving off the shared project stops
  the quota contention), simpler since it didn't need any change to this app's stored key/Settings.
  Original finding, for reference: confirmed directly from Skyler's own usage dashboard that both
  consumers shared one project's quota and both hit real errors the same day (`429` for
  pokedex-binder, `503` for Claude-mem) — not any inefficiency in this app's own Gemini usage
  (only one call site, `ScannerViewModel.kt:79`, no retry-on-429, auto-capture properly guarded
  against double-firing).

## Refreshed — 2026-09-10 (session 12, live-device debugging after the crop fix shipped)

### Fixed this session — found live on Skyler's real S26 Ultra, not guessed

- ~~**`SmartThresholdUseCase.evaluate()`'s confidence check validated the perceptual hasher's own
  pick against itself, never against what Gemini actually read off the card.**~~ **Fixed
  (`ff0134c`).** `ScannerViewModel.processImage()` called `evaluate(candidates, best.name,
  best.number)` — `best` is the hasher's ALREADY-CHOSEN candidate, always a member of
  `candidates`, so the number-match check was trivially self-referential and `isHighConfidence`
  was always `true` whenever there was more than one candidate. Confirmed via git archaeology:
  correct at `455178a` (original implementation, passed `parsed.cardName`/`parsed.cardNumber`),
  silently broken by `498d035` (2026-05-20, introduced the perceptual hasher in the same diff that
  swapped the call's arguments). Live-reproduced: real Castform scan, Gemini correctly read
  `name=Castform number=62`, app confidently returned a different print ("Castform Sunny Form
  #20") because #62 was never compared against anything. Explains the accuracy drop Skyler
  reported since switching phones — the bug's been live since May, exposure depends on how often
  the hasher's own guess happens to be wrong, which a different camera pipeline changes. Fixed via
  a new pure `confidenceCheckInputs(parsed, best)` (always surfaces Gemini's own
  `parsed.cardNumber`, including `null` when Gemini didn't read one — which correctly produces low
  confidence instead of a rubber-stamped guess). 3 new regression tests,
  `testDebugUnitTest --rerun-tasks`: 292/292 pass.

- ~~**`GUIDE_FRAME_WIDTH_RATIO` (0.32) was calibrated for the MAIN lens's 18cm floor, stale since
  the scanner switched to the physical ultrawide lens.**~~ **Bumped to 0.5 (`643978c`), first
  live-tested value, not a final calibration.** The ultrawide lens actually bound (since
  2026-09-08) reports a real minimum focus distance of 5cm (`requestedPhysicalMinFocusDistance=
  20.0` diopters, confirmed live tonight and against the real Android API docs — distance_m =
  1/diopters), nowhere near the 18cm the old ratio assumed. Skyler's own demo photo (card filling
  most of frame, sharp, number trivially legible) plus the live symptom (Gemini reading the
  species fine but too often failing to read the small printed card number, forcing manual-pick
  fallback) both confirmed the card was needlessly small in frame. New target ~15cm, still 3x the
  lens's real floor for margin. **Re-tune from here if it's still not landing well** — flagged as
  re-tunable in the constant's own comment, exactly like the ratio it replaced.

### New this session, not fixed — flagged, deferred to next session

- ~~**`GeminiCardScanner.kt`'s `scan()` has zero retry on a transient failure.**~~ **Fixed session 13
  (`c70d8b6`).** New `executeWithRetry()` extends the existing 429/`RateLimitException` pattern:
  retries on a 5xx response or `SocketTimeoutException` only, up to `MAX_SCAN_RETRIES = 2`
  additional attempts (3 total), fixed `SCAN_RETRY_DELAY_MS = 500L` delay. 429/other-4xx unchanged.
  Failed 5xx response bodies closed before retry (leak prevention). Full `dev-team-pipeline` run,
  ship at 97/100. Both constants are first-pass values, retunable like `GUIDE_FRAME_WIDTH_RATIO`.
  One minor gap surfaced by both the orchestrator's own trace and the Reviewer: the unchanged
  400/401/403/404 path still has no dedicated test (code untouched by this diff, correct by
  inspection) — see new entry below, not blocking.

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

- ~~**`ImageProxyExt.kt`'s `toCroppedBitmap()` can throw `IllegalArgumentException` on a degenerate
  (zero-width/height) `cropRect`, bypassing the function's own graceful-fallback guard.**~~ **Fixed
  session 13 (`806242d`).** Early-return guard added before the `guideFrameImageRect` call, exactly
  the cheap fix this entry originally proposed. New regression test caught a real bug in the fix's
  own first draft before it shipped: the log line's string template called `Rect.toString()`
  implicitly, which throws `RuntimeException: Method toString in android.graphics.Rect not mocked`
  under this project's non-Robolectric unit-test stub jar (same class of gotcha as the existing
  field-access-only convention documented in this file's `toCroppedBitmap()` code comments) — fixed
  by logging `cropWidth`/`cropHeight` fields directly instead of the `Rect` object.
  `testDebugUnitTest --rerun-tasks`: 293/293 pass at the time (fresh XML), 296/296 in the final
  combined state with item 2's changes.

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

## Refreshed — 2026-09-09 (session 10)

### Fixed this session

- ~~**`Migration6to7Test`'s expected column lists for `connecting_art_slot`/`personal_collection_entry` were stale.**~~ **Fixed.** Root cause (five-whys, not just "add the missing column"): the test's `CREATE TABLE IF NOT EXISTS` statements for the 4 new tables are no-ops, because `db = Room.inMemoryDatabaseBuilder(...).build()` in `setUp()` already creates every table at the CURRENT entity schema before the test's raw-SQL block ever runs — so `columnsOf("connecting_art_slot")` was always reading Room's real current `ConnectingArtSlot` entity shape, not MIGRATION_6_7's own output in isolation. Both tables gained a `language` column from the 2026-07-15 language/lock/remarks feature, added after this test was written. **Not a systemic pattern** — checked the other two migration tests: `Migration8to9Test` correctly suffixes every table it touches (`main_binder_v8_shape`, etc.) to avoid exactly this collision; `Migration5to6Test` has no column-list assertions at all. This was Migration6to7Test's own inconsistency (suffixed only the one pre-existing table it seeded, not the 4 new ones), not a bug shared across the migration-test suite.

- ~~**`PokedexDatabaseTest.secondaryBinderOrdersByIdDesc` failed live, root cause unconfirmed.**~~ **Fixed — root cause was the test, not the DAO.** It called the raw `insert()` DAO method (bypassing `insertAtEnd()`), leaving `position` at the entity default (0) for both rows, then asserted "newest first." Traced the DAO's only real caller (`SecondaryBinderViewModel.addCard`) — always `insertAtEnd()`, which assigns the next `position` — confirming the actual intended design is append-to-end (oldest-first), matching the drag-to-reorder Card History grid (`SecondaryBinderScreen.kt`'s `ReorderableItem`/`longPressDraggableHandle`), not a recency feed. Rewrote the test to exercise `insertAtEnd()` and assert the real order.

- ~~**`88f/63f` card-aspect literal triplicated.**~~ **Fixed.** Extracted `CARD_ASPECT_RATIO`, 3 call sites in `ScannerScreen.kt`.

- ~~**`ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM` shadowed by a hardcoded `20f` in `logUltrawideFeasibility`.**~~ **Fixed** — now reads the named constant, log message included.

- ~~**~120 lines of `sun.misc.Unsafe` reflection test fixture duplicated across `ScannerScreenTest.kt`/`ScannerFocusIndependentVerificationTest.kt`.**~~ **Fixed** — extracted to `FocalLengthsKeyTestFixture.kt`.

- ~~**1px rounding shift on mirrored rotation axes (90/180/270) in `guideFrameImageRect`.**~~ **Fixed — turned out to be a real bug, not just a stale doc comment.** `dRight`/`dBot` are exclusive bounds (one-past-last-pixel) but were fed straight into the rotation corner-map, which treats its inputs as literal pixel coordinates — silently shifting the mapped rect by 1px on any axis a rotation flips. Verified by hand: a guide box centered in the frame must map onto itself exactly under a 180° rotation; before this fix it came out 1px off on every edge (339/776/659/1222 instead of 340/777/660/1223). Fixed by converting to inclusive coordinates before mapping, back to exclusive after. `ScannerScreenTest`'s 3 rotation assertions updated to the hand-derived correct values. **Not test-executed** — see Environment note below.

### Environment note (not a codebase gap — a tooling limitation, recorded here so it isn't rediscovered from scratch)

- ~~**Every Gradle task — including plain compilation, not just test execution — failed in this session's sandbox with `java.io.IOException: Unable to establish loopback connection`.**~~ **Gone as of session 11 (2026-09-09).** `testDebugUnitTest --rerun-tasks` ran clean on the first try; every fix this wall had blocked is now build-confirmed (see session 11 entry above). Not root-caused why it cleared — not investigated further since it stopped reproducing. Worth a fast sanity check (`gradlew.bat testDebugUnitTest --rerun-tasks`) at the start of any future session before assuming either state.

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

- ~~**1px rounding shift on mirrored rotation axes** (90/180/270) in `guideFrameImageRect`~~ —
  **Fixed session 10** (see that section above) — turned out to be a real 1px bug, not just a
  stale doc comment.

- ~~**`88f/63f` card-aspect literal triplicated**~~ — **Fixed session 10.**

- ~~**`ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM` (20f) is shadowed by a hardcoded `20f`**~~ — **Fixed
  session 10.**

- ~~**~120 lines of `sun.misc.Unsafe` reflection test fixture duplicated verbatim**~~ — **Fixed
  session 10** (`FocalLengthsKeyTestFixture.kt`).

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

- ~~**`PendingImportError` (new, `BackupImporter.kt`) uses raw `SharedPreferences` rather than this
  codebase's `@Singleton`/DataStore convention for persisted state**~~ **Fixed 2026-09-20 (gap-audit
  batch, documentation only).** A comment above `PREFS_NAME` now explains why (DataStore has no
  synchronous write, would race the forced `Process.killProcess()`) — no behavior change.

- ~~**The new `shared_prefs/backup_import_state.xml` (`PendingImportError`'s backing file) isn't
  excluded from Android's Auto Backup domain**~~ **Fixed 2026-09-20 (gap-audit batch.)** Excluded in
  both `data_extraction_rules.xml` and `full_backup_content.xml` now, alongside `exports/`.

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

## Closed 2026-10-03 — scanner crop/zoom calibration entries (working live; see gaps.md history)

- **`GUIDE_FRAME_WIDTH_RATIO=0.75` and `CROP_MARGIN_FACTOR=1.30` were tuned against the *broken*
  capture.** With the preview now on the ultrawide at 0.6x, both may be off; one card (Castform)
  checked. `CROP_MARGIN_FACTOR=1.30` was never validly calibrated (the 1.10 -> 1.30 bump compensated
  for the zoom bug).

- **`detectCardInFrame()` still duplicates `guideFrameImageRect()`'s geometry** instead of sharing
  it — deferred again this session for the same reason (live camera-analysis path, no way to verify
  a refactor here without real hardware). Already deferred twice before this.

- **The two calibration items from item 16 (rotation + crop-margin fix) and item 15/17 (hash-margin
  constants) all still need a real live-device scan** to confirm they hold — see the session-16
  entries below and `project-overview.md` items 15-17.

- **Neither fix above is live-tested yet.** Explicit human-in-the-loop gate per this session's own
  spec — take a real scan on the connected S26 Ultra, orchestrator pulls the resulting
  `scan_debug_*.jpg` and confirms (a) right-side-up, (b) full card (header through footer) visible,
  not just the middle band. `CROP_MARGIN_FACTOR=1.10` is a first guess pending this data — same
  standing pattern as `GUIDE_FRAME_WIDTH_RATIO`'s entire history.

- **Live-tested since session 14's fixes shipped: neither `GUIDE_FRAME_WIDTH_RATIO=0.75` nor the
  `gemini-3.6-flash` model swap resolved the original reported symptoms.** 3 scans post-install
  (Castform x2, Furfrou x1) all still returned `number=null`, identical to the pre-fix behavior.
  Session 14's own entries below describing the ratio bump as addressing this are **superseded** —
  the ratio bump was a reasonable, well-evidenced hypothesis (confirmed Gemini *can* read the
  number given a good enough photo) but did not translate into the live auto-capture pipeline
  actually producing that quality. Root cause of *why* the live capture still underperforms a
  manual photo remains open — the session-14 temp diagnostic (captures every scan to
  `Android/data/com.skyler.pokedexbinder/files/scan_debug_*.jpg`, see `ScannerViewModel.kt`'s
  uncommitted diagnostic block) exists specifically to answer this once more real scans are taken.

- **`GUIDE_FRAME_WIDTH_RATIO` bumped 0.5 -> 0.75** (`79b8417`) — session 13's 0.5 (~15cm) produced
  3 genuine failures live-testing on the S26 Ultra (Furfrou misread as a different species, Furfrou
  and Castform both twice failed to OCR the number). Root cause isolated by replaying a manually-
  taken close-up photo of the same Castform card through the app's exact Gemini call — Gemini read
  the number correctly at that framing, ruling out Gemini's OCR itself as the bottleneck. See
  `project-overview.md`'s Scanner Camera Architecture history, item 12. **Update, session 15: this
  did not resolve the live symptom** — post-install scans still returned `number=null`. See session
  15's entry above; the real fix path is now the hash-margin confidence spec, not further ratio
  tuning.

- **Crop rect is ~1.4x larger linearly (~1.9x by area) than the guide box `CardFrameOverlay` draws on screen** (carried from session 9, unchanged) — needs real-device calibration data per its own note below, not a blind code patch. See session-9 entry.

- **Crop rect is ~1.4x larger linearly (~1.9x by area) than the guide box `CardFrameOverlay` draws
  on screen** — `guideFrameImageRect` takes a fraction of the *image's* display-space width;
  `CardFrameOverlay` draws the same fraction of the *view's* width, and `PreviewView` defaults to
  `FILL_CENTER` scaling (never overridden), so image and view aspect ratios differ. Not a
  positional error (still concentric, never clips the card) — just admits more background than the
  user aimed at, the exact risk the crop feature exists to close. Should fold into Task 5's
  real-device calibration pass, calibrated against the crop's actual behavior, not the drawn box.
  **(Session 10: deliberately left out of that session's cleanup pass — real geometry fix, not a
  cheap one, and needs real-device numbers to pick correct values.)**

## Closed 2026-10-03 — session 16 hash-margin constants (superseded by art hash)

### Still open, unchanged from session 15

- **Both new confidence-path constants (`HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12`,
  `HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16`) are first-guess values, not live-device-tested.** Same
  standing pattern as `GUIDE_FRAME_WIDTH_RATIO`'s entire history — needs Skyler's real S26 Ultra
  scans (same-species multi-candidate cases for the margin, another single-candidate misread for
  the ceiling) before either number should be trusted as final.
  **Live data, 2026-09-17, same session as install (`adb logcat -s ScannerMatch:*`), 4 real
  post-install scans (2x Furfrou, 2x Castform, different angles each), all correctly avoided a
  false-accept (real improvement over session 14/15's actual wrong-assigns) but none reached high
  confidence — all 4 fell to manual-pick, all `path=NONE`:**
  | Card | Gemini number | Best hash distance | Margin | Why NONE |
  |---|---|---|---|---|
  | Furfrou #1 | null | 27/64 | 2 | margin << 12 |
  | Castform #1 | 42 (unmatched) | 25/64 | 0 | number read but unmatched — correctly skips hash path per design |
  | Castform #2 | null | 24/64 | 1 | margin << 12 |
  | Furfrou #2 | 148 (unmatched) | 21/64 | 5 | number read but unmatched — correctly skips hash path per design |

  Every best-match distance landed in a narrow 21-27/64 band, and every margin was ≤5 — all well
  under `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD=12`. Two of four also show Gemini reading a card
  number that matches none of the returned candidates (not a null-number case at all) — the
  deliberate `parsedNumber==null` vs `numberMatch==null` distinction correctly refused to fall
  through to the hash path in both. n=4, 0 false positives so far, but also 0 confirmed true
  positives — no wide-margin case has occurred yet to prove the threshold catches a real match
  rather than just avoiding bad ones. Don't retune off this data alone; keep watching for a
  wide-margin scan.
- Whether this fix would have actually caught the original Furfrou→Zorua misread remains unverified
  — that exact photo was never saved. The kept TEMP diagnostic block in `ScannerViewModel.kt`
  (unchanged this session, still uncommitted) exists to catch the next occurrence.

## Closed 2026-10-03 (session 22) — fixed in `e7e0b72`, unit-tested (529/529), not run on device

- **P2: a failed TCGCSV Japan PRODUCT download is not remembered** (`TcgcsvJapanSource.fetchProducts` `catch -> null`;
  the sync resolver passes no budget). If the host starts rejecting product downloads, an auto sync could send up to 400
  failing requests. Fix: per-group retry-at on failure + a test (5 unbudgeted lookups with `getJapanProducts` throwing =
  1 call). Root: the backoff was added to the one call the finding named, not every call in the per-item loop.
- **`TcgdexSetCatalog.englishNames()` does not remember a failure** (retries `/sets` per English step, ≤6 per scan).
- **Sync result wording is misleading, and English has no backup resolver.** Settings shows "Still missing N (TCGdex has
  no image yet)" but N counts cards where neither TCGdex NOR the Japan TCGCSV resolver had an image (phone log 2026-10-03
  13:40 shows the backup did resolve M6-021 and M6-048). `JapanTcgcsvResolver` returns null for non-`ja`, so an English
  card TCGdex lacks an image for gets no sync fallback (search-time TCGCSV English merge can still patch it). Skyler
  (2026-10-03): "working well for now"; reword the label and consider an English resolver only if he reports misses.
- **Weak tests:** "second refresh never touches owned entries" (PC) cannot fail; "two newest groups" asserts
  `getJapanProducts(1)` not called, which also passes with `take(3)`; share-sheet rotation gate is only tested at
  ViewModel level; the concurrent-sync test fails by timeout, not assertion.
- **KDoc `TcgcsvJapanSource.kt:56` is false** ("one groups call plus one product-list call"; can be two without a budget).
- **Gemini quota cause unconfirmed** (free-tier daily vs per-minute); 429 body is not logged. Gemini is still
  tried first on every scan, so after quota exhaustion each scan pays one failed call; no circuit breaker. A
  Gemini reply with all-null fields does not fall back to Claude.
- **`lowConfidenceCards` tail filter uses card equality, not id** (`ScannerViewModel.kt`); harmless while the
  cascade de-dupes by id.
- **`AppSettings` holds both API keys** — any future log/`toString` of the object leaks them. `executeWithRetry`
  does not close the `Response` on the 429 / non-2xx branches (inherited from Gemini).
- **Claude's English name can be wrong** (read Klawf as "Crabominable"); `printedName` + number + total saved
  the match. `nameVerified` uses a substring test (ミュウ could match ミュウツー; needs exact number+total too).
- **`normalizeName` keeps combining marks:** NFC vs NFD accents could mismatch (both sources emit NFC today).
- **`ScannerViewModel`'s generic `catch (Exception)` swallows `CancellationException`** (pre-existing); its
  `NoApiKeyException` → state mapping has no JVM test (needs camera objects).

## Closed 2026-10-04 — fixed in `c7c9367` and `4167771`, verified (537 unit, 22/22 emulator instrumented), not run on phone

- **`AppNavigationScreenTest`: 6 of 7 tests fail** ("There are multiple DataStores active for the same file ...
  app_settings.preferences_pb"), found 2026-10-04 on the `pokedex_test` emulator (`connectedDebugAndroidTest`: 22 run, 16
  pass, 6 fail; only `drawerShowsAllFiveItemsInTopToBottomOrder` passes, the first test to run). [Likely] cause:
  `SecurityPrefsModule` provides the settings DataStore as `@Singleton` via `PreferenceDataStoreFactory`, and Hilt builds a
  new singleton component per test, so test 2 onward opens a second DataStore on the same file in one process. The module
  is unchanged since `d88be31` (session 16, DI-based testability) and today's merge touched no DI/androidTest files, so it
  is not from session 22; those UI tests were last seen passing 2026-09-04, before that commit. Not bisected.
  Fix options: a Hilt test module that replaces the DataStore providers with in-memory ones, or cancel the scope between tests.

- **Empty Gemini reply + Claude failure:** the user now sees Claude's error (a Claude 429 shows `RateLimited` and bumps
  the counter) instead of the old no-match screen. Spec was silent; optional fix is to return the empty Gemini result.

## Closed 2026-10-04 — seen on the phone by Skyler after scanning a zh-cn Palafin and a ko Elgyem (both logged `crossLanguage=true`)

~~**The cross-language header was never seen on screen**~~, only its log flag.
