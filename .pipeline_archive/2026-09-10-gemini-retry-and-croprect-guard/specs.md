# Feature Spec: Retry-once-with-backoff on transient Gemini API failure

**Status:** Draft
**Date:** 2026-09-10

---

## TL;DR

`GeminiCardScanner.scan()` currently surfaces any non-2xx response or thrown exception straight to `ScannerState.Error` with zero retry. Found live on Skyler's S26 Ultra (2026-09-10): two consecutive scans hit Gemini 503s / a timeout, each a dead end requiring a manual re-scan. Add a bounded retry (up to 2 extra attempts, short fixed backoff) for 5xx responses and client-side socket timeouts only, reusing the existing 429/`RateLimitException` branch as the pattern to extend — not replace.

---

## Problem

`scan()` (`app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt:55-90`) makes one `okHttpClient.newCall(request).execute()` and either returns a parsed result or throws. `ScannerViewModel.processImage()` (`ui/scanner/ScannerViewModel.kt:109-116`) catches `RateLimitException` specially (→ `ScannerState.RateLimited`, cooldown UI) and everything else generically (→ `ScannerState.Error`, dead end, manual re-scan required). A transient 503, or a slow response hitting the 30s OkHttp read timeout (`NetworkModule.kt:36-38`, shared singleton client → `java.net.SocketTimeoutException`, an `IOException` subtype), is indistinguishable today from a genuine unrecoverable failure.

### Evidence

- Live session log, 2026-09-10: Castform scan → Gemini 503. Furfrou scan → client timeout. Both surfaced as `ScannerState.Error` immediately, no retry (`handoff.md` session 12, `gaps.md` "New this session, not fixed").
- Confirmed not caused by the same-session `GUIDE_FRAME_WIDTH_RATIO` bump — image sent to Gemini is capped at 1024px longest side regardless of crop size (`scaleBitmap`, `maxDim=1024`) — external Gemini flakiness, not a self-inflicted payload-size regression.
- The 429 path (`RateLimitException`, `scan()` line 72) already proves the codebase's pattern for a status-code-specific branch inside `scan()` before the generic `!response.isSuccessful` throw.

---

## Proposal

Extend `scan()`'s error handling to retry on two transient conditions — a 5xx response, or a `SocketTimeoutException` from `execute()` — up to `MAX_SCAN_RETRIES` additional attempts, short fixed delay between attempts. All other failure modes (429, other 4xx, non-timeout `IOException`, parse failure) are unchanged: fail on first occurrence. Only after retries are exhausted does the original exception propagate to `ScannerViewModel`'s existing `catch (e: Exception)` → `ScannerState.Error` — no ViewModel/UI change needed.

### How it works (high level)

1. `scan()` builds the request exactly as today (unchanged).
2. New private suspend helper `executeWithRetry(request: Request): Response` replaces the single `execute()` call.
3. 429 → `RateLimitException` immediately, no retry (unchanged, preserves cooldown UX).
4. 5xx → closes response body; retries with delay if attempts remain; once exhausted, throws `IOException("Gemini error: $code")` (same message format as today).
5. Other non-2xx (400/401/403/404) → throws immediately, no retry.
6. `SocketTimeoutException` → retries with delay if attempts remain; once exhausted, rethrows original.
7. Success → returns `Response` for existing (unchanged) body-parsing logic.

---

## User stories

- As Skyler scanning a card, I want a transient Gemini 503/timeout retried automatically, so one flaky API response doesn't force a manual re-scan.
- As Skyler scanning a card, I want a genuine failure (bad API key, real outage, malformed response) to still surface immediately as an Error, so retry logic doesn't mask a real problem.
- As Skyler hitting the rate limit (429), I want that path untouched, so the existing cooldown/backpressure UX doesn't get retried into worse rate-limiting.

---

## Acceptance criteria

- [ ] **Given** Gemini returns 503 then 200, **when** `scan()` is called, **then** it returns the parsed result with no exception; mock server recorded 2 requests.
- [ ] **Given** Gemini returns 503 on every attempt (`MAX_SCAN_RETRIES + 1` total), **when** `scan()` is called, **then** it throws `IOException("Gemini error: 503")` after exactly `MAX_SCAN_RETRIES + 1` requests.
- [ ] **Given** the client throws `SocketTimeoutException` on attempt 1 and succeeds on attempt 2, **when** `scan()` is called, **then** it returns the parsed result, no exception.
- [ ] **Given** every attempt times out, **when** `scan()` is called, **then** the original `SocketTimeoutException` propagates after `MAX_SCAN_RETRIES + 1` attempts.
- [ ] **Given** Gemini returns 429, **when** `scan()` is called, **then** `RateLimitException` throws immediately, zero retries (existing test `scan throws RateLimitException on 429` still passes unmodified).
- [ ] **Given** Gemini returns 400/401/403/404, **when** `scan()` is called, **then** the existing `IOException("Gemini error: $code")` throws immediately, zero retries.
- [ ] **Given** a retryable 5xx response, **when** a retry is about to happen, **then** the failed response's body is closed first (`response.close()`) — no connection leak.
- [ ] Existing test `scan throws IOException on 500` updated to enqueue `MAX_SCAN_RETRIES + 1` 500 responses (currently 1) and assert `server.requestCount == MAX_SCAN_RETRIES + 1` — otherwise hangs/fails against new retry behavior.
- [ ] `.\gradlew.bat testDebugUnitTest --rerun-tasks` passes, including all new/modified `GeminiCardScannerTest` cases.

---

## Out of scope

- Retrying `RateLimitException`/429 — has its own cooldown/backpressure design (`ScannerState.RateLimited`, `consecutiveRateLimits`, `IdleWithGrace`); retrying here would fight it.
- Any new `ScannerState` ("Retrying…" UI) — `Scanning` already covers the window; scope is explicitly minimal.
- Retrying non-timeout `IOException`s (`UnknownHostException`, `ConnectException`) — scope is "5xx or client-side timeout," a `SocketTimeoutException`-specific catch, not blanket `catch (IOException)`.
- Exponential/jittered backoff, configurable retry count, or a retry-policy abstraction — a short fixed delay and two named constants, per "no new abstraction beyond what's needed."
- Changing the shared `OkHttpClient`'s 30s timeouts in `NetworkModule.kt` — unrelated, shared by every other API client.
- The separately-tracked P2 gap (`ImageProxyExt.kt` degenerate-`cropRect` throw) and the `GUIDE_FRAME_WIDTH_RATIO` re-tune — both in `gaps.md`, not part of this change.

---

## Dependencies

- Pattern to extend already read/understood: 429/`RateLimitException` branch (`GeminiCardScanner.kt:55-90`).
- `kotlinx.coroutines.delay` already used elsewhere in this module (`BackfillCardNamesUseCase.kt`, `RestoreViewModel.kt`) — no new dependency.
- External: Gemini API — no contract change, only how the app reacts to its transient failures.
- Team: none — single production file + its existing test file.

---

## Open questions

- [ ] `MAX_SCAN_RETRIES = 2` (3 total attempts) vs 1 retry (2 total), per "retry once (maybe twice)"? **Owner:** Skyler. Defaulting to 2 (stated ceiling) unless told otherwise.
- [ ] `SCAN_RETRY_DELAY_MS = 500` acceptable, or tied to observed Gemini recovery timing? **Owner:** Skyler. Non-blocking, retunable post-ship like `GUIDE_FRAME_WIDTH_RATIO`.

---

## Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Worst case adds up to 2×30s timeout + 2×backoff before Error — longer spinner, no new feedback | Low | Med | Accepted in scope (no new UI state); retune constants later if annoying. |
| Retrying 5xx without closing the failed response's body leaks an OkHttp connection over repeated scans | Med if missed | Med | Explicit acceptance criterion + Task 1 (`response.close()` before retry). |
| Existing `scan throws IOException on 500` test enqueues only one 500 — hangs/fails against multi-attempt behavior if not updated | High w/o fix | Low | Explicit acceptance criterion + Task 2. |
| `delay()` inside `withContext(Dispatchers.IO)` is real wall-clock time in JVM tests (not skipped by `runTest`'s virtual time) — new retry tests add ~0.5-1.5s real time each | High | Low | Acceptable; not a correctness risk. Don't mock `delay` to "fix" it. |

---

## Smallest shippable increment

**MVP:** Exactly the scope above — a private `executeWithRetry()` helper, two named constants, retry on 5xx and `SocketTimeoutException` only, unchanged 429/other-4xx/parse-error behavior, updated + new tests. Already the smallest correct version — one function's control flow in one file.

**Future iterations (not in this change):** a "Retrying… (attempt N/M)" UI indicator if silent retries prove confusing; broader retry coverage if other transient `IOException`s show up in real logs; remotely-configurable retry count/delay if flakiness characteristics vary a lot over time.

---

## Technical layer (implementation detail for Coder stage)

### Files to touch

1. **`app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt`** (production, ~30 line diff)
2. **`app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt`** (1 modified test, 2-4 new)

### New/changed symbols in `GeminiCardScanner.kt`

```kotlin
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay
import okhttp3.Response   // new explicit import — return type of the new helper

private const val MAX_SCAN_RETRIES = 2       // extra attempts after the first; 3 total attempts
private const val SCAN_RETRY_DELAY_MS = 500L // fixed delay between attempts; retune like GUIDE_FRAME_WIDTH_RATIO

// Retries ONLY on 5xx or SocketTimeoutException — must NOT retry 429 (own RateLimited/cooldown UX).
private suspend fun executeWithRetry(request: Request): Response {
    var lastTimeout: SocketTimeoutException? = null
    for (attempt in 0..MAX_SCAN_RETRIES) {
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            lastTimeout = e
            if (attempt < MAX_SCAN_RETRIES) { delay(SCAN_RETRY_DELAY_MS); continue }
            throw e
        }
        if (response.code == 429) throw RateLimitException()
        if (response.code in 500..599) {
            response.close() // must close before retrying — avoid leaking the connection
            if (attempt < MAX_SCAN_RETRIES) { delay(SCAN_RETRY_DELAY_MS); continue }
            throw IOException("Gemini error: ${response.code}")
        }
        if (!response.isSuccessful) throw IOException("Gemini error: ${response.code}")
        return response
    }
    throw lastTimeout ?: IOException("Gemini scan failed after retries")
}
```

`scan()` changes only its call site: `execute()` + the `429`/`!isSuccessful` checks → `val response = executeWithRetry(request)`. Everything after (body parsing) is untouched. Sketch, not gospel — Coder should verify against the current file state and may adjust structure (`while` vs `for`) as long as the retry/no-retry decision table above holds exactly.

### Edge cases (must handle)

- **Response body leak on retry**: an unread/unclosed 5xx `Response` before the next `execute()` leaks the connection — `response.close()` before retrying.
- **429 must never retry** — checked before the 5xx branch, throws immediately, same as today.
- **Non-5xx, non-429 failure** — must NOT retry; only 5xx and `SocketTimeoutException` are retryable.
- **Exhausted retries on 5xx** — same `IOException("Gemini error: $code")` message format as today (existing test asserts `is java.io.IOException`).
- **Exhausted retries on timeout** — rethrow original `SocketTimeoutException` (is-an `IOException`, so `ScannerViewModel`'s generic catch handles it identically to today).
- **`Dispatchers.IO` + `delay()` in tests** — real wall-clock delay (not skipped by `runTest`'s virtual time, which only applies on the test dispatcher). ~0.5-1.5s real time per new retry test; not a bug.

### Task breakdown (dependency-ordered)

1. **[S] Add `executeWithRetry()` + 2 constants to `GeminiCardScanner.kt`, wire into `scan()`.** Single file, contained logic, no new types beyond the private helper.
2. **[S] Update `scan throws IOException on 500` test** to enqueue 3 (`MAX_SCAN_RETRIES + 1`) 500 responses, assert `server.requestCount == 3`. Depends on 1.
3. **[M] Add new tests**: 503-then-200 succeeds (assert `requestCount == 2`); timeout-then-200 succeeds (needs a short-timeout `OkHttpClient` built in-test, e.g. `.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()`, combined with `MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)`); all-timeouts-exhausted rethrows. Depends on 1.
4. **[S] Regression-check**: existing `scan throws RateLimitException on 429` still passes unmodified — no code change, confirm in the run. Depends on 1.
5. **[S] Run `.\gradlew.bat testDebugUnitTest --rerun-tasks`**, confirm full suite green, fresh XML count. Depends on 1-4.

Overall size: **S** — Task 3 is the only one needing real design thought (simulating a socket timeout against MockWebServer), rest is mechanical.

---

## Friction Notes

- Serena's Kotlin language server failed to initialize in this sandbox (`Error extracting archive`) — fell back to Read/Grep/Glob for all code investigation. Worth checking if this recurs across sessions.
- `gaps.md` (759 lines) truncated mid-`Read` with a pagination notice; needed section was near the top and fully captured, but a targeted `Grep` first is the better default going forward given its size trajectory.
- `.pipeline/` is gitignored/sandboxed against Serena's file-editing tools (`replace_content` refused, "Path is ignored") — used plain `Write` for this stage's output instead.
