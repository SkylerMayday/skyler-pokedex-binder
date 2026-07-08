# Review verdict: restore-from-snapshot

**Verdict: SHIP** (with one non-blocking note for the record — see §6).

Reviewer stage, independent verification. Every claim below traced by reading the actual source, not
by trusting `changes.md` / `test-results.md`.

---

## 1. Structural-preservation guarantee (the one that must be airtight) — VERIFIED

`RestoreRepository.kt` overlay logic, traced line by line:

- **Line 70** — `val currentEntries = binderRepository.getAllEntries()`. Loads CURRENT local rows first.
  (`BinderRepository.getAllEntries()` confirmed at `BinderRepository.kt:28` → `mainBinderDao.getAll()`.)
- **Line 74** — `currentEntries.map { entry -> ... }`. Iterates the LOCAL rows, not the snapshot slots.
  This is the crux: the row identity/structure comes from local, the snapshot only supplies overlay values.
- **Line 75** — `val snap = snapshotSlots[entry.pokemonId] ?: return@map entry`. Slots with no snapshot
  match are returned **untouched** (E8).
- **Lines 77-85** — filled snapshot slot → `entry.copy(...)` sets **only** `assignedCardId`,
  `assignedCardName`, `assignedCardSetName`, `assignedCardImageUrl`. Every other field
  (`dexOrder`, `dexNumber`, `pokemonName`, `slotType`, `pokemonId`) is carried by `copy` from the LOCAL
  `entry`, never from `snap`. The snapshot's `slotName` and `dexNumber` are never read here.
- **Lines 86-94** — empty snapshot slot over a locally-filled slot → clears only the 4 assignment fields
  via `copy`; structural fields again untouched (E6).
- **Line 95** — `else -> entry` (empty both sides) untouched.
- **Line 102** — `binderRepository.seedFromJson(updated)`. `updated` is the overlaid LOCAL rows, so the
  REPLACE-based bulk write re-writes identical structure. No `dexOrder`/`slotType`/name corruption.

**Conclusion:** the D2 overlay-not-rebuild mechanism is implemented exactly as specified. The snapshot is
never used to reconstruct row structure. This is the airtight guarantee the destructive feature required.

## 2. Regression test genuinely guards the guarantee — VERIFIED

`RestoreRepositoryTest.kt:136-169` (`restore preserves local pokemonName and dexOrder even when snapshot
slotName differs`). Verified the assertion logic myself, not just the Tester's description:

- Snapshot slot `s1` has `name = "Deoxys (old display name)"` and `cardId = "c1-new"` (line 142-143).
- Local entry `s1` has `pokemonName = "Deoxys"`, `dexOrder = 999`, `slotType = "REGIONAL"` (line 145).
- Assertions (line 160-168): `assignedCardId == "c1-new"` (overlay DID happen from snapshot) **and**
  `pokemonName == "Deoxys"`, `dexOrder == 999`, `dexNumber == 386`, `slotType == "REGIONAL"`,
  `pokemonId == "s1"` (structure did NOT come from snapshot).

A naive rebuild-from-snapshot implementation would produce `pokemonName == "Deoxys (old display name)"`
(from `slotName`) → `assertEquals("Deoxys", written.pokemonName)` fails. And `dexOrder` isn't in the
snapshot schema at all, so a rebuild could not produce `999` → that assertion fails too. **The test would
fail loudly on regression.** It is a real guard, not a mock-was-called check (it uses `slot<>()` capture
and asserts on captured values — line 151, 157).

The happy-path test (line 75-133) additionally covers restored/cleared/untouched across 4 rows with
structural-field assertions on all of them. Coverage is solid.

## 3. Mirrors of publish conventions — VERIFIED

- `RestoreBinderUseCase.kt` — byte-for-byte structural mirror of `PublishBinderUseCase.kt` (thin
  `@Inject` wrapper delegating one suspend fn). Match.
- `RestoreViewModel.kt` vs `PublishViewModel.kt` — identical shape: `ELAPSED_TICK_MS = 200L`, same
  `MutableStateFlow(Idle)` + `asStateFlow`, same `tickerJob` while-`isActive` loop, same start-guard
  `if (_state.value is ...Running) return` (RestoreVM:42 == PublishVM:41), same terminal `when`
  (Success→Done, NoSnapshot/NoChanges→its state, Failure→Error), same `dismiss()`. Divergence limited to
  the spec'd `NoSnapshot` state and the Done payload (counts vs diff). Match.
- `RestoreDialog.kt` vs `PublishDialog.kt` — identical private `StepRow` (same icon logic, 16dp,
  `strokeWidth=2.dp`, `padding(vertical=4.dp)`), identical `formatElapsed` (`"%.1fs"`), identical
  `*_STEPS_ORDER` + trailing static "Done" row pattern, identical non-dismissable Running dialog. Intended
  divergences only: `RestoreDialog` has no `pageUrl` param, adds `NoSnapshot`, and Done shows
  "Restored X • Cleared Y • Skipped Z" — all per spec §7/D3. Match.

## 4. SettingsScreen wiring — VERIFIED (no bypass of confirmation)

Traced the destructive-action path in `SettingsScreen.kt`:

- Line 268-275 — `OutlinedButton("Restore from published snapshot")` `onClick` sets **only**
  `showRestoreConfirm = true`. Does not touch `showRestore`, does not start the VM.
- Line 59-73 — `AlertDialog` gated on `showRestoreConfirm`. Its confirm `TextButton` (line 67) is the
  **only** place in the file that sets `showRestore = true`. Cancel (line 70) and `onDismissRequest`
  (line 61) reset `showRestoreConfirm` and leave `showRestore = false`.
- Line 75-81 — `if (showRestore) { LaunchedEffect(Unit) { restoreVm.startRestore() } ... }`.
  `startRestore()` cannot fire unless `showRestore` is true, which per above requires the explicit
  "Restore" confirm click. **No path bypasses user confirmation** (E9/E10 hold). Confirmation copy
  explicitly warns the action overwrites local data and "cannot be undone" (line 63-65), satisfying
  acceptance criterion for the destructive gate.

## 5. Scope creep check — CLEAN

- `PublishRepository.fetchBaselineSnapshot` is `internal` (line 228) as required; the fetch body,
  `buildSnapshot`, and publish error handling (lines 220-251+) are coherent and unchanged in behavior —
  no publish-flow logic touched beyond the visibility keyword.
- Secondary / card-history restoration: **not implemented**, correctly out of scope (spec §9). The
  overlay filters `binders.firstOrNull { it.id == BINDER_ID_POKEDEX }` (line 61-62); the
  `non-pokedex binder in snapshot is ignored` test (line 249-277) proves a `cardHistory` binder in the
  snapshot produces zero counts and never writes its slots. No secondary DAO / card-history code was
  touched.
- No DI module changes (constructor `@Inject` + `@HiltViewModel`, all deps already `@Singleton`). Correct.

## 6. Non-blocking note (does not gate ship)

The entire `app/.../publish/` directory is **untracked in git** (`git status` → `?? .../publish/`),
including `PublishRepository.kt` itself. Consequently the spec's "private → internal, and nothing else"
change to `PublishRepository.kt` **cannot be diff-verified against a committed baseline** — there is no
HEAD version to diff against. I verified by reading that the fetch function and publish flow are
internally coherent and unchanged in behavior, but the "ONLY change is the visibility keyword" guarantee
rests on code-reading + the Coder/Tester's assertion, not on a git diff. This is a property of the repo's
uncommitted state, not a defect in this change. If a committed baseline for the publish feature exists on
another branch, a `git diff` there would close this gap. Not a blocker.

Separately: I could not execute the test suite myself — the Gradle daemon fails with
`Unable to establish loopback connection` in this environment (networking-restricted), independent of
sandbox mode. I relied on the Coder's and Tester's reported runs (83 tests, 8 pre-existing unrelated
failures, all Restore tests green) **plus** my own line-by-line reading of the test assertions, which is
the load-bearing verification for the structural guarantee regardless of who ran the binary.

---

## Acceptance criteria (spec §12) — all met

- Restore button adjacent to Publish — yes (SettingsScreen:268).
- Confirmation AlertDialog, overwrites/irreversible copy, Restore/Cancel — yes (SettingsScreen:59-73).
- Cancel → no-op; Restore → Fetching→Restoring→Done progress w/ live elapsed, PublishDialog pattern — yes.
- 404 → "Nothing to restore", non-error, DB untouched — yes (RestoreRepository:56-59, test line 172).
- Unconfigured → "GitHub not configured" — yes (RestoreRepository:42-47, test line 183).
- Post-restore assignments match snapshot; dexOrder/slot names/types NOT altered — yes (§1, §2).
- Secondary/card-history untouched, not restored — yes (§5).
- No GET/base64/JSON-parse duplication; `fetchBaselineSnapshot` single source, reused — yes.
- Tests pass, build green — per Coder/Tester run (not independently executable here, §6).

**Ship.**
