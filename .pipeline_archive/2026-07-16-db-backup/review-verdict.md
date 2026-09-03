# Stage 5 (Reviewer) — Verdict

**Reviewed:** pre-migration automatic local DB backup safety net (`data/local/backup/` package +
`DatabaseModule.kt`/`PokedexDatabase.kt` wiring), against `.pipeline/specs.md`,
`.pipeline/changes.md`, `.pipeline/test-results.md`, and the actual working-tree diff.

**Verdict: SHIP** — the code is correct, matches the spec, and is adequately tested for what a JVM
sandbox with no emulator can prove. One documentation-sync action item should be completed before
the session is considered closed (non-blocking on the code itself, but explicitly required by this
project's own standing conventions and by the spec's own release checklist). No code changes
requested.

---

## Scope reviewed

- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/SqliteVersionReader.kt` (new)
- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/MigrationPathResolver.kt` (new)
- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/DatabaseBackupManager.kt` (new)
- `app/src/main/java/com/skyler/pokedexbinder/data/local/PokedexDatabase.kt` (modified)
- `app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt` (modified)
- `app/src/test/java/.../backup/MigrationPathResolverTest.kt` (new, 5 tests)
- `app/src/test/java/.../backup/DatabaseBackupManagerTest.kt` (new, 9 tests)
- `app/src/androidTest/java/.../backup/DatabaseBackupManagerInstrumentedTest.kt` (new, 2 tests, compiled/unrun)

Independently re-ran (not just trusted from `changes.md`/`test-results.md`):
- `.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin` → **BUILD SUCCESSFUL**.
- Parsed `app/build/test-results/testDebugUnitTest/*.xml` myself: **23 files, 182 tests, 0
  failures/errors** — matches both prior stages' claims exactly, confirmed independently rather
  than re-trusted.

## Intent audit (Phase 2)

Traced every spec requirement (§4 architecture, §5 edge cases, §7 task table) to actual diff
content:

| Spec item | Status |
|---|---|
| `SCHEMA_VERSION`/`ALL_MIGRATIONS` constants, annotation points at constant | DONE — `PokedexDatabase.kt` lines 18, 30, 124-126 |
| `SqliteVersionReader` + `FrameworkSqliteVersionReader` | DONE — matches spec's code verbatim |
| `MigrationPathResolver` (BFS) | DONE — matches spec's code verbatim |
| `DatabaseBackupManager` (check → copy → rotate, try/catch swallow) | DONE — matches spec's code verbatim |
| Wire into `DatabaseModule.provideDatabase` before `.build()` | DONE — confirmed correct ordering (see Correctness below) |
| Both destructive fallbacks guarded symmetrically | DONE — confirmed, see below |
| JVM tests for resolver + manager | DONE — 14 tests, all edge cases in spec §5/§6 traced to a specific test in `test-results.md`'s table and spot-checked directly by me in the test source |
| Instrumented test | DONE (compiles, unrun — matches stated sandbox constraint, consistent with `Migration8to9Test` precedent) |
| **`gaps.md` flagged per §8 checklist** ("flag in `gaps.md` after implementation exactly as `Migration8to9Test` was flagged") | **NOT DONE — see Finding 1** |

No scope creep found — the `DB_FILE_NAME` constant introduced in `DatabaseModule.kt` (replacing
two repeated `"pokedex_binder.db"` literals) is in-scope, directly serves the same call site being
modified, not a "while I was in there" expansion.

## Correctness — the ordering question (the whole point of this feature)

Confirmed by direct read of `DatabaseModule.kt` lines 22-37:

```kotlin
fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase {
    DatabaseBackupManager().backupIfDestructiveMigrationImminent(
        context = context, dbFileName = DB_FILE_NAME,
        targetVersion = PokedexDatabase.SCHEMA_VERSION, migrations = PokedexDatabase.ALL_MIGRATIONS
    )
    return Room.databaseBuilder(context, PokedexDatabase::class.java, DB_FILE_NAME)
        .addMigrations(*PokedexDatabase.ALL_MIGRATIONS)
        .fallbackToDestructiveMigration()
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
}
```

`backupIfDestructiveMigrationImminent(...)` is a plain, synchronous, non-suspending function call
that is a complete statement preceding `Room.databaseBuilder(...).build()` in the same function
body. Kotlin executes statements top-to-bottom with no reordering or laziness available here (no
coroutine launch, no lazy delegate) — there is no code path in which `.build()` can execute before
this call returns. This is the correct fix for the bug the whole feature exists to prevent.

Both `fallbackToDestructiveMigration()` (upgrade-side, line 34) and
`fallbackToDestructiveMigrationOnDowngrade()` (downgrade-side, line 35) remain chained onto the
same builder, and both are guarded by the same single pre-check: `MigrationPathResolver.hasPath`
(`MigrationPathResolver.kt` line 14) takes `from`/`to` with no directional assumption — it does a
plain BFS over `(startVersion, endVersion)` edges regardless of whether `from > to` or `from < to`.
`DatabaseBackupManager.kt` line 37 (`if (MigrationPathResolver.hasPath(migrations, onDiskVersion,
targetVersion)) return`) calls it once, symmetrically. So a version reversion (the exact incident
that happened) and a broken forward-migration chain both correctly trigger a backup through the
same code path. Confirmed correct.

## `MigrationPathResolver` BFS — false-positive-path check

Read `MigrationPathResolver.kt` in full. The dangerous direction per the task brief — concluding a
path exists when Room would actually still destructively wipe — was checked specifically:

```kotlin
val edges = migrations.groupBy { it.startVersion }
val visited = mutableSetOf(from)
val queue = ArrayDeque(listOf(from))
while (queue.isNotEmpty()) {
    val current = queue.removeFirst()
    for (m in edges[current].orEmpty()) {
        if (m.endVersion == to) return true
        if (visited.add(m.endVersion)) queue.add(m.endVersion)
    }
}
return false
```

For the actual data in this codebase — `ALL_MIGRATIONS` is a strict linear chain, 4→5→6→7→8→9, one
edge per version, no branches, no declared downgrade edge (`PokedexDatabase.kt` lines 32-121) —
this BFS produces identical results to Room's own incremental-migration resolution: it finds the
path if and only if every single link 4→5→6→7→8→9 is present, and any on-disk version above 9
correctly resolves to "no path" since no edge starts above 9. Traced by hand against
`MigrationPathResolverTest.kt`'s five cases (contiguous chain true, one broken link false,
same-version trivially true, downgrade-with-only-forward-migrations false, multi-version jump
honored) — all five match the resolver's actual logic, no gap between what's tested and what's
implemented.

**Confidence 4, non-blocking, informational only:** the resolver is a *general* graph-reachability
search, which is theoretically more permissive than what Room's own path selection would accept if
this migration list ever grows branches (multiple migrations sharing the same `startVersion` with
different `endVersion`s) or non-monotonic edges. Today's data has neither, so there is no concrete
scenario where `hasPath` returns `true` while Room would still destructively wipe — this is a
forward-looking design note, not a bug, and the existing "multi-version jump" test already proves
the resolver isn't hardcoded to the current shape. No action requested.

## Backup-failure-never-blocks-startup guarantee

`DatabaseBackupManager.kt` line 49: `} catch (e: Exception) {`. This catches every checked and
unchecked `Exception` subtype (`IOException`, `SecurityException`, etc.) — confirmed by the
existing `IOException during copy` test (source pointed at a directory, forcing a real stream
failure, asserts no propagation). It does **not** catch `Throwable`/`Error` subtypes (e.g. a
hypothetical `OutOfMemoryError`). Checked whether this is reachable in practice: `copyIfExists`
(line 55) calls Kotlin's `File.copyTo`, which streams via a bounded buffer rather than loading the
whole file into memory, so an OOM during the copy of even a large `pokedex_binder.db` is very
unlikely under normal conditions. `context.filesDir` (line 40) is accessed inside the `try` block,
so any `Exception` it throws is already covered.

**Confidence 6, non-blocking:** the "airtight" framing in `specs.md`/`changes.md` is technically
slightly overstated (an `Error` subtype would still propagate and could block startup), but
widening the catch to `Throwable` would not actually be the safer fix — swallowing something like
`OutOfMemoryError` mid-copy is generally worse than letting it surface, since it signals the JVM
itself may be in a bad state. Net: current design (`catch (Exception)`, not `Throwable`) is the
correct call. No change requested — noted only so the "airtight" claim is calibrated accurately
rather than taken at face value.

## Finding 1 — `gaps.md` not updated (documentation sync, non-blocking on code)

`specs.md` §8 explicitly commits to: *"flag in `gaps.md` after implementation exactly as
`Migration8to9Test` was flagged."* `changes.md`'s "Files changed" section does not list `gaps.md`,
and `git diff` confirms it: `gaps.md`'s only backup-related content is still the pre-existing,
now-stale suggestion at lines 16-18:

> "Consider: a pre-migration automatic local backup (copy `pokedex_binder.db` before any
> destructive fallback fires) as a cheap insurance layer."

That sentence describes exactly what this pipeline just built, but reads as an open, unfulfilled
suggestion — `grep -rn "DatabaseBackupManager"` across `gaps.md`/`handoff.md`/`project-overview.md`
returns zero matches. Additionally, the one real coverage gap this pipeline's own Tester found and
flagged (no test exercises `DatabaseModule.provideDatabase`'s wiring — see Finding 2) is not
recorded in `gaps.md` either, so it exists only inside `.pipeline/test-results.md`, a working file
that isn't part of the project's durable weakness register per this project's own
`project-files.md` convention.

**Confidence 9 (directly quoted and grepped, not speculative).** Not a code defect and not a
reason to withhold ship — but it should be closed out before this session ends: update `gaps.md`
to (a) mark the pre-migration-backup suggestion as implemented (point at this feature), and (b)
add the DI-wiring coverage gap from Finding 2 as its own entry. This is a documentation task, not
something I can do myself in a read-only review stage.

## Finding 2 — DI-wiring coverage gap (Tester's finding, independent judgment requested)

Verified the Tester's claim directly rather than re-trusting it: `DatabaseModule.kt`'s
`provideDatabase` (lines 22-37) is the only call site of `DatabaseBackupManager`, and
`grep -rn "DatabaseModule\|provideDatabase"` across `app/src/test/` and `app/src/androidTest/`
returns no matches outside the module's own DAO-provider methods (which are also untested for
wiring). So no test in this suite would fail if the four-line
`DatabaseBackupManager().backupIfDestructiveMigrationImminent(...)` call were silently deleted —
confirmed as the Tester describes.

**My independent judgment: acceptable, non-blocking for this PR, but not a "wash" either.**

- It genuinely matters more than a typical untested Hilt binding: this exact class of bug (a
  wiring/config line quietly reverted or dropped) is the same shape as the original incident this
  feature exists to prevent — the risk isn't hypothetical, it's the project's own history repeating
  one layer up.
- It is also genuinely consistent with an existing, unrelated-to-this-task gap: this codebase has
  never had a wiring test for any `DatabaseModule` `@Provides` function, and `specs.md` §4
  deliberately chose no `@Provides` binding for `DatabaseBackupManager` (to avoid an unnecessary DI
  graph node) — a decision that, as a side effect, is also why the wiring itself can't be unit
  tested without either Robolectric (not a project dependency) or a real device (`@HiltAndroidTest`,
  blocked by the standing no-emulator sandbox constraint).
- Blocking ship on this would be inconsistent: it would hold this task to a testing bar (Hilt wiring
  coverage) that nothing else in the module meets, over a gap that can only be closed at the
  instrumented-test layer this sandbox cannot execute anyway.

**Recommendation:** accept as a tracked, named gap (not "same as the DAO gap" — call it out
specifically given the stakes), record it in `gaps.md` per Finding 1, and treat
`DatabaseBackupManagerInstrumentedTest.kt`'s sibling (an `@HiltAndroidTest` version asserting the
real `provideDatabase` path backs up a seeded stale-version DB) as the concrete fast-follow once a
device/emulator is available — matching the Tester's own §5.2 suggestion, which I confirm rather
than second-guess.

## What looks good

- Implementation matches `specs.md`'s proposed code essentially verbatim — no drift between plan
  and reality.
- `PokedexDatabase.SCHEMA_VERSION`/`ALL_MIGRATIONS` consolidation removes a real pre-existing class
  of bug (annotation version and `DatabaseModule`'s hand-maintained migration list disagreeing).
- Rotation logic (`DatabaseBackupManager.kt` lines 60-69) correctly removes `.db` + both sidecars
  together, verified by a real test that seeds 6 seeded sets + 1 live trigger and checks exact
  survivors — not just a shallow "count went down" assertion.
- Tester ran an actual mutation test (removed the wiring call, confirmed full suite still green,
  restored and re-verified via diff) rather than reasoning abstractly about the coverage gap — this
  is exactly the right level of rigor and I independently re-ran the full suite myself and got the
  same 182/182 result.
- Both destructive fallbacks (upgrade and downgrade) correctly identified and both are guarded by
  the same direction-agnostic check — the spec's own correction of `gaps.md`'s prior downgrade-only
  framing is accurate and the fix reflects it.

## Verdict

**SHIP.** No code changes required. One process action item before closing the session: update
`gaps.md` per Finding 1 (mark the pre-migration-backup suggestion fulfilled; add the DI-wiring gap
from Finding 2 as its own tracked entry). Both findings are informational/process, calibrated to
confidence 6-9 with quoted lines, not speculative — nothing here should block merge of the code
itself.
