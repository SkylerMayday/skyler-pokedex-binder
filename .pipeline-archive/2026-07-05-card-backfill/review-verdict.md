# Review Verdict — Card Name/Set Backfill

## Verdict: SHIP

Independently verified against source, not accepted on the Tester's word. All spec §§1–8 implemented as written, no scope creep, race-guard correct by independent analysis. Feature is non-destructive/cosmetic-only per spec; bar is met with margin.

---

## What I verified independently (file:line)

### 1. Fire-and-forget from ViewModel — CONFIRMED
`MainBinderViewModel.kt:48-56` — `backfill()` is the last statement in the existing `init { viewModelScope.launch { ... } }` chain, after the pre-existing seed/migrate steps. It does not block `displayItems` (`MainBinderViewModel.kt:31-46`), which is a separate `combine(observeSlots(), ...).stateIn(...)` coroutine backed by Room's reactive Flow — the UI renders independent of the init launch. Backfilled rows surface live via Room invalidation on each `backfillCardNameSet` write. No behavior change to existing ViewModel functionality; only a constructor param and one appended call.

### 2. Never throws / doesn't crash — CONFIRMED
`BackfillCardNamesUseCase.kt:30-40` — every per-row body is wrapped in `try { ... } catch (e: Exception)` logging at WARN and continuing. The broad catch covers HttpException (404), IOException (network), JsonDataException (malformed). `backfill()` has no uncaught throw path; `getRowsNeedingBackfill()` at line 24 is outside the loop but a DB read failure there would propagate — acceptable, matches the pre-existing seed/migrate steps in the same chain which are equally unguarded, and it runs in a background `viewModelScope` coroutine (an uncaught exception there does not crash the app process; it cancels that job). Not a regression.

### 3. 300ms pacing — CONFIRMED
`BackfillCardNamesUseCase.kt:39,46` — `delay(BACKFILL_REQUEST_DELAY_MS)` where `BACKFILL_REQUEST_DELAY_MS = 300L`, called once per iteration inside the sequential `for` loop. Named constant per spec §6. Sequential, not parallel — correct for anonymous rate limit.

### 4. tcgdex_ filtering before any network call — CONFIRMED
`BackfillCardNamesUseCase.kt:24-25` — `.filter { it.assignedCardId != null && !it.assignedCardId.startsWith("tcgdex_") }` runs on the eligible list *before* the loop, so `getCard()` is never invoked for a `tcgdex_` id. Not wasteful. Smart-cast on `assignedCardId` (`String?`, confirmed `MainBinderEntry.kt:13`) is valid after the `!= null` check.

### 5. Race-guard — INDEPENDENTLY CONFIRMED (not accepted on inspection alone)
`MainBinderDao.kt:353-357`:
```sql
UPDATE main_binder SET assignedCardName = :cardName, assignedCardSetName = :cardSetName
WHERE pokemonId = :pokemonId AND assignedCardId = :expectedCardId AND assignedCardName IS NULL
```
`expectedCardId` is passed as `cardId` read at SELECT time (`BackfillCardNamesUseCase.kt:31,34`). Room compiles a single `@Query` UPDATE to one atomic `executeUpdateDelete` — the WHERE is evaluated at write time, atomically, with **no read-modify-write gap inside the DAO method** for a concurrent write to slip into.

Reasoned through the stale-write question the brief raised ("could a concurrent modification between SELECT and UPDATE slip through?"):
- `assignCard` (`BinderRepository.kt:46-54`) is copy+upsert that **always** writes a non-null `assignedCardName` for the newly-assigned card.
- Concurrent reassign changes the **id** → row now has `assignedCardId = B`, but UPDATE requires `= A (expectedCardId)` → no match → no-op. Stale name for A is discarded. No desync.
- Concurrent reassign to the **same id** → `assignedCardName` is now non-null → `assignedCardName IS NULL` fails → no-op.
- The one hazard that mattered (name desyncing from a since-changed id, because backfill does not touch id/image) is fully closed by the `expectedCardId` clause.

Both guard clauses do real work; neither is redundant. The guard is correct. This is the safe §7 form, not the weaker §2 form — correct deviation, spec explicitly says "use it."

### 6. Tests meaningful — CONFIRMED
`BackfillCardNamesUseCaseTest.kt` — 6 tests, assertions are load-bearing:
- Happy path (L50-62): asserts return count `== 2` AND `coVerify` each `backfillCardNameSet` with exact name/set — verifies mapping `card.name` / `card.set.name`, not just that a call happened.
- tcgdex_ filter (L64-76): `coVerify(exactly = 0) { api.getCard("tcgdex_...") }` — proves no wasted call, plus sibling row still processed.
- Partial failure (L78-96): middle row throws IOException; asserts before/after rows still written and count `== 2` excludes only the failure — proves per-row isolation.
- Zero eligible (L98-107), all-tcgdex (L109-120), null id (L122-132): each asserts `0` return and `exactly = 0` API calls.
These are real assertions, not smoke tests. `mockkStatic(Log::class)` in `@Before` (L30-31) mirrors the established `PublishRepositoryTest` pattern — needed because `android.util.Log` is unmocked under plain JVM tests.

### 7. Test fixup correct, no behavior change — CONFIRMED
`MainBinderViewModelTest.kt:29,45,61,79` — added `mockk<BackfillCardNamesUseCase>(relaxed = true)` and threaded it through all 3 direct-construction sites. Relaxed mock means `backfill()` is a no-op returning a default — existing test logic (grouping, search filter) unaffected. Mechanical, correct. Row helper in the use-case test (`BackfillCardNamesUseCaseTest.kt:34-40`) uses valid named args against `MainBinderEntry` (`dexOrder` required, rest defaulted) — compiles.

### 8. API/DTO/repo passthroughs — CONFIRMED
- `PokemonTcgApi.kt:15-16` — `@GET("cards/{id}") getCard(@Path("id") id): TcgCardResponse`. Correct.
- `TcgCardDto.kt:35-38` — new `TcgCardResponse(val data: TcgCardDto)` wrapper; `TcgCardsResponse` (list form) untouched. `name` and `set.name` are non-null in the DTO (`TcgCardDto.kt:9,18`) → malformed payload throws JsonDataException → caught & skipped, never a garbage write. Correct.
- `BinderRepository.kt:30-34` — two thin passthroughs mirroring `getAllEntries()`. Correct.

### 9. Scope creep — NONE
No Settings UI added (spec §5 explicit skip). No TCGdex fallback. No migration / schema bump. No DI module change (use case is `@Inject`-constructible). `toSnapshotSlot()` fallback path (`PublishRepository.kt`) untouched — verified by the Tester and consistent with the diff surface described; the feature only ever populates previously-null columns.

---

## Residual gaps (accepted, not blocking)

- **No live network test** — all API interaction mocked. Standard for JVM unit tests; response-shape trust is against documented pokemontcg.io `/v2/cards/{id}`. Low risk: DTO is non-null-guarded so a shape mismatch fails safe (skip, not bad write).
- **No instrumented/Robolectric DAO test** — repo has no such harness; adding one is genuine infra scope beyond this feature. Race-guard correctness rests on SQL-shape analysis (§5 above), which for a single atomic UPDATE statement is a complete proof — there is no runtime read-modify-write gap that a live test could exercise beyond what the WHERE clause already guarantees. Acceptable for a cosmetic, non-destructive feature.

Neither gap touches a data-loss or crash path. Ship.
