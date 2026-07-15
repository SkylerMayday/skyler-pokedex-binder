# Implementation Plan — Concurrent 3-API Search + TCGCSV Image Backfill

**Pipeline stage:** 1 (Planner) → hand off to Coder
**Spec:** `docs/superpowers/specs/2026-07-12-concurrent-search-image-backfill.md` (APPROVED)
**Scope:** 3 production files + 2 test files. No new deps, no DI changes, no API-interface changes.

This plan is exact and buildable. The Coder implements it verbatim. Every code block below is
the literal intended source unless labelled "illustrative". Decisions I resolved myself are called
out inline under **DECISION**.

---

## 0. Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt` | Add `SearchProgress` sealed class + `searchStreaming(...)` Flow method + `mergeAndBackfillImages` and matching helpers. `searchTcgcsvByName` reused **as-is** (do not modify). |
| `app/src/main/java/com/skyler/pokedexbinder/ui/manualsearch/ManualSearchViewModel.kt` | Add `stillSearching` to `SearchState.Results`; rewrite `search()` to collect the streaming flow with a cancellable `Job`; collapse `retry()` (drop TCGCSV-on-empty branch). |
| `app/src/main/java/com/skyler/pokedexbinder/ui/manualsearch/ManualSearchScreen.kt` | Restructure the `Results` branch: thin `LinearProgressIndicator` when `stillSearching`; remove the "Search more sources (TCGplayer)" `OutlinedButton`. |
| `app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt` | Add 4 `searchStreaming` tests. Keep existing tests. |
| `app/src/test/java/com/skyler/pokedexbinder/ui/ManualSearchViewModelTest.kt` | Delete 2 obsolete tests, rewrite 2, add 3 new. |

No other call sites are affected: `Screen.ConnectingArtSearch`, `Screen.UnownSearch`,
`Screen.AddToSecondary` all reuse `ManualSearchScreen`/`ManualSearchViewModel` unchanged.
`QuickScanScreen.retry()` is a **different** ViewModel — do not touch it.

---

## 1. CardSearchRepository — streaming search + merge/backfill

### 1.1 New top-level `SearchProgress` sealed class

Place directly **above** the `@Singleton class CardSearchRepository` declaration (same file, same
package), mirroring how `SearchState` lives at file top-level in the ViewModel file.

```kotlin
sealed class SearchProgress {
    /** pokemontcg.io + TCGdex results — rendered immediately. */
    data class Fast(val cards: List<TcgCard>) : SearchProgress()
    /** Fast results merged with the completed TCGCSV scan (appends + image backfill). */
    data class Complete(val cards: List<TcgCard>) : SearchProgress()
}
```

### 1.2 New imports

Add to the existing import block:

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
```

(`async`, `awaitAll`, `coroutineScope` already imported.)

### 1.3 New public method `searchStreaming`

**DECISION — signature.** The spec sketch shows `searchByNameStreaming(name): Flow<SearchProgress>`
that internally calls `searchByName(name)`. I am **not** using that exact shape. Reason: the
ViewModel's fast path has three branches (`isPromoNumber` → `searchByNumber`.ifEmpty{searchByName},
`promoOnly` → `searchPromosByName`, else → `searchByName`). Hard-coding `searchByName` inside the
repo would either drop concurrency for the promo/number branches or force the branch-routing logic
down into the repository (a bigger, worse change — routing is a UI-input concern). Instead the
repository stays agnostic to *which* fast search runs by taking the fast producer as a suspend
lambda, and takes the TCGCSV scan key separately:

```kotlin
/**
 * Progressive search: runs the caller-supplied fast source (pokemontcg.io + TCGdex, already
 * merged) and the TCGCSV 217-group scan concurrently. Emits [SearchProgress.Fast] as soon as the
 * fast source resolves, then [SearchProgress.Complete] once TCGCSV finishes and its cards have been
 * merged/back-filled into the fast list.
 *
 * @param tcgcsvQuery the name to scan TCGCSV for (the raw trimmed user query).
 * @param fastSearch  produces the fast (pokemontcg.io + TCGdex) result list.
 *
 * Cancellation: collecting coroutine cancelled -> flow's [coroutineScope] cancels the in-flight
 * TCGCSV scan via structured concurrency. No manual teardown needed.
 */
fun searchStreaming(
    tcgcsvQuery: String,
    fastSearch: suspend () -> List<TcgCard>
): Flow<SearchProgress> = flow {
    coroutineScope {
        // Both start immediately -> the 10-13s TCGCSV cost overlaps the fast search + user reading.
        val fastDeferred = async { fastSearch() }
        val tcgcsvDeferred = async { searchTcgcsvByName(tcgcsvQuery) }

        val fast = fastDeferred.await()
        emit(SearchProgress.Fast(fast))

        val tcgcsv = tcgcsvDeferred.await()
        emit(SearchProgress.Complete(mergeAndBackfillImages(fast, tcgcsv)))
    }
}
```

**Concurrency note (P0 requirement):** `tcgcsvDeferred` is created on the line right after
`fastDeferred`, both before any `await()`. The TCGCSV scan therefore starts at the same time as the
fast search, not sequenced after it. Do not reorder these so that a `.await()` sits between the two
`async {}` calls.

**Neither `async` can throw out of the scope:** `searchTcgcsvByName` has its own top-level
`try/catch` returning `emptyList()`, and both `safeSearch`/`tcgdexSearchByName` inside the fast
lambda catch internally. So the flow only ever terminates via normal completion or cancellation —
no new failure mode is introduced (confirms spec edge case). The ViewModel still wraps `collect` in
a `try/catch` defensively.

### 1.4 New merge + image-backfill helpers

Add these **private** members (place them next to the existing `mergeResults`/`dedupeKey` "Merge
helpers" section). `dedupeKey` is reused unchanged.

```kotlin
/**
 * Merges the completed TCGCSV scan into the fast results:
 *  - Fast cards with a blank image get their image patched in from a name+number-matching TCGCSV
 *    product that HAS an image (identity/id unchanged — only [TcgCard.imageUrl] is replaced).
 *  - TCGCSV cards that are NOT the same card as any fast card (by name+number) AND whose dedupeKey
 *    isn't already present are appended.
 *  - A TCGCSV card that IS the same card as a fast card is never appended (it only ever contributes
 *    a possible image backfill) — this is what prevents the "duplicate second Victini" entry.
 */
private fun mergeAndBackfillImages(
    fast: List<TcgCard>,
    tcgcsv: List<TcgCard>
): List<TcgCard> {
    // 1. Backfill blank images in place. Only patch when the fast card's image is blank.
    val patched = fast.map { card ->
        if (card.imageUrl.isBlank()) {
            val match = tcgcsv.firstOrNull { it.imageUrl.isNotBlank() && isSameCard(card, it) }
            if (match != null) card.copy(imageUrl = match.imageUrl) else card
        } else {
            card // existing non-blank image is never overwritten
        }
    }

    // 2. Append genuinely-new TCGCSV cards. Suppress any that are the same card as a fast entry
    //    (whether or not it was used for backfill) so patched cards don't get a duplicate.
    val seenKeys = fast.mapTo(HashSet()) { dedupeKey(it) }
    val appended = tcgcsv.filter { c ->
        dedupeKey(c) !in seenKeys && fast.none { isSameCard(it, c) }
    }

    return patched + appended
}

/** Cross-source "same physical card" test used for backfill + de-dup. */
private fun isSameCard(a: TcgCard, b: TcgCard): Boolean =
    namesMatch(a.name, b.name) && numbersMatch(a.number, b.number)

private fun namesMatch(a: String, b: String): Boolean {
    val na = a.lowercase().filter { it.isLetterOrDigit() }
    val nb = b.lowercase().filter { it.isLetterOrDigit() }
    return na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
}

/**
 * Number equality tolerant of cross-source formatting differences:
 *  - TCGCSV numbers arrive as "030/034" (slash fraction) or "SVP208" (promo prefix).
 *  - TCGdex/pokemontcg.io give "208", "58", "SVP208", etc.
 * Strategy: take the part before '/', keep alphanumerics, lowercase, drop leading zeros; compare.
 * If those differ, fall back to comparing the digit-only portions (so "svp208" == "208").
 */
private fun numbersMatch(a: String, b: String): Boolean {
    val na = normalizeNumber(a)
    val nb = normalizeNumber(b)
    if (na.isEmpty() || nb.isEmpty()) return false
    if (na == nb) return true
    val da = na.filter { it.isDigit() }.trimStart('0')
    val db = nb.filter { it.isDigit() }.trimStart('0')
    return da.isNotEmpty() && da == db
}

private fun normalizeNumber(raw: String): String =
    raw.substringBefore('/').filter { it.isLetterOrDigit() }.lowercase().trimStart('0')
```

**DECISION — matching strategy.** The spec says "matches by name+number." Cross-source `setName`
values differ (TCGdex uses a set code like `SVP`; TCGCSV uses the group name), so `dedupeKey`
(name|number|setName) will *not* match a genuine cross-source pair — that's exactly why the
Victini-SVP image needs a dedicated `isSameCard` (name+number only) rather than reusing `dedupeKey`.
Name match is punctuation-insensitive with containment tolerance ("Mr. Mime" vs "Mr Mime"); number
match tolerates slash-fractions, leading zeros, and promo prefixes. **Known limitation** (accepted,
documented, not a bug): two distinct promos sharing a Pokémon name and the same trailing digits but
different prefixes (e.g. a hypothetical `SWSH208 Victini` vs `SVP208 Victini`) could match — low
risk, and the worst outcome is a correct-Pokémon image on the wrong printing, never a crash or a
lost card. This is acceptable for a personal project per the spec's "no metrics theater" framing.

**DECISION — append de-dup semantics.** For appended cards I keep the existing `mergeResults`
convention (dedupe by `dedupeKey`) *plus* the new `isSameCard` suppression. The `isSameCard`
suppression is the stronger guard and is what actually prevents duplicates across sources; the
`dedupeKey` check is retained for consistency with the rest of the repo and to collapse exact
TCGCSV-internal repeats (already deduped inside `searchTcgcsvByName`, so this is belt-and-braces).

---

## 2. ManualSearchViewModel — progressive state + cancellation

### 2.1 `SearchState.Results` gains `stillSearching`

```kotlin
data class Results(val cards: List<TcgCard>, val stillSearching: Boolean = false) : SearchState()
```

Default `false` keeps `SearchState.Results(list)` construction valid where it doesn't matter.

### 2.2 New imports

```kotlin
import com.skyler.pokedexbinder.repository.SearchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
```

(`TcgCard`, `viewModelScope`, `launch`, `MutableStateFlow`, `StateFlow` already imported.)

### 2.3 Job field for cancellation

Add alongside the existing private fields:

```kotlin
private var searchJob: Job? = null
```

**DECISION — cancellation mechanism.** The spec offers "same `viewModelScope.launch` slot" or "a
`Job` reference cancelled before each new `search()`." I use the explicit `Job` reference: it's the
least ambiguous, survives `togglePromoFilter`/`retry` (which both call `search()`), and cancelling
the collecting coroutine propagates — via `collect` → the flow's `coroutineScope` → the TCGCSV
`async` — cancelling the in-flight 217-group scan. Backing out of the screen cancels
`viewModelScope` itself, which cancels `searchJob` the same way (confirms spec edge case; no extra
handling needed).

### 2.4 `search()` rewrite (full replacement of the current method)

```kotlin
fun search(query: String) {
    if (query.isBlank()) return
    val trimmed = query.trim()
    lastQuery = query
    searchJob?.cancel()                 // supersede any in-flight previous search
    _state.value = SearchState.Loading
    searchJob = viewModelScope.launch {
        try {
            val fastSearch: suspend () -> List<TcgCard> = {
                when {
                    isPromoNumber(trimmed) -> cardSearchRepository.searchByNumber(trimmed)
                        .ifEmpty { cardSearchRepository.searchByName(trimmed) }
                    _promoOnly.value -> cardSearchRepository.searchPromosByName(trimmed)
                    else -> cardSearchRepository.searchByName(trimmed)
                }
            }
            cardSearchRepository.searchStreaming(trimmed, fastSearch).collect { progress ->
                _state.value = when (progress) {
                    is SearchProgress.Fast ->
                        SearchState.Results(progress.cards, stillSearching = true)
                    is SearchProgress.Complete ->
                        SearchState.Results(progress.cards, stillSearching = false)
                }
            }
        } catch (e: CancellationException) {
            throw e // never swallow cancellation, never flip to Error on supersede
        } catch (e: Exception) {
            _state.value = SearchState.Error(e.message ?: "Search failed")
        }
    }
}
```

**Ordering is load-bearing:** cancel the old job *before* setting `Loading`, and the
`CancellationException` catch **must** precede the generic `Exception` catch (it's a subclass) so a
superseded search doesn't render an error. The `trimmed` value doubles as the TCGCSV scan key; for
promo-number / promo-only queries `searchTcgcsvByName(trimmed)` simply returns empty (no name match)
— harmless, matches today's retry-on-empty behavior which also scanned by the raw query.

### 2.5 `retry()` collapse (full replacement)

The retry-on-empty-TCGCSV branch is dead (TCGCSV always runs now). Collapse to just re-running the
search — which covers the Error-state "Try Again" path unchanged:

```kotlin
fun retry() {
    if (lastQuery.isBlank()) return
    search(lastQuery)
}
```

`togglePromoFilter()` is unchanged (it already calls `search(lastQuery)`).

---

## 3. ManualSearchScreen — subtle in-progress indicator, button removal

Replace the entire `is SearchState.Results -> { ... }` branch (current lines 100–141) with the
block below. No new imports required — `LinearProgressIndicator` is covered by the existing
`androidx.compose.material3.*` wildcard, and the removed `OutlinedButton` was also from that
wildcard.

```kotlin
is SearchState.Results -> {
    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (s.stillSearching) {
            // Thin, non-blocking indeterminate bar under the filter row. Does not intercept
            // touches on the results below it — user can tap/assign visible cards while it shows.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (s.cards.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (s.stillSearching) {
                        "No cards found yet — checking more sources…"
                    } else {
                        "No cards found"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            Text(
                "Tap to select · Long-press to preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(s.cards, key = { it.id }) { card ->
                    ListItem(
                        headlineContent = { Text(card.name) },
                        supportingContent = { Text("${card.setName} · #${card.number}") },
                        leadingContent = {
                            AsyncImage(
                                model = card.imageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { onCardSelected(card) },
                            onLongClick = { previewCard = card }
                        )
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
```

Key points vs. current code:
- **"Search more sources (TCGplayer)" `OutlinedButton` deleted** (P0 dead-code removal).
- Empty state now distinguishes "still scanning" ("…checking more sources…") from "done, nothing"
  ("No cards found"), so an empty-then-merges-in flow (Goal 2) reads correctly and the empty state
  still shows immediately when the fast sources return nothing (Non-Goal: don't remove empty state).
- `stillSearching` bar sits **above** the list/empty area, so it never pushes results out of view.
- `key = { it.id }` retained → when `Complete` swaps in the merged list, unchanged items keep
  identity and don't visibly re-render; a backfilled card keeps the same `id` (only its `imageUrl`
  changed) so `AsyncImage` just reloads that one row's model. No full-list flicker.

The `Loading` branch (initial spinner, before any `Fast` emission) is unchanged.

---

## 4. Edge cases — resolution summary

| Edge case | Handling | Where |
|---|---|---|
| User backs out mid-scan | `viewModelScope` cancels `searchJob` → `collect` cancels → flow `coroutineScope` cancels TCGCSV `async`. Natural, no extra code. | §1.3, §2.3 |
| New search before previous `Complete` | `searchJob?.cancel()` at top of `search()`; `CancellationException` rethrown, not turned into Error. | §2.4 |
| Empty fast + TCGCSV also empty | `Fast([], still=true)` then `Complete([], still=false)`. Empty state shows immediately, indicator clears on Complete. | §3 |
| TCGCSV scan throws | Impossible to escape: `searchTcgcsvByName` catches internally → `emptyList()`. Flow can't fail from it. No new failure mode. | §1.3 |
| Card in both fast + TCGCSV, fast already has a (different) image | `isSameCard` suppresses the TCGCSV append; blank-only backfill guard means the existing image is never overwritten. | §1.4 |
| Card in fast with blank image, TCGCSV has it with image | Image patched in via `.copy(imageUrl=…)`; TCGCSV card suppressed from append (no duplicate). | §1.4 |
| TCGCSV-only card (fast found nothing, e.g. CLB Mr. Mime) | Not same as any fast card, `dedupeKey` unseen → appended. | §1.4 |

---

## 5. Tests

### 5.1 `CardSearchRepositoryTest.kt` — ADD (keep all existing tests unchanged)

New imports:

```kotlin
import com.skyler.pokedexbinder.data.model.TcgCard
import kotlinx.coroutines.flow.toList
```

(`SearchProgress` is same-package — no import.) Add these four tests:

```kotlin
@Test
fun `searchStreaming emits Fast before Complete`() = runTest {
    coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = emptyList())
    val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
    val fast = listOf(
        TcgCard("base1-1", "Pikachu", "58", "Base", "https://img/a.jpg", listOf("Pikachu"))
    )

    val emissions = repo.searchStreaming("Pikachu") { fast }.toList()

    assertEquals(2, emissions.size)
    assertTrue(emissions[0] is SearchProgress.Fast)
    assertTrue(emissions[1] is SearchProgress.Complete)
    assertEquals(fast, (emissions[0] as SearchProgress.Fast).cards)
}

@Test
fun `searchStreaming backfills blank image on existing card without duplicating`() = runTest {
    val group = TcgcsvGroupDto(groupId = 5, name = "SV Promo")
    val product = TcgcsvProductDto(
        productId = 208, name = "Victini", cleanName = "Victini",
        imageUrl = "https://tcgcsv/victini.jpg", groupId = 5,
        extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "SVP208"))
    )
    coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
    coEvery { tcgcsvApi.getProducts(5) } returns TcgcsvProductsResponse(results = listOf(product))
    val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
    val fast = listOf(
        TcgCard("tcgdex_svp-208", "Victini", "208", "SVP", "", listOf("Victini"))
    )

    val complete = repo.searchStreaming("Victini") { fast }.toList()
        .last() as SearchProgress.Complete

    assertEquals(1, complete.cards.size)                                  // no duplicate appended
    assertEquals("tcgdex_svp-208", complete.cards[0].id)                  // identity unchanged
    assertEquals("https://tcgcsv/victini.jpg", complete.cards[0].imageUrl) // image patched in
}

@Test
fun `searchStreaming appends TCGCSV-only card when fast empty`() = runTest {
    val group = TcgcsvGroupDto(groupId = 7, name = "Pokemon TCG Classic")
    val product = TcgcsvProductDto(
        productId = 99, name = "Mr. Mime", cleanName = "Mr. Mime",
        imageUrl = "https://tcgcsv/mrmime.jpg", groupId = 7,
        extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "030/034"))
    )
    coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
    coEvery { tcgcsvApi.getProducts(7) } returns TcgcsvProductsResponse(results = listOf(product))
    val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)

    val complete = repo.searchStreaming("Mr. Mime") { emptyList() }.toList()
        .last() as SearchProgress.Complete

    assertEquals(1, complete.cards.size)
    assertEquals("tcgcsv_99", complete.cards[0].id)
}

@Test
fun `searchStreaming does not overwrite an existing non-blank image`() = runTest {
    val group = TcgcsvGroupDto(groupId = 5, name = "SV Promo")
    val product = TcgcsvProductDto(
        productId = 208, name = "Victini", cleanName = "Victini",
        imageUrl = "https://tcgcsv/victini.jpg", groupId = 5,
        extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "SVP208"))
    )
    coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
    coEvery { tcgcsvApi.getProducts(5) } returns TcgcsvProductsResponse(results = listOf(product))
    val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
    val fast = listOf(
        TcgCard("tcgdex_svp-208", "Victini", "208", "SVP", "https://existing/img.jpg", listOf("Victini"))
    )

    val complete = repo.searchStreaming("Victini") { fast }.toList()
        .last() as SearchProgress.Complete

    assertEquals(1, complete.cards.size)                                   // TCGCSV dup suppressed
    assertEquals("https://existing/img.jpg", complete.cards[0].imageUrl)   // not overwritten
}
```

**Note for Coder:** confirm the actual constructor parameter order of `TcgcsvGroupDto`,
`TcgcsvProductDto`, `TcgcsvExtendedDataDto`, and their response wrappers against
`data/remote/` — the shapes above match the existing passing tests
(`searchTcgcsvByName maps products…`), so they should compile as written, but verify field names if
the DTOs changed.

### 5.2 `ManualSearchViewModelTest.kt` — DELETE / REWRITE / ADD

**DELETE these two existing tests** (the behavior they assert is removed by this change):
- `` `first search does NOT query TCGCSV` `` — first search now *does* run TCGCSV concurrently.
- `` `retry after empty results queries TCGCSV and shows its results` `` — the retry-on-empty
  branch is deleted.

**DELETE/REPLACE this test:**
- `` `retry after non-empty results does NOT query TCGCSV` `` — retry no longer has a TCGCSV
  branch; replace with the streaming-based `retry re-runs search` test below.

**REWRITE this test** for the streaming API:
- `` `retry from Error state re-runs search, not TCGCSV` `` → `` `search error then retry re-runs` ``
  below.

New imports:

```kotlin
import com.skyler.pokedexbinder.repository.SearchProgress
import io.mockk.every
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
```

**IMPORTANT mocking note:** `searchStreaming` is a **non-suspend** function returning `Flow`, so
stub it with `every { ... }` (not `coEvery`). The `fastSearch` lambda arg is matched with `any()`
and is never invoked (the canned flow is returned directly), so the inner `searchByName`/etc. calls
need no stubs — they're covered in the repository tests.

The existing `setUp`/`tearDown` (`UnconfinedTestDispatcher`, `Dispatchers.setMain`) and the `card()`
helper stay. Final test set:

```kotlin
@Test
fun `search shows stillSearching until fast resolves`() = runTest {
    val fast = listOf(card("base1-1"))
    // Emit Fast, then hang -> collection parks at stillSearching = true.
    every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } returns
        flow { emit(SearchProgress.Fast(fast)); awaitCancellation() }

    val vm = ManualSearchViewModel(cardSearchRepository)
    vm.search("Mr Mime")

    assertEquals(SearchState.Results(fast, stillSearching = true), vm.state.value)
}

@Test
fun `search clears stillSearching on Complete`() = runTest {
    val fast = listOf(card("base1-1"))
    val complete = listOf(card("base1-1"), card("tcgcsv_1"))
    every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } returns
        flowOf(SearchProgress.Fast(fast), SearchProgress.Complete(complete))

    val vm = ManualSearchViewModel(cardSearchRepository)
    vm.search("Mr Mime")

    assertEquals(SearchState.Results(complete, stillSearching = false), vm.state.value)
}

@Test
fun `new search cancels previous in-flight search`() = runTest {
    var slowCancelled = false
    every { cardSearchRepository.searchStreaming(eq("Slow"), any()) } returns
        flow {
            emit(SearchProgress.Fast(listOf(card("slow"))))
            try { awaitCancellation() } finally { slowCancelled = true }
        }
    every { cardSearchRepository.searchStreaming(eq("Fast"), any()) } returns
        flowOf(
            SearchProgress.Fast(listOf(card("fast"))),
            SearchProgress.Complete(listOf(card("fast")))
        )

    val vm = ManualSearchViewModel(cardSearchRepository)
    vm.search("Slow")
    assertEquals(SearchState.Results(listOf(card("slow")), stillSearching = true), vm.state.value)

    vm.search("Fast")

    assertTrue("previous search's flow should have been cancelled", slowCancelled)
    assertEquals(SearchState.Results(listOf(card("fast")), stillSearching = false), vm.state.value)
}

@Test
fun `search error then retry re-runs search`() = runTest {
    var call = 0
    every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } answers {
        call++
        if (call == 1) flow<SearchProgress> { throw RuntimeException("boom") }
        else flowOf(SearchProgress.Complete(emptyList()))
    }

    val vm = ManualSearchViewModel(cardSearchRepository)
    vm.search("Mr Mime")
    assertTrue(vm.state.value is SearchState.Error)

    vm.retry()

    assertEquals(SearchState.Results(emptyList(), stillSearching = false), vm.state.value)
}
```

**Why `awaitCancellation()` instead of collecting the StateFlow for the intermediate assertion:**
`state` is a conflated `StateFlow`; under `UnconfinedTestDispatcher` a `Fast` then `Complete` emitted
back-to-back would conflate and a collector could miss the `Fast` frame. Parking the flow at
`awaitCancellation()` after `Fast` makes `stillSearching = true` the stable current value, so the
assertion is deterministic. `runTest` does not await `viewModelScope` children, so the parked
coroutine doesn't hang the test.

---

## 6. Manual on-device verification (post-implementation, for the human)

Not automatable — listed so the Reviewer/Skyler runs them (P0 acceptance):
1. Search "Mr Mime" → fast (empty/near-empty) shows immediately with the thin progress bar; within
   ~10s **CLB Mr. Mime** appears in the list without any tap; bar disappears.
2. Search "Victini" → SVP card shows immediately (no image); a few seconds later its **image appears
   in place**, not as a second Victini row.
3. Start a second search while the first's bar is still visible → the first scan's results never
   appear in the second search's list.

---

## 7. Build/verify commands

```
./gradlew :app:testDebugUnitTest --tests "com.skyler.pokedexbinder.repository.CardSearchRepositoryTest"
./gradlew :app:testDebugUnitTest --tests "com.skyler.pokedexbinder.ui.ManualSearchViewModelTest"
./gradlew :app:compileDebugKotlin
```

Full suite must be green (P0). Then hand to Coder.
