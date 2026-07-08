# Run 1B — Reviewer Verdict

**Verdict: SHIP.**

Independently verified (read every changed/new file, cross-checked repo + DAO signatures, traced the
tricky nav wiring myself rather than trusting the Tester's trace). Every Run 1B requirement in
`.pipeline/specs.md` traces to real code. Both explicit non-goal traps are clean. No scope creep. Build
green, 17 new ViewModel tests pass, zero new regressions.

---

## Requirement traceability (spec → code)

| Requirement | Traced to | Status |
|---|---|---|
| Grid preset selection (1×2/1×3/1×4/2×2/3×3) | `ConnectingArtScreen.kt:28` `GRID_PRESETS`, `:249-262` `FilterChip` per preset | OK |
| Card assignment via search sub-route | `ConnectingArtScreen.kt:85-88` `beginAssign` + `onOpenSlotSearch`; `AppNavigation.kt:182-200` | OK |
| Owned/unowned toggling (Connecting Art) | `ConnectingArtScreen.kt:277-285` sheet; `ConnectingArtViewModel.kt:72-74` `setOwned` | OK |
| Owned/unowned toggling (Personal Collection) | `PersonalCollectionScreen.kt:123-131`; `PersonalCollectionViewModel.kt:82-86` `toggleOwned` | OK |
| Drag-reorder wiring (sh.calvin.reorderable, list variant) | `ConnectingArtScreen.kt:26,44-46,80` + `:136,146` `longPressDraggableHandle()` on header | OK |
| Group deletion via cascade, no manual cleanup | `ConnectingArtViewModel.kt:60-63` → `repository.deleteGroup` only | OK |
| 5 fixed Pokémon sections, spec order, Minccino+Cinccino merged | `PersonalCollectionViewModel.kt:18-24` | OK |
| Pull-to-refresh wiring (material3 `PullToRefreshBox`, no accompanist) | `PersonalCollectionScreen.kt:12,43-45` | OK |
| Owned-state preservation on refresh | `PersonalCollectionRepository.kt:37-55` (cache-only replace) + two-table split | OK |
| releaseDate thread-through | `TcgCardDto` → `CardSearchRepository.kt:104` → `TcgCard.kt:12` → `PersonalCollectionRepository.kt:49` → `PersonalCollectionCache.kt:13` → DAO `ORDER BY releaseDate DESC` | OK |
| Shared DimmableCardImage | `ui/common/DimmableCardImage.kt` (desaturate + 0.45 scrim), used by both screens | OK |

Every repository/DAO method the ViewModels and repo call was confirmed to exist with matching
signatures (`ConnectingArtRepository.kt`, `PersonalCollectionDao.kt`, `CardSearchRepository.searchByName`).
No phantom calls.

---

## The two non-goal traps — independently confirmed clean

1. **No manual owned-state merge on refresh.** `refreshPokemon` (`PersonalCollectionRepository.kt:37-55`)
   only builds `PersonalCollectionCache` rows and calls `dao.replaceCacheForPokemon`. That DAO method
   (`PersonalCollectionDao.kt:29-33`) deletes + upserts `personal_collection_cache` **only** —
   `personal_collection_entry` is never touched. The ViewModel's `refreshAll` never calls
   `setOwned`/`removeOwned` (`PersonalCollectionViewModel.kt:66-80`). Owned state survives a refresh for
   free. No merge logic anywhere. ✔

2. **No manual slot cleanup on group delete.** `ConnectingArtViewModel.deleteGroup` (`:60-63`) calls
   `repository.deleteGroup(groupId)` and nothing else. Repo → `dao.deleteGroupById` (plain parent
   delete). Cascade FK does the rest. ✔ (Tester independently confirmed the FK is live at runtime —
   entity annotation + migration SQL agree, Room enables `PRAGMA foreign_keys=ON` by default, no
   `setForeignKeyConstraintsEnabled(false)` override in `DatabaseModule`.)

---

## Shared ViewModel scoping (the trickiest wiring) — verified myself

`AppNavigation.kt:182-200`: the `ConnectingArtSearch` composable obtains
`hiltViewModel(navController.getBackStackEntry(Screen.ConnectingArt.route))` — scoped to the **list
screen's own back-stack entry**, wrapped in `remember(backStack)`. Both the list route (`:174`) and the
search route resolve the ViewModel against the *same* `Screen.ConnectingArt.route` `NavBackStackEntry`
object, so Hilt returns the same instance. `beginAssign(slotId)` (list screen) sets
`_pendingAssignSlotId` on that instance; `assignPendingCard(card)` (search screen) reads it off the same
instance and writes to the correct slot (`ConnectingArtViewModel.kt:48-54`). `ManualSearchScreen`'s own
`viewModel` is left defaulted (fresh per entry) — correct, only the assign target needs sharing. This is
sound. The nav-arg `slotId` is declared (`:184`) but unused by the composable body — intentional
belt-and-suspenders per spec §9, not a dropped requirement.

Cross-checked `slots.getOrNull(slotIndex)` in `GroupSection` (`ConnectingArtScreen.kt:171-172`) against
`observeAllSlots` (`ConnectingArtDao.kt:49` `ORDER BY groupId ASC, slotIndex ASC`) — slots arrive
sorted by `slotIndex`, so row-major lookup `r * cols + c` is correct.

---

## Scope creep check — none

`git status` Run-1B-touched files match the plan's file list exactly (TcgCard, TcgCardDto,
CardSearchRepository, AppNavigation + new ui/connectingart, ui/personalcollection,
ui/common/DimmableCardImage, + new tests). The untracked `data/local/*` and `*Repository.kt` files are
Run 1A infra staged on the same branch, not new here. No Pokédex/Card History changes. No Room
migration/version bump. No sorting/filtering UI. Clean.

Deviations from spec (both documented, both fine):
- `SECTIONS` → `PERSONAL_COLLECTION_SECTIONS`: cosmetic rename, identical content/order.
- `GroupSection` as a `ReorderableCollectionItemScope` extension function
  (`ConnectingArtScreen.kt:136`): correct — `longPressDraggableHandle()` is an extension on that scope
  and is only callable with the scope as receiver; compilation success confirms the receiver type
  matches the 2.4.0 API.

---

## The "could not verify visually" gap — not a blocker

The logic and wiring layer was verified thoroughly (build, 17 unit tests incl. the highest-risk
refresh-preserves-owned test, nav graph, releaseDate chain, cascade at DB level). What's unverified is
purely rendered-pixel/gesture behavior: layout/aspect-ratio, actual drag gesture → index callback,
actual pull-to-refresh gesture gating, `DimmableCardImage`'s rendered desaturation/scrim. None of these
can crash or corrupt data if wrong — worst case is a visual nit Skyler eyeballs on first install and
reports back. This is the same precedent as Run 1A (shipped with the migration's live-execution
unverified on Skyler's single dev device). Holding back working, tested logic for want of an emulator
that doesn't exist in this environment would be net-negative. Ship it; Skyler verifies visually on
device.

---

## Must-fix before ship

None.

## Nice-to-have follow-up (non-blocking)

1. **Visual pass on device** — confirm grid alignment, `aspectRatio(0.72f)` cells, drag-reorder feel,
   pull-to-refresh gesture, and the desaturate+scrim "unowned" look on a real screen. If drag feels
   laggy, spec §3 already sanctions adding a local optimistic-override StateFlow.
2. **Empty-section header flicker during refresh** (`PersonalCollectionScreen.kt:70`): while
   `isRefreshing` is true, a section with zero cached cards still emits its full-span header (the
   `!state.isRefreshing` guard lets empty sections through during refresh). On first-ever load this
   briefly shows 5 bare headers before the `CircularProgressIndicator` branch is chosen — actually the
   `isRefreshing && !hasAnyCards` branch (`:48`) wins on first load so the grid isn't shown then, so
   this only surfaces on a *manual* refresh of a section that returns no cards. Cosmetic only. Optional
   tighten: skip the header when its own `cards.isEmpty()` regardless of global refresh state.
3. **Reorder during active drag with a slow DB write**: `reorderGroups` (`ConnectingArtViewModel.kt:65-70`)
   has no optimistic local state; the combined flow re-emits after `updateGroupPosition` persists. Fine
   per spec ("start without it"); revisit only if #1 shows lag.

All three are polish, observable only on a running device, and appropriate to defer to Skyler's
manual pass.
