# Spec — Card Language, Lock, and Remarks

Status: approved for implementation (scope confirmed with Skyler 2026-07-15)

## Problem

Cards added into binders currently have no metadata beyond identity (id/name/image/set). Two needs:
1. Some physical cards are non-English prints — there's no way to record that.
2. On Pokédex/Unown, Skyler wants to mark a slot as "locked" (e.g. a card he's certain about /
   doesn't want to accidentally overwrite) with a visible marker and a note explaining why.

## Goals

- Every card-holding entity records a `language`, defaulting to `EN`, editable via a dropdown.
- Pokédex and Unown entries additionally record `remarks` (free text) and `isLocked` (boolean).
- A locked Pokédex/Unown slot shows a small lock icon on the card and cannot be
  removed/reassigned until unlocked.
- An "Edit Details" action exists on every binder's card item: Pokédex/Unown open a dialog with
  language dropdown + remarks text field + lock toggle; the other three binders (Connecting Art,
  Personal Collection, Card History) open a dialog with just the language dropdown.

## Non-Goals

- No search/filter/display of a binder by language elsewhere in the UI (e.g. no "show only JP
  cards" filter) — out of scope for this pass, matches the project's prior parked decision on
  language display (project-overview.md: "No language/localization display").
- No lock feature on Connecting Art, Personal Collection, or Card History — confirmed scope,
  those three get language-only.
- No enforcement/validation tying `remarks` content to anything (e.g. required when locked) —
  remarks is just free text, can be blank even when locked.
- No change to card search/matching behavior (CardSearchRepository, TCGCSV, etc.) — this is
  purely local metadata on already-assigned cards.

## Data Model

New Kotlin enum, `data/model/Language.kt`, following the `SlotType` string-backed convention:

```kotlin
enum class Language(val displayName: String) {
    EN("English"), JA("Japanese"), KO("Korean"), ZH("Chinese"),
    FR("French"), DE("German"), IT("Italian"), ES("Spanish")
}
```
Stored as `Language.name` (String) on each entity, default `"EN"`. Repository-level mapping via
`Language.valueOf(raw)` mirrors `SlotType`'s existing `toDomain()` pattern; fall back to `EN` on
any unrecognized/legacy-null value (defensive, since ADD COLUMN default backfills existing rows
anyway).

| Entity | New columns |
|---|---|
| `MainBinderEntry` (main_binder) | `language: String = "EN"`, `remarks: String? = null`, `isLocked: Boolean = false` |
| `UnownBinderEntry` (unown_binder) | `language: String = "EN"`, `remarks: String? = null`, `isLocked: Boolean = false` |
| `ConnectingArtSlot` (connecting_art_slot) | `language: String = "EN"` |
| `PersonalCollectionEntry` (personal_collection_entry) | `language: String = "EN"` |
| `SecondaryBinderEntry` (secondary_binder) | `language: String = "EN"` |

## Migration Plan (Room v8 → v9)

**Hard constraint** (project-overview.md Gotchas): a prior v8→v7 downgrade wiped Skyler's local DB
via `fallbackToDestructiveMigrationOnDowngrade()`. This migration must be a real forward
`MIGRATION_8_9`, never reverted once shipped.

1. Bump `@Database(version = 9)` on `PokedexDatabase`.
2. Add `MIGRATION_8_9` in `PokedexDatabase.kt` companion object, following the existing raw-SQL
   `execSQL` style used by `MIGRATION_6_7`/`MIGRATION_7_8`:
   ```sql
   ALTER TABLE main_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN';
   ALTER TABLE main_binder ADD COLUMN remarks TEXT;
   ALTER TABLE main_binder ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0;

   ALTER TABLE unown_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN';
   ALTER TABLE unown_binder ADD COLUMN remarks TEXT;
   ALTER TABLE unown_binder ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0;

   ALTER TABLE connecting_art_slot ADD COLUMN language TEXT NOT NULL DEFAULT 'EN';
   ALTER TABLE personal_collection_entry ADD COLUMN language TEXT NOT NULL DEFAULT 'EN';
   ALTER TABLE secondary_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN';
   ```
3. Register `MIGRATION_8_9` in `DatabaseModule.kt`'s `.addMigrations(...)` chain.
4. Add a `Migration8to9Test.kt` (androidTest) following the `Migration6to7Test`/existing template
   pattern — the project's gaps.md already flags missing migration test parity; this migration
   should not repeat that gap.
5. Do not touch `fallbackToDestructiveMigration()`/`OnDowngrade()` config — out of scope, and
   touching it risks the same class of incident this constraint exists to prevent.

## UI/UX

### Lock icon (Pokédex + Unown only)

Small lock icon overlay, top-corner of the card tile — mirrors the existing precedent in
`SecondaryBinderScreen.kt`'s delete-button overlay (`IconButton`, `Modifier.size(24.dp)
.align(Alignment.TopEnd)`, translucent circular background). Use `Icons.Default.Lock`,
`contentDescription = "Locked"`, shown only when `isLocked == true`; not a button itself (tapping
the card still opens the existing action sheet) — purely a visual marker. Applies to:
- `SlotCard` in `MainBinderScreen.kt`
- The inline card-cell composable in `UnownBinderScreen.kt`

### Removal/overwrite guard (Pokédex + Unown only)

- `BinderRepository.assignCard(...)`/`clearCard(...)` and `UnownBinderRepository.assignCard(...)`/
  `clearCard(...)` must check `isLocked` and no-op (or throw/return a result the ViewModel surfaces
  as a toast/snackbar: "Unlock this card before removing or reassigning it") when locked.
- The existing `SlotActionSheet` (Unown) and slot-tap-to-reassign flow (Pokédex `SlotDetailScreen`)
  must disable/hide "Reassign"/"Remove Card" when the slot is locked, and surface an "Unlock" action
  instead (which flips `isLocked` off via the same Edit Details path, or a quick dedicated toggle —
  implementer's choice, but must be discoverable from the same sheet).

### Edit Details dialog

New shared composable, `EditCardDetailsDialog` (e.g. `ui/components/EditCardDetailsDialog.kt`), a
simple `AlertDialog`/`Dialog` with:
- Language dropdown (all 5 binders) — `DropdownMenu` over `Language.entries`, mirrors the existing
  "Jump to section" dropdown pattern already in the codebase.
- Remarks `OutlinedTextField` (multiline) — Pokédex/Unown only.
- Lock toggle (`Switch` or checkbox labeled "Locked") — Pokédex/Unown only.
- Save/Cancel actions.

Entry point: add an "Edit Details" button/action to each binder's existing per-card action
surface:
- Unown / Connecting Art / Personal Collection: add an "Edit Details" button to the existing
  `SlotActionSheet`/`CardActionSheet` `ModalBottomSheet` (alongside Reassign/Remove/Owned-toggle).
- Pokédex: `MainBinderScreen`'s `SlotCard` has no existing action sheet (single `onClick` → nav to
  `SlotDetailScreen`) — add the Edit Details entry point inside `SlotDetailScreen` instead (its
  natural home, since that's already the per-slot detail/reassign screen).
- Card History: no bottom sheet exists (direct top-corner delete icon). Add a second small
  top-corner icon (e.g. a pencil/"Edit" icon, opposite corner or stacked) on the
  `SecondaryBinderScreen` list item that opens the dialog directly — no full action-sheet needed
  for a language-only edit.

## Acceptance Criteria

- [ ] App builds and full unit suite passes with Room bumped to v9 and `MIGRATION_8_9` in place.
- [ ] A device already on schema v8 upgrades cleanly via `MIGRATION_8_9` with zero data loss (all
      existing rows retain their data; new columns backfill to `EN`/`null`/`false`).
- [ ] Every newly-created row in all 5 binders defaults to `language = EN`.
- [ ] Locking a Pokédex or Unown slot shows a lock icon on that card in the grid.
- [ ] Attempting to remove or reassign a locked Pokédex/Unown slot is blocked with a clear message;
      unlocking first allows it to proceed normally.
- [ ] "Edit Details" is reachable from every binder's card item and opens the correct dialog
      variant (language+remarks+lock vs. language-only) for that binder.
- [ ] Connecting Art, Personal Collection, and Card History are unaffected beyond gaining the
      language field and Edit Details action — no lock/remarks UI appears there.
- [ ] No `fallbackToDestructiveMigrationOnDowngrade()`-triggered data loss risk introduced (version
      only moves forward: 8 → 9).
