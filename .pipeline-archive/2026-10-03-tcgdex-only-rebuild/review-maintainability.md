# Review: maintainability + simplification + test quality (lens 3 of 3), re-review pass 2

**Date:** 2026-10-03 · **Scope:** fix pass 1 (changes.md "Fix pass 1") on top of passes 1-3. I checked it against my pass-1 report (82), `review-verdict.md`, `test-results.md` + `evidence/test-results.log` (505/0 failures, `--rerun-tasks`, run by Skyler), and fresh greps over `app/src/{main,test,androidTest}`.
**Verdict: ship.** Score **94/100**, up **+12** from 82. There is no P0/P1. The one remaining P2 (M6, Room SQL not executed) is a verification step, not a code defect.

## Score

| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 23/25 | Fix items 1-9 are all traced to code and tests. One deviation is documented and sound: no budget token is taken for the groups call. Cost: the spec text still describes the dropped release-date ordering and T8a. That is T7 work, listed below |
| Correctness | 25/25 | This lens found no bug. The cascade order, the PC partial-refresh guard and the post-filter Japanese fill are each pinned by an assertion that fails on the old code |
| Security & reliability | 19/20 | The groups cooldown (15 min / 6 h on 401/403) removes the retry storm. The UA is unchanged: the orchestrator saw HTTP 200, but the phone is still unverified |
| Maintainability | 14/15 | M1, M2, M3 and M8 are resolved cleanly. Cost: one false KDoc cost claim (N1), plus the M12 comment and the CallStats name, both accepted for T7 / leave-alone |
| Test evidence | 13/15 | The 490→505 delta reconciles exactly (+17 −2). The M4 and M5 tests now fail on broken code. Cost: M6 is still unexecuted, the two-newest test does not pin `take(2)` (N2), and one PC test is a mock echo (N3) |

## Prior finding status

| ID | Pass-1 sev | Status | Evidence |
|---|---|---|---|
| M1 dead ptcg leftovers | P2 | **fixed** | `TcgCard` ends at `val pokemonNames: List<String>`. Fresh grep: `getRowsNeedingCardBackfill`, `getRowsNeedingBackfill`, `backfillCardNameSet`, `setReleaseDate` = 0 hits. The `.artist` hits are all `ParsedCardInfo`/`GeminiCardResult` |
| M2 dead release-date path | P2 | **fixed** | 0 hits for `getSet(`, `TcgdexSetDetailDto`, `releaseDate(`, `englishName(`. `TcgdexSetCatalog` now only has `englishNames()`. The publish line is now `slots = rows   // already in the cache query's order`, which is correct: `getAllCache` is `ORDER BY releaseDate DESC, setName, cardId`. The `releaseDate` column is kept (schema) and every write is `releaseDate = ""` |
| M3 id scheme in 4 places | P2 | **fixed** | `fun fromCardId(id: String): Language = when (TcgdexIds.parse(id)?.lang)`. The builder is `id = TcgdexIds.cardId(lang, id)`. The resolver uses `TcgdexIds.localId(ref.id)`. `TCGDEX_EN_ID_PREFIX` and `isNonEnglishTcgdexId` = 0 hits. Guard test: `assertNotEquals(lang, Language.EN, Language.fromCardId(TcgdexIds.cardId(lang, "s1-001")))` over `TCGDEX_ASIAN_LANGS` |
| M4 cascade-order coverage | P2 | **fixed** | See "Tests that fail on broken code" |
| M5 concurrent auto sync | P2 | **fixed** | See "Tests that fail on broken code" |
| M6 Room SQL never executed | P2 | **open** (not in the fix list) | `test-results.md`: `compileDebugAndroidTestKotlin BUILD SUCCESSFUL`. Compiled, never run. Carried below |
| M7 `CallStats` name/KDoc | P3 | accepted (verdict item 10) | `/** Per-search tally of TCGdex call outcomes ... */` now also holds `japanBudget` and `allSucceeded()` |
| M8 `FetchBudget.left` public var | P3 | **fixed** | `var left: Int = initial` / `private set` |
| M9 Reset use case pass-through | P3 | accepted | Unchanged; the spec named it |
| M10 over-claiming test names | P3 | **open** | `cancel at either confirmation writes nothing` and `resolver is tried only after tcgdex has no image and first non-null wins` are still present. The duplicate Unavailable tests at L326 and L826 are both still there |
| M11 2-newest branch untested | P3 | **fixed, weakly** | See N2 |
| M12 stale androidTest comment | P3 | open → T7 | `AppNavigationScreenTest.kt:79` still reads `fans out real pokemontcg.io/TCGdex network calls` |
| M13 unrelated uncommitted edits | P3 | open | `M CLAUDE.md`, `M app/CLAUDE.md`, `M gaps.md` are still mixed into the tree. Commit them separately |

## `tcgdex_` literals outside `TcgdexIds`

- **main:** only KDoc, at `CardSearchRepository.kt:524` (`en keeps "tcgdex_<id>" (stable for saved entries)`) and `:526`. That is documentation and acceptable. The only other card-id prefix literal is `id = "tcgcsv_$productId"`. That is a separate scheme with a single builder, and `parse` returns null for it, as intended.
- **test/androidTest:** fixture ids such as `"tcgdex_ja_M6-048"` in Sync, PC, SmartThreshold, CardSearch and `PokedexDatabaseTest:82`. **Keep them.** These ids are persisted in Room, so hard-coded fixtures are the right pin: a scheme change inside `TcgdexIds` should break them.
- **Remaining language knowledge outside `TcgdexIds`:**
  - `LANGS_WITHOUT_TCGDEX_IMAGES = setOf("ko", "zh-cn")` (Sync) and `tcgdexLangsFor`/`CROSS_LANGUAGE_BASES` hold data facts and search policy, not the id scheme. That is acceptable.
  - The `when` in `fromCardId` restates the language codes, but the guard test above covers it.

## Tests that fail on broken code

- **M4, cascade order:** `tries dex+number+total before name+number+total and species-blind number+total` stubs all three steps with distinct ids (`x-116` / `n-116` / `bw-116`). It asserts `assertEquals(listOf("tcgdex_x-116"), ...)` and `coVerify(exactly = 0) { tcgdexApi.filterCards("en", "116", "eq:172", null, "Castform") }`. If the dex and name blocks swap, the name step returns `n-116`, the id assertion fails, and the verify count becomes 1. **Pinned.**
  - The fall-through case is restored with total 64: `parsed("Black Kyurem", "11", "64", null)` → `"broad-number"`.
- **Coder's claim about "name before dex": correct.** The code reads `// Species-pinned (dex, then name) before the species-blind number+total steps`. HEAD's TCGdex tier had the same order: `HEAD:100 // TCGdex first: species-pinned (dex, then name)`. HEAD's `still tries name before dex` stubbed only the **ptcg** queries (`stub("name:*Castform* number:\"116\" set.total:172", ...)`) and asserted `"name+number+total"`, so it pinned the deleted ptcg tier. The aggregate's item 7 wording ("name+number+total is tried before dex+number+total") inverted the live TCGdex order, and the Coder was right to follow M4's text. My own pass-1 shorthand "HEAD's `still tries name before dex`" helped cause the confusion: that test pinned the ptcg tier, not the TCGdex one.
- **Mew vs Mewtwo:** unchanged and still pinned by `bestNameMatch` (`firstOrNull { normalizeName(it.name) == target } ?: ...`).
- **Japan fill after filtering:** the test asserts `coVerify(exactly = 1) { japanImages.imageUrl(any(), any(), any()) }` over 3 ja briefs, of which 2 are dropped by the filters. The old fill inside `toCards` made 3 calls. **Pinned.**
- **Cooldown:**
  - `repeat(5) { assertNull(s.imageUrl("M6", "048", budget)) }` then `coVerify(exactly = 1) { api.getJapanGroups() }`. Without the cooldown that is 5 calls → fails.
  - The 14/16-minute boundary pins `GROUPS_RETRY_MS`.
  - The 401 test (`now += hour` → still 1 call) fails if the 6 h branch is removed.
  - **Pinned.**
- **Concurrent sync (M5):** if `tryLock` is removed or becomes `withLock`, `syncIfDue` suspends on `gate.await()` before `gate.complete(Unit)` runs, so the test fails. It fails by a `runTest` timeout, not a clean assertion. That is slow but real. Acceptable.
- **Starvation:** 400 ko/zh-cn ids are placed ahead of ja/en, and `coVerify(exactly = 1) { api.getCard("ja", "M6-048") }`. Without the `sortedBy`, the ja/en ids fall outside `take(400)` → fails. **Pinned.**
- **PC partial refresh:** `coVerify(exactly = 0) { dao.replaceCacheForPokemon(any(), any()) }` with `complete = false`. Always replacing → fails. **Pinned.**
- **Count:** 490 − 2 + 4 + 2 + 1 + 4 + 2 + 2 + 2 = 505, which equals the evidence line `tests 505 skipped 0 failures 0 errors 0`.

## Fix-pass hygiene (duplication / magic numbers)

Every new number has a name:
- `GROUPS_RETRY_MS = 15L * 60 * 1000`
- `GROUPS_BLOCKED_RETRY_MS = 6L * 60 * 60 * 1000`, with a why-comment
- `JAPAN_IMAGE_FETCHES_PER_SEARCH = 3`
- `LANGS_WITHOUT_TCGDEX_IMAGES`

Other checks:
- The 401/403 literals are standard HTTP codes. Fine.
- `groupsRetryAt` is only read and written inside `groupsLock.withLock` (L111, L122, L125). The Mutex gives happens-before, so it needs no `@Volatile`.
- The `ponytail:` comment in Sync states a true ceiling and an upgrade path: `still starves if >MAX_AUTO_IDS resolvable-language ids stay image-less; rotate an offset if that ever happens`.
- The `FetchBudget` KDoc records the budget-token deviation accurately.
- `TcgdexIds.cardId`, `isNonEnglish` and `localId` are one-liners on top of `parse`. The fix added no new duplication.
- The duplicated `name.split(" & ")...` at `CardSearchRepository.kt:552` predates this build (HEAD:545). Out of scope.

## Findings

### P2

**M6 (carried): reset/fill Room SQL is still compiled only.** `test-results.md`: `compileDebugAndroidTestKotlin BUILD SUCCESSFUL 16s`. `resetAllClearsAssignmentsButKeepsSlotRowsAndGroups` and `fillBlankImageOnlyTouchesBlankRows` have never run, and `fillBlankImage` is covered for `main_binder` only. Fix (default): Skyler runs `connectedDebugAndroidTest --tests '*PokedexDatabaseTest*'` once on the phone. Alternatively, record at T7 that spec verification steps 3 and 6 are the accepted check. This does not block ship. Confidence 9.

### P3

- **N1: the KDoc cost claim is false.** `TcgcsvJapanSource.kt:56`: `One lookup costs at most one groups call plus one product-list call`. In fact `candidateGroups` can return `matching.sortedByDescending { it.p }.take(2)`, and `findInGroup` can refetch on a miss (`if (!attempted && nowMs() - file.fetchedAt >= DAY_MS)`). Without a budget (the sync resolver passes none), one lookup can therefore make 2 product calls. Reword it to "one product-list call per candidate group (max 2); searches are capped by [FetchBudget]". Confidence 8.
- **N2: the two-newest test does not pin `take(2)`.** It stubs group 3 as a miss and group 2 as a hit, then asserts `coVerify(exactly = 0) { api.getJapanProducts(1) }`. The loop returns on group 2's hit, so `take(3)` or no `take` passes too. It does pin newest-first. To pin the cap, make group 2 also miss and give group 1 the number, then assert null and 0 calls to group 1. Confidence 9.
- **N3: mock-echo PC test.** `a second identical refresh writes the same rows and never touches owned entries` asserts `coVerify(exactly = 0) { dao.upsertEntry(any()) }`, but `refreshPokemon` never calls the entry DAO, so this assertion cannot fail. "Owned survives" is a property of `replaceCacheForPokemon`'s SQL (androidTest/M6). It is not harmful; the name over-claims. Rename it to "...is deterministic", or move the owned-survives check into `PokedexDatabaseTest`. Confidence 8.
- **N4: the share-sheet fix is pinned at VM level only.** The test asserts `assertEquals(ResetUiState.Confirm2(intent, shareSheetShown = true), vm.state.value)`. The real gate, `if (current is ResetUiState.Confirm2 && !current.shareSheetShown)` in `SettingsScreen.kt:83`, is Compose and untested. That is acceptable for a personal app, and rotation can be checked by hand on the phone. Confidence 7.
- **9a, no cancellation test:** accepted. The Coder's reason holds: the launched job is private.

## T7 (docs at wrapcon) must still correct

1. **`specs.md`:**
   - Drop or replace the release-date text: L47 (`englishName`, `releaseDate(lang,setId)`), L51 (`releaseDate` bullet: "else `setCatalog.releaseDate`"), L124 ("newest set first"), L192 step 5 ("newest first"). Replace with "no date ordering; DAO sorts `setName, cardId` (`releaseDate` column always `""`)".
   - Record the T8a decision. L90 ("Use `PokedexBinder/1.0`"), L154 and L172 are superseded by "UA unchanged, orchestrator verified HTTP 200". `NetworkModule.kt:62` still has `.header("User-Agent", "Mozilla/5.0 (Linux; Android 13) PokedexBinder")`.
   - Record the groups-call budget deviation: L154 says "<=4 calls per search". That still holds as 1 groups call (cooldown/24 h cached) + 3 products, so only a note is needed.
2. **`project-overview.md`:**
   - L25 "**pokemontcg.io** (primary)"
   - L766 "(pokemontcg.io/TCGdex)"
   - L854 "`pokemontcg.io` cascade ... `POKEMONTCG-IO-FALLBACK`"
   - L868 "`searchByName` (Personal Collection) stays ptcg-first until an id-remap migration is planned"
   - Rewrite all of these for: TCGdex-only, wipe/rescan, image sync (24 h auto, 400 cap, ko/zh-cn last), the TCGCSV Japan source (24 h groups, 90-day settled files, 15 min / 6 h cooldown, budget 3), the Discord toggle, and `TcgdexIds` as the id-scheme owner.
3. **`handoff.md`:** L36 decision row 3 (ptcg kept as fallback) and L58 next step 3 (id-remap plan). Both are superseded by the removal plus the wipe.
4. **`gaps.md`:**
   - Close L27 (ptcg shutdown / `searchByName` still ptcg-first).
   - Add M6 (androidTest unexecuted), the `MAX_AUTO_IDS` starvation ceiling (the ponytail comment), the 404-counts-as-failed retry loop (changes.md), N2/N3 if not fixed, and U3 deferred with its sketch (spec L109).
   - L37 (old `scan_debug_*.jpg` on the phone) already exists. Keep it.
5. **The M12 comment fix** at `AppNavigationScreenTest.kt:79`. Then rerun `grep -ri "pokemontcg\|PokemonTcgApi\|TcgCardDto" app/src/main app/src/test`. It is clean now: hits remain only in the androidTest `Migration8to9Test` fixtures (leave-alone) and that comment.
6. **The M13 commit split:** commit the unrelated `CLAUDE.md`, `app/CLAUDE.md` and `ScannerViewModel` edits separately.

## Friction rollup (Item F)

Not written by this lens. Three lenses run in parallel, and adding to the index means rewriting `~/.claude/rules/lessons.md` in full (I have no Edit tool), so a parallel write would clobber the other lenses' additions. I hand it to the orchestrator for the single end-of-run pass. Candidates:
- **(a)** A "only the first N candidates are tried" test needs the N+1th candidate to be the *only* match. A `coVerify(exactly = 0)` on a later candidate proves nothing when the loop short-circuits on an earlier hit (N2).
- **(b)** When an aggregate's paraphrase contradicts a lens's concrete fix text, the code comment plus the lens text win. Fold this into `aggregate-multi-lens-findings-by-positive-list-diff`.
- **(c)** `grep -- "$pat" dirs --include=...` treats `--include` as a path; use `-e`. This is minor and arguably already covered by the general grep lessons.
