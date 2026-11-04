# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned. Struck-through (closed) entries live in
[gaps-archive.md](gaps-archive.md).

## Refreshed — 2026-11-04 (session 24 — redundancy audit)

**Audit scope note**: `ponytail-audit` read of deps, repositories, DAOs, interfaces; screens not opened. Tests 552/552. Archived history (sessions <=20) is in [gaps-archive.md](gaps-archive.md) and was not re-audited.

### New, not fixed

- **Deferred cuts** (each needs a device check or rewrites tests for little gain): `accompanist-permissions` (loses the resume re-check if replaced naively), `material` lib (theme parent, dark-mode flash risk), `AssignCardUseCase` merge, five identical blank-image DAO query pairs, `ConnectingArtRepository` pass-throughs, `SqliteVersionReader` bound twice (constructor default + `@Provides`). `SecondaryBinderScreen`/`UnownBinderScreen` and `ConnectingArtScreen`/`PersonalCollectionScreen` may share a composable; unread.

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
