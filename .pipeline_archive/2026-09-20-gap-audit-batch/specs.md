# Spec: Session 16 Gap-Audit Batch (5 independent no-device-needed fixes)

## TL;DR

Five small, independent, already-diagnosed fixes from the standing gap register (`gaps.md`), none
needing a live device: (1) move the Gemini API key from plaintext DataStore to the
`EncryptedSharedPreferences` pattern `PublishSettingsRepository` already uses, with a one-time
migration preserving Skyler's existing key; (2) exclude `backup_import_state.xml` from Android Auto
Backup in both backup-rules XML files; (3) close a confidence-gating edge case where a failed image
download can falsely grant `HASH_MARGIN` high confidence; (4) add the missing test for
`GeminiCardScanner`'s no-retry 4xx path; (5) comment-only doc of why `PendingImportError` uses raw
`SharedPreferences` instead of DataStore. No dependency/version changes, no scanner geometry changes.

## Problem

1. Gemini API key sits in plaintext while an identical-threat-model secret (GitHub PAT) is already
   encrypted — inconsistent, avoidable exposure risk.
2. A stale one-shot pending-import-error message could ride a cloud backup/device-transfer onto a
   fresh install and show a confusing Toast there.
3. A single failed candidate-image download can inflate `HashMatchResult.margin` past the
   high-confidence threshold, precisely in the `parsedNumber == null` case that path exists to help.
4. `GeminiCardScanner`'s 400/401/403/404 no-retry path is correct but untested — a future change
   could silently reintroduce retries there with nothing to catch it.
5. A correct architectural deviation (raw `SharedPreferences`, not DataStore) is undocumented at
   the point it diverges, unlike a precedented sibling deviation that already is.

## Proposal

### Fix 1 — Encrypt the Gemini API key + migrate the existing plaintext value

**File:** `app/src/main/java/com/skyler/pokedexbinder/repository/SettingsRepository.kt` (edit only)

Current: `AppSettings.geminiApiKey`, `Keys.GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")`,
`settings: Flow<AppSettings>` maps it from `context.dataStore`, `getGeminiApiKey()`/
`setGeminiApiKey()` read/write the same DataStore key.

Reference pattern, replicate exactly: `PublishSettingsRepository.kt`'s lazy `securePrefs`
(`EncryptedSharedPreferences.create(context, "publish_secrets",
MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
PrefKeyEncryptionScheme.AES256_SIV, PrefValueEncryptionScheme.AES256_GCM)`), read synchronously
inside `config`'s `Flow.map` (no dispatcher wrapping — established precedent, reuse it), writes
wrapped in `withContext(Dispatchers.IO)`.

Change:
- Add `private val securePrefs: SharedPreferences by lazy { ... }` using a **new** file name
  `"settings_secrets"` (not `"publish_secrets"` — keep the two repositories decoupled) with
  `SecureKeys.GEMINI_API_KEY = "gemini_api_key"`.
- `settings` Flow's map block: call `migrateGeminiKeyIfNeeded()` then read `geminiApiKey =
  securePrefs.getString(SecureKeys.GEMINI_API_KEY, "") ?: ""` instead of the DataStore prefs map.
- `getGeminiApiKey()`/`setGeminiApiKey()` — **signatures unchanged**. Bodies: call
  `migrateGeminiKeyIfNeeded()` then read/write `securePrefs` via `withContext(Dispatchers.IO)`.
- New private suspend `migrateGeminiKeyIfNeeded()`, guarded to run once per process:
  ```kotlin
  private val migrationMutex = Mutex()
  @Volatile private var geminiKeyMigrated = false

  private suspend fun migrateGeminiKeyIfNeeded() {
      if (geminiKeyMigrated) return
      migrationMutex.withLock {
          if (geminiKeyMigrated) return@withLock
          if (shouldMigrateGeminiKey(securePrefs.getString(SecureKeys.GEMINI_API_KEY, null))) {
              val legacy = context.dataStore.data.map { it[Keys.GEMINI_API_KEY] ?: "" }.first()
              if (legacy.isNotEmpty()) {
                  withContext(Dispatchers.IO) {
                      securePrefs.edit().putString(SecureKeys.GEMINI_API_KEY, legacy).apply()
                  }
                  context.dataStore.edit { it.remove(Keys.GEMINI_API_KEY) }
              }
          }
          geminiKeyMigrated = true
      }
  }
  ```
  Split the branch condition into a pure, independently-testable function (no Android types) since
  this codebase has no Robolectric/real-Keystore test setup (see Open Questions):
  `internal fun shouldMigrateGeminiKey(encryptedValue: String?): Boolean =
  encryptedValue.isNullOrEmpty()`. It only gates whether to check the legacy value at all; the
  actual copy still requires `legacy.isNotEmpty()`, checked separately.
- **Order matters**: read legacy → write encrypted → THEN clear plaintext. Never clear first (a
  crash mid-migration would silently lose the key).

**Call sites, verified, no signature changes needed:** `ui/scanner/ScannerViewModel.kt:70`
(`settingsRepository.getGeminiApiKey()`), `ui/settings/SettingsViewModel.kt:34`
(`setGeminiApiKey(key)`), `ui/settings/SettingsScreen.kt:33,155` (`settings.geminiApiKey`,
`viewModel.setGeminiApiKey(it)`).

**Edge cases:** encrypted store already populated → zero DataStore access, zero writes. Plaintext
key already empty (fresh install) → guard skips write/clear, flag still set. Concurrent callers at
app start → `Mutex`+`@Volatile` serializes; second caller fast-paths. Clearing the plaintext key
triggers one extra `dataStore.data`/`settings` emission — expected, terminates (next pass's
`legacy` is empty), not a loop.

### Fix 2 — Exclude `backup_import_state.xml` from Auto Backup

**Files:** `app/src/main/res/xml/data_extraction_rules.xml`,
`app/src/main/res/xml/full_backup_content.xml` (both edit only)

Backing file: `BackupImporter.kt`'s `PendingImportError.PREFS_NAME = "backup_import_state"` →
`shared_prefs/backup_import_state.xml`.

Add to **both** `<cloud-backup>` and `<device-transfer>` blocks in `data_extraction_rules.xml`,
alongside the existing `exports/` exclusion: `<exclude domain="sharedpref"
path="backup_import_state.xml" />`. Add the same line to `full_backup_content.xml`'s single
exclusion block. Match each file's existing comment style; a short inline note ("one-shot
pending-error message, must never survive a device transfer") is enough — don't duplicate
`BackupImporter.kt`'s own docstring. Pure additive exclusion, no code path touches it, no edge cases.

### Fix 3 — Gate `HASH_MARGIN` confidence on a real (non-sentinel) winning distance

**Files:** `domain/SmartThresholdUseCase.kt` (`evaluate()` only),
`test/.../domain/SmartThresholdUseCaseTest.kt` (add tests)

Do **not** touch `PerceptualHasher.findBestMatch()` — its `Int.MAX_VALUE` download-failure
fallback is correct and unchanged; only the consuming condition changes.

Current (`evaluate()`, ~lines 61-63):
```kotlin
if (parsedNumber == null && hashMatch != null &&
    hashMatch.margin >= HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD
) {
```
New:
```kotlin
if (parsedNumber == null && hashMatch != null &&
    hashMatch.distance != Int.MAX_VALUE &&
    hashMatch.margin >= HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD
) {
```
Add a one-line comment: `Int.MAX_VALUE` means the *winning* candidate's own image download
failed (`findBestMatch()`'s documented fallback) — the margin is an artifact of a missing
comparison, not a strong visual match, even though the raw arithmetic can clear the threshold.

Do **not** apply the same guard to `SINGLE_CANDIDATE_HASH` (`hashMatch.distance <=
HASH_SINGLE_CANDIDATE_MAX_DISTANCE`, ~line 42) — `Int.MAX_VALUE` can never satisfy a `<=` ceiling,
already structurally immune, out of scope.

New/updated tests, reusing the file's existing `hashMatch(card, distance, margin)` helper:
1. **New, regression for this bug**: `hashMatch(xy1, distance = Int.MAX_VALUE, margin = 999)`,
   `parsedNumber = null`, 2+ candidates → `isHighConfidence == false`, `matchPath == NONE`.
2. **No-regression check**: the existing "hash margin wins when number is null and margin clears
   the threshold" test (real `distance = 2`) must still pass unmodified after the edit.

### Fix 4 — Add a dedicated test for the 400/401/403/404 no-retry path

**File:** `test/.../domain/GeminiCardScannerTest.kt` (add one test, no production change)

`GeminiCardScanner.kt`'s `executeWithRetry()` line 125 (`if (!response.isSuccessful) throw
IOException("Gemini error: $code")` where `code = response.code`) is confirmed correct and untouched.
Representative code: **404**. New test, same `MockWebServer`/`runTest` conventions as every other
test in this file, placed near `scan throws IOException on 500`:
```kotlin
@Test
fun `scan throws immediately on 404 with no retry`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    var caught: Exception? = null
    try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
    assert(caught is java.io.IOException) { "Expected IOException but got $caught" }
    assertEquals(1, server.requestCount)
}
```
No new imports needed — all symbols already imported in this file.

### Fix 5 — Document the `SharedPreferences`-not-DataStore deviation (comment only)

**File:** `data/local/backup/BackupImporter.kt` (comment only, no behavior change)

Add, immediately above `private const val PREFS_NAME = "backup_import_state"` inside `object
PendingImportError` (which already has a class-level KDoc on *why this must survive a restart* —
this adds *why raw `SharedPreferences` specifically*, which the KDoc doesn't state):
```kotlin
// Deliberately NOT this codebase's usual DataStore convention (SettingsRepository,
// PublishSettingsRepository): DataStore has no synchronous write API, and persist() below must
// complete before the imminent Process.killProcess() call in BackupImporter.applyDb() — an async
// DataStore write would race the kill and could be lost. See persist()'s commit=true below.
```
Do not touch `persist()`/`consume()` logic.

## User stories

- As Skyler, my Gemini API key is encrypted at rest, consistent with my GitHub PAT, so neither
  equally-sensitive credential leaks differently under a rooted-device or insecure-backup scenario.
- As Skyler upgrading to this build, my already-configured Gemini API key keeps working with no
  re-entry — the migration is invisible.
- As Skyler, a stale pending-import-error message never resurfaces on a fresh install after a
  cloud restore or device transfer.
- As Skyler, a scan only lands on the hash-margin confidence path because of a real visual
  comparison, never because one candidate's image failed to download.
- As a future developer touching `GeminiCardScanner`, an accidental "now retries on 404"
  regression is caught by a test, not discovered live.

## Acceptance criteria

**Fix 1**
- [ ] `getGeminiApiKey()`/`setGeminiApiKey()` signatures unchanged; all 3 call sites compile with
      no edits.
- [ ] `AppSettings.geminiApiKey` now sourced from `EncryptedSharedPreferences`, not plaintext.
- [ ] First read after ship: empty encrypted store + non-empty plaintext key → value copied,
      plaintext key removed.
- [ ] Second+ reads: no DataStore access for the check, no re-write.
- [ ] `shouldMigrateGeminiKey()` unit-tested: `null`→true, `""`→true, non-empty→false.
- [ ] `assembleDebug` and `testDebugUnitTest --rerun-tasks` clean.

**Fix 2**
- [ ] `data_extraction_rules.xml` excludes `backup_import_state.xml` (`domain="sharedpref"`) in
      both `<cloud-backup>` and `<device-transfer>`.
- [ ] `full_backup_content.xml` excludes the same path. No other exclusion altered in either file.

**Fix 3**
- [ ] `HASH_MARGIN` branch requires `hashMatch.distance != Int.MAX_VALUE` in addition to existing
      conditions.
- [ ] New test: `Int.MAX_VALUE` distance + wide margin → not high confidence, path `NONE`.
- [ ] Existing test: real distance + wide margin → still high confidence, path `HASH_MARGIN`.
- [ ] `PerceptualHasher.kt` diff is zero lines. All existing `SmartThresholdUseCaseTest.kt` cases pass.

**Fix 4**
- [ ] New test asserts `IOException` on a 404 response with `server.requestCount == 1`.
- [ ] `GeminiCardScanner.kt` has zero production-code changes. Existing tests still pass.

**Fix 5**
- [ ] Comment added directly above `PREFS_NAME` explaining the deviation and the process-kill race.
- [ ] Diff in `BackupImporter.kt` is comment-only.

**All 5**
- [ ] Fresh `testDebugUnitTest --rerun-tasks`: baseline 305 (per `handoff.md`) + at least 3 new
      (Fix 1's pure-function test, Fix 3's new test, Fix 4's new test), 0 failures.
- [ ] `assembleDebug --rerun-tasks` clean.
- [ ] No dependency version in `gradle/libs.versions.toml` changed.
- [ ] No file under `ui/scanner/ScannerScreen.kt`, `CardFrameOverlay.kt`, or `ImageProxyExt.kt`'s
      crop/rotation/geometry logic touched.

## Out of scope

- Camera/scanner geometry, focus, or crop-rect logic (already fixed this session, pending
  Skyler's live-device test).
- Any dependency/version bump (audited this session, no CVEs; see `project-overview.md`'s "Native
  Dependencies" history of painful speculative bumps).
- `PublishSettingsRepository`'s own pattern (synchronous `securePrefs` read inside `Flow.map`,
  no IO-dispatcher wrapping) — reused as-is per Fix 1, not itself audited or changed.
- Retuning `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD`/`HASH_SINGLE_CANDIDATE_MAX_DISTANCE` or any
  other scanner constant — separate, live-device-gated work.
- Republishing `binder.json`, archiving `.pipeline/` from the prior scanner session — unrelated.
- A full `SettingsRepositoryTest.kt` exercising the real `EncryptedSharedPreferences` path — see
  Open Questions.

## Dependencies

- Fix 1 depends on `androidx.security:security-crypto:1.1.0-alpha06`, already in
  `gradle/libs.versions.toml` (`securityCrypto`) and already used by `PublishSettingsRepository.kt`
  — no `build.gradle.kts` change.
- All 5 fixes are mutually independent (no shared code path, no ordering constraint); bundled as
  one pipeline run per Skyler's instruction.
- Fix 3's tests depend on the existing `hashMatch()` helper in `SmartThresholdUseCaseTest.kt`.

## Open questions

None blocking. One judgment call, flagged for visibility: Fix 1 gets no full-repository test
(only the pure `shouldMigrateGeminiKey()` helper) because this codebase has zero unit-test
precedent for any `EncryptedSharedPreferences`-backed repository — `PublishSettingsRepository`
(identical pattern) has no test file either, and `BackupImporterTest.kt`'s own header states "no
Robolectric in this project," which real Keystore-backed testing would require. Introducing
Robolectric to close this is a separate, larger decision outside this batch's "no new
dependencies" constraint — Skyler's call if he wants it pursued later.

## Risks and mitigations

- **Migration silently drops Skyler's real API key.** Mitigation: read-then-write-then-clear
  ordering; `shouldMigrateGeminiKey()` isolated and unit-tested; acceptance criteria require
  tracing all 3 call sites unchanged and manually checking both "fresh install" and "upgrade,
  key already set" cases before calling this fix done.
- **Mutex/@Volatile guard subtly wrong under concurrency.** Mitigation: it only serializes the
  migration decision+copy, not steady-state reads/writes — those go straight to `securePrefs`
  (`SharedPreferences` is already thread-safe). Keep the mutex scope exactly as drafted.
- **Fix 3's edit leaks into `SINGLE_CANDIDATE_HASH`.** Mitigation: acceptance criteria require a
  zero-diff on `PerceptualHasher.kt` and scope the new guard to the `HASH_MARGIN` block only.
- **Auto Backup XML exclusion typo'd silently no-ops.** Mitigation: `domain="sharedpref"` with a
  bare filename matches the existing `domain="file" path="exports/"` syntax style exactly, just a
  different domain — no new syntax invented.

## Smallest shippable increment

All 5 together — each is S-sized alone (single file or a two-file XML pair, no cross-fix
dependency), so no partial split saves meaningful effort; bundled per Skyler's own framing ("fix
everything that doesn't need me to run"). If forced to cut, Fix 1 (security, real data at risk)
and Fix 3 (correctness, active scanner bug) matter most; Fix 2/4/5 are low-severity or
zero-behavior-risk and could ship in a follow-up without loss.

**Suggested order (dependency-clean):** Fix 5 (XS, comment-only) → Fix 2 (XS, XML-only) → Fix 4
(S, test-only) → Fix 3 (S, one guarded condition + tests) → Fix 1 (M, real migration logic,
highest risk — do last so it gets full attention and the freshest test run confirms nothing
upstream broke).

## Friction Notes

- Serena's Kotlin language server failed to initialize in this project (`Error extracting
  archive`) — `find_symbol`/`find_referencing_symbols` unusable for this Kotlin/Android repo this
  session; fell back to `Grep`+`Read`, which worked fine at this codebase size. Worth fixing at
  the environment level if future planning/coding stages here hit the same wall.
- `gaps.md` is 1000+ lines and got truncated mid-read (33KB against a 25KB tool cap). The relevant
  recent sections were all on the first page for this task, but a future audit needing older
  history should expect to page through with `offset`.
