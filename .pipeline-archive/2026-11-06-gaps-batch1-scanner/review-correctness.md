# Code Review: gaps batch 1, scanner behaviour and tests (LENS 1: correctness + spec trace)

**Reviewer:** Reviewer stage, correctness lens
**Date:** 2026-11-06
**Commits:** `git diff master...HEAD` (5 commits, d20fc1c..f98e5f6), base 5a5f7df
**Scope:** 20 files, +665/-94 (8 main, 12 test). Untracked files: none (`.pipeline/` is ignored). First pass, so there is no prior score to compare against.

## Summary
Every acceptance criterion except AC11 traces to code and to a test. I found no behaviour regression in the retry loop, provider fallback, error classification, rate-limit state, sync rotation or Tcgcsv cooldown. The open items are bookkeeping and test hygiene: the gaps.md entries the spec asks for are not written yet, the Coder's mutation evidence is only partly attributable, and one test assertion can flake.

**Recommendation:** ship. Do the gaps.md writes (F1) before merge.
**Critical (P0):** 0. **Important (P1):** 0. **Minor (P2):** 3. **P3:** 1.

## Intent audit (Phase 2): clean, with one item delegated
| Item / AC | Status | Evidence |
|---|---|---|
| 1 / AC1 per-attempt timing | DONE | `CardScanShared.kt` `"attempt provider=$tag n=${attempt + 1} ms=... outcome=$outcome"`. Each branch logs once before it throws, continues or returns. `SCAN_CALL_TIMEOUT_MS = 10_000L` is unchanged. Tests: CardScanSharedTest (4 new), plus the Gemini and Claude attempt-line tests. |
| 2 / AC2 log order | DONE | `geminiEmpty?.let { log("scan provider=gemini fallbackReason=$fallbackReason claudeError=$claudeError"); return it }` comes before the `provider=none` log. The test asserts `logs.none { it.contains("provider=none") }`. |
| 3 / AC3 wording | DONE | `t !is MalformedReplyException`, `gemini is InvalidApiKeyException -> "Gemini key rejected"`, `GEMINI_INVALID_KEY_CODES = setOf(401, 403)`, and the Gemini-only wrap `throw ScanFailedException(singleProviderMessage(ScanProvider.GEMINI, e), e)`. Four CardScanProviderTest cases plus the scanner 401/403 and malformed tests. |
| 4 / AC4 typed provider | DONE | `enum class ScanProvider(val displayName: String)`, `RateLimited(..., val provider: ScanProvider)`, and `rateLimitMessage` picks the console URL per provider. I diffed the Gemini strings by eye: they are byte-identical, and the existing exact-match test still passes. |
| 5 / AC5 429 body | DONE | `logRateLimitBody` runs `runCatching { response.peekBody(...) }.getOrNull() ?: return`, redacts the key, then calls `.take(RATE_LIMIT_BODY_LOG_CHARS)`. Tested for both providers, and the unpeekable-body test checks the `RateLimitException` is still thrown. |
| 6 / AC6 EmptyScanException | DONE | Grep finds no `EmptyScanException` anywhere in `app/src`. Line 105: `geminiEmpty != null -> "EmptyReply"`. |
| 7 / AC7 T6 tests | DONE (7f deferred, as the spec says) | Tests a-e are present. 579/0 per `.pipeline/evidence/test-results.log` (`BUILD SUCCESSFUL in 2m 23s`, `EXIT=0`). |
| 8 / AC8 Tcgcsv helper | DONE | There is one `retryDelayMs`, one `RETRY_MS` and one `BLOCKED_RETRY_MS`. Grep finds no remaining old constants. 403 and 503 tests were added. |
| 9 / AC9 toString guard | DONE | The `ToStringGuard.kt` reflection test is used in both repository tests. |
| 10 / AC10 sync rotation | DONE | `start = ... ((now / AUTO_SYNC_INTERVAL_MS) * limit % first.size)`. The math checks out: successive day windows `[d*limit, (d+1)*limit) mod size` abut, so every id is reached in ceil(size/limit) days. Manual sync (`Int.MAX_VALUE`) never rotates. The test covers all 405 ids. |
| 11 / AC11 item 11 | PARTIAL | The Tester ran it and it hit the PKIX wall (`item11-connected.log:109 EXIT=1`). The cause is still unexplained, and the gaps.md record is not in the diff (F1). |
| Deviation: `fromReply` | CHANGED (accepted) | Needed for the spec's own "malformed JSON -> MalformedReplyException" requirement, because Moshi's `EOFException` and `JsonEncodingException` are IOExceptions. No scope creep. |

## Phase 3/4: regression trace (no confirmed bugs)
- **Retry loop.** The new `catch (e: IOException)` comes after `catch (e: InterruptedIOException)`. A `throw e` inside the first catch is not caught by its sibling, so timeouts still retry and other IOExceptions still escape unretried, as before.
- **Fallback.** Claude RateLimit or InvalidKey is still rethrown before the `geminiEmpty` return, which keeps the prior precedence. `addSuppressed` is unchanged.
- **Gemini-only.** RateLimit and InvalidKey are rethrown raw (`ScannerViewModel.kt:123`/`:129` map them). Everything else becomes a fixed-text `ScanFailedException`, so a hostname cannot reach `Error(e.message)`.
- **Rate-limit state.** `geminiSkipUntil` is still set on any Gemini 429. The breaker still applies only when `hasGemini && hasClaude`. A Gemini 403 does not trip the cooldown, which matches the old behaviour for 403.
- **Behaviour change (intended).** With both keys set, a Gemini 403 now shows "Gemini key rejected and ..." instead of "Gemini failed and ...". With Gemini only, it shows `Gemini rejected the API key (HTTP 403)`. A 403 can also mean a project or API-not-enabled problem, but "key rejected" is close enough for a personal app.
- **Tcgcsv.** Behaviour is unchanged. The old products constants equalled the groups ones (`PRODUCTS_BLOCKED_RETRY_MS = GROUPS_BLOCKED_RETRY_MS`).
- **Enum completeness.** `when (provider)` in `rateLimitMessage` is exhaustive with no `else`, so a third provider would fail to compile, which is what we want.

## Findings

### F1 [P2, conf 9] The gaps.md records the spec requires are not written
- **File:** `gaps.md` (not in the diff, per `git diff --stat`)
- **Quote, spec T2:** `Add to gaps.md: "collect attempt ... ms= lines on the phone, then retune SCAN_CALL_TIMEOUT_MS"`. Spec 7f: `Record in gaps.md with this reason.` AC11: `documented cause/fix or "unexplained, 30 min spent" in gaps.md`.
- **Issue:** changes.md hands this off (`Follow-ups for the orchestrator (gaps.md, not edited by me)`), but nobody has done it yet. If the branch merges as-is, three tracked follow-ups (timeout retune, the 7f deferral, the item 11 PKIX outcome) plus the open question about adding 400 are lost.
- **Fix:** before merge, the orchestrator writes these four gaps.md entries and closes the items that are done (1-10).

### F2 [P2, conf 9] 8 of 14 mutation rows have evidence that cannot be attributed
- **Quote, changes.md:** `Logs: .pipeline/evidence/mutation-batch1.log, mutation-batch2.log`. Batch 1's whole result is `577 tests completed, 24 failed`, and `mutation-batch2.log` does not exist (the `.pipeline/evidence/` listing has no such file).
- **Issue:** the rows for the VM provider, NoApiKey, cancellation, body-close, toString, 429 redaction and log, attempt log, wording, and rateLimitMessage have no sidecar showing which test went red. The Tester re-ran 6 rows with named failures (`mutation-spotcheck.log`, `mut-403.log`, `mut-503.log`), and those are solid. The other 8 are supported only by prose. The test code itself reads as correct for each of them; this is an evidence gap, not a test bug. Lesson `mutation-evidence-must-name-the-red-test.md` already covers it.

### F3 [P2, conf 5, verify] The hasher cancellation test can flake low
- **File:** `PerceptualHasherTest.kt:256-265`
- **Quote:** `started.await()` ... `job.cancel()` ... `assertEquals(4, server.requestCount)`
- **Issue:** `started` completes on the *first* dispatched request. Any of the other three first-wave coroutines that has not yet reached `execute()` is cancelled at `withContext(Dispatchers.IO)` (PerceptualHasher.kt:87), which leaves `requestCount` at 3 or below and makes the exact-equals assertion fail. The window is a few milliseconds, but a loaded machine can hit it.
- **Fix:** `assertTrue("count=${server.requestCount}", server.requestCount <= 4)`. The `GlobalScope.async` mutation (8 requests) still turns it red.

### F4 [P3, conf 6] `outcome=ok` and `ms=` cover headers only
- **Quote:** `logAttempt("ok"); return response`. The body is read later by the caller in `.use { it.body?.string() }`.
- **Issue:** a call-timeout or reset during the body read is logged as `ok` and then escapes from `string()`. `ms=` also leaves out the body download, while `SCAN_CALL_TIMEOUT_MS` caps the whole call. This matches the spec (it times `execute()`), and the reply bodies are small JSON, so the error is small. Keep it in mind when retuning from these logs. No change needed now.

## What looks good
- Redacting before truncating closes off a key prefix leaking at the 300-character cap.
- `fromReply` correctly fixes the latent EOF-reads-as-network misclassification.
- The rotation math is clean and has no divide-by-zero, because the modulo is guarded by `limit < first.size`.
- Removing the `isCancelled` tautology from the hasher test was a good call.
- `singleProviderMessage` removes duplicated text.

## Score (correctness lens)
| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | 22/25 | Items 1-10 are done, and the one deviation is justified. -3 for F1: the gaps.md writes required by T2, 7f and AC11 are still outstanding. |
| Correctness & bug-freedom | 24/25 | No confirmed bug across the six flows I traced. -1 for F4's misleading `ok` on a body-read failure (diagnostic only). |
| Security & reliability | 19/20 | Redaction order is correct, and a failed peek does not change the throw. -1 because a key echoed JSON-escaped would not be redacted (unrealistic for ASCII keys; the Tester noted it). |
| Maintainability & simplification | 14/15 | Helpers are shared and the constants are named. -1 because `ToStringGuard` only supports String and Boolean (documented, fails loudly). |
| Test evidence quality | 11/15 | Fresh 579/0 run with a verbatim tail, and 6 mutations re-proved by name. -3 for F2 (8 rows unattributable, one cited log missing). -1 for F3's flake risk. |
| **Total** | **90/100** | |

**Verdict: ship.** Score is 90 (>= 85) and there are no P0/P1 findings. F1 is a pre-merge orchestrator write, not a code change. F2 and F3 are optional follow-ups.

## Friction rollup
The security lens has already done this run's rollup (it wrote `lessons/mutation-evidence-must-name-the-red-test.md`, index row 245). I did not write a second lesson, because rewriting `lessons.md` without the Edit tool could overwrite a parallel lens's write. One candidate is left for the orchestrator: "Moshi `fromJson` parse failures (`EOFException`, `JsonEncodingException`) are IOExceptions, so an `IOException`-means-network classifier mislabels a bad 2xx body as connectivity. Map them to a dedicated exception at the parse site." Grep finds no existing lesson that covers it.
