# Stage 2 (Coder) — Changes

Implements `.pipeline/specs.md` in full: Connecting Art + Personal Collection publish/restore, and the Unown nav move. No scope creep — no `di/` module edits, no new `PublishConfig` field, no Settings UI, no WorkManager/debounce/auto-publish code (grep-confirmed empty).

## Files modified

### Model
- `app/src/main/java/com/skyler/pokedexbinder/publish/model/BinderSnapshot.kt` — added `owned: Boolean = true` as the trailing field of `SnapshotSlot` (T1). Additive/backward-compatible: Moshi codegen defaults absent JSON fields, so old `binder.json` deserializes with `owned = true`.

### DAO / repository read+write methods (T2, T3)
- `app/src/main/java/com/skyler/pokedexbinder/data/local/ConnectingArtDao.kt` — added `getAllSlots(): List<ConnectingArtSlot>` (suspend query) and `updateSlots(slots: List<ConnectingArtSlot>)` (`@Update` on a list).
- `app/src/main/java/com/skyler/pokedexbinder/repository/ConnectingArtRepository.kt` — added `getAllGroups()`, `getAllSlots()`, `updateSlots()` thin pass-throughs.
- `app/src/main/java/com/skyler/pokedexbinder/data/local/PersonalCollectionDao.kt` — added `getAllCache(): List<PersonalCollectionCache>` and `getAllEntries(): List<PersonalCollectionEntry>` (suspend queries).
- `app/src/main/java/com/skyler/pokedexbinder/repository/PersonalCollectionRepository.kt` — added `getAllCache()`, `getAllEntries()` thin pass-throughs. Restore reuses existing `setOwned`/`removeOwned` — no new write method needed (per spec §6b).

### Constants (T4)
- `app/src/main/java/com/skyler/pokedexbinder/publish/SnapshotSections.kt` — added `PERSONAL_COLLECTION_SECTION_ORDER: List<Pair<String,String>>` (5 fixed sections, mirrors `PersonalCollectionViewModel.PERSONAL_COLLECTION_SECTIONS`).
- `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt` — added `BINDER_ID_CONNECTING_ART`/`BINDER_NAME_CONNECTING_ART`/`BINDER_ID_PERSONAL_COLLECTION`/`BINDER_NAME_PERSONAL_COLLECTION` constants.
- `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt` — added `BINDER_ID_CONNECTING_ART`/`BINDER_ID_PERSONAL_COLLECTION` constants.

### Publish (T5, T6)
- `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt`
  - Constructor: added `connectingArtRepository: ConnectingArtRepository` and `personalCollectionRepository: PersonalCollectionRepository` (trailing, Hilt auto-wires — no `di/` edit needed; `assembleDebug` confirms the graph resolves).
  - `publish()`: reads CA groups/slots and PC cache/entries unconditionally (content-gated, not `PublishConfig`-gated per spec non-goal) and passes them into `buildSnapshot`.
  - `buildSnapshot()`: 4 new trailing defaulted params (`connectingArtGroups`, `connectingArtSlots`, `personalCache`, `personalEntries`) — all existing 4-positional call sites still compile.
    - CA branch: includes a group's section only if ≥1 slot has a `cardId`; emits ALL of an included group's slots (empty ones too) ordered by `slotIndex`; `slotId = "ca-${group.id}-${slot.slotIndex}"`; `owned = slot.owned` (real per-slot value, per R3).
    - PC branch: one section per fixed Pokémon key with ≥1 cache row; publishes ALL cached cards (owned + unowned); `slotId = cardId`; `owned = cardId in ownedIds`.
  - `computeDiff()`: added the owned-flip branch — same `slotId`, same non-null `cardId`, differing `owned` → one `REPLACED` delta (R5). This is what keeps a pure ownership toggle from silently no-op'ing the whole publish (`hasChanges` would otherwise be `false`).

### Restore (T7)
- `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt`
  - Constructor: added the same two new repository deps.
  - CA overlay: matches local slots to snapshot slots via the same `"ca-<groupId>-<slotIndex>"` key; restores `cardId/cardName/cardImageUrl/owned` on match, clears (incl. `owned=false`) when locally assigned but absent from snapshot; counts `restored`/`cleared`; unmatched snapshot keys count as `skipped`; guarded by `caSnapshotSlots.isNotEmpty()` so an old snapshot without a `connectingArt` binder is a full no-op.
  - PC overlay: restores ownership onto `personal_collection_entry` by `cardId` only (`setOwned`/`removeOwned`), never touches `personal_collection_cache` (R6). Contributes to `restored`/`cleared` but never `skipped` (R8, no local-row precondition). Guarded by `pcSnapshotSlots.isNotEmpty()`.

### Navigation (T8, T9)
- `app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt` — removed the standalone "Unown" `NavigationDrawerItem`; `Screen.Unown`/`Screen.UnownSearch` routes, `UnownBinderScreen`, and its ViewModel/repo/DAO/entity are all untouched. `PersonalCollectionScreen` composable registration now passes `onOpenUnown = { navigateTo(Screen.Unown.route) }`.
- `app/src/main/java/com/skyler/pokedexbinder/ui/personalcollection/PersonalCollectionScreen.kt` — added `onOpenUnown: () -> Unit` param; added a 6th chip in the section-jump `Row` after the 5 `PERSONAL_COLLECTION_SECTIONS` chips — labeled "Unown", trailing `Icons.AutoMirrored.Filled.ArrowForward` (opens-a-new-screen affordance), `onClick = onOpenUnown`. Does not participate in `sectionItemIndex`/`collapsedSections`/the grid loop (standalone nav trigger only, per spec §8c.3).

### Tests (T10, T11, T12)
- `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt` — added `connectingArtRepository`/`personalCollectionRepository` relaxed mocks to the constructor call; added `caGroup`/`caSlot`/`pcCache`/`pcEntry` builders; extended the `slot(...)` helper with an `owned` param (default `true`); added 11 new tests covering CA binder inclusion/omission/skip-empty-group/slotId encoding, PC binder inclusion/omission/skip-empty-section/section-title mapping, owned-flip `REPLACED` + no-op-when-unchanged, and pokedexComplete non-inflation.
- `app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt` — added the same two mocks + constructor wiring + default `emptyList()` stubs; added `localCaSlot`/`caSnapshot`/`pcSnapshot` builders and extended `snapshotSlot(...)` with an `owned` param; added 8 new tests covering CA restore/clear-by-position, CA skip-count, CA old-snapshot no-op guard, PC restore/clear-by-cardId, PC restore-with-no-local-entry (R6), PC cache-never-written assertion, PC old-snapshot no-op guard, and a publish→restore owned round-trip test (builds a real `PublishRepository` instance solely to exercise the pure `buildSnapshot` function, then feeds the result into `repository.restore`).
- `app/src/test/java/com/skyler/pokedexbinder/ui/navigation/AppNavigationScreenTest.kt` — added the one cheap JVM route-contract guard test (`Screen.Unown.route == "unown"`, `Screen.PersonalCollection.route == "personal_collection"`) per spec §9c; no Compose-UI harness exists to test the drawer/chip UI directly (on-device verification only, per spec).

## Simplification pass (Phase 5)

- Reused the existing "content-gated `SnapshotBinder` appended only if sections non-empty" pattern already used for Pokédex/Card History/Unown — no new abstraction introduced for CA/PC.
- Reused the existing Unown restore-overlay's guard-then-overlay-then-count shape verbatim for both new overlays (same `restored`/`cleared`/`skipped` accounting, same `isNotEmpty()` short-circuit for backward compatibility with old snapshots) — no duplicated helper extracted since each overlay's key scheme and target repository differ enough that a shared helper would need as many parameters as the inline code has lines.
- `PERSONAL_COLLECTION_SECTION_ORDER` placed in `SnapshotSections.kt` (not duplicated inline in `PublishRepository.kt`) — this is the established "publish layer keeps its own copy of section metadata" convention already used for `SECTION_ORDER`/`GENERATIONS`.
- No correctness issues found beyond what specs.md's R1–R8 already resolved; implementation matches the spec's exact resolutions (slot-id schemes, owned semantics, diff branch, restore guards) with no deviation.
- Considered extracting a shared "restore one N->1 keyed overlay" generic helper across Unown/CA/PC, but the three targets have different local-entity shapes (`UnownBinderEntry` vs `ConnectingArtSlot` vs `PersonalCollectionEntry`) and different write APIs (`overwriteAll` vs `updateSlots` vs `setOwned`/`removeOwned` per-row) — genericizing would trade ~15 readable lines per block for a parameterized function harder to follow than the direct code. Left as-is (matches spec's own choice not to genericize).

## Verification

```
./gradlew.bat compileDebugKotlin --console=plain   → BUILD SUCCESSFUL
./gradlew.bat testDebugUnitTest --console=plain    → BUILD SUCCESSFUL
./gradlew.bat assembleDebug --console=plain         → BUILD SUCCESSFUL (confirms Hilt graph resolves new ctor deps, no di/ edits needed)
```

Test result counts (from `app/build/test-results/testDebugUnitTest/`):
- `PublishRepositoryTest`: 34 tests, 0 failures, 0 errors
- `RestoreRepositoryTest`: 19 tests, 0 failures, 0 errors
- `AppNavigationScreenTest`: 5 tests, 0 failures, 0 errors

Full `testDebugUnitTest` suite (all modules, not just the 3 touched files): BUILD SUCCESSFUL, no failures reported.

grep-confirmed no scope creep: no `publishConnectingArt`/`publishPersonalCollection` `PublishConfig` fields added; no `WorkManager`/`debounce` code in `publish/` or `ui/settings/`.

## Known pre-existing/documented gaps (per spec §10, not introduced by this change, not fixed — out of scope)
- First PC publish lists every cached card as `ADDED`; Discord embed size for a large `changes` list was flagged in the spec for Tester/Reviewer to confirm against the existing `DiscordEmbedBuilderTest` — not verified in this Coder pass (out of scope for Stage 2).
- The PC screen's 6th Unown chip is inside the `else` branch of the refresh/error/content switch — transient unreachability during the very-first cold-load spinner or a zero-card hard error, per spec §8c note. Documented, not restructured (spec explicitly says not to fix this to keep scope tight).
