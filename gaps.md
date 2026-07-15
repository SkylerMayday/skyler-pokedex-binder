# Gaps — PokedexBinderV2

Weakness register. Refreshed at every `wrapcon` via a codebase audit. Remove entries only when
actually fixed and verified, not when merely planned.

## First audit — 2026-07-15

### Data-integrity / migration risk (highest priority)

- **`fallbackToDestructiveMigrationOnDowngrade()` is a live landmine, not a theoretical risk.**
  Confirmed this session: reverting the Room schema version from v8 to v7 wiped Skyler's entire
  local database (Pokédex, Card History, Connecting Art, Personal Collection — everything) with
  no warning, no confirmation dialog, nothing. This was previously accepted as a reasonable
  trade-off for "dev-phone-only installs," but the actual blast radius (total data loss, only
  recoverable if a GitHub publish snapshot happens to exist) is more severe than that framing
  suggests. No fix applied — documenting as an accepted, now-proven risk. Consider: a
  pre-migration automatic local backup (copy `pokedex_binder.db` before any destructive fallback
  fires) as a cheap insurance layer.
- **`Migration7to8Test.kt` doesn't exist.** Every prior Room migration (4→5, 5→6, 6→7) has a
  paired `androidTest` following the `Migration6to7Test` template. The v7→v8 migration (adding
  `unown_binder`) was built, then the standalone Unown binder went through several rebuilds this
  session, and the test was never recreated for the final version. Can't be run in this dev
  sandbox regardless (no emulator) but should exist for parity and for Skyler to run once he has
  a device/emulator available.
- **`Migration5to6Test`/`Migration6to7Test` have never actually executed on a real device** —
  still only statically/DDL-verified, unchanged from before this session. Oldest open gap in the
  project.

### Test coverage gaps

- **No integration/instrumented test verifies a full publish→restore round trip actually
  preserves Connecting Art / Personal Collection / Unown data in practice.** All three were
  built and unit-tested with mocked repositories this session; the actual GitHub Pages output
  (binder.json shape, whether the website project's shelf-grouping logic renders it correctly)
  has never been checked end-to-end.
- **`AppNavigationScreenTest.kt` was deleted during Unown's multiple reworks and never
  recreated** for the current (final) nav layout — no test coverage on route wiring/drawer order
  at all right now.
- **`QuickScanViewModelTest.kt` only covers the new `searchStreaming` wiring** (6 tests added
  this session) — no regression coverage for the rest of `QuickScanViewModel`'s existing
  behavior (slot-matching logic, manual entry flow, secondary-binder fallback).

### Performance / efficiency

- **TCGCSV's ~10-13s full-group-scan now runs on every search, not just retries.** Since
  `searchStreaming` fires TCGCSV concurrently on every `ManualSearchViewModel`/
  `QuickScanViewModel` search (not gated behind a manual "try harder" action anymore), every
  single card search — routine or not — now does a ~217-request background fan-out. Bounded to 8
  concurrent connections, wrapped in try/catch, doesn't block the fast-path UI — but it's real,
  recurring network/battery cost on every search, worth revisiting if it proves noticeable on
  Skyler's actual device/data plan.

### Design decisions worth re-flagging as debt, not fully "gaps"

- **Personal Collection publishes its entire search cache (owned AND unowned cards), not just
  what Skyler owns.** Intentional (confirmed decision, 2026-07-12) — the public site shows every
  card the search found for Charizard/Celebi/etc., dimmed if unowned. Worth re-confirming this
  is still the desired public-facing behavior before the next publish, since it means anyone
  looking at the site can see the full search result set, not just Skyler's actual collection.
- **No manual card-entry fallback for Connecting Art or Personal Collection's search flow** —
  `QuickScanViewModel` has a `ManualEntry` state (type in name/set/number/image URL) for cards
  missing from all 3 APIs; `ManualSearchScreen` (used by Connecting Art + Card History's "Add to
  Secondary") has no equivalent. If a Connecting Art card is missing from all 3 sources, there's
  currently no way to add it at all via that flow.

### Housekeeping

- **Nothing committed since `e42eeb5` (2026-07-10).** A full session's worth of shipped,
  tested work — Unown binder, TCGCSV integration + two real bug fixes, Connecting Art/Personal
  Collection publish support, several UI fixes — is sitting uncommitted. Not a code gap, but a
  real risk (uncommitted work has no backup beyond the local disk).
