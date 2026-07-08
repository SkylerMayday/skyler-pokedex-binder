# Test results: restore-from-snapshot

Tester stage. Verified against `.pipeline/specs.md` and the Coder's `.pipeline/changes.md`.

## 1. Build check — PASS

```
./gradlew.bat assembleDebug testDebugUnitTest --console=plain
```

- `assembleDebug`: **BUILD SUCCESS** (UP-TO-DATE, no compile errors — Coder's build artifacts still valid).
- `testDebugUnitTest`: **83 tests completed, 8 failed.** All 8 failures are the pre-existing, unrelated
  ones already called out in the task brief and in the Coder's `changes.md`:
  `AssignCardUseCaseTest`, `GeminiCardScannerTest` (x4), `SmartThresholdUseCaseTest`,
  `BinderRepositoryTest`, `CardSearchRepositoryTest`. Confirmed by exact name match — none touch
  restore/publish code. Test count went from 82 (Coder's run) → 83 after I added one more case (see §2).
  **No new failures introduced.**

## 2. RestoreRepositoryTest.kt critique — strengthened

Read the full file (`app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt`)
before trusting the "8 passing" claim. The existing 7 spec cases + 1 extra (DB-write-failure) are
real, meaningful assertions — not mock-was-called checks. In particular the happy-path test already
captures the actual `List<MainBinderEntry>` passed to `seedFromJson` via `slot<List<MainBinderEntry>>()`
and asserts on the captured values, which is the right shape of test for this feature.

**Gap found:** the happy-path test's `entry()` / `snapshotSlot()` helpers always use matching names
(`entry("s1", "S1", ...)` paired with `snapshotSlot("s1", ..., name = "S1")`). Because local
`pokemonName` and snapshot `slotName` were always equal in that test, the assertions on `pokemonName`
never actually distinguished "value came from local" vs "value came from snapshot" — a bug that
overlaid `slotName` onto `pokemonName` would have silently passed. This is precisely the D2/§0
CRITICAL risk the spec calls out (snapshot `slotName` is a stale display name, e.g. "Deoxys" migration
case) and it was the single highest-risk point in this feature, per the task brief.

**Fix — added a new test** (same file,
`D:\Claude Projects\PokedexBinderV2\app\src\test\java\com\skyler\pokedexbinder\publish\RestoreRepositoryTest.kt`,
inserted before `` `404 returns NoSnapshot and never writes` ``):

```kotlin
@Test
fun `restore preserves local pokemonName and dexOrder even when snapshot slotName differs`() = runTest {
    val snapshot = pokedexSnapshot(
        snapshotSlot("s1", cardId = "c1-new", name = "Deoxys (old display name)")
    )
    val localEntries = listOf(
        entry("s1", "Deoxys", dex = 386, dexOrder = 999, slotType = "REGIONAL", cardId = "c1-old")
    )
    // ... mocks wired the same way as the happy-path test ...
    val result = repository.restore {}
    val written = capturedSlot.captured.single()

    assertEquals("c1-new", written.assignedCardId)      // assignment field DID come from snapshot
    assertEquals("Deoxys", written.pokemonName)          // structural field did NOT — never "(old display name)"
    assertEquals(386, written.dexNumber)
    assertEquals(999, written.dexOrder)
    assertEquals("REGIONAL", written.slotType)
    assertEquals("s1", written.pokemonId)
}
```

This deliberately sets the snapshot `slotName` to a value that would be visibly wrong if it leaked
into `pokemonName`, and a `dexOrder` (999) that has no relationship to the snapshot at all (snapshot
carries no dexOrder field). It fails loudly if `RestoreRepository.restore()` is ever changed to build
rows from the snapshot instead of overlaying onto loaded local rows. **Ran and passes** (part of the
83/91 passing count above; 8 pre-existing failures are unrelated).

**Verdict on structural-preservation coverage: now solid.** Combined with the existing happy-path test
(covers restore/clear/untouched-slot cases across 4 rows) and this new differential test, the test
suite proves — not just asserts trivially — that `dexOrder`/`slotType`/`pokemonName` survive a restore
untouched while only the 4 assignment fields (`assignedCardId/Name/SetName/ImageUrl`) move from the
snapshot.

## 3. Edge cases from spec §10 — cross-checked against code + tests

| # | Case | Code location | Test coverage | Verdict |
|---|---|---|---|---|
| E1 | Unconfigured (blank PAT/owner/repo) | `RestoreRepository.kt:41-47` — guard before any `onStep`/fetch call | `unconfigured PAT fails fast before any fetch` — asserts `Failure(Fetching, ...)` and `fetchBaselineSnapshot` never called | PASS |
| E2 | 404 — no snapshot yet | `RestoreRepository.kt:56-59` — `snapshot == null` → `onStep(NoSnapshot)`, return `RestoreResult.NoSnapshot`, no DB write | `404 returns NoSnapshot and never writes` — asserts result type + `coVerify(exactly = 0) { seedFromJson(any()) }` | PASS — clean, non-crash, non-error outcome as required |
| E3 | Malformed JSON | Reused from `PublishRepository.fetchBaselineSnapshot` (`PublishRepository.kt:228-251`) — Moshi `fromJson` returning null on a 200 falls into the `snapshot == null` → `NoSnapshot` path (same as E2); a Moshi-thrown exception is caught by `RestoreRepository`'s generic `catch (e: Throwable)` → `Failure(Fetching, ...)` | Not separately unit-tested (Moshi parse failure isn't mocked directly), but the code path is identical to the already-tested `fetch throws maps to Failure at Fetching step` case (throws) and the already-tested `404 returns NoSnapshot` case (null) — both branches malformed JSON can hit are covered | PASS by construction — no new code path introduced for this case; spec explicitly flagged this as "Coder: verify Moshi behaviour", and the behavior matches E2/E4's already-tested branches |
| E4 | Network failure / timeout mid-fetch or mid-restore | `RestoreRepository.kt:49-111` — try/catch wraps steps 4-10; `currentStep` var tracks Fetching vs Restoring | `fetch throws maps to Failure at Fetching step` (fetch-side) + `db write failure maps to Failure at Restoring step` (write-side, added by Coder beyond the 7 spec'd cases) | PASS — both sides of E4 covered |
| E5 | Empty binder (zero slots in snapshot) | `RestoreRepository.kt:61-65` — empty slots map, loop over `currentEntries` is a no-op for overlay purposes since `snapshotSlots[entry.pokemonId]` is always null | `empty pokedex binder yields Success with all zero counts` — asserts `Success(0,0,0,...)` | PASS |
| E6 | Snapshot slot cardId=null vs local filled → clear | `RestoreRepository.kt:86-94` | Covered inside happy-path test (`s2`: local `c2-old` → snapshot null → cleared, `clearedCount == 1`) | PASS |
| E7 | Snapshot slotId with no local match → skipped | `RestoreRepository.kt:99-100` | `snapshot slotId with no local match is counted as skipped` — asserts `skippedCount == 1`, no orphan row written | PASS |
| E8 | Local slot not in snapshot → untouched | `RestoreRepository.kt:75` (`?: return@map entry`) | Covered inside happy-path test (`s4`, no snapshot entry, `assignedCardId` stays `"c4-untouched"`) | PASS |
| E9 | Double-tap Restore | `RestoreViewModel.kt:42` — `if (_state.value is RestoreUiState.Running) return` | Not unit-tested (ViewModel not covered by `RestoreRepositoryTest`), but code matches `PublishViewModel`'s identical, already-shipped guard pattern verbatim | PASS by code inspection — same guard shape as production-proven `PublishViewModel` |
| E10 | User dismisses confirm dialog | `SettingsScreen.kt:69-70` — `TextButton(onClick = { showRestoreConfirm = false })`, `showRestore` never set true | No automated UI test (none exist for `PublishDialog`'s confirm flow either — consistent with existing test coverage philosophy in this codebase) | PASS by code inspection |

No edge case in the spec's table is unhandled. E3/E9/E10 rely on code-path equivalence with
already-tested/already-shipped patterns rather than dedicated new tests — consistent with this
codebase's existing test coverage depth for the analogous Publish flow (no Compose UI tests exist
for `PublishDialog`/`SettingsScreen` either), so this isn't a regression in rigor, just parity.

## 4. Confirmation dialog wiring — PASS, verified against actual destructive-action path

Read `SettingsScreen.kt:45-81` in full. The trigger chain is:

1. `OutlinedButton("Restore from published snapshot")` (line 268-275) → `onClick` only sets
   `showRestoreConfirm = true`. Does **not** touch `showRestore` or call `restoreVm.startRestore()`.
2. `AlertDialog` (line 59-73) is gated entirely on `showRestoreConfirm`. Its **confirm** button
   (line 67) is the *only* code path in the entire file that sets `showRestore = true`. Its
   **dismiss/Cancel** button (line 70) and `onDismissRequest` (line 61) both only reset
   `showRestoreConfirm = false` — neither touches `showRestore`.
3. `RestoreViewModel.startRestore()` is invoked *exactly once*, inside `LaunchedEffect(Unit)` at
   line 76, which is nested inside `if (showRestore) { ... }` (line 75). This block cannot execute
   unless `showRestore` is `true`, which per step 2 can only happen via the AlertDialog's explicit
   "Restore" confirm click.

**Confirmed: there is no path where `startRestore()` fires without the user explicitly clicking
"Restore" in the confirmation `AlertDialog`.** Cancel/dismiss/scrim-tap all leave `showRestore` at its
initial `false` and the ViewModel's `LaunchedEffect` never runs.

## 5. RestoreDialog UI pattern — PASS, mirrors PublishDialog

Read `RestoreDialog.kt` and `PublishDialog.kt` side by side in full.

- Both use an identical private `StepRow(label, isDone, isActive)` composable — same icon logic
  (`Check` / `CircularProgressIndicator(16.dp, strokeWidth=2.dp)` / `RadioButtonUnchecked`), same
  `Modifier.padding(vertical = 4.dp)` row layout, same 16dp icon size, same text padding.
  `RestoreDialog` duplicates it locally rather than importing `PublishDialog`'s private one — matches
  spec §7's explicit instruction ("duplicate the small private StepRow ... do not export").
- Both use identical `stepOrdinal`/`stepLabel` dispatch pattern and an identical
  `formatElapsed(ms) = "%.1fs".format(ms / 1000f)` helper (byte-for-byte same implementation).
- Both `Running` states: non-dismissable (`onDismissRequest = { /* ... */ }` no-op), title ending in
  "…", ordered step rows via a `*_STEPS_ORDER` list + a trailing static "Done" row, `Elapsed: ...` text,
  empty `confirmButton = {}`.
- Terminal-state divergences are intentional per spec D3/§7, not accidental drift: `RestoreDialog` has
  a `NoSnapshot` state (Restore-only concept, spec'd) that Publish has no equivalent of; `Done` shows
  restored/cleared/skipped counts instead of a diff + "View page" button (spec explicitly says "no
  View page" for Restore, since there's no page to view after a local-only write). Copy for all four
  terminal states (`Done`, `NoSnapshot`, `Error`) matches spec §7 verbatim.

**Verdict: RestoreDialog is a faithful structural mirror of PublishDialog**, diverging only where the
spec explicitly calls for divergence (no page-view action, added NoSnapshot state, different Done
summary copy).

## Overall verdict

All 5 checks PASS. One test-suite gap found and fixed (pokemonName/slotName differential case) —
this was the single highest-risk gap in the original test suite relative to the feature's core
structural-preservation guarantee, and it's now covered by a targeted regression test that would fail
if that guarantee were ever silently broken.

**Files touched by Tester stage:**
- `D:\Claude Projects\PokedexBinderV2\app\src\test\java\com\skyler\pokedexbinder\publish\RestoreRepositoryTest.kt`
  — added 1 new test case (`restore preserves local pokemonName and dexOrder even when snapshot
  slotName differs`), no existing test modified or removed.

No other files modified. Ready for Reviewer stage.
