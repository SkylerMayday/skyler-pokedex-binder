# Coder Stage — Changes (Card Language, Lock, and Remarks — Room v8 → v9)

Implemented per `.pipeline/specs.md` (Coder stage). All 26 tasks in the spec's Task List
completed. No scope creep beyond what the spec specified.

## Files changed, by area

### Phase 1 — Schema / migration foundation

- **`data/model/Language.kt`** (new) — `Language` enum (EN/JA/KO/ZH/FR/DE/IT/ES) with
  `fromRaw(raw: String?)` defensive parser, mirroring the `SlotType` string-backed convention.
- **`data/local/MainBinderEntry.kt`**, **`UnownBinderEntry.kt`** — added
  `language: String = "EN"`, `remarks: String? = null`, `isLocked: Boolean = false`.
- **`data/local/ConnectingArtSlot.kt`**, **`PersonalCollectionEntry.kt`**,
  **`SecondaryBinderEntry.kt`** — added `language: String = "EN"` only (no lock/remarks —
  out of scope for these 3 binders per PRD non-goals).
- **`data/model/PokemonSlot.kt`** — added the same 3 fields (`language`, `remarks`,
  `isLocked`) to the domain model, since `MainBinderScreen`/`SlotDetailScreen` consume
  `PokemonSlot`, not `MainBinderEntry`, directly.
- **`data/local/PokedexDatabase.kt`** — bumped `@Database(version = 9)` (was 8), added
  `MIGRATION_8_9` (raw `execSQL` per statement, matching the existing migration style) —
  9 `ALTER TABLE ... ADD COLUMN` statements across the 5 tables.
- **`di/DatabaseModule.kt`** — registered `MIGRATION_8_9` in `.addMigrations(...)`, appended
  after `MIGRATION_7_8` (order preserved). Did **not** touch
  `fallbackToDestructiveMigration()`/`OnDowngrade()` — version only moves forward, 8 → 9.
- **`app/src/androidTest/.../Migration8to9Test.kt`** (new) — two tests, following
  `Migration6to7Test.kt`'s exact pattern (bare-shape tables + real-DAO round-trip), since
  `exportSchema = false` rules out `MigrationTestHelper`. Requires a device/emulator to run
  live — none available in this sandbox, so it was verified by **compilation only**
  (see Verification below), not executed.

### Phase 2 — DAO / repository layer

- **`MainBinderDao.kt`**, **`UnownBinderDao.kt`** — added `updateDetails(id, language, remarks,
  isLocked)` targeted `@Query` UPDATE (not full-entity `@Upsert`, to avoid clobbering
  concurrent card-assignment fields).
- **`ConnectingArtDao.kt`**, **`PersonalCollectionDao.kt`**, **`SecondaryBinderDao.kt`** —
  added `updateLanguage(id, language)` targeted `@Query` UPDATE.
- **`repository/BinderRepository.kt`**, **`UnownBinderRepository.kt`** — `assignCard()` and
  `clearCard()` return type changed `Unit` → `Boolean` (true = proceeded, false = no-op
  because the slot's `isLocked` was true). Guard is `if (existing.isLocked) return false`
  before the upsert. Added `updateDetails(id, language, remarks, isLocked)`. `toDomain()`
  (BinderRepository) now maps `language`/`remarks`/`isLocked` onto `PokemonSlot`.
- **`repository/ConnectingArtRepository.kt`**, **`PersonalCollectionRepository.kt`** —
  added `updateLanguage(id, language)`. `PersonalCollectionRepository.updateLanguage`
  creates the `PersonalCollectionEntry` row first (`upsertEntry` with `owned = false`) if
  absent, since entries are otherwise only created on first owned-toggle and a plain
  `UPDATE` would silently no-op on a nonexistent row.
- **`domain/AssignCardUseCase.kt`** — `assign()` return type `Unit` → `Boolean`. Added an
  explicit `if (slot.isLocked) return false` guard *before* the existing-card-to-secondary-
  binder backup logic, so a blocked assignment doesn't spuriously create a Card History
  entry for a card that was never actually replaced (assignCard would have no-op'd anyway;
  this keeps the whole operation atomic under the lock). Existing callers
  (`AssignmentViewModel`, `QuickScanViewModel`, `ScannerViewModel`) were left unchanged —
  they ignore the new return value, which Kotlin permits without a compile error, matching
  the spec's "no signature-breaking requirement on them."

### Phase 3 — UI

- **`ui/components/EditCardDetailsDialog.kt`** (new) — shared `AlertDialog`-based composable.
  Language `OutlinedButton` + `DropdownMenu` over `Language.entries` (matches the existing
  "Jump to section" dropdown pattern). `showLockAndRemarks` toggles the remarks
  `OutlinedTextField` + lock `Switch`. `onSave(language, remarks, isLocked)` callback.
- **Pokédex** — `ui/slotdetail/SlotDetailViewModel.kt`: `clearCard()` now propagates the
  repository's `Boolean` via a new `blockedByLock: SharedFlow<Unit>` one-shot event; added
  `updateDetails(...)`. `ui/slotdetail/SlotDetailScreen.kt`: Remove/Replace buttons are
  lock-aware (disabled/swapped for "Unlock to Remove" when locked); added an always-present
  "Edit Details" button; collects `blockedByLock` to show a defense-in-depth `AlertDialog`
  ("Unlock this card before removing or reassigning it.").
- **Pokédex grid** — `ui/mainbinder/MainBinderScreen.kt`'s `SlotCard`: added a non-clickable
  `Lock` icon overlay (`TopEnd`, 20dp circular surface background) when `slot.isLocked`,
  matching `SecondaryBinderScreen`'s existing icon-overlay precedent.
- **Unown** — `ui/unown/UnownBinderViewModel.kt`: same `blockedByLock` event + `updateDetails`
  addition. `ui/unown/UnownBinderScreen.kt`: lock icon overlay on the card cell;
  `SlotActionSheet` now takes an `onEditDetails` callback — when locked, Reassign/Remove
  are replaced by a single "Unlock to Edit" button (opens the dialog); an always-present
  "Edit Details" button was also added per the spec (so locked slots show both "Unlock to
  Edit" and "Edit Details", both opening the same dialog — verbatim per the spec's two
  separate bullet points, not a bug).
- **Connecting Art** — `ui/connectingart/ConnectingArtViewModel.kt`: added
  `updateLanguage(slotId, language)`. `ConnectingArtScreen.kt`'s `SlotActionSheet`: added an
  "Edit Details" button, wired to `EditCardDetailsDialog(showLockAndRemarks = false)`.
- **Personal Collection** — `ui/personalcollection/PersonalCollectionViewModel.kt`:
  `PersonalCard` gained a `language: String = "EN"` field; the `uiState` combine block now
  builds a `cardId -> language` lookup from `entries` (default `"EN"` when absent, mirroring
  the existing `owned` lookup pattern) and threads it through. Added `updateLanguage(...)`.
  `PersonalCollectionScreen.kt`'s `CardActionSheet`: added "Edit Details" button.
- **Card History** — `ui/secondarybinder/SecondaryBinderViewModel.kt`: added
  `updateLanguage(id, language)` (DAO-direct, matching the existing no-repository
  convention for this binder). `SecondaryBinderScreen.kt`: added a second `IconButton`
  (`Icons.Default.Edit`, `TopStart` — opposite corner from the existing delete `IconButton`
  at `TopEnd`), same 24dp circular treatment, opening the dialog with
  `showLockAndRemarks = false`.

### Phase 4 — Tests

- **`repository/BinderRepositoryTest.kt`** — added: `assignCard` returns `true`/upserts when
  unlocked; `assignCard` no-ops/`returns false` when locked; `clearCard` returns
  `true`/upserts when unlocked; `clearCard` no-ops/returns `false` when locked;
  `updateDetails` delegates to the DAO with the raw `Language.name`. (7 tests total, up
  from 3.)
- **`repository/UnownBinderRepositoryTest.kt`** (new — none existed before) — same 5-test
  shape as `BinderRepositoryTest`'s additions, for `UnownBinderRepository`.

## Simplification / review pass findings

Ran through the 4 review lenses (reuse, quality, efficiency, correctness/conventions)
against the diff:

- **Fixed during implementation** (not left for a separate pass): an initial draft of
  `EditCardDetailsDialog.kt` imported `Spacer`/`Switch` from `androidx.compose.material3`
  wildcard-style but hand-picked imports, and briefly included a nonsensical private
  `RowScope.weight` shim extension function that duplicated the real
  `androidx.compose.foundation.layout.weight` — removed before it ever reached a build;
  fixed to use the standard `androidx.compose.foundation.layout.*` wildcard import matching
  every other screen file's convention.
- **Reviewed, no change needed**: the "Edit Details" + "Unlock to Edit" dual-button
  redundancy in Unown's locked `SlotActionSheet` — looks odd but is exactly what the spec's
  two independent bullet points specify; not a bug, left as-is rather than second-guessing
  the spec.
- **Reviewed, no change needed**: `AssignCardUseCase.assign()`'s explicit `isLocked` check
  duplicates what `binderRepository.assignCard()` already guards — kept intentionally
  (see Phase 2 notes above) because omitting it would let a blocked assignment still create
  a spurious Card History backup entry.
- **Reuse**: no hand-rolled duplication found — `Language.fromRaw` is the single parse path,
  used consistently at every UI call site instead of ad-hoc `enumValueOf`/`try-catch`.
- **Efficiency**: `PersonalCollectionViewModel`'s new `languageByCardId` lookup is built once
  per `combine()` emission (same cadence as the existing `ownedIds` lookup it sits next to),
  not recomputed per row.

## Verification

Environment: `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`, `TEMP=TMP=C:\Windows\Temp` (required —
`gradlew.bat` fails at daemon startup with a misleading "Unable to establish loopback
connection" error without these set).

**1. `./gradlew.bat compileDebugKotlin --console=plain`**
```
> Task :app:compileDebugKotlin
w: .../ImageProxyExt.kt:11:16 This extension is shadowed by a member... (pre-existing, unrelated)
w: .../ScannerScreen.kt:188:26 'val LocalLifecycleOwner...' is deprecated (pre-existing, unrelated)
w: .../SlotDetailViewModel.kt:28:10 This declaration needs opt-in... ExperimentalCoroutinesApi (pre-existing flatMapLatest usage, unrelated to this change)

BUILD SUCCESSFUL in 33s
16 actionable tasks: 2 executed, 14 up-to-date
```
First attempt failed with `Unresolved reference 'Spacer'` (wrong import package in
`EditCardDetailsDialog.kt`) — fixed, then green.

**2. `./gradlew.bat testDebugUnitTest --console=plain`**
```
BUILD SUCCESSFUL in 43s
31 actionable tasks: 10 executed, 21 up-to-date
```
Parsed all `TEST-*.xml` result files under `app/build/test-results/testDebugUnitTest/`:
**139 tests total, 0 failures, 0 errors, 0 skipped** — full unit suite, not just the
changed files. `BinderRepositoryTest`: 7/7 pass. `UnownBinderRepositoryTest` (new): 5/5
pass.

**3. `./gradlew.bat compileDebugAndroidTestKotlin --console=plain`**
Failed — but the only compile errors are in **`PokedexDatabaseTest.kt`**, a file this
task never touched:
```
e: .../PokedexDatabaseTest.kt:6:8 Unresolved reference 'app'.
e: .../PokedexDatabaseTest.kt:37:63 No value passed for parameter 'dexOrder'.
... (13 more errors, all in the same file)
```
Confirmed via `git show HEAD:app/build.gradle.kts` and
`git show HEAD:.../MainBinderEntry.kt` that both root causes **predate this feature
entirely** and are unrelated to the language/lock/remarks work:
- `turbine` is only `testImplementation`, never `androidTestImplementation` — `import
  app.cash.turbine.test` was always unresolvable in this source set.
- `MainBinderEntry.dexOrder: Int` has never had a default value; the test's positional
  3-arg constructor call (`MainBinderEntry("bulbasaur", "Bulbasaur", 1)`) was already
  missing it before any change here.

This is standing tech debt, not a regression introduced by this change — flagged as a
separate background task (`task_82071e1f`) rather than fixed inline, per "no scope creep."
**`Migration8to9Test.kt`** (this task's new file) and the pre-existing
**`Migration6to7Test.kt`** produced zero errors of their own in the same compiler run —
confirmed by inspecting the full error list, every line references `PokedexDatabaseTest.kt`
only. Per the spec's own carried-over note (mirroring `Migration6to7Test.kt`'s header): no
device/emulator was available in this sandbox, so `Migration8to9Test` is verified by
**compilation correctness only**, not executed live — flagged, not silently skipped.

## Acceptance criteria status

- [x] App builds and full unit suite passes with Room bumped to v9 and `MIGRATION_8_9` in place.
- [x] `MIGRATION_8_9` compiles and is logically verified against a v8-shaped schema by
      `Migration8to9Test` — **not executed on a live device/emulator** (none available).
- [x] Every newly-created row in all 5 binders defaults to `language = EN` (entity defaults +
      migration `DEFAULT 'EN'` backfill for existing rows).
- [x] Locking a Pokédex or Unown slot shows a lock icon on that card in the grid.
- [x] Removing/reassigning a locked Pokédex/Unown slot is blocked with a clear message;
      unlocking first allows it to proceed (Flow-backed `StateFlow`s re-emit immediately on
      unlock — no local snapshot taken anywhere in the locked-state UI paths).
- [x] "Edit Details" reachable from every binder's card item, correct dialog variant per binder.
- [x] Connecting Art / Personal Collection / Card History gained only the language field +
      Edit Details action — no lock/remarks UI on any of the three.
- [x] No `fallbackToDestructiveMigration()`/`OnDowngrade()` config touched; version only
      moves forward (8 → 9).
