# Test Results: retry-once-with-backoff on transient Gemini API failure

**Note on process deviation:** the `pipeline-tester` subagent hit the account's session rate limit
(`resets 5am Asia/Singapore`) and died before writing any output. Rather than immediately respawn
into the same wall, the orchestrator performed this stage's verification directly — fresh test run
plus a manual acceptance-criteria trace against the actual diff. Flagged here per the pipeline's own
transparency convention, not silently absorbed.

## Fresh test run (orchestrator, this session)

Command: `.\gradlew.bat testDebugUnitTest --rerun-tasks` (stale `test-results/testDebugUnitTest/`
deleted first, per this repo's own convention).

```
BUILD SUCCESSFUL in 1m 46s
31 actionable tasks: 31 executed
EXIT CODE: 0
```

Fresh XML count (not Gradle's own summary): **296 tests, 0 failures, 0 errors.**

`GeminiCardScannerTest.xml` (sidecar: `.pipeline/evidence/test-results-geminicardscanner.xml`) — all
7 cases pass:

```
scan throws RateLimitException on 429                          time="4.528"
scan retries once on 503 then succeeds                         time="0.52"
scan retries once on timeout then succeeds                     time="0.715"
scan throws IOException on 500                                 time="1.02"
scan returns nulls for missing fields                           time="0.011"
scan rethrows original timeout after retries exhausted         time="1.615"
scan returns ParsedCardInfo on success                          time="0.012"
```

(The 429 test's 4.5s is first-test-in-class JVM/MockK warm-up, not retry delay — the 429 branch
throws on the very first loop iteration, before any `delay()` call, confirmed by code trace below.)

## Acceptance criteria trace (spec → code → test)

| # | Spec AC | Code (`GeminiCardScanner.kt`) | Test | Result |
|---|---|---|---|---|
| 1 | 503 then 200 → parsed result, 2 requests | `executeWithRetry` loop: 5xx→close+delay+continue; success→return | `scan retries once on 503 then succeeds` | ✅ requestCount==2 asserted |
| 2 | 5xx on every attempt (3 total) → `IOException`, exactly 3 requests | attempt 2 (0-indexed) not `< MAX_SCAN_RETRIES` → throw | `scan throws IOException on 500` (3× enqueued) | ✅ requestCount==3, type asserted |
| 3 | Timeout attempt 1, success attempt 2 | `catch (SocketTimeoutException)` → delay+continue | `scan retries once on timeout then succeeds` | ✅ requestCount==2 |
| 4 | Every attempt times out → original `SocketTimeoutException` propagates after 3 attempts | attempt 2 not `< MAX_SCAN_RETRIES` → `throw e` (original, not wrapped) | `scan rethrows original timeout after retries exhausted` | ✅ type + requestCount==3 asserted |
| 5 | 429 → `RateLimitException` immediately, zero retries | `if (response.code == 429) throw RateLimitException()` — checked before the 5xx branch, first loop iteration | `scan throws RateLimitException on 429` (unmodified) | ✅ passes; no retry possible even in principle since only 1 response is enqueued and the code path can't reach the retry branch for 429 |
| 6 | 400/401/403/404 → existing `IOException`, zero retries | `if (!response.isSuccessful) throw IOException(...)` — unchanged from pre-diff code, not inside any retry branch | No new/existing test for this specific case | ⚠️ Not explicitly test-covered (see Findings) |
| 7 | 5xx retry closes the failed response body first | `response.close()` called before `delay()`/`continue` in the 5xx branch | Not independently assertable in a JVM test (no leak-detection harness in this suite) | ✅ code-verified, not test-verified (spec's own risk table treats this as an acceptance criterion satisfied by the code change itself, not a new test) |
| 8 | `scan throws IOException on 500` updated to 3 responses | — | Confirmed: `repeat(3) { server.enqueue(...) }`, `assertEquals(3, server.requestCount)` | ✅ |
| 9 | Full suite green, fresh XML | — | 296/296, 0 failures | ✅ |

## Findings

- **Minor gap, not a regression**: AC #6 (other 4xx → no retry) has no dedicated new test. The code
  path is byte-for-byte unchanged from before this diff (`!response.isSuccessful` branch, same as
  pre-existing behavior) and isn't in the Coder's task breakdown (`specs.md` Tasks 1-5 only cover
  429/5xx/timeout). Low risk — untouched code, not newly introduced. Worth a follow-up test if this
  file is touched again, not blocking for this change.
- **Minor**: `scan throws IOException on 500` asserts `caught is java.io.IOException` but not the
  exact message (`"Gemini error: 500"`). Matches this test's own pre-existing assertion style
  (loose-typed, not message-exact) — not a new looseness introduced by this diff.
- Hand-traced every retry-count arithmetic case (0-indexed `attempt` loop, `MAX_SCAN_RETRIES = 2` →
  3 total attempts) against both the code and the fresh per-test `requestCount` assertions — all
  match exactly, no off-by-one.

## Status

**PASS.** Decision table (429 no-retry / other-4xx no-retry / 5xx retry-then-throw / timeout
retry-then-rethrow) is implemented exactly as specified and exercised by real, fresh, passing tests
for every branch except the pre-existing/unchanged other-4xx path. No blocking issues found.
Proceeding to Reviewer.
