# Reviewer Stage — Verdict (Card Language, Lock, and Remarks — Room v8 → v9)

## Scope

Reviewed the full uncommitted working-tree diff (`git diff` / `git status`) against
`.pipeline/specs.md` and cross-checked the Coder's `changes.md` and Tester's `test-results.md`
claims by reading the actual source. Focus areas per task brief: migration safety (v8→v9,
past v8→v7 destructive-downgrade incident), lock-guard bypass-proofing across every
assign/clear entry point, and the pre-existing `PokedexDatabaseTest.kt` compile failure.

## Intent audit (Phase 2)

Clean. All 26 tasks in `specs.md`'s Task List trace to concrete diff content:

- Migration: `PokedexDatabase.kt` version bump 8→9 + `MIGRATION_8_9` (9 `ALTER TABLE ADD COLUMN`
  statements across 5 tables) exactly matches the spec's prescribed SQL. `DatabaseModule.kt`
  registers it appended after `MIGRATION_7_8`; `fallbackToDestructiveMigration()` /
  `OnDowngrade()` untouched — confirmed by reading both lines directly.
- Lock guard: `BinderRepository.assignCard`/`clearCard` and `UnownBinderRepository`'s
  equivalents both changed `Unit`→`Boolean` with `if (existing.isLocked) return false` before
  the upsert, exactly as specced. `AssignCardUseCase.assign()` propagates it and adds its own
  early `if (slot.isLocked) return false` before the Card-History backup logic (a deliberate,
  documented belt-and-suspenders addition beyond the literal spec text, justified in `changes.md`
  to prevent a spurious Card History entry on a blocked assignment — reasonable, not scope creep).
- UI: lock icon overlays (Pokédex, Unown), lock-aware Remove/Replace buttons + blocked-action
  `AlertDialog`, and `EditCardDetailsDialog` wired into all 5 binders' action sheets/screens with
  the correct `showLockAndRemarks` variant per binder — all present and match the spec's exact
  file/line targets.
- Tests: `Migration8to9Test.kt` (new, compiles, not live-executed — no emulator, explicitly
  flagged, matches the spec's own carried-over caveat from `Migration6to7Test.kt`), plus repository
  and ViewModel test additions across both Coder and Tester stages.

No scope creep found. No missing requirements found — every acceptance-criteria checkbox in
`specs.md` has direct, verifiable diff content.

## Migration safety (highest-scrutiny area per task brief)

Read `PokedexDatabase.kt`'s `MIGRATION_8_9` directly:

```kotlin
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE main_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
        ...
    }
}
```

- Version only moves forward (8→9); `fallbackToDestructiveMigration()`/`OnDowngrade()` in
  `DatabaseModule.kt` are byte-for-byte unchanged from the pre-diff version (confirmed via
  `git diff` — those two lines don't appear in the diff at all). The exact incident this
  constraint guards against (a v8→v7 downgrade wiping the device DB) has no new surface here:
  nothing in this diff changes the downgrade path.
- Every new column is `ADD COLUMN` with either a `NOT NULL DEFAULT` or nullable-no-default
  (`remarks TEXT`) — SQLite backfills existing rows with the default rather than leaving them in
  an invalid state; this is standard, safe `ALTER TABLE` usage, no `DROP`/`RENAME`/table-rebuild
  involved anywhere in the migration.
- `Migration8to9Test.kt` independently re-declares the migration's exact SQL against hand-built
  bare v8-shaped tables (not just re-running the real migration against Room's own already-
  migrated schema, which would be circular) and asserts pre-existing row data survives untouched
  plus new-column backfill values — this is the right verification shape for this kind of test,
  matching the existing `Migration6to7Test.kt` precedent in this same file.
- Confirmed independently (not just trusting the changelog) that this test cannot be executed
  live in this sandbox: no `connectedAndroidTest`/AVD available, consistent with every prior
  migration test in this repo. This is a genuine environment limitation, not corner-cutting — it
  is explicitly flagged in both `changes.md` and `test-results.md`, and flagged again here: **a
  live device/emulator run of `Migration8to9Test` before shipping to Skyler's actual device is the
  one acceptance criterion this review cannot independently close.**

## Lock-guard bypass audit (second highest-scrutiny area)

Traced every path that can write `assignedCardId`/clear it on `main_binder` and `unown_binder`:

- `BinderRepository.assignCard`/`clearCard` — guarded (`if (existing.isLocked) return false`),
  only call sites are `AssignCardUseCase.assign()` (also guards, see above) and nothing else —
  grepped `mainBinderDao.upsert(` and confirmed the only two call sites are these two guarded
  repository methods; no other code path writes to `main_binder`'s assignment columns directly.
- `AssignCardUseCase.assign()` is the sole entry point for `AssignmentViewModel`,
  `QuickScanViewModel`, and `ScannerViewModel` (confirmed via grep — all three inject
  `AssignCardUseCase`, none inject `BinderRepository` directly for assignment). All three ignore
  the new `Boolean` return value, which is explicitly out of scope per the spec ("no signature-
  breaking requirement... not required by acceptance criteria") — this is a data-safety guard, not
  a UX gap, and is correctly scoped as such.
- `UnownBinderRepository.assignCard`/`clearCard` — same guard shape. `UnownBinderViewModel` calls
  `repository.assignCard(...)` directly (no use-case layer exists for Unown, matching the existing
  no-use-case convention for this binder) and ignores the return value in `assignPendingCard()` —
  same reasoning as above, and additionally the UI-level entry point to reassign
  (`SlotActionSheet`'s "Reassign" button) is itself hidden when `entry.isLocked`, so the ignored
  return value is truly defense-in-depth, not the only guard.
- UI defense-in-depth: `SlotDetailScreen.kt`'s Replace button is `enabled = slot != null &&
  slot.isLocked != true` when occupied, and Remove is swapped for "Unlock to Remove" when locked —
  both read `slot?.isLocked` from the Flow-backed `StateFlow`, so a stale snapshot isn't possible
  (confirmed by reading `SlotDetailViewModel.slot`'s `flatMapLatest`/Room-`Flow` chain directly).
  Same shape confirmed in `UnownBinderScreen.kt`'s `SlotActionSheet`.
- No other write path to `isLocked` itself was found: the only writer is
  `MainBinderDao.updateDetails`/`UnownBinderDao.updateDetails`, both called only from
  `BinderRepository.updateDetails`/`UnownBinderRepository.updateDetails`, both called only from
  the two ViewModels' `updateDetails(...)`, both called only from the two screens'
  `EditCardDetailsDialog.onSave` — a single, traceable path with no bypass found.

No bypass path found. Confidence: high (9/10) — based on direct reads of every call site, not
pattern-matching.

## Quality pass (Phases 3-4)

- **Correctness**: DAO update methods are all targeted `@Query UPDATE ... SET <touched columns
  only>`, never `@Upsert`/full-entity `@Update` — this is the correct fix for the exact
  `ConnectingArtSlot` field-clobbering risk the spec calls out, confirmed by reading all 5 new DAO
  methods directly (`MainBinderDao.updateDetails`, `UnownBinderDao.updateDetails`,
  `ConnectingArtDao.updateLanguage`, `PersonalCollectionDao.updateLanguage`,
  `SecondaryBinderDao.updateLanguage`).
- **`PersonalCollectionRepository.updateLanguage`**: correctly creates the entry row
  (`owned = false`) first if absent before the `UPDATE`, avoiding the silent-no-op-on-nonexistent-
  row edge case the spec flagged. Verified by reading the method and its two new unit tests
  (`PersonalCollectionRepositoryTest.kt`), which explicitly assert the no-upsert-when-row-exists
  case too (not just the happy path).
- **`EditCardDetailsDialog`**: single shared composable, `showLockAndRemarks` correctly gates the
  remarks/lock fields, internal state seeded from `current*` params and only committed on Save —
  matches the spec's "staged locally until Save" requirement. Minor stylistic note, not a bug: the
  `Save` button's `remarks.ifBlank { null }` normalization only applies to Pokédex/Unown, which is
  correct (the other 3 binders pass `remarks = null` through regardless via `onSave`'s ignored
  second parameter at every non-lock call site).
- **Reliability**: `blockedByLock` is a `MutableSharedFlow<Unit>` (no replay/buffer specified,
  defaults to buffer=0) collected via `LaunchedEffect(viewModel)` in both screens — correct choice
  for a one-shot UI event; no risk of a stale re-emission on recomposition since `SharedFlow` with
  no replay doesn't replay past values to new collectors.
- **Maintainability**: naming and structure consistent with existing conventions throughout
  (`updateDetails` vs `updateLanguage` naming split cleanly mirrors the lockable-vs-not binder
  split). No dead code, no duplication beyond the acknowledged-and-justified Unown dual-button
  ("Edit Details" + "Unlock to Edit" both visible when locked) — this is explicitly per two
  separate spec bullet points, not an oversight; confirmed by re-reading `specs.md` lines 301-307,
  which do specify both buttons independently.

No bugs found in the categorized bug-hunt pass (logic, null-handling, async/concurrency,
resource-management, edge cases) — the lock-guard and DAO-targeting design specifically closes off
the two bug classes (silent overwrite of locked slots, and field-clobbering on partial-entity
saves) that this feature's own risk profile centers on.

## What looks good

- Migration is minimal, additive-only, and independently re-verified by a bare-shape test rather
  than trusting Room's own re-application of the same migration.
- Lock guard is centralized in the repository layer (not just the UI), exactly per the spec's own
  stated rationale, and traced end-to-end with no bypass found.
- Targeted `@Query UPDATE`s instead of full-entity upserts for every new "details" write path —
  directly forecloses the concurrent-field-clobbering risk this kind of feature usually creates.
- Test coverage added at both Coder and Tester stages closes real gaps (ViewModel-level lock/dialog
  behavior) rather than stopping at the repository layer.
- Pre-existing `PokedexDatabaseTest.kt` compile failure independently confirmed via `git log`/`git
  diff` to predate this feature (introduced in `df6b36c`, zero diff from this session) — correctly
  not treated as this change's problem, and already flagged as a separate background task.

## Findings

None reach the confidence threshold for the report. No P0/P1/P2 findings.

## Verdict: SHIP

One item to flag to Skyler explicitly before it reaches his real device (not a code defect, an
environment limitation both Coder and Tester already surfaced and this review independently
confirms): **`Migration8to9Test` has not been executed live** — no device/emulator was available
in this sandbox. The migration SQL and guard logic are correct by direct code inspection and the
test would need only a device/emulator to run, but the non-negotiable "zero data loss on the v8→v9
upgrade" constraint deserves one real-device confirmation before this ships, given the project's
prior incident history with this exact class of migration risk.
