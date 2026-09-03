# Code Review: Binder Backup — Card History Restore Gap + Local Export/Import

**Reviewer:** Stage 5 Reviewer — lens: **security + reliability only** (3rd and FINAL Reviewer pass, Item I loop cap reached)
**Date:** 2026-09-03
**PR or commit:** working tree on `master` (uncommitted; base `92c5ef9`), no worktree isolation
**Author:** dev-team-pipeline Coder (fix-loop iteration 2 of 2 — final permitted iteration)
**Scope this pass:** the one blocking finding my own iteration-1 pass raised, verified against actual
current source — `BackupImporter.kt`, `MainActivity.kt`, `ImportViewModel.kt`, `BackupImporterTest.kt`
— plus a fresh security/reliability sweep of the fix itself and a spot-check that iteration 1's
already-verified work didn't regress. Unrelated working-tree changes (`graphics-path` dep fix,
`CLAUDE.md` files, `docs/specs/*`, `*.bat`, project docs, `.serena/`) excluded, as in both prior passes.

**Prior-iteration verdict (this reviewer, same lens):** 83/100, needs-changes, exactly one new P1.
Read in full before this pass. This file overwrites it.

---

## Summary

**The blocking finding is genuinely closed, and I verified it three independent ways rather than
one:** by reading the current source and enumerating every statement between `database.close()` and
the process kill, by running the unit suite myself with `--rerun-tasks` after deleting the stale
results dir (264/264, `BackupImporterTest` 21/21, both new regression cases present and green), and
by checking the Tester's live on-device round-trip against what the code actually does.

The Coder took the verdict file's ranked **option 2** — force the same `restartProcess()` on failure
that the success path already used — and added the message-survives-the-restart refinement my own
"minimum to reach ship" list named. The fix is minimal (3 production files, 1 test file; the
`MainActivity` diff is 9 added lines, verified via `git diff`), and I found no scope creep.

**One thing I checked that a naive read would have flagged as the same bug surviving:** `applyDb`
still contains `catch (e: CancellationException) { throw e }` inside the post-close `try`, which
*would* be a hole — a cancellation after `close()` would skip the restart. It isn't reachable.
There is no suspension point anywhere between `database.close()` (line 216) and `restartProcess()`
(lines 239/243): `databaseBackupManager.backupNow(...)` is `fun`, not `suspend`
(`DatabaseBackupManager.kt:57`), `swapInDatabaseFile`/`deleteDbAndSidecars`/`persist`/`restartProcess`
are all plain functions, and the whole body is one blocking block inside a single
`withContext(Dispatchers.IO)`. Cooperative cancellation cannot interleave where there is nothing to
suspend on. That rethrow is inert for this region, not a gap.

Equally worth stating: `backupNow` **cannot** throw — it is a total try/catch returning `Boolean`
(`DatabaseBackupManager.kt:57-79`). So the only statement in the guarded region that can raise is
`swapInDatabaseFile`, which is exactly the case the new tests exercise. Every reachable failure after
the close routes through the restart.

**Security posture:** unchanged and still clean, re-derived rather than re-asserted. The one new
persistence surface this fix introduces (`PendingImportError`'s `SharedPreferences` file) is
`MODE_PRIVATE`, app-private, written synchronously, consumed exactly once — I found two small,
non-blocking wrinkles with it (below), neither exploitable.

**Recommendation:** Ship

**Critical issues (P0):** 0
**Important issues (P1):** 0 — the sole iteration-1 blocker is resolved
**Minor issues (P2):** 0
**Suggestions (P3):** 4 (all non-blocking, all quoted)

---

## Verification of my iteration-1 blocking finding — FIXED, verified three ways

### 1. Every reachable post-close exit restarts (source-derived, not narrative-derived)

`BackupImporter.kt:214-244`:

```kotlin
suspend fun applyDb(preview: ImportPreview.Db): Unit = withContext(Dispatchers.IO) {
    val dbFile = context.getDatabasePath(PokedexDatabase.DB_FILE_NAME)
    database.close()

    // From here on `database` can never be reused in this process. Every exit below — success
    // or failure — must therefore restart: leaving this closed instance in place while control
    // returns to the caller is exactly the bug this structure exists to prevent.
    try {
        databaseBackupManager.backupNow(context, PokedexDatabase.DB_FILE_NAME, reason = "preimport")
        try {
            swapInDatabaseFile(source = preview.tempFile, target = dbFile)
        } finally {
            deleteDbAndSidecars(preview.tempFile)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PendingImportError.persist(
            context,
            e.message ?: "Could not replace the database file — your data is unchanged"
        )
        restartProcess()
        return@withContext
    }

    restartProcess()
}
```

Statement-by-statement audit of the guarded region, which is what the finding actually required:

| Statement | Can it throw? | Routes to restart? |
|---|---|---|
| `databaseBackupManager.backupNow(...)` | **No** — total `try { ... } catch (e: Exception) { Log.e(...); false }`, `DatabaseBackupManager.kt:57-79` | n/a |
| `swapInDatabaseFile(...)` | Yes — rethrows as `IOException` (`BackupImporter.kt:281-286`) | Yes, `catch (e: Exception)` → persist → `restartProcess()` |
| `deleteDbAndSidecars(...)` (in `finally`) | No — `File.delete()` returns `Boolean`, never throws | n/a |
| `CancellationException` | Not reachable — no suspension point exists between `close()` and the kill (see Summary) | inert |

`restartProcess()` (`BackupImporter.kt:247-253`) is one shared private function called by both
branches, so success and failure cannot drift apart:

```kotlin
private fun restartProcess() {
    val restartIntent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(restartIntent)
    Process.killProcess(Process.myPid())
}
```

The `return@withContext` after the failure branch's `restartProcess()` is load-bearing in tests
(where `killProcess` is mocked and control continues) and moot in production — correct either way.

### 2. The persisted-message mechanism is safe on the write side

`BackupImporter.kt:51-71`:

```kotlin
object PendingImportError {
    private const val PREFS_NAME = "backup_import_state"
    private const val KEY_MESSAGE = "pending_import_error"

    internal fun persist(context: Context, message: String) {
        // commit = true: synchronous, since the process is about to be killed and the async
        // apply() would race it.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putString(KEY_MESSAGE, message)
        }
    }
```

Checked, and each one holds:
- **Synchronous before the kill** — `edit(commit = true)` is `commit()`, which returns only after
  the disk write. `persist(...)` is called *before* `restartProcess()` in source order, so there is
  no race with `Process.killProcess`. An `apply()` here would have been a genuine bug; it isn't one.
- **No cross-process race** — the app is single-process; the writer dies before the reader's process
  exists. `MODE_PRIVATE` (0) with no `MODE_MULTI_PROCESS` is correct for that shape.
- **Consumed exactly once** — `consume` (`BackupImporter.kt:65-70`) reads then `remove`s in the same
  call, and `MainActivity.onCreate` is its only caller (grepped: `PendingImportError` appears at
  `BackupImporter.kt:51,211,235` and `MainActivity.kt:11,27`, nowhere else).
- **No leak surface** — the file lives in the app's private `shared_prefs/`, is not exported, and is
  never passed to a `FileProvider` (`provider_paths.xml` grants only `files-path name="exports"`,
  byte-identical to the version I cleared in both prior passes).

### 3. Independently re-run, not taken on trust

Per this project's own rule that verifying another stage's build claim needs `--rerun-tasks`, I
deleted `app/build/test-results/testDebugUnitTest/` and re-ran the suite myself:

```
BUILD SUCCESSFUL in 1m 6s
31 actionable tasks: 31 executed
EXIT CODE: 0
files: 27 total 264 failures 0 errors 0 skipped 0
BackupImporterTest tests=21 failures=0 errors=0
  CASE: applyDb forces a process restart even when the swap fails, instead of returning to the caller with a closed database
  CASE: applyDb persists the failure message for MainActivity to show after the forced restart
```

(First attempt died on a Windows file lock — `FileSystemException: ...classes.jar: The process
cannot access the file because it is being used by another process` on
`:app:bundleDebugClassesToRuntimeJar`. Environmental, not code: `gradlew --stop` + retry passed
clean. Captured as a lesson, see Friction Rollup.)

The tests themselves assert the regression, not a proxy — `BackupImporterTest.kt:350-356`:

```kotlin
verify { database.close() }
assertEquals("live bytes", dbFile.readText())
verify { context.startActivity(any()) }
verify { Process.killProcess(4242) }
```

and `BackupImporterTest.kt:374-379` asserts `putString` **plus `commit()`** — i.e. the synchronous
write specifically, not just that something was persisted. That is the right assertion for this
mechanism. `tearDown` calls `unmockkAll()` (`:96`), so the `mockkStatic(Process)`/
`mockkConstructor(Intent)` interception can't leak into sibling tests.

The Tester's live device round-trip is consistent with what the code does and adds the one thing
unit tests structurally cannot: that a real process kill → cold start → real `SharedPreferences`
read shows the message once, clears the key on disk (`<map />` after), and comes back to a
fully-rendered Room-backed screen. I did not re-run the device sequence myself; I checked that each
claimed step corresponds to real code and that the fixture text differs from the Coder's, which is
what makes it an independent derivation rather than a re-read.

---

## Iteration-1 work — spot-checked, no regression

- `SqliteVersionReader.kt` still has no sidecar deletion for any caller; sidecar cleanup is owned by
  `BackupImporter.deleteDbAndSidecars`/`deleteSidecars` (`:292-300`). The original P0 stays fixed.
- `swapInDatabaseFile` (`:270-290`) still stages at `.importing` and moves with
  `REPLACE_EXISTING, ATOMIC_MOVE`, deleting the staging file on failure.
- `REQUIRED_TABLES` identity gate (`:38`, checked at `:156-162`) and the lower-bound migration-path
  gate (`:173-182`) both intact.
- Credentials still structurally excluded: `BackupEnvelope` carries no credential-shaped field and
  `PublishConfig` is only ever an input toggle at `BackupExporter.kt:86-95` — unchanged this pass.
- `git diff` on the only tracked file this iteration touched (`MainActivity.kt`) is 9 added lines: one
  import, one comment block, one `consume(...)?.let { Toast }`. No scope creep.

---

## Findings

### P3-1 — `database.close()` sits outside the `try`, so a throw from `close()` itself still escapes without a restart

- **File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/BackupImporter.kt:216`
- **Confidence:** 5 (verify this — I can quote the structural gap, but I could not demonstrate a
  concrete `RoomDatabase.close()` failure)
- **Category:** Reliability / error handling

```kotlin
    val dbFile = context.getDatabasePath(PokedexDatabase.DB_FILE_NAME)
    database.close()

    // From here on `database` can never be reused in this process. Every exit below — success
    // or failure — must therefore restart
    try {
```

The comment says "every exit below", and that is accurate — but `close()` is *above* the `try`.
Room's `close()` takes a write lock, calls `invalidationTracker.stopMultiInstanceInvalidation()` then
`openHelper.close()`; if either raised, the instance would be in the same half-dead state the whole
finding is about, with no persist and no restart. I have no concrete scenario that makes it throw,
which is why this is confidence 5 and P3 rather than a blocker. Moving `database.close()` to be the
first statement *inside* the existing `try` would close it for one line of diff and no behavior
change on any path that works today.

### P3-2 — the new `SharedPreferences` file is not excluded from Auto Backup / device transfer, so an unconsumed error message can be restored onto a different install

- **Files:** `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/full_backup_content.xml`,
  against `BackupImporter.kt:52` (`private const val PREFS_NAME = "backup_import_state"`)
- **Confidence:** 8
- **Category:** Reliability / data hygiene (security-adjacent, not exploitable)

Both new rules files exclude the `file` domain only:

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="exports/" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="exports/" />
    </device-transfer>
</data-extraction-rules>
```

`shared_prefs/backup_import_state.xml` is in the `sharedpref` domain, which is included by default
("Only exclusions are listed, so everything else ... keeps the default include behaviour", per the
file's own header comment). So if a swap fails and the message is persisted but never consumed —
reachable if `startActivity` is suppressed and the user doesn't relaunch before a backup runs — that
message can be uploaded to cloud backup and restored onto a *new device or reinstall*, where it
surfaces as a contextless `Toast` about a database failure that never happened on that install.
Low impact, but it is exactly the "stale state across unrelated app runs" class, and the fix is one
line in each of the two files this feature already added:
`<exclude domain="sharedpref" path="backup_import_state.xml" />`.

### P3-3 — the persisted/toasted message interpolates the underlying exception, which for file errors is an absolute internal path

- **File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/BackupImporter.kt:283-285`,
  surfaced at `MainActivity.kt:27-29`
- **Confidence:** 7
- **Category:** Error handling — messages leaking internals

```kotlin
            throw IOException(
                "Could not replace the database file — your data is unchanged (${e.message})", e
            )
```

`e` here is typically a `kotlin.io.NoSuchFileException` / `java.nio.file.FileSystemException`, whose
`message` is built from the file path — so the user-visible `Toast` (and the on-disk prefs value)
becomes something like `... (/data/user/0/com.skyler.pokedexbinder/databases/pokedex_binder.db.importing:
The source file doesn't exist.)`. Not exploitable (Android 11+ blocks other apps from reading text
toasts; the prefs file is app-private) and arguably useful for a single-user personal app — but the
raw cause belongs in `Log.e(...)`, with the user-facing half being the already-written friendly
prefix. Worth deciding deliberately rather than by accident.

### P3-4 — `backupNow`'s return value is discarded, so the swap proceeds even when the recovery copy provably failed (pre-existing, not introduced here)

- **File:** `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/BackupImporter.kt:226`
- **Confidence:** 8 for the fact, 5 for the practical impact
- **Category:** Reliability

```kotlin
            databaseBackupManager.backupNow(context, PokedexDatabase.DB_FILE_NAME, reason = "preimport")
```

`backupNow` returns `Boolean` (`DatabaseBackupManager.kt:57`) and swallows every failure into
`false` + a log line. The comment two lines above calls the swap "the only irreversible operation in
the feature" — yet the code proceeds to it without checking whether the recovery copy it just took
actually exists. In the common failure mode (disk full) the staging copy fails too and nothing is
lost, which is why impact confidence is only 5; a `db_backups/` `mkdirs()` failure is the narrow case
where the swap succeeds with no recovery copy.

**Explicitly not blocking:** this is iteration-1 code, unchanged by this pass, and was not raised in
either of my prior two passes. Raising it as a blocker on the final permitted iteration would be
moving the goalposts. Follow-up, not a gate.

### Appendix (suppressed from the main list per Phase 5 — confidence 3-4)

- `catch (e: Exception)` does not catch `Error`; an `OutOfMemoryError` during the staging copy would
  skip the restart. No concrete scenario, confidence 4.
- If `context.startActivity(...)` itself threw inside `restartProcess()`, the exception would escape
  from within the `catch` block and `killProcess` would never run — the original failure mode. With
  `FLAG_ACTIVITY_NEW_TASK` set and `MainActivity` in the manifest, I have no scenario that makes it
  throw (background-start restrictions no-op and log, they don't throw). Confidence 3.
- `consume`'s `prefs.edit { remove(KEY_MESSAGE) }` uses the KTX default (`apply()`, async). A crash in
  the same millisecond could in principle replay the toast once. Confidence 4, cosmetic.

---

## What looks good

- **The fix is the right shape, and the reasoning for rejecting option 1 is sound.** `changes.md`
  argues that deferring `close()` past the swap would only *narrow* the window (a `backupNow` failure
  between close and swap would still hit it) rather than eliminate it. That reasoning is correct as
  far as it goes and is now moot in a better way than the Coder claimed — `backupNow` can't throw at
  all, so the window is a single statement. Restart-on-any-post-close-failure is still the right
  choice because it is invariant to future edits inside that block.
- **`restartProcess()` extracted and shared by both branches** — the success and failure restarts
  cannot drift apart, which is the kind of thing that rots when duplicated.
- **The KDoc now states the real contract** (`:203-213`): "Does not return normally on a real device,
  **on success or failure**". A function that kills its own process needs exactly that warning, and
  `ImportViewModel.kt:74-77` carries the matching note at the call site.
- **The backstop `catch` in `ImportViewModel` was kept, not deleted**, and correctly re-documented as
  a backstop. Removing it would have been the tidier-looking, worse choice.
- **Test evidence is real.** `verify { Process.killProcess(4242) }` with a stubbed `myPid()` returning
  4242 proves the *specific* pid was killed, not that some kill happened; `verify { ...commit() }`
  proves synchrony specifically. Both are the assertion that would fail if the fix regressed.
- **The Tester's own disclosed gap** ("no test exercises a *second consecutive* `.db` import failure")
  is honest and correctly reasoned — each `persist()` overwrites the single key, so the worst case is
  showing the more recent message.

---

## Dimension scorecard (my lens only)

| Dimension | Pass | Issues |
|---|---|---|
| Security | ☑ | Provider scope minimal, credentials structurally excluded, new prefs file app-private and single-consumption. Two P3s (backup-domain exclusion, path in message), neither exploitable |
| Reliability | ☑ | The iteration-1 blocker is closed on every reachable path; one structural P3 (`close()` outside the try) and one pre-existing P3 (`backupNow` result ignored) |
| Correctness | — | Other lens; the fix's control flow re-derived here as part of the reliability check |
| Performance | — | Not in lens; nothing observed |
| Maintainability | — | Other lens (shipped at 91 last iteration; nothing this pass touches its findings) |

---

## Score

| Dimension | Points | Reasoning |
|---|---|---|
| **Spec compliance** | **25** / 25 | (prior: 21) My −4 last pass was specifically that the spec's own risk-table mitigation — "force a full process restart ... rather than continuing in-process" — was honored only on the success path. It is now honored on both, through one shared `restartProcess()`. Nothing else spec-mandated is missing, and the diff is 3 production files + 1 test file with no scope creep (`MainActivity.kt`'s `git diff` is 9 added lines, verified). |
| **Correctness & bug-freedom** | **23** / 25 | (prior: 20) The bug is genuinely gone: I enumerated every statement in the guarded region and confirmed the only one that can throw routes to the restart, that `backupNow` cannot throw at all, and that the `CancellationException` rethrow is inert because no suspension point exists between the close and the kill. Two new regression tests assert the exact composition, and I ran them myself. −2 for P3-1 (`close()` outside the `try` leaves one structurally-unguarded statement, confidence 5) plus the confidence-3/4 appendix items. |
| **Security & reliability** | **18** / 20 | Security ~9/10: re-derived, not re-asserted — provider scope unchanged, credentials structurally absent, the new prefs file `MODE_PRIVATE`/app-private/consumed-once with `commit()` before the kill (no race, verified in source order and asserted by a test). −1 for P3-2 (`sharedpref` domain not excluded from cloud backup/device transfer, so a stale message can travel to another install) and P3-3 (internal path interpolated into a user-facing toast). Reliability ~9/10, up from ~6.5: the highest-risk path in the feature now restarts on every reachable failure and the live round-trip proves the app is genuinely usable after that restart. −1 for P3-1 and P3-4. |
| **Maintainability & simplification** | **14** / 15 | (prior: 14, unchanged — this lens defers to the maintainability lens, which shipped at 91.) From what I touched: the fix is minimal and reuses the existing restart path rather than adding a parallel one; `restartProcess()` extraction prevents drift; the KDoc contract is accurate. −1: the iteration-1 residual is still there (`DatabaseBackupManager` instantiated twice with two lifecycles, `DatabaseModule.kt:39` vs `:63`, still uncommented), and `PendingImportError` is a global `object` with a static Context-taking API living inside `BackupImporter.kt` — defensible for a cross-restart mechanism, but it is a second public type in a file named after the first. |
| **Test evidence quality** | **14** / 15 | (prior: 12) The exact gap I named last pass — "none of these tests compose a real `PokedexDatabase.close()` with a subsequent swap failure through the real `applyDb`" — is closed by two tests that do precisely that, and I re-ran the suite independently (stale results deleted, `--rerun-tasks`, counted from regenerated XML: 264/264, `BackupImporterTest` 21/21). The assertions are the discriminating ones (`killProcess(4242)` with a stubbed pid; `commit()` not just `putString`), and `unmockkAll()` prevents leakage. −1: `database` is a relaxed `mockk`, so `close()` is verified as a call rather than as real Room behavior, and the second-consecutive-failure case is disclosed-but-unasserted. Both honestly stated by the Tester rather than papered over. |
| **Total** | **94** / 100 | |

**Prior iteration: 83/100. This pass: 94/100 — up 11 points.** The one blocking finding is resolved
and independently re-verified; the remaining 6 points are four P3s, none of which gates a ship.

---

## Verdict

**ship**

Score 94 ≥ the 85 ship threshold, and there is no unresolved P0 or P1 on this lens. The iteration-1
blocker — a failed `.db` swap leaving the shared Room singleton permanently closed with the process
still running — is closed for every reachable failure path, proven by source enumeration, by two
regression tests I ran myself after forcing re-execution, and by a live on-device round-trip whose
steps map to real code.

The four P3s are follow-ups, not gates. If any one is worth doing before commit, it is **P3-1** — one
line, move `database.close()` inside the existing `try` — and **P3-2**, two lines adding a
`sharedpref` exclusion to the rules files this feature already introduced. Neither changes behavior
on any path that works today, and I am not making the ship conditional on them.

---

## Friction Rollup (Item F — once per pipeline run, consolidated)

Read the `## Friction Notes` sections of `.pipeline/specs.md`, `.pipeline/changes.md`,
`.pipeline/test-results.md`, and `.pipeline/debug-report.md`, plus my own this stage. Checked each
against `~/.claude/rules/lessons.md` before writing anything.

**Already captured, no action:** Serena Kotlin LSP init failure (→ `fallback-when-language-server-tool-fails-init`),
`adb` path mangling from Git Bash (→ `adb-from-git-bash-path-mangling`), `run-as sh -c` quoting loss
and `am start` vs `monkey` timing (→ `adb-run-as-loses-quoting-on-compound-commands`), mockk DSL
imports (→ `mockk-dsl-functions-are-not-importable`), the stub `android.jar` throwing on
self-constructed framework objects (→ `android-unit-test-stubs-throw-on-transitive-framework-calls`),
"don't trust a prior session's environment claim" (→ CLAUDE.md §6 / `verify-before-acting`).

**Two new lessons written:**
- `~/.claude/rules/lessons/gradle-rerun-tasks-can-fail-on-a-locked-jar.md` — my own this stage.
  `--rerun-tasks` died on `FileSystemException: ...classes.jar: being used by another process`
  (`:app:bundleDebugClassesToRuntimeJar`); `gradlew --stop` + retry fixed it. Direct companion to the
  existing `delete-stale-test-results-dir-before-rerun` lesson, which mandates the very flag that
  provokes this lock.
- `~/.claude/rules/lessons/an-existing-green-test-can-encode-the-behavior-youre-changing.md` — from
  `specs.md`'s note that adding the Card History branch made an existing *passing* test
  (`non-pokedex binder in snapshot is ignored`) assert something false. Generalizable: when a change
  removes an "X isn't supported yet" limitation, grep the test sources for negative-named assertions
  first.

Both added to the index in `~/.claude/rules/lessons.md` under "Testing & verification mechanics".

---

## Sign-off

**Approval status:** Approved
**Date:** 2026-09-03
**Lens:** security + reliability
**Score delta vs. prior pass:** 83 → 94 (+11)
