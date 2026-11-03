# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned. Struck-through (closed) entries live in
[gaps-archive.md](gaps-archive.md).

## Refreshed — 2026-11-04 (session 23 — model swap + hygiene batch)

**Audit scope note**: full read of the scanner pipeline, DI, manifest, backup XMLs; reference sweep for unused code. Changed
`GeminiCardScanner` (`gemini-3.8-flash`), `ClaudeCardScanner` (`claude-sonnet-5-5`), scan call timeout, parallel hash downloads,
backup exclusions, provider-aware rate-limit text, 10 dead-code removals. Unit tests **547/547** (fresh XMLs, `--rerun-tasks`);
3-lens Opus review 92 / 89 / 88. **Not run on the phone. `compileDebugAndroidTestKotlin` and `lintDebug` could not run**
(sandbox Gradle fails `PKIX path validation` on uncached artifacts, e.g. `hilt-android-testing`, `lint-gradle`): run both in your own shell.
Three stale entries moved to [gaps-archive.md](gaps-archive.md) ("Closed 2026-11-04").

### New, not fixed

- **The 10 s scan `callTimeout` has no data behind it** (`SCAN_CALL_TIMEOUT_MS`, user decision). It covers upload + model time + body
  read and nothing streams. If healthy scans exceed 10 s, each provider fails after ~43.5 s (4 attempts) and every attempt is
  billed; the screen then says "check your internet connection". Nothing logs elapsed ms per attempt. Add that log, then retune.
- **Encrypted-prefs recovery is built (2026-11-04) but not tried on a device.** `createOrResetOnDecryptFailure` in `SecurityPrefsModule.kt`
  deletes an unreadable `settings_secrets` / `publish_secrets` file and recreates it empty (GeneralSecurityException / IOException only;
  the keys must be re-entered). Caveat: a transient Keystore failure would also wipe the stored keys. The backup exclusions only
  apply to backups made after the hygiene build: press "Back up now" once after installing.
- **Untested:** `ScannerViewModel` passing `e.provider` into `RateLimited` (the constructor default is gone, so the compiler now
  enforces it); the `PerceptualHasherTest` cancellation test cannot fail for the leak it is written for (its final assert is always true).
- **P3:** provider is a plain `String` although `ScanProvider` exists; the rate-limit text hardcodes `console.anthropic.com` for any non-Gemini provider.
- **`ScannerScreen.kt` split (2026-11-04, pure move, no logic change):** geometry/detection -> `ScannerGeometry.kt`, camera focus/diagnostics -> `ScannerCamera.kt`; the file is 664 lines, down from 1,034. `CameraPreview` (~200 lines) and the nine-state `ScannerScreen` stay together and still have no camera tests.
- **Stale in this file:** the session-15 "`SmartThresholdUseCase` can never mark high confidence when the number is null" entry predates the `HASH_MARGIN` path.

## Refreshed — 2026-10-04 (session 22, part 2 — androidTest DataStore fix, clearer scan failure)

**Audit scope note**: added `androidTest/di/FakeSecurityPrefsModule.kt`; changed `CardScanProvider`, `CardScanShared`,
`CardScanProviderTest`. Reviewed 95 (androidTest fix) and 93 (scan failure), single reviewer each. Unit tests **537/537 green** on
`master` (fresh XMLs); instrumented suite **22/22 on the emulator** (mutation-checked: without the module the same 6 fail).
**Not run on the phone.** Two closed entries are in [gaps-archive.md](gaps-archive.md) (section "Closed 2026-10-04").

### New, not fixed

- **Which failure produced the 2026-10-04 12:02 scan error is unknown.** The screen showed `Unable to resolve host
  "api.anthropic.com"`; the phone was disconnected, so the `scan provider=none geminiError=… claudeError=…` log line was not
  captured. A real DNS outage cannot be fixed in the app (check Wi-Fi/mobile data, Settings > Private DNS, VPN/ad-blocker,
  captive portal). Next time, capture logcat tag `ScannerMatch` right after a failure.
- **Wording gaps (P3):** a malformed Claude reply (`IOException("Empty Claude response")` / `"No text in Claude response"`,
  `ClaudeCardScanner.kt:50,56`) is classified as a network failure and says "check your internet connection"; a Gemini
  `InvalidApiKeyException` is folded into "Gemini failed"; a Gemini-only config with a network failure still shows raw text.
- **Log line order:** `scan provider=none` is logged before the empty-Gemini result is returned, so a scan that returns a
  result can log as `provider=none` (diagnostics only).
- **`connectedDebugAndroidTest` exits 1 ("There were failing tests") even when the test log says `OK (22 tests)`**; cause
  unexplained. Read verdicts from `testlog/test-results.log` only; an absent `test_status` in the textproto is NOT a pass.
- **The pipeline folders of this run were deleted with the worktree** (`.pipeline/` is not archived to `.pipeline-archive`).

## Refreshed — 2026-10-03 (session 22 — gaps.md code fixes, merged to master `e7e0b72`)

**Audit scope note**: touched `TcgcsvJapanSource`, `TcgdexSetCatalog`, `CardScanProvider`, `CardScanShared`, `GeminiCardScanner`,
`ParsedCardInfo`, `CardSearchRepository`, `SettingsRepository`, `PublishSettingsRepository`, `ScannerViewModel`, `SettingsScreen` + tests.
Three Opus lenses: 93 / 92 / 88 (min 88, ship). Unit tests **529/529 green** on `master` (fresh XMLs, `--rerun-tasks`).
**Not run on the phone.** Ten closed entries are in [gaps-archive.md](gaps-archive.md) (section "Closed 2026-10-03 (session 22)").

### New, not fixed

- **The new `nameVerified` has not run on a device.** Re-scanned 2026-10-04 on the new build: Klawf (`SV1S-088`) and Mega
  Malamar ex (`M6-048`) were both accepted via `IDENTITY_PINNED`, but both took the dex-pinned query, which runs before
  `nameVerified` (log: `nameVerified=false`), so only NFC and the pinned path are confirmed. `nameVerified` is now a lone
  whole-name match (tolerating a trailing ex/gx/v/vmax/vstar); it only matters when the reader drops the dex number. A
  variant outside the suffix list (e.g. fullwidth `ＥＸ`) shows as unverified, never auto-accepted wrongly.
- **Product-download failure is remembered per group**, so the first failure for each distinct group (~83) still costs one call.
- **Untested:** "a cooled-down group spends no `FetchBudget`" (swapping the two guard lines stays green); the
  `CancellationException` rethrow in `ScannerViewModel`; the Gemini success-path `.use` body close.
- **`AppSettings.toString` / `PublishConfig.toString` are hand-written**, so a new field silently drops out of the output.
- **Small:** `EmptyScanException` is never thrown (log name only); the 401/403 `blocked` check is duplicated at
  `TcgcsvJapanSource.kt:124,181` and `PRODUCTS_RETRY_MS` restates the 15-minute literal.
- **Gradle loopback wall did not recur this session.** `./run_build.bat <task>` from Bash worked, including inside a
  worktree (needs `local.properties` copied in). The root `CLAUDE.md` line "often dies" is now overstated; left alone
  because Skyler's edits to that file are uncommitted.
- **`security-crypto` stays** (Parked section). A plaintext-storage revert was built, reviewed (93/92/88) and then backed out
  before merge at Skyler's request (he had read the name as cryptocurrency-related); nothing from it reached `master`.

## Refreshed — 2026-10-03 (session 21, part 2 — TCGdex-only rebuild, wipe/reset, image sync)

**Audit scope note**: touched/created `CardSearchRepository.kt` (pokemontcg.io removed), `TcgdexIds.kt`, `TcgdexSetCatalog.kt`,
`TcgcsvJapanSource.kt`, `JapanTcgcsvResolver.kt`, `SyncCardImagesUseCase.kt`, `CollectionResetDao.kt` + reset UI,
`PersonalCollectionRepository.kt`, `PublishRepository.kt` (Discord toggle), 5 DAOs (blank-image queries). Reviewed by three
Opus lenses (89 / 94 / 94 on the second pass). Unit tests **505/505 green**, `compileDebugAndroidTestKotlin` green.
**None of this part has run on the phone** (installed build predates it).

### New, not fixed

- **Reset + image-fill SQL now executed** (2026-10-04, emulator, not the phone): `resetAllClearsAssignmentsButKeepsSlotRowsAndGroups`
  and `fillBlankImageOnlyTouchesBlankRows` pass; `fillBlankImage` is still covered for `main_binder` only.
  Do not run `connectedDebugAndroidTest` against the phone: AGP uninstalls the app afterwards and would delete its data
  (pin the emulator with `ANDROID_SERIAL=emulator-5554`; boot it with `-gpu swiftshader_indirect -no-window`).
- **`MAX_AUTO_IDS` ceiling:** the auto sync checks the first 400 blank ids (ko / zh-cn last); if >400 ja/en blanks never
  resolve, later ones are starved until a manual sync.
- **English has no backup image resolver.** `JapanTcgcsvResolver` returns null for non-`ja`, so an English card TCGdex
  lacks an image for gets no sync fallback (search-time TCGCSV English merge can still patch it). Skyler
  (2026-10-03): "working well for now"; build one only if he reports misses.
- **Weak test:** the share-sheet rotation gate is only tested at ViewModel level.
- **Only ~83 of 116 Japanese sets join to TCGCSV** (vintage `E*/neo*/PMCG*/PCG*`, `SM1p-5p`, `MC`, `SVLS` and part of
  `M-P`/`SV-P` don't): those stay image-less. U3 (Japanese backup search via TCGCSV when TCGdex is down) was deferred —
  a saved `tcgcsv_ja_` card would be orphaned once TCGdex adds the set. English TCGCSV still scans ~217 groups per manual
  search (~30 MB raw); a compact name index would fix it.
- **No Japanese/other-language fallback when TCGdex is down**: search throws "Card database (TCGdex) unreachable"
  (English manual search can still be rescued by TCGCSV). Accepted.
- **User-Agent for tcgcsv.com not changed**: `Mozilla/5.0 (Linux; Android 13) PokedexBinder` returned 200 on 2026-10-03;
  a 401 "User-Agent blocked" appeared once during a 370-request probe burst. If the phone sees blocked responses, change
  it to `PokedexBinder/1.0` (known good).
- **Spec drift:** the archived specs still mention release-date ordering for Personal Collection (dropped by decision) and
  T8a (skipped). Archived specs are history; do not "fix" them.
- **Unrelated uncommitted edits** (Skyler's `CLAUDE.md` / `app/CLAUDE.md`, `scripts/scan_capture.log`) remain out of the commits.

## Refreshed — 2026-10-03 (session 21 — scanner matching overhaul)

**Audit scope note**: touched `CardSearchRepository.kt`, `SmartThresholdUseCase.kt`, `PerceptualHasher.kt`,
`ArtHash.kt`, `CardScanProvider.kt`, `ClaudeCardScanner.kt`, `CardScanShared.kt`, `GeminiCardScanner.kt`,
`ScannerViewModel.kt`, settings files; read `ScannerScreen.kt` (header only). Not a full re-audit.
Tests 428/428 green; live-verified for Japanese cards on 2026-10-03 (see handoff).

### New, not fixed

- **The art hash cannot discriminate in the 20–45 band.** Correct scans scored 8–34; wrong/absent cards 24–30.
  Identity therefore rests on metadata, with 45 only a sanity ceiling. Root: no labelled calibration set, and the
  capture art box assumes the physical card fills the guide frame (unmeasured). Collect `ranked=` log lines.
- **TCGdex data gaps.** zh-cn `CSV9C`/`CSV9.5C` have 0 cards; ko (239 cards) and zh-cn (877) have no images; newest
  JA set `M6` has no images; JA Black Kyurem ex has no `dexId`; list filters are substring matches (client-side
  exact filtering is mandatory). Coverage varies by language; a paid/other source would be the only fix.
- **Korean (and partly Traditional Chinese) card coverage is thin; circle back to PTCG-database.** TCGdex `ko` has
  only 239 cards and no images; `zh-tw` has 98 sets. `github.com/type-null/PTCG-database` (MIT, static JSON per
  language folder, last commit 2026-09; README claims ko 20,323 cards / 264 sets, tc 14,583 / 134, jp 24,039 /
  329; images hotlinked from official regional sites; its Simplified-Chinese data is copied from TCGdex, no
  Thai/Indonesian) is the only free source found. It has no search API: it would need a per-language index
  downloaded and cached on the phone, searched locally, matched by number + set total + printed native name.
  Not built (Skyler 2026-10-03: "we don't necessarily have to build that now"). README figures unverified by me
  beyond the `data_jp` folder listing (329 entries). Until built, a Chinese/Korean card that TCGdex lacks falls to
  the "closest English printings" list, which Skyler accepts for now.
- **Gemini quota cause unconfirmed** (free-tier daily vs per-minute); 429 body is not logged.
- **TCGdex call budget:** worst case on a total miss is 11 requests (zh) / 8 (others), above the spec's 7 / 5. (After
  part 2 the ported name+number / dex+number steps raised the test's bound from 7 to 12.)
- **Claude's English name can be wrong** (read Klawf as "Crabominable"); `printedName` + number + total saved
  the match.
- **`ScannerViewModel`'s `NoApiKeyException` → state mapping has no JVM test** (needs camera objects).
- **Saved card Language is not pre-filled from the detected language** (out of scope; touches 4+ assign flows).
- **`scripts/claude_scan_bakeoff.py` never run** (Skyler's Python lacks `anyio`); prices in it are from the
  claude-api skill's cached table (2026-06-04) and the per-scan cost (~$0.002 Haiku / ~$0.006 Sonnet) is an
  estimate, not measured.
- **Gemini API key still unrotated** (carried from session 20).

## Parked — 2026-09-30 (session 20, from the security/dependency pass) — circle back after the current fixes are test-green

Read-only pass (manifest + `libs.versions.toml`); no CVE database lookups were run.

- **`security-crypto` is deprecated.** Project is on `1.1.0-alpha06`; Google deprecated the library
  in `1.1.0-alpha07` (Reddit post quoting the release; the Jetpack releases page does not list
  it), so no further fixes. It backs the Gemini key, GitHub PAT and Discord webhook
  (`SecurityPrefsModule.kt`, `SettingsRepository`, `PublishSettingsRepository`). Works today, no
  known vulnerability. Option when picked up: Keystore AES-GCM (or Tink) behind the existing
  DataStore/SharedPreferences seams, with a migration for the three secrets. Risk: a migration
  bug could wipe stored keys, so plan it (`planning` + pipeline), don't quick-edit.
- **Stale dependencies, no audit cadence.** `navigation-compose` 2.7.7, `room` 2.6.1, `coil` 2.6.0,
  `coreKtx` 1.13.1, `accompanist-permissions` 0.34.0, `lifecycle` 2.8.3 are behind. `okhttp`
  4.12.0 is current for 4.x. Each bump needs a Gradle build and carries regression risk; no known
  payoff. Run `dependency-management` when a bump is actually wanted.

## Refreshed — 2026-09-30 (session 20 — cleanup batch)

**Audit scope note**: touched `GeminiCardScanner.kt`, `ScannerScreen.kt` (geometry helpers,
`CameraPreview` bind path, focus logging), `CardSearchRepositoryTest.kt`, two scanner test files;
read `CardSearchRepository.kt`, manifest, `libs.versions.toml`. Not a full re-audit.

### New, not fixed

- **Root cause of why these sat open so long:** they were gated on live-device evidence Skyler's
  phone/Gemini quota kept blocking (sessions 18-19). The gated items (live `query=` data, Reviewer
  F1, hash-margin calibration, `GUIDE_FRAME_WIDTH_RATIO`/`CROP_MARGIN_FACTOR` re-tune) are still
  open and unchanged — see session 19's list below.
- **No test covers `CameraPreview`'s simplified bind path** (compose + real camera only); the
  fallback-bind branch it used to have is gone, so a failed bind now yields no camera and a log line.

## Refreshed — 2026-09-29 (session 19 — capture zoom mismatch, key-in-logcat)

**Audit scope note**: touched `ScannerScreen.kt` (bind path), `NetworkModule.kt`, two gradle files;
read `ImageProxyExt.kt`, `GeminiCardScanner.kt` (grep only). Not a full re-audit.

### New, not fixed

- **The Gemini API key is exposed and unrotated.** It appeared in logcat and in this session's
  transcript (auto-archived to the vault). Why it existed: key sent as a URL query param -> any
  URL logger leaks it. Rotate it; move to the `x-goog-api-key` header in `GeminiCardScanner.kt:83`.
- **No test for the new `ZoomState` observer** (`ScannerScreen.kt`). It runs only against a real
  bound camera; it is proven by one live scan, not by any automated test.
- **The still capture's lens was never observed, only inferred from the image.** The session-level
  `CaptureCallback` diagnostic got no still results. If the fix regresses on another device/OS
  update, there is no log line that says which lens the still used.
- **`okhttp-logging` removal and `NetworkModule` edit** build clean (Skyler's `installDebug`), no
  behaviour test.
- **Gradle loopback wall, 5th consecutive session** — 2/2 attempts failed from Claude again (Bash
  and PowerShell); Skyler ran Gradle himself. Cause still undiagnosed.
- **Live matching path (`query=`, Castform via `dex+number+total`, Reviewer F1) still not
  live-tested** — Gemini 429 daily quota blocked every scan this session.

## Refreshed — 2026-09-29 (session 18 — species-guard query reorder)

**Audit scope note**: touched `CardSearchRepository.kt`, `ScannerViewModel.kt` and
`CardSearchRepositoryTest.kt` only; a `TODO`/`FIXME`/`XXX` grep on the two production files found
nothing. Not a full codebase re-audit.

### New this session, not fixed

- **`dex+number` (no set total) can pre-empt a correct `number+total` hit** with a same-species card
  from a different set when the API's dex data is sparse (Reviewer F1). Follows from the spec's
  mandated order; needs live `query=` data to know if it happens.
- **`CardSearchRepositoryTest`'s new tests depend on `safeSearch`'s broad `catch (e: Exception)`**
  swallowing unstubbed strict-mockk calls (Reviewer F3). Narrowing that catch fails all 7 with a
  confusing mockk error.
- "still tries name before dex" test would also pass on the old order (guards regressions only);
  `name+number` and `broad-dex` labels have no dedicated test (Reviewer F2 + Tester).
- **The Gemini retry-budget change (4 attempts, exponential backoff) was committed at Skyler's
  request without ever being test-run** — see `handoff.md` next step 2.
- **Project `CLAUDE.md` claims Bash cannot invoke `gradlew.bat`; a Tester subagent ran it from Git
  Bash and Gradle started.** The line looks stale. Left as-is pending Skyler's call.
- **P2 still open:** whether `number+total` should exist at all — needs live `query=` data.
- **Gradle loopback wall, 4th consecutive orchestrator-level failure, 3rd session running.** Cleared
  only by Skyler running Gradle himself. Cause still undiagnosed.

## Refreshed — 2026-09-22 (session 17 — logcat check, direct debugging skill fix)

### Fixed this session (root-caused via `adb logcat`, not a full pipeline run)

- **New finding this session, fixed**: `CameraPreview`'s `AndroidView` never unbound the camera
  provider when leaving composition — relied entirely on CameraX's own lifecycle-owner-stop unbind,
  which lags Compose's own teardown enough to race the PreviewView's SurfaceView release. Confirmed
  via logcat: `BufferQueueProducer: ... BufferQueue has been abandoned` (4-5x, ~150-300ms) on every
  one of 4 scan sessions, immediately after `SV[...] releaseSurfaces`. Fixed: `AndroidView`'s
  `onRelease` now calls `provider.unbindAll()` directly. Full detail: `project-overview.md` item 18.

### Investigated this session, disputed — not a bug

- **One `SQLiteLog: (10) POSIX Error 9 / SQLite Error 3850` lock-protocol line at cold start.**
  Single occurrence, no exception, no failed query, no crash, self-resolved. Matches known benign
  Samsung/Android SQLite-VFS log noise; no app code owns SQLite locking directly (single Room DB,
  single connection pool). Not fixed — would be a blind patch with no confirmed root cause, against
  the debugging skill's own Iron Law. Flagging here so it isn't re-investigated from scratch if it
  recurs.

### Second pass this session (2026-09-23) — live-retest after a fresh install

- **Session 16's rotation fix confirmed correct** — the retest's first scan actually succeeded
  (Gemini 200 on the 3rd retry), producing this project's first-ever pulled-and-viewed successful
  debug image (`scan_debug_1790101276039.jpg`): upright, text reads normally. Rotation fix holds.
- **New data point on the Gradle loopback wall**: failed 3/3 more orchestrator-direct attempts this
  session (`installDebug` once, `testDebugUnitTest` twice), on top of yesterday's 3/3 — the wall is
  now failing consistently at orchestrator level across 2 consecutive sessions, not the "always
  clears" pattern documented before 2026-09-22. `installDebug` itself was run by Skyler directly,
  same workaround as yesterday.

### New this session — the actual headline finding, initially missed

- **All 4 of this session's live-test scans failed at the Gemini API call — none ever reached the
  matching pipeline.** Skyler's stated purpose for this session was live-testing session 16's
  pending items (rotation/crop-margin, hash-margin confidence); first pass at the logcat check
  missed this and reported only crash/error noise. Re-checked the same captured log for
  `ScannerMatch`/Gemini HTTP activity: 4 scans × 3 attempts each (the app's own `executeWithRetry`
  budget) = 12 requests, 11 got `503 Service Unavailable`, 1 timed out — 100% failure rate, every
  scan exhausted its retries and hit `ScannerViewModel.kt:133`'s generic catch, surfacing
  `ScannerState.Error("Gemini error: 503")` on screen each time. `503` is Gemini's own
  model-overloaded signal, distinct from the session-14 `429`/shared-quota-project gap (still
  separately open, below) — not reproducible from this log alone whether it was a transient
  Gemini-side outage at that specific time (2026-09-22 ~21:15-21:17) or something recurring; needs
  a retry to know. **Session 16's live-test items remain unverified** — zero successful scans this
  session to check rotation/crop or hash-margin data against.
- **Item 18's two fixes (camera unbind + focus in-flight guard) are build-verified only, not
  live-device re-tested** — same root cause as above: no scan reached far enough to exercise
  either fix's actual behavior (the camera-unbind fix triggers on leaving the screen regardless of
  scan outcome, so it likely did fire all 4 times, but wasn't specifically confirmed). `compileDebugKotlin
  --rerun-tasks` succeeded (confirmed on Skyler's own machine, see below).
- **Not committed.** Both fixes sit uncommitted in the working tree (`ScannerScreen.kt`) — Skyler
  didn't ask for a commit this session.
- **New data point on the Gradle loopback wall — this time the standing "always clears on
  orchestrator retry" pattern did NOT hold.** `compileDebugKotlin --rerun-tasks` hit
  `Unable to establish loopback connection` 3/3 times run directly by the orchestrator (inline
  command, a real `.ps1` with `--no-daemon`, the same `.ps1` with `dangerouslyDisableSandbox: true`)
  — first time this has failed at the orchestrator level since the pattern was first documented
  session 16. Resolved only by Skyler running the identical command on his own machine via screen
  share (`BUILD SUCCESSFUL in 31s`). Logged as an update to the standing lesson file
  (`~/.claude/rules/lessons/subagent-sandbox-blocks-loopback-sockets-try-orchestrator-first.md`) —
  what changed on the host between 2026-09-17 and now (VPN, firewall, AV, Windows update) is
  untested, flagged open, not diagnosed.

## Refreshed — 2026-09-20 (session 16, cont'd — testable settings repos)

### New this session — 2 real bugs found and fixed while building the above, neither shipped

- **A `TestScope`/`runTest{}` scheduler mismatch caused an indefinite hang** (21+ min, no progress,
  had to be killed manually) — `DataStore` built in `@Before` via `scope = TestScope().
  backgroundScope`, but the test body's own `runTest {}` uses a separate, disconnected `TestScope`,
  so DataStore's internal actor never got scheduled. Fixed by dropping the explicit `scope`
  (defaults to the same real `Dispatchers.IO`-backed scope production already uses).
- **A documented Windows-only AndroidX DataStore bug** — real, temp-file-backed `DataStore.edit()`
  called 2+ times in one JVM test throws `IOException: ... multiple instances of DataStore for this
  file` (confirmed via Google's own `nowinandroid` hitting the identical issue, PR #1542). Fixed
  with a hand-written in-memory `DataStore<Preferences>` fake instead of adding the
  `okio-fakefilesystem` dependency the "official" nowinandroid-style fix would need.

## Refreshed — 2026-09-20 (session 16, gap-audit batch)

Full audit of the standing gap list at Skyler's request ("fix everything that doesn't need me to
run"). Manual security review (secrets storage, exported components, backup domain) and dependency
audit (OkHttp/Room/Moshi/AGP, no active CVEs on pinned versions) both performed this session.

## Refreshed — 2026-09-20 (session 16, cont'd)

### Fixed this session (full `dev-team-pipeline` run, ship at 97/100)

- **Captured images were never rotated upright** — `ImageProxyExt.kt`'s `toCroppedBitmap()` used
  `rotationDegrees` only for crop-rect math, never for the actual pixel rotation. Found by pulling
  4 real `scan_debug_*.jpg` files via `adb pull` and looking at them: every one sideways. This has
  been true since the crop function was written — every image ever sent to Gemini has been
  sideways. Fixed: `Matrix().postRotate()` on the already-cropped bitmap, no-op at rotation 0.
  305/305 tests pass, `assembleDebug` clean. Full detail: `project-overview.md` item 16.
- **Guide box gave too little visual/physical margin, causing real header/footer clipping** — box
  consumed 87.7% of canvas height with only corner-bracket visual feedback (no continuous edge).
  Mathematically confirmed the crop geometry itself matched the on-screen box exactly (not a math
  bug) — the box was just too tight and too hard to align to precisely. Fixed: full `drawRect`
  border replacing corner brackets, plus `CROP_MARGIN_FACTOR=1.10f` (first-guess, uncalibrated)
  inflating only the actual capture crop, not the drawn box or `detectCardInFrame()`'s presence
  heuristic. Full detail: `project-overview.md` item 16.

## Refreshed — 2026-09-17 (session 16)

### Recurred this session — 4th occurrence, now has a standing lesson

- **The Coder-stage subagent hit the standing Gradle daemon IPC wall** (`Unable to establish
  loopback connection`, open since session 10) and could not run any Gradle task. The orchestrator
  ran the exact same commands directly immediately after and the wall cleared on the **first**
  attempt — same pattern as every prior occurrence (sessions 10-13). Captured as a standing lesson
  this session: `~/.claude/rules/lessons/subagent-sandbox-blocks-loopback-sockets-try-orchestrator-
  first.md`. Also confirms `usePlainSocketImpl`/forced `WindowsSelectorProvider` (session 13's
  "not yet tried" candidates) are dead ends — don't re-suggest them.

## Refreshed — 2026-09-13 (session 15)

**Audit scope note**: no production code touched this session — pure investigation (live logcat,
model-deprecation root-cause, a real empirical hash-collision test against 16 live card images,
and a spec write-up). One diagnostic-only addition (`ScannerViewModel.kt`, temp capture-to-disk)
made session 14 remains uncommitted in the working tree.

### New this session — confirmed root cause, real bug, not fixed yet

- **Separately, structurally: `SmartThresholdUseCase.evaluate()` can never mark high confidence
  when Gemini's OCR'd card number is null, regardless of hash quality** — it only checks for a
  `numberMatch` among candidates ([SmartThresholdUseCase.kt:21-33](app/src/main/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCase.kt:21)), never consulting the
  perceptual hasher's own result at all. This means fixing the hash bug above, by itself, does
  **not** fix the live-reported "Furfrou/Castform number came back null, got a manual-pick list"
  symptom — confirmed by tracing the exact code path, not assumed. Needs a genuinely new
  confidence path (hash margin, not just a bare top-pick) — spec'd as tasks 3-6 in the same doc.
  Both this and the `candidates.size == 1` short-circuit below (session 14 entry, unchanged) are
  now covered by one spec rather than two separate ad-hoc fixes.
- **Competitive research note (informational, not a gap)**: looked at EyeRis (a shipped competitor,
  App Store id6789088141) — it advertises on-device camera matching rather than cloud OCR.
  Verified this does *not* require bundling a full card-image database for this app's own
  narrower design (species-via-Gemini + hash-rank same-species candidates reuses the existing
  per-scan candidate-fetch flow, no new data pipeline) — full detail and the rejected "skip Gemini
  entirely" alternative in `docs/specs/2026-09-13-scanner-hash-confidence.md`.

## Refreshed — 2026-09-10 (session 14)

**Audit scope note**: this session touched exactly one production file (`ScannerScreen.kt`, a
single constant + UI string) plus 2 test files updating fixture values to match — a scoped grep
for `TODO`/`FIXME`/`XXX` in the touched files found nothing new. Not a full codebase re-audit;
prior sessions' entries below remain comprehensive as of session 12's own full pass. Most of this
session was live-device diagnosis (logcat, a real Gemini API dashboard check) and a Gemini quota
investigation — no code touched by that half of the session.

### New this session, not fixed — deferred, needs Skyler's call

## Refreshed — 2026-09-10 (session 13)

**Audit scope note**: this session touched exactly two files (`GeminiCardScanner.kt`,
`ImageProxyExt.kt`) plus their tests — a scoped grep for `TODO`/`FIXME`/`XXX` in the touched files
found nothing new. Not a full codebase re-audit; the prior sessions' entries below are already
comprehensive and current as of session 12's own full pass. See session 12/9's own entries above
for the two items fixed this session (marked with strikethrough in place, not duplicated here).

### Fixed this session

- Both carried over from session 12/9: the Gemini retry gap (`c70d8b6`) and the degenerate-`cropRect`
  P2 (`806242d`). Struck through in their original entries below rather than re-stated here.

### New this session, not fixed

- **`GeminiCardScanner.kt`'s unchanged 400/401/403/404 path has no dedicated test.** Surfaced by
  both the orchestrator's own acceptance-criteria trace and the Reviewer, independently, during the
  retry-logic pipeline run. The code path itself (`if (!response.isSuccessful) throw
  IOException("Gemini error: $code")`) is byte-for-byte unchanged from before this session and
  correct by inspection — not a behavior regression — but no test (old or new) exercises it
  specifically, so a future change to this function could silently break it with nothing to catch
  it. Cheap fix when picked up: one `MockResponse().setResponseCode(404)` test asserting immediate
  `IOException` and `requestCount == 1`.
- **Subagent spawns can die silently mid-task on the account's session rate limit, with zero
  partial output written.** The `pipeline-tester` subagent hit this mid-run this session — no
  `.pipeline/test-results.md`, no partial file, just a `failed` task notification. Not a codebase
  gap (infra/quota, not app code) but a real process gap worth naming: any pipeline run assuming a
  subagent's output file exists after a "completed" or even "failed" status should still check the
  filesystem before trusting either state. The orchestrator compensated by doing that stage's work
  directly this time; a future session hitting this earlier in a longer pipeline (e.g. Stage 1)
  would need the same fallback.
- **New data point on the intermittent Gradle `Unable to establish loopback connection` wall**
  (open since session 10, never root-caused, previously just "self-resolves"): this session it did
  **not** self-resolve for a subagent despite 6 workaround attempts, while clearing immediately for
  direct orchestrator-run commands — consistent with, and reinforcing, the session 10-12 data point
  that this fires more reliably for Tester/Coder-stage subagents than orchestrator-run commands in
  the same session, same repo state. New this session: the stack trace consistently bottoms out at
  `sun.nio.ch.WEPollSelectorImpl` → `UnixDomainSockets.connect0` → `SocketException: Invalid
  argument: connect` — points at the JDK's Windows AF_UNIX-socket-based NIO selector self-pipe
  specifically, not a literal TCP loopback failure (a plain TCP loopback self-test on the same
  machine works fine, checked this session). Not yet tried: forcing the classic
  (non-AF_UNIX-based) Windows selector, e.g. `-Djdk.net.usePlainSocketImpl=true` as a Gradle JVM
  arg. Worth an actual attempt next time this recurs, rather than another round of retries.

## Refreshed — 2026-09-10 (session 12, live-device debugging after the crop fix shipped)

### New this session, not fixed — flagged, deferred to next session

- **`ScannerViewModel.kt` had zero logging on the match pipeline before tonight** (Gemini's parsed
  read, candidate count, perceptual hasher's pick, final confidence) — added a `ScannerMatch` log
  tag this session (diagnostic-only, no behavior change) specifically because this made the
  confidence-check bug above impossible to root-cause past "something in here is wrong." Worth
  keeping permanently, not just for this investigation — this file previously had the exact same
  standing gap `ScannerScreen.kt`'s `ScannerFocus` tag already existed to close on the focus side.

## Refreshed — 2026-09-09 (session 12, mid-session — full wrapcon pending)

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

### Open, newly logged this session (from attempt #6, deliberately deferred — none touched across 3 review iterations)

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

### Fixed this session (cont'd)

- **Binder Backup's new regression tests for the DB-closed-before-restart fix failed on a clean
  first `--rerun-tasks` run** (`NoClassDefFoundError: Hilt_MainActivity`), passed on immediate
  re-run, `[Likely]` a Windows KSP-regeneration race rather than a real test-logic bug (the
  production path was independently proven correct live on-device, separate from this test). The
  regression guard for this specific fix can go red for reasons unrelated to the code — if
  `BackupImporterTest`'s two newest cases (the ones asserting `Process.killProcess`/`startActivity`
  after a simulated swap failure) ever fail again, re-run clean before assuming a real regression.
- **New, secondary finding (2026-09-04, same logcat)**: repeated
  `CameraControl$OperationCanceledException: Cancelled by another startFocusAndMetering()` —
  multiple `triggerFocus()` calls firing in quick succession (likely edge-triggered refocus and/or
  tap-to-focus overlapping a retry from the fix above) cancel each other's in-flight metering
  requests. Every observed sequence in the log still eventually reaches a real
  `isFocusSuccessful=true`, so this is **not** currently believed to be blocking focus lock
  outright — flagged for awareness, not treated as the primary suspect for the wrong-card symptom.
  Worth a closer look if the macro-focus pipeline's own focus-request logic (which will replace
  large parts of this function) doesn't incidentally resolve it.

### Open, newly discovered this session (2026-09-04, cont'd) — surfaced only now that `connectedDebugAndroidTest` can finally execute

- **Gradle's own `connectedDebugAndroidTest` task-level pass/fail is unreliable in this sandbox,
  separate from whether tests actually pass** — `"Failed to receive the UTP test results"` (a UTP↔
  Gradle IPC glitch) makes the Gradle task report `FAILED` even on a run where every single test in
  the JUnit XML/textproto output shows `PASSED` (reproduced twice this session: identical 9/9-pass
  textproto both times, Gradle task FAILED both times). **When verifying a `connectedDebugAndroidTest`
  claim in this sandbox, read `app/build/outputs/androidTest-results/connected/debug/.../test-result.textproto`
  directly — don't trust Gradle's own exit code/summary alone.**

## Refreshed — 2026-08-28 (session 6, cont'd)

### Fixed this session

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

### Open, newly logged this audit pass

- **`skylermayday-site`'s "Other" nav tile possibly swallows Personal Collection/Connecting
  Art/Unown** — noticed in passing during this session's live-site check, not investigated.
  Different repo, out of this project's scope to fix; tracked here only per this file's existing
  cross-repo-contract precedent (see `binder.json` entry below) so the observation isn't lost.
  Action, if any, belongs in a `skylermayday-site` session, not this one.

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
