# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned. Struck-through (closed) entries live in
[gaps-archive.md](gaps-archive.md).

## Refreshed — 2026-11-12 (session 28 — Room schema export on, commit `760aafd` on `master`)

**Audit scope note**: touched `app/build.gradle.kts`, `gradle/libs.versions.toml`, `PokedexDatabase.kt`, new `app/schemas/.../9.json`, new `Schema9ValidationTest`. On `master` after the merge: unit tests **595/595** (fresh XMLs, `--rerun-tasks`, with `9.json` present), `compileDebugAndroidTestKotlin` and `lintDebug` (alone) pass. On the branch: emulator `OK (31 tests)` (`emulator-5554` only), `debugRuntimeClasspath` / `releaseRuntimeClasspath` / `debugUnitTestRuntimeClasspath` identical to `master`. Pipeline run (Planner, Coder, Tester, single Reviewer 89/100, two text-only fixes applied). Not run on the phone.

### Closed
- **Room schema export blocked by `AbstractMethodError`**: root cause was kotlinx-serialization 1.8.1 on the KSP processor classpath (room-migration BOM pin) against Room bundle serializers built for >= 1.9. Fixed with `resolutionStrategy.force` to 1.9.0 on `ksp*` and `debugAndroidTestRuntimeClasspath` only (a constraint does not work; no Kotlin/KSP bump needed). Cause proven by removing/restoring the force. Moved to [gaps-archive.md](gaps-archive.md).

### New, not fixed
- **The force must be revisited on any Room/KSP/Kotlin bump:** check `kspDebugKotlinProcessorClasspath` for kotlinx-serialization >= 1.9 without it, then delete the block and `kotlinxSerialization` from the catalog (note in `build.gradle.kts`).
- **`Schema9ValidationTest` cannot detect drift between `9.json` and the entities** (renamed column, changed `identityHash`, extra column in `createSql` all pass; only corrupt SQL fails): at equal start/end versions `MigrationTestHelper` validates the JSON against itself. Drift shows as a `git diff` on `9.json` because Room rewrites it on each KSP build. A real guard comes with the first 8->9 `runMigrationsAndValidate` test once an `8.json` exists. Only version 9 is exported; older migrations stay untestable.
- **`lintDebug` fails when run in the same Gradle call as the compile and test tasks** (`FileNotFoundException` on a KSP-generated `*_GeneratedInjector.java` in `lintAnalyzeDebugUnitTest` / `lintAnalyzeDebugAndroidTest`). Pre-existing: it fails identically on `master` `cf144e6`. Run `lintDebug` alone.
- ~~`assembleRelease` not run on the new config~~ Closed 2026-11-12: full `assembleRelease --rerun-tasks` passed in the main checkout (53 tasks, signed APK produced). Not installed or launched on the phone.
- **Worktree hygiene:** the worktree tool branches from the stub `origin/main` (reset to `master` each time), and Windows path length breaks `git worktree remove` after a Gradle build; delete the leftover folder with `rmdir /s /q "\\?\<path>"`.

## Refreshed — 2026-11-11 (session 27 — transactional Card History assign, commit `abd3b66` on `master`)

**Audit scope note**: touched `MainBinderDao`, `BinderRepository`, `SlotDetailScreen`, `BinderRepositoryTest`, new `AssignWithHistoryTransactionTest`. Unit tests **595/595** (fresh XMLs, `--rerun-tasks`, on `master`), `lintDebug`, `assembleDebug`, `compileDebugAndroidTestKotlin` pass; emulator `OK (30 tests)` (26 + 4 new, pinned `emulator-5554`). Rollback proven twice by mutation (without `@Transaction` the new test fails 1 of 4). Pipeline run (Planner, Coder, Tester, single Reviewer 91/100, ship). Not run on the phone.

### Closed
- **Assign is not transactional** (batch 2 carry-over): `MainBinderDao.assignDisplacing` does the history insert and the slot upsert in one `@Transaction`. Moved to [gaps-archive.md](gaps-archive.md).

### New, not fixed
- **Room schema export was blocked** (closed in session 28, see the section above). The "Kotlin/KSP mismatch" guess was wrong; it needed no bump.
- **P2: id-set / url-null slot now overwrites with no history row** (`assignDisplacing` receives `displaced = null`); master threw an NPE there. Probably unreachable (`withCard` writes id and url together). Untested and silent; pin with a test or a `Log.w`.
- **P3s:** read-then-write race remains (`getByPokemonId` runs outside the transaction and the stale copy is written back wholesale); next-position SQL duplicated in `MainBinderDao` and `SecondaryBinderDao` (drift risk); the real-SQL test does not assert the moved row's name or image URL (only the mock unit test does); the rollback test injects failure on UPDATE only; the `ScannerViewModel`/`QuickScanViewModel`/`SecondaryBinderViewModel`/`RestoreRepository` paths that call `insertAtEnd` remain non-transactional with their own writes.
- **Remaining `!!`:** `PublishRepository.kt:501-521` (six, snapshot diff). Not changed.

## Refreshed — 2026-11-11 (session 26 — grid layout, `security-crypto` dropped; commits `22ec8c5`, `ab85185` on `master`, pushed)

**Audit scope note**: touched `MainBinderScreen.kt`, `SecurityPrefsModule.kt`, `FakeSecurityPrefsModule.kt`, both backup XMLs, `build.gradle.kts`, `libs.versions.toml`; deleted `SecurityPrefsModuleTest.kt`. Unit tests **595/595** (fresh, `--rerun-tasks`; 607 minus the 12 deleted migration tests), `lintDebug` and `compileDebugAndroidTestKotlin` pass. No pipeline run (small removal; no reviewer pass). On the phone: Keystore migration confirmed (logcat `migrated 2 of 2` for both stores, only `*_v2.xml` left, no plaintext key prefixes in them, 4 keys visible in Settings).

### Closed
- **Drop `security-crypto`** (batch 3 checklist): done except the optional deletion of the old `_androidx_security_master_key_` Keystore entry (harmless, left). The "Downgrade then re-upgrade" P2 below still applies to the old APK only.

### New, not fixed
- **Pokédex grid is 12 columns** (BASE/REGIONAL span 3, ALTERNATE_FORM/MEGA/GMAX span 4). While searching, results are a flat list with mixed types, so rows can hold mixed tile sizes. No test covers the grid; 4-across tile look confirmed by Skyler on the phone 2026-11-12.
- **Published web snapshot** has no grid setting in this repo; whether it should mirror the 4/3 split is undecided.
- **Emulator run done 2026-11-11** (`pokedex_test` AVD, pinned `ANDROID_SERIAL=emulator-5554`, phone untouched): `testlog/test-results.log` says `OK (26 tests)`, 26 start + 26 pass codes, including `KeystoreSecretStoreInstrumentedTest` (first run against a real AndroidKeyStore) and `DatabaseModuleWiringTest`. Gradle still exits 1 with `Failed to receive the UTP test results`, the long-standing item 11 symptom: it is a UTP result-collection failure, not a test failure; read the verdict from `test-results.log`.
- **Not run:** drawer-section tap-through (Skyler said sections look okay 2026-11-11; "Back up now" confirmed 2026-11-12).

## Session 25 deferred on purpose (2026-11-06)

Not built, each with its reason: Korean / Traditional Chinese PTCG-database source and English image fallback and Japanese backup search (Skyler: not now / only on reported misses);
`accompanist-permissions` and `material` removal (need a device look); art-hash 20-45 band (needs a labelled calibration set); TCGdex data gaps (external); `claude_scan_bakeoff.py` (his Python lacks `anyio`).
Gemini key rotation: dropped by Skyler.

## Refreshed — 2026-11-06 (session 25, batch 3 — Keystore AES-GCM replaces `security-crypto`, branch `gaps-batch3-keystore`)

**Audit scope note**: new `di/KeystoreSecretStore.kt`; rewrote `SecurityPrefsModule.kt` (verified legacy migration, `createOrResetOnDecryptFailure` deleted); guard in
`SettingsRepository`; backup-exclude XMLs now list `settings_secrets_v2` / `publish_secrets_v2`. Unit tests **607/607** (fresh XMLs, `--rerun-tasks`); 3-lens Opus review 90 / 87 / 92
(min 87, ship) plus review-fix pass. **Not run on a device.** `KeystoreSecretStoreInstrumentedTest` now compiles (verified on `master` 2026-11-06 after the sandbox PKIX fix, see root `CLAUDE.md`) but has never run: the migration is unproven on real AndroidKeyStore until the emulator run and phone check below.

### Needs Skyler (all three done 2026-11-11)

1. Emulator run (compile already passes): `$env:ANDROID_SERIAL="emulator-5554"; .\gradlew.bat connectedDebugAndroidTest` (emulator only; AGP uninstalls the app from whatever device it targets). I can run this for you if you ask.
2. Phone: write down your 4 secrets (Gemini key, Anthropic key, GitHub PAT, Discord webhook) first. `installDebug`, open Settings and Publish, confirm all 4 are still there, look for `migrated 2 of 2` (or `migrated N of N`) in logcat tag `SecurityPrefs`, and confirm only the `*_v2.xml` files remain under `shared_prefs`. Press "Back up now" once.
3. Reply with the result. Only then run the "drop `security-crypto`" change.

### New, not fixed

- **Drop-`security-crypto` checklist (separate later change, gated on items 1-2 above):** remove `migrateLegacySecrets`, `LEGACY_RESERVED_PREFIX` and the ESP/MasterKey imports in `SecurityPrefsModule.kt`; delete `SecurityPrefsModuleTest.kt` (migration tests); drop the toml entry and `build.gradle.kts` dependency lines; remove the three legacy backup-exclude blocks (or keep for one release); fix the `FakeSecurityPrefsModule` doc comment; delete the legacy `settings_secrets` / `publish_secrets` files unconditionally; optionally delete the old `_androidx_security_master_key_` Keystore entry.
- **Downgrade then re-upgrade can lose a newer secret (P2).** Rollback by `adb install -r -d` of an older APK, re-entering keys in the old app, then upgrading again: stale v2 values still decrypt, so nothing is copied (new store wins) and the legacy file holding the newer keys is deleted. A rotated GitHub PAT would be lost. Do not downgrade after the migration without exporting secrets; no code fix planned.
- **Unreadable v2 values read as empty, never wipe (D8).** A backup restored to a new device (Keystore key absent) shows empty key fields; re-enter them. Restored `*_v2.xml` is excluded from backups, so this should only happen via adb or device-to-device copies of app data.
- **P3s:** a failed migration rollback removes a pre-existing but transiently unreadable v2 value instead of restoring a snapshot; `apply()` is synchronous and a transient encrypt failure silently drops a newly typed key (D13 accepted); `clear()` drops puts made earlier in the same editor (no caller uses it); the store implements `getAll`/`contains`/`remove`/`clear` that no production code calls (~20 lines); two log-leak tests are near-tautological (all log messages are fixed templates); the instrumented test's `@Before` file delete can never find a file and `ivIsTwelveBytes` checks total blob size; a possible `deleteEntry` throw in its `@After` on a missing alias (unconfirmed).
- **Main-thread Keystore reads:** the settings and publish flows decrypt on Main (~1 ms per read, key now cached after first successful load).

## Refreshed — 2026-11-06 (session 25, batch 2 — structure cleanup, branch `gaps-batch2-structure`)

**Audit scope note**: touched `DatabaseBackupManager`, `DatabaseModule`, `BinderRepository`, `QuickScanViewModel`, `ScannerViewModel` + tests; read the five `fillBlankImage` DAOs,
`ConnectingArtRepository` and its consumers, and the four section screens. Unit tests **581/581** (fresh XMLs, `--rerun-tasks`); single-reviewer score 94 (ship).
**Not run on the phone.** `compileDebugAndroidTestKotlin` now passes on `master` (2026-11-06), so the `DatabaseBackupManagerInstrumentedTest` edits compile; `DatabaseModuleWiringTest` and the instrumented suite have not been run.

### New, not fixed

- **Weak assertions in `BinderRepositoryTest`:** `coVerify(exactly = 0) { secondaryDao.insert(any()) }` in the empty-slot test can never fail (`assignWithHistory` never calls `insert`); the locked-slot test only catches guard removal because its fixture is both occupied and locked. The `insertAtEnd`-removal mutation was proven by the Coder only, not re-run by the Tester.
- **Closed as "not a gap" with evidence:** the five `fillBlankImage` pairs differ in table/column names and Room needs literal SQL per table; `ConnectingArtRepository` has non-ViewModel consumers (`BackupExporter`, `PublishRepository`, `RestoreRepository`) and does real work; the duplicate-screen pairs differ on 244 of ~366 and 431 of ~580 lines.

## Refreshed — 2026-11-06 (session 25, batch 1 — scanner behaviour and tests, branch `worktree-gaps-batch1-scanner`)

**Audit scope note**: touched `CardScanShared`, `CardScanProvider`, `ClaudeCardScanner`, `GeminiCardScanner`, `ScannerScreen`,
`ScannerViewModel`, `TcgcsvJapanSource`, `SyncCardImagesUseCase` + tests. Unit tests **579/579** (fresh XMLs, `--rerun-tasks`, recounted by
the orchestrator); 3-lens Opus review 90 / 90 / 91 (min 90, ship). **Not run on the phone.** `lintDebug` and
`connectedDebugAndroidTest` were blocked by sandbox PKIX; fixed later (Windows-ROOT trust store), `lintDebug` now passes, the emulator run is still open. Fixed entries moved to
[gaps-archive.md](gaps-archive.md) ("Closed 2026-11-06"). The Gemini key rotation gap is dropped by Skyler's decision.

### New, not fixed

- **Collect `attempt provider= n= ms= outcome=` lines (logcat tag `ScannerMatch`) before retuning `SCAN_CALL_TIMEOUT_MS`.** `ms=` stops at the
  response headers (`logAttempt("ok")` fires before the body is read), so a timeout during the body read still logs `outcome=ok`.
- **Gemini bad-key HTTP code is unverified.** 401/403 are classed as invalid key; Gemini may return 400. After one wrong key on the phone, read
  `outcome=` in logcat and add 400 only if it appears. A 403 can also mean "API not enabled" or a restricted key (the key itself fine); only the 429 body is logged.
- **Item 11 stays unexplained.** `connectedDebugAndroidTest` exits 1 with `OK (22 tests)`; the sandbox emulator run died at dependency download (PKIX, now fixable) before any test. Run
  `$env:ANDROID_SERIAL="emulator-5554"; .\gradlew.bat connectedDebugAndroidTest` in your own PowerShell, with the phone unplugged or the serial pinned.
- **Share-sheet rotation gate** (`SettingsScreen.kt` ~80-87) is tested only at ViewModel level; the real test is a Compose + Hilt `ActivityScenario.recreate()` emulator test. Deferred.
- **P3s from review:** the sync rotation keys off the day number, so syncs exactly 2 days apart with 800 blank ids and limit 400 check the same half each time
  (random start would fix it); a failed 429 body peek is swallowed without a log line; the log seam runs before `response.close()` with no try/finally
  (leaks only if a test forgets to stub the seam); `ToStringGuard` relies on declared field order and handles String/Boolean fields only; the key-header names
  appear in three places (`CardScanShared.kt`, each scanner); `InvalidApiKeyException` still takes a provider `String`.
- **Mutation evidence is thin for 8 of the 14 table rows** (the Coder's `mutation-batch1.log` records only "24 failed"; `mutation-batch2.log` was never written). The Tester re-proved 6; code-read confirms the rest.

## Refreshed — 2026-11-04 (session 24 — redundancy audit)

**Audit scope note**: `ponytail-audit` read of deps, repositories, DAOs, interfaces; screens not opened. Tests 552/552. Archived history (sessions <=20) is in [gaps-archive.md](gaps-archive.md) and was not re-audited.

### New, not fixed

- **Deferred cuts** (each needs a device check): `accompanist-permissions` (loses the resume re-check if replaced naively), `material` lib (theme parent, dark-mode flash risk). The other session 24 cuts were resolved in session 25 batch 2 (see archive).

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
  billed. Per-attempt `ms=` logging now exists (session 25); collect it on the phone, then retune.
- **Encrypted-prefs recovery superseded (session 25 batch 3):** `createOrResetOnDecryptFailure` was deleted; the new Keystore store reads an unreadable value as
  empty and never deletes anything (see the batch 3 section). The backup exclusions only apply to backups made after the hygiene build: press "Back up now" once after installing.
- **`ScannerScreen.kt` split (2026-11-04, pure move, no logic change):** geometry/detection -> `ScannerGeometry.kt`, camera focus/diagnostics -> `ScannerCamera.kt`; the file is 664 lines, down from 1,034. `CameraPreview` (~200 lines) and the nine-state `ScannerScreen` stay together and still have no camera tests.

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
- **`MAX_AUTO_IDS` ceiling:** the auto sync checks 400 blank ids per run; since session 25 the window rotates daily, so later ids are
  reached over several days (manual sync stays exhaustive). See the session 25 P3 on 2-day gaps.
- **English has no backup image resolver.** `JapanTcgcsvResolver` returns null for non-`ja`, so an English card TCGdex
  lacks an image for gets no sync fallback (search-time TCGCSV English merge can still patch it). Skyler
  (2026-10-03): "working well for now"; build one only if he reports misses.
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
- **Gemini quota cause unconfirmed** (free-tier daily vs per-minute); the 429 body is now logged (redacted, 300 chars), so read it after the next 429.
- **TCGdex call budget:** worst case on a total miss is 11 requests (zh) / 8 (others), above the spec's 7 / 5. (After
  part 2 the ported name+number / dex+number steps raised the test's bound from 7 to 12.)
- **Claude's English name can be wrong** (read Klawf as "Crabominable"); `printedName` + number + total saved
  the match.
- **Saved card Language is not pre-filled from the detected language** (out of scope; touches 4+ assign flows).
- **`scripts/claude_scan_bakeoff.py` never run** (Skyler's Python lacks `anyio`); prices in it are from the
  claude-api skill's cached table (2026-06-04) and the per-scan cost (~$0.002 Haiku / ~$0.006 Sonnet) is an
  estimate, not measured.

## Parked — 2026-09-30 (session 20, from the security/dependency pass) — circle back after the current fixes are test-green

Read-only pass (manifest + `libs.versions.toml`); no CVE database lookups were run.

- **`security-crypto` is deprecated; replaced by `KeystoreSecretStore` in session 25 batch 3 and dropped from the build in session 26 (`ab85185`).**
  Closed; the Jetpack releases-page evidence (all APIs deprecated in `1.1.0-alpha07`, no further releases) is why it was dropped.
- **Dependency bumps merged (session 25 batch 4), phone tap-through still owed.** `coil` 2.7.0, `core-ktx` 1.17.0, `lifecycle` 2.10.0, `navigation-compose` 2.9.8, `room` 2.8.5; one commit per library on `master`,
  so a failing section reverts one commit. Verified on `master` 2026-11-06: `testDebugUnitTest --rerun-tasks` 607/607, `lintDebug`, `compileDebugAndroidTestKotlin` pass. Versions are capped by the toolchain
  (AGP 8.9.1, compileSdk 36, Kotlin 2.1.20): `lifecycle` 2.11 and `navigation-compose` 2.10 need AGP 9.2+ (Compose compileSdk 37), `core-ktx` 1.18+ needs compileSdk 36.1; revisit when AGP is upgraded.
  **Do on the phone:** tap through every drawer section (navigation and Room paths have no UI tests). Watch-list: Room Kotlin codegen is on by default with KSP since 2.7.0 (compiled fine; fallback is ksp arg
  `room.generateKotlin=false`), Room 2.8.x suspending queries throw after the DB is closed, navigation 2.8.1 stopped deep-linking into destinations without explicit deep links (the app only navigates by string route).
  Left alone: `accompanist-permissions` 0.34.0 (deferred with the permission re-check), `okhttp` 4.12.0 (current for 4.x).
