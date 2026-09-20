# Changes: DI-based testability for SettingsRepository / PublishSettingsRepository

Implemented `.pipeline/specs.md` exactly (5 areas). No scope creep — no other file needed to change,
confirmed by diff.

## Files

### New: `app/src/main/java/com/skyler/pokedexbinder/di/SecurityPrefsModule.kt`
4 `@Provides @Singleton @Named(...)` bindings, verbatim from spec: `settingsDataStore`,
`publishDataStore` (both via `PreferenceDataStoreFactory.create` + `preferencesDataStoreFile`),
`settingsSecrets`, `publishSecrets` (both `EncryptedSharedPreferences.create` with
AES256_SIV/AES256_GCM, same file names as before: `settings_secrets`, `publish_secrets`).

### Modified: `SettingsRepository.kt`
- Constructor now takes `@Named("settingsDataStore") DataStore<Preferences>` and
  `@Named("settingsSecrets") SharedPreferences`; removed `@ApplicationContext Context` param.
- Deleted the `Context.dataStore` extension property and the `securePrefs by lazy { ... }` block.
- All 9 `context.dataStore` references → `dataStore`.
- `migrateGeminiKeyIfNeeded()` body byte-for-byte unchanged (read→write-via-`.commit()`→clear
  ordering, `Mutex`+`@Volatile` guard) — only the two field references changed what they point to.
- `getGeminiApiKey()`/`setGeminiApiKey()` signatures unchanged.

### Modified: `PublishSettingsRepository.kt`
Identical treatment: constructor takes `@Named("publishDataStore")`/`@Named("publishSecrets")`,
`Context.publishDataStore` extension + `securePrefs by lazy` block deleted, 5 references switched to
`dataStore`, `@ApplicationContext Context` removed. All 7 public method signatures unchanged
(`getConfig`, `setGithubOwner`, `setGithubRepo`, `setGithubPat`, `setDiscordWebhookUrl`,
`setPublishPokedex`, `setPublishCardHistory`).

### New: `app/src/test/java/com/skyler/pokedexbinder/repository/FakeSharedPreferences.kt`
Verbatim from spec — real map-backed `SharedPreferences`/`Editor`, no mocking framework.

### Modified: `SettingsRepositoryTest.kt`
Kept the 3 existing pure `shouldMigrateGeminiKey()` tests unchanged. Added a `TemporaryFolder`-backed
real `DataStore` + `FakeSharedPreferences` harness (fresh instances per test in `@Before`, per spec's
leak-prevention risk note) with 5 new cases: fresh install (empty key, no crash), upgrade migration
(asserts both the new encrypted value AND the cleared legacy DataStore key, read directly), repeated
post-migration reads (correctness-twice, per spec's own honesty note about no call-count hook without
a mock), `setGeminiApiKey` round-trip, and the `settings` Flow surfacing the migrated key plus
defaults for every other field.

### New: `PublishSettingsRepositoryTest.kt`
Same harness pattern, no migration to test: `getConfig()` defaults
(`publishPokedex=true`/`publishCardHistory=false`/empty strings), owner/repo round-trip, PAT/webhook
round-trip asserted both via `getConfig()` and directly against `FakeSharedPreferences` under their
real key names (`github_pat`, `discord_webhook_url`), and the two boolean setters' round-trip.

## Simplification/Review pass

Ran through the coding skill's Phase 5 lenses inline (no subagent tools available in this stage):
- **Reuse**: `FakeSharedPreferences` used verbatim from the spec (no existing equivalent fixture in
  the codebase to reuse instead — checked `FocalLengthsKeyTestFixture.kt`/`RectTestFixture.kt`, both
  unrelated). New test files follow the same `TemporaryFolder` + `TestScope` idiom the spec specifies,
  no invented alternative.
- **Quality**: No stringly-typed drift — `@Named` strings match `SecurityPrefsModule.kt` exactly by
  direct comparison. No nested conditionals introduced. No dead code/unused imports left behind
  (checked both modified files for leftover `context` references — none).
- **Efficiency**: No behavioral change to the migration algorithm's I/O pattern; `dataStore`/
  `securePrefs` are now Hilt-provided singletons instead of per-repository lazy/extension-property
  singletons — same singleton lifetime, no added allocation.
- **Correctness/Conventions**: `NetworkModule.kt`'s exact `@Provides @Singleton @Named` pattern
  followed for the new module. Confirmed via grep that no other file in `app/src` constructs
  `SettingsRepository(...)`/`PublishSettingsRepository(...)` directly outside the two new/updated
  test files, and that `git diff --name-only` shows only the 2 intended repository files plus the
  3 test-file changes — none of `ScannerViewModel.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`,
  `PublishRepository.kt`, `RestoreRepository.kt` appear.

Nothing needed fixing beyond the direct-to-spec implementation — no findings to apply.

## Verification (Item M, all 7 checks — full output in `.pipeline/evidence/changes-verify.log`)

1. **Typecheck**: No standalone Kotlin typecheck task separate from Gradle compilation exists;
   Gradle itself was blocked (see #3). Manual line-by-line review of all 6 changed/added files
   confirmed import/signature consistency. Best-effort substitute, not a compiler-verified pass.
2. **Lint**: N/A — no ktlint/detekt configured in this project (grepped all `*.gradle.kts`, zero
   matches).
3. **Scoped test run** (`testDebugUnitTest --rerun-tasks`): **Blocked** by the documented Gradle
   daemon IPC wall. 3 attempts (default daemon ×2, `--no-daemon` ×1), all failed identically:
   ```
   FAILURE: Build failed with an exception.
   * What went wrong:
   java.io.IOException: Unable to establish loopback connection
   ```
   Per the task brief, this sandbox's daemon wall hits subagents more reliably than the orchestrator
   — stopping here as instructed; the orchestrator is expected to verify directly. New test code
   was re-read end-to-end for logical correctness (harness construction order, assertion targets,
   key-name literals matching `SecureKeys`/`Keys` objects) as the best available substitute.
4. **Production build**: N/A for this subagent — same Gradle daemon wall as #3 (not task-specific,
   it's a JVM-fork/loopback-IPC failure that would recur identically for `assembleDebug`). Not
   reattempted separately. Orchestrator verifies directly.
5. **Dev/start server boot**: N/A — Android app, no dev/start server.
6. **Stray debug statements / scratch files**: Grepped all 6 changed/added files for
   `console.log|Log\.d|println|debugger` — zero matches. The `.ps1` build script and its log output
   live in the session scratchpad directory, not the repo.
7. **`git status` scope check**: Exactly the 6 expected files — 3 modified
   (`PublishSettingsRepository.kt`, `SettingsRepository.kt`, `SettingsRepositoryTest.kt`), 3 new
   (`SecurityPrefsModule.kt`, `FakeSharedPreferences.kt`, `PublishSettingsRepositoryTest.kt`).
   Nothing accidental.

**New-dependency check (Item P)**: N/A — no manifest changed, no new dependency added (spec's own
"Dependencies: None new" confirmed; `gradle/libs.versions.toml` and `app/build.gradle.kts` untouched
per `git status`).

## Friction Notes

- Serena's Kotlin language server failed to initialize in this environment ("Error extracting
  archive") on the very first symbolic edit attempt — fell back to the standard Read/Edit tools for
  all Kotlin file changes in this stage. Worth flagging upstream: Serena's setup docs/tooling should
  either pre-validate the Kotlin LSP archive extraction or fail with a more actionable message,
  since this blocked the mandated Serena-first workflow for an entire language in this project.
- The known Gradle daemon IPC ("loopback connection") wall reproduced exactly as described in the
  task brief — 3 attempts (2 default-daemon, 1 `--no-daemon`) all failed identically. `--no-daemon`
  does not route around it since Gradle still forks a single-use worker JVM that communicates over
  the same loopback mechanism. Confirms the orchestrator-verifies-directly fallback is the correct
  call here, not a sign the implementation itself is broken.

**Update (Tester stage, orchestrator-run — see `.pipeline/test-results.md`)**: the Gradle wall
cleared for the orchestrator as usual, but surfaced 2 real bugs this diff introduced, one severe
(an indefinite hang from a `TestScope`/`backgroundScope` mismatch between `@Before`-time DataStore
construction and each test's own `runTest {}` scheduler — required killing the process manually
after 21+ minutes) and one Windows-specific (a documented AndroidX DataStore file-rename bug when
`.edit()` is called 2+ times against a real temp-file-backed instance in one test, confirmed via web
research to match Google's own `nowinandroid` team hitting the identical issue). Both fixed by the
orchestrator: dropped the `TestScope` override (defaults to the same real scope production already
uses), and added a new `InMemoryPreferencesDataStore.kt` test fixture (real, not mocked — a 2-method
`DataStore<Preferences>` implementation) to avoid real file I/O in tests entirely, removing the
`TemporaryFolder` rule from both test files as a result. After both fixes: `testDebugUnitTest
--rerun-tasks`: **324/324 passing, fresh XML**; `assembleDebug lintDebug --rerun-tasks`: `BUILD
SUCCESSFUL`.
