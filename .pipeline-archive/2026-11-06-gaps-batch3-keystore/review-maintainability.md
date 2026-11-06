# Code Review - Lens 3: maintainability + simplification + test quality

**Reviewer:** review-overall (lens 3) | **Date:** 2026-11-06 | **Branch:** gaps-batch3-keystore, `git diff master...HEAD` (4 commits, 8 files, +653/-72)
**Prior iteration:** none. This is the first pass, so there is no score delta.

## Summary
**Verdict: SHIP.** No P0 or P1 findings. `KeystoreSecretStore` is close to the smallest thing that works, given D1 (keep the `SharedPreferences` type so that the repos and both Fakes stay unchanged). The `key` seam is justified. The migration is readable, and the mutation evidence names the red test for each of the 16 mutations. Findings are P2/P3: stale comments left over from the ESP era, a little unused `SharedPreferences` surface, one misleading test comment, two near-tautological "no plaintext in logs" tests, and an incomplete checklist for the later drop-security-crypto change.

Critical 0 | Important 0 | Minor 6 | Suggestions 2

## Intent audit (Phase 2): clean
T1-T6 are all present (DONE): `KeystoreSecretStore.kt` is new; `openSecrets` and `migrateLegacySecrets` replace `createOrResetOnDecryptFailure`; both test classes are present; the instrumented test is present; there are 3 backup excludes; the gradle comment is in place. Repos, `FakeSharedPreferences`, `FakeSecurityPrefsModule` and `libs.versions.toml` are untouched (verified with `--name-status`). No scope creep. The Tester's commit `73398c4` is test-only.

## Simplification audit (what was asked)
| Question | Answer |
|---|---|
| Smallest thing that works? | Yes, with a small excess (finding M2). There is no interface, factory or config. The constants are named (`TRANSFORMATION`, `GCM_IV_BYTES`, `GCM_TAG_BITS`, `AES_KEY_BITS`, `ANDROID_KEYSTORE`, `LEGACY_RESERVED_PREFIX`). |
| Is the `key: () -> SecretKey` seam justified? | Yes. AndroidKeyStore cannot run on the JVM, and the seam is also what lets the tests inject transient failures (`if (failOnce) ... throw KeyStoreException("hiccup")`). A lambda costs less than an interface with one implementation. |
| Is `SecurityPrefsModule` readable? | Yes. `openSecrets` is 20 lines. `migrateLegacySecrets` reads top to bottom, and its steps match the spec's 1-7. Small nit: the argument style is mixed (`newRaw, newName, { androidKeystoreAesKey(SECRETS_KEY_ALIAS) },` positional, then `log =` named). |
| Proguard leftovers | None. A grep of `*.pro` for security/tink/crypto finds nothing. |

## Minor issues

**M1 (P3, conf 9) Stale gradle comment stacked above the new one.** `app/build.gradle.kts:126-127`:
```
    // EncryptedSharedPreferences (publish secrets: GitHub PAT, Discord webhook URL)
    // security-crypto: read-only, migrates legacy ESP into KeystoreSecretStore; drop after phone confirms
```
Line 126 is now wrong: ESP no longer holds the publish secrets at runtime, and it never covered only those (the settings secrets were in it too). Fix: delete line 126 and keep 127.

**M2 (P3, conf 8) Unused `SharedPreferences` surface is implemented, not thrown.** The production consumers call only `securePrefs.getString(...)` and `securePrefs.edit().putString(...).apply()/.commit()` (SettingsRepository L80-156, PublishSettingsRepository L55-80, checked by grep). Yet the store implements `getAll`, `contains`, `remove`, `clear` and put-null, with a `private var cleared = false` flag, plus 3 tests that cover them. All of this was specified (spec "also supports `remove` and `clear`", "`getAll` returns decrypted Strings"), so it is not drift, but it is about 20 lines of YAGNI against D1's own rule ("Unused surface ... throws UnsupportedOperationException"). Not worth churning now. If the store is ever touched again, consider making `getAll`/`clear` throw.

**M3 (P3, conf 9) The "apply" path is silently synchronous.** `override fun apply() { write() }` calls `write()`, which `return editor.commit()`. This deliberately diverges from the platform contract (the Tester flagged it, and it is safe because the repos call from coroutines), but nothing in the code says so. A later "make apply async" change would break D13's atomic-or-nothing logging. Add a one-line why-comment.

**M4 (P3, conf 9) Backup-XML and Fake kdoc comments still describe the old behaviour.** `full_backup_content.xml:7-8` (and both copies in `data_extraction_rules.xml`) still say `ciphertext is useless without this device's Keystore master key; restoring it crashes on decrypt.` That is no longer true: `openOld` failure is caught (`log("legacy store unreadable ...; keeping it"); return newStore`). `FakeSecurityPrefsModule.kt:21-22` still says `It also needs the Keystore for EncryptedSharedPreferences`. Neither is harmful, but both mislead whoever does the drop change. Fold them into that change (see S1).

**M5 (P3, conf 9) Misleading test comment. The apply() half of the D13 test is weaker than it reads.** `KeystoreSecretStoreTest.kt:113`: `calls = 1 // second encrypt of the apply() attempt fails again`. With `if (++calls == 2) throw`, setting `calls = 1` makes the FIRST encrypt of the apply() fail, not the second. So the apply() half never exercises "first value encrypted, second failed" (the partial-write case). The commit() half does, and S7 went red on that half (`mutations.log:28`), so coverage holds. Fix the comment, or set `calls = 0` so apply() also fails on the second value.

**M6 (P3, conf 8) Near-tautological tests.** (a) `logged exceptions never carry the plaintext` and `empty legacy value migrates and logs carry no secret` assert that a value is absent from messages, but every log message is built from constant templates (`"blob too short"`, `"migrated ${toCopy.size} of ${entries.size}"`), and the real log sinks in `openSecrets` (`Log.w(TAG, "$newName value unreadable (${it::class.java.simpleName})")`) are never exercised. No plausible mutation turns these red. They are cheap regression guards, so keep them, but don't count them as evidence. (b) `assertEquals("", s.getString("gemini_api_key", "") ?: "")`: the `?: ""` would also pass if the store returned null. Drop the elvis.

## Test-quality audit (the questions you asked)
- **Palindrome false-green:** fixed. `fillOld()` uses `"gem-1"`/`"ant-2"`, and `.reversed()` changes both. N5 and N5b are red on `read-back mismatch serves old and rolls back copied keys` (`mutations.log:42,44`). Note that `write failure serves old ... leaves new empty` cannot prove rollback (encryption fails before any write, so `newRaw` is empty either way). Only the read-back test covers rollback, which is enough.
- **Process-death test:** sound. `deleteOld = { throw death }` with an `AssertionError` gets past `catch (e: Exception)`, so rollback cannot run. If `fail(...)` fires, its AssertionError is caught and then `assertSame(death, e)` fails, so the test is not tautological. Nit: it uses fully-qualified `org.junit.Assert.fail` instead of an import.
- **Duplication between the two test classes:** minimal. Both repeat `KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()`, and `"AAAA"` appears as the "bad blob" in both. The migration tests deliberately use a real store, because D11 depends on real decrypt semantics. A shared helper isn't worth adding for one line.
- **Mutation evidence:** strong. `mutations.log` has 16 entries, each with named FAILED lines plus `BASELINE after revert: exit=0`. The Tester independently re-proved 8 (`mutations-tester.log`). The Tester's 8 added tests were not mutation-checked, which is fine because they are edge-case additions.
- **Instrumented test cleanup:** correct in scope. It deletes only `"test_secrets_$id"` and never touches the real aliases. Nits: (a) `@Before setUp` deletes a prefs file whose name is a fresh UUID per test instance, which cannot exist, so it is dead code. (b) `ivIsTwelveBytes` asserts the total blob size `12 + 6 + 16`, so the name overclaims. (c) Verify on the emulator: if a test fails before the key is created, `deleteEntry` on an absent alias may throw in `@After` and hide the real failure. [Guessing] Behaviour varies by API level, and I could not check it here. Confidence 5. Still uncompiled (PKIX), and Skyler owns that run.

## Suggestions for follow-up

**S1 (P2, conf 9) The drop-security-crypto checklist is incomplete.** The spec's gaps.md text lists only `drops securityCrypto (toml + gradle) and openOld, deletes the legacy files unconditionally, and optionally deletes the _androidx_security_master_key_ entry`. Removing `openOld` actually makes all of the following dead code, and they must go in the same change:
1. `migrateLegacySecrets` and `LEGACY_RESERVED_PREFIX` (SecurityPrefsModule.kt:65-112), the `EncryptedSharedPreferences`/`MasterKey` imports (L10-11), and the `openSecrets` call to the migration (it returns `store` directly).
2. All 12 tests in `SecurityPrefsModuleTest.kt` (the class becomes empty, so delete the file).
3. `build.gradle.kts:126-128` and both toml lines (`securityCrypto`, `androidx-security-crypto`).
4. The legacy excludes and their comments in both backup XMLs (3 blocks). Keep them until the unconditional delete has shipped once, or drop them in the same change: decide which.
5. The `FakeSecurityPrefsModule` kdoc (M4).
6. Gate: `KeystoreSecretStoreInstrumentedTest` must have compiled and passed first (spec, Smallest shippable increment).
Recommend that the orchestrator writes this list into the gaps.md entry instead of the 3-item summary.

**S2 (P3)** Tidy-ups: `log: (Exception) -> Unit` on the store and `log: (String) -> Unit` on the migration are two shapes for the same purpose in one package. This is acceptable because the store's caller formats the message. No action needed.

## What looks good
- `decryptOrNull` handles the three expected exception families in one place. `getString` and `getAll` share it.
- `write()` encrypts first and then makes one raw editor pass. This is exactly D13, and S7 proves it.
- `androidKeystoreAesKey`'s kdoc explains why it never regenerates, so the comment says why rather than what.
- Deleting `createOrResetOnDecryptFailure` and its 5 tests is a net simplification, not just a move.

## Score (lens 3)
| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | Every task is traced to the diff with no creep. -1: the gradle comment was added beside the stale line instead of replacing it (M1). |
| Correctness & bug-freedom | 24/25 | No production bug found in this lens. -1: the instrumented test was never compiled, so the real-Keystore paths are unproven. |
| Security & reliability | 19/20 | Not this lens's focus. Logs carry no values, and the backup excludes are complete. -1: the stale "crashes on decrypt" comments misdescribe failure behaviour (M4). |
| Maintainability & simplification | 12/15 | Small and named, and the seam is justified. -1 unused SharedPreferences surface (M2), -1 stale comments (M1/M4), -0.5 undocumented synchronous `apply` (M3), -0.5 incomplete drop-change checklist (S1). |
| Test evidence quality | 13/15 | 16 mutations, each with a named red test in a sidecar, independently re-proved. -1 misleading D13 comment plus weak apply() half (M5), -1 near-tautological log tests and `?: ""` (M6). |
| **Total** | **92/100** | Ship: score >= 85 and no P0. |
