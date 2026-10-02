# Review verdict: TCGdex primary card source (pass 2)

**Verdict: ship** (score 91/100, up 8 from pass 1's 83; no P0/P1). I did not run Gradle. Skyler's `--rerun-tasks` run is in `.pipeline/test-results.md`: "tests 361 skipped 0 failures 0 errors".

## Prior findings, re-verified in code
| # | Status | Evidence (quoted) |
|---|---|---|
| F1 | FIXED | `firstOrNull { normalizeName(it.name) == target } ?: firstOrNull { namesMatch(it.name, name) }`. Used in `resolveDex` (`}.bestNameMatch(name) ?: return null`) and in the seeding line (`en.bestNameMatch(name)?.pokemonNames ?: pokemonNamesOf(name)`). The seeding line is a same-root-cause catch beyond what I flagged. |
| F2 | FIXED | `val pinnedNoImage = identityPinned && single.imageUrl.isBlank() && hashMatch != null && ...`. TCGdex `toDomain` gives a blank url only when the API has no image: `image?.let { "$it/high.webp" } ?: ""`. |
| F3 | DEFERRED (accepted) | The broad-dex step still uses `langs[0]`. It is spec-literal and was an open question in the spec. It stays a tracked P3. |
| F4 | FIXED | `} catch (e: CancellationException) { throw e } catch (e: Exception) {` appears in `tcgdexFilter`, `resolveDex` and `tcgdexSearchByName`, with the import `kotlinx.coroutines.CancellationException`. Rethrow before the broad catch is correct inside suspend try blocks. `safeSearch` is untouched and keeps its old behaviour (out of scope). |

## Regression checks
- **`namesMatch` refactor: identical behaviour.** Old: `a.lowercase().filter { it.isLetterOrDigit() }` for both inputs, then `na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))`. New: `normalizeName` is the same expression, and the final line is unchanged. The other callers (`matchingParsedName`, the dedupe `namesMatch(a.name, b.name)`) are unaffected.
- **`bestNameMatch` is a strict refinement.** The exact match is tried first, then the old substring behaviour. An exact match is always also a `namesMatch`, so it only changes the pick when an earlier substring-only card existed. That is the intended change.
- **Edited `SmartThresholdUseCaseTest` is legitimately updated, not weakened.** The pinned-accept test now sets `.copy(imageUrl = "")`, which is exactly the new contract. A new test, "real image failed to download is rejected", covers the opposite case (`card(...)` has an image, distance `Int.MAX_VALUE`, pinned, so NONE). The "image disagrees (30)", "unpinned" and "multiple candidates" tests are untouched.
- **New tests fail on the old code:**
  - Mew vs Mewtwo: old code picks Mewtwo (dex 150), so the ja `eq:150` result would produce `tcgdex_ja_SV2a-150`. The assertion expects `[..., "tcgdex_ja_SV2a-151"]` with `pokemonNames == ["Mew"]`, so it fails on the old code.
  - Failed download not pinned: the old condition accepted it, so the test fails on old code.
- **The IOException test asserts real behaviour:** a ptcg result is returned, `matchedQuery == "dex+number+total"`, and `assertFalse(result.identityPinned)`. It does not echo mocks.
- **The null-language test is a coverage gap-closer, not a regression test.** The old code was already ja-first. It uses `coVerifyOrder`, which asserts the call order.

## Findings (new this pass)
- **F5 (P3, conf 4, shown only as a note) Empty-after-normalize name.** `normalizeName(name) == ""` for a name made only of symbols would exact-match any card whose own name normalizes to "". `val target = normalizeName(name)` followed by `normalizeName(it.name) == target` has no empty guard. TCGdex card names are never empty, and the previous `namesMatch` had an `isNotEmpty` guard, so this is a theoretical edge. Optional one-line guard; not blocking.
- No other new issues found in the fix diff. The first pass's items 1-8, the intent audit (all requirements DONE) and the scope-creep result (none) still hold.

## Score
| Dimension | Pts | Reason |
|---|---|---|
| Spec compliance | 24/25 | All requirements traced. F3 is a spec-literal, deferred risk (-1). |
| Correctness | 23/25 | F1 and F2 fixed with tests; F3 deferred (-1); F5 theoretical (-1). |
| Security & reliability | 19/20 | Cancellation is now rethrown in the new code (-1: pre-existing `safeSearch` still swallows it). |
| Maintainability | 13/15 | Small, clear helper; `normalizeName` is deduplicated; the dead `!startsWith(ja)` check from pass 1 remains (-2). |
| Test evidence | 12/15 | 361/0 from a `--rerun-tasks` run; the new tests assert behaviour. A positive-list gap remains: the `filterCards` throw is tested but a throw in `tcgdexSearchByName` and a cancellation-propagation test are not (-3). |
| **Total** | **91/100** | Improved +8 from 83. At or above the 85 gate with no blocking finding, so ship. |

Deferred, tracked: F3 (broad-dex en candidates when language is null or ja). Optional: F5 guard.
