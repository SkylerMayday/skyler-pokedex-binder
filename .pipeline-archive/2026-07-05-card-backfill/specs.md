# Spec — Card Name/Set Backfill

## Problem

`main_binder` rows assigned before schema v6 (migration `MIGRATION_5_6`, `PokedexDatabase.kt:34`) have `assignedCardId` set but `assignedCardName` / `assignedCardSetName` null — those two columns did not exist when the card was assigned. The columns were added `ALTER TABLE ... ADD COLUMN ... TEXT` (nullable, no default), so pre-v6 assignments are permanently null until re-assigned.

Why it matters (verified, not assumed):
- `PublishRepository.buildSnapshot` maps entries via `MainBinderEntry.toSnapshotSlot()` (`PublishRepository.kt:305-310`), passing `cardName = assignedCardName`, `cardSet = assignedCardSetName` straight through into the published `BinderSnapshot`.
- Downstream consumers fall back to the slot/pokemon name when `cardName` is null:
  - Web viewer: `web/pokedex-binder-site/app.js:93` — `slot.cardName || slot.slotName`.
  - Publish diff display: `PublishRepository.kt:334,352,360,368` — `nextSlot.cardName ?: nextSlot.slotName`.
  - `BinderSnapshot.kt:34` comment confirms: *"fallback handled by viewer/Discord, not here"*.
- So the **"falls back to pokemonName" claim is CONFIRMED**. Backfill is non-destructive UX improvement: published binder shows the real card name + set instead of the generic Pokémon slot name. No crash risk, no data-loss risk if we skip a row.

Decision locked with Skyler: **pokemontcg.io only, skip silently on any failure.** No TCGdex fallback. Unresolved rows keep the null → existing fallback behavior.

## Non-goals

- No TCGdex lookup for backfill.
- No user-facing error surface for failed lookups (log only).
- No re-fetch of image URL (rows already have `assignedCardImageUrl`; only name/set are missing). Do not touch `assignedCardImageUrl`.
- No backfill of `secondary_binder` (out of scope; publish path for secondary already passes `cardName = null` intentionally — `PublishRepository.kt:283`).
- No new Room migration or schema-version bump (columns already exist since v6; this is data population, not schema change).

## API grounding (verified)

- `NetworkModule.kt:47-51`: base URL `https://api.pokemontcg.io/v2/`. **No auth interceptor, no `X-Api-Key` header** — the app calls pokemontcg.io anonymously. This dictates rate-limit strategy (see §6).
- Existing endpoint `PokemonTcgApi.searchCards` returns `TcgCardsResponse` (`{ data: [...], totalCount }`, `TcgCardDto.kt:29-33`).
- pokemontcg.io `GET /v2/cards/{id}` returns a **single** card wrapped as `{ "data": { ...card } }` (object, not array). So a new response wrapper is needed — `TcgCardsResponse` cannot represent it (its `data` is a `List`, and it requires `totalCount` which the single-card endpoint does not return). `TcgCardDto` itself is reusable as the inner object.

---

## 1. New API endpoint

File: `app/src/main/java/com/skyler/pokedexbinder/data/remote/PokemonTcgApi.kt`

Add:

```kotlin
import retrofit2.http.Path

@GET("cards/{id}")
suspend fun getCard(@Path("id") id: String): TcgCardResponse
```

File: `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt`

Add a single-card wrapper (new type — do NOT reuse `TcgCardsResponse`):

```kotlin
@JsonClass(generateAdapter = true)
data class TcgCardResponse(
    val data: TcgCardDto
)
```

`TcgCardDto` already exposes `name` and `set.name` (`TcgSetDto.name`, `TcgCardDto.kt:7-21`) — exactly the two fields we need. No change to `TcgCardDto`.

Note on tcgdex-sourced ids: some assigned cards may have ids prefixed `tcgdex_` (see `CardSearchRepository.kt:135`). Those are NOT valid pokemontcg.io ids — `getCard("tcgdex_...")` will 404. Per the skip-silently decision that is acceptable (they just stay null). The backfill query cannot cheaply distinguish them in SQL, so filter them in Kotlin before the call (see §4) to avoid guaranteed-failing requests.

## 2. DAO — backfill-eligible query + single-row update

File: `app/src/main/java/com/skyler/pokedexbinder/data/local/MainBinderDao.kt`

Add:

```kotlin
/** Rows that have a card assigned but are missing the card's name (pre-v6 assignments). */
@Query("SELECT * FROM main_binder WHERE assignedCardId IS NOT NULL AND assignedCardName IS NULL")
suspend fun getRowsNeedingCardBackfill(): List<MainBinderEntry>

/** Writes the resolved name/set onto a single row, leaving id/image untouched. */
@Query("UPDATE main_binder SET assignedCardName = :cardName, assignedCardSetName = :cardSetName WHERE pokemonId = :pokemonId")
suspend fun backfillCardNameSet(pokemonId: String, cardName: String, cardSetName: String)
```

Rationale for a targeted `@Query UPDATE` over read-modify-`upsert`: it only touches the two backfilled columns, so it cannot clobber a concurrent card assignment the user makes on another row, and it is a no-op-safe single statement. (The existing `BinderRepository.assignCard` uses copy+upsert, but that path owns the whole row; backfill only owns two columns.)

Guard condition `assignedCardName IS NULL` in the WHERE of the SELECT is the eligibility gate. We deliberately do NOT also gate the UPDATE on `assignedCardName IS NULL` — if the user assigns a real card mid-backfill, the SELECT snapshot for that pokemonId is already stale, but `assignCard` writes a non-null name AND we only update name/set (not id/image), so worst case is an idempotent overwrite with the same-or-equivalent card name. Acceptable. (Edge case detailed in §7.)

## 3. Domain use case

New file: `app/src/main/java/com/skyler/pokedexbinder/domain/BackfillCardNamesUseCase.kt`

Follows the thin-use-case pattern of `PublishBinderUseCase.kt` (constructor-injected repository, single suspend entry point), but this one needs both the DAO-backed repo and the TCG API, so inject the API directly (mirrors `CardSearchRepository`'s `@Inject constructor(private val api: PokemonTcgApi, ...)`).

```kotlin
@Singleton
class BackfillCardNamesUseCase @Inject constructor(
    private val binderRepository: BinderRepository,
    private val api: PokemonTcgApi
) {
    /** @return number of rows successfully backfilled. Never throws; failures are logged and skipped. */
    suspend fun backfill(): Int
}
```

Structure of `backfill()` (signatures/pseudocode only — Coder implements):

1. `val eligible = binderRepository.getRowsNeedingBackfill()` (new repo passthrough, §5).
2. Filter out ids that cannot resolve on pokemontcg.io: `eligible.filter { it.assignedCardId != null && !it.assignedCardId.startsWith("tcgdex_") }`. (`assignedCardId` is `String?`; smart-cast after the null check.)
3. If empty → return 0 immediately (zero-eligible fast path, §7).
4. Iterate **sequentially** (not parallel — see §6). For each row, in a `try`:
   - `val card = api.getCard(id).data`
   - `binderRepository.backfillCardNameSet(row.pokemonId, card.name, card.set.name)`
   - increment success counter
   - `catch (e: Exception) { Log.w(TAG, "Backfill skipped for ${row.pokemonId} / $id", e); }` — swallow, continue. Catch broad `Exception` (covers `HttpException` 404, `IOException` network, Moshi `JsonDataException` malformed) consistent with `CardSearchRepository.safeSearch` (`CardSearchRepository.kt:91`).
   - `delay(BACKFILL_REQUEST_DELAY_MS)` between iterations (§6).
5. Return success counter.

Named constant: `private const val BACKFILL_REQUEST_DELAY_MS = 300L` and `private const val TAG = "BackfillCardNames"`.

## 4. Repository passthroughs

File: `app/src/main/java/com/skyler/pokedexbinder/repository/BinderRepository.kt`

Add two thin methods (mirroring existing `getAllEntries()` passthrough, `BinderRepository.kt:28`):

```kotlin
suspend fun getRowsNeedingBackfill(): List<MainBinderEntry> =
    mainBinderDao.getRowsNeedingCardBackfill()

suspend fun backfillCardNameSet(pokemonId: String, cardName: String, cardSetName: String) =
    mainBinderDao.backfillCardNameSet(pokemonId, cardName, cardSetName)
```

## 5. Trigger mechanism — DECISION

**Automatic, one-time, on-launch, fire-and-forget — appended to the existing seed/migrate chain in `MainBinderViewModel.init`.** No Settings button.

Where: `MainBinderViewModel.kt:46-53`, the existing `init { viewModelScope.launch { ... } }` block that already runs `seedIfEmpty → seedAlternateFormsIfMissing → seedMegasIfMissing → migrateSlotNamesIfNeeded`. Inject `BackfillCardNamesUseCase` into the ViewModel constructor (`MainBinderViewModel.kt:21-25`) and append `backfillCardNamesUseCase.backfill()` as the final step in that coroutine.

Rationale (this is the Planner's call, stated as one decision, not a menu):

- **It is idempotent and self-limiting by construction.** The eligibility query returns only rows where `assignedCardName IS NULL`. Every successful backfill flips a row to non-null, permanently removing it from the eligible set. On a fully-backfilled install the query returns zero rows and the pass is a single cheap `SELECT` returning empty → effectively free on every subsequent launch. This kills the main objection to on-launch work (repeated network cost): there is no repeated network cost once caught up. New nulls only ever appear from a *new* pre-v6-style row, which cannot happen — all new assignments write the name (`BinderRepository.assignCard`).
- **Population is small and one-time.** Realistically a handful to low-dozens of pre-v6 assigned rows on one user's device (this is effectively Skyler's personal binder). Not thousands. Sequential trickle finishes in seconds and never runs again.
- **Zero UI surface is correct for a silent data-repair.** A Settings button would demand the user know their data has a latent gap and remember to press it — bad UX for a purely cosmetic backfill they can't see the need for. Restore has a button because it is destructive and user-initiated by intent; backfill is neither.
- **Matches the established pattern.** The app already does exactly this shape of work — idempotent, guarded, fire-and-forget data migrations chained in `init` (`migrateSlotNamesIfNeeded` and the `insertIfAbsent` seeders). Backfill is the same category and belongs in the same place, not bolted onto Settings.

Consequences accepted: runs inside `MainBinderViewModel` scope, so if the user leaves the main binder screen mid-pass the coroutine is cancelled (`viewModelScope`). That is fine — see §7 "app backgrounded / screen left mid-backfill": partial progress is durably committed row-by-row, and the next launch resumes on whatever is still null. No checkpoint state needed.

## 6. Rate limiting / pacing — DECISION

**Sequential, one request at a time, with a `delay(300)` between requests.** Not parallel.

Rationale: the app calls pokemontcg.io **without an API key** (`NetworkModule.kt` has no `X-Api-Key` interceptor). pokemontcg.io's documented anonymous limit is low relative to keyed access. [Likely] the practical anonymous ceiling is on the order of a small number of requests/sec and daily-capped; hammering it in parallel risks `429`s. Since:
- the eligible set is small and one-time,
- there is no user waiting on a progress bar (it is background/silent),
- and a `429` on any row is silently skipped anyway (that row just stays null and gets retried next launch),

throughput is irrelevant and politeness is free. Sequential + 300 ms spacing keeps well under any reasonable anonymous rate limit and is the lowest-risk choice. Do NOT use the `async {}`/parallel pattern from `CardSearchRepository.searchByName` here — that is tuned for latency on a 2-call user-facing search, not a bulk loop.

`[Guessing]` on the exact anonymous numeric limit — do not hardcode assumptions about it beyond "pace conservatively." 300 ms is a safe default; it is a named constant so it is trivially tunable.

## 7. Edge cases

- **Zero eligible rows** (fresh install, or already fully backfilled): `getRowsNeedingCardBackfill()` returns empty → `backfill()` returns 0 without any network call. Free on every steady-state launch. This is the common case after first successful run.
- **`tcgdex_`-prefixed assignedCardId**: filtered out in Kotlin (§3 step 2) so no guaranteed-404 request is made. Stays null forever (acceptable per decision; TCGdex-side lookup is explicitly out of scope).
- **404 (card id no longer in pokemontcg.io DB)**: `HttpException` caught, logged at WARN, row skipped, loop continues.
- **Network error / timeout**: `IOException` caught, same handling. Whole remaining batch is NOT aborted — each row is independently try-wrapped, so one network blip doesn't stop later rows (though if the network is fully down, all rows fail-and-skip this launch and retry next launch — correct behavior).
- **Malformed / unexpected response shape**: Moshi throws `JsonDataException` (e.g. `data` missing, or `set` object absent). Caught by the broad `catch (Exception)`, row skipped. `TcgCardResponse.data` and `TcgSetDto.name` are non-null in the DTO, so a partial payload surfaces as a parse exception rather than a silent bad write — good, we skip rather than write garbage.
- **App backgrounded / user leaves main binder screen mid-backfill**: `viewModelScope` coroutine cancels. Rows already updated are durably committed (each `backfillCardNameSet` is its own transaction). Next launch's `init` re-runs `backfill()`, the eligibility query naturally returns only the still-null remainder, and it resumes. No resume-token / checkpoint needed — the null column *is* the checkpoint.
- **User assigns a real card to an eligible row during the pass** (race): the SELECT snapshot is stale for that pokemonId. `assignCard` will have written a non-null name already. Our `backfillCardNameSet` then overwrites name/set with the API lookup of the *old* `assignedCardId` — BUT note `assignCard` also changes `assignedCardId`/`assignedCardImageUrl`, which backfill does NOT touch, so this could desync name from id. **Mitigation**: change the UPDATE in §2 to additionally guard `AND assignedCardName IS NULL AND assignedCardId = :expectedCardId`. Revised signature:

  ```kotlin
  @Query("UPDATE main_binder SET assignedCardName = :cardName, assignedCardSetName = :cardSetName WHERE pokemonId = :pokemonId AND assignedCardId = :expectedCardId AND assignedCardName IS NULL")
  suspend fun backfillCardNameSet(pokemonId: String, expectedCardId: String, cardName: String, cardSetName: String)
  ```

  Pass `row.assignedCardId` as `expectedCardId`. If the row changed under us (new id, or name already filled), the WHERE matches nothing → no-op, no desync. This is the safe form; use it. (Race is extremely unlikely on a single-user device the instant the app opens, but the guard is one extra clause and removes the only correctness hazard.)
- **Duplicate work across ViewModel recreation**: `MainBinderViewModel` could be recreated (config change) triggering `init` again while a prior pass runs. Both passes read overlapping eligible sets; the `expectedCardId AND assignedCardName IS NULL` guard makes the second writer a no-op for any row the first already wrote. Safe, just mildly redundant network calls. Not worth a mutex given the tiny set.

## 8. Files touched — summary

New:
- `app/src/main/java/com/skyler/pokedexbinder/domain/BackfillCardNamesUseCase.kt`

Edited:
- `app/src/main/java/com/skyler/pokedexbinder/data/remote/PokemonTcgApi.kt` — add `getCard(id)`.
- `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt` — add `TcgCardResponse` wrapper.
- `app/src/main/java/com/skyler/pokedexbinder/data/local/MainBinderDao.kt` — add `getRowsNeedingCardBackfill()`, `backfillCardNameSet(...)`.
- `app/src/main/java/com/skyler/pokedexbinder/repository/BinderRepository.kt` — add two passthroughs.
- `app/src/main/java/com/skyler/pokedexbinder/ui/mainbinder/MainBinderViewModel.kt` — inject use case, append `backfill()` to `init` chain.

No DI module change required — `BackfillCardNamesUseCase` is `@Inject`-constructible from already-provided `BinderRepository` and `PokemonTcgApi` (both `@Singleton`, provided in `DatabaseModule`/`NetworkModule`). `@Singleton` on the use case is optional/consistent-with-`CardSearchRepository`; a plain `@Inject constructor` also works since the ViewModel is the only consumer.

No new Room migration, no DB version bump.

## 9. Tests to add (Tester stage)

- `BackfillCardNamesUseCaseTest`: fake `BinderRepository` + fake/mock `PokemonTcgApi`.
  - happy path: N eligible rows → N `backfillCardNameSet` calls with correct name/set, returns N.
  - zero eligible → returns 0, zero API calls.
  - `tcgdex_` ids filtered → no API call for them.
  - API throws (404/IOException/JsonDataException) on one row → that row skipped, others still processed, count excludes it, no exception propagates.
  - name/set written from `card.name` / `card.set.name`.
- `MainBinderDao` (instrumented or Robolectric per existing DB test convention — check `app/src/test`/`androidTest` for the established harness): `getRowsNeedingCardBackfill` returns only `assignedCardId != null AND assignedCardName IS NULL`; `backfillCardNameSet` updates only matching `pokemonId + expectedCardId` and is a no-op when id differs or name already set.

## 10. Open questions

- None blocking. `[Guessing]` on the exact pokemontcg.io anonymous rate limit — handled by conservative sequential pacing so the exact number doesn't matter. If Coder finds an existing keyed-request setup elsewhere (there isn't one in `NetworkModule`), reconcile, but current evidence is anonymous.
