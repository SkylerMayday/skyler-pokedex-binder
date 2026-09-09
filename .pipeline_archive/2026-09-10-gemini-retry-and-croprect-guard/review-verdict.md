# Code Review: retry-once-with-backoff on transient Gemini API failure

**Reviewer:** review-overall (single-lens — 4 files/129 lines, no security-sensitive path)
**Date:** 2026-09-10
**Scope:** `app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt` (+39/-7),
`app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt` (+61/-3), verified via
`git diff master --stat` on exactly these two paths. `ImageProxyExt.kt`/`ImageProxyExtTest.kt`
confirmed out of scope (separate pre-existing work sharing the branch, per orchestrator's note and
independently confirmed via `git status`) and not evaluated against this spec.

---

## Summary

Implementation matches `.pipeline/specs.md`'s skeleton line-for-line — `executeWithRetry()`'s
decision table (429 immediate-fail → 5xx close-retry-throw → other-4xx immediate-fail →
`SocketTimeoutException` retry-rethrow → success-return) is exactly what the spec's Technical Layer
section specified, no structural deviation. Independently re-verified the test evidence rather than
trusting `test-results.md` secondhand: pulled the actual fresh JUnit XML from
`app/build/test-results/testDebugUnitTest/` (not the copied sidecar) and summed all 32 files myself
— **296 tests, 0 failures, 0 errors**, matching the claimed count exactly. Also independently hit
the identical `Unable to establish loopback connection` Gradle daemon wall when attempting my own
rerun, which corroborates (rather than merely trusts) the "environment issue, not diff-related"
claim in `changes.md`.

**Recommendation:** Approve.

**Critical issues:** 0
**Important issues:** 0
**Minor issues:** 1
**Suggestions:** 1

---

## Intent audit (Phase 2)

No scope creep, no missing requirements. Every spec acceptance criterion traces to a concrete diff
line:

| Spec AC | Diff evidence |
|---|---|
| 503→200 retries, 2 requests | `GeminiCardScanner.kt:111-118` (5xx close+delay+continue); test `scan retries once on 503 then succeeds` |
| 5xx exhausted → `IOException` after 3 requests | `:113-117` (`attempt < MAX_SCAN_RETRIES` false → throw); test `scan throws IOException on 500` (3 enqueued, `requestCount==3` asserted) |
| Timeout→200 retries | `:102-108` (`catch (SocketTimeoutException)` → delay+continue); test `scan retries once on timeout then succeeds` |
| Timeout exhausted → original exception | `:108` (`throw e`, not wrapped); test `scan rethrows original timeout after retries exhausted` (type + count asserted) |
| 429 unaffected | `:110` (`if (response.code == 429) throw RateLimitException()`, checked first); existing test unmodified in diff (confirmed — no lines touched around it) |
| 400-404 unaffected | `:119` (`if (!response.isSuccessful) throw ...`, unchanged) |
| Body closed before retry | `:112` (`response.close()` before the 5xx continue) |
| `scan throws IOException on 500` updated to 3 responses | `GeminiCardScannerTest.kt:105-112` |
| Full suite green | Independently confirmed, see Summary |

Out-of-scope items from the spec (exponential backoff, new `ScannerState`, `OkHttpClient` timeout
changes, `ImageProxyExt` gap) — none present in the diff. Clean.

One spec AC has no dedicated test (see Minor issues below) — informational, not a gate per Phase 2's
own rule (only HIGH-impact discrepancies block).

---

## Critical issues (blockers)

None found.

## Important issues

None found.

## Minor issues

- **`GeminiCardScanner.kt` (400/401/403/404 path, unchanged) / no dedicated test.** Spec AC: *"Given
  Gemini returns 400/401/403/404, when scan() is called, then the existing IOException("Gemini
  error: $code") throws immediately, zero retries."* — `if (!response.isSuccessful) throw
  IOException("Gemini error: ${response.code}")` (`GeminiCardScanner.kt:119`) is byte-for-byte
  unchanged from pre-diff code and not inside the retry loop, so it's correct by inspection. But no
  test (old or new) exercises this branch specifically — `test-results.md` already flagged this
  honestly as a known gap rather than hiding it. Low risk: untouched code path, not in the spec's own
  Task 1-5 breakdown. Not blocking.

## Suggestions for follow-up

- `GeminiCardScannerTest.kt:127-131` and `:143-147` build near-identical `shortTimeoutClient` +
  scanner-rewire blocks in two separate tests. Minor duplication; a small `@Before`-adjacent helper
  would remove it if this file grows more timeout-based tests. Not worth blocking a 2-line-duplicate
  in an otherwise clean diff.

---

## What looks good

- Retry/no-retry decision table cross-checked line-by-line against every spec branch — no deviation.
- `response.close()` placed correctly before every retry continue (`:112`) — the acceptance
  criterion's explicit connection-leak concern is actually satisfied in code, not just claimed.
- Named constants (`MAX_SCAN_RETRIES`, `SCAN_RETRY_DELAY_MS`) with inline rationale comments, no
  retry-policy abstraction introduced — matches spec's explicit "no new abstraction" scope boundary.
- `SUCCESS_BODY` extraction removes real duplication the 3 new tests would otherwise introduce,
  without touching test semantics (existing "returns ParsedCardInfo" test still asserts the same
  fields).
- Test evidence independently reproducible from a real on-disk Gradle artifact
  (`app/build/test-results/testDebugUnitTest/TEST-*.xml`, timestamped 2026-09-10 01:41:08 +0800,
  32 files, self-summed to 296/0/0) — not just a claim in a markdown file.
- Honest process-deviation disclosure (Tester rate-limited, orchestrator substituted) and honest
  gap disclosure (400-404 untested) both present in `test-results.md` rather than smoothed over.

---

## Dimension scorecard

| Dimension | Pass | Issues |
|---|---|---|
| Correctness | ✅ | None found; full decision-table trace clean |
| Security | ✅ | N/A — no new input/auth/secret surface |
| Performance | ✅ | Worst case +2×500ms+timeout, explicitly accepted in spec's risk table |
| Reliability | ✅ | This *is* the reliability feature; body-leak criterion verified in code |
| Maintainability | ✅ | Minor test duplication only (see Suggestions) |

---

## Score

**Spec compliance: 23/25.** Every AC traces to a concrete diff line except the 400-404 branch,
which is correct-by-inspection (unchanged code) but has zero dedicated test coverage — a real,
if minor, gap against an explicit spec bullet. −2.

**Correctness & bug-freedom: 25/25.** Traced every branch of `executeWithRetry` by hand (attempt
loop bounds, 429-before-5xx ordering, close-before-retry, original-exception-rethrow-not-wrapped) —
no bugs found. The unreachable trailing `throw` after the loop is normal Kotlin exhaustiveness
boilerplate, not a defect.

**Security & reliability: 20/20.** No new security surface. Reliability is the entire point of this
change and the leak-prevention criterion (`response.close()`) is verified present at the right
point in the control flow.

**Maintainability & simplification: 14/15.** Clean, minimal, named constants, no unneeded
abstraction. −1 for the two near-identical `shortTimeoutClient` setup blocks in the test file
(cosmetic, flagged as follow-up only).

**Test evidence quality: 15/15.** Verified independently from the raw on-disk JUnit XML artifacts
(not the sidecar copy, not the markdown claim) — self-summed 296 tests/0 failures/0 errors across
all 32 files, matching `test-results.md` exactly. Also independently reproduced the same Gradle
daemon environment failure the orchestrator described, corroborating rather than just accepting
the "not diff-related" claim.

**Total: 97/100.**

No prior `review-verdict.md` existed for this branch — first pass, no delta to report.

---

## Sign-off

**Approval status:** Approved
**Date:** 2026-09-10

**Verdict: ship.** Score 97/100 ≥ 85, zero P0/blocking findings. The one minor gap (400-404 path
untested) is informational per Phase 2's own escalation rule (only HIGH-impact discrepancies block)
and doesn't affect shipped behavior — the code path is unchanged from pre-diff.
