# Code Review — Lens 1 of 3: Correctness + Spec-Compliance Trace

**Reviewer:** Reviewer (dev-team-pipeline Stage 5, multi-lens run — correctness/spec-trace lens)
**Date:** 2026-08-28
**Branch:** `test/nav-and-quickscan-coverage` (uncommitted working tree, base `HEAD` = `20c74f3`)
**Spec:** `.pipeline/specs.md` — nav route/drawer coverage + `QuickScanViewModel` gap-fill
**Prior review iteration:** none — no `.pipeline/review-verdict.md` or `.pipeline/review-correctness.md` existed before this pass. First loop iteration.

---

## Scope reviewed

**Tracked diff (`git diff HEAD`)** — 3 files, +347 / −3:

| File | Change |
|---|---|
| `app/build.gradle.kts` | 4 lines: `testInstrumentationRunner` swap + 2 androidTest-scope dependency lines |
| `gradle/libs.versions.toml` | 1 line: `hilt-android-testing` catalog entry |
| `app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt` | +345 (6 → 29 `@Test`) |

**Untracked files reviewed separately** (inspected explicitly, per Phase 1):
- `app/src/androidTest/java/com/skyler/pokedexbinder/CustomTestRunner.kt` (new, 18 lines)
- `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt` (new, 129 lines)
- `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationRouteTest.kt` (new, 122 lines)

**Untracked files NOT attributable to this stage** (verified by mtime, not assumed): `docs/specs/2026-08-27-card-grading-estimate.md` (mtime `2026-08-27 21:53`), `docs/specs/2026-08-09-binder-backup-export-import.md`, `.pipeline_archive/`, `.serena/`. All predate this pipeline run (new test files are mtime `2026-08-28 02:34–12:12`). Out of scope, untouched.

**Repo-history check** (mandatory per Execution Style): `git log --all --oneline -- "**/AppNavigationScreenTest*" "**/AppNavigationRouteTest*"` → empty. Confirms the Planner's finding — no prior version of either file was ever committed, so there is no historical implementation to diff against or regress from. Both are genuinely net-new.

---

## Summary

This is a clean, correctly-scoped, test-only change. The one hard boundary — no production/app-logic edits — **holds absolutely**: `git diff --stat HEAD -- app/src/main/` returns empty, and every one of the 4 changed build-config lines is verbatim pre-authorized by `specs.md`'s Task Group C1. I hand-verified the new test assertions against real production source rather than against the tests' own comments, and every assertion I checked is correct. Two spec-trace/reliability gaps are worth Skyler's attention before he runs `connectedAndroidTest`, neither blocking.

**Recommendation:** **Ship** (with 1 Important and 3 Minor follow-ups, all deferrable).

**Critical issues:** 0
**Important issues:** 1
**Minor issues:** 3
**Suggestions:** 2

---

## Intent audit (Phase 2) — spec-compliance trace

### Hard scope boundary: PASS

The single line this task could not cross:

> `specs.md` line 89: *"No production code file changes are present in the diff unless a genuine bug was found and separately flagged (not silently fixed) — verified by the Reviewer stage."*

Verified independently, not taken from `test-results.md`:

```
$ git diff --stat HEAD -- app/src/main/
(empty)
```

The only tracked non-test changes:

```diff
-        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
+        testInstrumentationRunner = "com.skyler.pokedexbinder.CustomTestRunner"
...
+    androidTestImplementation(libs.hilt.android.testing)
+    kspAndroidTest(libs.hilt.compiler)
...
+hilt-android-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }
```

All four lines are pre-authorized verbatim by `specs.md` lines 309-310:

> *"add `hilt-android-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }` … add `androidTestImplementation(libs.hilt.android.testing)` and `kspAndroidTest(libs.hilt.compiler)` … change `testInstrumentationRunner` … to point at the new custom runner's fully-qualified name"*

Note for the orchestrator: the task brief described this as "two build-config lines" — the actual, spec-sanctioned count is **four** lines across two files (runner swap, two dependency declarations, one catalog entry). `kspAndroidTest(libs.hilt.compiler)` is not optional padding — it is what generates the Hilt component for `@HiltAndroidTest`, and it is named explicitly in the spec. No scope creep.

Secondary confirmation the runner swap is behaviorally safe: `PokedexBinderApp.kt` is `@HiltAndroidApp class PokedexBinderApp : Application()` — an empty body. Replacing it with `HiltTestApplication` in instrumented tests loses no initialization, so the risk `specs.md` line 178 named ("`testInstrumentationRunner` change breaks existing non-Hilt `androidTest` files") has no application-init component to it beyond the compile check the Tester already forced.

### Zero scope creep

No "while I was in there" edits found. The existing 6 `QuickScanViewModelTest` tests are untouched apart from the backward-compatible helper signature change:

```kotlin
private fun viewModel(slotId: String = "", replacing: Boolean = false) = QuickScanViewModel(
```

`replacing` is a new parameter with a default — all 6 pre-existing call sites (`viewModel()`) are unaffected. Verified against `git show HEAD:` for that file.

### Acceptance-criteria trace (every criterion → concrete hunk)

| # | Criterion (`specs.md` line) | Status | Evidence |
|---|---|---|---|
| 1 | 74 — every `createRoute()` matches its route template, incl. encoding | **DONE** | `AppNavigationRouteTest.kt:44-105`, 8 encoding/position tests |
| 2 | 75 — `QuickScan`/`Scanner` defaults match `navArgument` defaults | **DONE** | `AppNavigationRouteTest.kt:73-95` |
| 3 | 76 — instrumented test compiles against new Hilt dep + runner | **DONE** | `compileDebugAndroidTestKotlin --rerun-tasks` → `BUILD SUCCESSFUL` (Tester §6) |
| 4 | 77 — 5 drawer items exist in exact top-to-bottom order | **DONE** | `AppNavigationScreenTest.kt:68-89` |
| 5 | 78 — **each** drawer item clicked → its `TopAppBar` title present | **PARTIAL** | 4 of 5 covered (`:91-121`); no test for the "Pokédex" item → `"Pokédex Binder"`. See Important issue 1 |
| 6 | 79 — bottom-nav Scan absent by default, Pokédex present | **DONE (conditionally)** | `:123-128`; precondition not enforced — see Minor issue 1 |
| 7 | 80 — all existing tests still pass, additive only | **DONE** | Re-verified from on-disk XML myself: 24 files / **226 tests / 0 failures / 0 errors** |
| 8 | 81 — `search()` routes to dex/promo(+fallback)/name | **DONE** | `QuickScanViewModelTest.kt:168-220` |
| 9 | 82 — `init` auto-search via `effectiveSearchName`; null slot → Idle | **DONE** | `:224-247` |
| 10 | 83 — `selectCard` w/ slot: `CardConfirm`, no `assign` yet | **DONE** | `:251-271` |
| 11 | 84 — `selectCard` w/o slot: 0 / 1 / 2+ matches, case-insensitive | **DONE** | `:275-322` |
| 12 | 85 — `addToSecondary` field mapping + `?: ""` fallback | **DONE** | `:365-402` |
| 13 | 86 — `showManualEntry` prefills last query | **DONE** | `:406-416` |
| 14 | 87 — `confirmCustomCard` both branches | **DONE** | `:418-450` |
| 15 | 88 — `confirmCard`/`wrongCard`/`assignToSlot`/`retry`/`reset` | **DONE** | `:326-361`, `:454-488` |
| 16 | 89 — no production code changes | **DONE** | See "Hard scope boundary" above |

15 DONE / 1 PARTIAL / 0 NOT DONE / 0 UNVERIFIABLE-at-JVM-level. Every criterion traces to a concrete hunk.

**Why the one PARTIAL exists** (Phase 2 step 5 requires investigating, not just flagging): the uncovered item is the start destination, which is already rendered before the drawer opens. Most likely a deliberate-but-unstated judgment that clicking back to the screen you're already on is a no-op assertion. It isn't quite — `navigateTo()` runs `popUpTo(findStartDestination().id) { saveState = true }`, which is real navigation logic that could regress. Cheap to add; not worth blocking on.

---

## Important issues

### 1. AC line 78 is only 4/5 covered — no test asserts the "Pokédex" drawer item reaches `"Pokédex Binder"`

- **File:** `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt`
- **Line:** 91-121 (four `drawer*ItemNavigatesTo*` tests)
- **Confidence:** 9 — verified by reading both the spec criterion and every `@Test` in the file.
- **Motivating lines.** The criterion (`specs.md:78`):
  > *"**Given** each drawer item is clicked in turn, **when** the corresponding screen renders, **then** its unique `TopAppBar` title text (**"Pokédex Binder"**, "Connecting Art", "Personal Collection", "Unown", "Card History") is present, confirming route wiring is correct."*

  The string `"Pokédex Binder"` appears nowhere in the test file. The four tests present are:
  ```kotlin
  fun drawerConnectingArtItemNavigatesToConnectingArtScreen() {
  fun drawerPersonalCollectionItemNavigatesToPersonalCollectionScreen() {
  fun drawerUnownItemNavigatesToUnownScreen() {
  fun drawerCardHistoryItemNavigatesToSecondaryBinderScreen() {
  ```
  I confirmed `"Pokédex Binder"` is the real, unique title that would be asserted — `MainBinderScreen.kt:75`: `title = { Text("Pokédex Binder") },`
- **Issue:** the drawer's first item (`AppNavigation.kt:106-111`, `label = { Text("Pokédex") }`, `onClick = { closeDrawer(); navigateTo(Screen.MainBinder.route) }`) has no click-to-navigate coverage. A regression that pointed that item at the wrong route would be caught by no test in this diff.
- **Suggested fix (follow-up, non-blocking):** one more test mirroring the existing four — navigate away first (e.g. click "Unown"), then open the drawer, click "Pokédex", and `assertOnScreenTextExists("Pokédex Binder")`. Navigating away first is what makes it a real assertion rather than a tautology on the start destination.

---

## Minor issues

### 1. `bottomNavShowsPokedexAndHidesScanByDefault` assumes a fresh DataStore it never establishes

- **File:** `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt`
- **Line:** 123-128
- **Confidence:** 8 — the code paths are quoted below; downgraded from 9 only because it cannot be demonstrated in this sandbox (no AVD), so the failure is predicted from source rather than observed.
- **Motivating lines.** The test:
  ```kotlin
  fun bottomNavShowsPokedexAndHidesScanByDefault() {
      // Fresh DataStore default: useCameraScanner = false.
      assertOnScreenTextExists("Pokédex")
      composeTestRule.onNodeWithContentDescription("Scan").assertDoesNotExist()
  }
  ```
  The value is **persisted**, not constant — `SettingsRepository.kt:49`:
  ```kotlin
  useCameraScanner = prefs[Keys.USE_CAMERA_SCANNER] ?: false,
  ```
  and it is user-togglable — `SettingsScreen.kt:119`: `checked = settings.useCameraScanner,`. The gate itself is `AppNavigation.kt:148`: `if (settings.useCameraScanner) {`.
- **Issue:** the comment states a precondition the test does not create. `specs.md:79` phrases the criterion as *"**Given** a fresh `app_settings` DataStore (default `useCameraScanner = false`)"* — a given, not an assertion. On Skyler's own device/AVD, `connectedAndroidTest` runs against the installed app's persisted data directory; if he has ever switched the camera scanner on, this test fails spuriously and looks like a nav regression. That is a bad first impression for the project's first-ever Hilt instrumented test.
- **Suggested fix (follow-up):** clear the DataStore in `@Before` (delete `app_settings.preferences_pb` from the app context's `datastore` dir), or `@Uninstall`/`@BindValue` a fake `SettingsRepository` — the latter is more work but is what makes the "Given a fresh DataStore" precondition genuinely true rather than assumed. Until then, the test's own comment should say the precondition is *assumed*, not *established*.

### 2. `DatabaseModuleWiringTest`'s KDoc is now factually false, invalidated by this very diff

- **File:** `app/src/androidTest/java/com/skyler/pokedexbinder/di/DatabaseModuleWiringTest.kt`
- **Line:** ~30 (KDoc)
- **Confidence:** 10 — quoted verbatim from the current file, and the diff is what falsified it.
- **Motivating line:**
  > *"Deliberately does NOT use [DatabaseModule.provideDatabase] itself or Hilt's test runner: this project has **no Hilt test infra** (`hilt-android-testing` **isn't a dependency**, **no `HiltTestApplication`**)"*
- **Issue:** as of this diff, `hilt-android-testing` **is** a dependency (`libs.versions.toml`) and `HiltTestApplication` **is** the instrumented-test `Application` (`CustomTestRunner.kt:17`). The stated rationale for that test's design is now stale and will actively mislead the next person deciding whether to reach for Hilt in an instrumented test.
- **Suggested fix (follow-up, one-line comment edit):** reword to preserve the *still-valid* half of the rationale — `provideDatabase` hardcodes the real `pokedex_binder.db` filename, which is the real reason to call `buildDatabase` directly — and drop the now-false "no Hilt test infra" clause. Correctly **not** fixed in this diff: it is outside the stated scope, and the spec's rule is to flag rather than silently fold in.

### 3. `AppNavigationScreenTest`'s KDoc links a class that isn't on its compile path

- **File:** `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt`
- **Line:** 19
- **Confidence:** 9.
- **Motivating line:** ` * half is covered separately (and fully) by the JVM-level [AppNavigationRouteTest]. Drawer order`
- **Issue:** `AppNavigationRouteTest` lives in the `test` (JVM unit) source set; this file is in `androidTest`. The two source sets do not see each other, so the KDoc reference is dangling. Harmless at compile time (KDoc links aren't resolved by kotlinc), but it will not link in any generated docs and implies a relationship the compiler can't honour.
- **Suggested fix:** drop the brackets — plain prose reference.

---

## Bug hunt (Phase 4) — findings

I spot-checked the new assertions against real production source rather than trusting the tests' own comments. **No correctness bugs found in the new tests.** Specifically verified:

- **URL encoding.** `Screen.UnownSearch.createRoute` (`AppNavigation.kt:47-50`) is `URLEncoder.encode(letterId, "UTF-8").replace("+", "%20")`. Java's `URLEncoder` leaves only `a-zA-Z0-9`, `.`, `-`, `*`, `_` unescaped, so `"!"` → `%21`, `"?"` → `%3F`, `"A B"` → `A+B` → `A%20B`. The test's expected values (`:58-59`, `:64`) are all correct. `"Mr. Mime"` → `Mr.%20Mime` (`.` unreserved, space → `%20`) matches `:102`.
- **Fixture realism.** `UnownBinderRepository.LETTER_IDS` is `('A'..'Z') + listOf("!", "?")` — `!`/`?` are genuine production letter IDs, not invented test strings, so the encoding test covers real inputs.
- **Placeholder names** (`AppNavigationRouteTest.kt:113-120`) cross-checked against the actual `navArgument(...)` declarations I read directly in `AppNavigation.kt:256`, `:269-271`, `:280-284` — `slotId`, `letterId`, `pokemonName`, `replacing`, `isSecondary` all match, and the `defaultValue = ""` / `defaultValue = false` declarations match the expected default-route strings on `:76` and `:92`.
- **`effectiveSearchName` transform.** `PokemonSlot.kt:33`: `slotType == SlotType.REGIONAL -> name.substringAfter(" ")`. The test's `"Alolan Vulpix"` → asserts `searchStreaming(eq("Vulpix"), any())` — correct, and genuinely exercises the transform rather than a pass-through name.
- **`findMatchingSlots` semantics.** `QuickScanViewModel.kt:207`: `slot.name.lowercase().contains(name.lowercase())`. The multi-match fixture (`"Charizard"` + `"Mega Charizard X"`) really does produce 2 matches, and the case-insensitivity test (`"CHARIZARD"` slot vs `"charizard"` card) really does produce 1 — the 0/1/2+ branches are each hit by the branch they claim.
- **`SecondaryBinderEntry` exact-equality `coVerify` is safe.** I checked for a hidden timestamp/nondeterministic field that would make exact matching flaky — there is none: `id: Int = 0`, `position: Int = 0`, `language: String = "EN"` are the only defaulted fields, and production supplies none of them either. The `coVerify` at `:372-381` is deterministic.
- **`fastSearch` capture idiom.** `slot<suspend () -> List<TcgCard>>()` + `capture(...)` + `.invoke()` correctly forces the previously-never-invoked lambda built at `QuickScanViewModel.kt:79-87` to actually run. The `.ifEmpty` fallback branch (`:84`) is covered by a dedicated test, and the non-fallback case correctly asserts `coVerify(exactly = 0) { ... searchByName(any()) }`.
- **Drawer-order disambiguation.** `assertOnScreenTextExists` filters `boundsInRoot.left >= 0f`. I confirmed the assertions aren't tautological: with the drawer closed the drawer sheet sits at negative x, so if navigation silently failed, the count would be 0 and the test would fail — it is a real check, not a pass-by-construction.

**Not found:** off-by-one, null-handling, async/race, resource-leak, or data-integrity bugs in the new code. Reported honestly rather than padded.

### Observations that did not clear the confidence gate (Phase 5), recorded for transparency

- **Unstubbed `init` path in two tests, confidence 5 — not raised as a finding.** `QuickScanViewModelTest.kt:253` (`val vm = viewModel(slotId = "target", replacing = true)`) and `:265` construct a ViewModel with a non-blank `slotId` without stubbing `binderRepository.getSlotByPokemonId`, relying on `mockk(relaxed = true)` behaviour to make `init`'s `if (slot != null)` branch benign. I could not demonstrate a concrete failure: the freshly-generated result XML for this class shows `tests="29" failures="0" errors="0"` and **zero bytes** of `system-out`/`system-err`, so nothing is being swallowed noisily today. Flagging it as a bug would be speculation. Worth knowing only if these tests are later extended.
- **`assertEquals(tops, tops.sorted())`** (`AppNavigationScreenTest.kt:88`) passes on ties as well as strict ordering. Degenerate in practice (two drawer rows can't share a `top`), so not a finding.

---

## Dimension pass (Phase 3)

| Dimension | Pass | Notes |
|---|---|---|
| Correctness | Yes | Assertions verified against real production source, not test comments. One env-dependent precondition (Minor 1). |
| Security | N/A | No security surface: no auth, network, serialization, secrets, or user input introduced. Only new dependency is `com.google.dagger:hilt-android-testing` at the already-pinned `hilt = 2.51.1`, androidTest-scope only — not in the shipped APK. |
| Performance | N/A | Test-only. Suite went 191 → 226 tests with no runtime concern; `testDebugUnitTest` + lint + `assembleDebug` completed in 1m55s. |
| Reliability | Mostly | Minor 1 is the one real reliability concern, and it is confined to a test that cannot run in this sandbox. |
| Maintainability | Yes | Reuses the existing `viewModel()`/`card()` fixtures, adds a focused `pokemonSlot()` helper, section-comments group related tests, comments explain *why* (e.g. the `ModalNavigationDrawer` disambiguation rationale) rather than restating code. Docked only for Minor 2/3. |
| Agent-Native Architecture | **Skipped — does not apply** | Checked before skipping, per brief: the diff touches no MCP server, no agent-callable API, no skill/agent definition, and `specs.md` contains no agent/AI integration requirement. Forcing this dimension onto a JVM test diff would be noise. |

---

## What looks good

- **The scope discipline is the headline.** A task whose entire premise is "add tests, touch nothing else" is easy to fail by one convenience edit. `git diff --stat HEAD -- app/src/main/` being empty, with all four build-config lines pre-authorized by name in the spec, is exactly right.
- **The `assertDoesNotExist` bug was handled the way the spec demanded** — root-caused against actual compiled bytecode (`javap` on `SemanticsNodeInteraction.class` from the `1.11.3` AAR), fixed as a one-line import removal with no behavioural change, and *flagged in `changes.md` rather than silently bundled*. That is the spec's "flag, don't silently fix" rule being followed under real pressure, mid-stage, after an agent handoff.
- **Test fixtures use real production values, not convenient ones.** `!`/`?` are the actual `UnownBinderRepository.LETTER_IDS` entries; `"Alolan Vulpix"` is chosen specifically because `effectiveSearchName` transforms it, so the test would fail if the transform broke — a name that passed through unchanged would have looked identical and proved nothing.
- **The instrumented test's limits are stated honestly and repeatedly** — in the spec, in `changes.md`, in `test-results.md`, and in the test file's own KDoc. Nobody is going to mistake "compiles" for "passes." That is the correct handling of an unrunnable test, and it is what keeps Test Evidence from collapsing.

---

## Score (Item I)

| Dimension | Score | Reasoning |
|---|---|---|
| **Spec compliance** | **23 / 25** | 15 of 16 acceptance criteria DONE with a concrete hunk each; zero scope creep; the hard "no production code" boundary verified independently and holding. −2 for AC line 78 landing PARTIAL (4 of 5 drawer items click-tested). |
| **Correctness & bug-freedom** | **21 / 25** | No correctness bugs in the new tests — encoding, placeholder names, `effectiveSearchName`, `findMatchingSlots` branch coverage, and `SecondaryBinderEntry` equality all hand-verified against production source. −3 for Minor 1 (an assertion resting on an unestablished device-state precondition, which will fail spuriously on a real AVD), −1 for the coverage hole behind the PARTIAL AC. |
| **Security & reliability** | **18 / 20** | No security surface at all; the one new dependency is first-party, androidTest-scope, at the already-pinned version. −2 for Minor 1's spurious-failure risk on first `connectedAndroidTest` run. |
| **Maintainability & simplification** | **14 / 15** | Reuses existing fixtures, adds one small helper, comments explain rationale not mechanics, no duplication, no magic values. −1 for the two stale/dangling comments (Minor 2 and 3), one of which this diff itself falsified. |
| **Test evidence quality** | **13 / 15** | Strong: counts recounted from freshly-regenerated XML (which I re-verified myself on disk — 24 files / 226 tests / 0 failures / 0 errors), `--rerun-tasks` forced after an `UP-TO-DATE` cache hit was correctly rejected as non-independent, and library claims settled by extracting real artifacts rather than asserted from memory. −2 because 6 of the 41 new tests (the entire instrumented file) have compile evidence only and zero execution evidence — honestly disclosed, but still absent. |
| **TOTAL** | **89 / 100** | |

**Prior iteration:** none (first pass) — no delta to report.

---

## Verdict

**SHIP.**

Score 89 ≥ 85, and there are **no unresolved P0/blocking findings**. The one hard constraint this task could not cross — zero production/app-logic changes — is verified clean by direct `git diff` against `app/src/main/`, not taken on trust from an earlier stage. The new JVM tests are correct against real production behaviour, and the full suite is green at 226/226 with the count re-derived from raw XML.

Carry forward as non-blocking follow-ups (Phase 7: **Defer**, all three):
1. Add the missing 5th drawer-navigation test (`"Pokédex"` → `"Pokédex Binder"`) to fully close AC line 78.
2. Establish the fresh-DataStore precondition in `bottomNavShowsPokedexAndHidesScanByDefault` before Skyler's first `connectedAndroidTest` run, or expect a spurious failure if he has the camera scanner enabled.
3. Correct `DatabaseModuleWiringTest`'s now-false "no Hilt test infra" KDoc, and de-bracket the cross-source-set KDoc link.

One standing caveat that is **not** a finding against this diff, restated so it is not lost at merge: `AppNavigationScreenTest.kt` has never executed. Its 6 tests are compile-verified and source-verified only. Until `connectedAndroidTest` runs on Skyler's AVD, the honest coverage claim for `gaps.md` gap #1 is *"route wiring: covered and passing; drawer order: written and compiling, unverified on-device."*
