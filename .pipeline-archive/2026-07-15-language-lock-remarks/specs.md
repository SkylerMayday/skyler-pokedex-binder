# Technical Spec — Card Language, Lock, and Remarks (Room v8 → v9)

Source PRD: `docs/specs/2026-07-15-card-language-lock-remarks.md` (approved, ground-truth for
scope/acceptance criteria). This document is the technical/task-decomposition layer for the Coder
stage — exact file paths, signatures, edge cases, dependency-ordered tasks. All file paths and
signatures below were verified directly against the current codebase on 2026-07-15 (not copied
blind from the PRD).

## Ground-truth deltas vs the PRD (read before coding)

The PRD's Data Model / UI sections describe the *intent* correctly, but the actual codebase has
two structural facts the PRD doesn't spell out — both change how the work must be sequenced:

1. **Pokédex (`main_binder`) reads through a domain model, not the entity directly.**
   `PokemonSlot` (`data/model/PokemonSlot.kt`) has no `language`/`remarks`/`isLocked` fields today.
   `MainBinderScreen`'s `SlotCard`, `SlotDetailScreen`, and `BinderDisplayItem` all consume
   `PokemonSlot`, never `MainBinderEntry` directly. So the lock icon and Edit Details dialog on
   Pokédex need `PokemonSlot` extended and `BinderRepository.toDomain()` updated — **this is
   extra work the PRD doesn't mention.** Unown has no such indirection (`UnownBinderEntry` is
   consumed directly by its screen/dialogs), so Unown is simpler.

2. **The lock guard must live in the repository layer to cover every assignment entry point, not
   just the SlotActionSheet/SlotDetailScreen "Reassign" button.** `BinderRepository.assignCard()`
   is also called by `AssignCardUseCase.assign()`, which is shared by `AssignmentViewModel`,
   `QuickScanViewModel`, and `ScannerViewModel` (scan-to-assign flows). If the guard only lived in
   the UI layer, scanning a card into an already-locked Pokédex slot would silently overwrite it.
   Putting the no-op in `BinderRepository.assignCard()`/`clearCard()` protects all paths at once.
   Same reasoning applies to `UnownBinderRepository` (only reached via `UnownBinderViewModel`
   today, but guard at the repo layer regardless, for symmetry and future-proofing).

3. **No Snackbar/Toast pattern exists anywhere in this codebase** (verified via grep — zero
   hits). The PRD's "toast/snackbar" wording for the blocked-removal message doesn't match any
   existing convention. Use `AlertDialog`, the pattern `SlotDetailScreen` already uses for its
   "Remove card?" confirmation — consistent, zero new dependency.

4. **DAOs have no existing update path for arbitrary field subsets** — every DAO here uses either
   `@Upsert`/full-entity `@Update`, or hand-written targeted `@Query` UPDATEs for specific
   migrations. New targeted `@Query` UPDATE methods are added per DAO (see Task list) rather than
   relying on read-modify-write via `@Upsert`, to match the existing DAO idiom and avoid
   clobbering fields the caller didn't intend to touch (relevant for `ConnectingArtSlot`, which
   has `cardId`/`cardName`/`cardImageUrl`/`owned` that must survive a language-only edit).

## Data Model

New file: `app/src/main/java/com/skyler/pokedexbinder/data/model/Language.kt`
```kotlin
package com.skyler.pokedexbinder.data.model

enum class Language(val displayName: String) {
    EN("English"), JA("Japanese"), KO("Korean"), ZH("Chinese"),
    FR("French"), DE("German"), IT("Italian"), ES("Spanish");

    companion object {
        /** Defensive parse — legacy/unrecognized/null values fall back to EN, never throw. */
        fun fromRaw(raw: String?): Language = raw?.let {
            runCatching { valueOf(it) }.getOrNull()
        } ?: EN
    }
}
```
Mirrors the `SlotType` string-backed convention already used by `MainBinderEntry.slotType`
(stored as raw String, mapped defensively in the repository layer — see
`BinderRepository.toDomain()`'s existing `when (slotType.lowercase())` pattern).

### Entity changes (all in `app/src/main/java/com/skyler/pokedexbinder/data/local/`)

| File | Entity | New columns (add to data class, after existing fields) |
|---|---|---|
| `MainBinderEntry.kt` | `main_binder` | `val language: String = "EN"`, `val remarks: String? = null`, `val isLocked: Boolean = false` |
| `UnownBinderEntry.kt` | `unown_binder` | same three |
| `ConnectingArtSlot.kt` | `connecting_art_slot` | `val language: String = "EN"` |
| `PersonalCollectionEntry.kt` | `personal_collection_entry` | `val language: String = "EN"` |
| `SecondaryBinderEntry.kt` | `secondary_binder` | `val language: String = "EN"` |

### Domain model change

`app/src/main/java/com/skyler/pokedexbinder/data/model/PokemonSlot.kt` — add:
```kotlin
val language: String = "EN",
val remarks: String? = null,
val isLocked: Boolean = false
```
to the `PokemonSlot` data class constructor (after `searchName`). `isOccupied`/`effectiveSearchName`
are unaffected (no behavior change to existing computed properties).

`BinderRepository.toDomain()` (private ext fn, `repository/BinderRepository.kt` line ~121) gets
three more mapped fields:
```kotlin
private fun MainBinderEntry.toDomain() = PokemonSlot(
    ...,
    language = language,
    remarks = remarks,
    isLocked = isLocked
)
```

## Migration Plan (Room v8 → v9)

**Hard constraint, non-negotiable**: per `project-overview.md` ("Gotchas" — 2026-07-15 incident),
a prior v8→v7 downgrade wiped Skyler's device DB via `fallbackToDestructiveMigrationOnDowngrade()`.
This must be a real forward `MIGRATION_8_9`. Do not touch `fallbackToDestructiveMigration()` /
`fallbackToDestructiveMigrationOnDowngrade()` config in `DatabaseModule.kt` — out of scope, and
touching it risks repeating the exact incident this constraint exists to prevent.

### `PokedexDatabase.kt` changes

1. Bump `@Database(version = 9)` (currently `version = 8`, line 18).
2. Add all 5 new entity classes' field lists to `entities = [...]` — **no change needed here**,
   the entity *list* doesn't change, only the entities' own field lists (Room reads schema from
   the annotated classes at compile time via KSP; no explicit column declarations needed in the
   `@Database` annotation itself).
3. Add `MIGRATION_8_9` to the companion object, immediately after `MIGRATION_7_8` (line 102),
   following the exact `execSQL`-per-statement style already used by `MIGRATION_6_7`/`MIGRATION_7_8`:
   ```kotlin
   val MIGRATION_8_9 = object : Migration(8, 9) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL("ALTER TABLE main_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
           db.execSQL("ALTER TABLE main_binder ADD COLUMN remarks TEXT")
           db.execSQL("ALTER TABLE main_binder ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")

           db.execSQL("ALTER TABLE unown_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
           db.execSQL("ALTER TABLE unown_binder ADD COLUMN remarks TEXT")
           db.execSQL("ALTER TABLE unown_binder ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")

           db.execSQL("ALTER TABLE connecting_art_slot ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
           db.execSQL("ALTER TABLE personal_collection_entry ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
           db.execSQL("ALTER TABLE secondary_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
       }
   }
   ```

### `DatabaseModule.kt` changes

Add `PokedexDatabase.MIGRATION_8_9` to the `.addMigrations(...)` chain (line 21-26), after
`MIGRATION_7_8`. Do not reorder the existing four. Do not touch lines 27-28
(`fallbackToDestructiveMigration()`/`OnDowngrade()`).

### Migration test — `Migration8to9Test.kt`

New file: `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration8to9Test.kt`,
following `Migration6to7Test.kt`'s exact structure (in-memory Room DB + separate bare-shape
`SupportSQLiteDatabase` verification, since `exportSchema = false` on this DB rules out
`MigrationTestHelper` + exported schema JSON). Two test methods:

1. `migration8to9SqlIsValidAgainstV8ShapeAndPreservesExistingData` — build 5 bare v8-shaped
   tables by hand (mirroring current entity columns exactly, no new columns), seed one row per
   table with representative "existing user data" (e.g. a `main_binder` row with
   `assignedCardId` set, a `connecting_art_slot` row with a card assigned and `owned = 1`), run
   `MIGRATION_8_9`'s exact SQL against them, then assert:
   - Every pre-existing row's original columns are untouched (spot-check 2-3 fields per table).
   - Every table now has the 1 or 3 new columns via `PRAGMA table_info`.
   - Every pre-existing row's new columns backfilled correctly: `language = 'EN'`,
     `remarks IS NULL` (main_binder/unown_binder only), `isLocked = 0` (main_binder/unown_binder
     only).
2. `migration8to9NewColumnsReachableThroughRealDaosAndRepositoryLayer` — exercise the actual
   post-migration in-memory schema through the real DAOs: insert a row via each of the 5 DAOs
   with explicit `language`/`remarks`/`isLocked` values, read it back, assert round-trip fidelity.

This directly closes the gap flagged in `gaps.md`'s "Data-integrity / migration risk" section
("`Migration7to8Test.kt` doesn't exist... every prior migration has a paired androidTest") — do
not introduce the same gap for 8→9.

Note (carried from `Migration6to7Test.kt`'s own header comment): androidTest requires a
device/emulator; if none is available in this environment, the test still must compile and be
correct, flagged as "not executed live" rather than skipped or omitted.

## DAO changes

Each DAO gets one new targeted `@Query` UPDATE method (matches the existing hand-written-SQL
idiom already used throughout these files — e.g. `ConnectingArtDao.setOwned`,
`MainBinderDao.backfillCardNameSet`). Full-entity `@Upsert`/`@Update` is deliberately avoided
here so an Edit Details save can't accidentally clobber concurrent card-assignment fields.

`MainBinderDao.kt` — add:
```kotlin
@Query("""
    UPDATE main_binder SET language = :language, remarks = :remarks, isLocked = :isLocked
    WHERE pokemonId = :pokemonId
""")
suspend fun updateDetails(pokemonId: String, language: String, remarks: String?, isLocked: Boolean)
```

`UnownBinderDao.kt` — add:
```kotlin
@Query("""
    UPDATE unown_binder SET language = :language, remarks = :remarks, isLocked = :isLocked
    WHERE letterId = :letterId
""")
suspend fun updateDetails(letterId: String, language: String, remarks: String?, isLocked: Boolean)
```

`ConnectingArtDao.kt` — add:
```kotlin
@Query("UPDATE connecting_art_slot SET language = :language WHERE id = :slotId")
suspend fun updateLanguage(slotId: Int, language: String)
```

`PersonalCollectionDao.kt` — add:
```kotlin
@Query("UPDATE personal_collection_entry SET language = :language WHERE cardId = :cardId")
suspend fun updateLanguage(cardId: String, language: String)
```
Edge case: a `PersonalCollectionEntry` row may not exist yet for a card that's only in the cache
and never toggled "owned" (entity is only created via `upsertEntry` on first owned-toggle — see
`PersonalCollectionDao.getEntry`/`upsertEntry`). The repository method (below) must
`upsertEntry`-create the row first if absent, matching `owned = false` default, then set language
— a plain `UPDATE` would silently no-op on a nonexistent row.

`SecondaryBinderDao.kt` — add:
```kotlin
@Query("UPDATE secondary_binder SET language = :language WHERE id = :id")
suspend fun updateLanguage(id: Int, language: String)
```

## Repository changes

### `BinderRepository.kt`

- `assignCard(...)` (line 39): before the existing `upsert`, check
  `if (existing.isLocked) return false` (change return type to `Boolean`, true = proceeded).
  Update the call site in `AssignCardUseCase.assign()` to propagate the boolean (see below).
- `clearCard(pokemonId)` (line 57): same guard, same return-type change.
- New method: `suspend fun updateDetails(pokemonId: String, language: Language, remarks: String?, isLocked: Boolean) = mainBinderDao.updateDetails(pokemonId, language.name, remarks, isLocked)`.
- `toDomain()` — see Domain model change above.

### `UnownBinderRepository.kt`

- `assignCard(...)` (line 25) and `clearCard(letterId)` (line 43): same `isLocked` guard +
  `Boolean` return type as `BinderRepository`.
- New method: `suspend fun updateDetails(letterId: String, language: Language, remarks: String?, isLocked: Boolean) = unownBinderDao.updateDetails(letterId, language.name, remarks, isLocked)`.

### `ConnectingArtRepository.kt`, `PersonalCollectionRepository.kt`, and the (currently
DAO-direct) Secondary Binder path

Add one new method each (exact file paths: `repository/ConnectingArtRepository.kt`,
`repository/PersonalCollectionRepository.kt` — confirm exact existing method style before
editing, not yet read in this pass but same DI/DAO-wrapper pattern as `BinderRepository`):
```kotlin
// ConnectingArtRepository
suspend fun updateLanguage(slotId: Int, language: Language) = dao.updateLanguage(slotId, language.name)

// PersonalCollectionRepository
suspend fun updateLanguage(cardId: String, language: Language) {
    if (dao.getEntry(cardId) == null) dao.upsertEntry(PersonalCollectionEntry(cardId = cardId, owned = false))
    dao.updateLanguage(cardId, language.name)
}
```
`SecondaryBinderViewModel` calls `secondaryBinderDao` directly today (no repository layer exists
for Card History) — add `secondaryBinderDao.updateLanguage(id, language.name)` call directly from
the ViewModel (see below), matching the existing no-repository convention for this binder.

### `AssignCardUseCase.kt`

`assign()` return type changes `Unit` → `Boolean` (true = assigned, false = blocked because
locked), propagating `binderRepository.assignCard(...)`'s new return value. This silently
protects the scanner/quickscan/manual-search entry points per ground-truth deltas item 2 above —
none of those call sites are required by acceptance criteria to show user feedback on a blocked
scan-into-locked-slot (out of scope per PRD, which only requires feedback from the
SlotActionSheet/SlotDetailScreen "Reassign"/"Remove" path), but the data must not silently
overwrite. Existing callers (`AssignmentViewModel`, `QuickScanViewModel`, `ScannerViewModel`) can
ignore the new return value (no signature-breaking requirement on them) unless the Coder judges
one of them needs to surface it too — not required by acceptance criteria.

## UI changes

### Lock icon overlay (Pokédex + Unown only)

Precedent: `SecondaryBinderScreen.kt` lines 104-120 (`IconButton`, `Modifier.size(24.dp)
.align(Alignment.TopEnd).background(surface.copy(alpha=0.85f), CircleShape)`).

- `MainBinderScreen.kt`'s `SlotCard` (private composable, line 180): add, inside the existing
  `Box(contentAlignment = Alignment.Center, ...)` at line 189, a conditional lock icon:
  ```kotlin
  if (slot.isLocked) {
      Box(
          modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(4.dp)
              .size(20.dp)
              .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
          contentAlignment = Alignment.Center
      ) {
          Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(14.dp))
      }
  }
  ```
  Not an `IconButton` — purely visual, per PRD ("not a button itself"). Requires the outer `Box`
  at line 189 to switch from a single-child `Box` to a container that permits an overlay child
  (it already is a `Box` with `contentAlignment` — the lock icon becomes a second child aligned
  `TopEnd`, unaffected by the existing centered image/text child).
- `UnownBinderScreen.kt`: the inline card-cell (lines 47-74, directly inside `items(entries)`,
  no extracted composable currently) needs the same overlay added inside the existing outer
  `Card { ... }` — same icon/positioning, conditioned on `entry.isLocked`. Since there's no
  extracted `SlotCell` composable to modify in isolation, this touches the inline block directly;
  optionally extract a private `UnownSlotCell` composable while here (not required, but the block
  is getting complex enough that the Coder may choose to, matching `MainBinderScreen`'s already
  extracted `SlotCard` pattern) — judgment call, not an acceptance criterion.

### Removal/reassignment guard UX

**Unown (`UnownBinderScreen.kt`'s `SlotActionSheet`, lines 96-121)**:
- Add `entry.isLocked` branching: when locked, replace the `Reassign`/`Remove Card` buttons with
  a single "Unlock to Edit" button that opens the Edit Details dialog (see below) instead of
  performing the action directly — the dialog's lock toggle is the actual unlock control, per
  PRD ("must be discoverable from the same sheet"). When unlocked, behavior is unchanged.
- Add an "Edit Details" button (always present, locked or not) that opens
  `EditCardDetailsDialog` for this entry.

**Pokédex (`SlotDetailScreen.kt`)**:
- The existing "Remove Card" `OutlinedButton` (lines 91-101): wrap in
  `if (occupied && slot?.isLocked != true)`. When `slot?.isLocked == true`, show instead an
  `OutlinedButton` "Unlock to Remove" that opens the Edit Details dialog.
- The existing "Replace"/"Search" `Button` (lines 83-89): when `occupied && slot.isLocked == true`,
  disable it (`enabled = slot != null && slot.isLocked != true`) — reassignment blocked the same
  way as removal.
- Add an "Edit Details" `OutlinedButton` (always present when a slot is loaded) that opens
  `EditCardDetailsDialog`.
- If `viewModel.clearCard()` (or a reassignment) is invoked while locked and the repository
  no-ops (defense in depth — the button should already be disabled, but a stale UI state or a
  race is possible), show an `AlertDialog` ("Unlock this card before removing or reassigning it.")
  — mirrors the existing `showRemoveDialog` `AlertDialog` pattern already in this file (lines
  27-42). Requires `SlotDetailViewModel.clearCard()` to propagate the repository's `Boolean`
  result via a new `StateFlow<Boolean>` or one-shot event (`Channel`/`SharedFlow`) the screen
  collects to trigger the dialog — Coder's choice of exact plumbing, but must not be a silent
  no-op from the user's perspective.

### `EditCardDetailsDialog` — new shared composable

New file: `app/src/main/java/com/skyler/pokedexbinder/ui/components/EditCardDetailsDialog.kt`
```kotlin
@Composable
fun EditCardDetailsDialog(
    currentLanguage: Language,
    currentRemarks: String? = null,      // null when showLockAndRemarks = false
    currentIsLocked: Boolean = false,     // null-equivalent when showLockAndRemarks = false
    showLockAndRemarks: Boolean,          // true for Pokédex/Unown, false for the other 3 binders
    onDismiss: () -> Unit,
    onSave: (language: Language, remarks: String?, isLocked: Boolean) -> Unit
)
```
Implementation: `AlertDialog` (matches `SlotDetailScreen`'s existing confirm-dialog convention,
not `ModalBottomSheet` — this is a modal edit form, not an action list) containing:
- A language selector: `OutlinedButton` + `DropdownMenu` over `Language.entries`, styled like the
  existing "Jump to section" dropdown pattern in `MainBinderScreen.kt` (lines 110-136) /
  `PersonalCollectionScreen.kt` (lines 101-127) — `OutlinedButton` opens `DropdownMenu`,
  `DropdownMenuItem` per `Language.entries` showing `.displayName`.
- `if (showLockAndRemarks)`: an `OutlinedTextField` (multiline, `remarks`) and a `Row` with
  `Text("Locked")` + `Switch(checked = isLocked, onCheckedChange = ...)`.
- `confirmButton = "Save"`, `dismissButton = "Cancel"`. Internal state (`remember { mutableStateOf(...) }`
  seeded from the `current*` params) so edits are staged locally until Save.

Entry points (each binder wires its own call site; the dialog itself is call-site-agnostic):
- **Unown**: `UnownBinderScreen.kt`'s `SlotActionSheet` — "Edit Details" button opens it with
  `showLockAndRemarks = true`, `onSave` calls
  `viewModel.updateDetails(entry.letterId, language, remarks, isLocked)` (new `UnownBinderViewModel`
  method wrapping `repository.updateDetails(...)`).
- **Connecting Art**: `ConnectingArtScreen.kt`'s `SlotActionSheet` (lines 264-296) — add an "Edit
  Details" `OutlinedButton` alongside the existing Mark Owned/Unowned/Remove buttons, opens with
  `showLockAndRemarks = false`, `onSave` calls a new `viewModel.updateLanguage(slot.id, language)`.
- **Personal Collection**: `PersonalCollectionScreen.kt`'s `CardActionSheet` (lines 216-240) —
  same pattern, `showLockAndRemarks = false`, `onSave` calls a new
  `viewModel.updateLanguage(card.cardId, language)`. Note: `PersonalCard` (the UI-layer data
  class, `PersonalCollectionViewModel.kt` line 31) currently has no `language` field — add one,
  sourced from `PersonalCollectionCache`... **actually not present there either.** `language`
  lives on `PersonalCollectionEntry` (the owned/language-tracking table), not
  `PersonalCollectionCache` (the read-only card-catalog cache) — the `uiState` combine block
  (lines 52-63) must be extended to read `entry.language` per card (default `"EN"` when no entry
  exists yet, matching the `owned` field's existing `cardId in ownedIds` lookup pattern) and
  thread it into `PersonalCard`.
- **Pokédex**: inside `SlotDetailScreen.kt` (per PRD — no action sheet exists here, so the entry
  point is a new `OutlinedButton` inside the screen body), opens with `showLockAndRemarks = true`,
  `onSave` calls `viewModel.updateDetails(pokemonId, language, remarks, isLocked)` (new
  `SlotDetailViewModel` method wrapping `binderRepository.updateDetails(...)`).
- **Card History**: `SecondaryBinderScreen.kt` — no bottom sheet exists (direct top-corner delete
  icon, lines 104-120). Add a second small `IconButton` (e.g. `Icons.Default.Edit`,
  `Alignment.TopStart` — opposite corner from the existing delete button at `TopEnd`, same
  24.dp/circular-background treatment) that opens `EditCardDetailsDialog` directly with
  `showLockAndRemarks = false`, `onSave` calls a new
  `viewModel.updateLanguage(entry.id, language)` (`SecondaryBinderViewModel`, DAO-direct per that
  file's existing no-repository convention).

## Edge cases

- **Legacy/null language values**: any row written before this migration reads back as
  `language = "EN"` (`ADD COLUMN ... DEFAULT 'EN'` backfills every existing row — SQLite `ALTER
  TABLE ADD COLUMN` always applies the default to existing rows, never leaves them `NULL`, so
  `Language.fromRaw` defensive fallback is a belt-and-suspenders guard against a future bad write,
  not something the migration itself will ever trigger).
- **`isLocked` on a slot that gets cleared via some other path** (e.g. a future bulk-reset feature
  not in this scope): out of scope, not addressed — flag only if the Coder discovers an existing
  bulk-clear path during implementation (there wasn't one found in this grounding pass).
- **Unlocking and removing in one flow**: PRD requires unlock-then-remove to "proceed normally" —
  confirm in testing that after `EditCardDetailsDialog` saves `isLocked = false`, the very next
  tap on Remove/Reassign succeeds (no stale cached `isLocked` in the ViewModel's `StateFlow` — both
  `SlotDetailViewModel.slot` and `UnownBinderViewModel.entries` are Flow-backed from Room, so this
  should be automatic, but verify no local snapshot is taken instead).
- **`PersonalCollectionEntry` row absence** — see DAO section above; `updateLanguage` must create
  the row if absent rather than silently no-opping.
- **Connecting Art `updateSlot`/`updateSlots`** (`ConnectingArtDao` lines 58-62) use full-entity
  `@Update` elsewhere in this DAO — do not repurpose those for the language edit; use the new
  targeted `updateLanguage` query instead, to avoid a stale in-memory `ConnectingArtSlot` copy
  clobbering `cardId`/`cardName`/`cardImageUrl`/`owned` on save.

## Non-goals (carried from PRD, restated for the Coder)

- No language filter/display elsewhere in the UI.
- No lock feature on Connecting Art, Personal Collection, or Card History.
- No validation tying `remarks` to `isLocked` (remarks can be blank even when locked).
- No change to card search/matching (`CardSearchRepository`, TCGCSV, etc.).
- No change to `fallbackToDestructiveMigration()`/`OnDowngrade()` config.

## Task List (dependency-ordered, sized S/M/L/XL)

### Phase 1 — Foundation (schema/migration; nothing downstream can start until this lands)

1. **[S] MODEL** — Create `data/model/Language.kt` (enum + `fromRaw`).
2. **[M] MODEL** — Add 3 new fields to `MainBinderEntry.kt` and `UnownBinderEntry.kt`; add 1 new
   field to `ConnectingArtSlot.kt`, `PersonalCollectionEntry.kt`, `SecondaryBinderEntry.kt`.
   Depends on: 1 (uses `Language`'s default `"EN"` string convention, though entities themselves
   store raw `String`, not the enum — no compile dependency, but logically sequenced first).
3. **[M] INFRA** — `PokedexDatabase.kt`: bump `version = 9`, add `MIGRATION_8_9`. Depends on: 2
   (migration SQL must match final entity column names/types).
4. **[S] INFRA** — `DatabaseModule.kt`: register `MIGRATION_8_9` in `.addMigrations(...)`.
   Depends on: 3.
5. **[L] TEST** — `Migration8to9Test.kt` (androidTest), both test methods per Migration Plan
   section above. Depends on: 3, 4.

### Phase 2 — Core (DAO + repository layer; needed before any UI can read/write new fields)

6. **[S] CORE** — `MainBinderDao.kt`: add `updateDetails(...)`. Depends on: 3.
7. **[S] CORE** — `UnownBinderDao.kt`: add `updateDetails(...)`. Depends on: 3.
8. **[S] CORE** — `ConnectingArtDao.kt`: add `updateLanguage(...)`. Depends on: 3.
9. **[S] CORE** — `PersonalCollectionDao.kt`: add `updateLanguage(...)`. Depends on: 3.
10. **[S] CORE** — `SecondaryBinderDao.kt`: add `updateLanguage(...)`. Depends on: 3.
11. **[M] MODEL** — Extend `PokemonSlot` (data/model/PokemonSlot.kt) with `language`/`remarks`/
    `isLocked`; update `BinderRepository.toDomain()` to map them. Depends on: 2.
12. **[M] CORE** — `BinderRepository.kt`: add `isLocked` guard to `assignCard`/`clearCard`
    (return `Boolean`); add `updateDetails(...)`. Depends on: 6, 11.
13. **[M] CORE** — `UnownBinderRepository.kt`: same guard + `updateDetails(...)`. Depends on: 7.
14. **[S] CORE** — `ConnectingArtRepository.kt`: add `updateLanguage(...)`. Depends on: 8. (First
    read the file's exact current method style before editing — not read in this planning pass.)
15. **[S] CORE** — `PersonalCollectionRepository.kt`: add `updateLanguage(...)` with
    create-row-if-absent logic. Depends on: 9. (Same caveat as 14 — read file first.)
16. **[S] CORE** — `AssignCardUseCase.kt`: change `assign()` return type `Unit` → `Boolean`,
    propagate `binderRepository.assignCard(...)`'s result. Depends on: 12.

### Phase 3 — Features (per-binder UI; can run in parallel across binders once Phase 2 lands)

17. **[M] UI** — `ui/components/EditCardDetailsDialog.kt` (new shared composable). Depends on: 1.
18. **[M] UI** — Pokédex: `SlotDetailScreen.kt` + `SlotDetailViewModel.kt` — lock-aware
    Remove/Replace buttons, Edit Details entry point, blocked-action `AlertDialog`. Depends on:
    12, 17.
19. **[M] UI** — Pokédex: `MainBinderScreen.kt`'s `SlotCard` — lock icon overlay. Depends on: 11.
20. **[M] UI** — Unown: `UnownBinderScreen.kt` + `UnownBinderViewModel.kt` — lock icon overlay,
    `SlotActionSheet` lock-aware buttons + Edit Details entry, blocked-action `AlertDialog`.
    Depends on: 13, 17.
21. **[S] UI** — Connecting Art: `ConnectingArtScreen.kt`'s `SlotActionSheet` +
    `ConnectingArtViewModel.kt` — Edit Details entry (language-only). Depends on: 14, 17.
22. **[M] UI** — Personal Collection: `PersonalCollectionScreen.kt`'s `CardActionSheet` +
    `PersonalCollectionViewModel.kt` — extend `PersonalCard`/`uiState` with `language`, Edit
    Details entry (language-only). Depends on: 15, 17.
23. **[S] UI** — Card History: `SecondaryBinderScreen.kt` + `SecondaryBinderViewModel.kt` — new
    top-corner edit icon, Edit Details entry (language-only) wired directly to
    `secondaryBinderDao.updateLanguage(...)`. Depends on: 10, 17.

### Phase 4 — Polish / verification

24. **[S] TEST** — Update `BinderRepositoryTest.kt` (existing unit test file, already present at
    `app/src/test/java/com/skyler/pokedexbinder/repository/`) with cases for: `assignCard`/
    `clearCard` no-op when `isLocked = true` and succeed when `false`; `updateDetails` round-trip.
    Depends on: 12.
25. **[S] TEST** — Equivalent unit test additions for `UnownBinderRepository` (create
    `UnownBinderRepositoryTest.kt` if one doesn't already exist — not found in this grounding
    pass, confirm before assuming). Depends on: 13.
26. **[M] INTG** — Full manual pass against every PRD acceptance-criteria checkbox (listed
    verbatim in the PRD's "Acceptance Criteria" section) — in particular the zero-data-loss
    upgrade path on a v8 device/emulator, since that's the one criterion no unit test can fully
    stand in for. Depends on: all above.

## Acceptance Criteria

Unchanged from the PRD — restated here for the Coder's direct reference:
- [ ] App builds and full unit suite passes with Room bumped to v9 and `MIGRATION_8_9` in place.
- [ ] A device already on schema v8 upgrades cleanly via `MIGRATION_8_9` with zero data loss.
- [ ] Every newly-created row in all 5 binders defaults to `language = EN`.
- [ ] Locking a Pokédex or Unown slot shows a lock icon on that card in the grid.
- [ ] Attempting to remove or reassign a locked Pokédex/Unown slot is blocked with a clear message;
      unlocking first allows it to proceed normally.
- [ ] "Edit Details" is reachable from every binder's card item and opens the correct dialog
      variant (language+remarks+lock vs. language-only) for that binder.
- [ ] Connecting Art, Personal Collection, and Card History are unaffected beyond gaining the
      language field and Edit Details action — no lock/remarks UI appears there.
- [ ] No `fallbackToDestructiveMigrationOnDowngrade()`-triggered data loss risk introduced
      (version only moves forward: 8 → 9).
