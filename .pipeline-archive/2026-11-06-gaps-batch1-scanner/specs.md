# Spec: Batch 1 - Scanner behaviour and tests (gaps items 1-11)

Worktree `gaps-batch1-scanner`, base 5a5f7df. Source plan: `~/.claude/plans/spicy-wiggling-fern.md` (Batch 1). Paths are under `app/src/main/java/com/skyler/pokedexbinder/` (MAIN) or `app/src/test/java/com/skyler/pokedexbinder/` (TEST). Line numbers are from the base commit.

## TL;DR
Eleven items, all read against the code. Verdicts: **fix** 1,2,3,4,5,6,8,9,10; item 7 = **fix** for six of seven tests, **defer** the share-sheet rotation test; item 11 = time-boxed Tester investigation (AVD `pokedex_test` exists; a physical phone is attached, so pin the serial). No Room change, no new dependency, `SCAN_CALL_TIMEOUT_MS` stays 10 s. Baseline 552 unit tests; every changed or added test is mutation-checked (table below).

## Problem
Scan failures are mislabelled (malformed replies read as "check your internet"), logs cannot answer "how long did each attempt take" or "why 429", provider identity is a stringly-typed `String`, and several guarded behaviours (cancellation rethrow, budget guard, `NoApiKey` mapping, rate-limit provider, secret masking) have tests that cannot fail or no test at all.

## Proposal (technical layer, in dependency order)

### T1 [S] ScanProvider public + displayName (item 4; enables 1,3,5)
- `MAIN domain/CardScanProvider.kt:13`: `enum class ScanProvider(val displayName: String) { GEMINI("Gemini"), CLAUDE("Claude") }`. Drop `internal`: it is exposed through public `ScannerState.RateLimited` and public `RateLimitException`.
- `CardScanShared.kt:15`: `class RateLimitException(val provider: ScanProvider = ScanProvider.GEMINI) : IOException("Rate limited by ${provider.displayName} API")` (message text unchanged).
- `executeWithRetry(request, provider: ScanProvider, invalidKeyCodes)` (CardScanShared.kt:86): use `provider.displayName` for `InvalidApiKeyException(...)` and for `"$name error: $code"` / `"$name scan failed after retries"`. Keep the `error: NNN` text exactly: the `HTTP_ERROR` regex at CardScanProvider.kt:18 depends on it.
- Callers: `ClaudeCardScanner.kt:49` -> `ScanProvider.CLAUDE`; `GeminiCardScanner.kt:69` -> `ScanProvider.GEMINI`.
- `ui/scanner/ScannerViewModel.kt:32`: `RateLimited(val consecutiveHits: Int, val provider: ScanProvider)`; line 124 unchanged.
- `ui/scanner/ScannerScreen.kt:649-664` `rateLimitMessage(provider: ScanProvider, ...)`: derive `name = provider.displayName` and `console = when (provider) { GEMINI -> "aistudio.google.com"; CLAUDE -> "console.anthropic.com" }`. Gemini strings must stay byte-identical (existing test `rateLimitMessage Gemini strings are unchanged`); Gemini repeated text says "daily Gemini quota", Claude repeated text says "usage limit"; keep those two distinct, share only the countdown/ready lines. Call site ScannerScreen.kt:499 unchanged. (SettingsScreen.kt:203 hint is fine: not a gap.)
- Mechanical test updates: `TEST ui/scanner/ScannerScreenTest.kt:126-151` string args -> enum; `TEST domain/CardScanProviderTest.kt` lines 162,163,378,392 `RateLimitException("Gemini"/"Claude")` -> enum; `GeminiCardScannerTest.kt:138` -> `ScanProvider.GEMINI`; `ClaudeCardScannerTest.kt:168` -> `ScanProvider.CLAUDE`. `InvalidApiKeyException("Gemini", 403)` keeps its String arg (display only).
- New ScannerScreenTest assertion: Claude repeated message contains `console.anthropic.com` and not `aistudio`; Gemini repeated message contains `aistudio.google.com` and not `anthropic`.

### T2 [M] Per-attempt timing + 429 body logging (items 1, 5)
`MAIN domain/CardScanShared.kt` `executeWithRetry`: add last param `log: (String) -> Unit`.
- Time each `newCall(request).execute()` with `System.nanoTime()`. Emit exactly one line per attempt: `attempt provider=<gemini|claude> n=<attempt+1> ms=<elapsed> outcome=<ok|http429|invalidKey<code>|http<code>|timeout|io:<SimpleClassName>>`. `timeout` = `InterruptedIOException`. Other `IOException` from `execute()` currently escapes uncaught: add a `catch (e: IOException)` that logs `io:<ClassName>` and rethrows the same object. Never log URL, headers, or exception messages.
- Item 5, in the 429 branch (lines 103-106) before `response.close()`: `runCatching { response.peekBody(...).string() }`, take the first `RATE_LIMIT_BODY_LOG_CHARS = 300` chars, collapse whitespace, replace every non-blank value of `request.header("x-goog-api-key")` / `request.header("x-api-key")` with `<redacted>`, then `log("rateLimit provider=<name> body=<snippet>")`. A failed peek must not change the thrown `RateLimitException`.
- Scanners: add to `GeminiCardScanner` and `ClaudeCardScanner` `internal var log: (String) -> Unit = { android.util.Log.i("ScannerMatch", it) }` (mirrors CardScanProvider.kt:60) and pass it on. `android.util.Log` throws in JVM tests, so existing scanner tests must set `it.log = {}` (or capture) at their 5 construction sites: `GeminiCardScannerTest.kt:62,176,192`, `ClaudeCardScannerTest.kt:62,70`.
- Update the KDoc at `CardScanProvider.kt:48-52` ("provider and exception class names only"): the 429 snippet is now logged, key-redacted.
- Item 1 verdict: fix (log only). Add to gaps.md: "collect `attempt ... ms=` lines on the phone, then retune `SCAN_CALL_TIMEOUT_MS`". Item 5 verdict: fix. Gemini quota errors carry quota ids and `retryDelay`, not the key; redaction is belt-and-braces.

### T3 [M] CardScanProvider: classification, wording, log order, EmptyScanException (items 2, 3, 6)
- `CardScanShared.kt`: add `class MalformedReplyException(provider: ScanProvider) : IOException("${provider.displayName} returned an unreadable reply")`; delete `EmptyScanException` (line 21).
- `ClaudeCardScanner.kt:51,54,57` and `GeminiCardScanner.kt:70,72,75`: throw `MalformedReplyException(...)` instead of the `IOException("Empty/No text in X response")` (Gemini has the identical flaw).
- `CardScanProvider.kt:20 isNetworkFailure`: add `t !is MalformedReplyException` to the `IOException` clause. Malformed Claude reply now yields the existing `"Claude failed — try again"`.
- **Gemini key rejected.** Verified: Gemini never raises `InvalidApiKeyException` in production (`GeminiCardScanner.kt:69` passes no `invalidKeyCodes`); the gap text only describes test behaviour. Add `private val GEMINI_INVALID_KEY_CODES = setOf(401, 403)` and pass it. Do NOT include 400 (also returned for bad requests). In `failureMessage`'s `geminiPart` `when` add, before `isNetworkFailure`: `gemini is InvalidApiKeyException -> "Gemini key rejected"`, giving e.g. `"Gemini key rejected and Claude failed — try again"`. Gemini-only + rejected key propagates raw (fixed text `Gemini rejected the API key (HTTP 403)`, user-safe, same as Claude-only today).
- **Gemini-only raw text** (`CardScanProvider.kt:88 if (!hasClaude) throw e`): keep `RateLimitException`/`InvalidApiKeyException` raw; any other exception -> `ScanFailedException(singleProviderMessage(ScanProvider.GEMINI, e), e)` with `"Can't reach Gemini — check your internet connection"` for network failures else `"Gemini failed — try again"`. Make the existing Claude-only tail (`failureMessage` lines 37-38) use the same helper. Cancellation still rethrown first.
- **Item 2 (log order).** Lines 101-107 log `provider=none` before `geminiEmpty?.let { return it }`. Restructure: rate-limit/invalid-key branch keeps its `provider=none ...` log then throws; if `geminiEmpty != null` log `scan provider=gemini fallbackReason=EmptyReply claudeError=<ClassName>` and return it; only the remaining path logs `provider=none ...` then throws `ScanFailedException`.
- **Item 6 verdict: replace.** `EmptyScanException` is only a log label and never reaches `failureMessage` (the empty path returns first). Delete it; do not assign it to `geminiError`; `fallbackReason` = `"GeminiCooldown"` | `geminiError` simple name | `"EmptyReply"` (when `geminiEmpty != null`) | `"none"`. Update `CardScanProviderTest.kt:191` expectation to `EmptyReply`.
- New/changed CardScanProviderTest cases: (a) Claude `MalformedReplyException` -> `"Claude failed — try again"`; (b) Gemini `InvalidApiKeyException` + Claude `IOException("Claude error: 500")` -> `"Gemini key rejected and Claude failed — try again"`; (c) Gemini-only `UnknownHostException("generativelanguage.googleapis.com")` -> `ScanFailedException` with `"Can't reach Gemini — check your internet connection"`, no hostname in message; (d) line 292 test (`empty gemini + claude network failure`) also asserts a log contains `provider=gemini` and none contains `provider=none`. Scanner tests: Gemini HTTP 403 -> `InvalidApiKeyException` after exactly 1 request; malformed JSON body -> `MalformedReplyException`; attempt-line and 429-body-line tests (see T2; 429 test body contains `RESOURCE_EXHAUSTED ... PerDay` and the key `test-key`, assert snippet present, key absent, length <= cap).

### T4 [S] TcgcsvJapanSource helper + constants (item 8) - fix
`MAIN data/remote/TcgcsvJapanSource.kt`: add `private fun retryDelayMs(e: Exception) = if (e is HttpException && (e.code() == 401 || e.code() == 403)) BLOCKED_RETRY_MS else RETRY_MS`; use at lines 124-125 and 181-182. Companion (238-248): replace `GROUPS_RETRY_MS`/`PRODUCTS_RETRY_MS` with one `RETRY_MS = 15L * 60 * 1000`, and `GROUPS_BLOCKED_RETRY_MS`/`PRODUCTS_BLOCKED_RETRY_MS` with one `BLOCKED_RETRY_MS = 6L * 60 * 60 * 1000`. Behaviour identical; existing 401 and 15-minute tests cover it.

### T5 [S] Sync starvation (item 10) - fix (small, no storage, no schema)
`MAIN domain/SyncCardImagesUseCase.kt run()` (lines 67-77). Today `targets = refs...sortedBy{ko/zh-cn last}.take(limit)` over an unordered DAO list, so ids past 400 are never reached on auto runs.
- `blankIds = (...).distinct().sorted()` (deterministic).
- Split resolvable refs into `first` (lang not in `LANGS_WITHOUT_TCGDEX_IMAGES`) and `last`. If `limit < first.size`, rotate `first` left by `start = ((now / AUTO_SYNC_INTERVAL_MS) * limit % first.size).toInt()`, else 0. `targets = (rotated first + last).take(limit)`. Manual sync (`Int.MAX_VALUE`) unaffected. Replace the `ponytail: still starves` comment with: rotates by day so every id is reached; ceiling: ids resolved mid-window shift the list, so a window may skip a few until the next day.
- New test in `TEST domain/SyncCardImagesUseCaseTest.kt`: 405 ids whose `getCard` returns no image; `syncIfDue(day0)` and `syncIfDue(day0 + AUTO_SYNC_INTERVAL_MS)` (settings stub returns 0); each run `checked == 400`; id #405 looked up at least once across both. Existing capped tests (lines 153, 164) stay green.

### T6 [M] Unit tests, item 7
a) `TEST ui/scanner/ScannerViewModelTest.kt`: `processImage` tests. Setup pattern from `ui/QuickScanViewModelTest.kt:41-55` (`UnconfinedTestDispatcher`, `Dispatchers.setMain/resetMain`). Needs `mockkStatic("com.skyler.pokedexbinder.ui.scanner.ImageProxyExtKt")` with `every { any<ImageProxy>().toCroppedBitmap() } returns <bitmap mock with width/height>`; `mockkStatic(android.util.Log::class)` with `every { Log.i(any(), any()) } returns 0` (precedent `DatabaseBackupManagerTest.kt:45`); relaxed `ImageProxy` (verify `close()`); mocks for the other 7 constructor deps; `SavedStateHandle()`. Unmock in `@After`.
 - `RateLimited carries the exception's provider`: scan throws `RateLimitException(ScanProvider.CLAUDE)` -> `RateLimited(1, CLAUDE)`; second call -> `RateLimited(2, CLAUDE)`.
 - `NoApiKeyException maps to NoApiKey`.
 - `CancellationException is not turned into Error`: scan throws `CancellationException`; state stays `Scanning`, `close()` still called.
b) `TEST domain/GeminiCardScannerTest.kt`: client with an application interceptor wrapping the response body source in a `ForwardingSource` that sets `closed = true` on `close()`; assert `closed` after a successful `scan`. Caveat to record in the test comment: `ResponseBody.string()` also closes the source, so deleting `.use` alone stays green (it is redundant belt-and-braces); the test guards against swapping to a non-closing read (mutation in table).
c) `TEST domain/PerceptualHasherTest.kt:238-264`: rewrite `cancellation propagates`. Keep dispatcher (1200 ms delay, 8 cards, 4 permits). After `started.await()`: `job.cancel(); job.join()`, then real `delay(1_800)` and `assertEquals(4, server.requestCount)` (second wave never started). Remove the always-true `assertTrue(job.isCancelled)`.
d) `TEST data/remote/TcgcsvJapanSourceTest.kt`: `a cooled-down group spends no FetchBudget`: `getJapanProducts(1)` throws `IOException`; `FetchBudget(3)`; first `imageUrl("M6","048",budget)` -> `budget.left == 2`; second lookup -> still 2 and `coVerify(exactly = 1){ api.getJapanProducts(1) }`.
e) `TEST repository/SettingsRepositoryTest.kt` + `PublishSettingsRepositoryTest.kt` (item 9, keep hand-written `toString`): over `Class.declaredFields` (skip static/synthetic): (1) `toString()` of a default instance contains `"<name>="` for every field; (2) with every String field set to `"SENTINEL_<name>"`, any String field whose sentinel appears in `toString()` must be in an explicit per-class allowlist (`PublishConfig`: `githubOwner`, `githubRepo`; `AppSettings`: empty), else fail "new String field leaks into toString". Keep the existing "never contains key" tests.
f) Share-sheet rotation gate - **defer.** Real layer is `ui/settings/SettingsScreen.kt:80-87` (`LaunchedEffect(resetState)` + `shareSheetShown`). Testing it needs a Compose + Hilt `ActivityScenario.recreate()` androidTest over the full SettingsScreen with `startActivity` intercepted: emulator-only, high setup, for a one-`if` gate whose flag is already covered at ViewModel level (`ResetCollectionViewModelTest.kt:104,117`). Record in gaps.md with this reason.

### T7 [S] Item 11 - `connectedDebugAndroidTest` exit 1 (Tester-stage investigation, time-box 30 min)
Facts: AVD `pokedex_test` (android-36) exists in `C:\Users\SkylerMayday\.android\avd`; emulator at `C:\Users\SkylerMayday\AppData\Local\Android\Sdk\emulator\emulator.exe`; `adb` is not on PATH (use `...\Sdk\platform-tools\adb.exe`); `adb devices` now lists only the phone `R5GL53ZDT5F`. **Never run the task unpinned with the phone attached**: AGP runs on every attached device and uninstalls the app afterwards (deletes phone data).
Steps: boot `emulator.exe -avd pokedex_test -gpu swiftshader_indirect -no-window -no-snapshot`, wait for `emulator-5554`; `ANDROID_SERIAL=emulator-5554 ./run_build.bat connectedDebugAndroidTest`. First hypothesis: exit 1 stems from a second device or AGP's report step, not from test failures; compare `app/build/outputs/androidTest-results/connected/**` failure counts and `testlog/test-results.log` with the exit code. Outcome: a documented cause/fix (e.g. "pin ANDROID_SERIAL") or "unexplained after 30 min" in gaps.md. Not a merge gate; if the emulator will not boot, report deferred with the boot error. `compileDebugAndroidTestKotlin` may hit the sandbox PKIX wall: say so, do not retry.

## User stories
- As Skyler, when Claude returns garbage I see "Claude failed - try again", not an internet message.
- As Skyler, with only a Gemini key I never see a raw hostname error, and a rejected Gemini key says so.
- As Skyler, after a rate limit the screen points me at the right provider console.
- As Skyler, logcat tag `ScannerMatch` shows per-attempt ms and the 429 reason so I can tune the timeout and read the quota cause.

## Acceptance criteria
1. Each provider attempt yields exactly one `attempt provider= n= ms= outcome=` line; no line contains a URL, header, key, or exception message. `SCAN_CALL_TIMEOUT_MS == 10_000L`.
2. A scan returning Gemini's empty result after Claude fails logs `provider=gemini ...`, never `provider=none`.
3. Malformed reply (either provider) -> "<Provider> failed — try again", not "internet". Gemini 401/403 -> `InvalidApiKeyException`; combined text "Gemini key rejected and ...". Gemini-only network failure -> "Can't reach Gemini — check your internet connection", no hostname.
4. `RateLimited.provider` and `RateLimitException.provider` are `ScanProvider`; `rateLimitMessage` picks the console URL per provider; Gemini strings byte-identical.
5. 429 body (<=300 chars, key-redacted) logged for both providers; `RateLimitException` still thrown if peeking fails.
6. `EmptyScanException` is gone; log label `EmptyReply`.
7. All T6 tests exist and fail under their mutation; `./run_build.bat testDebugUnitTest --rerun-tasks` green (552 + new, 0 failures, fresh XMLs).
8. One `retryDelayMs` helper, one `RETRY_MS`, no restated 15-minute literal.
9. A new `AppSettings`/`PublishConfig` field missing from `toString` (or a leaking String field) fails the reflection test.
10. Auto sync reaches every id across successive days when >400 stay blank; manual sync unchanged.
11. Item 11 ends with a documented cause/fix or "unexplained, 30 min spent" in gaps.md; the phone is never touched.

## Mutation checks (apply, see RED, revert, see GREEN; note in test comments)
| Test | Mutation | Expected red |
|---|---|---|
| VM RateLimited provider | `ScannerViewModel.kt:124` pass `ScanProvider.GEMINI` | provider assert |
| VM NoApiKey | delete `catch (e: NoApiKeyException)` | state is `Error` |
| VM cancellation | delete `catch (e: CancellationException) { throw e }` (125-126) | state becomes `Error` |
| Gemini body close | `.use { it.body?.string() }` -> `.body?.byteStream()?.readBytes()?.decodeToString()` | `closed == false` |
| Hasher cancellation | `PerceptualHasher.findBestMatch`: `async` -> `GlobalScope.async` | requestCount 8, not 4 |
| Tcgcsv budget | swap `TcgcsvJapanSource.kt:162` and `:163` | second lookup spends budget (left 1) |
| toString guard | add `val extra: Boolean = false` to `AppSettings`; separately print `geminiApiKey=$geminiApiKey` | missing-field red; leak red |
| Retry helper | make `retryDelayMs` always `RETRY_MS` | both 401 backoff tests red |
| 429 log | remove the redaction `replace`; separately remove the log call | key-in-body red; body-missing red |
| Attempt log | drop the ok-path `log(...)` | attempt-line test red |
| Wording | drop `!is MalformedReplyException` in `isNetworkFailure` | "internet" assert red |
| Log order | restore `provider=none` log before the `geminiEmpty` return | no-`provider=none` assert red |
| Sync rotation | force `start = 0` | id #405 never looked up |
| rateLimitMessage | hardcode `console.anthropic.com` for Gemini | Gemini/aistudio assert red |

## Out of scope
Timeout retune, Gemini key rotation (dropped), Room/schema, new dependencies, share-sheet Compose test, Gemini HTTP 400 as key-invalid, Batches 2-4, camera tests for `CameraPreview`.

## Dependencies
Order: T1 -> T2 -> T3 (needs T1 enum and T2 log signature) -> T4, T5 (independent) -> T6 (a needs T1; b-e independent) -> T7 last (Tester). Main files touched: CardScanShared, CardScanProvider, ClaudeCardScanner, GeminiCardScanner, ScannerViewModel, ScannerScreen, TcgcsvJapanSource, SyncCardImagesUseCase. `SettingsRepository`, `PublishSettingsRepository`, `PerceptualHasher` need no main-code change. Build: `./run_build.bat testDebugUnitTest --rerun-tasks` from the worktree root (JAVA_HOME `D:\jdk17\jdk-17.0.14+7` if needed).

## Open questions
- Gemini's real invalid-key HTTP code is unverified (401/403 assumed; real bad keys may return 400). Non-blocking: after T2 ships, one wrong key entered in Settings shows `outcome=http400` (or `invalidKey403`) in logcat; Skyler decides then whether to add 400.
- Item 11 cause unknown by design.

## Risks and mitigations
- `ScanProvider` public widens API surface: same module only, acceptable.
- JVM `android.util.Log` throws: every new `log` seam defaults to `Log.i`; tests must override it (listed). A miss shows as "not mocked".
- Rotation shifts as ids resolve: documented ceiling; manual sync stays exhaustive.
- Hasher cancellation test adds ~3 s real time; acceptable.
- Existing tests may assert the old Gemini-only raw throw: only `gemini failure without claude key rethrows the same exception` (RateLimitException, stays raw). The full suite run catches others.

## Smallest shippable increment
T1 + T3 (typed provider, correct wording, log order) with T4/T5 and the tests for what they touch. T2 logging and the remaining T6 tests can follow; T7 is independent and may be deferred without affecting merge.

## Friction Notes
- `adb` is not on PATH in Bash; use the full SDK path. The worktree sandbox rejects compound git/pipeline Bash commands; run them singly.
- gaps.md says Gemini `InvalidApiKeyException` is "folded into Gemini failed", but production never raises it (no `invalidKeyCodes` for Gemini): the gap text described test-only behaviour.
