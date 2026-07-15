# Tester Stage — Test Results (Card Language, Lock, and Remarks — Room v8 → v9)

## Overall verdict: PASS

All existing and newly-added unit tests pass with fresh, re-run evidence. The androidTest
migration test compiles cleanly (cannot execute — no device/emulator in this sandbox, flagged
explicitly, not silently skipped). One real test-coverage gap existed in the Coder's handoff
(no ViewModel-level tests for the new lock/dialog behavior, despite that being the area flagged
as highest-risk) — closed in this stage by adding 24 new test cases across 5 files.

## Testing layer decision (Phase 1)

This is a Kotlin/Room/Compose Android app. The changed surface splits into:
- **DAO / migration SQL** — unit-testable in isolation only via an in-memory Room DB, which
  requires `androidTest` (JVM unit tests can't run real SQLite/Room). No device/emulator is
  available in this sandbox, so `Migration8to9Test` is verified by **compilation correctness
  only**, consistent with the existing precedent (`Migration6to7Test.kt` in this same repo was
  authored the same way). This is a real, stated limitation, not a gap being papered over.
- **Repository layer** (lock guard, `updateDetails`/`updateLanguage`, create-row-if-absent) —
  fully unit-testable with MockK against the DAO interfaces/classes. Covered.
- **ViewModel layer** (blocked-by-lock event propagation, dialog save delegation) —
  fully unit-testable with MockK + `UnconfinedTestDispatcher` + Turbine, following this repo's
  existing `*ViewModelTest.kt` conventions. Was **not** covered by the Coder's handoff; added here.
- **Compose UI** (lock icon overlay, disabled buttons, dialog rendering) — no Compose UI test
  infrastructure (`createComposeRule`, Robolectric, or instrumented UI tests) exists anywhere in
  this codebase today, and none was requested in scope. Not covered — flagged as a pre-existing
  gap, not a regression from this feature.

No device/emulator is available in this environment — confirmed by attempting
`compileDebugAndroidTestKotlin` only (compile, not run); no `connectedAndroidTest`/instrumented
run was attempted since there is no AVD/emulator to target.

## Environment (Phase 2)

`gradlew.bat` fails at daemon startup with a misleading error without these set explicitly:
```
JAVA_HOME=D:\jdk17\jdk-17.0.14+7
TEMP=C:\Windows\Temp
TMP=C:\Windows\Temp
```
Confirmed working in every command below.

## Verification evidence (Phase 6 — fresh, this session)

### 1. Fresh full unit-test run (`--rerun`, forces re-execution, not cached UP-TO-DATE)

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 31s
31 actionable tasks: 5 executed, 26 up-to-date
```

Parsed every `TEST-*.xml` under `app/build/test-results/testDebugUnitTest/` directly (not the
Gradle summary line) after this run:

**152 tests total, 0 failures, 0 errors, 0 skipped** — full suite, up from the Coder's reported
139 (net +13 new test methods: some added as brand-new test classes, some as new `@Test` methods
in existing files — see "Tests added this stage" below for the exact breakdown).

Targeted per-file confirmation (fresh XML, this run):
- `BinderRepositoryTest`: 7/7 pass (lock guard on `assignCard`/`clearCard`, `updateDetails`).
- `UnownBinderRepositoryTest`: 5/5 pass (same shape, `UnownBinderRepository`).
- `PersonalCollectionRepositoryTest` (new, this stage): 2/2 pass (create-row-if-absent edge case).
- `SlotDetailViewModelTest`: 4/4 pass (was 1; +3 this stage — `blockedByLock` emit/no-emit,
  `updateDetails` delegation).
- `UnownBinderViewModelTest` (new, this stage): 5/5 pass.
- `ConnectingArtViewModelTest`: 10/10 pass (was 9; +1 this stage — `updateLanguage`).
- `PersonalCollectionViewModelTest`: 13/13 pass (was 11; +2 this stage — `updateLanguage`,
  language-threading-with-EN-default in `uiState`).

### 2. Fresh main-source compile (`--rerun`)

```
> Task :app:compileDebugKotlin
w: .../ImageProxyExt.kt:11:16 This extension is shadowed by a member... (pre-existing, unrelated)
w: .../ScannerScreen.kt:188:26 'val LocalLifecycleOwner...' is deprecated (pre-existing, unrelated)
w: .../SlotDetailViewModel.kt:28:10 This declaration needs opt-in... ExperimentalCoroutinesApi (pre-existing, unrelated)

BUILD SUCCESSFUL in 38s
16 actionable tasks: 1 executed, 15 up-to-date
```
Matches the Coder's reported warnings exactly — no new ones from this stage's changes.

### 3. `compileDebugAndroidTestKotlin` (fresh, this session)

```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: .../PokedexDatabaseTest.kt:6:8 Unresolved reference 'app'.
e: .../PokedexDatabaseTest.kt:37:63 No value passed for parameter 'dexOrder'.
... (13 more errors, all in the same file)
```
Confirmed independently (not just trusting the Coder's claim):
- Every single error line references `PokedexDatabaseTest.kt` only — `Migration8to9Test.kt`
  produces zero errors of its own.
- `git diff HEAD -- app/src/androidTest/.../PokedexDatabaseTest.kt` is empty and
  `git log` shows the file was introduced in `df6b36c` (pre-dates this feature entirely) —
  this is confirmed pre-existing tech debt, not something this session touched or introduced.

## Findings by acceptance criterion

- **MIGRATION_8_9 correctness (schema shape, defaults backfill to EN/null/false)** — verified by
  reading `PokedexDatabase.kt`'s `MIGRATION_8_9` against `Migration8to9Test.kt`'s bare-v8-shape
  assertions: all 9 `ALTER TABLE` statements match, `language` defaults to `'EN'` on all 5 tables,
  `remarks` defaults to `NULL` and `isLocked` to `0` on the 2 lockable tables. Test compiles
  clean; **not executed live** (no emulator) — this is the one acceptance-criterion item no unit
  test can fully substitute for, flagged rather than silently assumed.
- **Language defaulting to EN on new rows across all 5 binders** — confirmed at the entity level
  (`MainBinderEntry`, `UnownBinderEntry`, `ConnectingArtSlot`, `PersonalCollectionEntry`,
  `SecondaryBinderEntry` all declare `val language: String = "EN"`) and exercised by
  `Migration8to9Test`'s real-DAO round-trip test (compiles; not live-run) plus the
  `PersonalCollectionViewModelTest` addition proving the UI layer defaults to `"EN"` when no
  entry row exists yet for a card.
- **Lock guard: `assignCard`/`clearCard` refuse when `isLocked`, succeed when not** — read
  `BinderRepository.kt` and `UnownBinderRepository.kt` directly: both guard
  `if (existing.isLocked) return false` before the upsert in both methods, both return `Boolean`.
  Confirmed by fresh `BinderRepositoryTest`/`UnownBinderRepositoryTest` runs (12 tests total
  across the two files covering locked-no-op and unlocked-succeeds for both methods). Extended
  this stage: `SlotDetailViewModel.clearCard()`/`UnownBinderViewModel.clearCard()` correctly
  propagate the repository's `false` into a one-shot `blockedByLock` event (and correctly do
  *not* emit when the repository returns `true`) — this ViewModel-to-repository wiring was
  untested before this stage; now covered by 4 new test cases (2 per ViewModel).
- **Edit Details flow must not clobber other fields** — verified architecturally: every DAO
  update path added for this feature (`MainBinderDao.updateDetails`, `UnownBinderDao
  .updateDetails`, `ConnectingArtDao.updateLanguage`, `PersonalCollectionDao.updateLanguage`,
  `SecondaryBinderDao.updateLanguage`) is a targeted `@Query` `UPDATE ... SET <only the touched
  columns> WHERE <key>` — none use `@Upsert`/full-entity `@Update`, so a language-only save
  structurally cannot touch `cardId`/`cardName`/`cardImageUrl`/`owned`/`assignedCard*` on any
  entity. This is the correct fix for the exact risk the Planner flagged (`ConnectingArtSlot`'s
  card assignment surviving a language-only edit) — confirmed by reading the DAO source, not
  just trusting the changelog's description of it.
- **ViewModel-level tests for the new dialog/lock-toggle behavior** — **gap found and closed**
  this stage. None of the 5 binders' ViewModels had any test coverage for `updateDetails`/
  `updateLanguage`/`blockedByLock` before this session (confirmed via `git diff --stat` against
  every `*ViewModelTest.kt` — zero were touched by the Coder). Added 13 new test methods across
  `SlotDetailViewModelTest` (+3), a new `UnownBinderViewModelTest` (+5), `ConnectingArtViewModelTest`
  (+1), and `PersonalCollectionViewModelTest` (+2), plus a new `PersonalCollectionRepositoryTest`
  (+2) for the create-row-if-absent edge case specifically called out in the spec.

## Tests added this stage (all pass, fresh evidence above)

| File | New tests | What they prove |
|---|---|---|
| `ui/SlotDetailViewModelTest.kt` | +3 | `blockedByLock` fires on locked clear, stays silent on unlocked clear, `updateDetails` delegates all 3 fields |
| `ui/unown/UnownBinderViewModelTest.kt` (new) | +5 | assign/cancel-assign flow, same `blockedByLock`/`updateDetails` coverage as Pokédex |
| `ui/connectingart/ConnectingArtViewModelTest.kt` | +1 | `updateLanguage` delegates slotId+language |
| `ui/personalcollection/PersonalCollectionViewModelTest.kt` | +2 | `updateLanguage` delegates; `uiState` threads per-card language, defaulting to `"EN"` when no entry row exists |
| `repository/PersonalCollectionRepositoryTest.kt` (new) | +2 | `updateLanguage` creates the entry row (owned=false) when absent; does not re-create/touch owned state when the row already exists |

## Remaining coverage gaps (not closed this stage — flagged, not silently accepted)

1. **`Migration8to9Test` has never been executed live** — no device/emulator in this
   environment. Compiles clean, logic verified by inspection against the migration SQL, but the
   one acceptance criterion this can't fully stand in for ("a device already on schema v8
   upgrades cleanly... with zero data loss") needs a real device/emulator run before ship.
2. **No Compose UI tests anywhere in this codebase** (pre-existing, not introduced by this
   feature) — the lock icon overlay, disabled Remove/Replace buttons, and
   `EditCardDetailsDialog`'s rendering/interaction are unverified by automated test, only by the
   Coder's manual build-and-inspect pass described in `changes.md`.
3. **`ConnectingArtRepository`/`SecondaryBinderDao` direct-call paths** have no repository-level
   unit test for `updateLanguage` (only `PersonalCollectionRepository`'s was added, since it has
   actual conditional logic worth testing in isolation; `ConnectingArtRepository.updateLanguage`
   and the `SecondaryBinderViewModel`'s direct DAO call are single-line delegations already
   covered indirectly via the ViewModel-level tests above).
4. **`SecondaryBinderViewModel` has no test file at all** (pre-existing gap, not introduced by
   this feature — no lock feature applies to Card History, so risk here is lower, but the
   `updateLanguage` addition is untested at any layer beyond compilation).
5. Existing standing tech debt, unrelated to this feature (confirmed via `git log`/`git diff`,
   not introduced this session): `PokedexDatabaseTest.kt` fails to compile
   (`turbine` missing from `androidTestImplementation`; `MainBinderEntry`'s 3-arg constructor
   call missing `dexOrder`). Already flagged as background task `task_82071e1f` by the Coder
   stage — not re-flagged here.

## Recommendation

**Proceed to Reviewer.** No regressions, no failing tests, all touched acceptance criteria have
direct evidence. The one criterion that can't be fully closed in this sandbox (live migration
run on a real v8 device/emulator) should be called out to Skyler explicitly before ship, per the
spec's own non-negotiable constraint around data-loss risk on this exact migration.
