# Spec — Binder Backup: Card History Restore Gap + Local Export/Import

Status: draft. Re-planned 2026-09-02 (task breakdown added below) as part of a batched planning
pass alongside the scanner macro-focus fix, the `graphics-path` dependency fix, and the new
Discord live-lookup bot spec — see `handoff.md`/`gaps.md`'s 2026-09-02 entries for the batch
summary and priority ranking. **Do not start `dev-team-pipeline` on this until Skyler explicitly
says go** — planning only for now, the PRD-level approval this status line originally asked for is
still separate from today's task-breakdown pass.

## Problem

Triggered by: Skyler switched phones and asked how to move his binder data over. Investigation
found the app already has a working cloud backup path (`PublishRepository`/`RestoreRepository`,
GitHub Contents API) that most people would call "the" answer — but it has two real gaps:

1. `RestoreRepository.restore()` restores Pokédex, Connecting Art, Personal Collection, and Unown
   from `binder.json`, but has **no Card History branch at all** — even though
   `PublishRepository.buildSnapshot()` already writes a `"cardHistory"` `SnapshotBinder` whenever
   the (default-off) `publishCardHistory` toggle is on. Restore silently drops Card History.
2. The cloud path requires a GitHub PAT + repo configured in Settings. There is no fully local,
   no-account backup/restore path — today the only local option is manual `adb run-as` file
   pull/push, which needs a PC, USB debugging, and isn't documented anywhere in the app.

## Goals

- Card History survives a cloud Restore (append-only merge — must not wipe local additions made
  since the last publish, since it has no stable per-device row IDs to overwrite-match against).
- A local, GitHub-independent backup exists: one Settings action exports every binder's data as
  JSON (human-inspectable) **and** a raw Room `.db` file, handed to Android's share sheet so
  Skyler can send it to Drive, Telegram, email, or a USB file manager — whatever he has that day.
- Import accepts either artifact (JSON or `.db`), validates schema/app version before touching
  anything, and replaces local state.
- Neither the export nor the import path requires a GitHub account, PAT, or network connection.

## Non-Goals

- No change to the cloud publish mechanics themselves (Discord webhook, GitHub diff/changelog) —
  only adding Card History to the *restore read path*, which is a gap in restore, not publish.
- No automatic/scheduled export — always a manual action, matching the existing "manual publish
  only, no auto-publish" decision (project-overview.md, 2026-07-12).
- No merge-on-import for the local JSON/`.db` path — import always **replaces** local state, per
  Skyler's explicit answer. The one exception is Card History specifically, and only because of
  Part A's ID-instability constraint below — not a general merge feature.
- No cross-format conversion (edit the JSON as CSV, etc.) — JSON only, structured, not meant for
  hand-editing.
- No encryption of the export file. The GitHub PAT is already stored unencrypted in local Settings
  (existing precedent) and where the export file ends up (Drive/Telegram/etc.) is Skyler's own
  choice, outside this feature's scope.

---

## Part A — Card History Restore Gap

### Why append-only, not replace-on-restore

Card History (`secondary_binder`) rows use a local autoincrement `id` primary key. A fresh install
(new phone) starts autoincrement at 1 — there is no way to match a snapshot slot's key
(`"${pokemonId}-${id}"`) back to a real local row, because that `id` never existed on this device.
Unlike the other four binders (fixed slot count or a stable content key), Card History is an
open-ended list — restoring it means **inserting rows not already present**, matched by `cardId`
(the natural dedupe key for "this physical card assignment already exists here"), never deleting
or reordering what's already local.

### Change

In `RestoreRepository.restore()`, add a Card History branch alongside the existing four:

- Read the `BINDER_ID_CARD_HISTORY` (`"cardHistory"`) section from the fetched snapshot, same
  shape as the other four reads.
- Build the set of `cardId`s already present locally via `secondaryBinderDao.getAll()`.
- For each snapshot slot whose `cardId` is not in that local set: insert a new
  `SecondaryBinderEntry` (`pokemonId`, `pokemonName` = `slotName`, `cardId`,
  `cardImageUrl` = `imageUrl`, `language`, `position` = appended via the existing
  `insertAtEnd`-style next-position helper).
- Count inserted rows toward the existing `restored` counter in `RestoreStep.Done` /
  `RestoreResult.Success` — no new counter category needed.
- If `config.publishCardHistory` was off at publish time, the snapshot simply has no
  `"cardHistory"` binder — restore skips it, same as it already does for any binder id absent
  from a given snapshot.

---

## Part B — Local Export/Import

### Export format

Reuse `PublishRepository.buildSnapshot(...)` as-is — it already assembles every binder (Pokédex,
Card History, Connecting Art, Personal Collection, Unown) into one `BinderSnapshot`, the exact
shape already proven by the cloud path. Serialize with the existing `Moshi` adapter. No parallel
JSON schema to design or keep in sync.

Two files produced per export, handed to the share sheet together (`ACTION_SEND_MULTIPLE`):

1. **`pokedex-binder-backup-<yyyyMMdd_HHmmss>.json`** — the `BinderSnapshot` wrapped in a small
   envelope so import can validate before touching the DB:
   ```json
   {
     "appVersionName": "1.x.x",
     "roomSchemaVersion": 9,
     "exportedAt": "2026-08-09T20:15:00+08:00",
     "snapshot": { "...": "BinderSnapshot as already defined" }
   }
   ```
2. **`pokedex-binder-<yyyyMMdd_HHmmss>.db`** — raw copy of `pokedex_binder.db`. Run
   `PRAGMA wal_checkpoint(FULL)` first so the single `.db` file is self-contained (no dependency
   on separately-shipped `-wal`/`-shm` sidecars) — same checkpoint-then-copy approach
   `DatabaseBackupManager` already uses for its pre-migration safety copies, just triggered
   on-demand instead of only before a destructive migration.

### Export flow

New "Backup" entry in Settings, next to the existing GitHub/Discord config:

1. Checkpoint + copy `.db` into `filesDir/exports/`.
2. Build the JSON envelope, write it to the same folder.
3. Expose both files via a `FileProvider` (manifest addition — none currently declared in
   `AndroidManifest.xml`) and launch `Intent.ACTION_SEND_MULTIPLE` / chooser.
4. Success/error snackbar. No multi-step dialog needed (fast local operation, unlike Publish's
   multi-step network flow) — a simple loading state is enough.

### Import flow

New "Restore from file" entry in Settings, next to Backup:

1. `ActivityResultContracts.OpenDocument()` (or `GetMultipleContents`) — Skyler picks the `.json`,
   the `.db`, or both, from wherever he saved/received them.
2. **JSON import**: parse the envelope. If `roomSchemaVersion` > the app's current
   `PokedexDatabase.SCHEMA_VERSION` (9) → reject: *"This backup is from a newer app version —
   update the app first."* (mirrors the existing hard-learned downgrade-wipe lesson in
   project-overview.md Gotchas — never let an incompatible schema touch the live DB). Otherwise,
   feed the embedded `BinderSnapshot` into the **same restore-application logic Part A now uses**
   — refactor `RestoreRepository`'s per-binder "apply this `BinderSnapshot` to local tables" body
   into a function taking a `BinderSnapshot` directly, so cloud Restore and local JSON import run
   one identical code path instead of two implementations that can drift apart. Card History
   import also inserts by-`cardId`-not-present (same as Part A) — a deliberate, flagged exception
   to "import always replaces," forced by Card History's lack of a stable per-device key, not a
   design choice made for this feature.
3. **`.db` import**: read the picked file's `PRAGMA user_version` via the existing
   `SqliteVersionReader`/`FrameworkSqliteVersionReader` (already built for
   `DatabaseBackupManager`, reused unchanged). Reject if it exceeds the current `SCHEMA_VERSION`.
   Otherwise: close the live Room DB instance, replace `pokedex_binder.db` (+ sidecars) with the
   picked file's bytes, and require an app restart — Room reopens and runs whatever forward
   migrations are needed exactly as on any normal cold start. Surface this as an explicit
   "Restart the app to finish restoring" step rather than attempting to hot-swap a live connection.
4. Both import paths show a confirmation dialog before touching anything — *"This replaces your
   current binder data. Continue?"* — irreversible from the app's own state (though the source
   export file itself is untouched, so it stays recoverable by re-importing).

---

## Requirements

**P0**
- Part A: Card History rows are inserted (not skipped) by cloud Restore.
- Part B export: one Settings action produces both files, share sheet fires with both attached.
- Part B import: JSON path, version-gated, confirmation-gated, running through the Part A shared
  restore-application function.

**P1**
- Part B import: `.db` path (raw file replace) as an alternative to JSON.
- Validation error messages specific enough to explain the actual mismatch, not a generic
  "import failed."

**P2**
- Settings UI polish for Backup/Restore (progress state, icons) — ship P0/P1 functionally first;
  visual pass can follow the established `PublishDialog.kt`/`RestoreDialog.kt` pattern rather than
  inventing a new one.

## Acceptance Criteria

- [ ] Publishing with Card History toggle on, then restoring on a fresh install, brings all Card
      History entries back (inserted, appended); running restore a second time inserts no
      duplicates.
- [ ] Export produces both files; the share sheet opens with both attached.
- [ ] Importing a JSON export from the same app version fully restores all 5 binders' data to
      match what was live at export time.
- [ ] Importing a JSON export whose `roomSchemaVersion` exceeds the current app's is rejected with
      a clear message; no local data is touched.
- [ ] Importing a `.db` file whose `PRAGMA user_version` exceeds the current `SCHEMA_VERSION` is
      rejected; no local data is touched, existing DB file untouched.
- [ ] Importing a valid `.db` file from an older, migratable schema version succeeds after
      restart, via Room's own migration chain (no custom migration logic in the import path).
- [ ] Cloud Restore and local JSON import, given the same `BinderSnapshot` input, produce
      identical resulting local state — proof the shared code path is actually shared, not
      duplicated.

## Open Questions

- Should Backup show a soft reminder ("last local backup: N days ago") without becoming
  scheduled/automatic export? Non-blocking, can ship without it — product call, ask Skyler later.
- Exact `FileProvider` `authorities` string and `provider_paths.xml` scope — implementation
  detail, resolve during coding, not blocking spec approval.

## Task Breakdown (added 2026-09-02, for `dev-team-pipeline`'s Coder stage, when authorized)

| # | Task | File(s) | Type | Size | Depends on |
|---|---|---|---|---|---|
| 1 | Refactor `RestoreRepository`'s per-binder "apply `BinderSnapshot` to local tables" body into a function taking a `BinderSnapshot` directly, so cloud Restore and local JSON import share one path | `RestoreRepository.kt` | CORE | M | — |
| 2 | Add the Card History branch to that shared apply-function: read `"cardHistory"` snapshot binder, insert only rows whose `cardId` isn't already present locally, count toward `restored` | `RestoreRepository.kt` | CORE | S | 1 |
| 3 | `FileProvider` manifest addition + `provider_paths.xml` (none currently declared) | `AndroidManifest.xml`, new `res/xml/provider_paths.xml` | INFRA | S | — |
| 4 | Export: checkpoint (`PRAGMA wal_checkpoint(FULL)`) + copy `.db` to `filesDir/exports/`, build the JSON envelope (reuse `PublishRepository.buildSnapshot` + existing Moshi adapter), launch `ACTION_SEND_MULTIPLE` | New `data/local/backup/BackupExporter.kt` (or similar) | CORE | M | 3 |
| 5 | New Settings "Backup" entry wiring task 4 into the UI, with loading/success/error state | `SettingsScreen.kt`, `SettingsViewModel.kt` | UI | S | 4 |
| 6 | Import (JSON path): `ActivityResultContracts.OpenDocument()`, parse envelope, reject if `roomSchemaVersion` exceeds current `SCHEMA_VERSION`, feed into task 1's shared apply-function, confirmation dialog before touching anything | New `data/local/backup/BackupImporter.kt` (or similar) | CORE | M | 1, 3 |
| 7 | Import (`.db` path, P1): read `PRAGMA user_version` via existing `SqliteVersionReader`, reject if it exceeds `SCHEMA_VERSION`, close live Room instance, replace file + sidecars, require restart | Same as task 6 | CORE | M | 6 |
| 8 | New Settings "Restore from file" entry wiring tasks 6-7 into the UI | `SettingsScreen.kt`, `SettingsViewModel.kt` | UI | S | 6, 7 |
| 9 | Unit tests: Card History insert-not-duplicate (Part A acceptance criteria), envelope version-gate rejection, cloud-Restore-vs-local-import parity test (same `BinderSnapshot` in, identical resulting state out) | New/existing test files under `app/src/test/` | TEST | M | 1, 2, 6, 7 |

**Phase rollout:** 1-2 (Card History restore gap, P0, independently shippable) → 3-5 (export, P0)
→ 6 (JSON import, P0) → 7-8 (`.db` import, P1) → 9 (tests, throughout but gated complete before
calling any phase done, per this project's standing verification discipline).

## Timeline

No hard deadline. **Do not start `dev-team-pipeline` until Skyler explicitly says go.**
