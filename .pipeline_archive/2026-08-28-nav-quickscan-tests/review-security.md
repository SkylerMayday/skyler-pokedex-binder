# Code Review — Lens 2 of 3: Security + Reliability

**Reviewer:** dev-team-pipeline Stage 5 (Reviewer), lens = security + reliability
**Date:** 2026-08-28
**Branch:** `test/nav-and-quickscan-coverage` (uncommitted working tree; no worktree used)
**Scope reviewed:**
- Tracked diff: `app/build.gradle.kts` (+2/-1), `gradle/libs.versions.toml` (+1), `app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt` (+345)
- Untracked new files: `app/src/androidTest/java/com/skyler/pokedexbinder/CustomTestRunner.kt`, `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt`, `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationRouteTest.kt`
- Read for context (not in diff): `AppNavigation.kt`, `MainActivity.kt`, `PokedexBinderApp.kt`, `AndroidManifest.xml`, `DatabaseModule.kt`, `SettingsRepository.kt`, `PersonalCollectionViewModel.kt`, `PersonalCollectionScreen.kt`, `MainBinderViewModel.kt`, `BackfillCardNamesUseCase.kt`, `PublishViewModel.kt`, `DatabaseModuleWiringTest.kt`, generated manifests under `app/build/intermediates/`

**No prior `.pipeline/review-*.md` exists** — this is the first review pass of this loop, not a re-review.

---

## Summary

The security half of this lens is **clean and verified**: the `testInstrumentationRunner` swap is provably confined to the `androidTest` APK, `hilt-android-testing` is a legitimate first-party Google/Dagger artifact at the already-pinned version, and no test path in the diff can reach the GitHub PAT, the publish flow, or any webhook. The **reliability half is not clean**: `AppNavigationScreenTest` runs against the app's real Hilt graph with zero test-module substitution, so on a live `connectedAndroidTest` run it reads/writes the real on-device `pokedex_binder.db` and the real `app_settings` DataStore, fires real pokemontcg.io network requests, and asserts on a persisted user-mutable setting as if it were a compile-time constant. Two of the six instrumented tests are non-hermetic in ways that will produce environment-dependent failures or a Compose idling hang the first time Skyler actually runs them.

**Recommendation:** Request changes (needs-changes)

**Critical issues:** 0
**Important issues (P1):** 3
**Minor issues (P2/P3):** 3
**Verified-clean (explicitly checked, no finding):** 4

---

## Lens question 1 — Can the `CustomTestRunner` / `HiltTestApplication` swap leak into a release build?

**Verdict: No. Verified at the artifact level, not inferred.** No finding.

The change is:

```kotlin
-        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
+        testInstrumentationRunner = "com.skyler.pokedexbinder.CustomTestRunner"
```
(`app/build.gradle.kts`, `defaultConfig`)

Three independent confirmations:

1. **The runner only ever lands in the test APK's manifest.** `app/build/intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml` contains:
   ```xml
   <instrumentation
       android:name="com.skyler.pokedexbinder.CustomTestRunner"
   ```
   while the app-under-test manifest regenerated **after** this change (`packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml`, mtime `Aug 28 12:25`) still declares only:
   ```xml
   <application
       android:name="com.skyler.pokedexbinder.PokedexBinderApp"
   ```
   with no `<instrumentation>` element at all. The release packaged manifest likewise declares `android:name="com.skyler.pokedexbinder.PokedexBinderApp"` and contains no instrumentation entry.

2. **The class exists only in `debugAndroidTest` outputs.** `grep -rl "CustomTestRunner" app/build` returns only `debugAndroidTest` artifacts (`manifest_merge_blame_file/debugAndroidTest/...`, `packaged_manifests/debugAndroidTest/...`, `kotlin/compileDebugAndroidTestKotlin/...`, `tmp/kotlin-classes/debugAndroidTest/com/skyler/pokedexbinder/CustomTestRunner.class`). Nothing under `release/` or the main debug variant.

3. **`HiltTestApplication` is not on any non-test classpath.** It arrives via `androidTestImplementation(libs.hilt.android.testing)` — a Gradle configuration that by definition contributes only to the `androidTest` variant. The source file itself lives at `app/src/androidTest/java/com/skyler/pokedexbinder/CustomTestRunner.kt`, i.e. the `androidTest` source set, which is never compiled into the app APK.

Additionally, `PokedexBinderApp` is a bare class — `class PokedexBinderApp : Application()` with no `onCreate` override — so swapping it out during instrumentation loses no production initialization, which is what makes the existing non-Hilt instrumented tests genuinely unaffected. That claim in `CustomTestRunner.kt`'s doc comment ("existing non-Hilt instrumented tests ... are unaffected by this runner swap") is accurate.

---

## Lens question 3 — Is `hilt-android-testing` a supply-chain risk?

**Verdict: No. Verified against the resolved artifact.** No finding.

```toml
hilt-android-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }
```

Resolved POM at `~/.gradle/caches/modules-2/files-2.1/com.google.dagger/hilt-android-testing/2.51.1/.../hilt-android-testing-2.51.1.pom`:

```xml
<groupId>com.google.dagger</groupId>
<artifactId>hilt-android-testing</artifactId>
<version>2.51.1</version>
<url>https://github.com/google/dagger</url>
<organization><name>Google, Inc.</name></organization>
<licenses><license><name>Apache 2.0</name>...
```

First-party Google/Dagger, Apache 2.0, same `group` and same `version.ref = "hilt"` (2.51.1) already used by the project's existing `hilt-android` and `hilt-compiler` entries — no new version introduced. Full transitive set is `dagger`, `dagger-lint-aar`, `hilt-android`, `hilt-core`, `jsr305`, `androidx.{activity,annotation,fragment,lifecycle-*,multidex}`, `androidx.test:core`, `javax.inject`, `junit` — all standard, no unexpected or obscure transitive. (Minor precision note for the record: this is a Google/Dagger artifact, not an AndroidX one; the `changes.md` phrasing "standard first-party AndroidX/Hilt testing artifact" is loose but not materially wrong.)

`kspAndroidTest(libs.hilt.compiler)` reuses the already-present compiler artifact at the same version — correct and no new supply-chain surface.

---

## Lens question 2 — Does reusing the real Hilt graph create live external side effects?

**Partly. No credential/publish/webhook exposure (verified clean), but real network and real user-data writes are genuinely reachable.**

### VERIFIED CLEAN: no GitHub PAT / publish / webhook / Gemini-key exposure

The app does have a credential surface (`PublishSettingsRepository` persists publish config in a `"publish_settings"` DataStore; `GitHubApi.kt`, `DiscordApi.kt`, `GeminiCardScanner.kt` exist). I checked whether any of the six instrumented tests can reach it. They cannot:

- `MainBinderScreen.kt:48` does construct the publish ViewModel on every composition — `val publishVm: PublishViewModel = hiltViewModel()` — but `PublishViewModel` has **no `init` block**; its only entry point is `fun startPublish()`, gated behind `if (showPublish)` / `LaunchedEffect(Unit) { publishVm.startPublish() }`, which requires an explicit click on `contentDescription = "Publish"`. No test clicks it.
- No test navigates to `Screen.Settings`, `Screen.Scanner`, or any publish/restore dialog.

So the answer to "could a `connectedAndroidTest` run fire a real GitHub write or webhook" is **no**. Worth stating explicitly since it was the sharpest version of the question.

### P1-1 — The instrumented test reads and writes the real on-device database and DataStore, contradicting the project's own documented safety stance

**File:** `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt` (whole file — no test module replacement anywhere)
**Confidence:** 9

The test file's own doc comment states the design intent:

> `Reuses the app's real Hilt graph via [MainActivity] (already @AndroidEntryPoint) -- no fake DI bindings needed`

There is no `@UninstallModules`, no `@BindValue`, no test `DatabaseModule`. So `DatabaseModule.provideDatabase` runs unmodified:

```kotlin
private const val DB_FILE_NAME = "pokedex_binder.db"

@Provides
@Singleton
fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase =
    buildDatabase(context, DB_FILE_NAME)
```

Instrumented tests execute in the app-under-test's process, so `@ApplicationContext` resolves to the real data dir — meaning the real `pokedex_binder.db` and the real `"app_settings"` / `"publish_settings"` DataStores. Launching `MainActivity` then runs `MainBinderViewModel.init`, which is all **writes**:

```kotlin
init {
    viewModelScope.launch {
        binderRepository.seedIfEmpty(context)
        binderRepository.seedAlternateFormsIfMissing()
        binderRepository.seedMegasIfMissing()
        binderRepository.migrateSlotNamesIfNeeded()
        backfillCardNamesUseCase.backfill()
    }
}
```

Why this matters beyond "the app does that anyway": **two files already in this repo explicitly document that this is the thing to avoid.** `DatabaseModule.kt`'s own `@VisibleForTesting` rationale:

> `this project has no Hilt test infra (no hilt-android-testing dependency, no HiltTestApplication), and calling [provideDatabase] directly in a test would operate on the real on-device pokedex_binder.db file, which is unacceptable for a feature whose entire purpose is protecting that file from data loss.`

and `DatabaseModuleWiringTest.kt`:

> `Deliberately does NOT use [DatabaseModule.provideDatabase] itself or Hilt's test runner: ... [DatabaseModule.provideDatabase] hardcodes the real pokedex_binder.db file name — calling it directly in a test would operate on the actual on-device database if this ever runs on a device where the app is already installed, which is exactly the data this feature exists to protect.`

This diff builds exactly the infrastructure those comments said didn't exist, and then uses it in the way those comments said was unacceptable — without acknowledging the reversal anywhere in `specs.md`, `changes.md`, or `test-results.md`. There is also no mitigation configured: `app/build.gradle.kts` has **no `testOptions` block at all** (grep for `testOptions|clearPackageData|orchestrator|animationsDisabled` returns nothing), so there is no Android Test Orchestrator and no `clearPackageData = true` — app data is neither isolated nor reset between tests.

By inspection the specific writes are idempotent/non-destructive (`seedIfEmpty`, `...IfMissing`, `migrateSlotNamesIfNeeded`, and a backfill whose doc says "Non-destructive, idempotent, self-terminating"), so I am **not** claiming data loss is likely. The finding is that a decision the codebase twice documented as off-limits has been quietly reversed, with `fallbackToDestructiveMigration()` / `fallbackToDestructiveMigrationOnDowngrade()` live in the same builder, and with no stated acceptance of that risk.

**Suggested fix (pick one):** (a) add `@UninstallModules(DatabaseModule::class)` + a test module binding an in-memory / test-file-named database, which the already-`@VisibleForTesting internal fun buildDatabase(context, dbFileName)` seam exists precisely to enable; or (b) if running against real data is a deliberate accepted risk, say so explicitly in `specs.md`/`changes.md` and update the two now-false doc comments so a future reader isn't misled.

### P1-2 — Navigating to Personal Collection fires real network requests and can hang the Compose idling clock

**File:** `AppNavigationScreenTest.kt:100-105`
**Confidence:** 8

```kotlin
@Test
fun drawerPersonalCollectionItemNavigatesToPersonalCollectionScreen() {
    composeTestRule.onNodeWithContentDescription("Menu").performClick()
    composeTestRule.onNodeWithText("Personal Collection").performClick()

    assertOnScreenTextExists("Personal Collection")
}
```

`PersonalCollectionViewModel` is the one destination ViewModel with an eager remote path:

```kotlin
init {
    viewModelScope.launch {
        if (repository.cacheCount() == 0) refreshAll()
    }
}
```

and `refreshAll()` fans out to `repository.refreshPokemon(section.key, section.queryNames)` across 4 sections, which calls `cardSearchRepository.searchByName(name)` — real HTTP. On a fresh AVD or a freshly-installed CI device the cache is always empty, so this always fires.

The reliability problem compounds: while refreshing with no cached cards, `PersonalCollectionScreen.kt:85-88` renders

```kotlin
if (state.isRefreshing && !hasAnyCards) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
```

`CircularProgressIndicator` is an indefinite animation. `assertOnScreenTextExists` calls `fetchSemanticsNodes()`, which synchronizes on Compose idleness — and an indefinite animation keeps the Compose clock non-idle. This is the canonical Compose-test hang/`ComposeNotIdleException` shape. Best case the test is merely network-dependent and slow; worst case it times out on a device with connectivity, and passes only when the device is *offline* (failure path clears `isRefreshing`, removing the indicator).

Note this directly falsifies `specs.md`'s risk-table mitigation, which assumed the problem away:

> `Confirmed during planning: MainBinderViewModel.init only does local DB seeding (seedIfEmpty, migrateSlotNamesIfNeeded, etc.), no network calls`

That's true of `MainBinderViewModel` — but the same risk row's escape hatch ("the drawer/bottom-nav assertions only require the **start destination** to render, not every destination") does not apply, because this test deliberately navigates *to* the network-eager destination.

**Suggested fix:** seed the personal-collection cache before navigating (so `cacheCount() != 0`), or replace the real `PersonalCollectionRepository` binding for this test, or assert against a semantics node that doesn't require full idle sync.

### P1-3 — `bottomNavShowsPokedexAndHidesScanByDefault` asserts a persisted user setting as if it were a constant

**File:** `AppNavigationScreenTest.kt:123-128`
**Confidence:** 9

```kotlin
@Test
fun bottomNavShowsPokedexAndHidesScanByDefault() {
    // Fresh DataStore default: useCameraScanner = false.
    assertOnScreenTextExists("Pokédex")
    composeTestRule.onNodeWithContentDescription("Scan").assertDoesNotExist()
}
```

The comment names the assumption exactly — and the assumption is only true on a device where the setting has never been changed. The gate is:

```kotlin
if (settings.useCameraScanner) {
    NavigationBarItem(
        ...
        icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Scan") },
```

and the value is persisted, not defaulted at runtime:

```kotlin
useCameraScanner = prefs[Keys.USE_CAMERA_SCANNER] ?: false
```
backed by `preferencesDataStore(name = "app_settings")` in the real app data dir (see P1-1 — no `clearPackageData`, no test DataStore substitution). Any device where the camera scanner has been switched on — which, given this branch's own recent history is dominated by scanner work (`fix: scanner false-positive capture`, `fix: scanner auto-capture raced ahead of focus convergence`), is a live possibility on Skyler's own phone — fails this test for a reason that has nothing to do with nav wiring.

**Suggested fix:** write the setting explicitly in `@Before` via an injected `SettingsRepository` (or a test DataStore), then assert — turning an environment assumption into a controlled precondition.

---

## Lens question 4 — Reliability of the new test code itself (hangs, leaks, cross-test state)

### JVM tests: clean. No finding.

`QuickScanViewModelTest.kt` handles dispatcher lifecycle correctly and symmetrically:

```kotlin
@Before
fun setUp() { Dispatchers.setMain(testDispatcher) }

@After
fun tearDown() { Dispatchers.resetMain() }
```

Mocks are instance fields (fresh per JUnit4 test instance), there are no `mockkStatic`/`mockkObject` calls needing `unmockkAll`, no resource handles, no shared mutable statics. The captured-lambda idiom fails loudly rather than hanging if the stub never matches (`fastSearch.captured` throws when unset):

```kotlin
val fastSearch = slot<suspend () -> List<TcgCard>>()
every { cardSearchRepository.searchStreaming(any(), capture(fastSearch)) } returns
    flowOf(SearchProgress.Complete(emptyList()))
...
fastSearch.captured.invoke()
```

`AppNavigationRouteTest.kt` is pure string assertions with no I/O, no Android dependency, no shared state — nothing to leak.

### P2-1 — The drawer-order test can pass without the drawer ever opening

**File:** `AppNavigationScreenTest.kt:69-89`
**Confidence:** 8

The file's own doc comment establishes the premise that defeats the test:

> `A ModalNavigationDrawer's drawer content stays composed (just translated off-screen) even while closed`

and `test-results.md` §3 independently confirmed it against `material3-android:1.4.0` sources ("`drawerContent` is composed via a `Layout(...)` block that is **always part of the composition tree** — never conditionally removed"). Since the drawer's nodes exist in the semantics tree regardless of open/closed state, every lookup in this test resolves either way:

```kotlin
val connectingArtTop =
    composeTestRule.onNodeWithText("Connecting Art").fetchSemanticsNode().boundsInRoot.top
...
assertEquals("drawer items must render top-to-bottom in the declared order", tops, tops.sorted())
```

The `pokedexTop` disambiguation uses `.minOf { it.boundsInRoot.top }`, which picks the drawer's entry over the bottom-nav one whether the drawer is open or closed (both are near the top-ish vs. bottom bar). So if `onNodeWithContentDescription("Menu").performClick()` silently failed to open the drawer, the assertion would still pass. The test verifies *declaration order within the composition*, not *rendered on-screen order* — which is weaker than both the spec criterion ("appear in that exact top-to-bottom order") and the test's own name.

**Suggested fix:** assert the drawer is actually open first (e.g. `boundsInRoot.left >= 0f` on a drawer node, reusing the existing `assertOnScreenTextExists` helper) before doing the ordering comparison.

### P2-2 — Two in-repo doc comments are now factually false about this project's test infrastructure

**Files:** `app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt`, `app/src/androidTest/java/com/skyler/pokedexbinder/di/DatabaseModuleWiringTest.kt`
**Confidence:** 10

Both assert, verbatim:

> `this project has no Hilt test infra (no hilt-android-testing dependency, no HiltTestApplication)`

> `this project has no Hilt test infra (hilt-android-testing isn't a dependency, no HiltTestApplication)`

Both are false as of this diff. These aren't decorative comments — they are the stated *safety rationale* for a deliberate design choice about protecting the user's database. Leaving them means a future reader is actively misinformed that the unsafe path is structurally unreachable. I'm flagging rather than folding this into a fix request beyond a comment update, because touching `app/src/main/` would violate the spec's own "No production code file changes are present in the diff" criterion — this needs an explicit decision, not a silent edit.

### P3-1 — No `testOptions { animationsDisabled = true }` for the project's first UI test

**File:** `app/build.gradle.kts` (absence — `grep testOptions` returns nothing)
**Confidence:** 7

With the project gaining its first Compose UI instrumented test, there's no `testOptions` block disabling system animations. Compose's own test rule handles Compose animations, but system window/transition animations on the device are a well-known source of instrumented-test flakiness, and the drawer open/close is exactly the kind of transition affected. Low-cost hardening; not blocking on its own.

---

## Spec-compliance trace (contributes to my lens score)

| Acceptance criterion | Status | Evidence |
|---|---|---|
| `createRoute()` output matches route templates incl. URL-encoding | DONE | `AppNavigationRouteTest.kt:45-105` |
| QuickScan/Scanner defaults match `navArgument` defaults | DONE | `AppNavigationRouteTest.kt:73-95` |
| Instrumented test compiles against new Hilt-testing dep + runner | DONE | `test-results.md` §6, `--rerun-tasks`, `BUILD SUCCESSFUL` |
| All 5 drawer items exist in exact order | PARTIAL | `AppNavigationScreenTest.kt:69` — see P2-1; verifies declaration order, not rendered order |
| Each drawer item click → correct `TopAppBar` title | PARTIAL | 4 of 5 covered (`:92`, `:100`, `:107`, `:115`); the "Pokédex"/start-destination item has no click test |
| Scan absent by default, Pokédex present | DONE (fragile) | `AppNavigationScreenTest.kt:124` — see P1-3 |
| `testDebugUnitTest` all green, additive only | DONE | 226 tests / 24 files / 0 failures, counted from freshly regenerated XML |
| All 9 QuickScanViewModel behaviour criteria | DONE | 23 new `@Test` methods, cross-checked in `test-results.md` §4 |
| No production code file changes | DONE | `git diff --stat HEAD -- app/src/main/` empty; only `build.gradle.kts` + `libs.versions.toml`, both explicitly authorized by spec Task Group C1 |

**Scope creep:** none found. Every changed file traces to a named spec task.

---

## What looks good

- The release-isolation question has a genuinely clean answer, and it holds up at the artifact level rather than by convention.
- The Tester stage's rigor is above bar: bytecode extraction via `javap` to settle the `assertDoesNotExist` question, `material3-android` sources-jar extraction to check the drawer disambiguation, forced `--rerun-tasks` after catching an `UP-TO-DATE` cache hit, and direct XML test counting instead of trusting a printed summary.
- The JVM test additions are hermetic, correctly scoped, and genuinely exercise previously-dead code paths (the `fastSearch` lambda had never been invoked by any test).
- Every stage stated plainly and repeatedly that the instrumented test has never executed — no overclaiming anywhere.
- `CustomTestRunner.kt` is minimal and correct: the standard `newApplication` override, nothing extra.

---

## Score (Item I) — security + reliability lens

| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | **23 / 25** | Every requirement traces to real implementation; no scope creep. Two criteria land PARTIAL: only 4 of 5 drawer items get a click-navigation test, and the drawer-order criterion is satisfied more weakly than its wording implies (P2-1). Small proportional deduction. |
| Correctness & bug-freedom | **20 / 25** | No production-logic bugs (no production logic changed). The JVM tests are correct and verified against real source. Deduction is for a test whose assertion doesn't prove what it claims (P2-1, a genuine correctness defect in the new code) plus the untested 5th drawer item. |
| Security & reliability | **11 / 20** | Security half fully clean and verified: no release leak, legitimate first-party dependency, no PAT/publish/webhook reachability. Reliability half carries three P1s — real-DB/real-DataStore coupling that reverses a twice-documented safety decision (P1-1), real network plus a probable Compose idling hang (P1-2), and an assertion on persisted user state (P1-3). Three P1s in this lens's primary dimension take it roughly to half. |
| Maintainability & simplification | **12 / 15** | Documentation quality throughout is high and the code is minimal with no over-engineering. Deducted for two now-false safety-rationale comments left in place (P2-2), which actively mislead. |
| Test evidence quality | **12 / 15** | Verbatim, independently reproduced evidence for everything runnable — genuinely strong. Deducted because the headline deliverable has never executed (acknowledged and spec-sanctioned), and because no stage identified the hermeticity risks that will surface on first execution. |
| **Total** | **78 / 100** | |

**Prior iteration:** none (no `.pipeline/review-*.md` existed before this pass) — no delta to report.

---

## Verdict

**needs-changes**

Score 78 is below the 85 ship threshold, and three unresolved P1 findings sit in this lens's primary dimension. None are catastrophic and none touch production behaviour — but `AppNavigationScreenTest` as written is likely to fail or hang the first time it is actually run, which defeats the spec's own stated purpose ("a failing test the moment a drawer item is reordered ... so that nav regressions are caught before I tap through the app manually"). A test that can't be run reliably doesn't provide that safety net.

Minimum to reach ship on this lens:
1. **P1-1** — either substitute the database/DataStore bindings for the instrumented test (the `@VisibleForTesting buildDatabase(context, dbFileName)` seam already exists for exactly this), or explicitly record running-against-real-data as an accepted risk and correct the two contradicting doc comments.
2. **P1-2** — make `drawerPersonalCollectionItemNavigatesToPersonalCollectionScreen` not depend on a live network fetch and not sync against an indefinite progress animation.
3. **P1-3** — set `useCameraScanner` explicitly in `@Before` instead of assuming the persisted default.

P2-1 (drawer-order test can pass with the drawer closed) is a cheap fix worth folding into the same pass. P2-2 and P3-1 are fine to defer.

Explicitly **not** blocking, and verified clean: release-build isolation, the new dependency, and credential/webhook exposure.

**Sign-off:** Changes requested — security: pass; reliability: fail.
