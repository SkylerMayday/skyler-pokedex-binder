# Spec — Unown as Personal Collection Sections (2026-07-12)

**Status:** APPROVED — ready for dev-team-pipeline
**Owner:** Skyler
**Scope:** Android app — remove standalone Unown feature, extend Personal Collection

## Problem Statement

Unown was built as a standalone binder (Room v8 table, single-card-per-slot assignment, its
own screen) per an earlier decision this session. Skyler has since reconsidered: he wants Unown
to behave exactly like Personal Collection's existing sections (Charizard, Celebi, etc.) —
auto-search populates every matching card, owned/unowned toggle per result — just with 28 fixed
sections (one per letter/symbol) instead of open-ended assignment. This also removes the
"manually search and assign one specific card per letter" flow entirely.

## Goals

1. Unown appears as 28 additional sections on the Personal Collection screen (A-Z, !, ?),
   using the exact same auto-search + owned/unowned toggle mechanic as Charizard/Celebi/
   Leafeon/Tangela/Minccino & Cinccino.
2. No separate Unown screen, no separate nav entry, no separate Room table — it's just more
   entries in Personal Collection's existing section list, backed by its existing
   `PersonalCollectionCache`/`PersonalCollectionEntry` tables.
3. The already-shipped (but never device-verified) standalone Unown feature is fully removed —
   Room v8 migration, `UnownBinderEntry`/`Dao`/`Repository`/`ViewModel`/`Screen`, its publish/
   restore wiring, and its nav routes.

## Non-Goals

- **Preserving any Unown data captured under the old model.** Nothing has been verified
  on-device yet (the standalone Unown build was never confirmed working), so there's no real
  user data to migrate — the Room v8 table is dropped, not migrated-and-preserved.
- **Publish/restore support for the new Unown sections** — matches Personal Collection's
  current (pre-this-session) status quo for its 5 original sections... actually, correction:
  Personal Collection publish support was built earlier this session (2026-07-12 CA/PC publish
  spec). The 28 new Unown sections get that same publish/restore treatment for free — they're
  just more entries in the same section list `buildSnapshot`/`RestoreRepository` already
  iterate over. No new non-goal here; this is inherited, not a gap.
- **Manual card entry** — still explicitly rejected, unchanged.
- **Changing the TCGCSV/concurrent-search mechanics** — Unown's sections use the exact same
  `PersonalCollectionRepository.refreshPokemon` → `CardSearchRepository` path every other
  section uses; no special-casing.

## Design

### Section list extension

`PersonalCollectionViewModel.kt`'s `PERSONAL_COLLECTION_SECTIONS` gains 28 new entries, reusing
`UnownBinderRepository`'s existing constants before that class is deleted:

```kotlin
val PERSONAL_COLLECTION_SECTIONS = listOf(
    // ...existing 5...
) + ('A'..'Z').map { it.toString() }.plus(listOf("!", "?")).map { letter ->
    PokemonSection(key = "unown_$letter", title = "Unown $letter", queryNames = listOf("Unown $letter"))
}
```

Planner to finalize exact syntax/placement — `key` must be unique and stable (`unown_a`...
`unown_z`, `unown_!`, `unown_?` or similar; confirm no key collisions with existing section keys
or with cache/entry primary-key assumptions elsewhere). `!`/`?` in a `pokemonKey`/cache lookup
must not break Room queries or the existing section-key equality checks — same character-safety
question already resolved once this session for the standalone Unown table's letter IDs, verify
it holds for this reuse too.

### Removed entirely

- `data/local/UnownBinderEntry.kt`, `UnownBinderDao.kt`
- `repository/UnownBinderRepository.kt`
- `ui/unown/` (ViewModel, Screen)
- `MIGRATION_7_8` and the `UnownBinderEntry::class` entities-list entry in `PokedexDatabase.kt`
  — **replace with a `MIGRATION_7_8` that does nothing structurally new** (Room requires a
  migration object to exist for the version bump path already shipped, but since nothing has
  installed v8 in practice per the non-goals, Planner should determine: is it simpler/safer to
  revert the database version back to 7 entirely (undoing the bump, since nothing depends on
  v8 existing), or keep version 8 with an empty/no-op migration? Recommend reverting to v7 if
  no other change in this session's other work depends on v8 — verify against the Connecting
  Art/Personal Collection publish work (2026-07-12) which should NOT have needed a schema bump
  itself (confirm it didn't add any new Room entities of its own).
- `app/src/androidTest/.../Migration7to8Test.kt`
- `BINDER_ID_UNOWN`/`BINDER_NAME_UNOWN` constants and the `buildSnapshot`/`RestoreRepository`
  Unown-specific branches added earlier this session (superseded by Unown just being part of
  Personal Collection's existing section-based publish path)
- `Screen.Unown`/`Screen.UnownSearch` routes and the `PersonalCollectionScreen`'s "navigate
  away" 6th chip/list-item (both the top jump-chip and the bottom scroll-list entry added
  earlier this session) — Unown's 28 new sections behave exactly like the other 5 now (chip +
  collapsible section header + jump-to), no special "opens a new screen" treatment needed.
- `onOpenUnown` parameter threading through `AppNavigation.kt` → `PersonalCollectionScreen`

### Test files

- Delete: `Migration7to8Test.kt`, any Unown-specific unit tests (`UnownBinderRepository` tests
  if any exist, Unown-specific `PublishRepositoryTest`/`RestoreRepositoryTest` cases added this
  session, the `AppNavigationScreenTest.kt` Unown-route test).
- Extend: `PersonalCollectionViewModelTest.kt` (28 more sections behave like the existing 5 —
  spot-check a couple, not all 28 individually), `PersonalCollectionScreenTest`-equivalent if
  one exists for the chip/jump UI (33 total sections now scroll/jump correctly).

## Requirements

### P0 — Must have
- [ ] All 28 Unown letters appear as sections on the Personal Collection screen, auto-searching
      and showing owned/unowned toggle exactly like the existing 5 sections.
- [ ] Standalone Unown feature fully removed: no dead code, no orphaned nav routes, no unused
      Room entities/DAOs, no leftover publish/restore branches referencing the old binder id.
- [ ] Room migration path is clean — Planner to determine revert-to-v7 vs no-op-v8 and justify
      the choice; either way, a fresh install and an upgrade-from-v7 install must both work
      without crashing.
- [ ] Publish/restore continues working correctly for the original 5 Personal Collection
      sections (regression check — this change must not break the CA/PC publish work from
      earlier today).
- [ ] Full unit suite green.

### P1 — Nice to have
- None.

## Success Criteria

Personal project — no metrics theater. Done means: Skyler opens Personal Collection, sees 33
sections total (5 original + 28 Unown letters), can toggle owned/unowned on an Unown card the
same way as any other section, publishes, and it's included in binder.json alongside the rest —
verified on-device.

## Timeline / Phasing

Single dev-team-pipeline pass: remove standalone Unown (schema/DAO/repo/screen/nav/publish/
restore) → extend `PERSONAL_COLLECTION_SECTIONS` → full unit tests → on-device verification.
