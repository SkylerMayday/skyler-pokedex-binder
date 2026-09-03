# Code Review: Binder Backup — Card History Restore Gap + Local Export/Import (final re-review)

**Reviewer:** Stage 5 Reviewer — lens: **correctness + spec-compliance trace**
**Date:** 2026-09-03 (iteration 2 of 2 — the 3rd and final permitted Reviewer evaluation, Item I loop cap)
**Prior verdict from this lens:** 97/100, ship (iteration 1)
**PR or commit:** uncommitted working tree on `master` (no worktree isolation)
**Author:** Coder stage, fix-loop iteration 2
**Scope this pass (narrowed per brief):** confirm the iteration-2 diff stayed inside the one named
security-lens finding, spot-check my previously-shipped findings for regression, and sanity-check the
new `PendingImportError` / forced-restart mechanism for correctness. Not a full re-review.

---

## Headline

**Ship holds, but the score drops 3 points.** The production fix is correct and I found no new
production-code bug. What I did find, by running the suite myself instead of inheriting the Tester's
count: **the two new regression tests — the only unit-level proof this iteration's fix works — failed
on my first clean `--rerun-tasks` run and passed on my second.** They are non-deterministic. That is a
P2 test-reliability finding, not a blocker, and it costs Test evidence quality 3 points.

---

## 1. Scope discipline — confirmed clean

`changes.md` claims only `BackupImporter.kt`, `MainActivity.kt`, `ImportViewModel.kt` (comments), and
`BackupImporterTest.kt` were touched. Nothing is committed, so `git diff` cannot separate iteration 1
from iteration 2 — I used file modification times instead, which do separate them cleanly:

```
09:56:21  SqliteVersionReader.kt        <- iteration 1
09:57:18  DatabaseBackupManager.kt      <- iteration 1
09:58:50  DatabaseModule.kt             <- iteration 1
09:59:40  BackupExporter.kt             <- iteration 1
10:00:43  BackupDialogs.kt              <- iteration 1
10:01:08  SettingsScreen.kt             <- iteration 1
10:02:38  RestoreRepository.kt          <- iteration 1
10:05:17  RestoreRepositoryTest.kt      <- iteration 1
10:05:31  BackupExporterTest.kt         <- iteration 1
10:05:40  DatabaseBackupManagerTest.kt  <- iteration 1
10:05:55  ImportViewModelTest.kt        <- iteration 1
--- iteration 2 boundary (~6.5h gap) ---
16:39:11  ImportViewModel.kt            <- THIS ITERATION
16:39:20  MainActivity.kt               <- THIS ITERATION
16:49:44  BackupImporterTest.kt         <- THIS ITERATION
16:53:16  BackupImporter.kt             <- THIS ITERATION
```

Exactly the four claimed files, and a 6.5-hour gap separating them from everything else. **My
previously-shipped territory was not re-touched.** No scope creep.

## 2. Prior findings — spot-checked in current code, none regressed

Read directly this pass, not inferred from mtimes:

- **AC1 duplicate-`cardId` count matching** — intact. `RestoreRepository.kt:332-343`:
  `secondaryBinderDao.getAll().groupingBy { it.cardId }.eachCount().toMutableMap()`, then
  `unmatchedLocal[cardId] = localCopies - 1   // this slot is covered by a local row`. Count-based,
  not set-membership. Unchanged.
- **AC3 five-binder parity test** — intact. `RestoreRepositoryTest.kt:906-928` still captures and
  asserts `assertEquals(mainViaRestore.captured, mainViaImport.captured)`,
  `assertEquals(unownViaRestore.captured, unownViaImport.captured)`,
  `assertEquals(historyViaRestore.captured, historyViaImport.captured)`.
- **AC6 lower-bound migratable gate** — intact. `BackupImporter.kt:173-182`, `MigrationPathResolver.hasPath(...)`
  with the rejection message `"which this app can no longer upgrade to v... — importing it would wipe every table."`
- **AC7 confirmation gating** — intact. `ImportViewModel.kt:53-54`:
  `if (current !is ImportUiState.NeedsConfirmation) return`.
- **P0 sidecar-deletion fix** — `SqliteVersionReader.kt` mtime 09:56, untouched this iteration; the
  `deleteSidecars` ownership now lives in `BackupImporter.kt:292-300` as before.

## 3. The new mechanism — correctness check

**Spec alignment improved, not just preserved.** `specs.md:238-239` mandates *"a full process restart
(not a soft 'restart the app' prompt with no enforcement)"*. Iteration 1 satisfied that on the success
path only; iteration 2 extends the same enforcement to the failure path. This moves *toward* the spec.

**Ordering is correct.** `BackupImporter.kt:235-240` persists before restarting, and persist is
synchronous:

> `context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {` — `BackupImporter.kt:59`

`commit = true` means the message is on disk before `Process.killProcess(Process.myPid())`
(`BackupImporter.kt:252`) runs. No race.

**The obvious lifecycle hole I went looking for does not exist.** `applyDb` runs in
`viewModelScope` (`ImportViewModel.kt:56`), which is cancelled when the ViewModel clears, and
`applyDb` rethrows cancellation without restarting:

> `} catch (e: CancellationException) {` / `throw e` — `BackupImporter.kt:232-233`

If that path were reachable after `database.close()`, it would reintroduce the exact bug this
iteration fixes: a permanently closed DB with no restart. **It is not reachable.** There are no
suspension points inside the `withContext` block after `database.close()` — `backupNow` is a plain,
non-suspending function:

> `fun backupNow(context: Context, dbFileName: String, reason: String): Boolean {` — `DatabaseBackupManager.kt:57`

as are `swapInDatabaseFile`, `deleteDbAndSidecars`, and `database.close()`. Cooperative cancellation
has nowhere to fire between the close and the restart. The `CancellationException` catch is defensive
only. **Checked and cleared — not a finding.**

**Restart-on-failure does not risk data.** The swap failed, so the live `.db` is untouched, and
`databaseBackupManager.backupNow(..., reason = "preimport")` (`BackupImporter.kt:226`) already took a
recovery copy. The restart is a cold Room open on the original file.

---

## Findings

### P2 — the two new regression tests are non-deterministic (NEW, this iteration)

**Confidence: 10 (I ran it and parsed the XML myself).** Root-cause attribution below is `[Likely]`,
flagged as such.

`test-results.md` §5 claims **"PASS, 264/264"** from a clean `--rerun-tasks` run. That did not
reproduce on my first independent attempt. Same command, same environment variables, stale
`app/build/test-results/testDebugUnitTest` deleted first:

```
BUILD FAILED in 1m 30s
31 actionable tasks: 31 executed
EXIT CODE: 1
```

Counted from the regenerated XML:

```
SUITE: com.skyler.pokedexbinder.data.local.backup.BackupImporterTest tests 21 fail 2 err 0
  CASE: applyDb forces a process restart even when the swap fails, instead of returning to the caller with a closed database
    java.lang.NoClassDefFoundError: com/skyler/pokedexbinder/Hilt_MainActivity
  CASE: applyDb persists the failure message for MainActivity to show after the forced restart
    java.lang.NoClassDefFoundError: com/skyler/pokedexbinder/Hilt_MainActivity
TOTAL 264 failures 2 errors 0 skipped 0
```

**The two failures are precisely, and only, the two tests this iteration added** — the entire
unit-level proof of the fix. The other 262 were unaffected.

Motivating lines. Production side:

> `val restartIntent = Intent(context, MainActivity::class.java).apply {` — `BackupImporter.kt:248`

Test side:

> `mockkConstructor(Intent::class)` — `BackupImporterTest.kt:335`
> `every { anyConstructed<Intent>().addFlags(any()) } returns Intent()` — `BackupImporterTest.kt:338`

`mockkConstructor` intercepts `Intent`'s *construction*, but `MainActivity::class.java` still forces
the JVM to **class-load** `MainActivity`, whose superclass is the KSP-generated
`Hilt_MainActivity` (confirmed present at
`app/build/generated/ksp/debug/java/com/skyler/pokedexbinder/Hilt_MainActivity.java`, compiled to
`app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/.../Hilt_MainActivity.class`,
both regenerated at 23:54 by the very run that then failed to find it). No other test in this suite
class-loads a Hilt-generated Activity superclass, which is why no other test flaked.

**`[Likely]` root cause:** a Windows file-lock / visibility race on the freshly regenerated javac
output under `--rerun-tasks`, not a defect in the test's logic. Supporting evidence from this same
session — a second, unrelated Windows lock hit me minutes later on the same build tree:

```
java.io.IOException: Unable to delete directory 'D:\Claude Projects\PokedexBinderV2\app\build\test-results\testDebugUnitTest\binary'
    - D:\...\binary\output.bin
```

This machine's existing lesson `gradle-rerun-tasks-can-fail-on-a-locked-jar.md` documents the same
class of interference. **Not certain** — I did not isolate it further.

**Reproduction record (3 runs, this session, all mine):**

| Run | Command | Result |
|---|---|---|
| 1 | full suite, stale results deleted, `--rerun-tasks` | **264 tests, 2 failures** (both new tests) |
| 2 | `--tests "*BackupImporterTest"`, incremental | 21/21 pass |
| 3 | full suite, stale results deleted, `--rerun-tasks` | **264/264, 0 failures, 0 errors** (27 XML files) |

**Why this is P2 and not a blocker:** the production code is correct on-device — the DEX genuinely
contains `Hilt_MainActivity` (`app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/skyler/pokedexbinder/Hilt_MainActivity.dex`),
and the Tester independently reproduced the full restart-and-recover path live on the emulator with
their own fixture text. This is a JVM-unit-test classpath artifact, not a runtime defect.

**Why it still costs points:** the *only* unit-level regression guard for this iteration's fix is one
that can go red for reasons unrelated to the code, which is exactly how a real regression later gets
dismissed as "that test is flaky." Recommendation: **defer**, with a `gaps.md` entry. The durable
fix is to remove the `MainActivity::class.java` class-load from the unit test (an injectable
restart seam, which `changes.md` explicitly and reasonably declined as disproportionate for this
iteration's scope — that judgement was right at the time; the flake is new information).

### P3 (appendix — confidence 5, would not act on alone)

**(a) `applyDb`'s fallback failure message can assert something false.** The catch's default text:

> `e.message ?: "Could not replace the database file — your data is unchanged"` — `BackupImporter.kt:237`

The `try` block also covers the post-move cleanup:

> `} finally {` / `deleteDbAndSidecars(preview.tempFile)` — `BackupImporter.kt:229-230`

If `Files.move` succeeded and something in that `finally` throws with a null message (a
`SecurityException` on cacheDir deletion is the only realistic candidate), the user is told their
data is unchanged when it was in fact replaced. Probability is near zero; noted, not recommended for
action.

**(b) `persist`/`consume` durability asymmetry.** `persist` is synchronous (`edit(commit = true)`,
line 59) but `consume` is not:

> `prefs.edit { remove(KEY_MESSAGE) }` — `BackupImporter.kt:68`

The KTX `edit {}` default is `apply()` (async). If a second `.db` import restarts the process via
`killProcess` before that async removal flushes, the same Toast could appear twice. Cosmetic only,
and the Tester confirmed the on-device file transitioned to `<map />` in the normal case.

### Out of lens (flagged, not scored here)

`BackupImporter.kt:10` — `import com.skyler.pokedexbinder.MainActivity` puts a data-layer class in
direct compile-time dependency on a UI Activity. Layering question for the maintainability lens, not
a correctness defect.

---

## Intent audit (Phase 2)

No change to the AC trace from my prior pass — all 7 ACs remain DONE, re-verified in code above for
AC1/AC3/AC6/AC7, and the iteration-2 change strengthens `specs.md:238-239` compliance rather than
altering any AC. No scope creep (§1). No requirement newly unmet.

Phase 3's conditional **Agent-Native Architecture** dimension: checked and does not apply — no MCP
server, no agent-callable API, no skill/agent definition in the diff, and `specs.md` contains no
agent/AI integration requirement. Skipped, as the skill directs.

---

## Score

| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | **25 / 25** (unchanged) | All 7 ACs still DONE, four re-verified in current code this pass. Diff provably scoped to the one named finding (mtime evidence, §1). The fix moves *toward* `specs.md:238-239`'s "full process restart, not a soft prompt" mandate by extending it to the failure path. |
| Correctness & bug-freedom | **24 / 25** (unchanged) | No new production bug. The one plausible new hole — a `CancellationException` escaping after `database.close()` without restarting — was checked and cleared: no suspension points exist after the close (`backupNow` is a plain `fun`, `DatabaseBackupManager.kt:57`). Persist-before-kill ordering is correct and synchronous. Same 1 point held back as last pass: I still have not personally re-run the live-device WAL experiment. |
| Security & reliability | **19 / 20** (unchanged) | The security lens's P1 is genuinely closed at the source, and the failure path now has the same recovery guarantee as the success path plus a pre-swap `backupNow`. Same 1 point held for the still-unaddressed `close → copy → kill` window with active Flow collectors — untouched by this iteration, unchanged risk, correctly still a "watch it." |
| Maintainability & simplification | **14 / 15** (unchanged) | `restartProcess()` correctly extracted so success and failure share one code path with no drift risk. The new data-layer→UI dependency is noted above but belongs to the maintainability lens. Same 1 point deferred to that lens. |
| Test evidence quality | **12 / 15** (was 15, **−3**) | I re-ran the suite myself rather than inheriting the count, and `test-results.md`'s "PASS, 264/264" did not reproduce on my first clean `--rerun-tasks` attempt: 2 failures, both of them this iteration's only regression tests, `NoClassDefFoundError: com/skyler/pokedexbinder/Hilt_MainActivity`. It passed on a second identical run. Not zeroed, because the tests' *assertions* are genuinely strong (`verify { context.startActivity(any()) }` + `verify { Process.killProcess(4242) }` after an injected failure is the exact regression proof required) and the behavior is independently confirmed live on-device. But a green count from a single run is no longer sufficient evidence here, and the pipeline certified it as though it were. |
| **Total** | **94 / 100** (was 97) | |

**Score delta vs. prior iteration: −3 (97 → 94).**

The drop is *not* a regression in the code. It is the result of independently re-running verification
this lens previously accepted, and finding it non-reproducible. Iteration 2's production change is a
genuine improvement; its test evidence is weaker than iteration 1's was believed to be.

Ship gate: **met** — 94 ≥ 85, and no unresolved P0/blocking finding from this lens. The single P2 is
recommended for **defer** with a `gaps.md` entry, not for a fourth Coder pass (which the Item I cap
forbids regardless).

**Verdict: ship** (from this lens)

---

## Sign-off

**Approval status:** Approved with one deferred P2 (flaky new regression tests).
**Date:** 2026-09-03

Note for the aggregator: aggregate = minimum across lenses. This lens returns 94. Whether the feature
clears 85 overall depends on the security and maintainability lenses for this same iteration. The P2
above is a *test-suite* reliability item and should be carried into `gaps.md` at wrapcon rather than
re-entering the fix loop.
