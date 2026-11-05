# Changes: Batch 1 scanner behaviour and tests (specs T1-T6)

Branch `worktree-gaps-batch1-scanner`, base 5a5f7df, 4 commits. T7 (item 11) left to the Tester. No new dependency (skill check N/A), no Room change.

## Main code
| File | Change |
|---|---|
| `domain/CardScanProvider.kt` | `ScanProvider` public with `displayName`. `isNetworkFailure` excludes `MalformedReplyException`. New `singleProviderMessage` (used by Claude-only tail and new Gemini-only wrap). `failureMessage` adds "Gemini key rejected". `EmptyScanException` no longer assigned; `fallbackReason` = GeminiCooldown / error class / `EmptyReply` / none. Log order: `provider=gemini ... EmptyReply` on the empty-result return, `provider=none` only on real failure. Gemini-only: RateLimit/InvalidKey raw, anything else -> `ScanFailedException` with fixed text. KDoc updated. |
| `domain/CardScanShared.kt` | `RateLimitException(provider: ScanProvider)`; `MalformedReplyException` added, `EmptyScanException` deleted. `executeWithRetry(request, provider: ScanProvider, invalidKeyCodes, log)`: one `attempt provider= n= ms= outcome=` line per try (ok / http429 / invalidKeyNNN / httpNNN / timeout / io:Class); new `catch (IOException)` logs and rethrows same object. `logRateLimitBody`: peeks up to 2048 bytes, redacts `x-goog-api-key`/`x-api-key` values BEFORE truncating (so a key straddling the cap cannot leak a prefix), collapses whitespace, caps 300 chars; a failed peek is swallowed. New `fromReply` helper (see deviation 1). Named constants for caps. |
| `domain/GeminiCardScanner.kt` | `log` seam, `GEMINI_INVALID_KEY_CODES = {401,403}` (400 excluded, commented), `MalformedReplyException`, passes provider enum + log. |
| `domain/ClaudeCardScanner.kt` | `log` seam, `MalformedReplyException`, enum. |
| `ui/scanner/ScannerViewModel.kt` | `RateLimited.provider: ScanProvider`. |
| `ui/scanner/ScannerScreen.kt` | `rateLimitMessage(ScanProvider, ...)`: per-provider limit phrase + console URL via destructured pair, shared countdown/ready lines. Gemini strings byte-identical (existing test). |
| `data/remote/TcgcsvJapanSource.kt` | One `retryDelayMs(e)`, one `RETRY_MS`, one `BLOCKED_RETRY_MS`; four constants removed. |
| `domain/SyncCardImagesUseCase.kt` | `blankIds` sorted; resolvable ids rotate by `(now/day)*limit % size` when capped, ko/zh-cn last; ponytail comment replaced with the ceiling. Manual sync unaffected. |

## Deviations from spec (flagged)
1. **`fromReply` helper (not in spec).** The spec says a "malformed JSON body" must give `MalformedReplyException`, but Moshi throws `EOFException`/`JsonEncodingException` (both IOException, would read as "internet") or `JsonDataException` on bad JSON. `JsonAdapter<T>.fromReply(raw, provider)` maps all three plus null to `MalformedReplyException`; used at both envelope parses. The inner card-JSON parse keeps its existing `runCatching` -> empty result.
2. `CardScanSharedTest.kt` also needed mechanical updates (calls `executeWithRetry` with String); not listed in spec.
3. `GeminiCardScannerTest` TEST for 429 provider assertion switched from `assert(== "Gemini")` to `assertEquals(ScanProvider.GEMINI, ...)`; Claude test likewise.
4. Toggling `SettingsRepositoryTest` mutation used `AppSettings.extra` and the leak mutation used `PublishConfig.githubPat` (both guards live in different classes; applied in one batch, each went red in its own test).
5. Spec table row "Sync rotation" test uses days 1 and 2 (settings stub 0 makes `now=0` not due) and asserts all 405 ids are looked up across both runs, stronger than "#405 at least once".

## Tests added / changed (577 total, was 552: +25 net)
- `CardScanSharedTest`: attempt line, numbering/http503, timeout + invalidKey outcomes, non-timeout IOException logged by class and same object rethrown (no hostname), 429 redacted+capped snippet, unpeekable body still throws RateLimitException. Existing calls updated.
- `GeminiCardScannerTest`: 401/403 -> InvalidApiKey after exactly 1 request, four malformed bodies -> Malformed, attempt lines, 429 snippet, body-close via ForwardingSource interceptor (caveat comment: `string()` also closes, so only a non-closing read mutation fails).
- `ClaudeCardScannerTest`: malformed bodies (now incl. empty), attempt lines, 429 snippet.
- `CardScanProviderTest`: Malformed Claude -> "Claude failed", Gemini key rejected combined text, Gemini-only DNS wrap (no hostname), Gemini-only malformed + raw InvalidKey, empty-reply log order (provider=gemini, no provider=none); `EmptyReply` label.
- `ScannerScreenTest`: enum args; Gemini repeated -> aistudio not anthropic; Claude repeated -> console.anthropic.com.
- `ScannerViewModelTest`: RateLimited provider (twice, counts 1 then 2), NoApiKey, Cancellation stays Scanning; `close()` verified.
- `PerceptualHasherTest`: cancellation test rewritten (requestCount stays 4 after real 1.8 s delay); always-true assert removed.
- `TcgcsvJapanSourceTest`: cooled-down group spends no budget.
- `SyncCardImagesUseCaseTest`: rotation over two days reaches all 405 ids, 400 per run.
- `repository/ToStringGuard.kt` (new test helper) + one test each in `SettingsRepositoryTest`/`PublishSettingsRepositoryTest` (reflection: every field named, no non-allowlisted String sentinel in output).

## Mutation checks (all applied, RED seen, reverted, GREEN confirmed by full run)
Applied in two batches (distinct tests per mutation); failing-test names from `app/build/test-results` XMLs. Logs: `.pipeline/evidence/mutation-batch1.log`, `mutation-batch2.log`.
| Mutation | Result |
|---|---|
| VM pass GEMINI instead of `e.provider` | RED: `RateLimited carries the exception's provider` |
| VM delete NoApiKey catch | RED: `NoApiKeyException maps to NoApiKey` |
| VM delete cancellation rethrow | RED: `CancellationException is not turned into Error` |
| Gemini non-closing body read | RED: `scan closes the response body source after a successful read` |
| Hasher `GlobalScope.async` | RED: `cancellation propagates` (PerceptualHasherTest) |
| Tcgcsv swap budget/cooldown lines | RED: `a cooled-down group spends no FetchBudget` |
| AppSettings extra field / PublishConfig prints PAT | RED: both reflection tests; PAT leak also trips the old `never contains the PAT` test |
| `retryDelayMs` always RETRY_MS | RED: both 401 backoff tests |
| Remove 429 redaction | RED: CardScanSharedTest 429 test (+ Gemini/Claude scanner 429 tests) |
| Remove `logRateLimitBody` call | RED: same three 429 tests (batch 2: 3 failures of 46) |
| Drop ok-path attempt log | RED: both CardScanSharedTest attempt tests + Gemini/Claude attempt-line tests |
| Drop `!is MalformedReplyException` | RED: malformed Claude wording test (+ Gemini-only malformed test) |
| `provider=none` log before empty return | RED: `empty gemini reply plus claude network failure returns the empty result` |
| Sync `start = 0` | RED: `successive auto runs rotate...` |
| Gemini console hardcoded to anthropic | RED: both Gemini rateLimitMessage tests |
After `git checkout -- app/src/main` the tree was clean and the full run below is green.

## Simplification / review pass
- Reuse: `singleProviderMessage` shared by Claude-only and Gemini-only; one `retryDelayMs`; `fromReply` shared by both scanners; `rateLimitMessage` shares countdown/ready lines. No existing helper duplicated.
- Quality: removed now-unused `java.io.IOException` imports in both scanners; named constants for 300/2048/NANOS_PER_MS; no stringly-typed provider left.
- Efficiency: peek capped at 2 KB; log work only on 429.
- Correctness fixes found during the pass: redact-before-truncate (key straddling the cap); unpeekable-body test needed an overridden `close()` (a test bug, not product).
- Known limits: 400 not treated as invalid key (spec); `ms=` excludes retry back-off by design.

## Verification (Item M) - full output: `.pipeline/evidence/changes-verify.log`
| # | Check | Result |
|---|---|---|
| 1 | Typecheck | PASS (Kotlin compile of main + unit tests inside `testDebugUnitTest` and `assembleDebug`) |
| 2 | Lint | **N/A**: `lintDebug` cannot run in the sandbox. `generateDebugAndroidTestLintModel` fails resolving androidTest artifacts (hilt-android-testing, ui-test, room-testing, runner): `PKIX path validation failed` (`.pipeline/evidence/build-lint.log`) |
| 3 | Tests | PASS: `./run_build.bat testDebugUnitTest --rerun-tasks` exit 0; XMLs: tests=577 failures=0 errors=0 (baseline 552) |
| 4 | Build | PASS: `./run_build.bat assembleDebug` exit 0 |
| 5 | Dev server | N/A (Android app, no server) |
| 6 | Stray debug | PASS: no println/debugger/TODO in new files; only `Log.i("ScannerMatch")` seams that mirror existing code |
| 7 | git status | PASS: clean tree, 20 files vs base (8 main, 11 test, 1 new test helper), `.pipeline/` ignored |

## Follow-ups for the orchestrator (gaps.md, not edited by me)
- Collect `attempt ... ms=` lines on the phone, then retune `SCAN_CALL_TIMEOUT_MS` (item 1, log only).
- Share-sheet rotation test deferred (item 7f): emulator-only Compose+Hilt `ActivityScenario.recreate()`; flag already covered at ViewModel level.
- Open: after one wrong Gemini key on the phone, logcat shows `outcome=http400` or `invalidKey403`; decide then on adding 400.
- Item 11 (T7) not started.

## Friction Notes
- Compound Bash (`cd && ...`, heredocs, quoted `--tests` globs in one command) is rejected in this sandbox; use Edit/Write and single plain commands.
- `ResponseBody.close()` reads `source()`, so a test double whose `source()` throws also throws on `response.close()`; override `close()`.
- Moshi `fromJson("")` throws `EOFException` (an IOException), which the old code would have misread as a network failure.
