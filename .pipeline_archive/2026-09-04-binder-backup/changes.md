# Changes — Binder Backup: Card History Restore Gap + Local Export/Import

**Stage:** Coder (fix-loop iteration 2 of 2, Item I score-threshold auto-loop — final permitted iteration)
**Base:** `master` @ `92c5ef9`, working directly in the checkout (no worktree isolation)
**Input:** `.pipeline/review-verdict.md` + `.pipeline/review-security.md` (the one lens with a blocking
finding) + `.pipeline/changes.md` (iteration 1's state, read in full before this pass)
**Prior scores:** correctness 97/100 (ship), maintainability 91/100 (ship), security 83/100
(needs-changes) → aggregate 83/100. Per the brief, correctness and maintainability are **not**
re-touched this iteration — only the one named security finding.

This file replaces iteration 1's `changes.md`. Iteration 1's own file-by-file work (the P0 fix, the
identity/lower-bound gates, export rotation, Auto Backup exclusion, the `RestoreRepository`
decomposition, etc.) is condensed under "Iteration 1 — carried forward, not re-touched" below rather
than reproduced verbatim; every one of those items was independently re-verified fixed by all three
lenses in iteration 1's own review pass and is out of scope for this iteration.

---

## The one finding this iteration fixes

**`review-security.md`'s new P1 (score 83/100, the only thing keeping this feature below the 85 ship
threshold):** `BackupImporter.applyDb` closed the live, Hilt-singleton `PokedexDatabase` unconditionally
before attempting the atomic `.db` swap. If the swap subsequently failed, the exception was caught
gracefully into `ImportUiState.Error` — but nothing reopened or restarted around the now-permanently-closed
`database` instance. Room's own documented contract (`room-runtime-2.6.1-sources.jar`,
`RoomDatabase.kt:1217-1221`, re-verified directly, not assumed): *"The database will not be re-opened if
... closed manually ... you must create a new database using `RoomDatabase.Builder.build()`."* The old
uncaught-crash behavior accidentally self-healed via process death; the new graceful catch removed that
safety net without replacing it, leaving every other DAO-backed screen in the app pointed at a dead
connection for the rest of the process's life.

**Fix chosen: the verdict file's ranked option 2 — force the same restart path on failure that the
success path already uses**, plus persisting the failure message across that restart so the user still
sees why (a refinement the verdict file's "minimum to reach ship" section named explicitly). Option 1
(don't close the DB until the swap has already succeeded) was considered and rejected: the atomic move
still needs to land on the exact path Room's live connection has open, and reordering to defer `close()`
past the swap would only *narrow* the closed-with-no-recovery window, not eliminate it — a `backupNow()`
failure between `close()` and the swap would still hit it. Restart-on-any-post-close-failure closes the
gap completely regardless of which specific step throws, which is what "restart is the only way back to
a fresh `RoomDatabase.Builder.build()`" actually requires.

---

## File-by-file (this iteration)

**`app/src/main/java/com/skyler/pokedexbinder/data/local/backup/BackupImporter.kt`** (modified)
- New top-level `object PendingImportError` (`persist`/`consume`, backed by a dedicated
  `SharedPreferences` file, `commit()`-synchronous on write since the process is about to be killed).
  Exists because a restart-on-failure means the failure can no longer reach the user through
  `ImportViewModel`'s `StateFlow` — the process that would emit it is gone before any collector sees it —
  so the message has to survive on disk instead, for `MainActivity` to read once at the next cold start.
- `applyDb` restructured: the `backupNow(...)` + `swapInDatabaseFile(...)` sequence (everything that runs
  after `database.close()`) is now wrapped in one `try`/`catch`. `CancellationException` still rethrows
  unchanged. Any other `Exception` now calls `PendingImportError.persist(...)` with the failure message,
  then forces the *same* `restartProcess()` the success path already used, extracted into its own private
  function shared by both the success tail and the failure branch. The success path is otherwise
  byte-for-byte unchanged (still stages, moves, drops the old sidecars, restarts).
- KDoc on `applyDb` updated: it now documents that the function does not return normally on success
  **or** failure, and why (Room's no-reopen contract), rather than claiming failures are the caller's to
  surface.
- Used the `androidx.core.content.edit` KTX extension for the two `SharedPreferences` writes (`core-ktx`
  was already a dependency — `app/build.gradle.kts:79`) rather than raw `.edit().putX().commit()/apply()`
  chains, closing two `UseKtx` lint warnings my own first draft introduced (see Check 2 below) rather
  than leaving them.

**`app/src/main/java/com/skyler/pokedexbinder/ui/settings/ImportViewModel.kt`** (modified)
- Comments only, no logic change: `confirmImport`'s existing `try { applyDb(...) } catch (e: Exception) {
  ... }` around the `.db` branch is now documented as a backstop, not the primary handler — `applyDb`
  itself resolves a failed swap internally (persist + restart) and isn't expected to throw for that case
  in practice anymore. Kept the catch in place rather than removing it: if something above the
  restart call itself ever throws unexpectedly, this is still the only thing standing between that and an
  uncaught crash with no dialog.

**`app/src/main/java/com/skyler/pokedexbinder/MainActivity.kt`** (modified)
- `onCreate` now calls `PendingImportError.consume(this)` before `setContent {}` and shows a
  `Toast.makeText(..., Toast.LENGTH_LONG)` if a message was pending. This is the read side of the
  restart-survives-the-message mechanism above — deliberately a plain `Toast`, not a new navigation
  route, dialog, or ViewModel wiring, to keep this fix scoped to the one finding rather than growing a
  new UI surface for a single-line, one-shot message.

**`app/src/test/java/com/skyler/pokedexbinder/data/local/backup/BackupImporterTest.kt`** (modified, 19 → 21
tests)
- Two new tests compose `database.close()` + a real swap failure + the restart-or-recover decision in one
  flow through the real `applyDb`, closing exactly the gap the security lens named ("none of these tests
  compose a real `PokedexDatabase.close()` with a subsequent swap failure ... through the real `applyDb`,
  not an isolated `swapInDatabaseFile` call"):
  - *"applyDb forces a process restart even when the swap fails, instead of returning to the caller with
    a closed database"* — injects the same "Android evicted the cacheDir temp copy" failure the existing
    isolated `swapInDatabaseFile` test uses, then asserts `database.close()` ran, the live `.db` file
    survived untouched, and — the actual regression proof — `context.startActivity(...)` and
    `Process.killProcess(...)` were *still* both invoked despite the failure.
  - *"applyDb persists the failure message for MainActivity to show after the forced restart"* — same
    failure, asserts the `SharedPreferences.Editor` received a `putString` + `commit()` call with a
    message that names the database.
  - `android.os.Process` is intercepted via `mockkStatic` (not previously used in this file);
    `android.content.Intent`'s constructor via `mockkConstructor` — `Intent` is real Android framework
    code with no other seam in this project (no Robolectric), and `applyDb`'s restart path constructs one
    directly. Everything else in the test stays exactly as documented (mocked `Context`, fake
    `SqliteVersionReader`). The class doc comment was updated to name this one exception rather than leave
    the old "never touches real Android framework code" claim inaccurate.
  - `setUp()`/`tearDown()` gained `context.getSharedPreferences`/`packageName`/`startActivity` stubs and a
    mocked `SharedPreferences`/`Editor` pair, plus `unmockkAll()` in `tearDown` so the static/constructor
    mocks never leak into another test method.

---

## Iteration 1 — carried forward, not re-touched

Condensed from iteration 1's own `changes.md` (fully read before this pass, not reproduced verbatim
here) — all independently re-verified fixed by all three lenses in iteration 1's review and out of scope
for this iteration's brief:

- **P0 fixed:** `SqliteVersionReader` no longer deletes WAL/SHM sidecars for any caller (was silently
  discarding uncommitted transactions from `DatabaseBackupManager`'s own pre-migration backup on every
  cold start).
- **`.db` import atomicity + gates:** staged `<db>.importing` + `Files.move(..., ATOMIC_MOVE)` swap, a
  table-identity gate (`REQUIRED_TABLES`) so an unrelated SQLite file can't be accepted, a lower-bound
  migration-path gate so a too-old backup can't be accepted and then destructively wiped on the next cold
  start, and an unconditional pre-swap recovery backup via `DatabaseBackupManager.backupNow(...)`.
- **Export hygiene:** `exports/` rotated to 5 pairs via a new shared `BackupRotation`, excluded from Auto
  Backup/device transfer via new `res/xml/data_extraction_rules.xml` + `full_backup_content.xml`.
- **`RestoreRepository.applySnapshot`** decomposed into per-binder functions; Card History duplicate-key
  matching fixed to compare counts, not set membership.
- **UI:** the six inlined dialogs extracted into `BackupDialogs.kt`; confirmation copy made path-aware
  (JSON overlay vs. `.db` full replace).
- Full detail, line numbers, and per-finding mapping: iteration 1's review files
  (`.pipeline/review-correctness.md`, `.pipeline/review-maintainability.md`) and git history of the
  working tree (this file is gitignored, not committed, so iteration 1's own `changes.md` text itself no
  longer exists on disk — the summary above is what's preserved).

---

## What I did NOT do, and why

- **Did not touch `review-correctness.md` or `review-maintainability.md` territory.** Both lenses shipped
  (97, 91); re-touching `RestoreRepository.kt`, the export/rotation code, or the dialog files risks
  regressing already-verified work for zero benefit, per the brief.
- **Did not add a new interface/seam (e.g., an injectable "process restarter") to make `applyDb` more
  unit-testable.** `mockkConstructor`/`mockkStatic` are sufficient and keep the fix to the one function
  plus its two callers — adding a new Hilt-bound abstraction for this single call site would be exactly
  the kind of broad rework the brief says this iteration is not.
- **Did not build a full post-restart error screen/navigation route.** A `Toast` shown once from
  `MainActivity.onCreate` satisfies "the user still sees why" without growing new UI surface for a
  one-shot, single-line message.
- **Did not remove `ImportViewModel.confirmImport`'s existing `catch (e: Exception)` block**, even though
  `applyDb` no longer relies on it for the swap-failure case — kept as a backstop (see file-by-file above)
  since removing it would be a needless behavior change to code outside the one finding.

---

## Verification (all 7 Item M checks)

Environment for every Gradle run: `$env:JAVA_HOME = "D:\jdk17\jdk-17.0.14+7"`,
`$env:TEMP`/`$env:TMP = "C:\Windows\Temp"`, `$env:GRADLE_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"`,
invoked as `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <script>.ps1` (scripts live in
`.pipeline/`, gitignored).

### 1. Typecheck — PASS (via Kotlin compilation)

No separate typecheck task in this Gradle/Kotlin project; `compileDebugKotlin` +
`compileDebugUnitTestKotlin` are it, both compiled clean as part of the test run below (tail):

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 55s
31 actionable tasks: 31 executed
EXIT CODE: 0
```

No new compiler warnings attributable to this iteration's files (the warnings emitted are the
pre-existing `ExperimentalCoroutinesApi` opt-ins and `LocalLifecycleOwner` deprecation, all in files this
iteration didn't touch).

Two real compile errors were hit and fixed along the way, worth recording: `io.mockk.capture` and
`io.mockk.anyConstructed` don't exist as top-level imports — both are member functions on
`MockKMatcherScope`, resolved implicitly inside `every { }`/`verify { }` blocks with no import needed.
Removing the two bogus `import` lines fixed the build; see Friction Notes.

### 2. Lint — PASS, 0 errors

`gradlew.bat lintDebug assembleDebug`:

```
> Task :app:lintReportDebug
Wrote HTML report to file:///D:/Claude%20Projects/PokedexBinderV2/app/build/reports/lint-results-debug.html
> Task :app:lintDebug
BUILD SUCCESSFUL in 53s
52 actionable tasks: 11 executed, 41 up-to-date
EXIT CODE: 0
```

```
$ grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml
0
$ grep -n 'BackupImporter.kt\|MainActivity.kt' app/build/reports/lint-results-debug.xml
(no matches)
```

My first draft (raw `SharedPreferences.edit().putString().commit()`/`.edit().remove().apply()`) tripped
two `UseKtx` warnings ("Use the KTX extension function `SharedPreferences.edit` instead?"). Since
`core-ktx` was already a dependency, I switched both call sites to the `androidx.core.content.edit { }`
extension rather than leave a warning I'd just introduced; re-running lint confirms zero mentions of
either changed file now, not just zero errors.

### 3. Tests — PASS, 264/264

Stale `app/build/test-results/testDebugUnitTest/` deleted first, `--rerun-tasks`, counted from the
regenerated XML:

```
Deleted stale app\build\test-results\testDebugUnitTest
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 55s
31 actionable tasks: 31 executed
EXIT CODE: 0
```

```
files: 27 total 264 failures 0 errors 0 skipped 0
```

264 = 262 (iteration 1 baseline) + 2 net new. `BackupImporterTest` specifically:

```
<testsuite name="...BackupImporterTest" tests="21" skipped="0" failures="0" errors="0" .../>
```

19 (iteration 1) + 2 new = 21, matching the file-by-file above.

Two intermediate red states worth recording, both the test infrastructure doing its job, not the
production logic:
- First draft called `Intent(context, MainActivity::class.java).apply { addFlags(...) }` for real inside
  `applyDb`, un-mocked — the JVM unit-test `android.jar` stub throws
  `RuntimeException: Method addFlags in android.content.Intent not mocked` for any un-stubbed framework
  call. Fixed with `mockkConstructor(Intent::class)` + stubbing `addFlags`.
- The two bogus imports noted in Check 1.

`androidTest` (instrumented): still **NOT RUN** — the pre-existing Hilt/Kotlin-metadata compile failure
this project's `androidTest` suite has carried since before this feature (verified as pre-existing via a
clean worktree in iteration 1; not re-verified this iteration since nothing about that failure changed
and re-touching it is out of scope). This is exactly why Check 5 below is a real device, not
`connectedDebugAndroidTest`.

### 4. Production build — PASS

```
> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 53s
EXIT CODE: 0
```

(`assembleDebug` is this project's production build task per `CLAUDE.md`; no release signing config to
exercise.)

### 5. App boots — PASS, and this is where the fix was actually proven live

Installed on the `pokedex_test` AVD (`gradlew.bat installDebug` → `Installed on 1 device.`). Baseline cold
start first, confirmed healthy (pid present, 0 fatal exceptions in logcat).

**The specific gap this iteration closes — "does a message survive the forced restart, and does the app
stay usable" — was reproduced with real on-device state, not just mocks.** Driving the exact SAF-picker
trigger condition (pick a `.db`, evict the cached copy mid-confirmation, tap Confirm) isn't reachable
without Espresso, and `androidTest` doesn't compile in this project (Check 3) — so instead I exercised the
real, production `PendingImportError`/`MainActivity` code path directly, using the same technique iteration
1 used for its own P0 (byte-level device fixture manipulation via `adb run-as`, not a mock):

1. `adb shell am force-stop` the app, pushed a fixture `backup_import_state.xml` (containing
   `pending_import_error = "Could not replace the database file (live verification fixture)"`) into the
   app's real `shared_prefs/` directory via `adb push` + `run-as ... cp` (a compound `run-as sh -c` command
   lost its quoting through this Bash→adb layer, as before — separate single-command `run-as` invocations
   work; see Friction Notes).
2. Cold-started the app and screenshotted ~1.5s in:

   The exact fixture message appears as a real `Toast` over the splash screen — `MainActivity.onCreate`'s
   `PendingImportError.consume(this)` genuinely fired against the real on-device `SharedPreferences` file,
   not a test double.

3. Confirmed single-consumption for real: the on-device XML file itself transitioned from containing the
   key to `<map />` after that one cold start —

   ```
   --- shared_prefs file AFTER cold start ---
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map />
   ```

4. Confirmed the app stays fully usable afterward, not just non-crashing: waited past the splash and
   screenshotted the fully-rendered Main Binder screen (real Pokédex data, Gen I grid) with 0 fatal
   exceptions in logcat and the process still alive.
5. Force-stopped and relaunched a second time with the fixture already consumed — confirmed no repeat
   `Toast` and, again, full recovery to the working Main Binder screen with 0 fatal exceptions — proving
   the restart-then-cold-Room-reopen path this fix relies on (Room's own contract: "you must create a new
   database using `RoomDatabase.Builder.build()`") is genuinely sufficient in practice, on the real device,
   not just per the documentation quote.

This is real evidence for the two halves of the fix: (a) the message genuinely reaches the user across a
process restart via the real `SharedPreferences` file and the real `MainActivity`, consumed exactly once;
(b) a process that goes through kill-and-cold-relaunch — which is what `restartProcess()` forces on both
the success and the now-fixed failure path — comes back fully functional, matching Room's documented
guarantee rather than just quoting it.

Emulator left running at end of session (not torn down) — no instruction to stop it and the harness owns
process lifecycle at session end.

### 6. No stray debug statements or scratch files — PASS

```
$ grep -nE "println\(|System\.out|TODO\(|FIXME|debugger" <every file touched this iteration>
(no matches)
```

The two `.ps1` helper scripts (`run_tests.ps1`, `run_build.ps1`, `install_debug.ps1`) and the device
fixture XML live in `.pipeline/` and the session scratchpad respectively; `.gitignore:22` is `.pipeline/`,
confirmed via `git check-ignore -v`, so neither reaches the diff.

### 7. `git status` shows only intended files — PASS

```
 M app/build.gradle.kts                                    <- PRE-EXISTING, not mine
 M app/src/androidTest/.../di/FakeDatabaseModule.kt        <- iteration 1
 M app/src/main/AndroidManifest.xml                        <- iteration 1
 M app/src/main/java/com/skyler/pokedexbinder/MainActivity.kt   <- THIS ITERATION
 M app/src/main/.../data/local/PokedexDatabase.kt          <- earlier feature stage
 M app/src/main/.../data/local/backup/DatabaseBackupManager.kt   <- iteration 1
 M app/src/main/.../data/local/backup/SqliteVersionReader.kt     <- iteration 1
 M app/src/main/.../di/DatabaseModule.kt                   <- iteration 1
 M app/src/main/.../publish/RestoreRepository.kt           <- iteration 1
 M app/src/main/.../ui/settings/SettingsScreen.kt          <- iteration 1
 M app/src/test/.../DatabaseBackupManagerTest.kt           <- iteration 1
 M app/src/test/.../RestoreRepositoryTest.kt               <- iteration 1
 M build_debug.bat / run_build.bat / gaps.md / handoff.md / project-overview.md   <- PRE-EXISTING
?? app/src/main/.../backup/{BackupExporter,BackupImporter,BackupRotation}.kt   <- BackupImporter.kt: THIS ITERATION on top of iteration 1
?? app/src/main/.../publish/model/BackupEnvelope.kt
?? app/src/main/.../ui/settings/{BackupDialogs,BackupViewModel,ImportViewModel}.kt  <- ImportViewModel.kt: THIS ITERATION on top of iteration 1
?? app/src/main/res/xml/            (provider_paths, data_extraction_rules, full_backup_content)
?? app/src/test/.../backup/{BackupExporterTest,BackupImporterTest}.kt        <- BackupImporterTest.kt: THIS ITERATION on top of iteration 1
?? app/src/test/.../ui/settings/    (ImportViewModelTest)
?? .pipeline_archive/ .serena/ CLAUDE.md app/CLAUDE.md docs/specs/*   <- PRE-EXISTING
```

Nothing accidental. Only `MainActivity.kt` is a file this iteration touched that iteration 1 hadn't
already modified/created — expected, since `PendingImportError`'s reader lives there.

### New-dependency check (Item P) — N/A

No manifest changed this iteration: `app/build.gradle.kts` and `gradle/libs.versions.toml` carry no edit
from this pass. `androidx.core.content.edit` came from `androidx-core-ktx`, already a dependency
(`app/build.gradle.kts:79`, pre-existing) — nothing new was added, so the `dependency-management`
evaluation was not invoked.

---

## Friction Notes

- **`io.mockk.capture` and `io.mockk.anyConstructed` are not top-level imports** — both are member
  functions on `MockKMatcherScope`/`MockKMatcherScope`-like receivers, resolved implicitly inside
  `every {}`/`verify {}` without any import. Importing them explicitly is a compile error
  ("Unresolved reference"), and the error message gives no hint that the fix is "delete the import," not
  "add a different one." Worth a standing note: when a mockk DSL function only compiles inside
  `every`/`verify`, don't import it.
- **The Android unit-test stub jar throws on any unstubbed real framework call, even Android classes not
  directly under test.** Constructing a plain `Intent(context, SomeActivity::class.java)` and calling
  `.addFlags(...)` inside code under test is enough to hit
  `RuntimeException: Method addFlags in android.content.Intent not mocked` in a Robolectric-less JVM unit
  test — `mockkConstructor(SomeFrameworkClass::class)` is the fix when there's no other seam and adding one
  would be disproportionate to the fix's scope.
- **`adb shell run-as <pkg> sh -c '...'` still loses its quoting through this Bash→adb layer** (recorded
  in iteration 1 too — confirmed again this iteration). `adb push` to `/data/local/tmp/` + a plain,
  non-compound `adb shell run-as <pkg> cp ...` is the reliable pattern. Needed `MSYS_NO_PATHCONV=1` this
  time too, for the same reason as always: Git Bash rewrites a leading `/data/...` into a Windows path
  before it ever reaches `adb`.
- **A short, targeted live-device reproduction is worth more than a longer mocked one when the finding is
  specifically about OS-boundary behavior** (process death, `SharedPreferences` durability across a real
  restart). The two applied-mock unit tests prove the *code path* is exercised correctly; the device
  fixture round-trip proves the *OS contract* the fix relies on (message survives a real kill, Room reopens
  cold and stays usable) is not just asserted from documentation.
