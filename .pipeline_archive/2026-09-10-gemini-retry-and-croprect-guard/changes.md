# Changes: retry-once-with-backoff on transient Gemini API failure

Implements `.pipeline/specs.md` exactly — bounded retry on 5xx / `SocketTimeoutException` in
`GeminiCardScanner.scan()`. No scope creep beyond the spec's 5-task breakdown.

## Files changed

### `app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt` (+39/-7)

- Added imports: `kotlinx.coroutines.delay`, `okhttp3.Response`, `java.net.SocketTimeoutException`.
- Added two file-level constants: `MAX_SCAN_RETRIES = 2`, `SCAN_RETRY_DELAY_MS = 500L`.
- Added private suspend `executeWithRetry(request: Request): Response` — implemented verbatim
  from the spec's skeleton (Technical Layer section), no structural deviation:
  - `SocketTimeoutException` on `execute()` → retry with `delay()` if attempts remain, else rethrow
    the original exception.
  - `code == 429` → `RateLimitException()` immediately, no retry (checked first, unchanged order).
  - `code in 500..599` → `response.close()` (leak prevention), retry with `delay()` if attempts
    remain, else `throw IOException("Gemini error: $code")` (same message format as before).
  - Other non-2xx → `throw IOException("Gemini error: $code")` immediately, no retry (unchanged).
  - Success → returns `Response` for existing body-parsing logic, untouched.
- `scan()`'s call site: `okHttpClient.newCall(request).execute()` + inline 429/`isSuccessful` checks
  replaced by a single `val response = executeWithRetry(request)`. Everything after (JSON parsing,
  `ParsedCardInfo` construction) is byte-for-byte unchanged.

### `app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt` (+61/-3)

- Extracted the repeated success-response JSON body used by 3 tests into a top-level
  `SUCCESS_BODY` constant (existing "returns ParsedCardInfo" test now reuses it — avoids the
  copy-paste-with-variation the simplification pass would otherwise flag across the new tests).
- **Modified** `scan throws IOException on 500`: now enqueues 3 (`MAX_SCAN_RETRIES + 1`) 500
  responses instead of 1, asserts `server.requestCount == 3`. Required per spec — the old
  single-response version would leave the mock server starved on retry attempt 2.
- **Added** `scan retries once on 503 then succeeds`: 503 then 200, asserts result parses and
  `requestCount == 2`.
- **Added** `scan retries once on timeout then succeeds`: builds a short-timeout (`200ms`)
  `OkHttpClient` in-test, `SocketPolicy.NO_RESPONSE` then a real 200 response, asserts result
  parses and `requestCount == 2`.
- **Added** `scan rethrows original timeout after retries exhausted`: 3× `SocketPolicy.NO_RESPONSE`
  against the same short-timeout client, asserts `caught is SocketTimeoutException` and
  `requestCount == 3`.
- `scan throws RateLimitException on 429` — **unmodified**, per spec's explicit regression check
  (Task 4).

### Not touched

`app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt` and its test show as
modified in `git status` — pre-existing, already-completed work from earlier in this session, not
part of this diff. Confirmed via `git diff --stat`: my diff is exactly the two `GeminiCardScanner*`
files (39+7 and 61+3 lines respectively); the `ImageProxyExt*` lines were never opened or edited by
this task.

## Simplification / review pass

- **Reuse**: `kotlinx.coroutines.delay` already used elsewhere in the module (per spec's
  Dependencies section) — no new dependency. `SUCCESS_BODY` extraction removes the one piece of
  copy-paste the new tests would otherwise introduce.
- **Quality**: no nested-conditional growth — `executeWithRetry` stays a flat `for` loop with early
  `throw`/`return`/`continue`, matching the spec's own skeleton (single level, decision table
  preserved exactly). No new abstraction beyond the one private helper + two named constants, per
  the spec's explicit "no retry-policy abstraction" scope boundary.
- **Efficiency**: `response.close()` before every retry closes the acceptance-criterion connection
  leak; no other hot-path or concurrency concerns — this is a single sequential network call path,
  unchanged in that respect.
- **Correctness/conventions**: retry/no-retry decision table cross-checked line-by-line against the
  spec's acceptance criteria (see Verification below) — 429 short-circuits before the 5xx branch
  exactly as before, non-5xx/non-429 failures still fail on first occurrence, `IOException` message
  format unchanged.
- No findings required a fix beyond what's already reflected above (the `SUCCESS_BODY` dedup was
  applied proactively, not flagged-then-fixed).

## Verification (7-check)

Full raw output: `.pipeline/evidence/changes-verify.log`.

1. **Typecheck** — could not run via Gradle (see check 3). Substituted a manual API-surface check:
   decompiled the exact cached `mockwebserver-4.12.0.jar` with `javap` and confirmed
   `SocketPolicy.NO_RESPONSE`, `MockResponse.setSocketPolicy(SocketPolicy)`, and
   `MockWebServer.getRequestCount()` all exist with the signatures the new tests call (full output
   in sidecar). Production code changes are a straight port of the spec's own skeleton against
   symbols (`okhttp3.Response`, `SocketTimeoutException`, `delay`) already used correctly elsewhere
   in the codebase.
2. **Lint** — could not run via Gradle (see check 3). N/A beyond check 6's manual grep.
3. **Scoped test run** — **blocked**. `.\gradlew.bat testDebugUnitTest --rerun-tasks` fails before
   running a single test:
   ```
   FAILURE: Build failed with an exception.
   * What went wrong:
   java.io.IOException: Unable to establish loopback connection
   Caused by: java.net.SocketException: Invalid argument: connect
       at java.base/sun.nio.ch.UnixDomainSockets.connect0(Native Method)
       ...at java.base/sun.nio.ch.WEPollSelectorImpl.<init>(WEPollSelectorImpl.java:78)
   ```
   This is Gradle's own single-use-daemon IPC socket failing to open *before* touching any task —
   confirmed via `gradlew.bat help --stacktrace` (same failure, no project code involved). Isolated
   as environment-level, not diff-related:
   - Plain PowerShell loopback TCP connect works fine (`Connected: True`, sidecar line 14).
   - Reproduced identically on both installed JDKs (Temurin 17.0.14 and Android Studio's JBR 21.0.10).
   - Reproduced with sandbox on and off, and after killing 3 stale `java.exe` processes — 6 attempts
     total, same failure every time.
   - `gaps.md`/`handoff.md` document this exact `Unable to establish loopback connection` signature
     recurring across sessions 10-12 as a pre-existing, never-root-caused, previously
     self-resolving sandbox issue — unrelated to any specific diff (it blocked builds in sessions
     where the diff was unrelated to networking entirely). It did not self-resolve this session
     despite the retries above.
   - Tests were traced by hand against the spec's acceptance criteria instead (see Correctness pass
     above) and the mock-server API calls confirmed to compile against the real jar via `javap`.
4. **Production build** — same Gradle wall, same evidence; `assembleDebug` never got past daemon
   startup. Blocked for the same reason as check 3.
5. **Dev/start server boots** — **N/A**. Android app, no dev server.
6. **No stray debug output / scratch files** — clean. `git diff` on the two changed files grepped
   for `println|console.log|debugger|TODO|FIXME|Log\.d|Log\.v`: no matches. No scratch files left
   in the repo (verify script and logs live in the session scratchpad, outside the repo).
7. **`git status` shows only intended files** — my diff is exactly
   `GeminiCardScanner.kt` (+39/-7) and `GeminiCardScannerTest.kt` (+61/-3). `git status` also shows
   `ImageProxyExt.kt`/`ImageProxyExtTest.kt` modified — pre-existing, unrelated, already-completed
   work from earlier this session per the task brief; not touched by this diff (confirmed via
   `git diff --stat`, sidecar lines 145-149).

**Net result: implementation complete and hand-verified against every acceptance criterion in the
spec; automated typecheck/lint/test/build could not execute this session due to a pre-existing,
previously-documented (`gaps.md`, sessions 10-12), environment-level Gradle daemon IPC failure —
not caused by this diff, not resolved by any of the 6 workarounds attempted (JDK swap, sandbox
toggle, stale-process cleanup, repeated retries).** Recommend re-running
`.\gradlew.bat testDebugUnitTest --rerun-tasks` in a fresh session/host before merging — this
exact command is what `gaps.md` sessions 10-11 used to confirm the wall had cleared.

## New dependency check

N/A — no new dependency added (`kotlinx.coroutines.delay` and `okhttp3.mockwebserver.SocketPolicy`
are both already-declared dependencies used elsewhere/already in this test file's imports).

## Friction Notes

- Serena's Kotlin language server was not invoked this stage (matches the spec's own friction note
  that it fails to initialize in this sandbox) — used Read/Grep/Bash throughout.
- The Gradle `Unable to establish loopback connection` wall, previously described in `gaps.md` as
  self-resolving, did **not** self-resolve across 6 attempts (2 JDKs, sandbox on/off, stale-process
  cleanup, 3 plain retries) in this session — worth escalating from "retry and hope" to an actual
  root-cause investigation (the stack trace consistently points at
  `sun.nio.ch.WEPollSelectorImpl` → `UnixDomainSockets.connect0` → `SocketException: Invalid
  argument: connect`, i.e. the JDK's Windows AF_UNIX-socket-based NIO selector self-pipe, not a
  literal TCP loopback problem — a real TCP loopback self-test on the same machine works fine).
  Next session hitting this should try disabling AF_UNIX-based selectors specifically (e.g.
  `-Djdk.net.usePlainSocketImpl=true` or forcing the classic Windows selector) rather than retrying.
- `javap` against a cached dependency jar (`~/.gradle/caches/modules-2/files-2.1/...`) is a decent
  substitute compile-check for "does this API exist with this signature" when Gradle itself can't
  run — cheap, no build needed, confirmed real signatures rather than guessing from memory/docs.
