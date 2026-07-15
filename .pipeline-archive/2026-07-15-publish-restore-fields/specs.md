# Spec: Publish/Restore Snapshot — Add `language`/`remarks`/`isLocked`

**Status:** Ready for implementation
**Scope:** Android app only (`D:\Claude Projects\PokedexBinderV2`). Website/public viewer repo is out of scope — briefed separately.

## Problem

Room schema v9 added `language`, `remarks`, `isLocked` columns to `main_binder` and `unown_binder` (plus `language` only to `connecting_art_slot`, `personal_collection_entry`, `secondary_binder`). None of this reaches `binder.json` — `SnapshotSlot` doesn't carry these fields, so the publish pipeline silently drops them and restore has nothing to read back. The public site and any restore-from-JSON flow are missing this data entirely.

## Goals

- `binder.json` snapshot output includes `language` for every binder that has the column, and `remarks`/`isLocked` for Pokédex and Unown.
- Restore reads these fields back onto the correct Room entities.
- Follow the exact additive-field precedent set by `owned` (commit b0686a0): default value, no schema version bump, comment noting backward compatibility.

## Non-Goals

- No `SNAPSHOT_SCHEMA_VERSION` bump — nothing reads it today; precedent (owned) didn't bump it either.
- No new Room migration — schema v9 already exists per the feature request; this spec is serialization-only.
- No retrofit of a Card History restore path — it's append-only/one-way today and stays that way. Adding `language` to its snapshot output does not imply adding restore for it.
- No changes to the website/public viewer repo.
- No UI changes (this is a data-plumbing task — display of remarks/isLocked/language elsewhere in the app is out of scope unless it already exists).

## Grounding (verified against current code, 2026-07-15)

- `app/src/main/java/com/skyler/pokedexbinder/publish/model/BinderSnapshot.kt` — `SnapshotSlot` is the single flat data class reused across all 5 binders. Current last field: `owned: Boolean = true` (line 37), comment: `// additive, backward-compatible (old JSON with no field defaults to true)`.
- `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt` — `buildSnapshot(...)` (line 282) has 5 `SnapshotSlot(...)` construction sites:
  - Pokédex: `MainBinderEntry.toSnapshotSlot()` extension fn, line 419
  - Card History: inline, line 310 (loop over `secondaryEntries: List<SecondaryBinderEntry>`)
  - Connecting Art: inline, line 341 (loop over `connectingArtSlots: List<ConnectingArtSlot>`)
  - Personal Collection: inline, line 371 (loop over `personalCache: List<PersonalCollectionCache>`, joined against `personalEntries: List<PersonalCollectionEntry>` only for the `owned` set)
  - Unown: inline, line 395 (loop over `unownEntries: List<UnownBinderEntry>`)
  - `computeDiff(...)` line 430; existing field-level diff precedent for `owned` at lines 490-499 (`ChangeType.REPLACED` when cardId equal but `owned` differs).
- `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt` — `restore(...)` (line 48) has 4 overlay blocks (line numbers as currently read):
  - main_binder: lines 81-113 (`currentEntries.map { ... }` + `binderRepository.seedFromJson(updated)`)
  - Connecting Art: lines 116-149
  - Personal Collection: lines 151-175 (owned-only overlay, does not touch `personal_collection_cache`)
  - Unown: lines 177-215
  - **No Card History restore block exists at all** — confirmed by reading the full file. This is a genuine, pre-existing one-way asymmetry; not something this spec introduces or fixes.

### Room entity confirmation (columns actually present, read directly)

| Entity | File | `language` | `remarks` | `isLocked` |
|---|---|---|---|---|
| `MainBinderEntry` | `data/local/MainBinderEntry.kt` | yes (`"EN"` default) | yes (`String?`) | yes (`false` default) |
| `UnownBinderEntry` | `data/local/UnownBinderEntry.kt` | yes | yes | yes |
| `ConnectingArtSlot` | `data/local/ConnectingArtSlot.kt` | yes | **no** | **no** |
| `PersonalCollectionEntry` | `data/local/PersonalCollectionEntry.kt` | yes | **no** | **no** |
| `PersonalCollectionCache` | `data/local/PersonalCollectionCache.kt` | **no** (no language column at all) | **no** | **no** |
| `SecondaryBinderEntry` (Card History) | `data/local/SecondaryBinderEntry.kt` | yes | **no** | **no** |

Confirms ground truth from the feature request exactly. Key subtlety: **Personal Collection's publish loop iterates `PersonalCollectionCache` rows**, which has no `language` column — the column lives on `PersonalCollectionEntry`, keyed by `cardId`. The publish loop must look up `language` via a `personalEntries` map keyed by `cardId`, analogous to the existing `pcOwnedIds` set built at line 362. Missing entry → default `"EN"` (same default as the Room column itself, so a PC card with no `personal_collection_entry` row yet — cache-only — still serializes something sane).

### Existing tests to account for

- `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt` — constructs `SnapshotSlot(...)` and calls `buildSnapshot`/`computeDiff` directly; will need new/updated cases.
- `app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt` — exercises the 4 restore overlay blocks; will need new/updated cases for `language`/`remarks`/`isLocked` restore.

## Design

### 1. `SnapshotSlot` — add 3 fields, all additive with safe defaults

```kotlin
@JsonClass(generateAdapter = true)
data class SnapshotSlot(
    val dexNumber: Int,
    val slotName: String,
    val slotType: String,
    val slotId: String,
    val cardId: String?,
    val cardName: String?,
    val cardSet: String?,
    val imageUrl: String?,
    val owned: Boolean = true,          // additive, backward-compatible (old JSON with no field defaults to true)
    val language: String = "EN",        // additive, backward-compatible (old JSON with no field defaults to "EN")
    val remarks: String? = null,        // additive, backward-compatible (old JSON with no field defaults to null); Pokédex/Unown only, unset elsewhere
    val isLocked: Boolean = false       // additive, backward-compatible (old JSON with no field defaults to false); Pokédex/Unown only, unset elsewhere
)
```

Do **not** bump `SNAPSHOT_SCHEMA_VERSION` (stays at 1) — no reader checks it, and this matches the `owned` precedent exactly.

### 2. `PublishRepository.buildSnapshot(...)` — populate at all 5 sites

- **Pokédex** (`MainBinderEntry.toSnapshotSlot()`, line 419): add `language = language`, `remarks = remarks`, `isLocked = isLocked` — direct 1:1 field copy from the entity.
- **Card History** (line 310 inline): add `language = entry.language`. Leave `remarks`/`isLocked` at their defaults (no Room column) — do not fabricate values.
- **Connecting Art** (line 341 inline): add `language = slot.language`. Leave `remarks`/`isLocked` at defaults.
- **Personal Collection** (line 371 inline): add `language = pcLanguageByCardId[row.cardId] ?: "EN"`, where `pcLanguageByCardId` is a new lookup built alongside the existing `pcOwnedIds` set:
  ```kotlin
  val pcLanguageByCardId = personalEntries.associate { it.cardId to it.language }
  ```
  Leave `remarks`/`isLocked` at defaults.
- **Unown** (line 395 inline): add `language = entry.language`, `remarks = entry.remarks`, `isLocked = entry.isLocked` — direct 1:1 field copy.

### 3. `PublishRepository.computeDiff(...)` — diff-worthy field changes

Recommendation: extend the existing per-slot "no card change but flag change" branch (currently only checks `owned`, lines 490-499) to also fire a `ChangeType.REPLACED` delta when `isLocked` changes. Rationale: `isLocked` is a binary, user-intentional toggle exactly like `owned` — same precedent, same user-visible meaning ("this slot's state changed even though the card didn't").

`language` and `remarks` are freeform/lower-signal edits (a remarks typo fix, a language correction) — do **not** add them to the changelog delta condition; would create noisy changelog entries for cosmetic edits. This is a judgment call per the feature request's "use judgment on remarks/language verbosity" note — document it here so it's not re-litigated.

Updated condition (merge into the existing branch rather than adding a parallel one, since `SlotDelta` carries no per-field detail — it's still just "this slot changed"):

```kotlin
baselineCardId != null && nextCardId != null &&
    baselineCardId == nextCardId &&
    (baselineSlot!!.owned != nextSlot!!.owned || baselineSlot.isLocked != nextSlot.isLocked) -> {
        deltas += SlotDelta(
            type = ChangeType.REPLACED,
            slotId = slotId,
            displayName = nextSlot.cardName ?: nextSlot.slotName,
            cardSet = nextSlot.cardSet
        )
    }
```

No changes to `SlotDelta`/`ChangeType`/`ChangelogEntry` shapes needed — the delta is still slot-identity-level, matching existing `owned` behavior.

### 4. `RestoreRepository.restore(...)` — extend 3 of 4 overlay blocks (Card History has none)

- **main_binder block** (lines 81-113): when `snap.cardId != null` (restored branch), also copy `language`, `remarks`, `isLocked` from `snap` onto the entry:
  ```kotlin
  entry.copy(
      assignedCardId = snap.cardId,
      assignedCardName = snap.cardName,
      assignedCardSetName = snap.cardSet,
      assignedCardImageUrl = snap.imageUrl,
      language = snap.language,
      remarks = snap.remarks,
      isLocked = snap.isLocked
  )
  ```
  Edge case: the "cleared" branch (`entry.assignedCardId != null` but `snap.cardId == null`) currently resets only the 4 assigned* fields. Decide: should clearing a slot also reset `language`/`remarks`/`isLocked` to defaults? **Recommendation: no** — these 3 fields are properties of the physical slot/binder position, not of the assigned card; clearing the card shouldn't wipe a user's remark or lock state on that slot. Leave the cleared branch untouched.

- **Connecting Art block** (lines 116-149): add `language = snap.language` to the "restored" copy (`caSlot.copy(...)`, ~line 130). No `remarks`/`isLocked` — column doesn't exist. On the "cleared" branch (~line 139), no language change needed (same reasoning as above — slot-level property, not card-level).

- **Personal Collection block** (lines 151-175): this overlay works differently — it's a owned-only toggle via `personalCollectionRepository.setOwned(cardId, true)` / `.removeOwned(cardId)`, not an entity `.copy()`. Restoring `language` here requires a new repository method (e.g. `personalCollectionRepository.setLanguage(cardId, language)`) since there's no existing write path for language on `PersonalCollectionEntry`. **Verify `PersonalCollectionRepository` (not yet read in this pass — read it in Task 5 before implementing) for what update methods already exist** before deciding whether to add a new one or extend `setOwned` to accept language. Given PC entries are keyed by `cardId` and the snapshot's `pcSnapshotSlots` already carries `slot.language` (once Task 1/2 land), the restore call should become something like:
  ```kotlin
  pcSnapshotSlots.forEach { snap ->
      val cardId = snap.cardId ?: return@forEach
      // ...existing owned overlay...
      personalCollectionRepository.setLanguage(cardId, snap.language)  // new; only if repository lacks this today
  }
  ```
  This is the one site where the exact call shape depends on reading `PersonalCollectionRepository.kt` first (not in the ground truth read so far) — flagged as an open item for the Coder stage, not blocking the rest of the spec.

- **Unown block** (lines 177-215): add `language = snap.language`, `remarks = snap.remarks`, `isLocked = snap.isLocked` to the "restored" copy (~line 193), same pattern as main_binder. Cleared branch (~line 202) left untouched for the same slot-vs-card reasoning.

- **Card History**: no restore block exists today (confirmed by full-file read). Do **not** add one — out of scope per Non-Goals. Note it as a pre-existing, intentional asymmetry (append-only publish, no restore), not something newly introduced by this change.

## Edge Cases

1. **Old `binder.json` without these fields** (published before this change) — Moshi's generated adapter uses the Kotlin default values (`"EN"` / `null` / `false`) for missing JSON keys, matching the `owned` precedent. No migration/backfill needed.
2. **Personal Collection card with cache row but no `personal_collection_entry` row yet** (never toggled owned) — `pcLanguageByCardId[row.cardId]` misses → falls back to `"EN"` default, same as the Room column default. Same fallback already exists for `owned` via `pcOwnedIds`.
3. **Restore of a Personal Collection card with `snap.language` present but repository has no write path** — must be resolved in Task 5 (read `PersonalCollectionRepository.kt` first); if adding a new DAO/repository method, keep it minimal (single `UPDATE ... SET language WHERE cardId = ?`).
4. **`isLocked` diff logic double-counts** — a slot where both `cardId` changes AND `isLocked` changes: falls into the ADDED/REPLACED/REMOVED branches earlier in the `when`, which already fire on cardId change alone; the new `isLocked`-check branch only matters when cardId is unchanged. No double-delta risk since it's a `when` (mutually exclusive branches).
5. **Card History publish** gets `language` but restore never reads it back — accepted, documented asymmetry (Non-Goals).

## Task Decomposition

Dependency-ordered. Sizes: S (single file, simple) / M (multi-file, needs care) / L (multi-module, needs tests).

1. **[S] Add 3 fields to `SnapshotSlot`**
   File: `app/src/main/java/com/skyler/pokedexbinder/publish/model/BinderSnapshot.kt`
   Add `language: String = "EN"`, `remarks: String? = null`, `isLocked: Boolean = false` after `owned`, each with the backward-compat comment matching the `owned` precedent. No other changes to this file (no schema version bump).
   Acceptance: file compiles; Moshi codegen produces adapter with 3 new nullable-safe fields.

2. **[M] Populate `language`/`remarks`/`isLocked` at all 5 `buildSnapshot` construction sites**
   File: `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt`
   Depends on: Task 1.
   - `MainBinderEntry.toSnapshotSlot()` (~line 419): add all 3 fields, direct copy.
   - Card History inline (~line 310): add `language = entry.language`.
   - Connecting Art inline (~line 341): add `language = slot.language`.
   - Personal Collection inline (~line 371): build `pcLanguageByCardId` map (alongside `pcOwnedIds`), add `language = pcLanguageByCardId[row.cardId] ?: "EN"`.
   - Unown inline (~line 395): add all 3 fields, direct copy.
   Acceptance: `buildSnapshot(...)` output includes correct `language` for all 5 binder types and correct `remarks`/`isLocked` for Pokédex/Unown only; Card History/Connecting Art/Personal Collection slots have `remarks == null`, `isLocked == false` (defaults).

3. **[S] Extend `computeDiff` to treat `isLocked` changes as diff-worthy**
   File: `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt`, `computeDiff(...)` (~line 430).
   Depends on: Task 1 (needs the field to exist on `SnapshotSlot`).
   Merge `baselineSlot.isLocked != nextSlot.isLocked` into the existing `owned`-comparison branch (see Design §3) rather than adding a parallel branch. Do not add `language`/`remarks` to any diff condition.
   Acceptance: a slot with unchanged `cardId` but changed `isLocked` produces exactly one `SlotDelta(REPLACED, ...)`; a slot with only `language`/`remarks` changed produces no delta.

4. **[M] Restore `language`/`remarks`/`isLocked` in main_binder and unown overlay blocks**
   File: `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt`.
   Depends on: Task 1.
   - main_binder "restored" branch (~lines 88-96): add `language = snap.language, remarks = snap.remarks, isLocked = snap.isLocked` to the `.copy(...)`. Leave the "cleared" branch (~97-104) untouched.
   - Unown "restored" branch (~lines 191-198): same 3 fields added. Leave "cleared" branch (~200-207) untouched.
   Acceptance: restoring a snapshot with non-default `language`/`remarks`/`isLocked` on a Pokédex or Unown slot updates the corresponding Room row; clearing a slot (card removed in snapshot) does not reset these 3 fields.

5. **[M] Restore `language` in Connecting Art and Personal Collection overlay blocks**
   File: `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt`; read `app/src/main/java/com/skyler/pokedexbinder/repository/PersonalCollectionRepository.kt` first to confirm available write methods.
   Depends on: Task 1, Task 2 (PC snapshot must actually carry `language` before restore can read it).
   - Connecting Art "restored" branch (~line 130): add `language = snap.language` to `caSlot.copy(...)`. Cleared branch (~line 139) untouched.
   - Personal Collection block (~lines 157-175): add a language-restore call per card. If `PersonalCollectionRepository` has no existing update-language method, add a minimal one (DAO `@Query("UPDATE personal_collection_entry SET language = :language WHERE cardId = :cardId")` or equivalent, following whatever pattern `setOwned`/`removeOwned` already use). Only write when `snap.language` differs from current stored value, if a lookup is cheaply available — otherwise unconditional upsert-style write is acceptable given this runs once per restore, not per-frame.
   Acceptance: restoring a snapshot with non-default `language` on Connecting Art or Personal Collection slots updates the corresponding Room row.

6. **[S] Confirm/document Card History asymmetry**
   No code change. Verify (already done in this spec) that no Card History restore block exists and that this is intentional per Non-Goals. If the Coder stage finds this assumption wrong (e.g. a restore block was added since this spec was written), stop and flag rather than silently building one.

7. **[L] Update/add tests**
   Files: `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt`, `app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt`.
   Depends on: Tasks 1-5.
   - `PublishRepositoryTest`: extend `buildSnapshot` test fixtures to set non-default `language`/`remarks`/`isLocked` on `MainBinderEntry`/`UnownBinderEntry` test data and assert they appear in the built `SnapshotSlot`s; assert Card History/Connecting Art/Personal Collection slots keep `remarks == null`/`isLocked == false` defaults while `language` is correctly populated (including the Personal Collection cardId-lookup case, and the cache-row-with-no-entry-row fallback-to-"EN" case). Add a `computeDiff` case: same cardId, `isLocked` flips → one REPLACED delta; same cardId, only `language`/`remarks` differ → no delta.
   - `RestoreRepositoryTest`: extend snapshot fixtures with non-default `language`/`remarks`/`isLocked`, assert restored Room entities pick them up for main_binder/unown, `language` for Connecting Art/Personal Collection; assert the "cleared" path leaves these 3 fields untouched.
   Acceptance: all new/updated tests pass; existing tests continue to pass (no unrelated regressions from the additive default values).

## Definition of Done

- [ ] `SnapshotSlot` carries `language`/`remarks`/`isLocked` with additive defaults, `SNAPSHOT_SCHEMA_VERSION` unchanged.
- [ ] All 5 `buildSnapshot` sites populate `language`; Pokédex + Unown also populate `remarks`/`isLocked`.
- [ ] `computeDiff` fires a delta on `isLocked` changes (not on `language`/`remarks`-only changes).
- [ ] Restore overlays for main_binder, Unown, Connecting Art, Personal Collection read back the applicable fields; Card History restore correctly stays absent.
- [ ] Unit tests updated/added per Task 7, passing.
- [ ] `./gradlew testDebugUnitTest` (or equivalent module test task) run clean before calling this done — per user's global "Verification Before Done" rule, no completion claim without proof.
- [ ] No changes made outside `D:\Claude Projects\PokedexBinderV2`.

## Open Questions (non-blocking, resolve during Task 5)

- Exact write-path shape for restoring `language` onto `PersonalCollectionEntry` — depends on what `PersonalCollectionRepository` already exposes. Tagged: engineering (Coder stage), non-blocking — a reasonable minimal DAO addition is pre-approved by this spec if nothing suitable exists.
