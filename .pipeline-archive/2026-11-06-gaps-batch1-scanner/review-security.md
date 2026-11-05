# Review: security + reliability lens. Batch 1 scanner (worktree-gaps-batch1-scanner)

**Verdict: SHIP. Score 90/100.** No P0 or P1. Three P2 findings and three P3 findings, none blocking. First pass for this lens, so there is no prior score to compare.

## Scope
`git diff master...HEAD`: 5 commits, 20 files (8 main, 12 test). I read the full main diff, `CardScanSharedTest` in full, the scanners' request builders, `CardScanProvider.scan`, `ScannerViewModel` catches and `SyncCardImagesUseCase.syncIfDue`. No untracked files. I tried to re-run `CardScanSharedTest`/`SyncCardImagesUseCaseTest` and hit the known `Unable to establish loopback connection` wall. I did not retry, so the test evidence below is the Tester's.

## Intent audit (security-relevant items)
| Spec item | Status | Evidence |
|---|---|---|
| T2 attempt line, no URL/header/message | DONE | `"attempt provider=$tag n=${attempt + 1} ms=... outcome=$outcome"`, tag = `provider.name.lowercase()`, outcome = fixed token or `e::class.java.simpleName` |
| T2 IOException logged by class, same object rethrown | DONE | `catch (e: IOException) { logAttempt("io:${e::class.java.simpleName}"); throw e }` |
| T2/AC5 429 snippet, redacted, capped, peek failure harmless | DONE | `runCatching { response.peekBody(RATE_LIMIT_BODY_PEEK_BYTES).string() }.getOrNull() ?: return` |
| T3 Gemini 401/403 key-invalid, 400 excluded | DONE | `private val GEMINI_INVALID_KEY_CODES = setOf(401, 403)` |
| T3 Gemini-only: no raw hostname | DONE | `throw ScanFailedException(singleProviderMessage(ScanProvider.GEMINI, e), e)` |
| T4 one helper | DONE, behaviour identical | `private fun retryDelayMs(e: Exception): Long =` |
| T5 rotation | DONE | `val start = if (limit < first.size) ((now / AUTO_SYNC_INTERVAL_MS) * limit % first.size).toInt() else 0` |
No security-relevant scope creep. `fromReply` (deviation 1) is justified: it stops a Moshi `EOFException` from being read as a network failure.

## Key-leak trace (the main question for this lens)
| Path | Can a key reach logcat or the UI? | Why |
|---|---|---|
| Gemini URL | No | The key goes in a header, `.header("x-goog-api-key", apiKey)`. `GEMINI_BASE_URL` has no `?key=`. |
| Claude | No | `.header("x-api-key", apiKey)` |
| OkHttp interceptor logging | No | A grep of main finds no `HttpLoggingInterceptor`. `forScan` only sets `callTimeout`. |
| `attempt` lines | No | Fixed tokens, status code and exception simple class name only. |
| `io:` outcome | No | Class name only. The test asserts `assertFalse(logs[0].contains("secret.example.com"))`. |
| 429 body | No, for realistic bodies | Redaction runs on up to 2048 bytes before the 300-char cap. A key that straddles byte 2048 sits past char 300 anyway (300 chars are at most 900 UTF-8 bytes), so a prefix can't escape. The header lookup is case-insensitive. Keys are ASCII, so JSON escaping can't hide one from `replace`. |
| UI `Error(e.message)` | No | `ScanFailedException` text is fixed. The raw `InvalidApiKeyException` reads `"$provider rejected the API key (HTTP $code)"`, which holds no key. `ScannerViewModel` never logs `e` or its stack. |
| `scan provider=...` lines | No | Class names only. |

## Findings

### P2-1 Body-read timeout is logged as `outcome=ok`, and `ms=` leaves out the body download (confidence 8, reliability/diagnostics)
`CardScanShared.kt`: `logAttempt("ok")` then `return response`. The body is read later, in the scanner: `.use { it.body?.string() }`. `callTimeout` covers the body read too, so if it fires during `string()`, logcat shows `outcome=ok ms=<headers only>`. The `InterruptedIOException` then escapes outside `executeWithRetry` with no retry, and the user sees "check your internet". Item 1's whole purpose is to retune `SCAN_CALL_TIMEOUT_MS` from these lines, so the figure undercounts exactly the cases that matter. With non-streaming `generateContent` the body is small, so the effect is usually small. It is still a misleading log. Fix: note in the KDoc and gaps.md entry that `ms=` covers up to the headers only, or log a second `bodyMs=` line in the scanners. Not blocking.

### P2-2 A throwing `log` seam skips `response.close()` and masks the real exception (confidence 7, resource management)
`logAttempt("http429")` and `logRateLimitBody(response, request, tag, log)` both run before `response.close()`. The same pattern sits in the invalid-key, 5xx and non-2xx branches, and `logAttempt("ok")` runs before `return response`. None of these is in a `try/finally`. If `log` throws, the response leaks and the caller gets that exception instead of `RateLimitException`. In production `Log.i` does not throw. It does throw in a JVM test that forgets to stub the seam ("not mocked"), which is a known risk in the spec. So today the impact is confusing test failures, not leaks. Fix: close first, or wrap the log call in `runCatching`. Cheap, and optional.

### P2-3 Test evidence for the security mutations is thin (confidence 9, test evidence)
`changes.md` cites "Logs: `.pipeline/evidence/mutation-batch1.log`, `mutation-batch2.log`". `ls .pipeline/evidence` shows no `mutation-batch2.log`. That is the batch holding "Remove `logRateLimitBody` call: RED ... (batch 2: 3 failures of 46)". `mutation-batch1.log` holds only `577 tests completed, 24 failed` and no test names. Neither redaction mutation was re-run by the Tester ("The other 8 table rows were not re-applied by me"). By reading the code I confirmed the redaction test would go red: the body contains `test-key` inside the first 300 chars, and `assertFalse(line, line.contains("test-key"))` checks for it. So the guard is real, but the recorded proof is not there. Wrote lesson `mutation-evidence-must-name-the-red-test`.

### P3-1 Day-indexed rotation can still starve under periodic even gaps (confidence 7, reliability)
`start = ((now / AUTO_SYNC_INTERVAL_MS) * limit % first.size)`, with the gate `if (now - settings.getLastImageSyncAt() < AUTO_SYNC_INTERVAL_MS) return null`. Gaps between runs are at least 1 day and are often 2, because an open 23h after the last sync is skipped. Take n = 800 and limit = 400. If every gap is even, then `2k*400 % 800 = 0`, so the same half is checked every time. Irregular real usage mixes gap lengths, so this is delay rather than total starvation. It sits outside the stated ceiling ("ids resolved mid-window shift the list"). If it ever matters, a random start (`Random.nextInt(first.size)`) needs no storage and has no parity trap. Otherwise add it to the ponytail comment. No off-by-one: the drop/take wrap is correct, and `% first.size` only runs when `first.size > limit >= 1`. Day 1 and day 2 with n = 405 cover all ids, as the test asserts.

### P3-2 "Gemini rejected the API key (HTTP 403)" can send the user to the wrong fix (confidence 6, verify, wording)
`GEMINI_INVALID_KEY_CODES = setOf(401, 403)`. Google also returns 403 PERMISSION_DENIED for API-not-enabled, project, or key-restriction problems where the key string itself is fine, so the user may regenerate a key that works. Only the 429 body is logged (`logRateLimitBody` is called in the 429 branch only), so logcat can't tell these 403 cases apart either. Suggest reusing `logRateLimitBody` (renamed) for the invalid-key branch. The body is already redacted. This also answers the spec's open question about 400 vs 403 with the actual reason. It is a spec-sanctioned choice, so it's a suggestion only.

### P3-3 A failed peek is swallowed with no trace (confidence 8, reliability)
`runCatching { ... }.getOrNull() ?: return` leaves no log line. When the 429 cause can't be read, the line is simply missing, which looks identical to "logging broken". A one-token `body=<unreadable>` costs nothing. The spec only requires that the thrown exception stays unchanged, and it does.

## Checked, no issue
- **Cancellation:** `catch (e: CancellationException) { throw e }` comes before `catch (e: Exception)` in both the Gemini and Claude blocks. `delay()` cancellation is not an `IOException`. `fromReply` catches only `IOException`/`JsonDataException`, which in-memory String parsing raises only on bad JSON.
- **Catch order:** `InterruptedIOException` comes before `IOException`, so `SocketTimeoutException` counts as a timeout and retries.
- **Thread safety:** the `log` vars are written once, in tests. Production reads the default lambda. `geminiSkipUntil` is `@Volatile` and unchanged. Rotation runs under `mutex`.
- **Overflow:** a day index of about 20,000 times 400 is far below `Long`.
- **Cooldown helper:** a byte-for-byte equivalent refactor. The Tester added 403 and 503 branch tests with red-green logs.
- **Wording:** the Gemini-only path keeps `RateLimitException`/`InvalidApiKeyException` raw (fixed, user-safe). The Claude repeated text is unchanged ("Claude usage limit", console.anthropic.com).
- **Moshi `fromReply`:** won't hide a real network failure, because the body is already fully in memory when it runs.

## What looks good
Redact-before-truncate is the correct order, and the Coder caught it in self-review. Keys travel in headers on both providers. Every log seam emits class names and fixed tokens only, and a test proves the hostname stays out. Peek is capped at 2 KB. `RateLimitException` survives an unreadable body, with a dedicated test.

## Score
| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | Every T2/T3/T4/T5 security item is traced. -1 because the item 1 log does not fully deliver "how long did each attempt take" (P2-1). |
| Correctness & bug-freedom | 22/25 | No confirmed functional bug. -2 for the misleading `ok` on body timeout (P2-1), -1 for the rotation parity case (P3-1). |
| Security & reliability | 18/20 | No leak path. -1 for close-after-log ordering (P2-2), -1 for silent peek failure and the undiagnosable 403 (P3-2, P3-3). |
| Maintainability & simplification | 14/15 | Small, named constants, one helper. -1 because the log-before-close pattern is repeated in 5 branches. |
| Test evidence quality | 12/15 | Fresh full run 579/0 (`test-results.log`, `BUILD SUCCESSFUL in 2m 23s`) and Tester spot-checks with named reds. -3 because the cited `mutation-batch2.log` is missing and batch 1 has counts only (P2-3). |
| **Total** | **90/100** | Ship (≥85, no P0). |

## Friction Notes
- Gradle loopback wall hit on a single targeted re-run. Not retried. This is already a lesson.
- Friction rollup: wrote 1 lesson (`mutation-evidence-must-name-the-red-test`) and indexed it. The compound-Bash, adb-PATH and loopback notes are already covered by existing lessons.
