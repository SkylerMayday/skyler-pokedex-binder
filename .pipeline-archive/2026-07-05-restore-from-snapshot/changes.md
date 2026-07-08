# Coder changes: restore-from-snapshot

Implements `.pipeline/specs.md` in full. No scope creep beyond what the spec lists.

## Files

### New

| Path | Spec section | Notes |
|---|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt` | §4 | `RestoreStep`/`RestoreResult` sealed types + `RestoreRepository.restore()`. Implements the algorithm exactly as prose-specified: guard on blank config → `Failure(Fetching, ...)`; fetch via `publishRepository.fetchBaselineSnapshot`; `null` snapshot → `NoSnapshot`; build `pokedex`-only `slotId -> SnapshotSlot` map; load `binderRepository.getAllEntries()`; overlay-map (restore/clear/untouched per D2); `seedFromJson(updated)`; `Done`/`Success` with restored/cleared/skipped counts. try/catch wraps steps 4-10 exactly as spec'd, `currentStep` var tracks which step failed for both `PublishFailedException` and generic `Throwable`. |
| `app/src/main/java/com/skyler/pokedexbinder/domain/RestoreBinderUseCase.kt` | §5 | Direct mirror of `PublishBinderUseCase`, thin wrapper. |
| `app/src/main/java/com/skyler/pokedexbinder/ui/restore/RestoreViewModel.kt` | §6 | Mirrors `PublishViewModel` 1:1 — same `ELAPSED_TICK_MS = 200L` ticker, same start-guard (`if (state is Running) return`), same terminal `when` mapping (`Success→Done`, `NoSnapshot→NoSnapshot`, `Failure→Error`). |
| `app/src/main/java/com/skyler/pokedexbinder/ui/restore/RestoreDialog.kt` | §7 | Mirrors `PublishDialog`. Step order = `[Fetching, Restoring]` + trailing static "Done" row. `StepRow` duplicated locally (not exported), per spec's explicit instruction. No `pageUrl` param — Done state shows `"Restored X slots • Cleared Y • Skipped Z"` + elapsed, single Close button. NoSnapshot and Error states match spec copy verbatim. |
| `app/src/test/java/com/skyler/pokedexbinder/publish/RestoreRepositoryTest.kt` | §11 | All 7 spec'd cases plus one extra (DB write failure → `Failure(Restoring, ...)`, covered by E4/E-db in §10 but not enumerated as a numbered §11 case — added since the spec's edge-case table requires it be tested). Uses `mockk<PublishRepository>()` directly (concrete class) — confirmed working via the existing `mockk` setup pattern already used elsewhere in the test suite; no `SnapshotFetcher` extraction needed, per the Coder judgment call the spec explicitly delegates (§11 footnote). |

### Edited

| Path | Spec section | Change |
|---|---|---|
| `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt` | §3 | `fetchBaselineSnapshot`: `private suspend fun` → `internal suspend fun`. No other change — behavior identical. |
| `app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsScreen.kt` | §8 | Added imports for `RestoreDialog`/`RestoreViewModel`. Added `showRestoreConfirm`/`showRestore`/`restoreVm`/`restoreState` state declarations alongside the existing publish state. Added the confirmation `AlertDialog` gate (destructive, Restore/Cancel) and the `RestoreDialog` progress-dialog block, wired exactly as spec'd. Added `OutlinedButton("Restore from published snapshot")` directly below the existing `Button("Publish now")`, inside the same `Column`. `AlertDialog`/`TextButton`/`OutlinedButton` all resolve via the existing `androidx.compose.material3.*` wildcard import — no new imports needed for those. |

## DI

No DI module changes, as spec'd — `RestoreRepository`/`RestoreBinderUseCase` are `@Inject`-constructor classes (`RestoreRepository` is `@Singleton`), `RestoreViewModel` is `@HiltViewModel`. All upstream deps (`PublishRepository`, `PublishSettingsRepository`, `BinderRepository`) were already `@Singleton`-provided.

## Build result

```
./gradlew.bat assembleDebug testDebugUnitTest --console=plain
```
`assembleDebug`: **BUILD SUCCESS** (no compile errors).

`testDebugUnitTest`: 82 tests completed, 8 failed — **all 8 are the pre-existing, unrelated failures called out in the task brief**: `AssignCardUseCaseTest`, `GeminiCardScannerTest` (x4), `SmartThresholdUseCaseTest`, `BinderRepositoryTest`, `CardSearchRepositoryTest`. Confirmed by name match against the failure list; not touched by this change. All new `RestoreRepositoryTest` cases (8 total, including happy path with structural-field-preservation assertions) pass. No new failures introduced.

## Deviations from spec

None substantive. Two judgment calls, both within spec's own stated tolerance:

1. **Test mocking approach**: mocked the concrete `PublishRepository` class directly with mockk (spec §11 explicitly allows this as the default, only requiring a `SnapshotFetcher` extraction "if mocking proves painful" — it wasn't; the existing test suite already demonstrates mockk works fine on concrete `@Singleton` classes, e.g. `PublishRepositoryTest` doesn't need it since it's the class under test, but `RestoreRepositoryTest` mocking `PublishRepository` as a collaborator worked without issue).
2. **Added one test case beyond the 7 enumerated in §11**: "DB write failure maps to Failure at Restoring step" — exercises edge case E4/E-db (§10) which the spec's algorithm section (§4, step 10-11 wrap) explicitly requires but which wasn't given its own numbered case in §11's list. Added for completeness of the try/catch branch coverage; did not remove or alter any of the 7 spec'd cases.

## Not done (explicitly out of scope per spec)

- Secondary/card-history binder restore — §9, explicit rationale in spec, not implemented.
- App not run/manually verified — Tester stage owns that per the task brief.
