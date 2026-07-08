# Spec: restore-from-snapshot

Planner output. Reverse of the shipped **publish** flow: fetch `binder.json` from GitHub and
restore local Room card assignments from it. Backup/restore for reinstall / device switch.

---

## 0. Ground-truth facts (verified by reading the code, not assumed)

- `PublishRepository.fetchBaselineSnapshot(auth, config): Pair<BinderSnapshot?, String?>` already does
  the exact GET half we need: GitHub Contents API GET → base64 decode → Moshi parse `BinderSnapshot`,
  and returns `null to null` on 404. It is **private**. We reuse it by making it `internal` (see §3).
- `SnapshotSlot.slotId` **is** the `MainBinderEntry.pokemonId` (see `PublishRepository.toSnapshotSlot()`
  line 301-310: `slotId = pokemonId`). This is the stable join key.
- `SnapshotSlot` carries: `cardId`, `cardName`, `cardSet`, `imageUrl` — exactly the 4 assignment fields
  on `MainBinderEntry` (`assignedCardId`, `assignedCardName`, `assignedCardSetName`,
  `assignedCardImageUrl`). `cardId == null` means empty slot.
- **CRITICAL — `MainBinderDao.insertAll` is `@Insert(onConflict = REPLACE)`.** `seedFromJson()` wraps it.
  REPLACE deletes-then-inserts the whole row. A naive `SnapshotSlot -> MainBinderEntry` map would need
  to reconstruct `pokemonName / dexNumber / dexOrder / slotType` from the snapshot, and the snapshot's
  `slotName` is a **display** name (often stripped/renamed by migrations, e.g. "Deoxys" vs the row's
  actual name) while `dexOrder` is **not** in the snapshot at all. Writing snapshot-derived rows would
  corrupt slot structure and destroy `dexOrder` sorting. **Decision:** restore must NOT rebuild rows
  from the snapshot. It must load current local rows, overlay only the 4 assignment fields (matched by
  `pokemonId == slotId`), and write back. See §4 for the exact mechanism.
- Config (owner/repo/PAT) comes from `PublishSettingsRepository.getConfig()`. No new settings.
- Only binder id `"pokedex"` maps to `main_binder`. `"cardHistory"` → secondary binder = **out of scope**
  (see §7 rationale).

---

## 1. Decisions (with justification)

### D1 — New `RestoreRepository`, do NOT extend `PublishRepository`.
`PublishRepository` is already large (~400 lines) and single-responsibility = publish. Restore is a
distinct write-direction concern. BUT the GitHub-fetch logic must not be duplicated (explicit ask).
**Resolution:** keep `fetchBaselineSnapshot` in `PublishRepository`, promote it `private → internal`,
and have `RestoreRepository` depend on `PublishRepository` and call it. This satisfies "don't duplicate
the GET" without merging two responsibilities. `RestoreRepository` owns: config check, the fetch call,
the slot-overlay mapping, and the bulk write.

### D2 — Overlay-write, not rebuild-write (see §0 CRITICAL). Restore updates only assignment fields on
existing rows. Slots present locally but absent from snapshot are left untouched (not cleared) —
see §6 edge case E6 for the rationale and the alternative considered.

### D3 — New `RestoreDialog` + `RestoreViewModel`, mirroring `PublishDialog`/`PublishViewModel` 1:1
(step indicator + elapsed ticker). Not reused because the step set differs (Fetching → Restoring → Done)
and the terminal states differ (no "View page", no diff counts — instead a restored-count summary).
Copy is small; a shared abstraction would be premature.

### D4 — Confirmation dialog is a SEPARATE Compose state gate in `SettingsScreen`, shown BEFORE the
`RestoreViewModel` is ever started. Restore is destructive; the ticker/step dialog only launches after
the user confirms. Mirrors nothing in publish (publish is non-destructive) — new UX.

---

## 2. Files

### New files
| Path | Purpose |
|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt` | Fetch + map + bulk write. Owns `RestoreStep` / `RestoreResult` sealed types. |
| `app/src/main/java/com/skyler/pokedexbinder/domain/RestoreBinderUseCase.kt` | Thin wrapper over `RestoreRepository.restore(...)`, mirrors `PublishBinderUseCase`. |
| `app/src/main/java/com/skyler/pokedexbinder/ui/restore/RestoreViewModel.kt` | `RestoreUiState` + start/dismiss + elapsed ticker. Mirrors `PublishViewModel`. |
| `app/src/main/java/com/skyler/pokedexbinder/ui/restore/RestoreDialog.kt` | Progress dialog (step rows + elapsed) + terminal states. Mirrors `PublishDialog`. |

### Edited files
| Path | Change |
|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt` | `private suspend fun fetchBaselineSnapshot` → `internal suspend fun fetchBaselineSnapshot`. No behaviour change. (Optionally also promote `decodeBase64` — NOT needed, restore reuses the whole fetch fn.) |
| `app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsScreen.kt` | Add confirmation-dialog state + "Restore from published snapshot" button + wire `RestoreDialog`. |

**No DI changes.** `RestoreRepository` and `RestoreBinderUseCase` are constructor-injected with
`@Inject`; `RestoreViewModel` is `@HiltViewModel`. All deps (`PublishRepository`,
`PublishSettingsRepository`, `BinderRepository`, `Moshi`, `GitHubApi`) are already `@Singleton`-provided.
`RestoreRepository` should be `@Singleton`.

---

## 3. `PublishRepository` edit (exact)

Line 228: change
```
private suspend fun fetchBaselineSnapshot(
```
to
```
internal suspend fun fetchBaselineSnapshot(
```
Nothing else. Note: on a fetch HTTP error (non-404, non-2xx) it throws `PublishFailedException` with
`PublishStep.FetchingCurrent`. `RestoreRepository` must catch this and translate — see §4.

---

## 4. `RestoreRepository.kt` (structure + signatures)

```
package com.skyler.pokedexbinder.publish

@Singleton
class RestoreRepository @Inject constructor(
    private val publishRepository: PublishRepository,
    private val publishSettingsRepository: PublishSettingsRepository,
    private val binderRepository: BinderRepository
) {
    suspend fun restore(onStep: (RestoreStep) -> Unit): RestoreResult
}
```

Sealed types (defined in this file, top-level):
```
sealed interface RestoreStep {
    data object Fetching : RestoreStep
    data object Restoring : RestoreStep
    data class Done(val restoredCount: Int, val clearedCount: Int, val skippedCount: Int) : RestoreStep
    data object NoSnapshot : RestoreStep      // 404 — nothing published yet
}

sealed interface RestoreResult {
    data class Success(
        val restoredCount: Int,   // slots that received a non-null card assignment
        val clearedCount: Int,    // slots explicitly cleared because snapshot had cardId=null (see E6 note)
        val skippedCount: Int,    // snapshot slotIds with no matching local pokemonId
        val elapsedMs: Long
    ) : RestoreResult
    data object NoSnapshot : RestoreResult
    data class Failure(val step: RestoreStep, val message: String) : RestoreResult
}
```

### Algorithm (prose, no full code)
1. `start = System.currentTimeMillis()`. `currentStep = Fetching`.
2. `config = publishSettingsRepository.getConfig()`. If `githubPat/owner/repo` blank →
   `RestoreResult.Failure(RestoreStep.Fetching, "GitHub not configured — add PAT and repo in Settings")`.
   (Same guard + copy as `PublishRepository.publish` lines 82-87.)
3. `onStep(Fetching)`. `auth = "Bearer ${config.githubPat}"`.
4. `val (snapshot, _) = publishRepository.fetchBaselineSnapshot(auth, config)` inside try/catch:
   - `PublishFailedException` → map to `RestoreResult.Failure(RestoreStep.Fetching, e.message)`.
   - other `Throwable` → `RestoreResult.Failure(RestoreStep.Fetching, e.message ?: "Unknown error")`
     (covers network failure, timeout — E4).
5. If `snapshot == null` → `onStep(NoSnapshot)`; return `RestoreResult.NoSnapshot`. (404 — E2.)
6. Build `snapshotSlots: Map<String, SnapshotSlot>` = all slots across **only** the `"pokedex"` binder's
   sections, `associateBy { it.slotId }`. Filter binder id == `BINDER_ID_POKEDEX` (`"pokedex"`).
   If no pokedex binder present → treat as empty map (E7 malformed/foreign snapshot).
   If snapshot has zero slots → proceed; loop is a no-op, all counts 0 (E5 empty binder).
7. `onStep(Restoring)`. `currentStep = Restoring`.
8. `val currentEntries: List<MainBinderEntry> = binderRepository.getAllEntries()`.
9. Overlay map (the D2 mechanism):
   ```
   var restored = 0; var cleared = 0
   val updated = currentEntries.map { entry ->
       val snap = snapshotSlots[entry.pokemonId] ?: return@map entry   // no snapshot data → untouched
       when {
           snap.cardId != null -> { restored++; entry.copy(
               assignedCardId = snap.cardId,
               assignedCardName = snap.cardName,
               assignedCardSetName = snap.cardSet,
               assignedCardImageUrl = snap.imageUrl) }
           entry.assignedCardId != null -> { cleared++; entry.copy(
               assignedCardId = null, assignedCardName = null,
               assignedCardSetName = null, assignedCardImageUrl = null) }
           else -> entry   // snapshot empty AND local empty → unchanged
       }
   }
   ```
   `skipped = snapshotSlots.keys.count { it !in currentEntries.map { e -> e.pokemonId }.toSet() }`.
10. `binderRepository.seedFromJson(updated)` — single bulk `insertAll` (REPLACE) call. Because `updated`
    preserves every structural field from the loaded rows, REPLACE re-writes identical structure and only
    the assignment fields change. **No `dexOrder`/`slotType`/name corruption.** (This is why we load-then-
    overlay instead of building rows from the snapshot.)
11. `onStep(Done(restored, cleared, skipped))`; return `RestoreResult.Success(restored, cleared, skipped,
    System.currentTimeMillis() - start)`.

Wrap steps 4-10 in the same try/catch shape as `publish()`: catch `PublishFailedException` (from the
fetch reuse) and generic `Throwable`, mapping to `RestoreResult.Failure(currentStep, msg)` so a mid-
restore DB error names the Restoring step (E4/E-db).

Constant reused/redeclared: `private const val BINDER_ID_POKEDEX = "pokedex"` (same literal as
`PublishRepository`). Redeclare locally rather than exporting — trivial string, avoids widening
`PublishRepository`'s API surface further.

---

## 5. `RestoreBinderUseCase.kt`

```
package com.skyler.pokedexbinder.domain

class RestoreBinderUseCase @Inject constructor(
    private val restoreRepository: RestoreRepository
) {
    suspend fun restore(onStep: (RestoreStep) -> Unit): RestoreResult =
        restoreRepository.restore(onStep)
}
```
(Direct mirror of `PublishBinderUseCase`.)

---

## 6. `RestoreViewModel.kt`

Mirror `PublishViewModel` exactly (same elapsed ticker at `ELAPSED_TICK_MS = 200L`, same start-guard).

```
package com.skyler.pokedexbinder.ui.restore

sealed interface RestoreUiState {
    data object Idle : RestoreUiState
    data class Running(val step: RestoreStep, val elapsedMs: Long) : RestoreUiState
    data class Done(
        val restoredCount: Int, val clearedCount: Int, val skippedCount: Int, val elapsedMs: Long
    ) : RestoreUiState
    data object NoSnapshot : RestoreUiState
    data class Error(val step: RestoreStep, val message: String, val elapsedMs: Long) : RestoreUiState
}

@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val restoreBinderUseCase: RestoreBinderUseCase
) : ViewModel() {
    val state: StateFlow<RestoreUiState>          // backed by MutableStateFlow(Idle)
    fun startRestore()                            // guard: if already Running, return
    fun dismiss()                                 // cancel ticker, reset to Idle
}
```

`startRestore()` initial running step = `RestoreStep.Fetching`. Map `RestoreResult` →
`RestoreUiState` in the terminal `when` (Success→Done, NoSnapshot→NoSnapshot, Failure→Error), identical
control flow to `PublishViewModel.startPublish()` lines 40-73.

---

## 7. `RestoreDialog.kt`

Mirror `PublishDialog`. Step order list = `listOf(RestoreStep.Fetching, RestoreStep.Restoring)`, plus a
trailing static "Done" row (same pattern as PublishDialog line 76).

```
fun stepLabel(step): String   // Fetching -> "Fetching snapshot", Restoring -> "Restoring", Done -> "Done", NoSnapshot -> "No snapshot"
fun stepOrdinal(step): Int    // Fetching=0, Restoring=1, Done=2, NoSnapshot=2
fun formatElapsed(ms): String // "%.1fs" — identical to publish

@Composable fun RestoreDialog(state: RestoreUiState, onDismiss: () -> Unit)   // NOTE: no pageUrl param
```

Terminal states:
- **Running** — title "Restoring…", non-dismissable, step rows + `Elapsed: …`. Same `StepRow` composable
  (duplicate the small private `StepRow` from PublishDialog into this file — it's ~20 lines; do not export).
- **Done** — title "Restore complete", body:
  `"Restored ${restoredCount} slots • Cleared ${clearedCount} • Skipped ${skippedCount}"` + `"Took …"`.
  Single **Close** button. No "View page".
- **NoSnapshot** — title "Nothing to restore", body
  `"No published snapshot found on GitHub yet. Publish first, then you can restore."` + **OK**.
- **Error** — title "Restore failed", body `"Failed at: ${stepLabel(step)}"` + `state.message`. **Close**.

---

## 8. `SettingsScreen.kt` edits (exact placement + copy)

After the existing "Publish now" `Button` (line 228-235), inside the same `Column`, add:

State (declare near the other `remember`s, ~line 38):
```
var showRestoreConfirm by remember { mutableStateOf(false) }
var showRestore by remember { mutableStateOf(false) }
val restoreVm: RestoreViewModel = hiltViewModel()
val restoreState by restoreVm.state.collectAsState()
```

UI (after Publish button):
```
OutlinedButton(
    onClick = { showRestoreConfirm = true },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
) { Text("Restore from published snapshot") }
```
(Use `OutlinedButton` to visually subordinate it to the primary Publish action while keeping it adjacent.)

Confirmation gate (destructive — MUST be an AlertDialog, per requirement 4):
```
if (showRestoreConfirm) {
    AlertDialog(
        onDismissRequest = { showRestoreConfirm = false },
        title = { Text("Restore from snapshot?") },
        text = { Text("This overwrites your local card assignments with the ones from the " +
            "published snapshot on GitHub. Slots you've filled in since the last publish will be " +
            "lost. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = { showRestoreConfirm = false; showRestore = true }) { Text("Restore") }
        },
        dismissButton = {
            TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
        }
    )
}
```

Progress dialog (only after confirm):
```
if (showRestore) {
    LaunchedEffect(Unit) { restoreVm.startRestore() }
    RestoreDialog(
        state = restoreState,
        onDismiss = { showRestore = false; restoreVm.dismiss() }
    )
}
```
Imports to add: `com.skyler.pokedexbinder.ui.restore.RestoreDialog`,
`com.skyler.pokedexbinder.ui.restore.RestoreViewModel`, `OutlinedButton`, `AlertDialog`, `TextButton`
(AlertDialog/TextButton come from the `material3.*` wildcard already imported line 10).

---

## 9. Card History / secondary binder — OUT OF SCOPE (rationale)

The snapshot *can* contain a `"cardHistory"` binder, but restoring it cleanly is not tractable here:
- Publish maps secondary entries with `slotId = "${entry.pokemonId}-${entry.id}"` where `id` is a Room
  **autoincrement PK** (`buildSnapshot` line 281). On a fresh reinstall those PKs don't exist and can't be
  reconstructed from the snapshot — the join key is meaningless post-reinstall.
- `SnapshotSlot` drops secondary-only fields (no per-entry timestamp/quantity survives the round-trip
  cleanly), so a faithful secondary restore isn't possible from `binder.json` alone.
- The stated feature goal is main-binder backup/restore. Secondary restore would need its own snapshot
  schema change, out of this feature's scope.

**Decision:** restore ignores every binder whose id != `"pokedex"`. Document this in the Done summary
implicitly (counts are pokedex-only). If secondary restore is wanted later, it's a separate spec.

---

## 10. Edge cases (all must be handled — mapped to where)

| # | Case | Handling |
|---|---|---|
| E1 | No PAT/owner/repo configured | §4 step 2 guard → `Failure(Fetching, "GitHub not configured …")`. Confirmation dialog still shows first; failure surfaces in RestoreDialog Error state. |
| E2 | 404 — no snapshot published yet | `fetchBaselineSnapshot` returns `null` → §4 step 5 → `RestoreResult.NoSnapshot` → "Nothing to restore" dialog. Clear, non-error. |
| E3 | Malformed JSON in binder.json | Moshi `fromJson` inside `fetchBaselineSnapshot` returns null or throws. If it returns null-snapshot from a successful 200, §4 step 5 treats it as NoSnapshot (acceptable — nothing usable to restore). If it throws, caught in §4 step 4 → `Failure(Fetching, message)`. Coder: verify Moshi behaviour; if a 200 with garbage yields a non-null but empty `binders`, E5 covers it. |
| E4 | Network failure / timeout mid-fetch or mid-restore | try/catch around fetch (step 4) and around DB write (steps 8-10). Fetch failure → `Failure(Fetching,…)`; DB failure → `Failure(Restoring,…)`. `currentStep` var tracks which. |
| E5 | Snapshot exists but pokedex binder has zero slots | §4 step 6/9 loop no-ops, all counts 0 → `Success(0,0,0,…)` → Done shows "Restored 0 slots …". Not an error. |
| E6 | Snapshot slot has `cardId=null` (slot was empty at publish) vs local slot currently filled | §4 step 9 **clears** the local assignment (counts as `cleared`). Rationale: restore = "make local match the published snapshot", so an empty published slot means empty locally. Alternative considered (only ever additive, never clear) rejected: it would make restore non-deterministic w.r.t. the snapshot and defeat the "match my published state" mental model. Confirmation copy explicitly warns "will be lost". |
| E7 | Snapshot contains slotIds not present locally (e.g. slot removed by a migration) | §4 step 9 `snapshotSlots[entry.pokemonId]` never matches for those; counted in `skipped`. No crash, no orphan insert. |
| E8 | Local slot present but not in snapshot (e.g. a section user hadn't published) | Left untouched (map returns `entry` unchanged). Not cleared — restore only asserts state for slots the snapshot actually describes... **except** E6 clearing applies only when a matching snapshot slot exists with null cardId. Slots with NO snapshot entry are never cleared. |
| E9 | Double-tap Restore | `RestoreViewModel.startRestore()` guards `if (state is Running) return`. `LaunchedEffect(Unit)` fires once per `showRestore` toggle. |
| E10 | User dismisses confirm dialog | `showRestoreConfirm=false`, `showRestore` stays false, VM never started. No-op. |

---

## 11. Tests (unit — mirror existing `AssignCardUseCaseTest` / `PublishRepository` test style)

New: `app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt`. Fake/mocked
`PublishRepository.fetchBaselineSnapshot`, `PublishSettingsRepository.getConfig`,
`BinderRepository.getAllEntries`/`seedFromJson`. Cases:
1. Happy path — snapshot with 3 pokedex slots (2 filled, 1 empty) over 4 local rows → asserts
   restored=2, and one local filled slot whose snapshot is null gets cleared (cleared=1), and the local
   row with no snapshot entry is untouched, and `seedFromJson` receives rows with **unchanged**
   `dexOrder`/`slotType`/`pokemonName` (the D2 guarantee — assert structural fields preserved).
2. 404 → `RestoreResult.NoSnapshot`, `seedFromJson` never called.
3. Unconfigured (blank PAT) → `Failure(Fetching, …)`, no fetch attempted.
4. Fetch throws → `Failure(Fetching, msg)`.
5. Empty pokedex binder → `Success(0,0,0,…)`.
6. Snapshot slotId with no local match → counted in `skipped`, not inserted.
7. Non-pokedex binder ("cardHistory") in snapshot is ignored — its slots don't affect counts.

Coder: check whether `PublishRepository` is easily mockable (it's a concrete `@Singleton` class, not an
interface). If mocking the concrete class is awkward in the existing test setup, the cleaner route is to
extract the fetch into a small `SnapshotFetcher` collaborator both repos depend on — but only do this if
mocking proves painful; default is mock the concrete class (mockk handles it).

---

## 12. Acceptance criteria

- [ ] Settings shows "Restore from published snapshot" button adjacent to "Publish now".
- [ ] Tapping it shows a confirmation AlertDialog stating the action overwrites local data & is
      irreversible, with Restore / Cancel.
- [ ] Cancel → nothing happens. Restore → progress dialog (Fetching → Restoring → Done) with live
      elapsed time, matching PublishDialog's visual pattern.
- [ ] 404 → "Nothing to restore" dialog, not an error, DB untouched.
- [ ] Unconfigured GitHub → clear "GitHub not configured" error.
- [ ] After restore, local main-binder card assignments match the published snapshot; `dexOrder` /
      slot names / slot types are NOT altered (structural integrity preserved).
- [ ] Secondary/card-history binder is untouched and not restored.
- [ ] No GitHub Contents-API GET / base64 / JSON-parse code is duplicated — `fetchBaselineSnapshot` is
      the single source, reused.
- [ ] `RestoreRepositoryTest` passes all §11 cases; full test suite + build green.
