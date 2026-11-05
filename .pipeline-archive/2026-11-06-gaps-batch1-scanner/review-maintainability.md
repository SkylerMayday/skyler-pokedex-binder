# Review (Lens 3: maintainability + simplification + test quality)

**Scope:** `git diff master...HEAD` (5 commits, 20 files: 8 main, 11 test, 1 new test helper). First pass for this lens; no prior review file.
**Verdict:** **ship**. Score **91/100**. No P0/P1. Fix the two P2s now or carry them as follow-ups.

## Intent audit (Phase 2)
Every T1-T6 item traces to a diff hunk. T7 (item 11) was attempted and hit the PKIX wall, documented in test-results.md. There is no scope creep. The one addition outside the spec, `fromReply`, is flagged and justified (see below). `CardScanSharedTest` call-site updates were mechanical and necessary. All 14 mutation-table rows have a matching test that I traced statically to its mutation (details under Test quality).

## Findings

### P2-1 Hasher cancellation test can go red on correct code (confidence 5, verify)
`PerceptualHasherTest.kt:256-265`:
```
started.await()
job.cancel()
...
assertEquals(4, server.requestCount)
```
`started` completes when the **first** request reaches the server. Each download goes through `withContext(Dispatchers.IO)` (`PerceptualHasher.kt:87`) and `gate.withPermit` (`:70`), and both are cancellation points before `.execute()`. If `job.cancel()` lands before the other 3 permit holders reach `execute()`, they are cancelled and `requestCount` ends at 1-3. The old test could not fail this way because it only asserted `isCancelled`. The mutation only needs "not 8". **Fix:** `assertTrue("count=${server.requestCount}", server.requestCount <= 4)`. This still fails under `GlobalScope.async` (8) and cannot fail when fewer than 4 requests got out. The test also costs about 3 s of real time (1.2 s join + 1.8 s delay). The spec accepted that, so no deduction for it.

### P2-2 Mutation evidence for 8 rows cannot be checked
changes.md cites `.pipeline/evidence/mutation-batch1.log, mutation-batch2.log`. `mutation-batch2.log` does not exist. `mutation-batch1.log` holds only the totals:
```
577 tests completed, 24 failed
```
It names no tests, so it cannot show that each mutation turned its intended test red. The Tester's `mutation-spotcheck.log` / `mut-403.log` / `mut-503.log` do name tests, but they cover 6 rows. The other 8 rest on changes.md's own claims. I traced those 8 by reading the code, and each would fail (see table). Mitigated, but the evidence trail itself is a gap. **Fix (process):** keep the per-test `FAILED` lines in the evidence log. Don't keep only the summary.

### P3-1 ToStringGuard relies on `declaredFields` order (confidence 6)
`ToStringGuard.kt:22-24`:
```
val ctor = cls.declaredConstructors.single { it.parameterCount == fields.size }
...
ctor.newInstance(*fields.map { argFor(it.type, it.name, sentinel) }.toTypedArray())
```
The JVM does not guarantee `getDeclaredFields()` order. HotSpot returns declaration order in practice, which is why this works today. If the order mixed String and Boolean fields, the call would fail loudly with IllegalArgumentException. If it only swapped String fields with each other, sentinels would land in the wrong fields, and an allowlisted field's sentinel could hide a real leak. The KDoc does state the assumption (`Assumes the primary constructor takes the instance fields in declaration order`). kotlin-reflect is already on the test classpath (`KotlinJsonAdapterFactory` in the scanner tests). `cls.kotlin.primaryConstructor!!.callBy(params.associateWith { ... by it.name })` would remove the assumption at the same size. Placement is fine: it is a test-only `internal` top-level function in the `repository` test package, used by both callers in that package. The unsupported-type `error(...)` correctly fails loudly.

### P3-2 Key header names are written out in three places
`CardScanShared.kt:167`:
```
for (header in listOf("x-goog-api-key", "x-api-key")) {
```
The same two literals also appear at `GeminiCardScanner.kt:70` and `ClaudeCardScanner.kt:46`. If a scanner's header were renamed, redaction would quietly stop working. The 429 tests in both scanner test classes would catch it, because each body echoes `test-key`, so this costs little. Optional: put the header on `ScanProvider` (`keyHeader`) and redact `request.header(provider.keyHeader)`. That would also remove the hard-coded list.

### P3-3 `InvalidApiKeyException` still takes a `String` provider
`CardScanShared.kt:20`: `class InvalidApiKeyException(provider: String, code: Int)`, called as `InvalidApiKeyException(provider.displayName, response.code)`. Its siblings `RateLimitException` and `MalformedReplyException` now take `ScanProvider`. The spec chose to keep the String ("display only"), so this is not a defect. It is the one stringly-typed provider left, despite changes.md's "no stringly-typed provider left".

## Simplification / over-engineering check
| Item | Assessment |
|---|---|
| `fromReply` (not in spec) | Justified. Moshi `fromJson("")` throws `EOFException`, an IOException, so without the helper `isNetworkFailure` would show the internet message, which is exactly the bug item 3 targets. It catches the specific exceptions (`IOException`, `JsonDataException`) rather than using `runCatching`. One 8-line helper serves both scanners. Keep. |
| `ScanProvider` made public | Required: it is a field of public `ScannerState.RateLimited` and `RateLimitException`. Same module. Fine. |
| `singleProviderMessage` | Removes the duplicate Claude-only tail and serves the new Gemini-only path. Good reuse. |
| `retryDelayMs` + `RETRY_MS`/`BLOCKED_RETRY_MS` | Net 4 constants down to 2, and the duplicated `blocked` expression is gone. Smallest correct change. |
| `logAttempt` local fun in loop | Captures `startNs`/`attempt`, so there is one log format in one place. Fine. |
| `logRateLimitBody` | Redacts before truncating (correct), with named caps (`RATE_LIMIT_BODY_LOG_CHARS`, `..._PEEK_BYTES`). Not over-built. |
| Sync rotation | 3 lines and the ponytail ceiling is documented. Not over-built. |
| `rateLimitMessage` | The destructured `(limit, console)` pair shares the countdown and ready lines, and the Gemini strings stay byte-identical (existing test). Clean. |
| `log` seams in both scanners | Mirror the existing `CardScanProvider` pattern. Consistent. |
| Dead code | `EmptyScanException`, `GROUPS_/PRODUCTS_*RETRY_MS` and the unused `java.io.IOException` imports: grep over `app/src` finds no leftovers. `TcgdexSetCatalog.SETS_RETRY_MS` is a separate class and out of scope. |

## Test quality (non-tautology check)
| Test | Would fail under its mutation? |
|---|---|
| VM RateLimited provider | Yes. Asserts `RateLimited(1, CLAUDE)` and `(2, CLAUDE)`. Passing GEMINI breaks the equality. |
| VM NoApiKey | Yes. `NoApiKeyException` is an `IllegalStateException`, so it falls through to `catch (e: Exception)` and gives `Error`. |
| VM cancellation | Yes. `processImage` sets `Scanning` before `launch` (`ScannerViewModel.kt:63`), and the catch-all would overwrite it with `Error`. |
| Gemini body close | Partially. The comment admits that `.use` deletion alone stays green. Honest and acceptable. |
| Hasher | Yes, 8 vs 4. But see P2-1 on flake. |
| Tcgcsv budget | Yes. Checks `budget.left == 2` twice plus `coVerify(exactly = 1)`. |
| Tcgcsv 403/503 | Yes. Tester's mut logs name them. |
| toString guard | Yes, both halves. `defaults.contains("${f.name}=")` has no prefix collisions in the current field names. |
| Attempt / 429 / io:Class logs | Yes. Exact-count and regex asserts, key-absent asserts, and a length cap with `<= prefix + 300`. |
| Wording, log order, rateLimitMessage | Yes. Exact-string or `none { provider=none }` asserts. |
| Sync rotation | Yes. Day 1 covers 401-405 and 1-395, day 2 covers 396-400. `start = 0` leaves 401-405 unreached. |

No real-time sleeps other than the hasher test. The retry tests use `runTest` virtual time for the backoff `delay`, and MockWebServer timeouts are 200 ms.

## Score (lens 3)
| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | All T1-T6 traced, deviations flagged and justified, T7 blocked by the environment and documented. -1 for T7 still open. |
| Correctness & bug-freedom | 24/25 | No product bugs found. -1 for the cancellation-test race (test-only). |
| Security & reliability | 19/20 | Redaction runs before truncation, a failed peek is swallowed, no hostnames in messages. -1 residual: a key echoed JSON-escaped would not match (already noted by the Tester). |
| Maintainability & simplification | 13/15 | Smallest-change reuse throughout, named constants, dead code fully removed. -1 ToStringGuard order assumption, -0.5 header literal duplication, -0.5 String/enum asymmetry. |
| Test evidence quality | 11/15 | Tests are non-tautological and the Tester spot-checked 6 rows with named failures. -2 potential flake in the hasher `== 4`, -2 missing `mutation-batch2.log` and a batch1 log with no test names. |
| **Total** | **91/100** | Ship threshold 85 met, no P0. First iteration for this lens, so no prior score to compare. |

## Friction rollup
Deferred to the orchestrator so three parallel lenses don't write the same lesson three times. Candidate from this lens: "mutation evidence logs must keep per-test `FAILED` lines, not only the `N tests completed, M failed` summary". It is generalizable, and I could not find whether an existing lesson already covers it.
