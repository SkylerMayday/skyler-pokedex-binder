# Test Results — Card Name/Set Backfill

## 1. Build check

```
Set-Location "D:\Claude Projects\PokedexBinderV2"
$env:JAVA_HOME = "D:\jdk17\jdk-17.0.14+7"
$env:TEMP = "C:\Windows\Temp"; $env:TMP = "C:\Windows\Temp"
& .\gradlew.bat assembleDebug testDebugUnitTest --console=plain
```

- `assembleDebug`: **PASS** (UP-TO-DATE, compiles clean).
- `testDebugUnitTest`: **PASS overall** — 89 tests run, 8 failed. All 8 failures are the exact pre-existing known-unrelated set (verified name-for-name against the Coder's report and reran to confirm no drift):
  - `AssignCardUseCaseTest > assign to occupied slot moves old card to secondary binder`
  - `GeminiCardScannerTest > scan throws RateLimitException on 429`
  - `GeminiCardScannerTest > scan throws IOException on 500`
  - `GeminiCardScannerTest > scan returns nulls for missing fields`
  - `GeminiCardScannerTest > scan returns ParsedCardInfo on success`
  - `SmartThresholdUseCaseTest > multiple results with name match but no number is low confidence`
  - `BinderRepositoryTest > assignCard upserts entry with card info`
  - `CardSearchRepositoryTest > searchByNameAndNumber builds correct query and maps results`

  **No new failures.** Test count went from 83 (Coder's report) to 89 — the 6 new `BackfillCardNamesUseCaseTest` cases, all passing.

## 2. New tests for BackfillCardNamesUseCase

No tests existed for the use case before this stage (confirmed — spec §9 explicitly deferred them here, Coder's summary confirmed the same).

New file: `app/src/test/java/com/skyler/pokedexbinder/domain/BackfillCardNamesUseCaseTest.kt` — 6 tests, MockK + `runTest`, following the existing `AssignCardUseCaseTest`/`CardSearchRepositoryTest` convention (relaxed mock for the repo, strict mock for the API).

| Test | Covers | Result |
|---|---|---|
| `eligible rows are looked up and written back with name and set` | (a) happy path — 2 eligible rows, both looked up via `api.getCard`, both written via `backfillCardNameSet` with correct name/set, returns 2 | PASS |
| `tcgdex-prefixed ids are filtered out and never call getCard` | (b) `tcgdex_`-prefixed id excluded from both the API call and the write; sibling eligible row still processed | PASS |
| `a failure on one row does not stop processing of remaining rows` | (c) middle row throws `IOException`, rows before and after it still get looked up and written, return count excludes only the failed row (2 of 3) | PASS |
| `zero eligible rows results in zero api calls` | (d) empty eligible list → 0 API calls, 0 writes, returns 0 | PASS |
| `all rows tcgdex-prefixed results in zero api calls` | Extra: every eligible row filtered out → 0 API calls (variant of b/d together) | PASS |
| `row with null assignedCardId is excluded from eligible set` | Defensive: use case's own null-check on `assignedCardId` holds even though the DAO query already guarantees non-null in production | PASS |

Note: had to add `mockkStatic(Log::class)` / `every { Log.w(any(), any<String>(), any()) } returns 0` in `@Before` — `android.util.Log` isn't mocked under plain JVM unit tests (Robolectric not in use here), and the use case's catch block calls `Log.w(TAG, msg, e)`. This mirrors the exact pattern already established in `PublishRepositoryTest.kt:70-71` for the same reason, so it's consistent with codebase convention, not a new pattern.

Item (e) — the race guard — is **not** exercised via a live-concurrency test (spinning two coroutines racing on Room in a JVM unit test is not practical/deterministic, and the spec itself green-lights verifying this at the SQL-shape level instead). See §3.

## 3. MainBinderDao query correctness

Read `app/src/main/java/com/skyler/pokedexbinder/data/local/MainBinderDao.kt:344-357` directly.

**`getRowsNeedingCardBackfill()`** (line 345-346):
```kotlin
@Query("SELECT * FROM main_binder WHERE assignedCardId IS NOT NULL AND assignedCardName IS NULL")
suspend fun getRowsNeedingCardBackfill(): List<MainBinderEntry>
```
Matches spec §2 exactly. The `tcgdex_` exclusion is **not** in this SQL — confirmed it is done in Kotlin, in `BackfillCardNamesUseCase.backfill()` line 25: `.filter { it.assignedCardId != null && !it.assignedCardId.startsWith("tcgdex_") }`. This matches spec §1's explicit instruction ("filter in Kotlin before the call") and spec §3 step 2. Correct, as-designed — not a bug.

**`backfillCardNameSet(...)`** (line 353-357):
```kotlin
@Query("""
    UPDATE main_binder SET assignedCardName = :cardName, assignedCardSetName = :cardSetName
    WHERE pokemonId = :pokemonId AND assignedCardId = :expectedCardId AND assignedCardName IS NULL
""")
suspend fun backfillCardNameSet(pokemonId: String, expectedCardId: String, cardName: String, cardSetName: String)
```
This is the **race-guarded 4-arg form from spec §7**, not the simpler 3-arg form from spec §2 — matches the Coder's stated deviation (§7 explicitly says "this is the safe form; use it," so skipping the intermediate 3-arg version is correct, not a shortcut).

**Race-guard verification (item e), by SQL-shape inspection (per task brief's fallback instruction):**
- The UPDATE is a single Room `@Query` — Room compiles this to one `SQLiteStatement.executeUpdateDelete()` call, which SQLite executes atomically as one write transaction. There is no read-then-write gap inside the DAO method itself.
- All three WHERE clauses must hold simultaneously for the row to update: `pokemonId` match (identifies the row), `assignedCardId = :expectedCardId` (the id read at SELECT time must still be current), `assignedCardName IS NULL` (the row must still be unbackfilled).
- Concrete race scenario: user reassigns `pokemonId=X` from `cardId=A` to `cardId=B` between the SELECT (which read `A`) and this UPDATE (called with `expectedCardId=A`). By the time `assignCard` commits, row X has `assignedCardId=B` and (per `BinderRepository.assignCard`, `BinderRepository.kt:39-55`) a non-null `assignedCardName` for the new card. The backfill UPDATE's WHERE then requires `assignedCardId = 'A'`, but the row now has `B` — **no row matches, the UPDATE is a no-op**, and the stale lookup for `A`'s name is silently discarded. This is exactly the intended guard from spec §7 and is correct by construction — no live-concurrency test needed to prove it; the WHERE clause is the proof.
- One caveat, not a bug: if the user reassigns to the *same* `cardId` (re-picks the identical card), `assignedCardId` still equals `expectedCardId` and `assignedCardName` would already be non-null (since `assignCard` always writes a name) — so the `assignedCardName IS NULL` clause alone blocks the stale write in that sub-case too. Both guard clauses are needed and both do real work; neither is redundant.

**Verdict: query correctness CONFIRMED**, both the eligibility SELECT and the race-guarded UPDATE match spec exactly.

## 4. Init chain wiring — MainBinderViewModel

Read `app/src/main/java/com/skyler/pokedexbinder/ui/mainbinder/MainBinderViewModel.kt:48-56`:

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

**Confirmed non-blocking / fire-and-forget, correctly wired:**
- `displayItems` (the StateFlow the UI actually collects, `MainBinderViewModel.kt:31-46`) is built independently via `combine(binderRepository.observeSlots(), ...).stateIn(...)` — a **separate** coroutine chain from `init`'s `viewModelScope.launch { ... }` block. `observeSlots()` is backed by Room's `Flow` (`MainBinderDao.observeAll()`), which emits from the DB reactively and is not gated on the `init` launch completing.
- The `init` launch and `displayItems`'s `stateIn` are two independent coroutines both started at ViewModel construction; Compose collects `displayItems` and renders whatever is in the DB immediately (empty list first frame, then live updates as Room emits) — it does not `await` the seed/migrate/backfill chain.
- `backfill()` is the last statement in that chain, after the seed/migrate steps that were already running in production before this feature existed — so it inherits the same non-blocking characteristics as those established steps, appended per spec §5's explicit instruction.
- Within `backfill()` itself, `delay(300L)` per row runs inside this background coroutine only — it never touches the main thread or blocks Compose recomposition. Screen rendering is unaffected regardless of how many rows are eligible.

**Verdict: CONFIRMED.** The main binder screen renders immediately from Room's live Flow; backfill trickles in the background and rows update live via the same Flow as they're written (each `backfillCardNameSet` triggers a Room invalidation → `observeAll()` re-emits → UI updates that row without any explicit wiring needed).

## 5. "Falls back to pokemonName" safety net — still intact

Read `PublishRepository.kt:301-310` (`toSnapshotSlot()`):
```kotlin
private fun MainBinderEntry.toSnapshotSlot(): SnapshotSlot = SnapshotSlot(
    ...
    slotName = pokemonName,
    ...
    cardName = assignedCardName,
    cardSet = assignedCardSetName,
    ...
)
```

Confirmed unchanged by this feature — `cardName`/`cardSet` are still passed through raw (null unless backfilled or freshly assigned), `slotName` is always populated from `pokemonName`. The feature adds zero new write paths to `toSnapshotSlot()` or `SnapshotSlot`; it only ever populates `assignedCardName`/`assignedCardSetName` on rows that already had a nulled-out gap. Downstream fallback consumers are untouched:
- Web viewer: `web/pokedex-binder-site/app.js:93` — `slot.cardName || slot.slotName` (not modified by this change).
- Publish diff display: `PublishRepository.kt:334,352,360,368` — `nextSlot.cardName ?: nextSlot.slotName` (not modified by this change).

**Verdict: CONFIRMED intact.** If backfill never runs (e.g. permanently offline device) or partially fails (network blips, 404s, tcgdex_ ids), affected rows simply keep `assignedCardName = null` forever, exactly as they do today pre-feature — the fallback chain handles it identically to the current production behavior. Zero regression risk: this feature can be entirely disabled (e.g. by removing the `backfill()` call from `init`) with no code elsewhere needing to change.

## What could not be fully verified

- **Real network behavior against the live pokemontcg.io API** was not exercised — all API interaction in `BackfillCardNamesUseCaseTest` is mocked via MockK (`coEvery { api.getCard(...) }`). This is standard/correct for a JVM unit test (no network in CI), but means: actual response shape from `GET /v2/cards/{id}`, actual anonymous rate-limit behavior, and actual latency were not observed live. The DTO mapping (`TcgCardResponse(data: TcgCardDto)`) is trusted against the documented shape referenced in spec §"API grounding," not verified against a live call.
- **No instrumented/Robolectric DAO test** was added for `getRowsNeedingCardBackfill()` / `backfillCardNameSet()` against a real (in-memory) Room database. Checked: this project has **no existing `androidTest` source set or Robolectric DB-test harness** (`Glob` for `app/src/test/**/*.kt` found no DAO-level DB tests anywhere in the existing suite — all DAO interactions in tests are mocked, e.g. `BinderRepositoryTest` mocks `MainBinderDao` directly). Adding a first-of-its-kind Robolectric/instrumented harness would be a meaningful scope expansion beyond this feature (new test infra, new Gradle test source set wiring) and was not attempted. Per the task brief's own fallback instruction, correctness of the race-guard UPDATE was instead verified by direct SQL-shape inspection (§3 above), which is sufficient for a single-statement atomic UPDATE — there is no read-modify-write gap inside the DAO method for a live concurrency test to actually exercise beyond what SQL guarantees already prove.
- **Live-concurrency race test** (two coroutines actually racing on the same row) was not written for the reason above — the task brief itself pre-approved the SQL-shape-verification fallback for this item, and a JVM-level fake race (e.g. two sequential calls to a mocked DAO) would not add confidence beyond what reading the WHERE clause already establishes, since MockK doesn't model SQLite transaction semantics.

## Summary

| Check | Result |
|---|---|
| 1. Build check (assembleDebug + testDebugUnitTest, no new failures) | PASS |
| 2. New BackfillCardNamesUseCaseTest (a–d + race-guard-by-inspection for e) | PASS — 6/6 new tests green |
| 3. MainBinderDao query correctness | CONFIRMED — tcgdex_ filtered in Kotlin (correct per spec), race-guarded UPDATE verified by SQL-shape analysis |
| 4. Init chain wiring — non-blocking | CONFIRMED — `displayItems` and `init`'s launch are independent coroutines; UI never awaits backfill |
| 5. pokemonName fallback safety net intact | CONFIRMED — zero changes to `toSnapshotSlot()` or downstream fallback logic |
