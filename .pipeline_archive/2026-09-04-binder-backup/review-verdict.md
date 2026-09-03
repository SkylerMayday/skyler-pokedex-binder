# Review Verdict (Aggregated) — Binder Backup: Card History Restore Gap + Local Export/Import

Multi-lens review triggered (16 files, ~1,500 lines changed — over the 8-file/400-line threshold).

**Iteration 0 (initial review):** 3 parallel lenses, all `opus` tier per Item J. Two of the three
background agents hit the account's Opus rate limit mid-run and were reported `failed` by the
task-completion notification — but both had already written their complete verdict file to disk
before the API error terminated the process (consistent with this harness's own documented
behavior: a subagent's file writes typically land before the run that produced them finishes). Both
files were read and confirmed structurally complete (Score block, Verdict line, quoted findings)
before being treated as real output. Scores: correctness 57, security 75, maintainability 54 →
aggregate 54/100, needs-changes, no reject.

**Iteration 1 (after Coder fix-loop pass 1):** first re-review attempt (3 parallel Opus-tier
agents) died instantly on the Opus rate limit a second time, this time all three before writing
anything (reset time had moved to 2:50pm SGT — a longer wait than iteration 0's reset). Per this
project's own documented precedent for this exact failure mode (handoff.md, session 6), did not
attempt a third identical respawn — retried the same 3 lenses at the session's default (non-Opus)
model tier instead, which completed cleanly. Scores this iteration: **correctness 97/100 (ship,
+40)**, **maintainability 91/100 (ship, +37)**, **security 83/100 (needs-changes, +8)**.

## Per-lens scores (iteration 1, current)

| Lens | Score | Verdict | Delta vs. iteration 0 |
|---|---|---|---|
| Correctness + spec-compliance (`.pipeline/review-correctness.md`) | 97/100 | ship | +40 |
| Security + reliability (`.pipeline/review-security.md`) | 83/100 | needs-changes | +8 |
| Maintainability + simplification (`.pipeline/review-maintainability.md`) | 91/100 | ship | +37 |

**Aggregate score = minimum of the three lenses = 83/100.** No lens returned `reject`. Every
iteration-0 finding across all three lenses is independently confirmed fixed. One new finding,
introduced by iteration 1's own fix, keeps this below the 85 ship threshold.

## Overall Verdict: needs-changes (83/100, below the 85 ship threshold — iteration 1 of 2 loop cap)

## The one blocking finding (new this iteration, security lens)

**`BackupImporter.applyDb` closes the live Room database before staging the atomic swap, with no
reopen/restart path if the swap subsequently fails.** Iteration 1 fixed the previous P0 (unguarded
non-atomic replace) by staging at `.importing` + `ATOMIC_MOVE` and catching failures into
`ImportUiState.Error` instead of crashing. But `database.close()` still runs unconditionally before
the swap is attempted, and Room's own contract (verified against the extracted
`room-runtime-2.6.1-sources.jar`) is explicit: *"If the database is closed manually, you must
create a new database using RoomDatabase.Builder.build()."* If the swap throws post-close (the
exact scenario iteration 1's own new regression test simulates — an evicted cacheDir temp file),
the exception is now caught gracefully rather than crashing — but the shared Hilt-singleton
`PokedexDatabase` is left permanently closed, unusable for the rest of the app session, with the UI
showing a normal-looking error dialog over a database that can no longer serve any query. The old
uncaught-crash behavior accidentally self-healed via process death (next launch reopens fresh);
the new graceful catch removed that safety net without replacing it. No data loss, not exploitable,
but a real reliability gap on the single highest-risk code path in this entire feature, never
exercised end-to-end (the new regression test asserts the error surfaces correctly, not that the
app remains usable afterward).

**Recommended fix, in priority order:**
1. Don't close the database until the swap has already succeeded (reorder: swap first via a
   temp-file rename that doesn't require the live handle, close only once the new file is
   confirmed in place) — if this is genuinely not possible on Android (some sources suggest the
   live file can't be replaced while any handle holds it open, even read-only), then:
2. On swap failure post-close, force the same process-restart path the success case already uses
   (worse UX than a graceful in-app error, but matches the actual safety guarantee Room requires —
   a fresh process gets a fresh `RoomDatabase.Builder.build()` regardless of what state the DB file
   itself ended up in).
3. At minimum, surface a distinctly different, more serious error state than a normal
   `ImportUiState.Error` — one that makes clear a restart is now mandatory, not optional, since any
   further DB access in the current process will fail.

## Loop status

This is iteration 1 of the Item I score-threshold auto-loop (cap: 2 iterations, 3 total Reviewer
evaluations). One more Coder→Tester→Reviewer cycle is available before the cap is hit and this
gets escalated to the user regardless of score.

## Iteration 0 findings — all independently confirmed fixed this iteration (history, not action items)

Every finding from the initial review is now fixed and independently re-verified by the relevant
lens(es) in iteration 1 — not re-detailed here to avoid confusion with the one still-open item
above. For the record: the shared-`readVersion()` P0, the non-atomic `.db` swap, the missing
table-identity gate, the `prepareDb` read-failure leak, unbounded `exports/` growth, the
test-evidence-integrity false claim (superseded — iteration 1's `test-results.md` was re-derived
fresh, not fixed by editing the false claim itself), AC1/AC3/AC6 partial compliance, and every
minor finding (main-thread I/O, DI seam convention, `applySnapshot` decomposition, dialog
convention) — full detail on what was wrong and how it was fixed is in each lens's own
`review-*.md` file (iteration 1 content) and in `.pipeline/changes.md`.

## What's genuinely solid (don't re-litigate)

- The `RestoreRepository` refactor itself (shared `applySnapshot()` between cloud restore and local
  import) is clean — maintainability lens diffed it line-by-line, confirmed no dead code, no
  duplication, verbatim-moved overlays.
- Security posture is good: `FileProvider` correctly `exported="false"` with a narrowly-scoped
  `files-path`, credentials (GitHub PAT/Discord webhook) structurally excluded from the export,
  verified independently by two lenses.
- Live on-device verification methodology (fresh XML counts, `pidof` process-kill proof, byte-level
  cache diffing) is genuinely strong evidence where it was actually applied.

## FINAL RESULT — SHIP (iteration 2 of 2, last permitted)

| Lens | Iter 0 | Iter 1 | Iter 2 (final) |
|---|---|---|---|
| Correctness | 57 | 97 | **94** |
| Security | 75 | 83 | **94** |
| Maintainability | 54 | 91 | **90** |
| **Aggregate (min)** | **54** | **83** | **90** |

**Score history: 54 → 83 → 90.** All three lenses ship on the final pass, aggregate 90/100 ≥ 85,
no unresolved P0. The single blocking finding from iteration 1 (DB closed before swap, no
reopen/restart on failure) is independently confirmed fixed by all three lenses, including the
security lens that found it (94/100, up from 83) tracing every statement between `close()` and the
process kill to confirm nothing can escape the new restart path.

**Residual, non-blocking items surfaced this final pass** (P2-P4, explicitly not treated as
blockers by the lenses that found them — moving the goalposts on the last permitted iteration):
- Correctness lens (P2): the two new regression tests for this iteration's fix failed on a clean
  `--rerun-tasks` run with `NoClassDefFoundError: Hilt_MainActivity`, then passed on immediate
  re-run — `[Likely]` a Windows KSP-regeneration race, not a real test-logic bug (production path
  proven live on-device by the Tester independent of this). The regression guard for this specific
  fix can go red for reasons unrelated to the code — worth a `gaps.md` entry so a future red run
  isn't waved off without checking.
- Security lens (P3/P4): `database.close()` sits outside the `try` its own comment implies it's
  inside (cosmetic, no demonstrated failure mode); the new `SharedPreferences` file isn't excluded
  from Android's Auto Backup domain (an unconsumed error message could theoretically ride a device
  transfer, low severity); `backupNow`'s `Boolean` return is discarded (pre-existing from iteration
  1, not newly introduced, correctly not re-opened as a blocker here).
- Maintainability lens (P3): `PendingImportError` is a raw `SharedPreferences`-backed `object`
  rather than following this codebase's `@Singleton`/DataStore convention for persisted state — the
  *technical* choice is correct (DataStore has no synchronous write, would race the process kill)
  but the deviation isn't documented at the point it diverges, unlike a precedented prior deviation
  in `PublishSettingsRepository.kt`. Also: `@VisibleForTesting` on a function with zero test
  callers, and the error message string hand-copied in three places.

None of these block ship. All are candidates for `gaps.md`, not further Coder passes on this
already-shipped feature.
