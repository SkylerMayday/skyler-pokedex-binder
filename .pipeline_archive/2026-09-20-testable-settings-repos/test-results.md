# Test Results — DI-based testability for SettingsRepository / PublishSettingsRepository

**Deviation disclosed**: run by the orchestrator directly, not `pipeline-tester`. Same recurring
pattern as every other stage this session — the Coder's sandbox hit the Gradle daemon IPC wall,
orchestrator ran the identical commands directly.

## Result: 2 real bugs found (one severe), both root-caused and fixed — now 324/324 passing

### Bug 1 (severe) — indefinite hang, not a test failure

First run: the Gradle process ran for 21+ minutes with zero output (normal run time ~90s) before
being killed manually. No exception, no timeout, no progress — a genuine coroutine deadlock, not
slowness.

**Root cause**: both new test files built their `DataStore<Preferences>` in `@Before` via
`PreferenceDataStoreFactory.create(scope = testScope.backgroundScope, ...)`, where `testScope` was
a plain `TestScope()` class field. The actual test bodies ran under the top-level `runTest { }`
function, which creates its **own separate** `TestScope`/scheduler — completely disconnected from
the field-level `testScope` whose `backgroundScope` was handed to DataStore. DataStore's internal
actor coroutine, tied to a scheduler nothing ever drives, never got to run — so every suspend call
into DataStore (`.data.first()`, `.edit {}`) blocked forever waiting for a response that could never
arrive.

**Fix**: dropped the `TestScope`/`backgroundScope` override entirely — call
`PreferenceDataStoreFactory.create(produceFile = { ... })` with no explicit `scope`, which defaults
to a real `Dispatchers.IO`-backed `CoroutineScope`. This also matches `SecurityPrefsModule`'s own
production code, which never passed an explicit `scope` either — tests now use the same default
production does, eliminating the mismatch class of bug entirely rather than tuning around it.

### Bug 2 (real, Windows-specific) — `IOException` on the 2nd `.edit()` call per test

After fixing the hang, 5 of the 9 new tests failed with:
```
java.io.IOException: Unable to rename ...settings_test.preferences_pb.tmp to ...settings_test.preferences_pb.
This likely means that there are multiple instances of DataStore for this file.
```
**Root cause, confirmed via web research, not guessed**: this is a well-documented Windows-only bug
in AndroidX DataStore's real, file-backed implementation — calling `.edit()` more than once against
the same temp-file-backed `DataStore` instance in a JVM unit test can fail this way due to Windows'
stricter file-rename/locking semantics (a `.tmp`-file rename-over-existing-file that works fine on
Linux/Mac can fail on Windows). Confirmed the failure pattern matched exactly: every test calling
`.edit()` 2+ times (test's own pre-seed write + the migration's internal clear-write) failed;
every test calling it 0-1 times passed. Google's own `nowinandroid` reference app hit the identical
issue (GitHub PR #1542, "Fix Windows unit test failing because of DataStore threading issue") and
fixed it the same way this fix does: replace the real file-backed DataStore with an in-memory one
for JVM tests, not a retry/delay workaround.

**Fix**: wrote `InMemoryPreferencesDataStore.kt` — a real (not mocked) 2-method
`DataStore<Preferences>` implementation backed by a `MutableStateFlow` + `Mutex` for atomic
sequential updates, matching the real contract exactly. No new dependency (considered
`okio-fakefilesystem`, the other standard approach, but that requires adding a new test dependency
this task explicitly said to avoid — the hand-written in-memory store needed none). Both test files'
`@Before` now construct `InMemoryPreferencesDataStore()` instead of a real file-backed one via
`TemporaryFolder` — also removed the now-unnecessary `TemporaryFolder` JUnit rule and its imports
from both files, a net simplification, not just a fix.

## Verbatim evidence

Post-both-fixes:
```
BUILD SUCCESSFUL in 1m 22s
31 actionable tasks: 31 executed
```
Fresh JUnit XML aggregate: `tests=324 skipped=0 failures=0 errors=0` (315 baseline + 9 new: 5 in
`SettingsRepositoryTest`, 4 in `PublishSettingsRepositoryTest`; the 3 pre-existing pure
`shouldMigrateGeminiKey()` tests are included in that 9 as unchanged).

`assembleDebug lintDebug --rerun-tasks` (post-fix): `BUILD SUCCESSFUL`, 52/52 tasks, no lint errors.

## Acceptance criteria trace (against `.pipeline/specs.md`)

- [x] `SecurityPrefsModule.kt` exists, provides all 4 `@Named` bindings, byte-identical production
      construction logic (schemes, file names) — confirmed by reading the file.
- [x] Both repositories take injected `DataStore`/`SharedPreferences`; no `Context.dataStore`/
      `Context.publishDataStore` extension properties or inline `by lazy {...}` blocks remain.
- [x] All public method signatures unchanged — confirmed via `git status`: none of
      `ScannerViewModel.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `PublishRepository.kt`,
      `RestoreRepository.kt` appear in the diff.
- [x] `migrateGeminiKeyIfNeeded()`'s ordering, `Mutex`/`@Volatile` guard, `.commit()` unchanged —
      confirmed by reading the file, byte-for-byte identical to the pre-refactor version modulo
      field references.
- [x] `FakeSharedPreferences` is real (non-mock), implements the full interface.
- [x] `SettingsRepositoryTest` covers fresh install, upgrade/migration (both encrypted value AND
      cleared legacy key, read directly), repeated post-migration reads, `setGeminiApiKey`, and the
      `settings` Flow — all passing.
- [x] `PublishSettingsRepositoryTest` (new) covers `getConfig()` defaults and every setter's
      round-trip — all passing.
- [x] `324/324` tests, fresh XML. `assembleDebug`/`lintDebug --rerun-tasks` clean.
- [x] No new entries in `gradle/libs.versions.toml` or `app/build.gradle.kts` — confirmed, neither
      file appears in `git status`.

**Status: DONE.** No Stage 4 (Debugger) needed in the formal sense — both bugs were root-caused and
fixed directly by the orchestrator within this stage using real evidence (a live hang requiring
manual intervention, then a documented, externally-verified bug pattern), not deferred or guessed at.
