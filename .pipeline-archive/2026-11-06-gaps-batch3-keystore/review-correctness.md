# Review - Lens 1: correctness + spec-compliance trace

**Verdict: SHIP** (score 90/100, no P0/P1). One P2 is a real interaction bug outside the diff. It needs only a 1-line follow-up, but the follow-up touches a repo file that the spec froze, so the orchestrator or Skyler decides when to fix it.
First pass for this lens (no prior `review-correctness.md`).

## Scope
`git diff master...HEAD` (d7ccad3..73398c4, 4 commits, 8 files, +653/-72). I also read, outside the diff, `SettingsRepository.kt`, `PublishSettingsRepository.kt`, `FakeSecurityPrefsModule.kt`, and the evidence logs. Nothing untracked except `.pipeline/`.

## Intent audit (spec trace)
| Item | Status | Evidence |
|---|---|---|
| D1 SharedPreferences impl, unused surface throws | DONE | `private fun unsupported(): Nothing = throw UnsupportedOperationException(...)`; test `unsupported types throw` |
| D2 names in clear | DONE | `editor.putString(k, v)` with clear `k` |
| D3 `Base64(iv‖ct‖tag)` | DONE | `Base64.getEncoder().encodeToString(iv + cipher.doFinal(...))`. Basic `java.util.Base64` on both sides (API 26, no line wrapping, strict decoder, so bad input raises IAE and is caught) |
| D4 no caller IV, require 12 | DONE | `cipher.init(Cipher.ENCRYPT_MODE, key())` / `require(iv.size == GCM_IV_BYTES)` |
| D5 AAD `"$storeName/$key"` | DONE | `"$storeName/$k".toByteArray(Charsets.UTF_8)`, applied on both encrypt and decrypt. storeName = `newName` (`settings_secrets_v2` / `publish_secrets_v2`, no `/`), so the AAD cannot be ambiguous. Mutations M1/M2 went red |
| D6 locked create, never regenerate | DONE | `if (ks.containsAlias(alias)) throw KeyStoreException(...)` inside `synchronized(keyCreationLock)` |
| D7 decrypt failure -> default, no delete | DONE | `decryptOrNull` catches GSE/ProviderException/IAE only. Test `transient key failure ... keeps raw, recovers` |
| D8 delete `createOrResetOnDecryptFailure` | DONE | removed in the diff, no remaining references (grep) |
| D9 openOld throws -> serve new, keep old | DONE | `catch (e: Exception) { log(...); return newStore }`, M8 red |
| D10 migrated = old empty | DONE | `oldHasData = { ...getSharedPreferences(oldName...).all.isNotEmpty() }` |
| D11 copy only if `getString == null` | DONE | `entries.filter { newStore.getString(it.key, null) == null }`; N7/N8 red |
| D12 keep old MasterKey | DONE | no deleteEntry in main code |
| D13 encrypt-all-first, commit false / apply silent | DONE | `puts.mapValues { ... encrypt(k, v) }` before `raw.edit()` |
| D14 no `@Synchronized` | DONE | |
| D15 absent -> no Keystore touch | DONE | `raw.getString(key, null) ?: return defValue`; S8 red |
| Backup excludes x3 | DONE | both domains in `data_extraction_rules.xml` and `full_backup_content.xml` |
| Repos / Fake module / toml unchanged | DONE | none of them in `--stat` |
| gradle comment | DONE | |
| T6 instrumented test | UNVERIFIABLE | written, never compiled (PKIX). Skyler must run `compileDebugAndroidTestKotlin` and the test on the emulator |
| AC2 second half ("later launch merges per D11") | CHANGED | no test of open-fail then a later launch, but D11 merging is covered by `newer value in new store beats older legacy value` |

No scope creep.

### Migration order, every branch
- Happy path: copy (`check(editor.commit())`), then read back (`check(toCopy.all { ... == v })`), then `deleteOld()`. Delete is the last fallible step, and only `log` follows it, so nothing can roll back after the delete. Correct.
- Write fails: KeystoreSecretStore `write()` returns false and nothing is written. The `check` throws, the catch rolls back (a no-op here), and `old` is served. Tested.
- Verify fails: the catch removes the `toCopy` keys from `newRaw` and serves `old`. Tested (non-palindromic values).
- `deleteOld` returns false: new store is served, and the next run has an empty `toCopy` and deletes. Tested.
- Death between copy and delete: the next launch logs `migrated 0 of 2` and deletes. Tested (an `Error` stands in for the death).
- Re-run idempotent: yes, because D11 skips keys that are already there.

### D8: can the app fail to start?
No path found. In the provider, everything after `oldHasData()` runs inside `try/catch (Exception)`. `oldHasData()` reads a plain prefs file, which does not throw on parse errors. The constructor does no I/O. Once the store is served, any GSE/ProviderException/IAE reads as the default. Residual (Tester's LOW, I agree): a RuntimeException outside that list, coming from `key()` inside `getString` on Main, would propagate. It is spec-accepted.

### Hilt
The providers keep `@Named("settingsSecrets"|"publishSecrets") SharedPreferences` and their `@Singleton` scope. `FakeSecurityPrefsModule` still has `@TestInstallIn(components = [SingletonComponent::class], replaces = [SecurityPrefsModule::class])` and is untouched, so the graph is consistent.

### Repo behaviour
Repos use only `getString(k, "")`/`getString(k, null)` and `edit().putString().apply()/.commit()`. The read path is identical for the user. The write path is identical on success. On failure it differs, see F1.

## Findings

### F1 - P2 - `commit()` now fails silently, and SettingsRepository's plaintext-key migration then deletes the only copy (confidence 7)
`SettingsRepository.kt:89-91` (not in the diff, but the diff changed its dependency's failure mode):
```
securePrefs.edit().putString(SecureKeys.GEMINI_API_KEY, legacy).commit()
...
dataStore.edit { it.remove(Keys.GEMINI_API_KEY) }
```
`KeystoreSecretStore.kt:126-130`:
```
puts.mapValues { (k, v) -> encrypt(k, v) }
} catch (e: Exception) {
    log(e)
    return false
```
If a Keystore hiccup happens during this commit, `commit()` returns false and nothing is written. The caller ignores the Boolean, removes the plaintext Gemini key from DataStore, and sets `geminiKeyMigrated = true`, so the key is lost. Before this change, ESP encrypted inside `putString` and threw `SecurityException` on a GSE [Likely: from memory of the androidx source, not re-opened this turn], which aborted the coroutine before the plaintext clear. The trigger is narrow: the legacy plaintext key must still be in DataStore AND Keystore must fail at that moment. It is probably already migrated on Skyler's phone. This is the same transient-loss class the batch exists to remove. The same silent-false behaviour also affects the `apply()` setters (a key the user enters is silently not saved), which D13 accepts.
**Fix (1 line, repo file):** `if (!securePrefs.edit()...commit()) return@withLock` (skip the clear and leave `geminiKeyMigrated` false). Alternatively, record it as an accepted risk.

### F2 - P3 - Rollback result unchecked (confidence 8)
`SecurityPrefsModule.kt:106`: `rollback.commit()`. Its false return is neither logged nor acted on. If rollback fails after a verify failure, the stale copy stays in v2 while `old` is served. A user edit made to `old` this session then loses on the next launch, because D11 skips keys already in v2 and `old` is deleted. The spec calls rollback "best-effort", so the residual is accepted, but a silent false defeats the "log everything" intent. Fix: `if (!rollback.commit()) log("rollback commit failed")`.

### F3 - P3 - Rollback can delete an undecryptable v2 entry (confidence 6, verify)
`toCopy` includes a key whose v2 raw entry exists but is undecryptable (D11). On a later failure, `toCopy.keys.forEach { rollback.remove(it) }` removes that raw entry. This departs slightly from D7's "never delete". It is harmless, because `old` still holds the value and is served. Informational only.

### F4 - P3 - `clear()` drops earlier pending puts (confidence 8)
`override fun clear() = apply { cleared = true; puts.clear(); removes.clear() }`. Platform semantics: `put("a").clear().commit()` keeps `a`, because clear is applied first whatever the call order. Here `a` is lost. No caller uses `clear()`, so there is no user impact. Mention it only because D1 claims SharedPreferences conformance.

No other bugs found. These checks were clean: AAD on both sides, GCM tag 128, IV slice offsets (`doFinal(blob, 12, size-12)`), `require(blob.size > 12)`, the empty-value round trip, and log hygiene (class names and counts only).

## What looks good
- The migration ordering is right in every branch, and every failure keeps `old`.
- The mutation set maps one-to-one onto the spec's security properties. 16 Coder mutations plus 8 Tester re-proofs all went red, with named tests in `mutations*.log`.
- Removing the wipe (D8) is justified: per-value decrypt means nothing can fail at open.

## Score
| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | D1-D15 and the ACs are all traced to code and tests. -1 because T6 is unverifiable and AC2's second half is covered only indirectly. |
| Correctness & bug-freedom | 20/25 | F1 (P2, a cross-file regression in failure semantics) -3; F2, F3, F4 (P3) -2. |
| Security & reliability | 18/20 | F1 is also a reliability issue (silent loss on a transient error) -1. A non-listed RuntimeException on Main can escape (LOW, accepted) -1. |
| Maintainability & simplification | 14/15 | Small and clear, reuses existing seams. `androidKeystoreAesKey` reloads KeyStore on every op (accepted cost) -1. |
| Test evidence quality | 14/15 | Fresh `--rerun-tasks` run (`BUILD SUCCESSFUL in 2m 16s`, 31 tasks executed), 604 tests, named-red mutation logs. -1 because the real AndroidKeyStore path is unexercised. |
| **Total** | **90/100** | |

Ship: 90 ≥ 85, no P0. Recommend fixing F1 and F2 as a follow-up, or in a short Coder pass, before Skyler's phone check.
