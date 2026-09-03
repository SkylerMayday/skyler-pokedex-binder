# Coder Stage — Changes

**Branch:** `test/nav-and-quickscan-coverage` (off `master`)
**Spec:** `.pipeline/specs.md` — nav route/drawer coverage + QuickScanViewModel gap-fill

**Note on provenance:** this stage ran across two agent instances. The first hit the account's
monthly spend limit mid-task and was cut off before writing this file (per the session's own
`check-subagent-artifacts-before-rerunning` lesson, its on-disk work was inspected before deciding
how to proceed — it had already written real, substantially-complete code). The orchestrator
picked up from there directly: diagnosed and fixed the one real compile blocker left behind
(details below), ran full verification, and wrote this record.

---

## What changed and why

### 1. `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationRouteTest.kt` (new)

JVM-level test for `Screen`/`createRoute()` — the "route wiring" half of `gaps.md` gap #1. 12
tests: route uniqueness, exact route-string values, `createRoute()` URL-encoding for every
parameterized route (including real production edge cases: `!`/`?` as Unown letter IDs, "Mr. Mime"
as a Scanner `pokemonName`, space-encoding as `%20` not `+`), and a direct check that each route
template's placeholder names match what `AppNavigation.kt` actually declares via `navArgument(...)`
— the specific "route typo silently breaks deep-linking" risk the spec named. Fully JVM-testable,
no Android/Compose/Hilt dependency, runs in this sandbox.

### 2. `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt` (new)

Hilt-instrumented Compose test — the "drawer order / reachability" half of gap #1, which genuinely
requires composing the real UI tree (drawer `NavigationDrawerItem` calls are inline Composable
statements with no separately-testable data structure — confirmed in specs.md's Architecture
Decision). 6 tests: drawer items render top-to-bottom in declared order (Pokédex → Connecting Art →
Personal Collection → Unown → Card History), each of the 4 drawer-only destinations is reachable by
clicking its item, and — a direct callback to this session's own earlier finding — confirms the
bottom-nav Scan button is hidden by default on a fresh install (`useCameraScanner` DataStore
default is `false`).

Handles a real disambiguation problem: `ModalNavigationDrawer` keeps its content composed (just
translated off-screen) while closed, so "Pokédex" matches both the drawer item and the always-present
bottom-nav item. `assertOnScreenTextExists` filters to nodes with non-negative `boundsInRoot.left`
to disambiguate.

**This is the project's first Hilt-instrumented test** — required adding `CustomTestRunner.kt`
(swaps in `HiltTestApplication` via `AndroidJUnitRunner`, existing non-Hilt instrumented tests —
Room migrations, `DatabaseModuleWiringTest` — unaffected) and wiring `testInstrumentationRunner` in
`app/build.gradle.kts` to point at it, plus the new `hilt-android-testing` dependency
(`gradle/libs.versions.toml`) and `kspAndroidTest(libs.hilt.compiler)`.

**No emulator in this sandbox — compiles and is structurally correct, has never executed.**
Real execution (and confirming the drawer-order disambiguation actually holds against this exact
`ModalNavigationDrawer` version's live layout behavior) is pending Skyler's own device/emulator
run, same standing constraint as every other instrumented test in this project.

### 3. `app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt` (extended)

Added coverage for the three named gaps (slot-matching logic, manual entry flow, secondary-binder
fallback) plus one the Planner found independently: `search()`'s query-routing lambda had never
actually been invoked by any existing test, since `searchStreaming` was fully mocked in every
prior test. 345 lines added.

---

## Real bug found and fixed during this stage (flagged per spec's explicit instruction, not silently bundled)

The first agent's `AppNavigationScreenTest.kt` (as originally written) had:

```kotlin
import androidx.compose.ui.test.assertDoesNotExist
```

This failed `compileDebugAndroidTestKotlin` with `Unresolved reference 'assertDoesNotExist'` — the
first agent's partial output (before being cut off by the spend limit) concluded this was "a hard,
environment-wide TLS trust-chain wall," having apparently conflated an earlier, real, unrelated TLS
issue (first-time dependency fetch for the new `hilt-android-testing` artifact, same category of
machine-level JDK-truststore gap documented earlier this session, genuinely fixed by the standard
`GRADLE_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` workaround) with this separate,
unrelated compile error that surfaced afterward.

**Root-caused directly against the actual compiled bytecode** (same `-sources.jar`/AAR-extraction
technique this session established as reusable for exactly this kind of question, per
`verify-vendored-api-via-sources-jar-extraction`): extracted `ui-test.aar` from the Gradle cache
for `androidx.compose.ui:ui-test-android:1.11.3` (the version this session's earlier `composeBom`
bump — `2024.12.01` → `2026.06.00` — pulled in) and ran `javap` on the compiled
`SemanticsNodeInteraction.class`:

```
public final void assertDoesNotExist();
```

Confirmed: in this Compose UI version, `assertDoesNotExist()` is a genuine **instance member**
of `SemanticsNodeInteraction`, not a top-level extension function importable from the
`androidx.compose.ui.test` package the way it apparently was in whatever earlier Compose UI
version this pattern was written against. The import itself is simply invalid now — no import is
needed at all, since member methods resolve automatically once you have the receiver type.
**Fix: removed the one bad import line.** No logic change, no test behavior change, `assertDoesNotExist()` is
still called the same way (`.assertDoesNotExist()`, line 127) — it just doesn't need importing.

This is the first Compose UI *test* code written since this session's `composeBom` bump — the
bump's own verification at the time only checked `compileDebugKotlin` (main sources), which never
exercises `compose-ui-test` APIs at all. This is a genuine, previously-undiscovered downstream
consequence of that bump, now closed. Worth noting in case any *other* pre-existing Compose UI
test code in this project uses the same now-outdated import pattern — grepped: none does (this was
the first and only Compose UI instrumented test in the project).

---

## Verification (Item M — all 7 checks, fresh)

**1. Typecheck** — `compileDebugKotlin` (part of the full run below) and `compileDebugAndroidTestKotlin`
(run standalone first to isolate/fix the bug above): both `BUILD SUCCESSFUL` after the one-line fix.

**2. Lint** — `lintDebug`, fresh:
```
BUILD SUCCESSFUL in 2m 26s
```
`app/build/reports/lint-results-debug.xml`: `grep -c 'severity="Error"'` → **0**.

**3. Scoped test run** — `testDebugUnitTest --rerun-tasks`, stale `test-results/testDebugUnitTest/`
deleted first per this session's established process-fix, counted directly from regenerated XML:
```
24 result files (up from 23 baseline -- the new AppNavigationRouteTest class)
total tests: 226 (up from 191 baseline)
total failures: 0
```

**4. Production build** — `assembleDebug`, part of the same run: `BUILD SUCCESSFUL`.

**5. Dev/start server** — N/A, native Android app.

**6. No stray debug output** — `git diff`/new-file review shows no `println`/`Log.d`/commented-out
code; the only production-file changes are the two build-config lines (`testInstrumentationRunner`,
new test-only dependencies) — no app-logic files touched, matching the spec's explicit "test-coverage
only" scope.

**7. `git status` shows only intended files** —
```
 M app/build.gradle.kts
 M app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt
 M gradle/libs.versions.toml
?? app/src/androidTest/java/com/skyler/pokedexbinder/CustomTestRunner.kt
?? app/src/androidTest/java/com/skyler/pokedexbinder/ui/  (AppNavigationScreenTest.kt)
?? app/src/test/java/com/skyler/pokedexbinder/ui/navigation/  (AppNavigationRouteTest.kt)
```
plus the three pre-existing untracked entries from session start (`.pipeline_archive/`, `.serena/`,
two `docs/specs/*.md` files) — none touched by this stage.

## New-dependency check (Item P)

**One new dependency added:** `hilt-android-testing` (androidTest-scope only), matching the
already-pinned `hilt` version (`2.51.1`) used elsewhere in this project — same group
(`com.google.dagger`), same version, standard first-party AndroidX/Hilt testing artifact, no
license/maintenance concern. `kspAndroidTest(libs.hilt.compiler)` also added (androidTest-scope) to
generate the Hilt component for `@HiltAndroidTest`.

## Status

DONE. Ready for Stage 3 (Tester).

---

## Auto-loop correction (Item I — re-invocation after review score 78/100)

**Trigger:** `.pipeline/review-verdict.md` scored this diff 78/100 (needs-changes), driven by the
security-reliability lens (78) despite correctness (89) and maintainability (90) both clearing the
85 ship threshold. Three Must-fix (P1) findings and six Should-fix findings, detailed in that file
and the three per-lens review files. This section records the fixes for all nine; the original
"What changed and why" section above is left untouched as the record of what pass 1 verified.

### Must-fix

**1. `AppNavigationScreenTest` ran against the real on-device database (P1-1).**

New file `app/src/androidTest/java/com/skyler/pokedexbinder/di/FakeDatabaseModule.kt`:
`@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])`
providing `Room.inMemoryDatabaseBuilder(...)` plus all 5 DAOs `DatabaseModule` provides (a
`@TestInstallIn` module replacement must mirror the replaced module's whole `@Provides` surface,
not just the one method that mattered here). Confirmed via `dagger.hilt.testing.TestInstallIn`'s
actual class file (extracted from the already-resolved `hilt-android-testing-2.51.1.aar` in the
Gradle cache) rather than assumed from memory, per this project's own
`verify-vendored-api-via-sources-jar-extraction` lesson.

No manual seeding needed for the drawer/reachability assertions themselves: `MainBinderViewModel`'s
own `init` block already seeds the main binder from the bundled `pokemon_slots` resource on any
fresh/empty database (real production fresh-install behavior, not a test-only path), and every
other destination screen already renders a valid empty state on a genuinely empty database today.

`DatabaseModuleWiringTest.kt` is unaffected by this — it never goes through Hilt DI at all (no
`@HiltAndroidTest`, calls `DatabaseModule.buildDatabase` directly against a hardcoded test file
name), so `@TestInstallIn`'s replacement, which only applies to `@HiltAndroidTest` classes, has no
interaction with it.

**2. Personal Collection reachability test could fire real network calls / hang on an indefinite spinner (P1-2).**

`AppNavigationScreenTest`'s `@Before` now seeds `PersonalCollectionDao`'s cache table (one row per
`PERSONAL_COLLECTION_SECTIONS` entry, imported directly from `PersonalCollectionViewModel.kt` to
avoid drift) via the same in-memory database `FakeDatabaseModule` provides. This makes
`PersonalCollectionViewModel.init`'s `if (repository.cacheCount() == 0) refreshAll()` never fire —
`refreshAll()` is what makes the real `searchByName` network calls and renders the indefinite
`CircularProgressIndicator` that risked hanging Compose's idle-sync. Chosen over the review's
alternative suggestion (fake-binding `CardSearchRepository`/the repository chain) because
`PersonalCollectionRepository` is a concrete `@Inject constructor` class with no interface or
providing module to intercept — seeding the cache achieves the same "never touches the network"
guarantee using infrastructure fix #1 already required, without inventing a new fake-binding seam.

**3. `bottomNavShowsPokedexAndHidesScanByDefault` asserted a persisted setting as a constant (P1-3).**

`AppNavigationScreenTest`'s `@Before` now injects `SettingsRepository` and calls
`setUseCameraScanner(false)` explicitly before any assertion runs, instead of trusting the ambient
device/emulator DataStore value. `composeTestRule.waitForIdle()` added after the `runBlocking` seed
block so the setting's `Flow` emission has propagated before the test method's assertions run.

### Should-fix

**4. Missing start-destination `TopAppBar` title assertion.** Added
`drawerPokedexItemNavigatesToMainBinderScreen`: navigates to Unown first (so returning to the start
destination is a real navigation, not a tautology on the screen already showing), then opens the
drawer and clicks "Pokédex", then asserts `"Pokédex Binder"`. Clicking the drawer's own "Pokédex"
item (as opposed to the bottom-nav item with the same label) required a new private
`clickDrawerPokedexItem()` helper — `onNodeWithText("Pokédex")` is ambiguous once the drawer is
open (2 on-screen matches), so it reuses the same top-position disambiguation the pre-existing
`drawerShowsAllFiveItemsInTopToBottomOrder` test already established (drawer entry has the smaller
`boundsInRoot.top`).

**5. `DatabaseModuleWiringTest.kt`'s KDoc claimed no Hilt test infra exists.** Both now-false
clauses (`hilt-android-testing` isn't a dependency; no `HiltTestApplication`) removed; kept the
still-valid rationale (`provideDatabase` hardcodes the real db filename) and added a line
explaining why this test still doesn't use `FakeDatabaseModule` either (it validates the real
`buildDatabase` production path directly, which is the point of the test). Comment-only — no
change to the test's logic, matching the spec's own "flag, don't silently fold into `app/src/main/`"
constraint (this file is `androidTest`, not production, so editing it was in scope).

**6. Dangling `[AppNavigationRouteTest]` KDoc cross-reference.** `AppNavigationScreenTest.kt`'s
class KDoc de-bracketed the reference (plain prose "the JVM-level AppNavigationRouteTest class")
since that class lives in the `test` source set, unreachable from `androidTest`'s compile path.

**7. Route-placeholder test overclaimed what it checks.** Renamed
`` `parameterized route templates declare the same placeholder names AppNavigation wires as navArgument` ``
to `` `parameterized route templates contain their expected placeholder names` `` in
`AppNavigationRouteTest.kt`, and rewrote the comment to state plainly that this only checks
`Screen`'s own route template against a hardcoded literal — it does not read `AppNavigation.kt`'s
actual `navArgument(...)` declarations, so a `navArgument` rename with the route template left
untouched would still slip past it. A genuine cross-check would need those names extracted into
shared constants, which is a production change out of scope for a coverage-only pass (per the
maintainability review's own suggested fix, which offered rename-for-accuracy as a valid
alternative to strengthening).

**8. Two `selectCard` tests in `QuickScanViewModelTest.kt` passed incidentally.** Both now stub
`binderRepository.getSlotByPokemonId("target")` and `cardSearchRepository.searchStreaming` the way
every sibling test with a non-blank `slotId` already does. For the second test (`falls back to a
single-card list outside CardSelection`), this changes what "outside CardSelection" actually means:
with the stub in place, `init`'s auto-search now genuinely completes to `NotFound("Bulbasaur")`
before `selectCard` is called — a real, established non-`CardSelection` state from the real state
machine, not an incidental `relaxed = true` null-fallback no-op. Added an explicit
`assertEquals(QuickScanState.NotFound("Bulbasaur"), vm.state.value)` before calling `selectCard` so
the starting state is asserted, not assumed. (Verified this by hand-tracing `QuickScanViewModel`'s
actual `init`/`searchForSlot`/`runSearch` code against `UnconfinedTestDispatcher`'s synchronous
execution — not the `Idle` state the review's suggested-fix text used as a placeholder example,
which would have been factually wrong here given what the stub actually causes to happen.)

**9. Drawer-order test could pass without the drawer ever opening.** Added
`assertOnScreenTextExists("Connecting Art")` as the first assertion in
`drawerShowsAllFiveItemsInTopToBottomOrder`, before computing the `tops` list — "Connecting Art"
has no bottom-nav duplicate, so this only passes once the drawer is genuinely on-screen (reusing
the existing `boundsInRoot.left >= 0f` on-screen filter), closing the gap where a silently-failed
`Menu` click would previously have gone undetected.

### Verification (Item M — all 7 checks, re-run fresh)

**1. Typecheck** — three separate `--rerun-tasks` runs, each `BUILD SUCCESSFUL`:
`compileDebugKotlin` (1m 1s), `compileDebugAndroidTestKotlin` (1m 7s — compiles the *whole*
`androidTest` source set, so this also re-confirms the pre-existing non-Hilt instrumented tests
`Migration5to6Test`/`Migration8to9Test`/`PokedexDatabaseTest`/`DatabaseBackupManagerInstrumentedTest`/
`DatabaseModuleWiringTest` still compile unaffected by `FakeDatabaseModule`'s presence),
`compileDebugUnitTestKotlin` (1m 6s). Only pre-existing warnings (deprecated APIs, experimental
opt-ins) — none new, none blocking.

**2. Lint** — `lintDebug --rerun-tasks`: `BUILD SUCCESSFUL in 1m 38s`.
`grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml` → **0**.

**3. Scoped test run** — deleted `app/build/test-results/testDebugUnitTest/` first, then
`testDebugUnitTest --rerun-tasks`, counted directly from the freshly regenerated XML (not the
runner's own summary):
```
files: 24
total tests: 226
total failures: 0
total errors: 0
```
Identical to pass 1's count — expected, since fix #8 only modified two existing tests' bodies (no
new `@Test` methods added to the JVM suite) and all other fixes touched `androidTest` files, which
`testDebugUnitTest` doesn't run. `AppNavigationScreenTest.kt`'s `@Test` count went from 6 to 7
(fix #4's new test) — confirmed via `grep -c "@Test"`, not run (still no AVD/emulator in this
sandbox — same standing constraint, stated plainly, not overclaimed).

**4. Production build** — `assembleDebug --rerun-tasks`: `BUILD SUCCESSFUL in 1m 11s`.

**5. Dev/start server** — N/A, native Android app (unchanged from pass 1).

**6. No stray debug output** — grep for `println(`, `Log.d(`, `TODO`, `FIXME` across every file
touched in this auto-loop pass (`FakeDatabaseModule.kt`, `AppNavigationScreenTest.kt`,
`AppNavigationRouteTest.kt`, `QuickScanViewModelTest.kt`, `DatabaseModuleWiringTest.kt`) → exit 1,
no matches.

**7. `git status` shows only intended files:**
```
modified:   app/build.gradle.kts
modified:   app/src/androidTest/java/com/skyler/pokedexbinder/di/DatabaseModuleWiringTest.kt
modified:   app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt
modified:   gradle/libs.versions.toml

Untracked:
app/src/androidTest/java/com/skyler/pokedexbinder/CustomTestRunner.kt
app/src/androidTest/java/com/skyler/pokedexbinder/di/FakeDatabaseModule.kt   (new, fix #1)
app/src/androidTest/java/com/skyler/pokedexbinder/ui/                        (AppNavigationScreenTest.kt)
app/src/test/java/com/skyler/pokedexbinder/ui/navigation/                    (AppNavigationRouteTest.kt)
```
plus the same pre-existing untracked entries from session start
(`.pipeline_archive/`, `.serena/`, two `docs/specs/*.md` files) — none touched by this pass.

`git diff --stat HEAD -- app/src/main/` → **empty**. The "no production code changes" boundary from
`specs.md` still holds after this auto-loop pass — every fix landed in `androidTest`/`test`
source-set files or the two spec-authorized build-config files. `DatabaseModuleWiringTest.kt`
(fix #5) is `androidTest`, not `app/src/main/`, so editing its KDoc was in scope.

### New-dependency check (Item P)

No new dependency introduced in this auto-loop pass — `FakeDatabaseModule.kt` uses
`dagger.hilt.testing.TestInstallIn`, already available transitively from the `hilt-android-testing`
dependency added in pass 1 (checked and recorded there). Manifest files (`libs.versions.toml`,
`app/build.gradle.kts`) unchanged in this pass.

### Status

DONE. All 3 Must-fix and 6 Should-fix findings from `review-verdict.md` addressed. Ready for
Stage 5 re-review.

## Friction Notes

- Serena's Kotlin language server still fails to initialize for this project ("Error extracting
  archive" on `replace_content`), exactly as `specs.md`'s own Friction Notes documented from the
  planning pass. Fell back to the standard `Read`/`Edit`/`Grep`/`Bash` toolchain for every Kotlin
  file this pass, per the project's own already-recorded degradation — not a new finding, but worth
  a one-time fix attempt (clear whatever cached archive Serena's Kotlin LSP extracts) so future
  sessions on this project stop silently degrading to whole-file reads/edits.
- `@TestInstallIn(replaces = [X::class])` replaces a module's *entire* `@Provides` surface, not
  just the one method that motivated the replacement — easy to under-scope a fake module by porting
  over only the provider that mattered and missing the others `DatabaseModule` also exposed (the 4
  DAO providers here). Worth remembering as a general Hilt-testing gotcha beyond this project.
