# Spec: DI-based testability for SettingsRepository / PublishSettingsRepository

## TL;DR

`SettingsRepository` and `PublishSettingsRepository` build their `DataStore`/`EncryptedSharedPreferences`
storage inline from a bare `Context`, so no JVM unit test can substitute anything — only the pure
`shouldMigrateGeminiKey()` gate is tested today. Move both collaborators to constructor injection
(`@Named` qualifiers, following `NetworkModule.kt`'s exact pattern), add a `SecurityPrefsModule` for
production wiring, and add real (non-mock) behavioral tests using a map-backed `FakeSharedPreferences`
and AndroidX DataStore's own temp-file JVM testing pattern. No Robolectric, no dependency changes.

## Problem

Zero behavioral test coverage on the migration algorithm (`migrateGeminiKeyIfNeeded()`: legacy-read →
encrypted-write via `.commit()` → plaintext-clear, guarded by `Mutex`+`@Volatile`) or on either
repository's get/set round-trips. The class can't be constructed in a JVM test with substitutable
storage today because `DataStore`/`SharedPreferences` are built inline from `@ApplicationContext
Context`. Robolectric was considered and rejected this session — unreliable AndroidKeyStore shadow
support, and it would test the third-party encryption library, not this app's own migration logic.

## Proposal

### 1. New file — `app/src/main/java/com/skyler/pokedexbinder/di/SecurityPrefsModule.kt`

```kotlin
package com.skyler.pokedexbinder.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityPrefsModule {

    @Provides @Singleton @Named("settingsDataStore")
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("app_settings") })

    @Provides @Singleton @Named("publishDataStore")
    fun providePublishDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("publish_settings") })

    @Provides @Singleton @Named("settingsSecrets")
    fun provideSettingsSecurePrefs(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "settings_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Provides @Singleton @Named("publishSecrets")
    fun providePublishSecurePrefs(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "publish_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
```

**Verified against the actual jar** (`datastore-preferences-android-1.1.7-sources.jar`,
`datastore-preferences-core-android-1.1.7-sources.jar` in the local Gradle cache — not guessed):
`androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(corruptionHandler = null,
migrations = listOf(), scope = CoroutineScope(Dispatchers.IO + SupervisorJob()), produceFile: () ->
File)` and `androidx.datastore.preferences.preferencesDataStoreFile(name: String): File` (extension
on `Context`, delegates to `dataStoreFile("$name.preferences_pb")`) both exist exactly as needed in
1.1.7. No new dependency required — `datastore-preferences` is already declared in
`gradle/libs.versions.toml`. **Constraint resolved, not an open question.**

### 2. `SettingsRepository.kt` changes

- Constructor: add `@Named("settingsDataStore") private val dataStore: DataStore<Preferences>` and
  `@Named("settingsSecrets") private val securePrefs: SharedPreferences`.
- Delete the top-level `private val Context.dataStore by preferencesDataStore(...)` extension
  property (line 34) and the `securePrefs by lazy { EncryptedSharedPreferences.create(...) }` block
  (lines 66-74).
- Replace every `context.dataStore` reference in the class body (lines 89, 99, 106, 120, 124, 128,
  132, 136, 140) with the injected `dataStore` param.
- `@ApplicationContext private val context: Context` — checked: nothing else in the class reads
  `context` once `MasterKey.Builder(context)` moves into the Hilt module. **Remove it from the
  constructor.**
- `getGeminiApiKey()` / `setGeminiApiKey()` signatures: unchanged (3 external call sites depend on
  this — `ScannerViewModel.kt:70`, `SettingsViewModel.kt`, `SettingsScreen.kt`, confirmed by grep).
- `migrateGeminiKeyIfNeeded()`: algorithm, ordering, `Mutex`/`@Volatile` guard, `.commit()` for the
  encrypted write — byte-for-byte unchanged. Only the two field references change what they point to.

### 3. `PublishSettingsRepository.kt` changes — identical treatment

- Constructor: add `@Named("publishDataStore") private val dataStore: DataStore<Preferences>` and
  `@Named("publishSecrets") private val securePrefs: SharedPreferences`.
- Delete `private val Context.publishDataStore by preferencesDataStore(...)` (line 31) and the
  `securePrefs by lazy { ... }` block (lines 51-59).
- Replace every `context.publishDataStore` reference (lines 61, 77, 81, 93, 97) with `dataStore`.
- Remove `@ApplicationContext private val context: Context` from the constructor — nothing else uses
  it after the above (checked).
- Public method signatures unchanged: `getConfig()`, `setGithubOwner()`, `setGithubRepo()`,
  `setGithubPat()`, `setDiscordWebhookUrl()`, `setPublishPokedex()`, `setPublishCardHistory()`.
  Confirmed call sites: `PublishRepository.kt:100` and `RestoreRepository.kt:75` (both only call
  `getConfig()`). No other production callers.
- No migration logic in this class — nothing to preserve there.

### 4. New test fixture — `app/src/test/java/com/skyler/pokedexbinder/repository/FakeSharedPreferences.kt`

Follows the existing fixture-file convention (`ui/scanner/FocalLengthsKeyTestFixture.kt`,
`RectTestFixture.kt` — standalone file next to what it supports). Map-backed, real (not mocked):

```kotlin
package com.skyler.pokedexbinder.repository

import android.content.SharedPreferences

/** Real map-backed [SharedPreferences] for JVM tests — not a mock. commit()/apply() both flush
 * synchronously (accepted limit, no async disk-write timing to model on the JVM). */
class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var cleared = false
        private val removed = mutableSetOf<String>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { cleared = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (cleared) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
```

### 5. Tests

**`SettingsRepositoryTest.kt`** — keep existing `shouldMigrateGeminiKey()` tests unchanged. Add a
real-DataStore + `FakeSharedPreferences` harness:

```kotlin
@get:Rule val tempFolder = TemporaryFolder()
private val testScope = TestScope()
private lateinit var dataStore: DataStore<Preferences>
private lateinit var securePrefs: FakeSharedPreferences
private lateinit var repository: SettingsRepository

@Before
fun setUp() {
    dataStore = PreferenceDataStoreFactory.create(
        scope = testScope.backgroundScope,
        produceFile = { tempFolder.newFile("settings_test.preferences_pb") }
    )
    securePrefs = FakeSharedPreferences()
    repository = SettingsRepository(dataStore, securePrefs)
}
```

Cases (all `runTest`), constructing fresh `dataStore`/`securePrefs`/`repository` per test in
`setUp()` (never shared class-level state):
- Fresh install, both stores empty → `getGeminiApiKey()` returns `""`, no crash.
- Upgrade: `dataStore.edit { it[stringPreferencesKey("gemini_api_key")] = "legacy-key-123" }`, then
  `getGeminiApiKey()` returns `"legacy-key-123"`; assert `securePrefs.getString("gemini_api_key",
  null) == "legacy-key-123"`; assert `dataStore.data.first()[stringPreferencesKey("gemini_api_key")]`
  is null (read the real DataStore directly, not via the repository).
- Call `getGeminiApiKey()` twice post-migration → both calls return the same correct value (the
  honestly-testable assertion — a real DataStore instance gives no observable hook to prove "no read
  occurred" without a mock, so this asserts correctness twice rather than call-count).
- `setGeminiApiKey("new-key")` → `securePrefs` contains it; legacy DataStore key remains absent.
- `settings: Flow<AppSettings>` — pre-seed a legacy key, collect one emission via
  `repository.settings.first()`, assert `geminiApiKey == "legacy-key-123"` and other fields default.

**New `PublishSettingsRepositoryTest.kt`** — same `FakeSharedPreferences` + temp-file DataStore
harness (`"publish_settings.preferences_pb"`), no migration to test:
- `getConfig()` on empty stores → defaults (`publishPokedex == true`, `publishCardHistory == false`,
  empty strings elsewhere).
- `setGithubOwner()`/`setGithubRepo()` round-trip through `getConfig()`.
- `setGithubPat()`/`setDiscordWebhookUrl()` round-trip through `getConfig()` and land in the fake
  `SharedPreferences` under their real key names (`"github_pat"`, `"discord_webhook_url"`).
- `setPublishPokedex(false)` / `setPublishCardHistory(true)` round-trip through `getConfig()`.

## User stories

- As the maintainer, I want `SettingsRepository`'s migration algorithm covered by a real behavioral
  test so a future refactor can't silently reintroduce the "clear-before-write" data-loss bug the
  current ordering was written to prevent.
- As the maintainer, I want `PublishSettingsRepository` (currently untested at all) covered by basic
  round-trip tests so a typo in a key name or a broken setter is caught by `testDebugUnitTest`
  instead of at runtime on a real device.
- As the maintainer, I want this done without Robolectric so the test suite doesn't inherit a known
  unreliable AndroidKeyStore shadow dependency for testing this app's own logic.

## Acceptance criteria

- [ ] `SecurityPrefsModule.kt` exists, compiles, provides all 4 bindings with the exact `@Named`
      strings specified; app still builds/runs with real `EncryptedSharedPreferences`/`DataStore`
      behavior byte-identical to today (file names, AES256_SIV/AES256_GCM schemes).
- [ ] `SettingsRepository` and `PublishSettingsRepository` take injected `DataStore`/
      `SharedPreferences` via constructor; no `Context.dataStore`/`Context.publishDataStore`
      extension properties or inline `by lazy { EncryptedSharedPreferences.create(...) }` blocks
      remain in either file.
- [ ] `getGeminiApiKey()`, `setGeminiApiKey()`, and every public `PublishSettingsRepository` method
      keep their exact pre-change signatures — no call site in `ScannerViewModel.kt`,
      `SettingsViewModel.kt`, `SettingsScreen.kt`, `PublishRepository.kt`, `RestoreRepository.kt`
      needs to change.
- [ ] `migrateGeminiKeyIfNeeded()`'s read→write→clear ordering, `Mutex`+`@Volatile` guard, and
      `.commit()` (not `.apply()`) for the encrypted write are unchanged.
- [ ] `FakeSharedPreferences` is a real map-backed implementation (no mocking framework) implementing
      the full `SharedPreferences`/`Editor` interface.
- [ ] `SettingsRepositoryTest` covers: fresh install, upgrade/migration (asserting both the new
      encrypted value AND the cleared legacy value, read directly not via the repo), repeated
      post-migration reads, `setGeminiApiKey()`, and the `settings` Flow.
- [ ] `PublishSettingsRepositoryTest` is a new file covering `getConfig()` defaults and every setter's
      round-trip.
- [ ] `.\gradlew.bat testDebugUnitTest --rerun-tasks` passes with all new tests green (existing pure
      `shouldMigrateGeminiKey()` tests untouched and still passing).
- [ ] No new entries added to `gradle/libs.versions.toml` or `app/build.gradle.kts`.

## Out of scope

- Rewriting or second-guessing `migrateGeminiKeyIfNeeded()`'s algorithm — already reviewed and
  shipped this session; this spec is testability-only.
- Robolectric, `connectedDebugAndroidTest`, or any instrumented test.
- Any change to `EncryptedSharedPreferences`/`MasterKey` construction parameters or schemes.
- Testing real AndroidKeyStore/on-device encryption behavior — `FakeSharedPreferences` intentionally
  does not encrypt; that's the library's job, not this app's.
- Migrating other repositories in this codebase to the same DI pattern (only if a follow-up asks).

## Dependencies

- None new. `androidx.datastore:datastore-preferences` (`1.1.7`, already declared) supplies
  `PreferenceDataStoreFactory` and `Context.preferencesDataStoreFile()`. `junit:junit` (`4.13.2`,
  already declared) supplies `org.junit.rules.TemporaryFolder`. `kotlinx-coroutines-test` and
  `mockk` are already `testImplementation` in `app/build.gradle.kts` (mockk unaffected — `mockk<...>()`
  in `RestoreRepositoryTest.kt`/`PublishRepositoryTest.kt`/`MainBinderViewModelTest.kt` never
  invokes the real constructor, so new constructor params don't break them).

## Open questions

None — the flagged risk (whether `PreferenceDataStoreFactory` is reachable on DataStore 1.1.7) was
checked directly against the sources jars in the local Gradle cache and confirmed available; see
Proposal §1.

## Risks and mitigations

- **Risk:** deleting the `Context` constructor param breaks a Hilt binding elsewhere that assumed
  its presence. **Mitigation:** grepped all direct construction — none exists outside Hilt
  `@Inject`/tests; Hilt regenerates the binding from the new constructor signature automatically.
- **Risk:** `TestScope().backgroundScope` for the DataStore's internal `CoroutineScope` could leak
  a background write across test cases if shared. **Mitigation:** fresh `DataStore` (fresh temp
  file, fresh `TestScope`) per test in `@Before`, never a shared class-level `val`.
- **Risk:** `FakeSharedPreferences.apply()` being synchronous could mask a real ordering bug that
  only manifests with actual async disk I/O timing. **Mitigation:** accepted, documented limit — the
  migration's own `.commit()` call is already synchronous, so ordering is unaffected by the fake.

## Smallest shippable increment

`SecurityPrefsModule.kt` + the two repository constructor changes, with only the
migration-upgrade-path test (highest-value case: proves no data loss on read→write→clear) added to
`SettingsRepositoryTest.kt`. `FakeSharedPreferences`, the remaining `SettingsRepositoryTest` cases,
and the new `PublishSettingsRepositoryTest.kt` land as an immediate follow-up commit — but
`FakeSharedPreferences` is a hard dependency of even the smallest increment's test, so it ships
together with it, not deferred.

## Friction Notes

- API-signature confirmation (`PreferenceDataStoreFactory`, `preferencesDataStoreFile`) was done by
  extracting `-sources.jar` files straight from `~/.gradle/caches/modules-2/files-2.1/<group>/
  <artifact>/<version>/*/*-sources.jar` and `unzip -p`-ing the relevant `.kt` — faster and more
  certain than guessing from memory with no internet/SDK docs access. Worth reusing when a spec
  needs to confirm an exact third-party API signature before committing to it.
- The feature request's guessed signatures were all correct on verification — no rework needed.
