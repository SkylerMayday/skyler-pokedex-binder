# Changes — Card Name/Set Backfill

Implemented exactly per `.pipeline/specs.md`. No scope creep — no Settings UI, no TCGdex fallback.

## Files touched

### New

- `app/src/main/java/com/skyler/pokedexbinder/domain/BackfillCardNamesUseCase.kt` (spec §3)
  - `@Singleton class BackfillCardNamesUseCase @Inject constructor(binderRepository, api)`.
  - `suspend fun backfill(): Int` — fetches eligible rows, filters out `tcgdex_`-prefixed ids, iterates sequentially with `delay(BACKFILL_REQUEST_DELAY_MS)` (300L) between requests, wraps each row in `try/catch (Exception)` logging at WARN via `Log.w(TAG, ...)` and skipping on failure. Returns count of successful writes. Never throws.
  - Named constants: `TAG = "BackfillCardNames"`, `BACKFILL_REQUEST_DELAY_MS = 300L`.

### Edited

- `app/src/main/java/com/skyler/pokedexbinder/data/remote/PokemonTcgApi.kt` (spec §1)
  - Added `@GET("cards/{id}") suspend fun getCard(@Path("id") id: String): TcgCardResponse`.
- `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt` (spec §1)
  - Added `TcgCardResponse(val data: TcgCardDto)` wrapper (new type, `TcgCardsResponse` untouched).
- `app/src/main/java/com/skyler/pokedexbinder/data/local/MainBinderDao.kt` (spec §2)
  - Added `getRowsNeedingCardBackfill(): List<MainBinderEntry>` — `SELECT ... WHERE assignedCardId IS NOT NULL AND assignedCardName IS NULL`.
  - Added `backfillCardNameSet(pokemonId, expectedCardId, cardName, cardSetName)` — used the **race-guarded** UPDATE form from spec §7 directly (`WHERE pokemonId = :pokemonId AND assignedCardId = :expectedCardId AND assignedCardName IS NULL`), not the simpler §2 signature, since §7 explicitly says "this is the safe form; use it."
- `app/src/main/java/com/skyler/pokedexbinder/repository/BinderRepository.kt` (spec §4)
  - Added `getRowsNeedingBackfill()` and `backfillCardNameSet(pokemonId, expectedCardId, cardName, cardSetName)` passthroughs, mirroring `getAllEntries()` style.
- `app/src/main/java/com/skyler/pokedexbinder/ui/mainbinder/MainBinderViewModel.kt` (spec §5)
  - Injected `BackfillCardNamesUseCase` into the constructor.
  - Appended `backfillCardNamesUseCase.backfill()` as the final step of the existing `init { viewModelScope.launch { ... } }` chain, after `migrateSlotNamesIfNeeded()`.

### Edited (test fixup, required by constructor signature change — not a spec item)

- `app/src/test/java/com/skyler/pokedexbinder/ui/MainBinderViewModelTest.kt`
  - `MainBinderViewModel` gained a new constructor parameter (`backfillCardNamesUseCase`); added a `mockk<BackfillCardNamesUseCase>(relaxed = true)` and passed it through all 3 direct-construction call sites in this pre-existing test file. No test logic changed.

## Deviations from spec (with rationale)

1. **DAO `backfillCardNameSet` signature**: implemented directly with the 4-arg race-guarded form (`pokemonId, expectedCardId, cardName, cardSetName`) shown in spec §7, skipping the intermediate 3-arg version shown in spec §2. The spec itself says to use the §7 form ("this is the safe form; use it"), so building the §2 version first and then replacing it would have been pure churn. `BinderRepository` and `BackfillCardNamesUseCase` call the 4-arg form throughout.
2. **Test file touched**: `MainBinderViewModelTest.kt` required updating because adding a constructor parameter to `MainBinderViewModel` is a breaking change to any direct-construction test, and this file constructs the ViewModel directly (not via Hilt) in 3 places. This is a mechanical fixup (add a mock, pass it through), not a new test and not scope creep — the alternative (leaving it broken) was not viable since it fails compilation.

## Build result

```
& .\gradlew.bat assembleDebug testDebugUnitTest --console=plain
```

- `assembleDebug`: **BUILD SUCCESS** (compiles clean, no errors, no new warnings beyond pre-existing Moshi/kapt deprecation notice).
- `testDebugUnitTest`: 83 tests run, 8 failed. All 8 failures are the **pre-existing, known-unrelated** failures called out in the task brief:
  - `AssignCardUseCaseTest > assign to occupied slot moves old card to secondary binder`
  - `GeminiCardScannerTest > scan throws RateLimitException on 429`
  - `GeminiCardScannerTest > scan throws IOException on 500`
  - `GeminiCardScannerTest > scan returns nulls for missing fields`
  - `GeminiCardScannerTest > scan returns ParsedCardInfo on success`
  - `SmartThresholdUseCaseTest > multiple results with name match but no number is low confidence`
  - `BinderRepositoryTest > assignCard upserts entry with card info`
  - `CardSearchRepositoryTest > searchByNameAndNumber builds correct query and maps results`
  
  **No new failures introduced.** No test yet exists for `BackfillCardNamesUseCase` or the new DAO queries — per the task brief, test authoring is the Tester stage's responsibility (spec §9 lists exactly what to add).

## Not done (out of scope, confirmed against spec)

- No `BackfillCardNamesUseCaseTest`, no `MainBinderDao` instrumented test for the new queries — spec §9 assigns these to the Tester stage.
- No Settings UI — explicitly a non-goal (spec §5, §17).
- No DI module changes — none needed; both `BinderRepository` and `PokemonTcgApi` are already `@Singleton`-provided, and `BackfillCardNamesUseCase` is plain `@Inject`-constructible (spec §8 confirms this).
- No Room migration / schema version bump (spec explicit non-goal).
- App not run — per instructions, Tester stage handles further verification.
