# Spec: Batch 3 - replace security-crypto with direct Android Keystore AES-GCM

Branch `gaps-batch3-keystore` (base d7ccad3). minSdk 26. Baseline 581 unit tests.

## TL;DR
New `KeystoreSecretStore` implements `SharedPreferences` (String values only). It encrypts values with AES-256-GCM under a non-exportable AndroidKeyStore key and stores them in plain files `settings_secrets_v2` / `publish_secrets_v2`. A fail-safe migration copies the legacy EncryptedSharedPreferences (ESP) files on launch and verifies them by read-back before deleting them. Repos and the Hilt graph are unchanged. `security-crypto` STAYS in this batch because the migration reads the old files with it.

## Problem
`security-crypto` 1.1.0-alpha06 is deprecated with no further releases. It holds 4 secrets: `settings_secrets` (`gemini_api_key`, `anthropic_api_key`) and `publish_secrets` (`github_pat`, `discord_webhook_url`). `createOrResetOnDecryptFailure` deletes the whole file on any GeneralSecurityException/IOException, so a transient Keystore error wipes every key (gaps.md, session 23).

Grounded (read this session):
- The only consumers are the two repos, through `@Named("settingsSecrets"|"publishSecrets") SharedPreferences`. They call ONLY `getString(k, def)` and `edit().putString(k, v).apply()/.commit()`.
- Legacy constants, confirmed from the cached alpha06 AAR: MasterKey alias `_androidx_security_master_key_`; reserved keys `__androidx_security_crypto_encrypted_prefs_key_keyset__` / `..._value_keyset__`.
- JVM tests run with no Robolectric and no `isReturnDefaultValues`, so `android.util.Log`/`Base64` throw there. Use `java.util.Base64` (API 26) and pass logging in as lambdas (the existing pattern).

## Proposal

**Threat model.** Defends against offline reads of the prefs XML (a file copied off a rooted device, a cloud or device-transfer backup) and against tampering or swapping ciphertexts. The key is non-exportable and TEE-backed where the device has one. Does NOT defend against code running as the app uid, or root on the live unlocked device (either can ask Keystore to decrypt), or memory reads. Same posture as ESP. No StrongBox, no user-auth.

| # | Decision | Why |
|---|---|---|
| D1 | Implement `SharedPreferences` | Repos, `FakeSharedPreferences` and `FakeSecurityPrefsModule` stay unchanged. Unused surface (non-String types, listeners) throws `UnsupportedOperationException`, so misuse fails loudly. |
| D2 | Key NAMES stored in clear | They are constants in the APK, so hiding them reveals nothing worth protecting. Encrypting them would need SIV (not in JCA) or an HMAC index. Clear names also let rollback use plain `remove`. |
| D3 | Value = `Base64(iv[12] ‖ ct‖tag[16])` | Self-contained. Versioned by the alias `_v1` and file `_v2`, so no version byte. |
| D4 | `init(ENCRYPT_MODE, key)` with NO caller IV, then read `cipher.iv` and `require(size == 12)` | AndroidKeyStore rejects a caller IV (randomized encryption is required by default). SunJCE generates a random 12-byte IV, so one code path works on both. |
| D5 | AAD = `"$storeName/$key"` UTF-8 | Blocks swaps across keys and across the two files, which share one alias. |
| D6 | One alias `pokedexbinder_secrets_aes_v1`. Create it under a global lock. If the alias exists but won't load, throw and never regenerate | A concurrent create, or regenerating after a transient load failure, would orphan every value. |
| D7 | Decrypt failure returns `defValue`, logs the class, and does NOT delete | The store fails per value, never at open, so the app always starts. "Unreadable = empty, re-enter" is kept, and a transient error deletes nothing. The next `putString` overwrites. |
| D8 | Delete `createOrResetOnDecryptFailure` and its 5 tests | D7 and D9 leave it with no caller. This deviates from the brief's letter but keeps its intent: the wipe IS the transient-loss bug. See Open questions. |
| D9 | `openOld` throws: serve the new store, keep the old file, retry next launch | Transient and permanent failures cannot be told apart. The dependency-drop change deletes the legacy files later. |
| D10 | "Migrated" = old file empty (`context.getSharedPreferences(old).all.isEmpty()`) | No flag to drift. A fresh install never calls `ESP.create`, so no MasterKey is created. |
| D11 | Copy an old entry only if `newStore.getString(k, null) == null` (decryptable check, NOT `contains`) | Keys re-entered while old was unreadable win. An undecryptable new entry is replaced by the old value. |
| D12 | Leave the old MasterKey Keystore entry | Deleting it is irreversible and gains nothing now. It goes in the dependency-drop change. |
| D13 | Encrypt every pending value at `commit()`/`apply()`. Any failure: write nothing and log; `commit()` returns false, `apply()` returns silently | Atomic per editor, and never throws into repo coroutines. |
| D14 | No `@Synchronized` | Fresh `Cipher` per op and SharedPreferencesImpl is thread-safe. Only key creation is locked. |
| D15 | Raw entry absent: return `defValue` without touching Keystore | Fresh-install reads make no IPC and create no key. |

## Technical layer

### Files
| File | Change |
|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/di/KeystoreSecretStore.kt` | NEW: class + `androidKeystoreAesKey` |
| `.../di/SecurityPrefsModule.kt` | `openEncryptedPrefs` becomes `openSecrets`. `createOrResetOnDecryptFailure` is replaced by `migrateLegacySecrets` |
| `app/src/test/java/com/skyler/pokedexbinder/di/KeystoreSecretStoreTest.kt` | NEW |
| `app/src/test/.../di/SecurityPrefsModuleTest.kt` | Rewritten as migration tests |
| `app/src/androidTest/java/com/skyler/pokedexbinder/di/KeystoreSecretStoreInstrumentedTest.kt` | NEW |
| `app/src/main/res/xml/data_extraction_rules.xml` (cloud-backup + device-transfer), `full_backup_content.xml` | Add excludes `settings_secrets_v2.xml`, `publish_secrets_v2.xml` (keep the old ones). Comment: Keystore key never leaves the device |
| `app/build.gradle.kts` L126 | Comment only: `// security-crypto: read-only, migrates legacy ESP into KeystoreSecretStore; drop after phone confirms` |
| `libs.versions.toml`, repos, `FakeSharedPreferences`, `FakeSecurityPrefsModule` | NO change (same `@Named` + type) |

### Signatures (package `com.skyler.pokedexbinder.di`)
```kotlin
internal const val SECRETS_KEY_ALIAS = "pokedexbinder_secrets_aes_v1"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private val keyCreationLock = Any()

/** Creates the key if absent; throws KeyStoreException if the alias exists but won't load (D6). */
internal fun androidKeystoreAesKey(alias: String): SecretKey = synchronized(keyCreationLock) { ... }
// KeyStore.getInstance("AndroidKeyStore").load(null); (getKey(alias,null) as? SecretKey)?.let { return it }
// if (containsAlias(alias)) throw KeyStoreException("...")
// KeyGenerator.getInstance(KEY_ALGORITHM_AES,"AndroidKeyStore").init(KeyGenParameterSpec.Builder(alias,
//   PURPOSE_ENCRYPT or PURPOSE_DECRYPT).setBlockModes(BLOCK_MODE_GCM)
//   .setEncryptionPaddings(ENCRYPTION_PADDING_NONE).setKeySize(256).build()); generateKey()

class KeystoreSecretStore(
    private val raw: SharedPreferences,   // plain backing file
    private val storeName: String,        // AAD prefix (D5)
    private val key: () -> SecretKey,     // seam: JVM tests pass a SunJCE key
    private val log: (Exception) -> Unit  // class name only
) : SharedPreferences
```
- `getString(k, def)`: if raw is absent, return `def` (D15). Otherwise decrypt. On `GeneralSecurityException`, `java.security.ProviderException` (the RuntimeException Keystore throws) or `IllegalArgumentException` (bad Base64 or short blob): `log(e)` and return `def`. Anything else propagates.
- `contains` = raw presence (can be true for an undecryptable entry; documented). `getAll` returns decrypted Strings and omits failures.
- Editor: `putString(k, null)` is the same as `remove(k)`; also supports `remove` and `clear`. `commit`/`apply`: encrypt all values first (D13), then one raw editor applying clear, then removes, then puts.
- Other getters/putters and listener methods throw `UnsupportedOperationException`.

```kotlin
/** Fail-safe legacy -> v2 copy. Returns the prefs to serve this launch. Never throws. */
internal fun migrateLegacySecrets(
    newStore: SharedPreferences, newRaw: SharedPreferences,
    oldHasData: () -> Boolean, openOld: () -> SharedPreferences, deleteOld: () -> Boolean,
    log: (String) -> Unit   // counts + exception class only
): SharedPreferences
```
1. If `!oldHasData()`, return `newStore`.
2. `old = try { openOld() } catch (e: Exception) { log; return newStore }`. Nothing is deleted (D9).
3. Inside one `try`: `entries = old.all` minus keys prefixed `__androidx_security_crypto_encrypted_prefs_`. Any non-String value throws `IllegalStateException`.
4. `toCopy = entries.filter { newStore.getString(it.key, null) == null }` (D11).
5. If `toCopy` is non-empty: write all of it with one editor, `commit()`; if that returns false, throw.
6. Verify each `toCopy` value reads back `==`, otherwise throw. Then `if (!deleteOld()) log(..)`, log `"migrated ${toCopy.size} of ${entries.size}"` and return `newStore`.
7. `catch (e: Exception)`: best-effort rollback `newRaw.edit()` removing the `toCopy` keys, then `.commit()`, so a stale copy can't later beat edits made to old this session. Log the class, return `old`, retry next launch.

```kotlin
private fun openSecrets(context: Context, oldName: String, newName: String): SharedPreferences {
    val newRaw = context.getSharedPreferences(newName, Context.MODE_PRIVATE)
    val store = KeystoreSecretStore(newRaw, newName, { androidKeystoreAesKey(SECRETS_KEY_ALIAS) },
        log = { Log.w(TAG, "$newName value unreadable (${it::class.java.simpleName})") })
    return migrateLegacySecrets(store, newRaw,
        oldHasData = { context.getSharedPreferences(oldName, Context.MODE_PRIVATE).all.isNotEmpty() },
        openOld = { /* existing MasterKey + ESP.create, params unchanged */ },
        deleteOld = { context.deleteSharedPreferences(oldName) },
        log = { Log.w(TAG, "$oldName migration: $it") })
}
```
Providers call it with `("settings_secrets","settings_secrets_v2")` and `("publish_secrets","publish_secrets_v2")`. Add `@Suppress("DEPRECATION")` on `openOld` ONLY if the compiler warns. alpha06's `MasterKey` has no `@Deprecated`, and ESP has one, probably on an unused overload.

### Edge cases
- Process death after the step-5 commit, before the delete: next launch re-merges with an empty `toCopy`, then deletes. Idempotent. Commit writes the XML atomically, so a partial copy can't happen.
- `deleteOld` returns false: serve the new store and retry next launch (D11 protects the new values).
- Second launch once migrated: old is empty or gone, so ESP and the MasterKey are never touched.
- Restore to a new device or reinstall (Keystore keys die with the uid): v2 is excluded from backup. If a v2 file arrives anyway, a fresh key is generated, AEADBadTag follows, values read empty, and the user re-enters them. No crash.
- Downgrade after migration: old files are gone, so the user re-enters keys.
- The `settings` flow's `map` calls `getString` on Main (`collectAsState`), so the Keystore IPC there costs about a millisecond. Accepted; ESP decrypted there too.

## User stories
- As Skyler, I update the app and my Gemini/Claude keys, GitHub PAT and webhook are still there, with nothing re-entered.
- As Skyler, a Keystore hiccup at launch never deletes my keys.

## Acceptance criteria
- [ ] **Given** old files holding 4 secrets, **when** the app starts, **then** v2 holds the same 4 values, the old files are deleted, and the repos return the same strings.
- [ ] **Given** `openOld` throws, **then** nothing is deleted and the new store is served. **When** a later launch finds old readable, **then** it merges per D11.
- [ ] **Given** a write or verify failure, **then** old is served, the old file is kept, and the copied keys are rolled back from v2.
- [ ] **Given** an AEAD failure on a v2 value, **then** `getString` returns the default and the raw entry still exists.
- [ ] A ciphertext swapped between keys or files fails. A flipped byte fails. Two writes of the same value differ, and each IV is 12 bytes.
- [ ] Repos, `FakeSecurityPrefsModule` and `libs.versions.toml` are unchanged. No new dependency, no Room change.
- [ ] The v2 files are excluded in all 3 backup lists.
- [ ] `./run_build.bat testDebugUnitTest --rerun-tasks` passes with fresh XMLs (581 - 5 + new).
- [ ] Logs carry the exception class and counts only, never values.

## Tasks
| # | Task | Size |
|---|---|---|
| T1 | `KeystoreSecretStore.kt` | M |
| T2 | `KeystoreSecretStoreTest` + mutations | M |
| T3 | `migrateLegacySecrets` + `openSecrets`; delete `createOrResetOnDecryptFailure` | M |
| T4 | Rewrite `SecurityPrefsModuleTest` + mutations | M |
| T5 | Backup XMLs + gradle comment | S |
| T6 | Instrumented test (written by reading; cannot compile here) | S |

### Tests and mutation checks
JVM key: `KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()`. Raw prefs: `FakeSharedPreferences` (from the `repository` test package).

| Store test | Mutation that must go red |
|---|---|
| round-trip via commit and apply; raw != plaintext and doesn't contain it | skip encrypt |
| raw of "a" copied to "b" returns default; AEADBadTag logged | drop `updateAAD` on both sides |
| same key name moved from storeName A to B returns default | AAD = key only |
| flip one decoded byte returns default; non-Base64 or a 5-byte blob returns default without throwing | narrow catch to AEADBadTagException |
| same value written twice: raw differs, IVs differ, IV size 12 | fixed `GCMParameterSpec(128, ByteArray(12))` on encrypt |
| key lambda throws KeyStoreException once: default returned, raw kept, readable afterwards | delete the entry on failure |
| write failure: `commit()` false, prior value intact, `apply()` doesn't throw | write raw before encrypting everything |
| absent key never calls the key lambda | remove the fast path |
| remove / put null / clear / getAll omits a bad entry; unsupported type throws | - |

Migration tests: old = `FakeSharedPreferences`, newStore = a real store over a `FakeSharedPreferences`.

| Migration test | Mutation |
|---|---|
| 2 strings + 1 reserved key: 2 copied, `deleteOld` called once, returns newStore | drop the reserved filter |
| no old data: `openOld` never called | drop the step-1 guard |
| `openOld` throws GSE: newStore served, no delete | delete in the catch |
| key throws during write: `assertSame(old)`, no delete, newRaw empty | return newStore on failure |
| read-back mismatch (`object : SharedPreferences by real` whose `getString` reverses non-null values): old served, no delete, copied keys rolled back | drop verify / drop rollback |
| `deleteOld` false, then a rerun: newStore both times, values equal, delete retried | - |
| new "k"=newer vs old "k"=older: newer kept | drop the D11 filter |
| undecryptable new entry + readable old: old value copied | `contains` instead of `getString` |
| non-String old value: old served, no delete | - |

T6 setup: plain `AndroidJUnit4`, no Hilt. Unique alias `test_secrets_${UUID}` and unique prefs. Covers: real-Keystore round-trip, a second instance on the same alias reads the value, tamper returns default, IV of 12 bytes. `@After` runs `KeyStore.deleteEntry(testAlias)` and `deleteSharedPreferences`. NEVER touch `_androidx_security_master_key_` or `SECRETS_KEY_ALIAS`. Skyler compiles it and runs it on the emulator only (`ANDROID_SERIAL=emulator-5554`).

## Out of scope
- Dropping `security-crypto`, deleting the legacy files unconditionally, deleting the old MasterKey alias. All three form ONE later change, after Skyler confirms on the phone.
- Encrypted key names (D2), StrongBox/user-auth, Tink, Room/DataStore changes, reactive secret updates.

## Dependencies
- Existing only: `security-crypto` alpha06 (read-only), JCA, `android.security.keystore`, `java.util.Base64`.
- Skyler: the phone check, plus `compileDebugAndroidTestKotlin` and T6 on the emulator (the sandbox hits PKIX).

## Open questions
- [ ] D8 departs from the brief's letter: the new store has no wipe, and `createOrResetOnDecryptFailure` is deleted. **Owner:** orchestrator/Skyler. Non-blocking. Restoring a wipe would restore the transient-loss bug.

## Risks and mitigations
| Risk | L | I | Mitigation |
|---|---|---|---|
| Migration loses keys | L | H | Delete only after read-back; every failure serves old; Skyler notes his 4 secrets before install |
| Keystore key lost (OS bug or reset) | L | M | Values read empty and get re-entered; nothing auto-deleted |
| Concurrent first key creation | L | H | Lock + never regenerate (D6) |
| Main-thread IPC jank | L | L | Reads happen per DataStore emission only |
| T6 never compiled here | M | L | Skyler compiles and runs it |

## Smallest shippable increment
T1-T5. T6 must run before the dependency-drop change.

**Phone check (Skyler):**
1. `installDebug` over the existing app.
2. Settings shows the Gemini/Claude keys; Publish shows the PAT and webhook.
3. Do one scan.
4. Logcat `SecurityPrefs` shows `migrated 2 of 2` twice.
5. `adb shell run-as com.skyler.pokedexbinder ls shared_prefs` lists only the `*_v2.xml` secret files.

Rollback: install the previous APK and re-enter the keys.

**gaps.md text for the orchestrator** (replaces the Parked `security-crypto` entry and the session-23 "Encrypted-prefs recovery" entry):
"**security-crypto replaced; the dependency remains (session 25 batch 3).** Secrets are in `settings_secrets_v2`/`publish_secrets_v2` (`KeystoreSecretStore`, AndroidKeyStore AES-256-GCM, alias `pokedexbinder_secrets_aes_v1`). A one-shot verified migration deletes the legacy files. An unreadable value reads as empty and nothing is auto-wiped. Remaining: after Skyler confirms on the phone, one change drops `securityCrypto` (toml + gradle) and `openOld`, deletes the legacy files unconditionally, and optionally deletes the `_androidx_security_master_key_` entry. `KeystoreSecretStoreInstrumentedTest` has not been compiled or run yet."

## Friction Notes
- The brief asked to keep the wipe for the new store. Per-value decrypt makes the wipe pointless, and the wipe is the root of the transient-loss gap. I flagged it rather than following it silently.
- Reusable: `grep -a -o` over classes unzipped from the Gradle-cached AAR confirms library constants offline (no `strings` binary available).
- The plan says "3 secrets" but the code has 4 (`anthropic_api_key`). The migration copies generically, so this has no effect.
