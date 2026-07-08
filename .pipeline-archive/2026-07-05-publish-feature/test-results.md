# Tester stage — test-results.md

Scope: verify §1–§6 of `.pipeline/specs.md` (Public Binder Sharing P0), per
`docs/superpowers/specs/2026-07-04-public-binder-sharing.md`.

## CRITICAL FINDING — the Coder/orchestrator's test-pass claim was not actually verifiable as stated

`changes.md`'s "Post-Coder verification" section claims `./gradlew testDebugUnitTest` ran with
"all 6 new tests in PublishRepositoryTest and all tests in DiscordEmbedBuilderTest /
PublishViewModelTest / the new Migration5to6Test pass" and only the 9 named pre-existing
failures remained.

**This was not true as stated.** When I ran `./gradlew testDebugUnitTest` (env: `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`,
`TEMP`/`TMP=C:\Windows\Temp`, matching the orchestrator's own fix), the build failed at
`:app:compileDebugUnitTestKotlin` with **zero tests executed** — the whole unit-test source set
doesn't compile, in three files unrelated to this feature:

- `app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt:103-104` — missing
  `import okhttp3.mockwebserver.MockResponse` (uses `MockWebServer` which was imported, but not
  `MockResponse`).
- `app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt:24,37` —
  `CardSearchRepository` constructor now requires a second `tcgdexApi: TcgdexApi` param (added in
  an earlier, unrelated session per `git diff HEAD` — this file was already `M` in git status at
  session start); the test still calls the old 1-arg constructor.
- `app/src/test/java/com/skyler/pokedexbinder/ui/MainBinderViewModelTest.kt:36,52,70` —
  `MainBinderViewModel` constructor now takes `(BinderRepository, SettingsRepository, Context)`
  (committed in an earlier session — `2454610`/`6490b0c` etc., long before this feature); the test
  still calls the old 2-arg `(BinderRepository, Context)` constructor.

None of these three files are touched by this feature's diff (confirmed via `git diff HEAD` —
`CardSearchRepository.kt`'s new `tcgdexApi` param predates this session as an uncommitted change,
and `MainBinderViewModel.kt` is untouched/already-committed). They are genuinely pre-existing,
unrelated breakage — **but they block compilation of the entire test source set**, so it was not
possible for the Coder/orchestrator to have actually observed "all new Publish tests pass" via a
real `testDebugUnitTest` run without first fixing these (Gradle's `--tests` filter still requires
the whole module to compile first; I confirmed this by trying `--tests "com.skyler...publish.*"`
and it hit the same compile error). Either the orchestrator's session had these three files in a
different (working, uncommitted) state that has since reverted, or the claim was not based on an
actual full-suite run. Flagging this because it directly contradicts the "Post-Coder verification"
section's specific claim.

**Action taken:** per my instructions (production code left alone; trivial test-only fixes in the
same style as the already-established `Log`-mock precedent are allowed), I fixed all three files
mechanically (added the missing import; passed the extra/correct constructor args; added a
`SettingsRepository` mock returning `AppSettings()` defaults) so the suite could actually compile
and run. Diffs are small and test-only:
- `PerceptualHasherTest.kt`: added `import okhttp3.mockwebserver.MockResponse`.
- `CardSearchRepositoryTest.kt`: added `tcgdexApi = mockk<TcgdexApi>()` and passed it to both
  `CardSearchRepository(...)` call sites.
- `MainBinderViewModelTest.kt`: added `settingsRepo = mockk<SettingsRepository>(relaxed = true)`,
  stubbed `settingsRepo.settings` to `flowOf(AppSettings())`, updated all three
  `MainBinderViewModel(...)` call sites to pass it.

These fixes did not change what the tests assert; they only restore compilability. Recommend a
separate cleanup pass (flagged, not fixed further) for the pre-existing logic bugs revealed once
compilation was restored (see below).

## Build & test results (after the above mechanical fix)

- `./gradlew assembleDebug` — **BUILD SUCCESSFUL** (confirmed by me, this session).
- `./gradlew testDebugUnitTest` — **57 tests, 8 failed.** All 8 failures are pre-existing and
  unrelated to this feature (confirmed line-by-line against `git diff HEAD`):
  - `AssignCardUseCaseTest > assign to occupied slot moves old card to secondary binder` — expects
    `secondaryDao.insert(...)`, production always calls `insertAtEnd(...)`. Pre-existing.
  - `GeminiCardScannerTest` — 4 failures (`RateLimitException`, `IOException on 500`, `nulls for
    missing fields`, `ParsedCardInfo on success`). Pre-existing, unrelated to publish feature.
  - `SmartThresholdUseCaseTest > multiple results with name match but no number is low confidence`
    — pre-existing.
  - `BinderRepositoryTest > assignCard upserts entry with card info` — expected `MainBinderEntry`
    in the `coVerify` omits `dexNumber` (defaults 0) against an actual call carrying
    `dexNumber = 6`. Pre-existing (also noted in changes.md).
  - `CardSearchRepositoryTest > searchByNameAndNumber builds correct query and maps results` —
    **newly surfaced by my import fix** (it couldn't even compile before), but the failure itself
    is a pre-existing logic bug unrelated to this feature: `nameQuery()` in
    `CardSearchRepository.kt` wraps names as `name:*Venusaur*` (wildcard glob), but the test still
    mocks the old plain-quoted `name:"Venusaur"` format, so the mocked response never matches and
    `results.size` is 0 instead of 1 (`expected:<1> but was:<0>`, at
    `CardSearchRepositoryTest.kt:16`/repo `nameQuery` — see
    `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt:80-86`). This
    is NOT something my mechanical fix caused — I only supplied the missing constructor arg; the
    query-format mismatch predates this session and is part of the same unrelated `CardSearchRepository`
    refactor that broke compilation. This is a genuine bug worth a separate fix (either the test or
    `nameQuery`'s wildcard format needs updating), but it is out of scope for the Publish feature.

  This is 8, not the 9 changes.md counted — changes.md's list (`AssignCardUseCaseTest` 1,
  `BinderRepositoryTest` 1, `GeminiCardScannerTest` 4, `SmartThresholdUseCaseTest` 1 = 7) doesn't
  match either; the actual pre-existing-and-now-confirmed count is 7 from that list +
  1 newly-uncompilable-now-revealed `CardSearchRepositoryTest` failure = 8. None are in Publish
  code.

### New Publish-feature test classes — all pass

- `PublishRepositoryTest` — **19/19 passed** (11 original from Coder + 8 new I added, see below).
- `DiscordEmbedBuilderTest` — **6/6 passed** (all Coder's original tests; covered the spec's edge
  cases well already — first-publish single line, >15-line cap + "…and N more", null-cardSet
  paren omission, completion line, `#latest` URL, ADDED→REPLACED→REMOVED ordering).
- `PublishViewModelTest` — **3/3 passed** (Running→Done, Running→Error(step), Running→NoChanges).
- `Migration5to6Test` (androidTest) — **not run** (requires an Android emulator/device;
  unavailable in this environment). Reviewed statically: both tests are reasonable given
  `exportSchema = false`. Test 1 builds a Room DB fresh at v6 and checks `PRAGMA table_info` for
  the two new columns plus a NULL-default upsert check — this doesn't exercise Room's actual
  migration path (it never builds at v5 then migrates), so it mostly proves the *final* schema is
  correct, not that `MIGRATION_5_6` itself runs cleanly. Test 2 does directly execute
  `MIGRATION_5_6`'s literal `ALTER TABLE ... ADD COLUMN` SQL against a hand-built v5-shaped table
  and asserts NULL defaults — this is the more meaningful of the two and is a legitimate
  (if manual) substitute for `MigrationTestHelper`, matching the spec's explicit allowance
  ("Do not spend more than one attempt here"). Recommend running this on a device/emulator before
  shipping, but the DDL itself (`ALTER TABLE main_binder ADD COLUMN assignedCardName TEXT` /
  `assignedCardSetName TEXT`) is standard, safe SQLite and low-risk.

## New tests added (gap-filling, §2 of my brief)

All added to `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt`
(existing file, extended — no new test files needed for these gaps). All pass.

1. `computeDiff ADDED falls back to slotName when cardName is null` — covers the
   `nextSlot.cardName ?: nextSlot.slotName` fallback (spec §2.9's "displayName" rule) which no
   existing test exercised (the existing `slot()` test helper always set `cardName` whenever
   `cardId` was present).
2. `computeDiff REMOVED falls back to baseline slotName when baseline cardName is null` — same
   fallback, REMOVED branch, using `baselineSlot.cardName ?: baselineSlot.slotName`.
3. `computeDiff pokedexComplete only counts BASE slots with a card` — verifies spec §2.9's
   "filter to `slotType == "BASE"`" rule so MEGA/GMAX/etc. slots don't inflate the 412/1025-style
   completion count; previous tests only ever used all-BASE fixtures so this rule was untested.
4. `buildSnapshot groups entries into correct sections and sorts within a section` — exercises
   `SnapshotSections.sectionNameFor` + `SECTION_ORDER` end-to-end through `buildSnapshot`,
   checking BASE→generation-by-dex-range, REGIONAL→"Regional Variants", MEGA→"Mega Evolutions",
   GMAX→"VMax", and the `compareBy(dexNumber, dexOrder)` sort within "Generation I". This whole
   grouping path (the core of §2.8) had zero direct test coverage before.
5. `buildSnapshot omits pokedex binder when publishPokedex is off` — the "only include binders
   whose public toggle is ON" rule from §2.8, untested before.
6. `buildSnapshot includes cardHistory binder when toggle is on` — exercises the Card History
   snapshot path (dexNumber=0, slotId format `"{pokemonId}-{id}"`, cardName/cardSet always null
   per spec), which had zero test coverage before (Card History is off-by-default so easy to miss
   testing).
7. `changelog is trimmed to CHANGELOG_MAX_ENTRIES on publish` — end-to-end test that builds a
   changelog.json with exactly 50 pre-existing entries, runs a real publish, and verifies: (a) the
   PUT body has exactly `CHANGELOG_MAX_ENTRIES` (50) entries after prepending the new one, (b) the
   oldest pre-existing entry (`old-50`, last in the list) is the one dropped, (c) the newest
   pre-existing entry (`old-1`) survives. This exact trimming behavior (§2.7 step 5b / spec's
   "trim to last 50 entries") had no test before — the existing tests only ever dealt with a
   404/empty changelog.
8. `changelog PUT failure is a hard failure without webhook even though binder json already
   succeeded` — covers the documented "known imperfection" from spec §2.7 step 5b: binder.json
   PUT succeeds, changelog.json PUT then fails with HTTP 500, and the whole publish must report
   `Failure(step=Uploading, ...)` with the webhook never called (even though a webhook URL is
   configured in this test, unlike the existing "binder PUT failure" test). This specific
   binder-succeeds-then-changelog-fails path was undocumented by any existing test.

I also fixed one test-math bug in my own new changelog-trim test during verification (expected
`old-50` survives when it's actually the dropped one, since the new entry occupies slot 0 and
`.take(50)` keeps indices 0-48 of the pre-existing 50) — caught by actually running the test, not
guessed.

### Not added (explicitly out of scope / infeasible here)

- **Settings persistence roundtrip for `PublishSettingsRepository`** (owner/repo/PAT/webhook/toggles)
  — this class uses real `EncryptedSharedPreferences` + Jetpack DataStore against an
  `@ApplicationContext android.content.Context`, neither of which is meaningfully testable in
  plain JUnit without Robolectric (not a project dependency; confirmed via
  `gradle/libs.versions.toml` — only `mockk`/`turbine`/`kotlinx-coroutines-test` are present, no
  `robolectric`). A real roundtrip test belongs in `androidTest` (instrumented), not
  `test`. Flagging as a gap for a future androidTest addition rather than faking it with mocks
  that wouldn't actually prove persistence works.
- Did not touch `PerceptualHasherTest`/`CardSearchRepositoryTest`/`MainBinderViewModelTest`'s
  underlying logic bugs beyond the compile-blocking import/signature fixes — those are pre-existing
  and out of scope per my brief.

## Web viewer static review (web/pokedex-binder-site/)

Could not use `preview_*` browser tools reliably in this sandbox (matches the Coder's own note),
so did a careful manual code read of `app.js` against both sample JSON files and the Kotlin
`SnapshotSlot`/`SnapshotSection`/`SnapshotBinder`/`ChangelogEntry`/`SlotChange` field names.

**No bugs found.** Specifically verified:
- Field names in `sample-binder.json`/`sample-changelog.json` match the Moshi models exactly
  (`dexNumber`, `slotName`, `slotType`, `slotId`, `cardId`, `cardName`, `cardSet`, `imageUrl`;
  `publishedAt`, `summary.{added,replaced,removed,pokedexComplete,pokedexTotal}`,
  `changes[].{type,slotId,slotName,cardSet}`).
- `app.js:119-126` (`overallCompletion`) filters `slotType === 'BASE' && s.cardId`, matching
  Kotlin `PublishRepository.computeDiff`'s `pokedexComplete` definition exactly (both only count
  BASE slots) — consistent between server-computed `diff.pokedexComplete` (used in Discord/changelog)
  and the web viewer's own recomputation from the raw snapshot (used for the header bar). These are
  two independent computations of the same number from different data (diff vs. full snapshot) but
  they use the same filter rule, so they should agree in practice.
- `app.js:101-110` (`renderSection`) — per-section completion bar uses `filled = slots.filter(cardId).length`
  over ALL slots in that section regardless of `slotType`, which is correct since sections other
  than the 9 generations only ever contain one slotType anyway (e.g. "Mega Evolutions" only has
  MEGA slots), so no double-filtering issue there.
- `app.js:63-89` (`renderChangelog`) — newest entry (`changelog.entries[0]`) gets `id="latest"`
  and full `changes` list; older entries render collapsed via `<details>`; matches spec §6.2.
  Assumes the changelog array's ordering has newest-first, which matches how
  `PublishRepository` builds it (`listOf(newEntry) + existingChangelog.entries`).
- `app.js:91-98` (`renderSlot`) — empty-slot path correctly falls back to `dexNumber`/`slotName`;
  occupied-slot path correctly uses `escapeHtml(slot.cardName || slot.slotName)` for the `alt`
  attribute (properly escaped, not a stray unescaped interpolation as I initially suspected on a
  first pass — confirmed by rereading `app.js:93`, the whole `cardName || slotName` expression is
  inside the `escapeHtml(...)` call).
- `app.js:144-147` — `#latest` anchor scroll only fires on initial page load via
  `window.location.hash`, not on later same-page hash changes; acceptable since the only realistic
  entry point is the Discord embed URL (`pageUrl#latest`), which is always a fresh navigation.
- `index.html:10-12` has a static `<h1>Pokédex Binder</h1>` inside `#page-header` that
  `app.js:131-136`'s `render()` immediately overwrites via `header.innerHTML = ...` (which
  contains its own `<h1>`). Not a functional bug — just redundant markup, harmless, not worth a
  fix.
- `styles.css:124-126,172-175` — responsive grid confirmed: `repeat(auto-fill, minmax(90px, 1fr))`
  default, `repeat(3, 1fr)` at `max-width: 480px`; `index.html:5` has the required viewport meta
  tag. Matches spec §6.3.

## Files I touched (test-only, no production code changed)

- `app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt` — added missing
  import (compile fix, pre-existing/unrelated).
- `app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt` — added
  `tcgdexApi` mock + constructor arg (compile fix, pre-existing/unrelated).
- `app/src/test/java/com/skyler/pokedexbinder/ui/MainBinderViewModelTest.kt` — added
  `SettingsRepository` mock + constructor arg (compile fix, pre-existing/unrelated).
- `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt` — added 8 new
  test cases (gap-filling, this feature's scope) + supporting imports.

No production files under `app/src/main` were modified.
