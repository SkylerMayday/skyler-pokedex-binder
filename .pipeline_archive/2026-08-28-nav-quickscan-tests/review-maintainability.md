# Code Review — Lens 3 of 3: Maintainability + Simplification

**Reviewer:** dev-team-pipeline Stage 5 (Reviewer), maintainability+simplification lens
**Date:** 2026-08-28
**Branch:** `test/nav-and-quickscan-coverage` (uncommitted working tree, base `HEAD` = `20c74f3`)
**Author:** Coder stage (two agent instances — see `changes.md` provenance note)
**Scope:** 3 tracked modified files (`app/build.gradle.kts`, `gradle/libs.versions.toml`, `QuickScanViewModelTest.kt`) + 3 new untracked files (`CustomTestRunner.kt`, `AppNavigationScreenTest.kt`, `AppNavigationRouteTest.kt`). 347 insertions tracked + ~250 lines new untracked. Pure test-coverage addition.

**Prior iteration:** none — no `.pipeline/review-maintainability.md` or `review-verdict.md` existed before this pass. This is a first-pass review, not a re-run.

---

## Summary

This is well-built test code. Conventions match the project exactly (backtick names in `test/`, camelCase in `androidTest/` — correct, since `minSdk = 26` forbids backtick method names on-device), every new file carries a KDoc that explains *why* it exists rather than restating what it does, and the fixtures use real production values (`!`/`?` from `UnownBinderRepository.LETTER_IDS`, `"Alolan Vulpix"` to actually exercise `effectiveSearchName`'s REGIONAL branch) instead of arbitrary strings. The Hilt-testing infrastructure is the minimal textbook pattern with no awkward coupling. I found no blocking issues.

Two Important findings worth fixing before this is treated as a durable safety net (a test name that overclaims what it verifies, and two tests that pass for incidental reasons), plus five Minor simplification items. None are correctness bugs today.

**Recommendation:** Approve with comments — **ship**.

**Critical issues:** 0
**Important issues:** 2
**Minor issues:** 5
**Suggestions:** 3

---

## Intent audit (Phase 2)

Traced every spec requirement to actual diff content. Result: **clean — no scope creep, no missing requirement.**

| Spec item | Status | Evidence |
|---|---|---|
| Task Group A — `QuickScanViewModelTest.kt` expansion, 12 named cases | DONE | 29 `@Test` (was 6). Every named case present: query routing ×4, init ×2, `selectCard` ×5, `confirmCard`/`wrongCard`/`assignToSlot`, `addToSecondary` ×2, manual entry ×2, `retry` ×2, `reset` |
| Task Group B — `AppNavigationRouteTest.kt`, 7 named cases | DONE | 12 `@Test` at the specified path `app/src/test/.../ui/navigation/` |
| Task Group C1 — Hilt infra | DONE | `libs.versions.toml` +1 line reusing `version.ref = "hilt"`; `build.gradle.kts` +`androidTestImplementation(libs.hilt.android.testing)` / `kspAndroidTest(libs.hilt.compiler)`; runner swapped to `"com.skyler.pokedexbinder.CustomTestRunner"` |
| Task Group C2 — `AppNavigationScreenTest.kt` | DONE (compile-only, as spec'd) | 6 `@Test`, `@HiltAndroidTest` + rule ordering exactly as the spec's skeleton prescribed |
| AC: "No production code file changes ... verified by the Reviewer stage" | **DONE — verified myself** | `git diff --stat HEAD -- 'app/src/main/'` → empty output |
| AC: stray debug output | DONE — verified myself | `grep -n "println\|Log\.d\|TODO\|FIXME"` across all 3 new files → exit 1, no matches |
| Success metric: "Both `gaps.md` entries can be marked resolved/struck through" | **NOT DONE** | `gaps.md` is untouched by this diff. Spec wording is "can be marked," not "must be" — so not a violation, but the stated primary success metric is left unrealised. See Suggestion 1 |

Scope creep: **none.** The only non-test files touched are the two build-config files the spec explicitly authorised, and each changed line is directly required by Task Group C1.

---

## Critical issues (blockers)

None.

---

## Important issues

### 1. Test name overclaims what it verifies — the navArgument drift it names is not actually covered

- **File:** `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationRouteTest.kt`
- **Line:** 108-121
- **Confidence:** 9 (verified by reading both the test and `AppNavigation.kt`)

The test is named:

```kotlin
fun `parameterized route templates declare the same placeholder names AppNavigation wires as navArgument`() {
```

but its entire body is one-sided assertions against hardcoded literals:

```kotlin
assertTrue(Screen.QuickScan.route.contains("{slotId}"))
assertTrue(Screen.QuickScan.route.contains("{replacing}"))
```

Nothing here reads `AppNavigation.kt`'s `navArgument(...)` declarations. Concretely: if someone renames `navArgument("slotId")` (AppNavigation.kt line 270) to `navArgument("slot_id")` and leaves `Screen.QuickScan.route`'s `{slotId}` untouched, deep-linking silently breaks — and **this test still passes**. That is precisely the "route typo silently breaks deep-linking" scenario `specs.md`'s Problem section named as the reason for the test.

The inline comment is honest about the mechanism (`These expected names are hardcoded here on purpose -- they mirror AppNavigation.kt's own navArgument(...) calls`), and `specs.md` Task Group B item 7 explicitly sanctioned hardcoding — so this is **not** spec non-compliance. The maintainability problem is the *name*: it is what shows up in a failure report and what a future maintainer reads when deciding "is nav-argument drift already covered?" It says yes; the body says no.

- **Suggested fix:** rename to something that describes the actual check, e.g. `` `parameterized route templates contain their expected placeholder names` ``, and add one line to the comment stating that the navArgument side is *not* cross-checked and drift there is still uncovered. (A genuine cross-check would need `AppNavigation.kt`'s `navArgument` names extracted into shared constants — a production change, out of scope here.)

### 2. Two `selectCard` tests skip the init-path stubbing their siblings do, so they pass incidentally

- **File:** `app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt`
- **Line:** new `selectCard with slot pre-selected reuses the current CardSelection cards list` and `selectCard with slot pre-selected falls back to a single-card list outside CardSelection`
- **Confidence:** 8 (verified by reading the tests, their siblings, and `QuickScanViewModel.init`)

Both construct a ViewModel with a non-blank `slotId` and stub nothing:

```kotlin
val vm = viewModel(slotId = "target", replacing = true)
```
```kotlin
val vm = viewModel(slotId = "target")
```

A non-blank `slotId` fires the init auto-search (`QuickScanViewModel.kt:57-67`):

```kotlin
init {
    if (targetSlotId.isNotBlank()) {
        viewModelScope.launch {
            val slot = binderRepository.getSlotByPokemonId(targetSlotId)
```

Every sibling test that constructs with a non-blank `slotId` stubs that path explicitly:

```kotlin
coEvery { binderRepository.getSlotByPokemonId("target") } returns slotFixture
every { cardSearchRepository.searchStreaming(any(), any()) } returns
    flowOf(SearchProgress.Complete(emptyList()))
val vm = viewModel(slotId = "target")
```
(`confirmCard assigns to the pre-selected slot and reports its display name`, and again in `confirmCustomCard assigns to the pre-selected slot`)

Without those stubs, whatever state these two tests start from is a byproduct of `binderRepository`'s `relaxed = true` fallback and `runSearch`'s catch-all (`catch (e: Exception) { _state.value = QuickScanState.Error(...) }`), not something the test establishes. That matters most for the second one, whose name is *"falls back to a single-card list **outside CardSelection**"* — it never puts the ViewModel into a known non-`CardSelection` state; it just happens not to be in one. If `runSearch`'s error handling or the init path ever changes such that state lands on `CardSelection`, the test silently starts exercising the opposite branch (`(_state.value as? QuickScanState.CardSelection)?.cards`, `QuickScanViewModel.kt:127`) while still passing under its current name.

- **Suggested fix:** either use `viewModel()` with a blank `slotId` where the init path isn't the point, or stub `getSlotByPokemonId` + `searchStreaming` the way the sibling tests do, and assert the starting state explicitly (`assertEquals(QuickScanState.Idle, vm.state.value)`) before calling `selectCard`.

---

## Minor issues

- **`AppNavigationRouteTest.kt:20-31`** — `` `static routes are all unique` `` is fully subsumed by the test directly beneath it. `assertEquals(routes.size, routes.toSet().size)` cannot fail if `` `static route values match expected strings` `` passes, since that test pins all 7 of the same routes to 7 distinct literals. Answers brief question 5: 11 of the 12 tests pull their own weight; this is the one that doesn't. Low cost to keep (it documents intent), but it is genuine duplication.

- **`AppNavigationScreenTest.kt:58-66`** — `assertOnScreenTextExists` has no KDoc of its own and no pointer to the library version its behavior depends on. The *class* KDoc explains the why very well ("A `ModalNavigationDrawer`'s drawer content stays composed (just translated off-screen) even while closed... a closed drawer's content is translated to a negative left coordinate"), which is the substance of brief question 2 — **yes, the why is documented, and clearly**. The gap is provenance: `.count { it.boundsInRoot.left >= 0f }` is load-bearing on `material3-android:1.4.0`'s `calculatedClosedAnchor = -width.toFloat()`, which the Tester verified against the real sources jar but which appears nowhere in the code. If material3 ever switches to alpha/visibility-based hiding, this filter degrades silently. One line on the helper — `// Depends on material3 1.4.0's closed-drawer anchor being -drawerWidth (NavigationDrawer.kt:417)` — makes the failure mode traceable.

- **`QuickScanViewModelTest.kt:70-76`** — the `pokemonSlot` helper exposes two parameters no call site ever overrides:
  ```kotlin
  dexNumber: Int = 0,
  dexOrder: Int = 0
  ```
  Every one of the 8 call sites passes only `id`/`name`/`slotType`. Against the project's own "KEEP ONLY ACTIVE CODE" standard, these should be inlined as literals in the body. Minor over-abstraction, not a bug.

- **`QuickScanViewModelTest.kt`, the 4 query-routing tests** — this 3-line idiom is repeated verbatim four times:
  ```kotlin
  val fastSearch = slot<suspend () -> List<TcgCard>>()
  every { cardSearchRepository.searchStreaming(any(), capture(fastSearch)) } returns
      flowOf(SearchProgress.Complete(emptyList()))
  ```
  A `private fun captureFastSearch(): CapturingSlot<suspend () -> List<TcgCard>>` would collapse it to one line per test with no loss of clarity. Borderline (explicit setup in tests is defensible), but four repetitions is past the threshold where the project's "duplication gets extracted" standard bites.

- **`AppNavigationScreenTest.kt:70, 93, 101, 109, 117`** — `composeTestRule.onNodeWithContentDescription("Menu").performClick()` appears 5 times, and the 4 drawer-navigation tests are structurally identical apart from one string. Keeping 4 separate `@Test` methods is correct (the spec chose that deliberately to avoid state bleed), but their bodies could each be one call to a shared `private fun assertDrawerItemNavigates(label: String)`. Also worth noting: `drawerShowsAllFiveItemsInTopToBottomOrder` uses a *second, different* disambiguation heuristic from the documented one — `.minOf { it.boundsInRoot.top }` rather than the left-bound filter. It's explained inline (lines 72-74) but not mentioned in the class KDoc, which currently reads as if `assertOnScreenTextExists` is the file's single disambiguation strategy.

---

## Suggestions for follow-up

1. **Strike the two closed entries in `gaps.md`.** `specs.md`'s stated primary success metric is that both entries "can be marked resolved/struck through with a real, currently-passing... test file backing the claim." The test files exist and pass; the register still lists them as open. Not part of this diff's scope, but it's the one piece of the spec's own success definition left undone.

2. **Record the `testInstrumentationRunner` swap as a project-level sharp edge.** `testInstrumentationRunner = "com.skyler.pokedexbinder.CustomTestRunner"` is a global change: *every* instrumented test — including the six pre-existing non-Hilt ones — now runs under `HiltTestApplication` instead of `PokedexBinderApp`. That is genuinely harmless today, and I verified why rather than taking it on trust: `class PokedexBinderApp : Application()` has an empty body, no `onCreate` override, no side effects to lose. But the moment someone adds an `onCreate` to `PokedexBinderApp`, every instrumented test silently stops running it. Worth one line in `project-overview.md` (or `gaps.md`) so it's discoverable before it bites, not after.

3. **Note the `SlotDetail` / `UnownSearch` encoding asymmetry in the route test.** `Screen.UnownSearch.createRoute` URL-encodes; `Screen.SlotDetail.createRoute(slotId: String) = "slot_detail/$slotId"` does not. `AppNavigationRouteTest`'s `assertEquals("slot_detail/bulbasaur", ...)` now locks in the unencoded behavior without saying it's deliberate. One comment stating "slot IDs are internal lowercase identifiers, no encoding needed" would stop a future reader treating the asymmetry as an oversight to 'fix'.

---

## Dimension scorecard

| Dimension | Pass | Notes |
|---|---|---|
| Correctness | ✅ | Independently reproduced 24 files / 226 tests / 0 failures / 0 errors from the on-disk JUnit XML. Verified all 5 `contentDescription = "Menu"` sites and every asserted `TopAppBar` title exist in production — the instrumented assertions are real, not tautological |
| Security | ✅ (N/A) | Test-only diff, no runtime surface. One new dependency, first-party `com.google.dagger`, `androidTest` scope only, reusing the already-pinned `version.ref = "hilt"` — no version drift, nothing new reaches a shipped artifact |
| Performance | ✅ (N/A) | No production code path touched |
| Reliability | ⚠️ | `AppNavigationScreenTest`'s 6 tests have never executed — honestly and repeatedly stated in the spec, `changes.md`, `test-results.md`, and the file's own KDoc. Spec-sanctioned; not held against the diff, but it is 6 tests of unproven runtime behavior |
| **Maintainability** | ⚠️ | Strong overall — conventions matched exactly, why-not-what KDoc on all three files, real production fixtures, minimal Hilt infra. 2 Important + 5 Minor items above, none blocking |

---

## Answers to the five lens questions

1. **Idiomatic / consistent with project conventions?** Yes, precisely. JVM tests use backtick names matching the existing 6 in the same file and the sibling `MainBinderViewModelTest`/`SlotDetailViewModelTest`; `AppNavigationScreenTest` uses camelCase matching all six pre-existing `androidTest` files (`migration5to6AddsNullableCardNameAndSetColumns`, `insertAndObserveMainBinder`, ...) — which is not just stylistic, it's required, since `minSdk = 26` rejects backtick method names on-device. The mockk usage (`coEvery`/`coVerify`/`slot`+`capture`/`returnsMany`, `UnconfinedTestDispatcher`, `runTest`) reuses the file's established fixtures rather than introducing a parallel style.

2. **Is `assertOnScreenTextExists`'s approach documented well enough?** Yes for the *why* — the class KDoc's explanation of `ModalNavigationDrawer` keeping content composed and translated to a negative left coordinate is exactly the "comments explain why, not what" standard. The one gap is the missing version-provenance pointer on the helper itself (Minor #2 above).

3. **Duplication / over-abstraction / unnecessary complexity?** Nothing over-abstracted; if anything the code errs toward explicit repetition. Four real simplification candidates, all minor: the redundant uniqueness test, the two dead helper parameters, the 4× capture idiom, the 5× drawer-open call.

4. **Is the Hilt infra easy to extend, or awkwardly coupled?** Easy to extend, and cleanly done. `CustomTestRunner` is 4 lines of the canonical Google pattern, sits at the package root so the FQN in `build.gradle.kts` stays short, reuses the existing `hilt` version ref and `hilt.compiler` alias rather than introducing new ones, and carries a KDoc that pre-empts the obvious question ("existing non-Hilt instrumented tests... are unaffected by this runner swap"). Any future `@HiltAndroidTest` needs only the two rules plus `hiltRule.inject()`. The only coupling nit: the KDoc names `AppNavigationScreenTest` as its example, which goes stale if that file is renamed. The real thing to write down is the global-scope consequence of the runner swap — Suggestion 2.

5. **Redundancy in `AppNavigationRouteTest`'s 12 tests?** One redundant test (`static routes are all unique`, subsumed by the next test). The other 11 each cover a distinct behavior — and the three `UnownSearch` tests in particular are correctly split rather than merged, since plain letters, real production special IDs (`!`/`?`), and the `+`→`%20` override are three genuinely different encoding behaviors that should fail independently.

---

## What looks good

- **Fixtures are real, not arbitrary.** `!` and `?` are the actual non-alphabetic entries in `UnownBinderRepository.LETTER_IDS`; `"Alolan Vulpix"` with `SlotType.REGIONAL` genuinely exercises `effectiveSearchName`'s `name.substringAfter(" ")` branch rather than a name that would pass through unchanged; `"Mega Charizard X"` + `"Charizard"` genuinely produces a 2-slot match under `findMatchingSlots`'s substring logic. These were chosen, not guessed.
- **The `fastSearch` capture tests close a gap nobody had noticed.** `search()`'s lambda was built by every prior test and invoked by none, because `searchStreaming` was fully mocked. Capturing and invoking it is the right technique and is the single highest-value addition in this diff.
- **The `assertDoesNotExist` fix was root-caused, not guessed.** `javap` on the actual `SemanticsNodeInteraction.class` from the pinned `ui-test-android:1.11.3` artifact, independently re-run by the Tester. One line removed, no behavior change. That is exactly the right size of fix for the finding.
- **Every stage was straight about the instrumented test never having run.** Spec, `changes.md`, `test-results.md`, and the file's own KDoc all say it. No overclaiming anywhere.

---

## Score (Item I)

| Dimension | Score | Reasoning |
|---|---|---|
| **Spec compliance** | **24 / 25** | All four Task Groups implemented at the specified paths with the specified structure; every named test case present (29 + 12 + 6 `@Test` verified by grep); zero scope creep — the only non-test files touched are the two build-config files the spec explicitly authorised. I verified the "no production code changes" criterion myself (`git diff --stat HEAD -- 'app/src/main/'` → empty). −1: the spec's own stated primary success metric ("both `gaps.md` entries can be marked resolved") is left unrealised — `gaps.md` is untouched. |
| **Correctness & bug-freedom** | **22 / 25** | No bugs found. 226 tests / 0 failures independently reproduced from the JUnit XML; all asserted `TopAppBar` titles and `contentDescription = "Menu"` sites verified present in production source, so the assertions are real. −2 for Important #2 (two tests pass via an incidental state path rather than one they establish — a latent false-confidence risk, not a current failure). −1 for Important #1 (the navArgument-drift scenario the spec named as the motivating risk remains uncovered by the test that claims to cover it). |
| **Security & reliability** | **19 / 20** | Test-only diff, no runtime attack surface, no secrets. The single new dependency is first-party Dagger at `androidTest` scope on the already-pinned version ref — no version drift, nothing shipped. The global `testInstrumentationRunner` swap was compile-verified across the whole `androidTest` source set, and I confirmed independently that `PokedexBinderApp` has an empty body, so nothing is lost by substituting `HiltTestApplication`. −1: six instrumented tests have zero execution evidence (spec-sanctioned, honestly disclosed, but still unproven). |
| **Maintainability & simplification** | **12 / 15** | Conventions match the project exactly (including the non-obvious backtick-vs-camelCase split forced by `minSdk = 26`); all three new files carry why-not-what KDoc; Hilt infra is minimal, textbook, and reuses existing version refs. −2 for the two Important findings (a test name that overclaims its coverage; inconsistent fixture setup across sibling tests). −1 for the five Minor items (one redundant test, two dead helper parameters, a 4× and a 5× repetition, a second undocumented disambiguation heuristic). No over-engineering anywhere — the code errs toward explicit, which is the right direction for test code. |
| **Test evidence quality** | **13 / 15** | Genuinely strong. The Tester re-derived every claim rather than trusting `changes.md`: re-extracted the AAR and re-ran `javap`, extracted `material3-android:1.4.0` sources to confirm `calculatedClosedAnchor = -width.toFloat()` instead of reasoning abstractly about drawer behavior, deleted stale results and forced `--rerun-tasks` before counting, recomputed the baseline arithmetic from `git show HEAD`, and caught that `specs.md`'s own 182 baseline was stale. Verbatim output throughout; I reproduced the 24/226/0 count myself. −2: the 6 instrumented tests have no execution evidence at all, and the `boundsInRoot.left >= 0f` heuristic is source-verified but not runtime-verified. |
| **Total** | **90 / 100** | |

**Score change from prior iteration:** N/A — first pass, no prior `.pipeline/review-maintainability.md` existed.

---

## Verdict

**SHIP.**

90/100, above the 85 threshold, with zero P0/blocking findings. The two Important items are both false-confidence risks in test code rather than defects — neither can break the app, and neither makes the suite unreliable today. They are worth fixing because this diff exists specifically to be a durable safety net, and a test named for a check it doesn't perform is worth less than no test at that spot. Reasonable to fold both into a quick follow-up pass alongside the five Minor simplifications, or to defer them as tracked items; neither should hold up the merge.

**Standing caveat, already correctly disclosed by every stage:** `AppNavigationScreenTest`'s 6 tests have never executed. They compile, and their central assumption (a closed `ModalNavigationDrawer`'s content sits at a negative root-space x) is verified against the real material3 1.4.0 source — but real pass/fail needs Skyler's own `connectedAndroidTest` run. Until then this diff has closed gap #1's route-wiring half with executed proof and its drawer-order half with structural proof only.

---

## Sign-off

**Approval status:** Approved with comments (ship)
**Date:** 2026-08-28
**Lens:** maintainability + simplification (3 of 3)
