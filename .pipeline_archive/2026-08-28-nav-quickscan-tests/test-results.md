# Tester Stage — Re-Verification Report (Auto-loop, Pass 2)

**Overall status: PASS.** All 3 Must-fix and 6 Should-fix findings from `.pipeline/review-verdict.md`
(78/100 needs-changes) are genuinely closed, independently re-verified against the actual code and
fresh tool output — not `changes.md`'s claims taken on trust. Zero regressions. Zero production
code touched. Ready for Stage 5 re-review.

This report supersedes the pass-1 `test-results.md` in full.

---

## Scope of this pass

Per the task brief: independently re-verify (a) the 3 Must-fix items genuinely close the gaps
they claim to (not just "compiles"), (b) spot-check 2-3 Should-fix items against real code, (c)
confirm zero production files touched, (d) re-run all 7 Item M checks fresh.

Working directory: real repo checkout, branch `test/nav-and-quickscan-coverage` (no worktree used).

---

## Must-fix verification (read the real code, not the claims)

### 1. `FakeDatabaseModule.kt` genuinely replaces `DatabaseModule`, in-memory, all 5 DAOs — CONFIRMED

Read `app/src/androidTest/java/com/skyler/pokedexbinder/di/FakeDatabaseModule.kt` in full:

```kotlin
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object FakeDatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase =
        Room.inMemoryDatabaseBuilder(context, PokedexDatabase::class.java).build()
    @Provides fun provideMainBinderDao(db: PokedexDatabase): MainBinderDao = db.mainBinderDao()
    @Provides fun provideSecondaryBinderDao(db: PokedexDatabase): SecondaryBinderDao = db.secondaryBinderDao()
    @Provides fun provideConnectingArtDao(db: PokedexDatabase): ConnectingArtDao = db.connectingArtDao()
    @Provides fun providePersonalCollectionDao(db: PokedexDatabase): PersonalCollectionDao = db.personalCollectionDao()
    @Provides fun provideUnownBinderDao(db: PokedexDatabase): UnownBinderDao = db.unownBinderDao()
}
```

- `@TestInstallIn(replaces = [DatabaseModule::class])` is a genuine module **replacement**, not an
  additional coexisting binding — confirmed against `dagger.hilt.testing.TestInstallIn`'s real
  contract (the review itself already extracted this from the resolved AAR).
- `Room.inMemoryDatabaseBuilder(...)` — genuinely in-memory, no file path, confirmed by direct read.
- Read the real `app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt` side-by-side: it
  provides exactly 5 DAOs — `MainBinderDao`, `SecondaryBinderDao`, `ConnectingArtDao`,
  `PersonalCollectionDao`, `UnownBinderDao`. `FakeDatabaseModule` provides the identical 5, same
  names, same return types. No DAO missing, none extra. **Genuinely closes the gap.**

### 2. Personal Collection cache-seeding genuinely prevents `refreshAll()` from firing — CONFIRMED

Read `PersonalCollectionViewModel.kt`'s actual `init` block:
```kotlin
init {
    viewModelScope.launch {
        if (repository.cacheCount() == 0) refreshAll()
    }
}
```
Read `PersonalCollectionRepository.cacheCount()`: `= dao.cacheCount()`, a direct pass-through.
Read `PersonalCollectionDao.cacheCount()`: `@Query("SELECT COUNT(*) FROM personal_collection_cache") suspend fun cacheCount(): Int`.

`AppNavigationScreenTest`'s `@Before` calls `personalCollectionDao.upsertCache(...)` with 4 rows
(one per `PERSONAL_COLLECTION_SECTIONS` entry, imported directly from the production file — no
drift risk) into that exact table, via the same `@Inject`-ed DAO backed by `FakeDatabaseModule`'s
`@Singleton`-scoped in-memory database — the same instance the ViewModel's repository reads from.
`cacheCount()` will genuinely return 4, not 0. **`refreshAll()` (real network calls + indefinite
spinner) genuinely never fires.** Not superficial — traced the full call chain from DAO through
repository to the `init` guard.

**Timing note (checked, not assumed):** `PersonalCollectionViewModel` isn't instantiated at
Activity launch — it's created lazily by Compose Navigation only when the user actually navigates
to `PersonalCollectionScreen`, which happens later in each `@Test` method body, well after `@Before`
has already seeded the cache. No race exists here regardless of rule-ordering questions (see #3).

### 3. `useCameraScanner` fix — CONFIRMED correct, with one factual nuance worth flagging

Extracted `androidx.compose.ui:ui-test-junit4-android:1.11.3`'s real sources
(`AndroidComposeTestRule.android.kt`) from the Gradle cache to check the actual rule-application
order, per this project's `verify-vendored-api-via-sources-jar-extraction` lesson:

```kotlin
override fun apply(base: Statement, description: Description): Statement {
    val testWithDisposal = object : Statement() {
        override fun evaluate() { ... base.evaluate() ... }
    }
    return object : Statement() {
        override fun evaluate() {
            environment.runTest { activityRule.apply(testWithDisposal, description).evaluate() }
        }
    }
}
```

`base` here is the innermost JUnit statement — `RunBefores(@Test invocation, [@Before methods])`.
`activityRule` (`ActivityScenarioRule`) wraps `testWithDisposal` and, per its own documented
contract ("The Activity is normally launched by the given activityRule **before the test starts**"),
launches `MainActivity` (and therefore composes `AppNavigation()` for the first time) **before**
`testWithDisposal.evaluate()` — and therefore before `base.evaluate()` — runs. **This means the
`@Before` method (which calls `setUseCameraScanner(false)`) does NOT run before `MainActivity`'s
first composition** — it runs after the activity is already launched, contradicting the literal
"before Compose UI is first composed" framing in the task brief's own verification instruction.

This is **not a bug**, and does not undermine the fix, for a specific, checked reason: `settings
by settingsVm.settings.collectAsState()` in `AppNavigation.kt` (line 82) collects
`SettingsRepository.settings`, a `Flow` built on `context.dataStore.data.map { ... }` — genuinely
reactive, not read once. When `@Before` calls `setUseCameraScanner(false)` after the activity has
already launched, the DataStore emits a new value, `collectAsState()` triggers a recomposition, and
the immediately-following `composeTestRule.waitForIdle()` (present in the actual `@Before` body)
lets that recomposition settle before the `@Test` method's own assertions run. `changes.md`'s own
wording ("sets it explicitly... before any assertion runs... waitForIdle() ... so the Flow emission
has propagated before the test method's assertions run") is actually more precise than the task
brief's framing and does **not** overclaim "before composition" — it correctly scopes the claim to
"before assertions," which is the part that actually matters and which the reactive-Flow +
`waitForIdle()` pattern genuinely delivers. **Verdict: fix is correct and sufficient; the one thing
to flag is that a reader could misread "runs before any assertion" as "runs before first
composition" — they are different claims, and only the former is true and needed here.**

---

## Should-fix spot-checks (3 of 6, against real code)

- **#5 `DatabaseModuleWiringTest.kt` KDoc fix** — read the file: both now-false clauses removed,
  replaced with an accurate note that `FakeDatabaseModule` exists but this file intentionally still
  bypasses it (tests the real `buildDatabase` path directly). Comment-only, confirmed via `git diff`
  scope (only this file's docstring changed).
- **#7 route-placeholder test rename** — read `AppNavigationRouteTest.kt` lines 107-124: test
  renamed to `` `parameterized route templates contain their expected placeholder names` `` with an
  honest comment explaining it only checks `Screen`'s own template against a hardcoded literal, not
  `AppNavigation.kt`'s actual `navArgument(...)` declarations. Matches the review's accepted
  "rename for accuracy" resolution exactly.
- **#8 `selectCard` tests stub the slot lookup** — read `QuickScanViewModelTest.kt` lines 267-282:
  the second test now stubs `binderRepository.getSlotByPokemonId("target")` and
  `cardSearchRepository.searchStreaming`, and asserts `QuickScanState.NotFound("Bulbasaur")` as the
  real pre-`selectCard` state. Cross-checked against `QuickScanViewModel.kt`'s actual `init`/search
  logic: `SearchProgress.Complete` with an empty card list maps to `NotFound(tcgcsvQuery)` — the
  assertion is correct, not assumed.
- **#9 drawer-open guard** — read `AppNavigationScreenTest.kt` lines 124-151:
  `assertOnScreenTextExists("Connecting Art")` is the first assertion in
  `drawerShowsAllFiveItemsInTopToBottomOrder`, before the `tops` list is computed. Confirmed present
  exactly as described.
- **#4 start-destination assertion** — read `AppNavigationScreenTest.kt` lines 153-166: new test
  `drawerPokedexItemNavigatesToMainBinderScreen` navigates to Unown first, then back via the
  drawer's own "Pokédex" item (disambiguated via `clickDrawerPokedexItem()`, smallest
  `boundsInRoot.top`), then asserts `"Pokédex Binder"`. Confirmed present and non-tautological.
- **#6 dangling KDoc reference** — read `AppNavigationScreenTest.kt` line 25-30: `[AppNavigationRouteTest]`
  de-bracketed to plain prose. Confirmed.

All 6 Should-fix items genuinely present and correct on inspection, not just claimed.

---

## Fresh Item M verification (all 7, this pass — commands run via PowerShell `.ps1` + `-File`)

Environment: `$env:JAVA_HOME="D:\jdk17\jdk-17.0.14+7"`, `$env:TEMP`/`$env:TMP="C:\Windows\Temp"`.
All Gradle tasks run with `--rerun-tasks` (never trusted `UP-TO-DATE`, per this project's own
`delete-stale-test-results-dir-before-rerun` lesson). `app/build/test-results/testDebugUnitTest/`
deleted before the unit-test run.

### 1. Typecheck

`compileDebugAndroidTestKotlin --rerun-tasks` (isolated first, since this is the file that had the
one real bug in pass 1):
```
> Task :app:kspDebugAndroidTestKotlin
> Task :app:compileDebugAndroidTestKotlin
w: .../AppNavigationScreenTest.kt:61:27 'fun <reified A : ComponentActivity> createAndroidComposeRule...' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. ...
BUILD SUCCESSFUL in 1m
31 actionable tasks: 31 executed
```
(Deprecation warning only, pre-existing category — v1 `createAndroidComposeRule` still works, not
an error. Compiling the whole `androidTest` source set here also re-confirms the pre-existing
non-Hilt instrumented tests — Room migrations, `DatabaseModuleWiringTest` — still compile.)

`compileDebugUnitTestKotlin --rerun-tasks`: `BUILD SUCCESSFUL in 1m 3s` (part of the combined run
below).

### 2. Lint

`lintDebug --rerun-tasks`:
```
BUILD SUCCESSFUL in 1m 34s
```
```
$ grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml
0
```

### 3. Scoped test run

Deleted `app/build/test-results/testDebugUnitTest/` first. `testDebugUnitTest --rerun-tasks`:
```
BUILD SUCCESSFUL in 1m 22s
```
Counted directly from the freshly regenerated XML (not the runner's own summary):
```
files: 24
tests: 226
failures: 0
errors: 0
```
Matches `changes.md`'s reported count exactly (24 files / 226 tests / 0 failures — identical to
pass 1, expected since the auto-loop's only JVM-suite-affecting change was fix #8, which edited
existing test bodies rather than adding new `@Test` methods).

`grep -c "@Test" app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt`
→ **7** (was 6 pre-auto-loop; fix #4 added one). Not run — no AVD/emulator in this sandbox, same
standing constraint as every other instrumented test in this project, stated plainly, not
overclaimed as passing.

### 4. Production build

`assembleDebug --rerun-tasks`:
```
BUILD SUCCESSFUL in 1m 8s
```

### 5. Dev/start server

N/A — native Android app.

### 6. No stray debug output

```
$ grep -rn "println(\|Log\.d(\|TODO\|FIXME" \
  app/src/androidTest/java/com/skyler/pokedexbinder/di/FakeDatabaseModule.kt \
  app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt \
  app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationRouteTest.kt \
  app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt \
  app/src/androidTest/java/com/skyler/pokedexbinder/di/DatabaseModuleWiringTest.kt
grep exit code: 1
```
No matches (exit 1 = clean).

### 7. `git status` shows only intended files

```
 M app/build.gradle.kts
 M app/src/androidTest/java/com/skyler/pokedexbinder/di/DatabaseModuleWiringTest.kt
 M app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt
 M gradle/libs.versions.toml
?? .pipeline_archive/
?? .serena/
?? app/src/androidTest/java/com/skyler/pokedexbinder/CustomTestRunner.kt
?? app/src/androidTest/java/com/skyler/pokedexbinder/di/FakeDatabaseModule.kt
?? app/src/androidTest/java/com/skyler/pokedexbinder/ui/
?? app/src/test/java/com/skyler/pokedexbinder/ui/navigation/
?? docs/specs/2026-08-09-binder-backup-export-import.md
?? docs/specs/2026-08-27-card-grading-estimate.md
```
The two `docs/specs/*.md` and `.pipeline_archive/`/`.serena/` entries are pre-existing, untouched
by this stage (confirmed present at session start per `changes.md`'s own note).

`git diff app/build.gradle.kts gradle/libs.versions.toml` — confirmed both changes are exactly the
two spec-authorized test-scope additions (`testInstrumentationRunner` swap,
`hilt-android-testing`/`kspAndroidTest(libs.hilt.compiler)`), nothing else.

**Production-code boundary — the acceptance criterion the whole spec turns on:**
```
$ git diff --stat HEAD -- app/src/main/
(empty)
```
**Zero production files touched.** Confirmed fresh, this pass, not inherited from `changes.md`'s claim.

---

## Coverage assessment against `specs.md`'s acceptance criteria

- Task Group A (QuickScanViewModel expansion) — all 12 planned test cases present and passing
  (226 total tests in the JVM suite includes the QuickScan expansion + the 12-test route file);
  spot-checked #8's fix independently against real `QuickScanViewModel` logic. **Met.**
- Task Group B (nav route JVM test) — `AppNavigationRouteTest.kt`, 12 tests, all passing as part
  of the 226. Route uniqueness, exact route strings, `createRoute()` encoding (including the named
  `!`/`?`/space/`.` edge cases), and the (honestly-scoped) placeholder-name check. **Met.**
- Task Group C (Hilt instrumented test infra + `AppNavigationScreenTest`) — compiles cleanly,
  structurally sound, DB/network/settings isolation genuinely verified against real production code
  (not just "compiles"). Cannot be run in this sandbox (no AVD) — stated plainly, not overclaimed.
  **Met to the extent this sandbox can verify; real pass/fail is pending Skyler's own
  `connectedAndroidTest` run, as the spec itself anticipated.**
- "No production code file changes" acceptance criterion — **met**, confirmed via empty
  `git diff --stat HEAD -- app/src/main/`.

---

## Test coverage gaps / residual risk (for the Reviewer and for Skyler)

1. **`AppNavigationScreenTest.kt` has never actually executed.** Everything in this report about
   its correctness is structural/traced verification (reading real production code, tracing DAO →
   repository → ViewModel call chains, extracting and reading the actual `AndroidComposeTestRule`
   library source) — not a real JVM/instrumentation run. The disambiguation logic
   (`boundsInRoot.left >= 0f` for closed-drawer content) is a documented assumption about
   `ModalNavigationDrawer`'s current Material3 1.4.0 layout behavior; it has not been confirmed
   against real rendered semantics. First real signal will be Skyler's own `connectedAndroidTest`.
2. **The route-placeholder test (#7) cannot catch a `navArgument` rename left un-synced with the
   route template** — explicitly and honestly documented in both the test's own comment and this
   report; a genuine cross-check would require extracting placeholder names into shared constants,
   which is a production change correctly left out of this coverage-only pass.
3. **Optional-polish items from `review-verdict.md` were not addressed** (redundant
  `static routes are all unique` test, two dead helper parameters, `boundsInRoot.left >= 0f`
  dependency-on-material3-version not recorded as an inline code comment) — explicitly marked
  "only if trivial alongside the above, do not spend a full extra pass" in the review; reasonable
  to leave for a future pass, not a blocking gap.

---

## Friction Notes

- The `verify-vendored-api-via-sources-jar-extraction` technique (Gradle-cached `-sources.jar` →
  extract → read) worked cleanly a second time in this same project, this time for
  `androidx.compose.ui:ui-test-junit4-android`'s `AndroidComposeTestRule` — confirming rule-apply
  ordering (`ActivityScenarioRule` launches the Activity before `@Before` methods run) that would
  otherwise have been guessed from memory/training data. Reusable pattern for any future "does this
  JUnit rule/test-framework construct run before or after X" question in Android instrumented tests.
- The task brief's own verification instruction #3 ("confirm the fix runs BEFORE Compose UI is
  first composed") was based on a premise that turned out to be factually false for this specific
  Compose test rule (the Activity launches, and therefore composes, before `@Before` runs) — but the
  fix is still correct because of Flow-based reactive state + `waitForIdle()`. Worth remembering as
  a general pattern for future Compose instrumented-test review: "does @Before run before first
  composition" is usually the wrong question for reactive (Flow/StateFlow-collected) UI state — the
  right question is "does the state update reactively, and is there a synchronization point
  (`waitForIdle()`) before assertions." Not filing this as a standalone lesson — narrow enough to
  note here rather than generalize globally.
