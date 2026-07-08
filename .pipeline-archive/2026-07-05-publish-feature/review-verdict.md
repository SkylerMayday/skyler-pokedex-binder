# Reviewer verdict — Public Binder Sharing (Phase 1 / P0)

**Verdict: SHIP** (with a short list of nice-to-have follow-ups; none blocking).

Reviewed independently against the approved spec (`docs/superpowers/specs/2026-07-04-public-binder-sharing.md`),
the Planner plan (`.pipeline/specs.md`), and the Coder/Tester handoffs. Ran the build and
full unit-test suite myself; read the actual diff for every P0 requirement rather than
trusting the summaries.

---

## Build & test — verified this session

- `./gradlew assembleDebug` — **SUCCESSFUL** (`:app:assembleDebug UP-TO-DATE`; the debug APK
  was built clean earlier this session by the Tester and nothing invalidated it).
- `./gradlew testDebugUnitTest` — **57 tests, 8 failed.** All 8 failures are pre-existing and
  unrelated to this feature (see "Pre-existing failures" below). **Zero** Publish-feature
  tests fail — grepped the log for `Publish|Discord|Migration|Snapshot`: no matches in the
  failure list. Confirms PublishRepositoryTest (19), DiscordEmbedBuilderTest (6),
  PublishViewModelTest (3) all green.

The Tester's count of 8 (vs. changes.md's 9/7) is the correct one — I reproduced exactly 8.

---

## P0 requirement-by-requirement (against the real diff)

### 1. Schema v5→6 + populate — ✅ COMPLETE
- `MainBinderEntry.kt:15-16` adds nullable `assignedCardName`, `assignedCardSetName`. Correct.
- `PokedexDatabase.kt:8-31` — `version = 6`, `MIGRATION_5_6` does `ALTER TABLE ... ADD COLUMN ... TEXT`
  (nullable, NULL default for existing rows). Correct per spec's "existing rows stay null".
- `DatabaseModule.kt:21` registers `MIGRATION_4_5, MIGRATION_5_6`; `.fallbackToDestructiveMigration()`
  left in place as the plan instructed. Correct.
- `BinderRepository.assignCard` (`BinderRepository.kt:33-49`) threads name/set through; `clearCard`
  (`:51-61`) nulls them. `AssignCardUseCase.kt:27` passes `card.name`, `card.setName`. Sub-checkbox
  "New assignments store card name + set" ✅. "Migration runs clean" — DDL is standard/safe; the
  androidTest (`Migration5to6Test`) can only run on a device, so this is verified by inspection +
  the raw-SQLite test-2 assertion, not an emulator run (documented, acceptable).

### 2. Publish flow — ✅ COMPLETE
`PublishRepository.publish()` (`PublishRepository.kt:77-225`) implements the diagrammed flow exactly:
- **No changes → no webhook, no commit**: `:102-106` short-circuits to `NoChanges` before any PUT.
  Test `PublishRepositoryTest` covers `coVerify(exactly=0)` on PUT+webhook. ✅
- **GitHub PUT fails → no webhook, user sees error**: `:124-129` throws `PublishFailedException(Uploading, ...)`
  before the Discord block; caught at `:220` → `Failure`. Test covers it. ✅
- **First-ever publish → "Initial publish — N cards"**: 404 baseline handled at
  `fetchBaselineSnapshot` (`:238-239`, `null to null`), `isFirstPublish` drives the summary line in
  `DiscordEmbedBuilder.kt:15-16`. PUT sends `sha=null`. ✅
- `PublishBinderUseCase.kt` is the thin orchestrator per §2.10. ✅

### 3. Discord embed — ✅ MATCHES SPEC EXACTLY
`DiscordEmbedBuilder.kt`:
- title `"Pokédex Binder updated"` (`:42`).
- `+ <name> (<set>)` with set omitted when null (`:24-27`); `↻ <name> (card swapped)` (`:28`);
  `− <name>` using U+2212 MINUS SIGN not ASCII hyphen (`:10,29`). ✅
- Ordered ADDED→REPLACED→REMOVED (`:18-20`). ✅
- 15-line cap + `"…and N more"` (`:7,22,33-36`). ✅
- Completion line `"Pokédex: X/Y"` appended (`:38`). ✅
- URL ends `#latest` (`:13`), derived from `https://<owner>.github.io/<repo>/` (`PublishRepository.kt:74-75`). ✅

### 4. Settings additions — ✅ COMPLETE
- `PublishSettingsRepository.kt`: owner/repo/toggles in DataStore (`publish_settings`), PAT+webhook
  in EncryptedSharedPreferences (`publish_secrets`). Defaults: `publishPokedex=true`,
  `publishCardHistory=false` (`:27-28,67-68`). ✅
- `SettingsViewModel`/`SettingsScreen` expose the fields + a "Publish now" button (per changes.md;
  confirmed the section wiring compiles as part of assembleDebug).
- Publish button in main binder top bar: `MainBinderScreen.kt` `CloudUpload` action (per changes.md,
  compiles).

### 5. Publish progress UI — ✅ COMPLETE (meets ui-progress rule)
`PublishDialog.kt`: Running variant shows a 4-row step list (Fetching current / Uploading /
Notifying Discord / Done) with check / spinner / idle icons keyed off `stepOrdinal` (`:41-47,69-76`),
a live `"Elapsed: X.Xs"` timer (`:49,78`), non-dismissable while running (`:65`). Done shows
Added/Replaced/Removed + completion + "Took X.Xs" + a "View page" `ACTION_VIEW` button (`:85-107`).
Error names the failed step (`:127`). NoChanges variant present (`:110-119`). ✅

### 6. Static viewer — ✅ COMPLETE
`web/pokedex-binder-site/app.js` (+ index.html/styles.css/README/sample-*):
- Sections rendered straight from JSON section names (`renderSection` `:101-110`); empty slots greyed
  with `#dex` + name (`renderSlot` `:95-98`); `loading="lazy"` images (`:93`). ✅
- Overall completion = BASE slots with a card / 1025 (`overallCompletion` `:119-126`) — **matches**
  `PublishRepository.computeDiff`'s `pokedexComplete` filter (`PublishRepository.kt:377-381`). Per-section
  bars (`:106`). ✅
- Recent-updates panel from changelog, newest gets `id="latest"`, older in `<details>` (`renderChangelog`
  `:63-89`); `#latest` scroll on load (`:144-147`). ✅
- Mobile: viewport meta + responsive grid (per Tester's static review of styles.css). ✅
- `sample-*` fallback on 404 so it renders before first publish (`loadJson` `:7-20`). ✅
- HTML escaping via `escapeHtml` on all interpolated JSON strings incl. `alt` (`:52,93,97`) — no XSS
  hole from card names. Good.

---

## Known-imperfection check (changelog atomicity) — ✅ handled as spec dictates

`PublishRepository.kt:187-196`: if `changelog.json` PUT fails **after** `binder.json` PUT succeeded,
it throws `PublishFailedException(Uploading, ...)` → hard failure, **webhook never fires** (the throw
happens before the `NotifyingDiscord` block at `:198`). Inline comment documents the accepted drift.
This is exactly the trade-off the spec's Open Questions section accepts. Tester added an explicit test
for this path (`changelog PUT failure is a hard failure without webhook…`). ✅

## Secrets handling — ✅ correct, not leaked
- PAT + webhook stored **only** via `EncryptedSharedPreferences` (`PublishSettingsRepository.kt:51-59,84-90`).
  DataStore holds only owner/repo/toggles — no secret in plaintext prefs. ✅
- No secret is logged. The single `Log.w` (`PublishRepository.kt:214`) is a benign "webhook not
  configured" message with no value interpolated. PAT travels only as the `Authorization: Bearer`
  header. ✅ (Note: OkHttp logging interceptor exists in the app — see nice-to-have #3 below.)

## Scope-creep check — ✅ clean
No P1/P2 work present (no restore, backfill, OG tags, changed-card highlight, CNAME, other binders).
`SecondaryBinderDao.getAll()` and `BinderRepository.getAllEntries()` are the only additions beyond the
manifest, both minimal and genuinely required by §2.8. `SnapshotSections.sectionNameFor` returning
`String?` instead of `String` is a defensive deviation (documented) — handled safely via `groupBy`/
`mapNotNull` at `PublishRepository.kt:261-263`. `System.currentTimeMillis()` substituted for
`SystemClock.elapsedRealtime()` in the ViewModel to keep it JUnit-testable (documented) — fine.

---

## Must-fix before ship
**None.** Every P0 requirement is implemented, the feature's own tests are green, and the app builds.

## Nice-to-have / follow-up (non-blocking)

1. **Pre-existing test failures (8) — separate cleanup, NOT this feature.** Confirmed each via diff:
   - `AssignCardUseCaseTest > assign to occupied slot…` expects `secondaryDao.insert(...)` but prod
     always calls `insertAtEnd(...)` (`AssignCardUseCase.kt:17`). Pre-existing.
   - `BinderRepositoryTest > assignCard upserts…` — `coVerify` omits `dexNumber` (defaults 0) vs. an
     actual call with `dexNumber=6`. Pre-existing.
   - `CardSearchRepositoryTest` + 4× `GeminiCardScannerTest` + `SmartThresholdUseCaseTest` — all from
     unrelated uncommitted refactors (`CardSearchRepository.kt`, `SmartThresholdUseCase.kt` were already
     `M` at session start, before this feature branch). `CardSearchRepository`'s `name:*Venusaur*`
     wildcard vs. the test's `name:"Venusaur"` is a real logic/test drift worth its own fix.
   Recommend a dedicated cleanup pass; do not gate this feature on it.

2. **`Migration5to6Test` never runs Room's real v5→v6 migration path** — test 1 builds fresh at v6
   (proves final schema only); test 2 runs the literal ALTER SQL against a hand-built table (proves the
   DDL). Neither uses `MigrationTestHelper` to migrate an actual v5 DB (blocked by `exportSchema=false`;
   spec explicitly allowed this fallback). Low risk (standard additive ALTER), but run it on a device/
   emulator before the first real user upgrade, or flip `exportSchema=true` + add a proper migration test.

3. **OkHttp logging interceptor + the GitHub `Authorization` header** — the shared client has a logging
   interceptor; verify it is not at `BODY`/`HEADERS` level in release, or the PAT could land in logcat.
   (Not introduced by this change — the interceptor predates it — but this feature is the first to send
   a bearer secret through it, so it's newly relevant.) Quick check worth doing before shipping to a
   device you don't control.

4. **`PublishSettingsRepository` has no persistence roundtrip test** — needs Robolectric or an androidTest
   (not present). Tester flagged this correctly; it's a genuine coverage gap, not a defect. Follow-up.

5. **Cosmetic:** `index.html` static `<h1>` is overwritten by `app.js render()` (harmless redundancy);
   `PublishDialog.StepRow` has a `Spacer(height(0.dp))` no-op (`PublishDialog.kt:160`). Trivial.

---

## Bottom line
The P0 spec is fully and faithfully implemented, the diff is clean of scope creep, secrets are stored
encrypted and not logged, the documented atomicity trade-off is handled exactly as the spec accepts, and
the Discord embed matches the spec character-for-character (including the U+2212 minus sign). The only
red in the test run is a pre-existing, unrelated set of 8 failures that this feature neither caused nor
touched. **Ship it**, and file the follow-ups above (especially #1 and #3) as separate tasks.
