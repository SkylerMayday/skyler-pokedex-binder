# Tester Stage — Test Results (Publish/Restore Snapshot — `language`/`remarks`/`isLocked`)

## Overall verdict: PASS

All 5 spec-mandated risk areas were independently re-verified against the actual diff (not the
Coder's handoff narrative), one real test-coverage gap was found and closed (Moshi backward
compatibility), and the full unit test suite passes fresh in this session.

## Independent verification (not trusting `changes.md` — read the real diff)

### 1. `SnapshotSlot` (`publish/model/BinderSnapshot.kt`)

Confirmed 3 new additive fields exist exactly as specced: `language: String = "EN"`,
`remarks: String? = null`, `isLocked: Boolean = false`, each with a backward-compat comment
matching the `owned` precedent. `SNAPSHOT_SCHEMA_VERSION` unchanged at `1`.

### 2. All 5 `buildSnapshot` construction sites (`PublishRepository.kt`)

Read the file directly, not the summary:
- **Pokédex** (`MainBinderEntry.toSnapshotSlot()`, line ~426): direct 1:1 copy of all 3 fields. Confirmed.
- **Card History** (line ~310, inline): `language = entry.language` only; `remarks`/`isLocked` left at class defaults. Confirmed.
- **Connecting Art** (line ~343, inline): `language = slot.language` only. Confirmed.
- **Personal Collection** (line ~365-385, inline) — the flagged likely-mistake spot: `pcLanguageByCardId = personalEntries.associate { it.cardId to it.language }` is built alongside the existing `pcOwnedIds` set, then `language = pcLanguageByCardId[row.cardId] ?: "EN"`. This correctly sources `language` from `PersonalCollectionEntry` (keyed by `cardId`), **not** from `PersonalCollectionCache` (which has no language column) — the wiring the spec called out as a likely error point is done correctly. Cache-only rows (no entry yet) fall back to `"EN"`, verified by test `buildSnapshot personalCollection slot falls back to EN when cache row has no entry row`.
- **Unown** (line ~399-411, inline): direct 1:1 copy of all 3 fields. Confirmed.

### 3. `computeDiff` (`PublishRepository.kt`, line ~500-509)

```kotlin
baselineCardId != null && nextCardId != null &&
    baselineCardId == nextCardId &&
    (baselineSlot!!.owned != nextSlot!!.owned || baselineSlot.isLocked != nextSlot.isLocked) -> {
        deltas += SlotDelta(type = ChangeType.REPLACED, ...)
    }
```
`isLocked` is merged into the existing same-cardId branch, exactly per spec. `language`/`remarks`
are **not** referenced anywhere in `computeDiff` — confirmed by reading the full function body,
not just the changed branch. Wrote a targeted regression test for this exact behavior (see below).

### 4. `RestoreRepository.kt` overlay blocks — read the full file (243 lines)

- **main_binder** (restored branch, line 91-99): copies `language`, `remarks`, `isLocked` from `snap`. Cleared branch (101-109) does **not** touch these 3 fields — confirmed by reading it; matches spec's "slot-level property, not card-level" reasoning.
- **Connecting Art** (restored branch, line 134-140): copies `language` only (no Room columns for the other two). Cleared branch (142-146) untouched.
- **Personal Collection** (line 156-188): reworked to `localPcEntriesByCardId` map; owned-toggle unchanged in shape, plus a new language-restore step that calls `personalCollectionRepository.updateLanguage(cardId, Language.fromRaw(snap.language))` only when the locally stored language (or the `"EN"` default when no entry row exists) differs from `snap.language`.
- **Unown** (restored branch, line 205-215): copies all 3 fields, same shape as main_binder. Cleared branch (217-224) untouched.
- **Card History**: confirmed by reading the entire file top to bottom — no `BINDER_ID_CARD_HISTORY` constant, no restore block, no reference to `SecondaryBinderEntry`/`SecondaryBinderDao` anywhere in `RestoreRepository.kt`. This is the pre-existing, documented one-way asymmetry (append-only publish, no restore) — not something newly introduced, and not something the Coder silently added. Confirmed deliberate per spec Non-Goals.

### `PersonalCollectionRepository.updateLanguage` / `Language.fromRaw` — verified they actually exist, not fabricated

```kotlin
suspend fun updateLanguage(cardId: String, language: Language) {
    if (dao.getEntry(cardId) == null) dao.upsertEntry(PersonalCollectionEntry(cardId = cardId, owned = false))
    dao.updateLanguage(cardId, language.name)
}
```
`Language.fromRaw(raw: String?)` defensively falls back to `EN` on null/unrecognized values via
`runCatching { valueOf(it) }.getOrNull() ?: EN` — never throws on a malformed snapshot value.
Both were pre-existing (added in an earlier session per the repository's doc comment), not new
code introduced by this feature — confirmed by reading the file, resolving the spec's Task 5 open
question honestly rather than taking the Coder's claim at face value.

### 5. Backward compatibility — gap found and closed

No existing test exercised Moshi actually parsing an old `binder.json` shape (missing `owned`,
`language`, `remarks`, `isLocked` entirely) through the generated adapter. This is exactly the
scenario edge case #1 in the spec depends on and is the one place a regression (e.g. someone later
making a field non-nullable, or removing a default) would silently break restore for every
previously-published binder. Added two new tests to `PublishRepositoryTest.kt`:

- `SnapshotSlot parses old JSON missing owned language remarks and isLocked with safe defaults` — parses a bare pre-`owned`-era `SnapshotSlot` JSON object directly through `moshi.adapter(SnapshotSlot::class.java)` and asserts `owned=true, language="EN", remarks=null, isLocked=false`.
- `BinderSnapshot round-trips a full old-shape JSON without the 3 new fields` — parses a full nested `BinderSnapshot` (schemaVersion/binders/sections/slots) shaped like a binder.json published before this change (has `owned` but not `language`/`remarks`/`isLocked`) and asserts the same defaults surface at the nested slot level.

Both pass. This closes the one verification gap between "compiles and unit tests pass" and "an
actual old file on GitHub today will restore correctly."

## Testing layer decision (Phase 1)

Pure-JVM unit tests are the correct and sufficient layer here: `buildSnapshot`/`computeDiff` are
pure functions over in-memory data, `restore(...)` is fully mockable via the existing DAO/repository
interfaces (no real Room/SQLite needed), and Moshi's generated adapter runs identically on the JVM
as on-device. No integration/E2E/instrumented test is needed for this serialization-only change —
consistent with how the rest of `publish/` is already tested in this codebase.

## Environment (Phase 2)

`gradlew.bat` requires explicit env vars in this sandbox (daemon startup fails with a misleading
error otherwise):
```
JAVA_HOME=D:/jdk17/jdk-17.0.14+7
TEMP=C:/Windows/Temp
TMP=C:/Windows/Temp
```
Confirmed working for every command below. No new test infrastructure was needed — existing
Vitest-equivalent (JUnit4 + MockK + kotlinx-coroutines-test) setup already in place and used.

## Verification evidence (Phase 6 — fresh, this session)

### Scoped run — `publish` package only, immediately after adding the 2 new backward-compat tests

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 24s
31 actionable tasks: 4 executed, 27 up-to-date
```
Parsed the actual `TEST-*.xml` files directly (not the Gradle summary line):
```
TEST-com.skyler.pokedexbinder.publish.PublishRepositoryTest.xml: tests="40" skipped="0" failures="0" errors="0"
TEST-com.skyler.pokedexbinder.publish.RestoreRepositoryTest.xml: tests="23" skipped="0" failures="0" errors="0"
```
(38→40 in PublishRepositoryTest reflects the 2 new backward-compat tests added this stage;
RestoreRepositoryTest unchanged at 23, all previously passing.)

### Full unit test suite (Definition of Done requirement)

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 20s
31 actionable tasks: 1 executed, 30 up-to-date
```
Aggregated every `TEST-*.xml` under `app/build/test-results/testDebugUnitTest/` by script (not
eyeballing the console): **168 tests total, 0 failures, 0 errors, 0 skipped** across the whole
project (up from the Coder's reported 166 by the 2 new backward-compat tests). No regressions
anywhere outside `publish/`.

## Findings by acceptance criterion (spec Definition of Done)

- [x] `SnapshotSlot` carries the 3 fields with additive defaults, schema version unchanged — confirmed by direct read.
- [x] All 5 `buildSnapshot` sites populate `language`; Pokédex + Unown also populate `remarks`/`isLocked` — confirmed by direct read + existing/new tests, including the Personal Collection cardId-lookup wiring specifically flagged as a risk spot.
- [x] `computeDiff` fires on `isLocked` change, not on `language`/`remarks`-only change — confirmed by direct read + `computeDiff isLocked flip on same card is REPLACED` and `computeDiff language or remarks only change on same card is no change` (the latter changes both language AND remarks simultaneously in one test, so a future regression that wires either field into the diff condition would be caught).
- [x] Restore overlays for main_binder, Unown, Connecting Art, Personal Collection read back the applicable fields; Card History restore correctly stays absent — confirmed by direct full-file read of `RestoreRepository.kt`, not just trusting the changelog.
- [x] Unit tests updated/added, passing — 168/168, 0 failures.
- [x] `./gradlew testDebugUnitTest` run clean, fresh, this session — evidence above.
- [x] No changes outside `D:\Claude Projects\PokedexBinderV2` — confirmed via `git status --short`, all changed paths are under the project root plus the pre-existing unrelated `app/build.gradle.kts` / `PokedexDatabaseTest.kt` diff (see below).

## Gaps / notes (not blocking, flagged honestly)

1. Two new tests were added by this Tester stage (not by the Coder) to close the backward-compat
   verification gap — see "Backward compatibility" section above. This is new test code, not a
   defect in the implementation; the implementation was already correct, but nothing had actually
   exercised the Moshi-parsing-old-JSON path until now.
2. `app/build.gradle.kts` and `app/src/androidTest/.../PokedexDatabaseTest.kt` carry a pre-existing,
   already-applied, unrelated diff (adds `androidTestImplementation(libs.turbine)`, fixes two
   positional `MainBinderEntry(...)` calls) — confirmed via `git status`/`git diff`, not touched in
   this stage, consistent with the Coder's note. Not part of this feature's acceptance criteria.
3. No Compose UI / instrumented test layer touches this feature (none needed — this is a pure
   data-plumbing change with no UI surface, per spec Non-Goals).

## Recommendation

**Proceed to Reviewer.** No regressions, no failing tests, every spec-flagged risk area (PC
cardId/language wiring, isLocked-only diff condition, Card History asymmetry, backward
compatibility) was independently re-verified against the actual code rather than the handoff
narrative, and the one real gap found (missing backward-compat test) was closed in this stage.
