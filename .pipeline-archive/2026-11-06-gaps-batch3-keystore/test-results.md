# Test results - batch 3 KeystoreSecretStore

**STATUS: PASS.** 604 tests, 0 failures, 0 errors, 0 skipped (fresh XMLs). Stage 4 (Debugger) not needed.
Raw run: `.pipeline/evidence/test-results.log` (final run, after my added tests). Mutation FAILED lines: `.pipeline/evidence/mutations-tester.log`.

## 1. Fresh full run
`rm -rf app/build/test-results/testDebugUnitTest`, then `./run_build.bat testDebugUnitTest --rerun-tasks`:
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 2m 16s
```
- First run (Coder's code as committed): XML sum over 48 suites = **596 tests, 0 skipped, 0 failures, 0 errors**. Coder's 596 claim confirmed.
- Final run (after my +8 tests): `tests=604 skipped=0 failures=0 errors=0`. di package: 28 testcases.
- No Gradle daemon left running (`--stop`: "No Gradle daemons are running."). Tree clean after commit.

## 2. Independent mutation re-proof (8 of 16 spec mutations)
Each: apply, run only the owning class (`--tests com.skyler.pokedexbinder.di.<Class>`), capture, `git checkout -- <file>`. Tree verified clean afterwards (`git status --short` empty).

| Mutation | Security property | Result (verbatim FAILED lines) |
|---|---|---|
| M1 drop `updateAAD` (2 sites) | AAD binding | `KeystoreSecretStoreTest > ciphertext copied to another key returns default FAILED`; `... ciphertext moved to another store name returns default FAILED` |
| M2 AAD = key only | cross-file swap | `KeystoreSecretStoreTest > ciphertext moved to another store name returns default FAILED` |
| M3 fixed zero IV | IV reuse | `KeystoreSecretStoreTest > same value twice gives different ciphertext and 12 byte IVs FAILED` |
| M4 narrow catch to AEADBadTag | tamper / short / bad Base64 / transient key | `... transient key failure returns default, keeps raw, recovers FAILED`; `... getAll decrypts good entries and omits a bad one, contains stays raw presence FAILED`; `... flipped byte, non base64 and short blob return default without throwing FAILED` |
| M5 drop read-back verify | verify before delete | `SecurityPrefsModuleTest > read-back mismatch serves old and rolls back copied keys FAILED` |
| M6 drop rollback | failure rollback | `SecurityPrefsModuleTest > read-back mismatch serves old and rolls back copied keys FAILED` |
| M7 drop D11 filter | new-store-wins | `SecurityPrefsModuleTest > newer value in new store beats older legacy value FAILED` |
| M8 delete in openOld catch | nothing deleted on open failure | `SecurityPrefsModuleTest > openOld failure serves new store and deletes nothing FAILED` |

All 8 RED, as the Coder claimed.

## 3. Adversarial review - findings
No production bug found. Verified by reading `KeystoreSecretStore.kt` and `SecurityPrefsModule.kt`:

| Area | Verdict |
|---|---|
| getString default when missing/undecryptable | OK. Absent returns default with no key call (tested). Undecryptable logs and returns default, raw kept. |
| Empty string values | OK, previously untested. Blob = 12+16 bytes, passes `size > 12`. `""` not mistaken for null. Now tested. |
| Very long values (200k chars), unicode | OK. Now tested. |
| Truncated blob (<=12, 20 bytes, `""`) | OK: IAE or AEADBadTag, both caught, default returned, raw kept. Now tested. |
| Base64 padding/decode errors | `Base64.getDecoder()` is strict, throws IAE, caught. Tested ("!!not base64!!"). |
| contains / getAll / remove / clear / putString(null) | OK. clear-then-put keeps only the new value (now tested). |
| apply vs commit | Both call the same `write()` which does a synchronous raw `commit()`. So `apply()` does disk I/O on the caller thread and, unlike real `apply()`, returns after the write. Safe (repos call it from coroutines). Not a defect, but not asynchronous. |
| Atomicity | Encrypt-all-first then one raw editor. Failure writes nothing (tested). |
| Thread safety | Fresh Cipher per op; key creation under global lock; SharedPreferencesImpl thread-safe. A single Editor instance is not thread-safe (same as the platform contract). |
| Keystore failure paths | `KeyPermanentlyInvalidatedException`/`UnrecoverableKeyException`/`InvalidKeyException` are GSE: caught. Keystore ProviderException caught. Alias-exists-but-unloadable throws and never regenerates. Residual: a non-listed RuntimeException from `key()` (e.g. IllegalStateException) would propagate out of `getString` on Main. Spec-accepted narrow catch, so LOW. |
| Process death between copy and delete | OK. New test simulates death with an `Error` from `deleteOld` (bypasses the rollback catch): copy survives; the next launch logs `migrated 0 of 2`, deletes once, serves the new store. |
| Legacy open/getAll failure | OK. `openOld` throws: new store served, nothing deleted (M8). `old.getAll()` throws SecurityException: old served, nothing deleted, new empty (now tested). |
| Secret leakage | OK. All log sites emit class simpleName or counts. Exception messages are constants (`"unexpected IV size"`, `"blob too short"`, `"non-String legacy value"`, `"commit failed"`, `"read-back mismatch"`, keystore constant). Tests assert the plaintext never appears in logged messages or migration logs. |
| Backup exclusions | OK. `settings_secrets_v2.xml` and `publish_secrets_v2.xml` appear in `full_backup_content.xml` and in both `cloud-backup` and `device-transfer` of `data_extraction_rules.xml` (domain `sharedpref`, exact file names). Legacy excludes kept. |
| Repo call patterns | Repos only use `getString(k, def)` and `edit().putString(k, v).apply()` / `.commit()` (SettingsRepository L89/146/156, PublishSettingsRepository L76/80). New test exercises exactly these with a SunJCE key, including overwrite and empty-string clearing. |
| Minor | A pre-existing wrong-typed raw entry (non-String) would throw ClassCastException from `raw.getString`. Cannot occur through this store. Ignored. |

## 4. Instrumented test (read only, not compiled or run)
`KeystoreSecretStoreInstrumentedTest.kt` - no API misuse found:
- Uses `androidKeystoreAesKey(alias)` (same module, `internal` visible to androidTest). KeyGenParameterSpec is built inside it: GCM, NoPadding, 256-bit, default randomized encryption, so the no-IV `cipher.init(ENCRYPT_MODE, key)` is the correct form for AndroidKeyStore.
- Alias cleanup: `@After` calls `deleteEntry(unique test alias)` and deletes the prefs file; `@Before` deletes prefs. Never references the master-key alias or `SECRETS_KEY_ALIAS`.
- Dependencies: `androidTestImplementation(libs.junit.ext)` (brings `androidx.test.core` / `ApplicationProvider` and `AndroidJUnit4`).
- Size assertion 12+6+16=34 is right for "secret" (6 chars).
- Caveats: `ivIsTwelveBytes` asserts total blob size, not the IV bytes directly (fine). Nothing exercises a real ProviderException path. Compile and run remain Skyler's.

## Tests added (commit `73398c4`)
- `KeystoreSecretStoreTest` (+5): empty/unicode/200k values; blob of 12 bytes, 20 bytes, `""`; clear-then-put; repo call patterns incl. overwrite and empty clear; logged exceptions carry no plaintext.
- `SecurityPrefsModuleTest` (+3): simulated process death between copy and delete; legacy `getAll` throws; empty legacy value migrates and logs carry no secret.

## Coverage gaps remaining
- Real AndroidKeyStore behaviour (no-IV encrypt, ProviderException subtypes, key invalidation): untestable on JVM; instrumented test unverified (PKIX).
- `lintDebug` not run (sandbox PKIX).
- Non-listed RuntimeException from the key lambda is not caught (see review).

## Friction Notes
- A foreground `sleep` loop in Bash got auto-backgrounded after 180s; the completion notification arrives anyway, so no need to poll.
- Python mutation harness (apply, run owning class, FAILED grep, `git checkout` in finally) ran 8 mutations unattended; handles CRLF sources via a `\r\n` check.
