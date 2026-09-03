# Feature Spec: Binder Backup — Card History Restore Gap + Local Export/Import

**Author:** Planning stage (grounded against PRD `docs/specs/2026-08-09-binder-backup-export-import.md`)
**Date:** 2026-09-03
**Status:** Approved for build (Skyler, per this pipeline run's kickoff — confirmed superseding the source doc's earlier "do not start" status line)
**Target ship:** Next `dev-team-pipeline` run, this project

---

## TL;DR

Cloud Restore (`RestoreRepository`) silently drops Card History today — it has no branch for that
binder at all, even though Publish already writes one. Separately, the only backup path that
doesn't require a GitHub PAT + network is a manual `adb run-as` file pull, undocumented and PC-only.
This spec closes both: (A) teach Restore to insert missing Card History rows, matched by `cardId`
(append-only, since Card History has no stable per-device key), and (B) add a local, no-account
Settings "Backup"/"Restore from file" pair that exports every binder as JSON + a raw `.db` file via
the share sheet, and imports either back, replacing local state after a version gate and
confirmation.

---

## Problem

Skyler switched phones and asked how to move his binder data over. The app already has a working
cloud backup path (GitHub Contents API via `PublishRepository`/`RestoreRepository`), but it has two
real gaps: Card History is written on publish but never read on restore, and there is no
GitHub-independent local export/import path at all — today's only fallback is `adb run-as` file
access, which needs a PC, USB debugging, and isn't documented anywhere in the app.

### Evidence

- `RestoreRepository.restore()` (verified directly, `app/src/main/java/com/skyler/pokedexbinder/publish/RestoreRepository.kt`) has overlay blocks for Pokédex, Connecting Art, Personal Collection, and Unown — no `"cardHistory"` branch exists, and `RestoreRepositoryTest.kt`'s `non-pokedex binder in snapshot is ignored` test (lines 307-335) explicitly documents and asserts that a `cardHistory` binder in a fetched snapshot is currently a no-op.
- `PublishRepository.buildSnapshot()` (same file) already writes a `"cardHistory"` `SnapshotBinder` whenever `config.publishCardHistory` is on — the data exists in every snapshot published since that toggle was introduced, restore just never reads it.
- No `FileProvider` is declared anywhere in `AndroidManifest.xml` (confirmed by direct read) and no local export/import UI exists in `SettingsScreen.kt` beyond "Publish now" / "Restore from published snapshot".

---

## Users

**Primary user:** Skyler (sole user of this personal project), specifically in the "I got a new
phone" or "I want an offline safety copy" moments.

**Use cases:**
1. Skyler adds cards to Card History, later restores on a fresh install (new phone) after
   publishing — today those Card History cards never come back.
2. Skyler wants a backup that doesn't depend on his GitHub PAT/repo being configured, or on having
   network access at the moment he wants to back up.
3. Skyler receives a `.json`/`.db` backup file (self-sent via Drive/Telegram/email) on a new device
   and wants to restore from it directly, no adb, no PC.

**Secondary users:** None — no other users of this app.

**Anti-users:** N/A (personal project, no multi-user considerations).

---

## Proposal

Part A fixes the Card History restore gap by adding an append-only insert branch to Restore's
existing per-binder logic, matched by `cardId` (Card History has no stable per-device row `id`
across installs, so it can never be a diff key — unlike the other four binders). Part B adds a
local Settings "Backup" action (exports every binder as one JSON envelope + a checkpointed raw
`.db` file via `ACTION_SEND_MULTIPLE`) and a "Restore from file" action (imports either artifact,
version-gated against the app's current Room schema version, confirmation-gated, and — for the
JSON path — routed through the exact same apply-to-local-tables logic Part A now uses, so cloud
Restore and local import can never drift apart).

### How it works (high level)

1. Skyler taps "Publish now" with Card History's toggle on (existing flow, unchanged) — `binder.json`
   now includes a `"cardHistory"` section (already true today).
2. On a new install, Skyler taps "Restore from published snapshot" — Card History cards not already
   present locally (matched by `cardId`) are inserted; running restore again is idempotent (no
   duplicates).
3. Skyler taps a new "Backup" entry in Settings — the app checkpoints and copies `pokedex_binder.db`,
   builds a version-stamped JSON envelope of every binder, and opens the Android share sheet with
   both files attached.
4. On a new device (or after a wipe), Skyler taps "Restore from file", picks the `.json` and/or
   `.db` file he saved, confirms an explicit "this replaces your current data" dialog, and the app
   either (JSON) applies the embedded snapshot through Part A's shared apply-function, or (`.db`)
   replaces the raw database file and asks for a restart.

---

## User stories

- As Skyler, I want Card History to survive a cloud Restore, so that switching phones doesn't quietly drop cards I added since my last publish.
- As Skyler, I want a one-tap local export that doesn't need my GitHub PAT, so that I have a backup even if I haven't configured (or don't trust) the cloud path that day.
- As Skyler, I want import to refuse a backup from a newer app version, so that I never wipe a working install with an incompatible schema.
- As Skyler, I want a confirmation step before import touches anything, so that a misclick doesn't silently destroy my current binder state.

---

## Acceptance criteria

- [ ] **Given** Card History's publish toggle is on and cards exist locally, **when** Skyler publishes then restores on a fresh install, **then** all Card History entries come back (inserted, appended to the end); running restore a second time inserts zero duplicates (matched by `cardId`).
- [ ] **Given** Skyler taps "Backup" in Settings, **when** the export completes, **then** both a `.json` and a `.db` file exist and the Android share sheet opens with both attached.
- [ ] **Given** a JSON export from the same app version, **when** Skyler imports it, **then** all 5 binders' data matches what was live at export time, and cloud-Restore-given-the-same-`BinderSnapshot` produces byte-for-byte-identical resulting local state (proof the shared apply-function is actually shared).
- [ ] **Given** a JSON export whose `roomSchemaVersion` exceeds the current app's `PokedexDatabase.SCHEMA_VERSION`, **when** Skyler imports it, **then** the import is rejected with a specific message and no local data is touched.
- [ ] **Given** a `.db` file whose `PRAGMA user_version` exceeds the current `SCHEMA_VERSION`, **when** Skyler imports it, **then** it is rejected, no local data is touched, and the existing on-disk DB file is untouched.
- [ ] **Given** a valid `.db` file from an older, migratable schema version, **when** Skyler imports it and restarts, **then** Room's own migration chain (`PokedexDatabase.ALL_MIGRATIONS`) runs on the next cold start with no custom migration logic in the import path.
- [ ] **Given** either import path is triggered, **when** the confirmation dialog appears, **then** nothing is touched until Skyler explicitly confirms.

---

## Out of scope

- No change to cloud publish mechanics themselves (Discord webhook, GitHub diff/changelog) — only Restore's read path gains a Card History branch.
- No automatic/scheduled export — manual action only, matching the existing "no auto-publish" decision (`project-overview.md`, 2026-07-12).
- No merge-on-import for the local JSON/`.db` path — import always **replaces** local state. Card History is the sole exception, and only because Part A's per-device-ID instability forces an append-only strategy for it specifically — not a general merge feature.
- No cross-format conversion (CSV, etc.) — JSON only, not meant for hand-editing.
- No encryption of the export file — same unencrypted-local-storage precedent as the existing GitHub PAT in Settings; where the file ends up (Drive/Telegram/etc.) is Skyler's own choice.

---

## Dependencies

### Required before development can start
- [x] PRD approved (`docs/specs/2026-08-09-binder-backup-export-import.md`)
- [x] Architecture settled (shared apply-function refactor, reuse `PublishRepository.buildSnapshot`, JSON+`.db` dual export) — not re-litigated here
- [x] Codebase grounding done (this document)

### External systems or services
- None new. Reuses the existing GitHub Contents API path for Part A (no new external dependency); Part B is fully local (Android `FileProvider` + share sheet + Storage Access Framework `OpenDocument`/`OpenMultipleDocuments`).

### Team dependencies
- None — solo personal project, no design/content/marketing handoffs.

---

## Success metric

No analytics in this app (personal project, explicitly no metrics per project conventions).

**Primary signal:** Skyler successfully restores Card History after a real publish→fresh-install cycle, and successfully exports+imports a local backup on his own device, without adb.

**Secondary signals:**
- 0 duplicate Card History rows after 2 consecutive restores of the same snapshot.
- Import correctly refuses at least one deliberately-newer-schema-version test file before shipping.

**Negative signal:** Any import path that touches local data before the confirmation dialog fires, or any schema-version-gate bypass, is a shipped regression, not a nice-to-have miss.

---

## Estimated effort

**Size:** Medium (spans ~2-3 sessions given the number of dependency-ordered tasks; no single task is XL)

**Breakdown (see Technical Implementation for the authoritative per-task table):**
- Part A (Card History restore gap + refactor): S–M, 2 tasks
- FileProvider/shared model infra: S, 2 tasks
- Export: M+S, 2 tasks
- Import (JSON): M, 1 task
- Import (`.db`): L (upsized from the source PRD's M — see Technical Implementation, task 8), 1 task
- Settings wiring: S, 1 task
- Tests: M, 1 task (covers both parts + the regression fix required in task 2)
- **Total: 10 tasks** (source PRD had 9 — one new task inserted for a real dependency-ordering gap found during grounding, see Technical Implementation)

---

## Priority

**P0** Launch blocker - must ship before [event/launch]
**P1** Next sprint - high impact, addressable now
**P2** Within quarter - valuable but not urgent
**P3** Backlog - nice-to-have, no commitment

**This spec:** P0 for Part A (restore gap, tasks 1-2) + Part B export/JSON-import (tasks 3-7); P1 for `.db` import (tasks 8-9); P2 deferred (Settings UI polish, not in this pipeline run's scope).

**Reasoning:** Part A is a real, silent data-loss gap on every phone switch going forward. Part B's JSON path is the GitHub-independent safety net Skyler explicitly asked for after switching phones once already. The `.db` path is a genuinely more complex, higher-risk addition (live Room instance lifecycle, file-level replacement, process restart) — still valuable but reasonably P1, not blocking the JSON path's ship.

---

## Open questions

- [ ] Should Backup show a soft reminder ("last local backup: N days ago") without becoming scheduled/automatic export? **Owner:** Skyler (product call) — **Decision needed by:** non-blocking, can ship without it.
- [ ] Exact `FileProvider` `authorities` string — resolved during grounding, not actually open: use `"${applicationId}.fileprovider"` = `"com.skyler.pokedexbinder.fileprovider"`, the standard Android convention, no reason to deviate.
- [ ] Should the Settings "Restore from file" flow accept `.json` and `.db` in a single multi-select picker action, or two separate single-file pickers? **Owner:** engineering call, not product — recommend `ActivityResultContracts.OpenMultipleDocuments()` (see Technical Implementation, task 7) so one tap can carry both files if Skyler picks them together from the same folder; resolve during coding, not blocking.

---

## Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Refactoring `RestoreRepository.restore()` (task 1) silently changes cloud-Restore behavior — 30 existing tests in `RestoreRepositoryTest.kt` currently pass against the monolithic function | Medium | High | Extract `applySnapshot()` as a pure move of existing logic, no behavior change; re-run the full existing suite unmodified except the one test that must change (see task 2's flagged regression) before adding any new test |
| `.db` import replaces the live database file while a Hilt-singleton `PokedexDatabase` instance still holds an open connection elsewhere in the process | Medium | High (data corruption) | Close the injected `PokedexDatabase` instance, delete stale `-wal`/`-shm` sidecars from the *old* DB before/after the copy (not left in place — a stale WAL replayed against the new file corrupts it), then force a full process restart (`Process.killProcess`) rather than continuing in-process — see task 8 |
| Reading `PRAGMA user_version` from a Storage-Access-Framework `content://` Uri — `SqliteVersionReader.readVersion()` takes a `java.io.File`, not a Uri, and some providers (Drive, Telegram) don't expose a real filesystem path | High | Medium (import version-gate silently fails or crashes) | Copy the picked document to a local `cacheDir` temp file first via `ContentResolver.openInputStream`, then run the existing `SqliteVersionReader` against that temp file — see task 8 |
| MIME-type sniffing for arbitrary picked files (`.json` vs `.db`) is unreliable across file-manager/Drive/Telegram providers | Medium | Medium (wrong parser invoked) | Route by file extension (queried display name via `OpenableColumns.DISPLAY_NAME`), not by `ContentResolver.getType()` — see task 7 |
| `SecondaryBinderEntry.cardImageUrl` is non-nullable but `SnapshotSlot.imageUrl` is nullable | Low | Low (compile-time only, caught immediately) | `snap.imageUrl ?: ""` fallback at the task 2 insert site |

---

## Smallest shippable increment

**MVP:** Tasks 1-2 (Card History restore gap) alone — independently shippable, fixes a real silent data-loss bug with no dependency on any of Part B. Confirmed in the source PRD's phase rollout and unchanged by this grounding pass.

**Future iterations** (not in this pipeline run):
- `.db` import path (task 8-9) could ship a session after the JSON path if time-boxing is needed — it's the highest-risk, most novel task in this spec (live DB lifecycle + process restart, no existing precedent in this codebase).
- Settings UI polish (progress state, icons) — P2, ship functionally first per the source PRD.
- The two explicitly non-blocking open questions above.

---

## Technical Implementation

Grounded directly against the current code (files read in full this session, not assumed from the
source PRD's prose): `RestoreRepository.kt`, `PublishRepository.kt`, `PokedexDatabase.kt` (confirmed
`SCHEMA_VERSION = 9`), `DatabaseModule.kt`, `SecondaryBinderEntry.kt`/`SecondaryBinderDao.kt`,
`BinderSnapshot.kt`, `SettingsScreen.kt`/`SettingsViewModel.kt`, `RestoreViewModel.kt`/`RestoreDialog.kt`
(pattern to mirror), `AndroidManifest.xml` (confirmed no `FileProvider` declared), `RestoreRepositoryTest.kt`
(confirmed a pre-existing test directly contradicts the intended post-fix behavior — see task 2),
`data/local/backup/{SqliteVersionReader,DatabaseBackupManager,MigrationPathResolver}.kt`, `NetworkModule.kt`
(confirmed `Moshi` is a Hilt-provided singleton, directly injectable into new classes).

**Corrections vs. the source PRD's task breakdown**, found during this grounding pass:

1. `RestoreRepository.restore()` is one monolithic function today — there is no pre-existing
   per-binder "apply" separation to literally extract; task 1 below specifies the exact new
   function shape needed.
2. `RestoreRepositoryTest.kt`'s existing `non-pokedex binder in snapshot is ignored` test (lines
   307-335) explicitly asserts today's cardHistory-is-ignored behavior — task 2 must update/replace
   it, not just add new tests alongside it, or the suite ships internally contradictory.
3. Card History's `pokemonId` field has no derivable value from a `SnapshotSlot` (the snapshot only
   carries `slotName`/`cardId`/`imageUrl`/`language` for this binder, not the original `pokemonId`
   string) — task 2 resolves this explicitly (see below), the source PRD left it unstated.
4. `RestoreRepository`'s constructor needs a new `secondaryBinderDao: SecondaryBinderDao` parameter
   for task 2 — this also requires updating `RestoreRepositoryTest.kt`'s manual constructor call
   (Hilt wires production automatically; the test does not).
5. The JSON envelope model (`appVersionName`/`roomSchemaVersion`/`exportedAt`/`snapshot`) is
   consumed by *both* export (task 5, was task 4 in the source PRD) and import (task 7, was task 6)
   — the source PRD's dependency graph had import depend only on {1, 3}, missing the shared model.
   **Inserted as new task 4** below; renumbers everything after it by one.
6. `.db` import (task 8, was task 7) is materially more involved than "M" once grounded: reading
   `PRAGMA user_version` off a SAF `content://` Uri requires a temp-file copy first (`SqliteVersionReader`
   takes a `java.io.File`), and replacing the live `.db` requires closing the Hilt-singleton
   `PokedexDatabase` instance, deleting stale `-wal`/`-shm` sidecars, and a full process restart
   (not a soft "restart the app" prompt with no enforcement) — **upsized to L**.
7. `DatabaseModule.kt`'s `DB_FILE_NAME = "pokedex_binder.db"` is currently a private constant local
   to that file — both the exporter and importer need this same file name. Relocate it to
   `PokedexDatabase.DB_FILE_NAME` (companion object, alongside the existing `SCHEMA_VERSION`
   pattern) so there is exactly one source of truth, not a second hardcoded string literal —
   folded into task 4.
8. Picked-file routing (`.json` vs `.db`) must not rely on `ContentResolver.getType()` — unreliable
   across providers (Drive/Telegram/file managers). Route by file extension via the picked Uri's
   `OpenableColumns.DISPLAY_NAME` — folded into task 7.
9. `ActivityResultContracts.OpenDocument()` (singular) can only return one `Uri`; if Skyler is meant
   to be able to pick `.json` and `.db` together in one action (per the PRD's UX description), the
   correct contract is `OpenMultipleDocuments()`, returning `List<Uri>` — folded into task 7 as the
   recommended choice (open question above, non-blocking either way).

### Task Breakdown (corrected, dependency-ordered)

| # | Task | File(s) | Type | Size | Depends on |
|---|---|---|---|---|---|
| 1 | Extract `RestoreRepository`'s per-binder apply logic into `internal suspend fun applySnapshot(snapshot: BinderSnapshot): SnapshotApplyCounts` (new small data class `SnapshotApplyCounts(restored: Int, cleared: Int, skipped: Int)`), called by both the existing `restore()` (fetch-then-apply) and a new public `suspend fun restoreFromSnapshot(snapshot: BinderSnapshot, onStep: (RestoreStep) -> Unit): RestoreResult` entry point for local import to call directly (no GitHub config needed for this path) | `RestoreRepository.kt` | CORE | M | — |
| 2 | Add the Card History branch inside `applySnapshot`: read the `"cardHistory"` snapshot binder (new `private const val BINDER_ID_CARD_HISTORY = "cardHistory"` in this file, matching `PublishRepository`'s private constant of the same value), build the local `cardId` set via `secondaryBinderDao.getAll()`, insert (via existing `secondaryBinderDao.insertAtEnd(...)`) any snapshot slot whose `cardId` isn't already present — `pokemonId = snap.slotName`, `pokemonName = snap.slotName` (Card History's `pokemonId` has no recoverable original value from a snapshot slot; the field is unused by any query in `SecondaryBinderDao`, so this is safe), `cardImageUrl = snap.imageUrl ?: ""` (nullable→non-nullable fallback), `language = snap.language`. Count inserts toward `restored`. Add `secondaryBinderDao: SecondaryBinderDao` to the constructor. **Must also fix `RestoreRepositoryTest.kt`'s existing `non-pokedex binder in snapshot is ignored` test** (currently asserts cardHistory is a no-op — false after this change) and add `secondaryBinderDao` to that test's manual `RestoreRepository(...)` construction | `RestoreRepository.kt`, `RestoreRepositoryTest.kt` | CORE | S | 1 |
| 3 | `FileProvider` manifest entry (`authorities="com.skyler.pokedexbinder.fileprovider"`, `exported="false"`, `grantUriPermissions="true"`, `<meta-data>` pointing at `@xml/provider_paths`) + new `res/xml/provider_paths.xml` declaring a `<files-path name="exports" path="exports/" />` matching `filesDir/exports/` | `AndroidManifest.xml`, new `app/src/main/res/xml/provider_paths.xml` | INFRA | S | — |
| 4 | **New task (gap found during grounding).** New `BackupEnvelope` model (`@JsonClass(generateAdapter = true) data class BackupEnvelope(val appVersionName: String, val roomSchemaVersion: Int, val exportedAt: String, val snapshot: BinderSnapshot)`), and relocate `DatabaseModule.kt`'s private `DB_FILE_NAME` constant to `PokedexDatabase.DB_FILE_NAME` (companion object) so export/import/`DatabaseModule` all reference one source of truth instead of a second hardcoded `"pokedex_binder.db"` literal | New `publish/model/BackupEnvelope.kt`, `PokedexDatabase.kt`, `DatabaseModule.kt` | MODEL | S | — |
| 5 | Export: inject `PokedexDatabase` + `Moshi` + `PublishRepository`, checkpoint via `db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")`, copy `context.getDatabasePath(PokedexDatabase.DB_FILE_NAME)` into `filesDir/exports/pokedex-binder-<yyyyMMdd_HHmmss>.db`, build a `BackupEnvelope` via `publishRepository.buildSnapshot(...)` (reads every binder fresh: `binderRepository.getAllEntries()`, `secondaryBinderDao.getAll()`, CA/PC/Unown reads — same call shape `PublishRepository.publish()` already uses) wrapped with `BuildConfig.VERSION_NAME`/`PokedexDatabase.SCHEMA_VERSION`/`OffsetDateTime.now()`, serialize via `moshi.adapter(BackupEnvelope::class.java).toJson(...)`, write to `filesDir/exports/pokedex-binder-backup-<stamp>.json`, launch `Intent.ACTION_SEND_MULTIPLE` with both `FileProvider.getUriForFile(...)` Uris in `EXTRA_STREAM` (as `ArrayList<Uri>`), `FLAG_GRANT_READ_URI_PERMISSION`, via `Intent.createChooser(...)` | New `data/local/backup/BackupExporter.kt` | CORE | M | 3, 4 |
| 6 | New Settings "Backup" entry (`OutlinedButton`, mirrors the existing "Restore from published snapshot" button styling in `SettingsScreen.kt`) wiring task 5 into the UI via a new small ViewModel state (`Idle`/`Loading`/`Success`/`Error` — simpler than `RestoreUiState`/`PublishUiState`'s stepped-ticker pattern per the source PRD's own call that this is a fast local op) | `SettingsScreen.kt`, `SettingsViewModel.kt` (or a new small `BackupViewModel.kt` mirroring `RestoreViewModel.kt`'s file-per-concern pattern) | UI | S | 5 |
| 7 | Import (JSON path): `ActivityResultContracts.OpenMultipleDocuments()` (returns `List<Uri>`, lets Skyler pick `.json`+`.db` together; MIME types `arrayOf("application/json", "application/octet-stream", "*/*")` since provider MIME-sniffing for `.db` is unreliable) — route each returned Uri by its `OpenableColumns.DISPLAY_NAME` file extension, not `ContentResolver.getType()`. For a `.json` Uri: `contentResolver.openInputStream(uri)`, parse via `moshi.adapter(BackupEnvelope::class.java).fromJson(...)`, reject with a specific message if `envelope.roomSchemaVersion > PokedexDatabase.SCHEMA_VERSION`, otherwise show the confirmation dialog then call `restoreRepository.restoreFromSnapshot(envelope.snapshot, onStep)` (task 1's new entry point) | New `data/local/backup/BackupImporter.kt` | CORE | M | 1, 3, 4 |
| 8 | Import (`.db` path, P1): copy the picked `.db` Uri's bytes to a `cacheDir` temp file first (`SqliteVersionReader.readVersion()` needs a `java.io.File`, not a Uri — SAF-provided documents from Drive/Telegram don't reliably expose a real filesystem path), read `PRAGMA user_version` via the existing `FrameworkSqliteVersionReader`, reject if it exceeds `PokedexDatabase.SCHEMA_VERSION`. Otherwise: inject and `close()` the live `PokedexDatabase` instance, delete the current `-wal`/`-shm` sidecars at `context.getDatabasePath(PokedexDatabase.DB_FILE_NAME)` (a stale WAL from the *old* DB replayed against the newly-copied file corrupts it), copy the temp file over the real DB path, then force a full process restart (`Process.killProcess(Process.myPid())` after relaunching `MainActivity` via a `PendingIntent`/fresh `Intent` with `FLAG_ACTIVITY_CLEAR_TASK or FLAG_ACTIVITY_NEW_TASK`) so Room reopens cold with a fresh Hilt graph — no in-process hot-swap attempted | Same `data/local/backup/BackupImporter.kt` | CORE | **L** (upsized from source PRD's M) | 7 |
| 9 | New Settings "Restore from file" entry wiring tasks 7-8 into the UI, with the mandatory "This replaces your current binder data. Continue?" confirmation dialog gating both paths before anything is touched | `SettingsScreen.kt`, `SettingsViewModel.kt` (or a new `ImportViewModel.kt`) | UI | S | 7, 8 |
| 10 | Unit tests: Card History insert-not-duplicate (task 2's AC), the corrected `non-pokedex binder in snapshot is ignored` test split (cardHistory now inserts; a genuinely-still-ignored binder id case retained separately), envelope version-gate rejection (both JSON and `.db` paths), and a parity test proving `restore()`'s cloud path and `restoreFromSnapshot()`'s local path produce byte-identical `SnapshotApplyCounts`/local state given the same `BinderSnapshot` input | New/existing test files under `app/src/test/` (extends `RestoreRepositoryTest.kt`; new `BackupExporterTest.kt`/`BackupImporterTest.kt`) | TEST | M | 1, 2, 7, 8 |

**Phase rollout:** 1-2 (Card History restore gap, P0, independently shippable) → 3-4 (shared infra:
FileProvider + envelope model, P0) → 5-6 (export, P0) → 7 (JSON import, P0) → 8-9 (`.db` import, P1)
→ 10 (tests, throughout, gated complete before calling any phase done — per this project's standing
verification discipline in `CLAUDE.md`).

---

## Changelog

| Date | Author | Change |
|---|---|---|
| 2026-08-09 | (prior session) | Initial PRD draft (Problem/Goals/Non-Goals/Part A+B approach/Requirements/Acceptance Criteria) |
| 2026-09-02 | (prior session) | Added Task Breakdown (9 tasks), status line updated, not build-authorized |
| 2026-09-03 | Planning stage (this pass) | Reshaped into `feature-spec-template.md` structure for `.pipeline/specs.md`; grounded every file path and function reference against current code; corrected the refactor's exact target function shape, resolved the undocumented Card History `pokemonId` derivation, found and fixed a broken pre-existing test the change would otherwise leave contradictory, inserted a new shared-model task (envelope + DB filename constant) closing a real dependency-graph gap, and upsized the `.db` import task from M to L after finding three previously-unaddressed edge cases (SAF Uri-to-File requirement, stale WAL sidecar corruption risk, process-restart-not-hot-swap requirement) |

---

## Friction Notes

- Serena's Kotlin language server failed to initialize in this environment (`Error extracting archive`) before any symbolic tool could be used — fell back to Read/Grep/Glob for the entire grounding pass, which my task's own guardrails explicitly permitted. Worth a standing fix at the environment level (re-provision or repair the LSP install) so future planning/coding stages on this repo get the cheaper symbol-level tools instead of whole-file reads.
- The source PRD's task breakdown table was written from prose recall of `RestoreRepository`'s structure, not from a fresh read — it assumed a pre-existing per-binder "apply" separation that doesn't exist in the actual file (it's one monolithic function). Any spec whose task breakdown describes a refactor target should be re-verified against the actual current function shape before being handed to a Coder stage, not trusted from an earlier planning pass's description, even a recent one.
- A real regression trap: adding the Card History branch (task 2) makes an *existing, currently-passing* test (`non-pokedex binder in snapshot is ignored`) assert something false. This class of "my feature makes an old green test wrong, not just incomplete" issue is easy to miss when a task breakdown is written at the prose level — worth explicitly checking existing test files for assertions that encode the *current* (soon-to-be-wrong) behavior whenever a fix targets long-standing "X is not supported yet" logic.
