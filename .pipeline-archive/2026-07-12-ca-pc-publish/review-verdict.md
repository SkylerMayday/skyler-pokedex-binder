# Stage 5 (Reviewer) — Verdict

**Spec:** `docs/superpowers/specs/2026-07-12-connecting-art-personal-collection-publish.md`
**Plan:** `.pipeline/specs.md` | **Changes:** `.pipeline/changes.md` | **Tests:** `.pipeline/test-results.md`

## VERDICT: SHIP

All 8 P0 requirements independently traced to real code changes, non-goals honored, resolved
ambiguities are sound engineering, fresh build+test run is green (verified myself, not taken on
faith), and scope is clean — no unrelated refactors from this pipeline pass.

---

## 1. P0 traceability (each verified against source, not the Tester's summary)

| # | Requirement | Code location | Verdict |
|---|---|---|---|
| 1 | `connectingArt` binder emitted iff ≥1 assigned slot, else omitted | `PublishRepository.kt` — `caSections` built via `mapNotNull { … if (groupSlots.none { it.cardId != null }) return@mapNotNull null }`; `if (caSections.isNotEmpty()) binders += …` | PASS |
| 2 | `personalCollection` binder emitted iff ≥1 cache row anywhere, else omitted | Same file — `pcSections` via `mapNotNull { if (rows.isEmpty()) return@mapNotNull null }`; `if (pcSections.isNotEmpty()) binders += …` | PASS |
| 3 | PC publishes ALL cached cards (owned+unowned) with accurate `owned` | `pcOwnedIds = personalEntries.filter{it.owned}.map{it.cardId}.toSet()`; every cache row mapped to a slot with `owned = row.cardId in pcOwnedIds` — no filtering on cache rows themselves | PASS |
| 4 | `SnapshotSlot.owned` default `true`, no regression on Pokédex/Card History/Unown | `BinderSnapshot.kt:37` — `val owned: Boolean = true`; grep-confirmed none of the Pokédex/CardHistory/Unown `SnapshotSlot(...)` call sites in `PublishRepository.kt` were touched (existing branches unchanged, verified by reading `git diff` — only additive hunks) | PASS |
| 5 | Restore overlays CA + PC correctly; old snapshots (no such binder) leave local data untouched | `RestoreRepository.kt` — both overlay blocks guarded by `if (caSnapshotSlots.isNotEmpty())` / `if (pcSnapshotSlots.isNotEmpty())`, derived from `snapshot.binders.firstOrNull{it.id==BINDER_ID_...}` which is null-safe via `?.sections.orEmpty()` | PASS |
| 6 | No new Settings UI / `PublishConfig` field / auto-publish / debounce | `PublishConfig` (`PublishSettingsRepository.kt:22-30`) has only `publishPokedex/publishCardHistory/publishUnown` — no CA/PC fields. Grepped `publishConnectingArt`/`publishPersonalCollection` — zero hits anywhere in `app/src/main`. No `WorkManager`/debounce code introduced (all publish reads for CA/PC are synchronous, inline in `publish()`) | PASS |
| 7 | Unown removed from drawer; reachable via PC screen; Unown's own screen/data/search flow unchanged | Verified `AppNavigation.kt` diff: only the PC composable registration changed (`onOpenUnown` param added) — no `NavigationDrawerItem` block for Unown present in the diff or current file (grep zero matches). `UnownBinderScreen`/`UnownBinderViewModel`/DAO/repo/TCGCSV files are untouched by this pipeline (see §3 below — those are pre-existing uncommitted work from an earlier session, not touched further here) | PASS |
| 8 | Full unit suite green, incl. new content-gating/owned-round-trip/nav tests | Independently re-ran `--rerun-tasks` myself (see §4) — BUILD SUCCESSFUL, 34+19+5 tests, 0 failures, fresh (non-cached) JUnit XML timestamps | PASS |

**8/8 P0 confirmed via source, not self-report.**

## 2. Resolved-ambiguity soundness check

- **R5 (computeDiff owned-flip scoping)** — read the full `when` block in `computeDiff` (`PublishRepository.kt:465-499`). The new branch requires `baselineCardId != null && nextCardId != null && baselineCardId == nextCardId && owned differs`, and sits *after* the existing `cardId changed → REPLACED` branch, so it only fires on the genuinely-new case (same card, owned flip). For Pokédex/Card History/Unown, both baseline and next always publish `owned = true` (untouched call sites), so `owned != owned` is always `false` there — the branch is a structural no-op for every binder except PC/CA. Confirmed via test `computeDiff owned unchanged same card is no change` (guards against over-firing) and the fact that no pre-existing Pokédex/CardHistory/Unown diff test's counts changed. **Sound, correctly scoped.**
- **R3 (CA publishes real `owned`, not `true`)** — verified `ConnectingArtSlot.kt`: `owned: Boolean = false` is a real column, and `ConnectingArtDao.kt` has `assignCard(...)` which explicitly sets `owned = 0` on assignment, with a *separate* `setOwned(slotId, owned)` query. This is a genuine assigned-but-not-owned state, distinct from Pokédex/Card History/Unown (which conflate assignment with ownership). The spec explicitly named only those three in its "assignment-equals-ownership" list and told the Planner to verify CA's actual shape before assuming — the Planner did that verification and found CA doesn't fit the list. Publishing the real flag is the accurate reading, not a convenient shortcut. **Defensible.**

## 3. Scope creep check

`git status` shows many more modified/untracked files than `.pipeline/changes.md` lists (e.g. `PokedexDatabase.kt`, `di/NetworkModule.kt`, `CardSearchRepository.kt`, `ui/manualsearch/*`, `ui/settings/*`, plus untracked `ui/unown/`, `TcgcsvApi.kt`, etc.). Investigated:

- All of these are the **Unown + TCGCSV feature** from the prior `2026-07-10-unown-binder-and-tcgcsv-fallback` spec, evidenced by: `PokedexDatabase.kt`'s only diff is the `MIGRATION_7_8` block creating `unown_binder` (nothing CA/PC-related); `.pipeline-archive/2026-07-11-unown-tcgcsv/` exists as an archived prior pipeline run; file mtimes show all Unown/TCGCSV files modified *before* the CA/PC/nav files (`BinderSnapshot.kt` through `AppNavigationScreenTest.kt`), which are the most-recently-touched files on disk.
- This confirms these files were **uncommitted leftovers from an earlier session**, not touched further by this pipeline's Coder stage. `.pipeline/changes.md`'s file list is accurate for *this* pipeline's actual diff.
- The `PersonalCollectionScreen.kt` diff includes collapsible-section/jump-chip code beyond just the Unown entry — this matches the spec's own annotation "(built this session)" for that feature, i.e. pre-existing uncommitted UI work this pipeline built on top of, not new scope. The Unown-chip addition itself is isolated (outside `PERSONAL_COLLECTION_SECTIONS.forEach`, no participation in `sectionItemIndex`/`collapsedSections`) exactly as claimed.

**No scope creep from this pipeline pass.** (Note for Skyler: the repo has substantial uncommitted work from the Unown/TCGCSV feature sitting in the tree — worth a separate commit/review pass, but that's a pre-existing-state observation, not a defect in this task.)

## 4. Independent build/test verification

```
JAVA_HOME=D:\jdk17\jdk-17.0.14+7 TEMP=TMP=C:\Windows\Temp
./gradlew.bat compileDebugKotlin --console=plain           → BUILD SUCCESSFUL (16 up-to-date)
./gradlew.bat testDebugUnitTest --console=plain --rerun-tasks → BUILD SUCCESSFUL, 31 actionable tasks: 31 executed
```

Cross-checked JUnit XML directly (not the console summary):
- `TEST-...PublishRepositoryTest.xml`: tests="34" failures="0" errors="0"
- `TEST-...RestoreRepositoryTest.xml`: tests="19" failures="0" errors="0"
- `TEST-...AppNavigationScreenTest.xml`: tests="5" failures="0" errors="0"

Timestamps on the XML match this run (not a stale cached report). Also read several test bodies
directly (`buildSnapshot omits connectingArt binder when no slots assigned`, `computeDiff owned
flip on same card is REPLACED`, `computeDiff owned unchanged same card is no change`, the nav
route-contract test) and confirmed the assertions genuinely exercise the claimed behavior — none
mock away the thing being tested.

## 5. Planner-flagged review items

1. **First-PC-publish Discord embed size** — `DiscordEmbedBuilder.kt` already caps at `MAX_EMBED_LINES = 15` with an "…and N more" overflow line (line 22, 33-36), for the non-first-publish path (`ordered.take(MAX_EMBED_LINES)`). The scenario in question (PC content appearing on a publish where `baseline != null`, i.e. not the app's literal first-ever publish) goes through this capped path — confirmed no truncation bug. The one case that skips the cap (`isFirstPublish == true`) only ever prints an aggregate count ("Initial publish — N cards"), never a per-item list, so it can't overflow either. **Not a bug — pre-existing cap already handles it.**
2. **Unown chip transient unreachability during PC cold-load** — confirmed in source: the chip `Row` is inside the final `else` of `if (state.isRefreshing && !hasAnyCards) { spinner } else if (error && !hasAnyCards) { error } else { … chip row … }`. Once any cached data exists (`hasAnyCards == true`) or the refresh completes, the chip renders. This is genuinely transient/self-resolving, not a dead end — matches the spec's own "accept as documented, don't restructure" call. **Non-blocking, acceptable as-is.**

## 6. Non-goals audit

- No `SettingToggleItem` / Settings UI rows added for CA or PC — confirmed via `SettingsScreen.kt`/`SettingsViewModel.kt` diffs containing zero CA/PC references (their diffs are entirely the pre-existing Unown feature).
- No Room schema/migration changes for this feature — `PokedexDatabase.kt`'s only migration (`MIGRATION_7_8`) creates `unown_binder`, unrelated to CA/PC (whose tables already existed from v7, confirmed by the Planner's ground-truth read and unchanged by this diff).
- Unown's data model/screen/TCGCSV flow untouched — only its nav entry point moved (drawer item removed, PC screen chip added); `UnownBinderScreen`, `UnownBinderViewModel`, `UnownBinderRepository`/DAO/Entity, `TcgcsvApi`/`TcgcsvDto` show zero diff attributable to this pipeline (all pre-existing from the earlier session, per §3).

## Summary

Ship. Every P0 traces to real, correctly-scoped code; the two resolved ambiguities the task asked
me to scrutinize (owned-flip diff scoping, CA's real-owned-flag publish) are both sound, not scope
overreach; the two Planner-flagged gaps are confirmed non-issues (embed cap already exists;
chip unreachability is genuinely transient); build and full test suite are green on a fresh run I
executed myself; and the large `git status` footprint outside the claimed file list is confirmed
pre-existing uncommitted work from a different (Unown/TCGCSV) feature, not scope creep from this
pipeline.

No changes required before Skyler's on-device verification pass.
