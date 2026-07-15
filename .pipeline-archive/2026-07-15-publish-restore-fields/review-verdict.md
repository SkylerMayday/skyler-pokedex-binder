# Reviewer Stage — Verdict (Publish/Restore Snapshot — `language`/`remarks`/`isLocked`)

## Scope

Reviewed the uncommitted working-tree diff (`git status`/`git diff`) against `.pipeline/specs.md`,
cross-checked `.pipeline/changes.md` (Coder) and `.pipeline/test-results.md` (Tester) claims against
the actual source, and independently re-read every touched file in full:
`BinderSnapshot.kt`, `PublishRepository.kt`, `RestoreRepository.kt`, `PersonalCollectionRepository.kt`,
`PersonalCollectionDao.kt`, `Language.kt`, `PersonalCollectionEntry.kt`, plus the test diffs.

Note: `app/build.gradle.kts` and `PokedexDatabaseTest.kt` carry an unrelated, already-applied
pre-existing fix (turbine dependency + positional-args fix) — confirmed out of scope for this
feature, not reviewed further here.

## Intent audit (Phase 2)

Clean. All 7 tasks in `specs.md`'s Task Decomposition trace to concrete diff content, no more and
no less:

- `SnapshotSlot` — 3 new additive fields (`language`/`remarks`/`isLocked`), correct defaults,
  `SNAPSHOT_SCHEMA_VERSION` unchanged at 1. Confirmed by direct read of the diff.
- `buildSnapshot` — all 5 construction sites populate `language`; Pokédex and Unown additionally
  populate `remarks`/`isLocked`; Card History/Connecting Art/Personal Collection correctly leave
  `remarks`/`isLocked` at class defaults (no Room column backing them). Confirmed by direct read.
- `computeDiff` — `isLocked` merged into the existing same-cardId `owned`-comparison branch; the
  `when` remains mutually exclusive so no double-delta risk (edge case #4 in the spec). `language`/
  `remarks` are not referenced anywhere in the function — confirmed by reading the full function
  body, not just the changed line.
- `RestoreRepository` — main_binder, Unown, Connecting Art, Personal Collection overlays extended
  exactly as specced; Card History restore correctly stays absent (pre-existing, intentional
  asymmetry per Non-Goals, not newly introduced).
- No `SNAPSHOT_SCHEMA_VERSION` bump, no new Room migration, no Card History restore path, no UI
  changes — all Non-Goals held.

No scope creep found beyond one small, justified refactor: the PC restore block's `locallyOwned:
Set<String>` was replaced with `localPcEntriesByCardId: Map<String, PersonalCollectionEntry>` so the
owned-check and new language-check share one lookup instead of two. This is in-scope simplification
in service of Task 5, not unrelated drive-by work.

## Scrutiny area 1 — Personal Collection language lookup: can it ever publish a stale/wrong language?

Traced the full data path in `PublishRepository.kt`:

```kotlin
val personalEntries = personalCollectionRepository.getAllEntries()   // publish(), line 122
...
buildSnapshot(..., personalEntries = personalEntries, ...)           // same list, passed through
...
val pcOwnedIds = personalEntries.filter { it.owned }.map { it.cardId }.toSet()   // line 364
val pcLanguageByCardId = personalEntries.associate { it.cardId to it.language }  // line 365
...
language = pcLanguageByCardId[row.cardId] ?: "EN"                     // line 384
```

- `PersonalCollectionEntry.cardId` is `@PrimaryKey` (`data/local/PersonalCollectionEntry.kt` line 8)
  — `associate` cannot silently drop/overwrite a colliding key because Room guarantees uniqueness at
  the DB layer; the in-memory list can't contain duplicate cardIds.
- `pcOwnedIds` and `pcLanguageByCardId` are built from the exact same `personalEntries` list, in the
  same function call, at the same point in time — no separate fetch, no async gap, no risk of one
  map reflecting a different DB state than the other.
- Falls back to `"EN"` only when no entry row exists yet (cache-only card, never toggled owned) —
  matches the Room column's own default, verified by test
  `buildSnapshot personalCollection slot falls back to EN when cache row has no entry row`.

No mechanism found by which this could publish a stale or wrong language. Confidence: high (9/10),
based on direct read of the fetch-to-use path, not pattern-matching.

## Scrutiny area 2 — can RestoreRepository changes clobber existing isLocked/remarks?

Read the full 244-line file. Every write path uses targeted field copies, not full-entity
replacement of unrelated data:

- **main_binder / Unown "restored" branches** (`entry.copy(assignedCardId = ..., language = ...,
  remarks = ..., isLocked = ...)`) — Kotlin data-class `.copy()` only overwrites the named
  parameters; every other field (including any not mentioned) carries forward from the original
  `entry` unchanged. Confirmed by reading both branches directly.
- **main_binder / Unown "cleared" branches** — deliberately omit `language`/`remarks`/`isLocked`
  from the `.copy(...)` call entirely, so clearing an assigned card cannot touch them. Verified by a
  dedicated test, `main_binder cleared branch leaves language remarks and isLocked untouched`, which
  asserts `isLocked`/`remarks` survive a card-clear with non-default starting values.
- **Personal Collection language write** — `PersonalCollectionRepository.updateLanguage` issues
  `@Query("UPDATE personal_collection_entry SET language = :language WHERE cardId = :cardId")`
  (`PersonalCollectionDao.kt` line 54) — a single-column `UPDATE`, not an `@Upsert`/full-entity
  replace, so it structurally cannot touch `owned`. Traced the ordering risk explicitly: in the
  `snap.owned && !isLocallyOwned` branch, `setOwned(cardId, true)` runs first (creating the entry
  row via `INSERT OR REPLACE` with `owned=true`), then the language check runs against the
  **pre-loop-snapshot** `localEntry` (still reflecting the old, possibly-absent row) and calls
  `updateLanguage`, which re-checks `dao.getEntry(cardId)` — now non-null since `setOwned` already
  ran — so it skips its own defensive row-creation and issues only the column-scoped `UPDATE`.
  `owned=true` from the prior step is never overwritten. Symmetric reasoning holds for the
  `removeOwned` branch (row deleted, then `updateLanguage` recreates it with `owned=false`, which
  matches the just-applied clear — not a clobber).
- **Connecting Art "restored"/"cleared"** — same `.copy()` discipline; cleared branch explicitly
  does not touch `language`.

No clobber path found for `isLocked`/`remarks` (or `owned`, checked as a related risk since it sits
next to the new language write). Confidence: high (9/10).

## Quality pass (Phases 3-4)

- **Correctness**: `computeDiff`'s `when` branches remain mutually exclusive (cardId-added / cardId-
  removed / cardId-replaced / same-cardId-flag-change / no-change) — the new `isLocked` condition is
  additive to the last branch only, can't double-fire.
- **Reliability**: `Language.fromRaw` defensively falls back to `EN` on null/unrecognized values
  (`runCatching { valueOf(it) }.getOrNull() ?: EN`) rather than throwing — a malformed/legacy
  `snap.language` value can't crash restore.
- **Maintainability**: the `localPcEntriesByCardId` map refactor is a net simplification (one lookup
  instead of two) with no behavior change to the existing owned-toggle logic — verified by reading
  the before/after `when` block shape side by side in the diff.
- **Efficiency**: no new DB round-trips — `getAllEntries()` was already fetched once per restore;
  the language check reuses the same in-memory map.

No bugs found in the categorized bug-hunt pass (logic, null-handling, async/concurrency, data
integrity, resource management, edge cases). The two areas with the highest a priori risk
(cardId-keyed map correctness, partial-entity-write safety) were exactly the ones independently
traced above and found sound.

## What looks good

- Additive-only field design, following the `owned` precedent exactly (no schema version bump,
  Kotlin defaults absorb old-JSON backward compatibility) — and there is now a dedicated regression
  test (`BinderSnapshot round-trips a full old-shape JSON without the 3 new fields`) that actually
  exercises Moshi parsing an old-shape JSON through the generated adapter, not just asserting on
  in-memory construction.
- Every restore write path is either a `.copy()` (preserves untouched fields) or a targeted
  single-column `UPDATE` (can't touch sibling columns) — structurally forecloses the clobber risk
  this spec's own scrutiny area called out, rather than merely avoiding it by convention.
- `computeDiff`'s freeform-vs-diff-worthy line (`isLocked` in, `language`/`remarks` out) is a
  documented judgment call from the spec, correctly implemented and correctly tested in both
  directions (`isLocked` flip → one delta; `language`+`remarks` change together → no delta).
- Card History's absent restore path is confirmed, not merely assumed — no `BINDER_ID_CARD_HISTORY`
  reference, no `SecondaryBinderEntry`/`SecondaryBinderDao` reference anywhere in
  `RestoreRepository.kt`.
- Test suite covers exactly the two scrutiny areas from this brief with dedicated cases (PC fallback-
  to-EN, PC skip-when-matching, cleared-branch field preservation) rather than only happy-path
  coverage.

## Findings

None reach the confidence threshold for the report. No P0/P1/P2/P3 findings.

## Verdict: SHIP
