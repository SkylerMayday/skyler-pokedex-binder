# Review - Lens 2: security + reliability (adversarial)

**Scope:** `git diff master...HEAD` on `gaps-batch3-keystore` (4 commits, 8 files). I read `KeystoreSecretStore.kt`, `SecurityPrefsModule.kt`, both backup XMLs, the instrumented test, the call sites in `SettingsRepository`/`PublishSettingsRepository`, the manifest backup attributes, minSdk, the mutation logs and the JUnit XMLs. First pass for this lens (no earlier `review-security.md` exists).

**Verdict: SHIP.** Score **87/100**. There are no P0 or P1 findings. One P2 (a data-loss path after a downgrade and re-upgrade, which the spec's own rollback plan leads into) should be fixed in the rollback note or tracked in gaps.md. It does not block the merge.

## Intent audit (security-relevant spec items)
| Item | Status | Evidence |
|---|---|---|
| D3/D4 IV from the provider, 12 bytes checked | DONE | `cipher.init(Cipher.ENCRYPT_MODE, key())` / `require(iv.size == GCM_IV_BYTES)` |
| GCM tag 128 | DONE | `GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES)` with `GCM_TAG_BITS = 128` |
| D5 AAD binding | DONE | `"$storeName/$k".toByteArray(Charsets.UTF_8)` on both sides. Store names are fixed constants with no `/`, so the AAD cannot be ambiguous |
| D6 lock + never regenerate | DONE | `if (ks.containsAlias(alias)) throw KeyStoreException(...)` inside `synchronized(keyCreationLock)` |
| D7 decrypt failure keeps the raw entry | DONE | `decryptOrNull` logs and returns null. No `remove` on any read path |
| D9/D10/D11 migration | DONE | `migrateLegacySecrets` matches steps 1-7 |
| D13 atomic editor | DONE | `puts.mapValues { encrypt }` runs before `raw.edit()` |
| Backup excludes (3 lists) | DONE | `settings_secrets_v2.xml` / `publish_secrets_v2.xml` are in `full_backup_content.xml` and in both `<cloud-backup>` and `<device-transfer>`. The legacy excludes are still there. The manifest wires both files (`android:dataExtractionRules`, `android:fullBackupContent`) |
| Logs carry the class name and counts only | DONE | Every log site uses `::class.java.simpleName` or a count. Exception messages are constants |

No scope creep.

## Crypto audit: clean
- **Key spec.** `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, 256-bit. `setRandomizedEncryptionRequired` keeps its default of true, which is why there is no caller IV and why IV reuse is impossible on the device. No user-auth flag, so there is no `KeyPermanentlyInvalidatedException` on a biometric change. No StrongBox. Both match the threat model.
- **IV.** A random 96-bit IV per encryption under one key. The birthday bound (about 2^32 writes) is irrelevant for 4 secrets.
- **Truncation and tamper.** `require(blob.size > GCM_IV_BYTES)` plus the GCM tag check. A short blob throws IAE or AEADBadTag, and both are caught.
- **API levels (minSdk 26).** `java.util.Base64` needs 26, `KeyGenParameterSpec` and AndroidKeyStore AES-GCM need 23, `deleteSharedPreferences` needs 24. All are compatible.
- **Alias exists but cannot load.** `getKey` throwing (UnrecoverableKeyException) propagates, a non-SecretKey entry hits `containsAlias`, and neither regenerates. Process death during `generateKey` is atomic in keystore, so there is no half key.
- **Plaintext residue.** Plaintext never reaches disk. The legacy file holds ESP ciphertext and is removed by `deleteSharedPreferences` only after read-back. `SecretEditor.puts` holds plaintext only in memory.

## Findings

### P2-1: Downgrade then re-upgrade: stale v2 values win and the only copy of the newer legacy secrets is deleted
Confidence 8. Category: data integrity / reliability.
Motivating lines (`SecurityPrefsModule.kt`):
```kotlin
toCopy = entries.filter { newStore.getString(it.key, null) == null }
...
if (!deleteOld()) log("legacy delete failed; retrying next launch")
```
**Scenario.** This is the spec's own rollback path ("Rollback: install the previous APK and re-enter the keys").
1. The app migrates. v2 holds PAT-A and the legacy file is deleted.
2. Skyler installs the old APK with `adb install -r -d`, so app data and the Keystore alias survive. The old APK recreates an empty `publish_secrets` and Skyler enters PAT-B (for example after revoking A).
3. He re-installs the new build. `oldHasData` is true. v2's PAT-A still decrypts, so `toCopy` is empty, and `deleteOld()` destroys PAT-B.
4. The app serves the revoked PAT-A, so publishing returns 401. A GitHub PAT is shown once, so PAT-B is gone.

D11 assumed v2 can only be newer than legacy. A downgrade breaks that assumption.
**Fix (smallest).** Amend the spec's rollback line and the gaps.md text: "If you roll back and later re-upgrade, v2 keeps the pre-rollback values and any key re-entered during the rollback is discarded. Re-enter changed keys after the re-upgrade." A code fix (choosing legacy vs v2 by freshness) needs a timestamp and is not worth building for a single-user app.

### P3-1: Rollback and D11 can drop a transiently unreadable v2 value (contradicts the "hiccup never deletes keys" story)
Confidence 6 (verify). Lines:
```kotlin
toCopy = entries.filter { newStore.getString(it.key, null) == null }
...
toCopy.keys.forEach { rollback.remove(it) }
```
`getString` returns null both for an absent entry and for a transient Keystore failure (the `ProviderException`/`KeyStoreException` path). The bad case needs three things at once:
1. A D9 launch earlier, which left a legacy file plus re-entered v2 values.
2. A transient failure on the v2 read at the next launch while ESP opens fine.
3. Either a commit or verify failure, in which case the rollback **removes the pre-existing v2 ciphertext** (the user's newer value) from `newRaw`, or a successful commit, which overwrites it with the older legacy value.

The probability is low, because the same Keystore daemon has to fail for one call and succeed for the next. No test covers a rollback over a key that already existed in `newRaw`.
**Fix.** Make the rollback restore the snapshot `newRaw.getString(k, null)` for each `toCopy` key instead of removing it. For D11, treat only a raw-absent entry or an `AEADBadTagException` as "replace". A cheaper alternative is to abort the migration when `key()` throws.

### P3-2: Catch allowlist in `decryptOrNull` can still crash a Main-thread read
Confidence 5 (verify). Line:
```kotlin
if (e !is GeneralSecurityException && e !is ProviderException && e !is IllegalArgumentException) throw e
```
`androidKeystoreAesKey` calls `KeyStore.load(null)`, which declares `IOException`, and vendor keystore bugs throw `IllegalStateException` or an NPE. Either one escapes `getString`. `SettingsRepository.settings` and `PublishSettingsRepository.config` call `getString` inside `dataStore.data.map {}`, which runs on the collector's dispatcher (Main through `collectAsState`). So any escape crashes the settings or publish screen, which contradicts D7's "fails per value, app always starts". The Tester flagged the same residual. I raise it from LOW because the call site is on Main.
**Fix.** Use `catch (e: Exception) { log(e); null }`. Mutation M4 (narrowing to AEADBadTag) stays meaningful.

### P3-3: Keystore IPC plus a global lock on every read and write
Confidence 8. Line:
```kotlin
cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(...))
```
`key` is `{ androidKeystoreAesKey(SECRETS_KEY_ALIAS) }`, so every `getString` runs `KeyStore.getInstance`, `load`, and `getKey` under `synchronized(keyCreationLock)`, plus the GCM begin/finish IPC. That is about 4 binder calls per value, on Main for the two flows above, and every secret read in the app is serialised behind one lock. ESP loaded its keyset once at create. The spec accepted "about a millisecond", but that figure was for the decrypt only, not for a reload of the key per read.
**Fix.** Wrap the key in `lazy` inside `openSecrets`, for example `val k by lazy { androidKeystoreAesKey(...) }`. Note that `lazy` caches only on success, so a transient failure is retried on the next read. That keeps D6, because creation still happens under the lock.

### P3-4: `apply()` failure silently drops the user's input
Confidence 7. Line:
```kotlin
override fun apply() { write() }
```
The repos save secrets with `.apply()` (`PublishSettingsRepository` L76/80, `SettingsRepository` L146/156). A transient encrypt failure logs a warning and keeps the old value, while the UI shows the newly typed value. The user finds out at the next scan or publish. This follows the spec (D13), and a platform `apply()` also cannot report errors, so I am not asking for a fix. It is worth a gaps.md line: switching the repo setters to `commit()` and surfacing `false` would close it.

### Checked, no finding
- **Main-thread migration cost.** This is one-shot: ESP.create, then 2 encrypts, a synchronous commit, 2 verifying decrypts, and a delete. The old code ran ESP.create on every launch, so this is a net improvement after the first launch.
- **Exceptions escaping app start.** `migrateLegacySecrets` wraps `openOld` and the copy or verify in `catch (Exception)`. `oldHasData` sits outside the try, but `SharedPreferencesImpl` swallows XML parse errors. Serving `old` on failure keeps master's existing behaviour, where an ESP `getString` on a corrupt value throws `SecurityException`. That is not a regression.
- **Restore or device transfer.** v2 is excluded, so a v2 file that arrives anyway gives AEADBadTag, an empty read and no crash. The older APK does not exclude v2 while it is installed, but only ciphertext with a non-exportable key is exposed. That is acceptable under the threat model.
- **Key creation race.** Both `@Singleton` providers can run concurrently, and the lock covers that. The manifest declares no `android:process`, so there is no multi-process race.
- **Secret leakage.** The default `toString` exposes no fields holding secrets. The tests assert that no plaintext appears in log messages.

## What looks good
- The migration ordering (commit, then a real-Keystore read-back, then delete) is correct, and every failure branch keeps the legacy file.
- D15's fast path means a fresh install never creates a key or makes an IPC call.
- All 16 spec mutations went RED, and the Tester independently re-proved 8 of them (`mutations-tester.log`, with verbatim `FAILED` lines).

## Score (lens: security + reliability)
| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 25/25 | Every D1-D15 item and acceptance criterion traces to code. No scope creep. |
| Correctness & bug-freedom | 19/25 | -4 for P2-1 (deletes the only copy of a secret, but only on a deliberate downgrade path). -2 for P3-1 (narrow conditions). |
| Security & reliability | 16/20 | Crypto and backup handling are clean. -2 for P3-2 (possible crash on Main). -1 for P3-3 (IPC and lock per op). -1 for P3-4 (silent save loss). |
| Maintainability & simplification | 14/15 | Compact and well named, with constants and no extra abstraction. -1 for re-fetching the key per op. |
| Test evidence quality | 13/15 | I summed the JUnit XMLs myself: 48 suites, 604 tests, 0 failures, 0 errors (di package 16 + 12 = 28). The mutation logs are verbatim. -2 because the instrumented test has never compiled or run (PKIX), so real-AndroidKeyStore behaviour (no-IV encrypt, ProviderException types) is unproven until Skyler runs it. The sidecar `test-results.log` lacks the totals line, but the XMLs cover that. |
| **Total** | **87/100** | Ship threshold met (≥85, no P0). |

**Before the dependency-drop change:** run T6 on the emulator, and apply P3-2 and P3-3 (each is a one-line fix).
