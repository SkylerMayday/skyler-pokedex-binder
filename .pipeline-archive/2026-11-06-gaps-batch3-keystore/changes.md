# Changes - batch 3: KeystoreSecretStore (replaces security-crypto at runtime)

Spec followed exactly (D1-D15). No deviations. No new dependency (Item P: N/A). security-crypto stays (read-only, used by the migration).

## Files
| File | What / why |
|---|---|
| `di/KeystoreSecretStore.kt` (NEW) | `androidKeystoreAesKey` (global lock, create-if-absent, throws if alias exists but won't load, never regenerates) + `KeystoreSecretStore : SharedPreferences`. Value = Base64(iv12 + ct + tag), AAD `"$storeName/$key"`, encrypt with no caller IV then `require(iv.size==12)`. Decrypt failure (GSE / ProviderException / IAE) -> log class, return default, never delete. Absent raw entry -> default without calling the key lambda. Editor encrypts all pending values first; failure writes nothing (`commit()` false, `apply()` silent). Non-String API throws UnsupportedOperationException. No `@Synchronized`. |
| `di/SecurityPrefsModule.kt` | `openEncryptedPrefs` -> `openSecrets(old, new)`; `createOrResetOnDecryptFailure` DELETED (D8), replaced by `migrateLegacySecrets` (copy -> read back -> delete old; every failure serves old or new as spec'd; rollback of copied keys on failure; reserved ESP keys skipped; non-String legacy value fails safe). Providers use `settings_secrets(_v2)` / `publish_secrets(_v2)`. |
| `test/.../KeystoreSecretStoreTest.kt` (NEW) | 11 tests per spec table. |
| `test/.../SecurityPrefsModuleTest.kt` | Rewritten: 5 old tests removed, 9 migration tests. |
| `androidTest/.../KeystoreSecretStoreInstrumentedTest.kt` (NEW) | Written by reading, NOT compiled or run (PKIX). Unique alias + prefs per test; `@After` deletes only the test alias; never touches the master-key alias or `SECRETS_KEY_ALIAS`. 4 tests: real round trip, second instance, tamper, 12-byte IV. |
| `res/xml/data_extraction_rules.xml` (cloud-backup + device-transfer), `full_backup_content.xml` | Excludes for `settings_secrets_v2.xml`, `publish_secrets_v2.xml`; old excludes kept. |
| `app/build.gradle.kts` | Comment only above security-crypto. |

Untouched: repos, `FakeSharedPreferences`, `FakeSecurityPrefsModule`, `libs.versions.toml`, Room. gaps/handoff left to the orchestrator.

## Commits
1. `ce185eb` feat: KeystoreSecretStore (store + tests)
2. `f448b28` feat: verified legacy-secrets migration, drop createOrResetOnDecryptFailure
3. `daa358f` chore: exclude v2 secret files from backup, add instrumented keystore test

## Simplification / review pass (inline, no subagents)
- Reuse: no existing helper duplicates this; used `java.util.Base64`, existing `FakeSharedPreferences`, existing log-lambda pattern.
- Correctness: found and fixed a false-green in my own test - the read-back-mismatch fake used `reversed()` on single-char values (palindromes); values lengthened (`gem-1`, `ant-2`). Editor semantics checked: remove-then-put and put-then-remove resolve to the last op; `clear()` drops pending ops.
- Secret hygiene: logs carry exception class names and counts only. No values, no key material, no stray println/Log.d.
- Accepted ceilings (spec): key names in clear; Keystore IPC on Main for the settings flow.

## 7-check verification (raw: `.pipeline/evidence/changes-verify.log`, `mutations.log`, `androidtest-compile.log`)
| # | Check | Result |
|---|---|---|
| 1 | Typecheck | PASS - Kotlin compile of main + unit tests inside the runs below; no `w:` warnings for the touched di files. |
| 2 | Lint | N/A - `lintDebug` cannot run in the sandbox (PKIX). |
| 3 | Tests | PASS - `rm -rf app/build/test-results/testDebugUnitTest`, then `./run_build.bat testDebugUnitTest --rerun-tasks`: BUILD SUCCESSFUL. XML totals: **596 tests, 0 failures, 0 errors, 0 skipped** (= 581 - 5 + 20: 11 store + 9 migration). |
| 4 | Production build | PASS - `assembleDebug` BUILD SUCCESSFUL. |
| 4b | `compileDebugAndroidTestKotlin` | FAIL, environmental, not retried: `Failed to transform monitor-1.7.1.aar ... PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors`. The instrumented test is unverified; Skyler compiles and runs it. |
| 5 | Dev server boot | N/A - Android app, no server. |
| 6 | No stray debug / scratch | PASS - grep for println/debugger/Log.d/i/v in touched files is empty. Mutation script lived in the session scratchpad, outside the repo. |
| 7 | git status | PASS - clean after the 3 commits (`.pipeline/` is untracked/ignored). |

## Mutation checks (apply -> RED -> revert -> GREEN)
Full per-test FAILED lines: `.pipeline/evidence/mutations.log`. Each run: `testDebugUnitTest --tests *KeystoreSecretStoreTest --tests *SecurityPrefsModuleTest`. All 16 went RED; the baseline after revert is GREEN (exit 0), and the full rerun above is green.

| Mutation | Primary red test(s) |
|---|---|
| S1 skip encrypt | round trip ... raw is not plaintext (+5 more store, 4 migration) |
| S2 drop updateAAD both | ciphertext copied to another key; moved to another store name |
| S3 AAD key only | ciphertext moved to another store name |
| S4 narrow catch to AEADBadTag | flipped byte/non-base64/short blob; transient key failure; getAll omits bad; migration undecryptable-new-entry |
| S5 fixed IV | same value twice -> different ciphertext and 12 byte IVs |
| S6 delete on failure | transient key failure keeps raw; getAll omits bad |
| S7 write raw before encrypting all | failed encrypt writes nothing |
| S8 remove fast path | absent key never touches the key lambda |
| N1 drop reserved filter | copies string entries, skips reserved keys |
| N2 drop step-1 guard | no old data never opens old |
| N3 delete in openOld catch | openOld failure serves new store and deletes nothing |
| N4 return newStore on failure | write failure serves old; read-back mismatch; non-String legacy value |
| N5 drop verify | read-back mismatch serves old and rolls back |
| N5b drop rollback | read-back mismatch serves old and rolls back |
| N7 drop D11 filter | newer value in new store beats older legacy |
| N8 contains instead of getString | undecryptable new entry replaced by legacy value |

## For orchestrator / Skyler
- Not verifiable here: real AndroidKeyStore behavior (no-IV encrypt, ProviderException types) and the instrumented test. JVM tests use a SunJCE key through the `key` seam. Skyler runs `compileDebugAndroidTestKotlin` + the instrumented test on the emulator, then the spec's phone check.
- D8 (wipe removed) per spec; the open question stays with the orchestrator.

## Review fixes (commit after 73398c4)
Evidence: `.pipeline/evidence/review-fixes-verify.log` (full run), `.pipeline/evidence/mutations-review.log` (per-test FAILED lines).
- F1: `SettingsRepository.migrateGeminiKeyIfNeeded` returns early (plaintext kept, flag unset) when the secure `commit()` returns false. Other callers (`setGeminiApiKey`, `setAnthropicApiKey`, Publish setters) use `apply()` with no destructive follow-up, so no same-class bug (D13 accepts silent apply failure). Test: `failed secure commit keeps the legacy plaintext key and retries the migration`.
- P3-2: `decryptOrNull` catches `Exception`; unused imports removed. Test with IOException / IllegalStateException key lambdas.
- P3-3: `cacheOnSuccess` (success-only `lazy`) wraps `androidKeystoreAesKey` in `openSecrets`; D6 unchanged. Test: fail once then succeed, loads == 2.
- F2: rollback `commit()` result checked and logged ("rollback commit failed").
- Docs: stale gradle comment deleted; D13 comment on `apply()`; backup XML + FakeSecurityPrefsModule comments; test comment and strict `getString` assertion fixed.
- Mutations (RED, then baseline GREEN): R1 remove F1 guard -> repo test; R2 narrow catch -> IO/ISE test (+ short-blob test); R3 cache failures -> cacheOnSuccess test.
- Verify: fresh `testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL, **607 tests, 0 failures/errors/skipped** (604 + 3 new); `assembleDebug` OK; lint N/A and androidTest compile PKIX unchanged.

## Friction Notes
- In worktree sessions, compound Bash with JAVA_HOME text is rejected; `./run_build.bat` already sets the JDK, so plain `./run_build.bat <task>` works.
- Python `subprocess` + `cmd /c .\run_build.bat` in a normal string turns `\r` into CR; use an absolute path built with `chr(92)`.
- Mutation harness as a background python script (apply/run/revert in try/finally, per-test FAILED lines to a log) ran 16 mutations in one call.
- Test pitfall: reversal-based "lying" fakes need non-palindromic values.
