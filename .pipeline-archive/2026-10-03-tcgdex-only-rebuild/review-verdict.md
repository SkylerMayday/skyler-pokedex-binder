# Aggregate review verdict (multi-lens, orchestrator-written) - pass 1 of max 3

Trigger: diff 40 files / ~1580 changed lines (+ new files) >= multi-lens threshold. Lenses ran on Opus.

| Lens | Score | Verdict | Report |
|---|---|---|---|
| Correctness + spec trace | 83 | needs-changes (P2 only) | review-correctness.md |
| Security + reliability + data safety | 80 | needs-changes (1 P1) | review-security.md |
| Maintainability + tests | 82 | needs-changes (6 P2) | review-maintainability.md |

**Aggregate = min = 80/100, verdict needs-changes (no reject).** Below 85 -> Item I auto-loop: Coder fix pass, Skyler tests, re-review.

## Findings to fix (de-duplicated across lenses)

1. **P1/P2 (all lenses saw it)** `TcgcsvJapanSource.loadGroups` records no failure -> every lookup re-calls `getJapanGroups()`; groups call outside FetchBudget; ~30 serialized calls per ja list; 60s stall per call. Fix: failure cooldown (~15 min, longer on 401/403), take one budget token before a cold groups fetch, test (groups failing + 5 lookups = exactly 1 call).
2. **P2** `PersonalCollectionRepository` partial refresh: dex resolve fails or one language call fails -> shorter non-empty list replaces cached rows for that language. Fix: never drop cached rows of a language whose fetch failed (or skip replacing the section if any call failed); test.
3. **P2/P3** `CardSearchRepository.toCards` fills Japanese images BEFORE number/name filtering, spending the 3-download budget on discarded cards. Fix: fill after filtering; test.
4. **M1** delete dead leftovers of the ptcg removal: `TcgCard.hp/artist/setReleaseDate` (verify zero readers first, incl. publish/restore/tests), `BinderRepository.getRowsNeedingBackfill/backfillCardNameSet` + the two `MainBinderDao` methods, stale comment near `CardSearchRepository.kt:539`.
5. **M2** delete the dead release-date path: `TcgdexSetCatalog.releaseDate`, `TcgdexApi.getSet`, `TcgdexSetDetailDto`, `englishName(setId)` if only tests use it (keep `englishNames()`); fix/remove the no-op `sortedByDescending { it.releaseDate }` and its false comment in `PublishRepository` (~L393).
6. **M3** one source of truth for the `tcgdex_[<lang>_]<id>` id scheme: make `Language.fromCardId`, `CardSearchRepository` prefixes/`isNonEnglishTcgdexId`/id builder and `JapanTcgcsvResolver`'s id parsing all use `TcgdexIds`. Adding a language must not silently fall back to EN.
7. **M4** restore lost coverage: a test that name+number+total is tried before dex+number+total (swapping them must fail), and the positive "continues to broad-number after number+total is name-filtered out" case.
8. **M5 + correctness tests** add: concurrent auto-sync returns null (`mutex.tryLock` path); TCGCSV groups failure-path call count (covered by #1); PC "owned survives a second refresh"; the 2-newest-groups branch if cheap.
9. **P3** `viewModelScope.launch { runCatching { imageSync.syncIfDue() } }` must not swallow CancellationException; `syncIfDue` `.take(limit)` should not starve ids behind 400 never-resolvable (ko/zh-cn) blanks (e.g. rotate/offset by last-attempt order or prioritise ja/en; pick the smallest correct change and document); reset share sheet must not reopen on rotation (consume the share event once); `FetchBudget.left` -> private/encapsulated.
10. **Leave alone:** `img.pokemontcg.io` fixtures in `Migration8to9Test` (historical v8 data); the 5 per-DAO blankImage queries (Room needs per-table SQL); T7 spec/doc drift (done at wrapcon); `CallStats` rename (optional, skip unless trivial); unchanged User-Agent (orchestrator verified HTTP 200).
