# Coder Stage — Changes

Implemented per `.pipeline/specs.md` ("Publish/Restore Snapshot — Add `language`/`remarks`/`isLocked`"). All 7 tasks in the spec's Task Decomposition completed exactly as specified — no scope creep.

## Files changed, by area

### 1. `SnapshotSlot` — 3 new additive fields

- **`app/src/main/java/com/skyler/pokedexbinder/publish/model/BinderSnapshot.kt`** — added
  `language: String = "EN"`, `remarks: String? = null`, `isLocked: Boolean = false` after the
  existing `owned` field, each with the same backward-compatibility comment style as `owned`.
  `SNAPSHOT_SCHEMA_VERSION` left at `1` (no bump), per spec Non-Goals.

### 2. `PublishRepository.buildSnapshot(...)` — populate at all 5 construction sites

**`app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt`**

- **Pokédex** (`MainBinderEntry.toSnapshotSlot()`): added `language`, `remarks`, `isLocked` —
  direct 1:1 field copy.
- **Card History** (inline): added `language = entry.language`. `remarks`/`isLocked` left at
  defaults (no Room column on `SecondaryBinderEntry`).
- **Connecting Art** (inline): added `language = slot.language`. `remarks`/`isLocked` left at
  defaults (no Room column on `ConnectingArtSlot`).
- **Personal Collection** (inline): added a new `pcLanguageByCardId` lookup
  (`personalEntries.associate { it.cardId to it.language }`), built alongside the existing
  `pcOwnedIds` set, then `language = pcLanguageByCardId[row.cardId] ?: "EN"`. `remarks`/`isLocked`
  left at defaults (no Room columns on `PersonalCollectionEntry`/`PersonalCollectionCache`).
- **Unown** (inline): added `language`, `remarks`, `isLocked` — direct 1:1 field copy.

### 3. `PublishRepository.computeDiff(...)` — `isLocked` is diff-worthy

Merged `baselineSlot.isLocked != nextSlot.isLocked` into the existing same-cardId "flag change"
branch (previously only checked `owned`), producing a `ChangeType.REPLACED` delta. `language`/
`remarks` deliberately excluded from the diff condition — freeform/cosmetic edits per spec's
explicit judgment call, documented in the spec itself so it isn't re-litigated.

### 4/5. `RestoreRepository.restore(...)` — extend 3 of 4 overlay blocks

**`app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt`**

- **main_binder "restored" branch**: added `language = snap.language, remarks = snap.remarks,
  isLocked = snap.isLocked` to the `.copy(...)`. "Cleared" branch left untouched (these 3 fields
  are slot-level properties, not card-level — clearing a card shouldn't wipe them).
- **Connecting Art "restored" branch**: added `language = snap.language`. "Cleared" branch
  untouched.
- **Personal Collection block**: reworked to build a `localPcEntriesByCardId` map (replacing the
  narrower `locallyOwned` set) so both the owned-toggle and the new language-restore call share
  one lookup. After the existing owned/cleared branches, added a language-restore step:
  `personalCollectionRepository.updateLanguage(cardId, Language.fromRaw(snap.language))`, called
  only when the locally stored language (or the Room column default `"EN"` if no entry row exists
  yet) differs from `snap.language`. `PersonalCollectionRepository.updateLanguage(cardId, Language)`
  and the underlying DAO method already existed (added in an earlier, unrelated session per
  `PersonalCollectionRepository.kt`'s doc comment) — no new repository/DAO method was needed,
  resolving the spec's Task 5 open question.
- **Unown "restored" branch**: added `language = snap.language, remarks = snap.remarks,
  isLocked = snap.isLocked`. "Cleared" branch untouched.
- **Card History**: confirmed (per spec Task 6) that no restore block exists and none was added —
  intentional, pre-existing one-way asymmetry, out of scope per Non-Goals.

### 7. Tests

- **`app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt`** — extended
  the `entry(...)` and `slot(...)` test helpers with optional `language`/`remarks`/`isLocked`
  params (all defaulted, so every pre-existing call site is untouched). Added:
  - `buildSnapshot pokedex slot carries language remarks and isLocked from entity`
  - `buildSnapshot unown slot carries language remarks and isLocked from entity`
  - `buildSnapshot cardHistory slot carries language but defaults remarks and isLocked`
  - `buildSnapshot connectingArt slot carries language but defaults remarks and isLocked`
  - `buildSnapshot personalCollection slot looks up language by cardId and defaults remarks and isLocked`
  - `buildSnapshot personalCollection slot falls back to EN when cache row has no entry row`
  - `computeDiff isLocked flip on same card is REPLACED`
  - `computeDiff language or remarks only change on same card is no change`
- **`app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt`** — extended
  the `entry(...)` and `snapshotSlot(...)` helpers the same way; added `unownSnapshot(...)` and
  `localUnownEntry(...)` builders (no Unown-specific restore tests existed before). Added:
  - `main_binder restore overlays language remarks and isLocked on restored slot`
  - `main_binder cleared branch leaves language remarks and isLocked untouched`
  - `connecting art restore overlays language on restored slot`
  - `personal collection restore updates language when it differs from local value`
  - `personal collection restore skips language write when it already matches the EN default`
  - `unown restore overlays language remarks and isLocked on restored slot`

## Simplification / review pass

Reviewed the diff for reuse, quality, efficiency, and correctness:

- **Reuse**: the Personal Collection restore path reused the already-existing
  `PersonalCollectionRepository.updateLanguage(cardId, Language)` / `Language.fromRaw(raw)` helpers
  instead of adding new ones — both were already present from a prior, separate schema-migration
  session, so no new DAO/repository code was needed (spec's Task 5 open question resolved by
  reading the file first, as instructed).
- **Quality**: replaced the narrower `locallyOwned: Set<String>` local variable in the PC restore
  block with a `Map<String, PersonalCollectionEntry>` (`localPcEntriesByCardId`) so the owned check
  and the new language check share a single lookup instead of two separate queries/collections —
  net simplification, not just an addition.
- **Efficiency**: no new DB round-trips — `personalCollectionRepository.getAllEntries()` was
  already being fetched once per restore; the new map is derived from the same call.
- **Correctness**: verified the PC language comparison treats a missing local entry as the Room
  column default (`"EN"`) rather than always mismatching, so restoring a snapshot that's already
  at the default language for an untouched card doesn't force-create a `personal_collection_entry`
  row just to restate the default (covered by the "skips language write" test above).
- No findings required a revert — nothing was flagged as a false positive needing to be skipped.

## Verification

- **Compile**: `./gradlew.bat compileDebugKotlin` — `BUILD SUCCESSFUL` (JAVA_HOME=D:/jdk17/jdk-17.0.14+7, TEMP=TMP=C:/Windows/Temp).
- **Scoped tests**: `./gradlew.bat testDebugUnitTest --tests "com.skyler.pokedexbinder.publish.*"` —
  `BUILD SUCCESSFUL`. `PublishRepositoryTest`: 38 tests, 0 failures. `RestoreRepositoryTest`:
  23 tests, 0 failures.
- **Full unit test suite** (Definition of Done requirement): `./gradlew.bat testDebugUnitTest` —
  `BUILD SUCCESSFUL`. Aggregate across all `TEST-*.xml` result files in
  `app/build/test-results/testDebugUnitTest/`: **166 tests total, 0 failures, 0 errors**.

## Definition of Done — status

- [x] `SnapshotSlot` carries `language`/`remarks`/`isLocked` with additive defaults, `SNAPSHOT_SCHEMA_VERSION` unchanged.
- [x] All 5 `buildSnapshot` sites populate `language`; Pokédex + Unown also populate `remarks`/`isLocked`.
- [x] `computeDiff` fires a delta on `isLocked` changes (not on `language`/`remarks`-only changes).
- [x] Restore overlays for main_binder, Unown, Connecting Art, Personal Collection read back the applicable fields; Card History restore correctly stays absent.
- [x] Unit tests updated/added per Task 7, passing.
- [x] `./gradlew testDebugUnitTest` run clean before calling this done.
- [x] No changes made outside `D:\Claude Projects\PokedexBinderV2`.

## Note on pre-existing uncommitted diff

`app/build.gradle.kts` and `app/src/androidTest/java/com/skyler/pokedexbinder/data/PokedexDatabaseTest.kt`
carry an unrelated, already-applied fix (added `androidTestImplementation(libs.turbine)`, fixed two
positional `MainBinderEntry(...)` calls to pass `dexOrder` explicitly) — not touched in this session,
per the Coder-stage briefing.
