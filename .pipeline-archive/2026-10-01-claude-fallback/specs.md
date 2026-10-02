# Spec: Scanner dual-provider fallback (Gemini Flash -> Claude Sonnet)

**HARD RULE FOR CODER / TESTER / REVIEWER: do NOT run or retry Gradle** (loopback failure in this harness; not a code problem). Verify by reading code. Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks` himself. Many uncommitted edits already in the tree: expected, do not revert. No new dependencies. No Room change.

## TL;DR
Scanner tries Gemini first; on ANY non-cancellation failure it resends the same bitmap to Claude (`claude-sonnet-4-6`, raw OkHttp). A new Anthropic key lives next to the Gemini key (EncryptedSharedPreferences). `NoApiKey` shows only when both keys are blank; a Gemini 429 with a Claude key set never reaches the `RateLimited` UI.

## Problem (grounding verified by reading)
- `domain/GeminiCardScanner.kt`: `scan(bitmap, apiKey)` builds prompt inline, `bitmapToBase64` (private, 1024px, JPEG q80, `Base64.NO_WRAP`), private `executeWithRetry` (MAX_SCAN_RETRIES=3, base 500ms, 429->`RateLimitException`, 5xx/`SocketTimeoutException` retried, other non-2xx -> `IOException("Gemini error: N")`), inline `GeminiCardResult -> ParsedCardInfo` mapping (language trim+lowercase+nonEmpty, printedName nonBlank). Test seam: `internal var baseUrl`. Key in `x-goog-api-key` header. `RateLimitException` is declared at top of this file, message "Rate limited by Gemini API", never constructed with args.
- `repository/SettingsRepository.kt`: Gemini key lives in `securePrefs` (`@Named("settingsSecrets")` SharedPreferences) under `SecureKeys.GEMINI_API_KEY`; `getGeminiApiKey()`/`setGeminiApiKey()` run on `Dispatchers.IO` after `migrateGeminiKeyIfNeeded()` (legacy DataStore migration - NOT needed for the new key). `AppSettings.geminiApiKey` is populated in the `settings` flow.
- `ui/settings/SettingsScreen.kt:153-174`: `OutlinedTextField` with `PasswordVisualTransformation` + visibility toggle, `enabled = settings.useCameraScanner`, write-through `onValueChange`. `SettingsViewModel.setGeminiApiKey` at line 34.
- `ui/scanner/ScannerViewModel.kt:66-146`: loads Gemini key, blank -> `NoApiKey`; `catch (e: RateLimitException)` increments `consecutiveRateLimits` -> `RateLimited`; `catch (e: Exception)` -> `Error(e.message)` (note: this also swallows `CancellationException` into `Error` today - pre-existing, see Risks). `ScannerScreen.kt:820` NoApiKey text says "No Gemini API key set..."; `:862-890` RateLimited text is Gemini-specific.
- Tests: `GeminiCardScannerTest` (MockWebServer; stubs `Base64.encodeToString` and `JSONObject.quote` via mockkStatic because plain-JVM `org.json`/`android.util.Base64` are stubs). `ScannerViewModelTest` only tests the pure `confidenceCheckInputs` - no test constructs `ScannerViewModel` or `GeminiCardScanner` outside `GeminiCardScannerTest`, so no existing constructor call sites break (`SettingsRepositoryTest` only needs the new getter additions to compile; it does not need edits). **No `unitTests.returnDefaultValues` in build.gradle: `android.util.Log` throws in JVM tests** - orchestrator must take an injectable logger (below).
- Anthropic request shape (verified in `~/.claude/skills/claude-api/curl/examples.md` + `python/claude-api/README.md` + `shared/error-codes.md` + `SKILL.md` model table): `POST https://api.anthropic.com/v1/messages`; headers `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`; body `{"model","max_tokens","messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"..."}},{"type":"text","text":"..."}]}]}`; response `{"content":[{"type":"text","text":"..."}], ...}`. Errors: 401 auth, 403 permission, 429 rate limit, 500 api_error, 529 overloaded (all 5xx retryable). Model id `claude-sonnet-4-6` (no date suffix). Do not send temperature/top_p/top_k.

## ADR-lite: where the fallback decision lives
| Option | Verdict |
|---|---|
| A. Orchestrator reads both keys itself, throws `NoApiKeyException` when neither; ViewModel just catches it | **Chosen.** Key-selection logic is fully unit-testable without constructing the Hilt ViewModel (which needs `ImageProxy`, `Context`, `Log`). VM diff is ~10 lines. |
| B. VM reads both keys, passes them in | Rejected: puts the selection logic in the untestable class. |
| C. Interface `CardScanner` with two impls | Rejected: one consumer, no polymorphism need (YAGNI). Orchestrator holds the two concrete classes (mockk handles final classes). |

## Proposal - technical layer: files and signatures

### 1. `domain/CardScanShared.kt` (NEW) - extractions, M
```kotlin
internal const val CARD_SCAN_PROMPT: String   // EXACT text moved verbatim from GeminiCardScanner.scan (incl. the "é" in Pokémon/Pokédex). Do not edit a character.
internal fun GeminiCardResult.toParsedCardInfo(): ParsedCardInfo   // existing mapping, verbatim
internal fun bitmapToJpegBase64(bitmap: Bitmap, maxDim: Int = 1024): String  // moved from Gemini: scale (recycle only if scaled !== bitmap), JPEG q80, Base64.NO_WRAP
internal suspend fun OkHttpClient.executeWithRetry(
    request: Request, provider: String, invalidKeyCodes: Set<Int> = emptySet()
): Response
```
`executeWithRetry` = Gemini's current loop moved verbatim, parameterised: 429 -> `RateLimitException(provider)`; code in `invalidKeyCodes` -> `InvalidApiKeyException(provider, code)`; 5xx exhausted -> `IOException("$provider error: $code")`; other non-2xx -> same. Gemini passes `provider="Gemini"`, empty set, so its messages/behaviour are byte-identical (existing tests assert only types/counts). Move `MAX_SCAN_RETRIES`, `SCAN_RETRY_BASE_DELAY_MS`, `backoffDelayMs` and the explanatory comment with it. Keep the "close response before retry" behaviour. In the 401/403 branch, `response.close()` before throwing.

New exception types (put beside `RateLimitException`, in `GeminiCardScanner.kt` or the new file):
```kotlin
class RateLimitException(provider: String = "Gemini") : IOException("Rate limited by $provider API")
class InvalidApiKeyException(provider: String, code: Int) : IOException("$provider rejected the API key (HTTP $code)")
class NoApiKeyException : IllegalStateException("No scanner API key configured")
```
Exception messages must never contain a key or request URL/headers.

### 2. `domain/GeminiCardScanner.kt` (MODIFY) - S
Replace inline prompt/mapping/base64/retry with calls to the shared items. Keep `baseUrl`, adapters, request JSON, header `x-goog-api-key`, public `scan` signature. Delete the now-moved private helpers. `GeminiResponse*` classes stay.

### 3. `domain/ClaudeCardScanner.kt` (NEW) - M
```kotlin
internal const val CLAUDE_MODEL = "claude-sonnet-4-6"      // single named constant
private const val CLAUDE_BASE_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_MAX_TOKENS = 512                   // JSON object is ~100 tokens

@JsonClass(generateAdapter = true) data class ClaudeResponse(val content: List<ClaudeContentBlock>? = null)
@JsonClass(generateAdapter = true) data class ClaudeContentBlock(val type: String? = null, val text: String? = null)

class ClaudeCardScanner @Inject constructor(private val okHttpClient: OkHttpClient, private val moshi: Moshi) {
    internal var baseUrl: String = CLAUDE_BASE_URL          // test seam, same pattern as Gemini
    suspend fun scan(bitmap: Bitmap, apiKey: String): ParsedCardInfo   // withContext(Dispatchers.IO)
}
```
`scan`: `bitmapToJpegBase64` -> body string built like Gemini's (`JSONObject.quote(CARD_SCAN_PROMPT)`, base64 interpolated; fields: model, max_tokens, messages as above; **no temperature/top_p/top_k**) -> `Request` with the three headers (`.header("x-api-key", apiKey)`; content-type via `toRequestBody("application/json".toMediaType())`) -> `okHttpClient.executeWithRetry(request, "Claude", invalidKeyCodes = setOf(401, 403))` -> `moshi.adapter(ClaudeResponse)` -> first block with `type == "text"` and non-null text, else `IOException("No text in Claude response")`; null body -> `IOException("Empty Claude response")` (use `response.body?.string()`, not `!!`, and `response.use {}` so the body is closed).
Fence-tolerant parse (private helper, unit-testable as `internal fun extractJsonObject(text: String): String`): substring from first `{` to last `}`; if none, return text unchanged. Then `runCatching { cardResultAdapter.fromJson(json) }.getOrNull() ?: GeminiCardResult()` then `.toParsedCardInfo()`. (Mirrors Gemini: unparseable JSON -> all-null `ParsedCardInfo`, NOT an exception. Per brief "empty/unparseable response" must fall back: Gemini's "empty" = IOException already falls back; an all-null parse does NOT trigger fallback in v1 - see Open questions.)
Logging: none in this class. Never log the request, URL, headers, or body.

### 4. `domain/CardScanProvider.kt` (NEW) - M
```kotlin
@Singleton
class CardScanProvider @Inject constructor(
    private val gemini: GeminiCardScanner,
    private val claude: ClaudeCardScanner,
    private val settings: SettingsRepository
) {
    /** Test seam: Log is unavailable in JVM unit tests. */
    internal var log: (String) -> Unit = { android.util.Log.i("ScannerMatch", it) }

    suspend fun scan(bitmap: Bitmap): ParsedCardInfo
}

/** Pure, testable. */
internal fun selectProviders(geminiKey: String, claudeKey: String): List<ScanProvider>   // ScanProvider enum GEMINI, CLAUDE; order Gemini then Claude; blank = absent; trim keys
```
Behaviour of `scan` (keys read via `settings.getGeminiApiKey()` / `getAnthropicApiKey()`, each `.trim()`ed):
1. `selectProviders` empty -> throw `NoApiKeyException`.
2. Gemini present: try it. Success -> log `scan provider=gemini`, return. `catch (e: CancellationException) { throw e }` FIRST. `catch (e: Exception)`: if no Claude key -> rethrow `e` unchanged (today's `RateLimited`/`Error` behaviour preserved). Else log `scan provider=claude fallbackReason=${e::class.java.simpleName}` (class name only, never `e.message`) and continue to Claude.
3. Claude (fallback or only): success -> log `scan provider=claude` (when Claude-only, `fallbackReason=none`), return. On failure: `CancellationException` rethrown; else if Gemini was attempted and failed, `claudeError.addSuppressed(geminiError)` and **throw Claude's exception** (the most recent/actionable one: e.g. 401 "Claude rejected the API key" tells Skyler to fix the key; Claude 429 -> `RateLimitException` -> RateLimited UI; the Gemini cause remains in the log line and as suppressed). Log `scan provider=none geminiError=<class> claudeError=<class>`.
Never log or include keys. Do NOT add any provider-health memory (see Risks: leaves room, deliberately unbuilt).
Hilt: both scanners and `SettingsRepository` are already injectable; `@Singleton` on the provider is fine (stateless). No module changes needed.

### 5. `repository/SettingsRepository.kt` (MODIFY) - S
Add `SecureKeys.ANTHROPIC_API_KEY = "anthropic_api_key"`; `AppSettings.anthropicApiKey: String = ""` (add as last param with default; populate in `settings` flow from `securePrefs`); `suspend fun getAnthropicApiKey(): String` and `suspend fun setAnthropicApiKey(key: String)` mirroring Gemini's but without the migration call (nothing to migrate; calling `migrateGeminiKeyIfNeeded` is harmless but pointless - skip). Use `.apply()` like Gemini.

### 6. Settings UI - S
- `SettingsViewModel`: `fun setAnthropicApiKey(key: String) = viewModelScope.launch { settingsRepository.setAnthropicApiKey(key) }`.
- `SettingsScreen.kt`: after the Gemini field (line ~174) add a second `OutlinedTextField` cloned from it: `var anthropicKeyText by remember(settings.anthropicApiKey) {...}`, `var anthropicKeyVisible by remember { mutableStateOf(false) }`, label "Anthropic API Key (fallback)", supportingText "Used when Gemini fails. console.anthropic.com", same `PasswordVisualTransformation` toggle, same `enabled = settings.useCameraScanner`. Update the toggle description (line 147) to "Requires a Gemini or Anthropic API key."

### 7. `ScannerViewModel.kt` + `ScannerScreen.kt` - S
- Constructor: replace `geminiCardScanner: GeminiCardScanner` with `cardScanProvider: CardScanProvider`; **keep `settingsRepository`** only if still used elsewhere in the class (grep: after change it is unused -> remove it and its import to avoid a dead dependency).
- In `processImage`: delete the key read/blank check (lines 70-74). Move bitmap crop first. Replace `geminiCardScanner.scan(bitmap, apiKey)` with `cardScanProvider.scan(bitmap)`. Add `catch (e: NoApiKeyException) { _state.value = ScannerState.NoApiKey }` BEFORE the `RateLimitException` catch. Rename the log prefix `"gemini parsed:"` -> `"scan parsed:"`. Ordering consequence: with no keys the camera crop + debug capture now run before the NoApiKey state (harmless; keep simple). Optional cheaper alternative if Coder prefers: no change needed.
- `consecutiveRateLimits`/`reset()` logic unchanged: the orchestrator only surfaces `RateLimitException` when there is no Claude key or Claude itself 429'd, which is exactly the required RateLimited condition.
- `ScannerScreen.kt:822` text -> "No API key set. Add a Gemini or Anthropic key in Settings to use the scanner." RateLimited strings (lines ~880-883): keep, but generalise "Gemini" to "the scanner" only if trivially safe; otherwise leave (cosmetic). Check `ScannerScreenTest` for string assertions before editing any string.

### 8. Tests - L
New `domain/ClaudeCardScannerTest.kt` (copy GeminiCardScannerTest's setUp: mockkStatic `Base64`, `JSONObject.quote`; MockWebServer; `baseUrl` override):
1. success: body `{"content":[{"type":"text","text":"{\"name\":\"Pikachu\",\"number\":\"25\",...}"}]}` -> mapped fields; language lowercased, printedName kept.
2. request shape via `server.takeRequest()`: headers `x-api-key`==key, `anthropic-version`==`2023-06-01`, content-type starts `application/json`; **no** `Authorization`; path has no `key=`. Body: parse with Moshi `Map` adapter (NOT `org.json`, which is a stub on plain JVM) and assert `model == CLAUDE_MODEL`, `max_tokens` present, `messages[0].content[0].type=="image"`, `source.type=="base64"`, `source.media_type=="image/jpeg"`, content[1] text equals `CARD_SCAN_PROMPT`, and body lacks `"temperature"`, `"top_p"`, `"top_k"`. (Note the test's `JSONObject.quote` stub escapes only `\` and `"`; the prompt has no newline escapes issue? It contains `\n` - the stub leaves a raw newline in JSON, which is invalid JSON. Fix: in this test's stub use `org.json`-free escaping: replace `\n` with `\\n` too, or assert on body string via `contains` instead of parsing. Coder: pick the contains approach if parsing fails.)
3. fenced JSON: text "```json\n{...}\n```" parses; also text with prose before/after the braces; and `extractJsonObject` pure cases (no braces -> unchanged).
4. 429 -> `RateLimitException`, exactly 1 request; message contains "Claude".
5. 401 and 403 -> `InvalidApiKeyException`, 1 request, message does not contain the key.
6. 500 x4 -> `IOException`, `requestCount == 4`; 529 then success -> 2 requests; timeout retry mirrors Gemini tests (short-timeout client).
7. no text block / empty `content` -> `IOException`; unparseable text -> all-null `ParsedCardInfo`.

New `domain/CardScanProviderTest.kt` (mockk the two scanners and `SettingsRepository`; set `provider.log = { logs += it }`; `runTest`):
- `selectProviders` pure table: both / gemini only / claude only / neither / whitespace-only treated blank.
- gemini ok -> claude `coVerify(exactly = 0)`; log contains `provider=gemini`.
- gemini `RateLimitException` -> claude result returned; log has `provider=claude fallbackReason=RateLimitException`.
- gemini `IOException`, `SocketTimeoutException`, `InvalidApiKeyException`, generic `RuntimeException` -> claude used (one parameterised loop).
- gemini fails + claude key blank -> the SAME exception instance rethrown; claude never called.
- claude key only -> gemini `coVerify(exactly = 0)`.
- neither -> `NoApiKeyException`.
- gemini fails then claude fails -> claude's exception thrown, `suppressed` contains gemini's; Gemini 429 + Claude 429 -> `RateLimitException`.
- gemini throws `CancellationException` -> rethrown, claude never called; claude throws it -> rethrown.
- no log line contains the key strings (use distinctive keys, e.g. `"SECRET-G"`, `"SECRET-C"`, assert across all captured logs).

Extend `SettingsRepositoryTest` only if its fake `SharedPreferences` makes it cheap: set/get Anthropic key round-trips and independent of the Gemini key.
`ScannerViewModelTest`: leave as is (pure function). `ScannerViewModel` NoApiKey/RateLimited behaviour is covered at the orchestrator level (`NoApiKeyException` -> NoApiKey; `RateLimitException` only escapes when no fallback/Claude 429), plus a code-read check by Reviewer of the two catch blocks. State this honestly in the report: the VM catch mapping itself has no JVM test (it needs `ImageProxy`/`Context`).
Existing `GeminiCardScannerTest` should pass unchanged (refactor must preserve behaviour); Coder re-reads it against the refactor instead of running it.

## Tasks (dependency order)
| # | Task | Size | Depends |
|---|---|---|---|
| 1 | `CardScanShared.kt` + exception classes; refactor `GeminiCardScanner` onto them | M | - |
| 2 | `SettingsRepository` Anthropic key + `AppSettings` field | S | - |
| 3 | `ClaudeCardScanner.kt` | M | 1 |
| 4 | `CardScanProvider.kt` | M | 1,2,3 |
| 5 | `ScannerViewModel`/`ScannerScreen` wiring | S | 4 |
| 6 | Settings VM + screen field | S | 2 |
| 7 | `ClaudeCardScannerTest`, `CardScanProviderTest` (+ repo test) | L | 3,4 |
| 8 | Update `project-overview.md`/`gaps.md`/`handoff.md` per project rules (Skyler's wrapcon) | S | all |

## User stories
- As Skyler, I want the scanner to fall back to Claude when Gemini errors, so scanning keeps working after the free quota is spent.
- As Skyler, I want to paste an Anthropic key in Settings, masked like the Gemini one, so the fallback can authenticate.
- As Skyler, I want no behaviour change when I have only a Gemini key, so existing flow keeps working.

## Acceptance criteria
- [ ] **Given** both keys set and Gemini returns 200, **when** a scan runs, **then** Claude is never called and the log shows `provider=gemini`.
- [ ] **Given** both keys set and Gemini fails with 429/4xx/5xx-after-retries/timeout/IOException, **when** a scan runs, **then** the Claude result is used, the scanner shows a normal match state, and the log shows `provider=claude fallbackReason=<ExceptionClass>`.
- [ ] **Given** only a Gemini key and Gemini 429s, **when** scanning, **then** `RateLimited` shows exactly as today.
- [ ] **Given** both keys and both 429, **then** `RateLimited`; **given** Gemini fails and Claude 401s, **then** `Error` with the invalid-key message.
- [ ] **Given** only a Claude key, **then** Gemini is never called.
- [ ] **Given** both keys blank, **then** `NoApiKey`; given either key set, `NoApiKey` never shows.
- [ ] `CancellationException` is never swallowed or fallen-back-on by the orchestrator.
- [ ] Anthropic request uses `x-api-key`, `anthropic-version: 2023-06-01`, model `claude-sonnet-4-6`, base64 `image/jpeg` image block, same prompt text as Gemini, no temperature/top_p/top_k.
- [ ] No key appears in any log line, exception message, or URL; no HTTP logging interceptor added.
- [ ] Anthropic key stored in the same EncryptedSharedPreferences as Gemini's; Settings field masked with a show/hide toggle.
- [ ] `GeminiCardScannerTest` unchanged and still passing; new tests above pass under `testDebugUnitTest --rerun-tasks` (Skyler runs).

## Out of scope
Bake-off/accuracy comparison (`scripts/claude_scan_bakeoff.py`, Skyler runs it); circuit breaker / remembering a failed provider across scans; cost-tracking UI; model picker UI; removing Gemini; prompt caching (the prompt is ~200 tokens, below caching minimums); Anthropic SDK / new dependencies; Room changes; changing the pre-existing `CancellationException`-to-`Error` swallow in the ViewModel's generic catch (noted only).

## Dependencies
- External: `api.anthropic.com` (Skyler's own Anthropic key, console.anthropic.com); existing Gemini API.
- Internal: `SettingsRepository`, `OkHttpClient`/`Moshi` Hilt singletons (no module change).
- Gradle unavailable to agents: Skyler runs the test suite.

## Open questions
- [ ] (Skyler, non-blocking) A Gemini reply that parses to all-null fields (e.g. unparseable JSON) currently returns an empty `ParsedCardInfo`, not an exception, so it does NOT trigger fallback in v1. The brief lists "empty/unparseable response" as a fallback trigger; v1 treats only exceptions (including "no text"/"empty body" IOExceptions) as failures. Recommend leaving as is: all-null can also mean a non-card photo, and fallback would burn $0.006 on those. Say so if you want it.
- [ ] (Reviewer to confirm) Anthropic key prefix validation (`sk-ant-`): not added; wrong key yields a 401 -> clear error.

## Risks and mitigations
| Risk | L | I | Mitigation |
|---|---|---|---|
| After Gemini's daily quota is gone, every scan first spends a failed Gemini call (429 returns fast, ~1 RTT; but 5xx/timeouts cost up to ~3.5s retries + timeouts before fallback) | High | Low | Accepted v1. Orchestrator leaves room: add a `@Volatile var geminiSkipUntil: Long` inside `CardScanProvider` (singleton), set on `RateLimitException`, checked in step 2 - ~6 lines, no signature change. Not built now. |
| Worst-case latency when Gemini times out: 4x30s before fallback | Low | Med | Existing Gemini retry budget kept as-is; revisit if observed. |
| Key leak in logs (a Gemini key leaked via URL logging before) | Med | High | Keys only in headers; logs carry exception class names only; test asserts no key in logs; no logging interceptor. Reviewer: grep new files for `Log.`/`toString()` of requests. |
| Refactor of `GeminiCardScanner` regresses it | Med | High | Verbatim moves, message parity (`"Gemini error: N"`), existing test file untouched and re-read against the change; Skyler runs full suite. |
| Claude "max_tokens" truncation / extra prose breaks parse | Low | Low | 512 tokens; `extractJsonObject` brace slicing; parse failure -> null fields (same as Gemini). |
| Claude 4xx other than 401/403/429 (e.g. 400 on an oversize image, 413) | Low | Low | Generic `IOException("Claude error: N")`, no retry. Image is <=1024px JPEG so size is not a concern. |
| ViewModel catch mapping untested in JVM | Med | Low | Logic pushed into orchestrator; VM change is two catch clauses; manual verification below. |
| Claude's higher per-scan cost on bad days (~$0.006) | Low | Low | Only on Gemini failure; no auto-loop (one Claude attempt + its bounded retries per scan). |

## Smallest shippable increment
Tasks 1-7 (shared extractions, Claude scanner, orchestrator, key storage + Settings field, VM wiring, tests). Nothing smaller works: without the Settings field there is no way to supply the key. Later: skip-Gemini-for-N-minutes after 429; all-null-result fallback; model picker.

## Verification procedure (Skyler)
1. `.\gradlew.bat testDebugUnitTest --rerun-tasks` - expect all green incl. `ClaudeCardScannerTest`, `CardScanProviderTest`, unchanged `GeminiCardScannerTest`.
2. `.\gradlew.bat installDebug`; Settings -> enable Camera Scanner -> paste Anthropic key into the new field (shown masked; eye icon toggles). Re-open Settings: key persists.
3. Normal path: `adb logcat -s ScannerMatch:*`, scan a card with a valid Gemini key -> `scan provider=gemini`, no Claude traffic.
4. Force fallback: temporarily set a wrong Gemini key (Gemini returns 400/403) -> scan -> expect `scan provider=claude fallbackReason=IOException` and a normal match. Quota-exhausted case shows `fallbackReason=RateLimitException`.
5. Claude-only: blank the Gemini key -> scan works, `provider=claude fallbackReason=none`.
6. Neither key: blank both -> "No API key set" message with Go to Settings.
7. Bad Anthropic key + bad Gemini key -> Error state naming "Claude rejected the API key (HTTP 401)". Confirm no key text appears anywhere in `adb logcat`.
8. Restore the real Gemini key afterwards.

## Friction Notes
- `android.util.Log` throws in JVM tests here (no `returnDefaultValues`), forcing an injectable `log` lambda on the orchestrator; same stub problem makes `org.json` unusable for asserting request bodies in tests (use Moshi/`contains`).
- Spec section on the `JSONObject.quote` stub: existing stub does not escape newlines, so the multi-line prompt would yield invalid JSON in tests; Coder must handle.
