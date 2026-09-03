# Feature Spec: Test Coverage — Nav Route/Drawer Wiring + QuickScanViewModel Regression Suite

**Author:** Planner (dev-team-pipeline Stage 1)
**Date:** 2026-08-28
**Status:** Draft
**Target ship:** This pipeline run (Coder → Tester → Reviewer)

---

## TL;DR

Close two long-standing entries in `gaps.md`'s "Test coverage gaps" section: (1) `AppNavigationScreenTest.kt` was deleted during Unown's reworks and never recreated for the current nav layout, leaving zero coverage on route wiring/drawer order; (2) `QuickScanViewModelTest.kt` only covers the `searchStreaming` wiring added in an earlier session — the rest of `QuickScanViewModel`'s behavior (query routing, slot-matching, manual entry, secondary-binder fallback, confirm/retry/reset) has no regression coverage at all. This is a pure test-addition pass — no production code changes are in scope unless a genuine bug is found while writing tests, in which case it gets flagged separately, not silently fixed.

---

## Problem

`AppNavigation.kt` defines the app's entire route table (`Screen` sealed class) and renders the hamburger drawer (Pokédex, Connecting Art, Personal Collection, Unown, Card History) plus a conditional bottom-nav Scan button gated on `settings.useCameraScanner`. None of this has test coverage — a route-string typo, a `createRoute()` encoding regression, or a silent drawer-item reorder/removal would only be caught by manually clicking through the app.

`QuickScanViewModel` backs the main Pokédex binder's slot-tap-to-assign flow. Only 6 of its tests (from a prior session) exercise the `searchStreaming` wiring; the query-routing logic inside `search()` (dex number vs. promo number vs. name), the slot-matching logic in `selectCard()`, the manual-entry flow (`showManualEntry()`/`confirmCustomCard()`), and the secondary-binder fallback (`addToSecondary()`) have never been exercised by any test — including the fact that the existing tests fully mock `searchStreaming`, so the `fastSearch` lambda `search()` builds internally is never actually invoked by any test today.

### Evidence

- `gaps.md` lines 221-226 (verbatim, still open as of this session): `AppNavigationScreenTest.kt` deleted during Unown reworks, never recreated; `QuickScanViewModelTest.kt` only covers `searchStreaming` wiring, no coverage for slot-matching, manual entry, secondary-binder fallback.
- `git log --all -- "**/AppNavigationScreenTest*"` returns nothing — the file was never committed to this repo's history, confirming there is no existing pattern to resurrect, only the current `AppNavigation.kt` to test fresh.
- Read `app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt` in full (148 lines, 6 tests): every test only calls `vm.search(...)` and asserts against a fully-mocked `searchStreaming` return value. `selectCard`, `confirmCard`, `wrongCard`, `assignToSlot`, `addToSecondary`, `showManualEntry`, `confirmCustomCard`, `retry`, `reset`, and the `init` block's auto-search-on-slot-tap path are never called anywhere in the file.

---

## Users

**Primary user:** Skyler, as the sole maintainer of this app, running future reworks of the nav drawer and the QuickScan flow (both have already been reworked multiple times per `gaps.md`'s own note about Unown's "multiple reworks").

**Use cases:**
1. A future nav rework accidentally drops a drawer item or reorders it — a test should fail before Skyler discovers it by tapping the drawer on-device.
2. A future edit to `Screen.createRoute()` (e.g. changing how a slot ID or letter ID gets URL-encoded) silently breaks deep-linking between screens — a JVM test should catch it in seconds, not require a manual click-through.
3. A future edit to `QuickScanViewModel`'s slot-matching, manual-entry, or secondary-binder-add logic regresses silently because only the `searchStreaming` path has a safety net today.

**Secondary users:** None — this is solo-maintainer internal tooling, not user-facing.

**Anti-users:** N/A.

---

## Proposal

Add three independent, dependency-free pieces of test coverage:

1. A pure-JVM unit test for the `Screen` sealed class's route templates and `createRoute()` encoding (fast, runs in this sandbox, catches route-string typos and encoding regressions).
2. A new Hilt-instrumented Compose UI test (`androidTest`) for `AppNavigation()` itself, verifying drawer item presence/order and click-to-navigate reachability, plus the bottom-nav Scan-button default-hidden state. This requires adding Hilt instrumented-test infrastructure to the project for the first time (see Architecture Decision below) — compiles and is structurally verified in this sandbox; actual execution requires Skyler's own AVD via `connectedAndroidTest`, consistent with every other `androidTest` file in this project.
3. An expansion of the existing `QuickScanViewModelTest.kt` covering every previously-untested branch of `QuickScanViewModel`: `search()`'s query-routing (dex/promo/name), the `init`-block auto-search, `selectCard()`'s slot-matching (no/one/many matches), `confirmCard`/`wrongCard`/`assignToSlot`, `addToSecondary` (secondary-binder fallback), the manual-entry flow (`showManualEntry`/`confirmCustomCard`), and `retry`/`reset`. Fully JVM, fully runnable in this sandbox, matching the existing file's mockk/coEvery/runTest pattern.

### How it works (high level)

1. Coder adds a new JVM test file asserting `Screen` route/encoding behavior — no app code touched.
2. Coder adds minimal Hilt-testing infrastructure (version catalog entry, `CustomTestRunner`, `testInstrumentationRunner` change) plus one new `androidTest` file that launches the real `MainActivity` (already `@AndroidEntryPoint`) via `createAndroidComposeRule<MainActivity>()` and asserts on the rendered drawer/nav bar — no `AppNavigation.kt` changes.
3. Coder appends new `@Test` methods to the existing `QuickScanViewModelTest.kt`, reusing its existing mockk fixtures — no `QuickScanViewModel.kt` changes unless a genuine bug surfaces (flagged separately, not silently fixed).
4. Tester runs `testDebugUnitTest` (must include the new/expanded JVM tests, full suite green) and compiles `assembleDebugAndroidTest` (or equivalent compile-check task) for the new instrumented test — cannot execute it in this sandbox, states that plainly.

---

## User stories

- As Skyler, I want a failing test the moment a drawer item is reordered or removed, so that nav regressions are caught before I tap through the app manually.
- As Skyler, I want `Screen.createRoute()` encoding covered by tests, so a future edit to URL-encoding logic doesn't silently break deep navigation (e.g. Unown letter IDs with special characters, QuickScan's optional query params).
- As Skyler, I want `QuickScanViewModel`'s slot-matching, manual-entry, and secondary-binder-fallback paths covered, so a future QuickScan rework doesn't silently break assignment logic that today has zero safety net.

---

## Acceptance criteria

The feature ships when ALL of these are true.

- [ ] **Given** the current `Screen` sealed class, **when** the new JVM test file runs, **then** every route's `createRoute()` output matches its own route template's placeholder positions exactly, including URL-encoding of spaces/special characters (`UnownSearch`, `QuickScan`, `Scanner`).
- [ ] **Given** `QuickScan.createRoute()` and `Scanner.createRoute()` called with default arguments, **when** asserted, **then** the produced route strings match what `AppNavigation.kt`'s `navArgument(...) { defaultValue = ... }` declarations expect (empty slotId, `false` for boolean flags).
- [ ] **Given** the new Hilt-instrumented `AppNavigationScreenTest.kt`, **when** compiled via Gradle in this sandbox, **then** it compiles cleanly against the newly added Hilt-testing dependency and `CustomTestRunner`.
- [ ] **Given** the drawer is opened in the instrumented test, **when** its contents are inspected, **then** all 5 items ("Pokédex", "Connecting Art", "Personal Collection", "Unown", "Card History") exist and appear in that exact top-to-bottom order.
- [ ] **Given** each drawer item is clicked in turn, **when** the corresponding screen renders, **then** its unique `TopAppBar` title text ("Pokédex Binder", "Connecting Art", "Personal Collection", "Unown", "Card History") is present, confirming route wiring is correct.
- [ ] **Given** a fresh `app_settings` DataStore (default `useCameraScanner = false`), **when** `AppNavigation()` renders, **then** the bottom-nav "Scan" item is absent, and the "Pokédex" bottom-nav item is always present.
- [ ] **Given** the expanded `QuickScanViewModelTest.kt`, **when** `testDebugUnitTest` runs, **then** every existing test still passes (6 pre-existing + all newly added) with zero regressions to the current 182-test project baseline count (only additive).
- [ ] **Given** `QuickScanViewModel.search()` is called with a numeric string, a promo-number-shaped string, and a plain name, **when** the captured `fastSearch` lambda is invoked, **then** it calls `searchByDexNumber`, `searchByNumber` (falling back to `searchByName` on empty), and `searchByName` respectively.
- [ ] **Given** a `QuickScanViewModel` constructed with a non-blank `slotId` and a mocked slot lookup, **when** the ViewModel initializes, **then** it auto-searches using the slot's `effectiveSearchName`; **given** the slot lookup returns null, **then** no search fires and state stays `Idle`.
- [ ] **Given** `selectCard()` is called with a non-blank `targetSlotId`, **when** invoked, **then** state becomes `CardConfirm` (using the current `CardSelection.cards` if present, else `listOf(card)`), without calling `assignCardUseCase.assign` yet.
- [ ] **Given** `selectCard()` is called with a blank `targetSlotId` and 0, 1, or 2+ matching slots, **when** invoked, **then** state becomes `NoSlot`, auto-assigns-and-`Success`, or `SlotSelection` respectively, matching case-insensitively on substring per `findMatchingSlots`'s actual logic.
- [ ] **Given** `addToSecondary(card)` is called, **when** invoked, **then** `secondaryBinderDao.insertAtEnd` is called with the correct `SecondaryBinderEntry` fields (including the `pokemonNames.firstOrNull() ?: ""` fallback when empty), and state becomes `Success(card, "Secondary Binder")`.
- [ ] **Given** `showManualEntry()` is called after a prior `search(query)`, **when** invoked, **then** state becomes `ManualEntry(prefillName = query)`.
- [ ] **Given** `confirmCustomCard(...)` is called with a non-blank `targetSlotId`, **when** invoked, **then** `assignCardUseCase.assign` is called and state becomes `Success`; **given** a blank `targetSlotId`, **then** state becomes `CardSelection(listOf(customCard))`.
- [ ] **Given** `confirmCard`, `wrongCard`, `assignToSlot`, `retry`, and `reset`, **when** each is exercised, **then** each produces its documented state transition.
- [ ] No production code file changes are present in the diff unless a genuine bug was found and separately flagged (not silently fixed) — verified by the Reviewer stage.

Each criterion must be observable and testable via a specific test assertion or `git diff` inspection.

---

## Out of scope

- Fixing or refactoring `AppNavigation.kt` or `QuickScanViewModel.kt` production code — this is coverage-only. A genuine bug found while writing tests gets flagged in the Coder's output, not fixed inline.
- Actually running the new instrumented `androidTest` file on a real device/emulator — this sandbox cannot execute `connectedAndroidTest`; compile-check only, same standing pattern as every other instrumented test in this project.
- The full publish→restore round-trip integration test and the TCGCSV performance item, both separate open entries in `gaps.md` — untouched by this spec.
- Testing `MainActivity` itself, `PokedexTheme`, or any other screen's internal logic beyond what's needed to assert drawer/route reachability (e.g. `ConnectingArtViewModel`, `UnownBinderViewModel` internals are not in scope).
- Extracting the drawer/bottom-nav content out of `AppNavigation()` into separately-testable composables — would be a production refactor, explicitly out of scope per the "no production code changes" constraint.

---

## Dependencies

### Required before development can start
- [x] Codebase read: `AppNavigation.kt`, `QuickScanViewModel.kt`, existing `QuickScanViewModelTest.kt`, `PokemonSlot.kt`, `TcgCard.kt`, `AssignCardUseCase.kt`, `CardSearchRepository.kt` (searchStreaming signature), `SettingsRepository.kt`, `MainActivity.kt`, all 5 destination screens' `TopAppBar` titles, `gradle/libs.versions.toml`, `app/build.gradle.kts`. All done during this planning pass (see Architecture Decision + Technical Plan below).
- [ ] No design/API/content/legal dependencies — internal test-only change.

### External systems or services
- None. All new tests are self-contained (JVM unit tests + one instrumented Compose test using the app's own real Hilt graph, no new external service).

### Team dependencies
- Engineering only (Coder → Tester → Reviewer stages of this pipeline). No design/content/marketing involvement.

---

## Success metric

**Primary metric:** Both `gaps.md` entries ("`AppNavigationScreenTest.kt` was deleted..." and "`QuickScanViewModelTest.kt` only covers...") can be marked resolved/struck through with a real, currently-passing (or compile-verified, for the instrumented case) test file backing the claim.

**Current baseline:** 182 tests / 23 files in `testDebugUnitTest` (per `gaps.md`'s housekeeping note and the `delete-stale-test-results-dir-before-rerun` lesson's confirmed baseline). Zero nav test coverage of any kind.

**Target:** 182 + N new JVM tests passing (QuickScan expansion + new route test file), zero regressions; one new instrumented test file that compiles cleanly (`assembleDebugAndroidTest` or equivalent).

**Measurement window:** This pipeline run — Tester stage verifies directly.

**Secondary metrics (signals to watch):**
- New JVM test count added — should be double digits given the number of previously-untested branches enumerated above.
- Diff touches zero production `.kt` files under `app/src/main/` except the two new/expanded test files and the new `CustomTestRunner` (which is test-only despite living in the `androidTest` source set) — Reviewer stage checks this explicitly.

**Negative signals (what would tell us this hurt):**
- Any existing test (of the 182-baseline) starts failing — would indicate an accidental production-code edit or a fixture change with unintended side effects.
- `testInstrumentationRunner` change (needed for Hilt instrumented tests) breaking the existing DB/migration `androidTest` files that don't use Hilt — `HiltTestApplication` must still support plain (non-`@HiltAndroidTest`) instrumented tests unchanged; verify by compile-checking the existing `androidTest` files still reference correctly after the runner swap.

---

## Estimated effort

**Size:** Medium (would be 1-3 days by a human; sized in S/M/L/XL per-task below for the pipeline).

**Breakdown:**
- Task Group A (QuickScanViewModel test expansion): M
- Task Group B (Nav route JVM test): S
- Task Group C (Hilt instrumented test infra + AppNavigationScreenTest): L (first-of-its-kind infra in this project)
- Total: M-L

---

## Priority

Framework used: **MoSCoW**, applied within this one spec (per `planning`'s Phase 3 guidance: "Prioritizing requirements within one spec → MoSCoW"). Both gaps were already explicitly selected by Skyler for this pipeline run — the prioritization question here is which pieces are load-bearing vs. stretch, not whether to do this work at all.

- **Must have:** Task Group A (QuickScanViewModel expansion) — pure JVM, zero new infra, zero risk, directly closes gap #2 in full.
- **Must have:** Task Group B (nav route JVM test) — pure JVM, zero new infra, directly covers the "route typos" half of gap #1's regression risk.
- **Should have:** Task Group C (Hilt instrumented Compose test) — closes the "drawer order" half of gap #1 that Task Group B structurally cannot reach (see Architecture Decision), but is new infrastructure with a real (if de-risked) integration surface, and can only be compile-verified in this sandbox. If the pipeline needs to cut scope under time pressure, this is the piece to cut first — but per the brief's explicit instruction to investigate and justify rather than default away from instrumented testing, it should be attempted first, not skipped by default.
- **Won't have (this pass):** Extracting drawer content into a separately-unit-testable composable (production refactor, out of scope), full publish→restore integration test (separate gap), any device/emulator execution of the new instrumented test.

**This spec:** P1 (both gaps are long-standing, explicitly named by the user for this pipeline run; not a launch blocker since nothing user-facing changes, but high-value debt closure the user asked for directly).

**Reasoning:** No user-facing risk from not shipping, but this is directly what was requested, closes real regression-risk gaps on two areas already reworked multiple times historically (nav, QuickScan), and Group A/B carry effectively zero execution risk.

---

## Open questions

- [ ] Should Task Group C (Hilt instrumented test infra) actually be attempted, given it's the project's first-ever `@HiltAndroidTest`/`HiltTestApplication` setup and can only be compile-verified (not run) in this pipeline's sandbox? — **Owner:** Skyler (or the Coder stage using judgment per the Should-have priority above) — **Decision needed by:** before Coder stage starts, or the Coder can proceed under the Should-have default (attempt it, flag clearly if it turns out more complex than scoped here) and let the Reviewer stage surface a concern if warranted.
- [ ] Does Skyler want the `CustomTestRunner` + Hilt-testing dependency addition kept even if Task Group C's actual test file is later deemed not worth maintaining (e.g., if it proves flaky against the real Room/network graph on his AVD)? — **Owner:** Skyler — **Decision needed by:** first time `connectedAndroidTest` is actually run on his AVD, not blocking for this pipeline run.

---

## Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Hilt instrumented test infra (Task Group C) turns out more complex than scoped (e.g. a screen's ViewModel has an eager network call at init that fires during composition) | Low | Medium | Confirmed during planning: `MainBinderViewModel.init` only does local DB seeding (`seedIfEmpty`, `migrateSlotNamesIfNeeded`, etc.), no network calls — matches the already-proven "app launches clean on the AVD" baseline from `gaps.md` (2026-08-22). If another destination screen's ViewModel turns out to have an eager network call, Coder should note it but the drawer/bottom-nav assertions only require the **start destination** (`MainBinderScreen`) to render, not every destination simultaneously. |
| `testInstrumentationRunner` change breaks existing non-Hilt `androidTest` files (`Migration5to6Test`, `PokedexDatabaseTest`, etc.) | Low | Medium | `HiltTestApplication` (from `hilt-android-testing`) is designed as a drop-in `Application` replacement compatible with plain instrumented tests that don't use `@HiltAndroidTest` — Coder should compile-check the full `androidTest` source set after the runner swap, not just the new file. |
| New JVM tests for `search()`'s captured `fastSearch` lambda are awkward with mockk (`slot<suspend () -> List<TcgCard>>()` + manual invocation inside `runTest`) and could be mis-written | Low | Low | Pattern is a standard mockk idiom (`capture(slot)` + `coVerify` after invoking `slot.captured()`); Coder should verify with the existing file's `UnconfinedTestDispatcher` setup already in place. |
| Reviewer flags an accidental production-code touch | Low | Medium | Explicit acceptance criterion above; Coder should self-check `git diff --stat` before finishing to confirm only test files (+ `libs.versions.toml`, `app/build.gradle.kts`, and the new `CustomTestRunner.kt`) changed. |

---

## Smallest shippable increment

**MVP:** Task Groups A + B alone (QuickScanViewModel expansion + nav route JVM test) — fully closes gap #2, and closes the "route typo" half of gap #1, with zero new infrastructure and zero execution risk. Both are S/M sized and independently mergeable.

**Future iterations** (not required for this pass to be valuable, but recommended to attempt in the same pipeline run per the Should-have priority):
- Task Group C: Hilt instrumented Compose test closing the "drawer order" half of gap #1.
- A real `connectedAndroidTest` run against Skyler's AVD outside this pipeline, to confirm Task Group C's test actually passes on-device (not just compiles).

---

## Architecture Decision: JVM unit test vs. instrumented Compose test for nav coverage

**Status:** Accepted (for this spec)
**Date:** 2026-08-28
**Deciders:** Planner stage (per the brief's explicit instruction to investigate both options and justify the choice)

### Context

Gap #1 names two distinct regression risks: "route wiring" and "drawer order." The brief asks to investigate whether a JVM-unit-level approach can adequately cover both before defaulting to instrumented-only.

### Decision

Split the two risks across two different test tiers, because they have genuinely different testability properties in this codebase's current structure:

- **Route wiring / `createRoute()` encoding** → covered entirely by a **JVM unit test** against the `Screen` sealed class. `Screen` and its `createRoute()` functions are plain Kotlin (string building + `java.net.URLEncoder`), with no Android/Compose/Hilt dependency — fully testable and fully runnable in this sandbox today.
- **Drawer order / click-to-navigate reachability** → cannot be verified at the JVM level without a production refactor. The drawer's `NavigationDrawerItem` calls are inline Composable statements inside `AppNavigation()`'s body — there is no separately-testable data structure representing "the ordered list of drawer items" to assert against at the JVM level. Verifying actual rendering order requires composing the real UI tree, which requires an **instrumented Compose test**.

### Options Considered

#### Option A: JVM-only (skip drawer-order coverage)
| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Cost | Low (no new infra) |
| Coverage | Partial — route typos only, drawer-order drift remains uncaught |
| Sandbox-runnable | Yes, fully |

**Pros:** Zero new infrastructure, zero risk, fast.
**Cons:** Leaves half of gap #1's named regression risk (drawer order) genuinely uncovered — doesn't satisfy the brief's ask to investigate before defaulting away from instrumented coverage.

#### Option B: Instrumented Compose test using the app's real Hilt graph (chosen for Task Group C)
| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium — first Hilt-instrumented test in the project, but the app's Hilt graph is already fully wired for real device use (proven to launch clean on the AVD per `gaps.md` 2026-08-22), so no fake/test DI bindings are needed — just the standard `HiltTestApplication` + `HiltAndroidRule` bootstrap |
| Cost | One-time infra addition (~3 new/changed files: `libs.versions.toml`, `app/build.gradle.kts`, `CustomTestRunner.kt`) + one test file |
| Coverage | Full — both route reachability and drawer order, verified against actual rendered output |
| Sandbox-runnable | Compile-check only in this pipeline's sandbox; real execution needs Skyler's AVD |

**Pros:** Actually covers the named regression risk in full; reuses `MainActivity` (already `@AndroidEntryPoint`) directly via `createAndroidComposeRule<MainActivity>()` rather than needing fake bindings for every screen's ViewModel.
**Cons:** New infra surface; only compile-verified here, not run-verified, until Skyler runs `connectedAndroidTest` himself.

#### Option C: Refactor drawer content into a separately-testable composable, unit-test that in isolation
Rejected outright — this is a production code change to enable testing, explicitly out of scope per the brief's "no production code should need to change" constraint.

### Trade-off Analysis

Option A is strictly cheaper but leaves the ask half-done. Option B costs one real (but bounded and de-risked) infrastructure addition in exchange for actually closing the gap as named. Given `gaps.md`'s own framing ("no test coverage on route wiring/drawer order **at all**") and the brief's explicit instruction not to default away from instrumented testing without checking adequacy first, Option B is the correct call — scoped as a Should-have (Task Group C) so it can be deprioritized without blocking the Must-have JVM coverage (Task Groups A/B) if it proves harder than expected mid-build.

### Consequences

- This project gains its first Hilt-instrumented-test capability, reusable for any future instrumented UI test (not just this one).
- The new `CustomTestRunner` and `testInstrumentationRunner` change affect the whole `androidTest` source set — Coder must confirm the existing non-Hilt instrumented tests (DB/migration) still compile under the new runner.
- Task Group C's test can only be compile-verified until Skyler runs it on his own AVD — this is stated explicitly in the acceptance criteria and should not be overclaimed as "passing" by any pipeline stage.

---

## Technical Plan (dependency-ordered tasks)

### Task Group A — `QuickScanViewModelTest.kt` expansion (Must-have, size M)

**File to edit:** `app/src/test/java/com/skyler/pokedexbinder/ui/QuickScanViewModelTest.kt` (existing file, append new `@Test` methods; reuse existing `mockk`/`UnconfinedTestDispatcher`/`viewModel()` helper — extend the helper if a test needs a non-default `slotId`, which it already supports via the existing `viewModel(slotId: String = "")` parameter).

No production code changes to `QuickScanViewModel.kt` (`app/src/main/java/com/skyler/pokedexbinder/ui/quickscan/QuickScanViewModel.kt`) unless a genuine bug is found — flag separately if so.

Test cases to add (dependency-clean, each independently runnable):

1. **`search()` query routing** — capture the `fastSearch` lambda `search()` builds and verify it calls the right repository method:
   - Numeric string (e.g. `"25"`) → `cardSearchRepository.searchByDexNumber(25)`.
   - Promo-number-shaped string (e.g. `"SWSH001"`, matching `Regex("^[A-Za-z]{2,5}\\d{1,4}$")`) → `cardSearchRepository.searchByNumber("SWSH001")`, with a sub-case confirming `.ifEmpty { searchByName(...) }` fallback fires when `searchByNumber` returns empty.
   - Plain name (e.g. `"Pikachu"`) → `cardSearchRepository.searchByName("Pikachu")`.
   - Technique: `val slot = slot<suspend () -> List<TcgCard>>(); coEvery { cardSearchRepository.searchStreaming(any(), capture(slot)) } returns flowOf(...)`, then after calling `vm.search(...)`, invoke `slot.captured()` inside the `runTest` coroutine and `coVerify` the right downstream call fired.
2. **`init` block auto-search** — construct `viewModel(slotId = "bulbasaur")` with `binderRepository.getSlotByPokemonId("bulbasaur")` mocked to return a `PokemonSlot` fixture; assert `cardSearchRepository.searchStreaming` was called with the slot's `effectiveSearchName` (test at least one `SlotType` where `effectiveSearchName` differs from `name`, e.g. `REGIONAL` "Alolan Vulpix" → "Vulpix", to actually exercise the transform, not just pass `name` through by coincidence). Second case: `getSlotByPokemonId` returns `null` → `cardSearchRepository.searchStreaming` never called, state stays `Idle`.
3. **`selectCard()` — slot pre-selected (`targetSlotId` non-blank)**: from a `CardSelection(cards, ...)` state, assert result is `CardConfirm(card, cards, isReplace)`; from a non-`CardSelection` state (e.g. `Idle`), assert result falls back to `CardConfirm(card, listOf(card), isReplace)`. Confirm `assignCardUseCase.assign` is NOT called yet (assignment happens later via `confirmCard`).
4. **`selectCard()` — no slot pre-selected (`targetSlotId` blank)**: mock `binderRepository.getAllSlots()` to return slot fixtures.
   - Zero matches (`card.pokemonNames` don't substring-match any slot name) → `NoSlot(card)`.
   - Exactly one match → `assignCardUseCase.assign` called with that slot's id and the card, state becomes `Success(card, slot.name)`.
   - Two+ matches (e.g. a card with `pokemonNames = listOf("Charizard")` matching both a `SlotType.BASE` "Charizard" slot and a `SlotType.MEGA` "Mega Charizard X" slot, since matching is `slot.name.lowercase().contains(name.lowercase())`) → `SlotSelection(card, matchingSlots)`.
5. **`confirmCard(card)`** — with a slot pre-selected via `init` (so `targetSlotName` is populated), assert `assignCardUseCase.assign(targetSlotId, card)` called and state becomes `Success(card, targetSlotName)`.
6. **`wrongCard(allCards)`** — assert state becomes `CardSelection(allCards)` (default `stillSearching = false`).
7. **`assignToSlot(slot, card)`** — assert `assignCardUseCase.assign(slot.id, card)` called and state becomes `Success(card, slot.name)`.
8. **`addToSecondary(card)`** — secondary-binder fallback. Assert `secondaryBinderDao.insertAtEnd(...)` called with a `SecondaryBinderEntry` whose `pokemonId` is `card.pokemonNames.firstOrNull() ?: ""`, `pokemonName = card.name`, `cardId = card.id`, `cardImageUrl = card.imageUrl`; state becomes `Success(card, "Secondary Binder")`. Add a second case with `pokemonNames = emptyList()` to exercise the `?: ""` fallback explicitly.
9. **`showManualEntry()`** — after `vm.search("Eevee")`, call `showManualEntry()`, assert state becomes `ManualEntry(prefillName = "Eevee")`.
10. **`confirmCustomCard(name, setName, number, imageUrl)`** — manual entry flow:
    - With `targetSlotId` non-blank (slot pre-selected via `init`) → assert `assignCardUseCase.assign` called with a `TcgCard` matching the given fields (id starts with `"custom_"`, don't assert the exact timestamp suffix) and state becomes `Success`.
    - With `targetSlotId` blank → assert state becomes `CardSelection(listOf(customCard))` (single-element list, no assign call).
11. **`retry()`** — after a prior `search("Squirtle")`, mock a second `searchStreaming` response, call `retry()`, assert it re-runs with `lastQuery`. Separately: `retry()` on a fresh ViewModel with no prior search (`lastQuery` blank) → state becomes `Idle`, no repository call.
12. **`reset()`** — from any non-`Idle` state, assert `reset()` sets state to `Idle`.

**Edge cases explicitly required:** empty `pokemonNames` list (task #8), no-match vs. single-match vs. multi-match slot resolution (task #4), `init` with null slot lookup (task #2), `retry()` with no prior query (task #11), case-insensitive substring matching in `findMatchingSlots` (task #4, via mixed-case fixture names).

---

### Task Group B — `Screen` route/encoding JVM test (Must-have, size S)

**New file:** `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationRouteTest.kt` (the existing empty `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/` directory already exists — use it).

Pure JVM, no Android/Compose/Hilt/mockk dependency needed — `Screen` and `createRoute()` are plain Kotlin using only `java.net.URLEncoder`/`java.nio.charset.StandardCharsets`.

Test cases:

1. **Static routes** — `Screen.MainBinder.route == "main_binder"`, `Screen.SecondaryBinder.route == "secondary_binder"`, `Screen.ConnectingArt.route == "connecting_art"`, `Screen.Unown.route == "unown"`, `Screen.PersonalCollection.route == "personal_collection"`, `Screen.Settings.route == "settings"`, `Screen.AddToSecondary.route == "add_to_secondary"` — each unique (assert the full set has no duplicates, catching a copy-paste route-string collision).
2. **`Screen.ConnectingArtSearch.createRoute(slotId: Int)`** — e.g. `createRoute(42) == "connecting_art_search/42"`; assert the output matches the route template `"connecting_art_search/{slotId}"`'s placeholder position (a small helper regex or manual prefix/suffix check).
3. **`Screen.UnownSearch.createRoute(letterId: String)`** — plain letter (e.g. `"A"`) encodes to `"unown_search/A"`; a letterId containing a space encodes the space as `%20` (matching the `.replace("+", "%20")` override of `URLEncoder`'s default `+`); confirm against `UnownBinderRepository.searchNameFor`'s actual letterId format (read that function to use realistic fixture values, e.g. actual Unown letter IDs like `"!"`/`"?"` if those exist, to catch real special-character encoding behavior, not just an arbitrary test string).
4. **`Screen.SlotDetail.createRoute(slotId: String)`** — e.g. `createRoute("bulbasaur") == "slot_detail/bulbasaur"`.
5. **`Screen.QuickScan.createRoute(...)` defaults** — `createRoute()` with no args produces `slotId=` (empty, encoded) and `replacing=false`, matching the `navArgument("slotId") { defaultValue = "" }` / `navArgument("replacing") { defaultValue = false }` declarations in `AppNavigation.kt`; a call with a non-empty `slotId` containing a space confirms encoding.
6. **`Screen.Scanner.createRoute(...)` defaults and encoding** — same pattern as QuickScan, plus confirm `pokemonName` with a space and/or special character (e.g. `"Mr. Mime"`) encodes correctly (space → `%20`), matching real usage from `ScannerScreen`/`SlotDetailScreen` call sites.
7. **Route-template placeholder consistency** — for each parameterized `Screen` object, assert its `route` string's `{argName}` placeholders match the argument names used in `AppNavigation.kt`'s corresponding `navArgument(...)` calls (hardcode the expected argument names as constants in the test, e.g. `{slotId}`, `{letterId}`, `{pokemonName}`, `{isSecondary}` — this is the direct "route typo" catch named in the brief).

---

### Task Group C — Hilt instrumented Compose test infra + `AppNavigationScreenTest.kt` (Should-have, size L)

#### C1 — Add Hilt-testing infrastructure (INFRA, size M)

**Files to edit:**
- `gradle/libs.versions.toml`: add `hilt-android-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }` to `[libraries]` (reuse the existing `hilt = "2.51.1"` version ref — do not introduce a new version).
- `app/build.gradle.kts`: add `androidTestImplementation(libs.hilt.android.testing)` and `kspAndroidTest(libs.hilt.compiler)` alongside the existing `// Tests` block's `androidTestImplementation` lines; change `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` to point at the new custom runner's fully-qualified name (e.g. `"com.skyler.pokedexbinder.CustomTestRunner"`).

**New file:** `app/src/androidTest/java/com/skyler/pokedexbinder/CustomTestRunner.kt`:
```kotlin
package com.skyler.pokedexbinder

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class CustomTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```

**Edge case / verification requirement:** after this change, the existing non-Hilt instrumented tests (`DatabaseBackupManagerInstrumentedTest.kt`, `Migration5to6Test.kt`, `Migration6to7Test.kt`, `Migration8to9Test.kt`, `PokedexDatabaseTest.kt`, `DatabaseModuleWiringTest.kt`) must still compile under the new runner — `HiltTestApplication` is a drop-in `Application` replacement, but Coder must confirm via a full `androidTest` source-set compile (not just the new file) that nothing broke.

#### C2 — `AppNavigationScreenTest.kt` (TEST, size L)

**New file:** `app/src/androidTest/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt`.

```kotlin
package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.skyler.pokedexbinder.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class AppNavigationScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    // test methods below — see acceptance criteria for exact scenarios
}
```

Test scenarios (compile-check only in this sandbox; real pass/fail requires `connectedAndroidTest` on Skyler's AVD):

1. **Drawer opens and shows all 5 items in order.** Click the "Menu" content-description icon (`composeTestRule.onNodeWithContentDescription("Menu").performClick()`), then assert `onNodeWithText("Pokédex")`, `"Connecting Art"`, `"Personal Collection"`, `"Unown"`, `"Card History"` all exist. Order check: use `composeTestRule.onAllNodes(hasText(...) or ...)` — or simpler, fetch each node's `boundsInRoot().top` via `onNodeWithText(x).fetchSemanticsNode().boundsInRoot.top` and assert strictly increasing top-to-bottom in the listed order.
2. **Each drawer item navigates to its screen.** For each of the 4 non-start-destination items (Connecting Art, Personal Collection, Unown, Card History), open the drawer, click the item, assert the corresponding `TopAppBar` title text appears (`"Connecting Art"`, `"Personal Collection"`, `"Unown"`, `"Card History"`), then navigate back (`Espresso.pressBack()` or re-launch — pick whichever is simpler/more reliable) before testing the next item, OR split into 4 separate `@Test` methods each re-launching `MainActivity` fresh (preferred — independently testable per the Task Decomposition Rules, avoids state bleed between assertions).
3. **Bottom nav "Pokédex" always present; "Scan" absent by default.** Assert `onNodeWithText("Pokédex")` exists (bottom nav label) and `onNodeWithContentDescription("Scan")` does NOT exist, matching the fresh-DataStore default `useCameraScanner = false`.

**Known constraint to state plainly in the Coder's output and the Tester's report:** this file will be compile-checked in this sandbox only (`./gradlew :app:compileDebugAndroidTestKotlin` or equivalent — confirm the exact task name during implementation); it cannot be executed here since there is no attached emulator/device in this pipeline's sandbox. Do not report it as "passing" — report it as "compiles cleanly, structurally correct, execution pending Skyler's own `connectedAndroidTest` run."

---

## Dependency order for the Coder stage

1. Task Group B (nav route JVM test) — no dependencies, fastest to verify, do first to establish a quick win.
2. Task Group A (QuickScanViewModel expansion) — no dependencies on B or C, can be done in parallel/either order.
3. Task Group C1 (Hilt testing infra) — must precede C2.
4. Task Group C2 (AppNavigationScreenTest) — depends on C1.

After all four: run `testDebugUnitTest` (must be 182 + all new JVM tests, all green — delete `app/build/test-results/testDebugUnitTest/` first per this project's own standing lesson before trusting any count) and compile-check the full `androidTest` source set.

---

## Changelog

| Date | Author | Change |
|---|---|---|
| 2026-08-28 | Planner (dev-team-pipeline Stage 1) | Initial draft |

---

## Friction Notes

- Serena's Kotlin language server failed to initialize for this project (`Error extracting archive` on `get_symbols_overview`) — every symbolic lookup in this session fell back to plain `Read`/`Grep`/`Bash`. Worth a one-time fix attempt (clear whatever cached archive Serena's Kotlin LSP extracts, or check for a stale/corrupt JAR in its language-server cache) so future sessions on this project get the token-efficiency benefit Serena is meant to provide instead of silently degrading to slower whole-file reads every time.
- `git log --all -- "**/AppNavigationScreenTest*"` returned nothing despite `gaps.md` describing the file as "deleted" — the file was apparently never actually committed to this repo's history (created and removed within an uncommitted working tree state, or squashed away entirely). Not a blocker, but worth noting so a future session doesn't waste time searching git history for a "previous version" to resurrect — there isn't one; `AppNavigation.kt`'s current structure is the only ground truth to test against.
