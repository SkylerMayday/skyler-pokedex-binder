# Debug Report: Binder Backup — orphaned `-shm`/`-wal` sidecars + Cancel-path temp `.db` leak

Both are implementation bugs in the Coder's Binder Backup diff, not a spec gap — `docs/specs/2026-08-09-binder-backup-export-import.md` never asked for these cleanup paths explicitly, but AC7's "nothing touched until explicit confirm" plainly implies the *reverse* is also true: nothing should be **left behind** by the version-check/preview phase either, whatever the outcome. Both are cleanup omissions in a normal, spec-anticipated code path, fixed at the exact place each temp resource is created/held.

---

## Bug 1 — `FrameworkSqliteVersionReader` leaks `-shm`/`-wal` sidecars on every `.db` pick

### Reproduction
- Expected: after `BackupImporter.prepare()` version-checks a picked `.db` (accept, reject, or unreadable-file), the app's `cacheDir` should contain no residue beyond whatever the caller explicitly decided to keep.
- Actual: every `.db` pick left a `<tempfile>.db-shm` (32768 bytes) and `<tempfile>.db-wal` (0 bytes) pair behind permanently, regardless of accept/reject outcome.
- Steps (Tester's original repro, reproduced live again pre-fix during this investigation is unnecessary — Tester's `adb shell run-as ... ls -la cache/` already showed 4 orphaned pairs after 2 rejections + 1 accept + 1 cancel): Settings → Restore from file → pick any `.db` → observe `cache/` afterward.

### Root Cause
`FrameworkSqliteVersionReader.readVersion()` (`app/src/main/java/com/skyler/pokedexbinder/data/local/backup/SqliteVersionReader.kt`) opens the temp copy via `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` purely to read `PRAGMA user_version`. Every real backup `.db` is exported from a WAL-mode Room database, so SQLite creates `-shm`/`-wal` sidecars next to the file the moment it's opened — even read-only, even just to peek at one pragma. `readVersion()`'s `.use { it.version }` closes the `SQLiteDatabase` handle correctly, but closing it does not delete the sidecar files SQLite created as a side effect of opening. Nothing downstream ever revisited them: `prepareDb()`'s rejection branches call `tempFile.delete()` on the *main* file only, and `applyDb()`'s success path likewise only deletes the main file after copying it — both were written as if `readVersion()` were side-effect-free, which it isn't. This is the sole call site in the codebase that opens a `.db` this way, so it's the only place that can reliably know the sidecars exist and clean them up — not a bug that a caller-side fix could close structurally, since every future caller of `readVersion()` would face the identical leak.

### Fix
`app/src/main/java/com/skyler/pokedexbinder/data/local/backup/SqliteVersionReader.kt` — `FrameworkSqliteVersionReader.readVersion()` now deletes `dbFile.path + "-wal"` and `dbFile.path + "-shm"` in a `finally` block, so cleanup runs on every outcome (successful read, exception, accepted, or rejected) rather than only on paths a caller happened to add cleanup to. This is the "fix at the source, not the symptom" version: instead of patching `prepareDb()`'s two divergent cleanup branches (reject vs. accept) to each separately also delete sidecars, the single method that creates them is now also the single method responsible for removing them — structurally impossible to reintroduce this leak from a new caller.

```kotlin
override fun readVersion(dbFile: File): Int? {
    if (!dbFile.exists()) return null
    return try {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { it.version }
    } catch (e: Exception) {
        android.util.Log.w("SqliteVersionReader", "Could not read on-disk version for ${dbFile.name}", e)
        null
    } finally {
        deleteSidecars(dbFile)
    }
}

private fun deleteSidecars(dbFile: File) {
    File(dbFile.path + "-wal").delete()
    File(dbFile.path + "-shm").delete()
}
```

---

## Bug 2 — `ImportViewModel.dismiss()` leaks the entire temp `.db` on Cancel

### Reproduction
- Expected: tapping **Cancel** on the "Restore from file? This replaces your current binder data." confirmation dialog discards the pending preview, including any temp file backing it.
- Actual: the full temp copy (167,936 bytes for the Tester's fixture) plus its sidecars remained in `cache/` indefinitely — same file whether the user proceeds (cleaned up post-copy by `applyDb()`) or backs out (never cleaned up at all).
- Steps: Settings → Restore from file → pick a valid `.db` → confirmation dialog appears → tap **Cancel** → check `cache/`.

### Root Cause
`ImportViewModel.dismiss()` (`app/src/main/java/com/skyler/pokedexbinder/ui/settings/ImportViewModel.kt`) is the Cancel button's `onClick` handler (confirmed via `SettingsScreen.kt` line 92: `TextButton(onClick = { importVm.dismiss() }) { Text("Cancel") }`). Its body was `_state.value = ImportUiState.Idle` — an unconditional reset that discards the `ImportUiState.NeedsConfirmation(preview)` object (and, with it, the only remaining reference to `ImportPreview.Db.tempFile`) without ever inspecting what it held. `BackupImporter` had no method to clean up a not-yet-applied preview at all — the class only knew how to *apply* previews (`applyJson`/`applyDb`), never how to *discard* one. The confirm path (`applyDb()`) already deletes `preview.tempFile` after copying it, so the leak was specifically the cancel path's asymmetry: one branch of the same decision cleans up, the other silently drops the reference.

### Fix
Two-file change, following the same "fix where the resource is owned" principle as Bug 1:

**`app/src/main/java/com/skyler/pokedexbinder/data/local/backup/BackupImporter.kt`** — added `discardPreview(preview: ImportPreview)`, giving `BackupImporter` (the class that already owns `applyJson`/`applyDb`) a matching "abandon" operation:
```kotlin
suspend fun discardPreview(preview: ImportPreview): Unit = withContext(Dispatchers.IO) {
    if (preview is ImportPreview.Db) {
        preview.tempFile.delete()
    }
}
```

**`app/src/main/java/com/skyler/pokedexbinder/ui/settings/ImportViewModel.kt`** — `dismiss()` now inspects the state it's discarding before resetting it, and delegates cleanup instead of dropping the reference:
```kotlin
fun dismiss() {
    val current = _state.value
    if (current is ImportUiState.NeedsConfirmation) {
        viewModelScope.launch { backupImporter.discardPreview(current.preview) }
    }
    _state.value = ImportUiState.Idle
}
```
`discardPreview` is a no-op for `ImportPreview.Json` (no temp file to clean), so `dismiss()` can call it unconditionally for any `NeedsConfirmation` state without the ViewModel needing to know which preview type is currently pending — that decision stays inside `BackupImporter`, next to the other preview-type-specific logic.

---

## Blast radius
3 production files touched (`SqliteVersionReader.kt`, `BackupImporter.kt`, `ImportViewModel.kt`), well under the 5-file gate. 2 test files touched/added (`BackupImporterTest.kt` extended, `ImportViewModelTest.kt` new) — no unrelated code touched, no "while I'm here" changes.

## Prevention (regression tests added)
- `BackupImporterTest.kt` — `discardPreview deletes the db preview's temp file` and `discardPreview is a no-op for a json preview`. Covers the new method's logic directly (pure file I/O, no Android framework dependency, so genuinely testable in this project's Robolectric-less JVM unit suite — unlike `FrameworkSqliteVersionReader` itself, whose real `SQLiteDatabase.openDatabase` call cannot run outside a real device/emulator here, which is exactly why Bug 1 was invisible to the existing unit suite in the first place and why this stage's live-device verification below is the evidence that actually matters for Bug 1).
- `ImportViewModelTest.kt` (new file — none existed for this ViewModel before) — three tests: `dismiss` from `Idle` doesn't call `discardPreview`; `dismiss` from `NeedsConfirmation` with a `.db` preview delegates to `discardPreview`; same for a JSON preview (proving the ViewModel delegates unconditionally rather than branching on preview type itself).

---

## Verification

### Unit tests — fresh run, verbatim evidence
Command: `gradlew.bat testDebugUnitTest --rerun-tasks` (fresh `$env:JAVA_HOME`/`$env:TEMP`/`$env:TMP`), stale `app/build/test-results/testDebugUnitTest/` deleted first, exactly per project lesson.

```
> Task :app:testDebugUnitTest
[Incubating] Problems report is available at: file:///D:/Claude%20Projects/PokedexBinderV2/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 1m 25s
31 actionable tasks: 31 executed
EXIT CODE: 0
```

Counted directly from the regenerated XML (not the Gradle summary):
```
files: 27
total 247 failures 0 errors 0 skipped 0
```
242 (Tester's baseline) + 2 (`BackupImporterTest`'s new `discardPreview` tests) + 3 (`ImportViewModelTest`, net-new file) = 247. Per-class confirmation from the same fresh XML set:
```
TEST-com.skyler.pokedexbinder.data.local.backup.BackupImporterTest.xml   tests="11" failures="0" errors="0"
TEST-com.skyler.pokedexbinder.ui.settings.ImportViewModelTest.xml        tests="3"  failures="0" errors="0"
```

### Live device — fresh evidence, `pokedex_test` AVD, same emulator the Tester used
Build+install: `gradlew.bat installDebug --rerun-tasks` →
```
> Task :app:installDebug
Installing APK 'app-debug.apk' on 'pokedex_test(AVD) - 16' for :app:debug
Installed on 1 device.
BUILD SUCCESSFUL in 1m 14s
42 actionable tasks: 42 executed
EXIT CODE: 0
```

Cleared `cacheDir` to a known-empty baseline, then re-ran the exact repro steps from `.pipeline/test-results.md` §5:

**1. Reject case** (`newer_schema.db`, schema v999) — picked via the real SAF picker, got the expected rejection dialog. `adb shell run-as com.skyler.pokedexbinder ls -la cache/` immediately after:
```
total 20
drwxrws--x 2 u0_a217 u0_a217_cache 4096 2026-09-02 18:26 .
drwx------ 7 u0_a217 u0_a217       4096 2026-08-26 14:09 ..
-rw------- 1 u0_a217 u0_a217_cache    0 2026-09-02 18:25 pokedex_binder.db.lck
```
Zero orphaned sidecars (Tester's original run left 1 `-shm`/`-wal` pair per rejection). **Bug 1 fixed for the reject path.**

**2. Cancel case** (`pokedex-test-backup.db`, valid schema), run twice in a row to confirm no accumulation:

Mid-flight (dialog showing, before tapping Cancel), cache correctly holds the pending temp file and *no* sidecars (confirming Bug 1's fix already applies to this path too):
```
total 188
-rw------- 1 u0_a217 u0_a217_cache 167936 2026-09-02 18:28 import_temp_1788373689307.db
-rw------- 1 u0_a217 u0_a217_cache      0 2026-09-02 18:25 pokedex_binder.db.lck
```
After tapping Cancel:
```
total 20
drwxrws--x 2 u0_a217 u0_a217_cache 4096 2026-09-02 18:28 .
drwx------ 7 u0_a217 u0_a217       4096 2026-08-26 14:09 ..
-rw------- 1 u0_a217 u0_a217_cache    0 2026-09-02 18:25 pokedex_binder.db.lck
```
The 167,936-byte temp file is gone — matches the exact fixture size Tester reported leaking. **Bug 2 fixed.**

Repeated the identical pick→confirm→Cancel cycle a second time: mid-flight cache held `import_temp_1788373759434.db` (167,936 bytes, no sidecars); after the second Cancel, cache was back to just `pokedex_binder.db.lck` — **no accumulation across repeated cycles** (Tester's original run had accumulated 4 orphaned pairs after 4 picks; this run shows 0 after 2 reject/cancel cycles plus the accept cycle below).

**3. Accept case** (regression check — same `pokedex-test-backup.db`, tapped **Restore** instead of Cancel), confirming the fix didn't disturb the already-working accept path: `pidof com.skyler.pokedexbinder` before → `6266`, after → `6545` (process genuinely killed/restarted). Post-restart cache:
```
total 20
drwxrws--x 2 u0_a217 u0_a217_cache 4096 2026-09-02 18:30 .
drwx------ 7 u0_a217 u0_a217       4096 2026-08-26 14:09 ..
-rw------- 1 u0_a217 u0_a217_cache    0 2026-09-02 18:30 pokedex_binder.db.lck
```
`adb logcat -d *:E | grep -iE "FATAL EXCEPTION|AndroidRuntime|pokedexbinder.*(Exception|Error)"` → no matches (no crash). App resumed to a normal, functioning home screen post-restart (screenshot confirmed).

---

## Status

DONE — both root causes identified and fixed at the source (not just the easiest branch), defense-in-depth applied (sidecar cleanup runs on every `readVersion()` outcome via `finally`, not just the branches a caller remembered to add it to), regression tests added and passing (247/247, 0 failures/errors, fresh XML), and both leaks independently re-verified live on the `pokedex_test` emulator across reject, cancel (×2, no accumulation), and accept flows — with the accept flow's process-kill/restart and crash-freedom also re-confirmed as a regression check.

## Friction Notes

- Serena's Kotlin language server failed to initialize for this project on every symbolic tool call this session (`get_symbols_overview`, `replace_content` — "Error extracting archive"), forcing a fallback to plain `Read`/`Grep`/`Edit` for the entire investigation and fix. Worth checking whether this is a one-off environment issue or a standing problem with this project's Serena setup before the next debugging session assumes semantic tools will be available.
- `adb` is not on `PATH` in this Bash environment and must be invoked via its full SDK path; more importantly, passing device-absolute paths like `/sdcard/Download/...` as `adb push` destination arguments through Git Bash gets silently mangled by MSYS path conversion (rewritten to `C:/Program Files/Git/sdcard/Download/...`) unless `MSYS_NO_PATHCONV=1` is set — and even then, calling `adb.exe` directly from Bash (not through a nested `powershell.exe -Command`) was simpler and avoided the binary-stdout corruption issue the Tester's own friction notes already flagged. Ended up not needing `adb push` at all this session since the Tester's fixture files were still present on the same long-lived AVD from the prior session — worth remembering that `pokedex_test`'s `/sdcard/Download` persists across pipeline stages within the same emulator instance, so re-pushing known-good fixtures is often unnecessary.
