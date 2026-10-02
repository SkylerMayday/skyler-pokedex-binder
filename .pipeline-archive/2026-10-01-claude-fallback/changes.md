# Changes: Scanner dual-provider fallback (Gemini -> Claude)

Implements `.pipeline/specs.md` tasks 1-7 in dependency order. Task 8 (project docs) is Skyler's wrapcon, not done here. No new dependency (New-dependency check: N/A, manifest untouched). No Room change. Gradle NOT run (harness loopback failure). Earlier uncommitted edits left untouched.

## Files

| File | Change | Why |
|---|---|---|
| `domain/CardScanShared.kt` (NEW) | `CARD_SCAN_PROMPT` (verbatim move), `GeminiCardResult.toParsedCardInfo()`, `bitmapToJpegBase64()` + private `scaleBitmap`, `OkHttpClient.executeWithRetry(request, provider, invalidKeyCodes)` + retry constants/backoff/comment; exceptions `RateLimitException(provider="Gemini")`, `InvalidApiKeyException`, `NoApiKeyException` | Shared by both scanners. Gemini passes `"Gemini"` + empty set so messages/behaviour are unchanged (`"Gemini error: N"`, 429 -> RateLimit, 5xx/timeout retried 4x). 401/403 branch closes the response before throwing. |
| `domain/GeminiCardScanner.kt` | Removed inline prompt, mapping, base64, retry, `RateLimitException` + retry consts; calls shared items. `baseUrl`, adapters, request JSON, header, `scan` signature unchanged. | Task 1 |
| `domain/ClaudeCardScanner.kt` (NEW) | `CLAUDE_MODEL="claude-sonnet-4-6"`, Anthropic request (`x-api-key`, `anthropic-version: 2023-06-01`, no temperature/top_p/top_k), `ClaudeResponse`/`ClaudeContentBlock` Moshi classes, `internal extractJsonObject` (first `{` to last `}`), response read via `.use{}` and `body?.string()`. No logging. | Task 3 |
| `domain/CardScanProvider.kt` (NEW) | `@Singleton` orchestrator + `internal enum ScanProvider` + `internal selectProviders`. Gemini first; any non-cancellation failure falls back to Claude if key set, else rethrows same instance; both fail -> Claude's exception with Gemini suppressed; neither key -> `NoApiKeyException`. `internal var log` seam; logs carry provider + exception class names only. | Task 4 |
| `repository/SettingsRepository.kt` | `AppSettings.anthropicApiKey` (last, default `""`), `SecureKeys.ANTHROPIC_API_KEY`, populated in `settings` flow, `getAnthropicApiKey()`/`setAnthropicApiKey()` (no migration call, `.apply()`). | Task 2 |
| `ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt` | `setAnthropicApiKey`; second masked field with show/hide toggle cloned from Gemini's; toggle description now "Requires a Gemini or Anthropic API key." | Task 6 |
| `ui/scanner/ScannerViewModel.kt` | Ctor: `GeminiCardScanner` -> `CardScanProvider`; removed now-unused `SettingsRepository` dep + import; deleted key read/blank check; `cardScanProvider.scan(bitmap)`; `catch (NoApiKeyException) -> NoApiKey` before the `RateLimitException` catch; log prefix `scan parsed:`. | Task 5 |
| `ui/scanner/ScannerScreen.kt` | NoApiKey text -> "No API key set. Add a Gemini or Anthropic key in Settings to use the scanner." RateLimited strings left as-is (cosmetic, spec says only if trivially safe). | Task 5 |
| `test/.../ClaudeCardScannerTest.kt` (NEW) | 16 tests: success, language/printedName, request shape (headers, no Authorization, no `key=`, no temperature/top_p/top_k, Moshi-parsed body: model, max_tokens, image block, source, text == `CARD_SCAN_PROMPT`), fenced + prose JSON, `extractJsonObject`, 429 (1 request, "Claude"), 401/403 (no key in message), 500x4, 529 retry, 400 no retry, timeout retry/exhaustion, no-text variants, unparseable -> all-null. `JSONObject.quote` stub here also escapes `\n` (the spec's flagged problem), so the body parses as JSON. | Task 7 |
| `test/.../CardScanProviderTest.kt` (NEW) | 12 tests: `selectProviders` table, gemini ok (claude 0 calls), 429 fallback + log, 4 failure types fall back, no-claude-key rethrows same instance, claude-only (gemini 0 calls), no keys -> `NoApiKeyException`, both fail (suppressed + log), both 429, cancellation from each provider rethrown, no key strings in any log. | Task 7 |
| `test/.../SettingsRepositoryTest.kt` | +1 test: Anthropic key round-trip, independent of Gemini key, surfaced in `settings` flow. | Task 7 |

`GeminiCardScannerTest` not edited; re-read against the refactor: base64/JSONObject stubs still used (shared fn calls the same statics), bitmap mock width/height/compress path identical, RateLimit/500x4/404/503/timeout expectations map 1:1 to the unchanged `executeWithRetry` logic.

## Desk-check (compile) results
- Call sites: `RateLimitException()` (default arg) used only in tests + `executeWithRetry`; `GeminiCardScanner` constructed only in its test and Hilt; `ScannerViewModel(` constructed nowhere in code/tests; `AppSettings(` calls use defaults only; `geminiCardScanner`/`settingsRepository` have zero remaining refs in the VM (grep, evidence log).
- Hilt: `CardScanProvider`/`ClaudeCardScanner` use `@Inject constructor` over already-provided `OkHttpClient`, `Moshi`, `SettingsRepository`, `GeminiCardScanner`. No module change.
- Found and fixed during desk-check: `ParsedCardInfo` first five params have no defaults, so `CardScanProviderTest` uses a `parsed(name)` helper instead of `ParsedCardInfo(cardName=...)`; `catch (_: X)` replaced with named catch params (underscore catch param needs a newer Kotlin than verified).
- `ScannerScreenTest`: no assertions on the changed string (grep for "No Gemini"/"Add your free key" across `app/src` returns nothing).

## Simplification/review pass (done inline, no subagents)
- Reuse: prompt/mapping/base64/retry are single copies now; Claude reuses `GeminiCardResult` rather than a second DTO.
- Quality: no dead code; `SettingsRepository` dep dropped from VM; no `Log.`/URL/header logging in new main files (only the provider's injected `log` seam, class names only).
- Efficiency: one ordering note per spec: with no keys the crop + debug capture now run before `NoApiKey` (harmless, spec-accepted).
- Known limits, per spec: VM catch mapping has no JVM test (needs ImageProxy/Context); all-null Gemini parse does not trigger fallback (open question for Skyler).

## 7-check verification (raw output: `.pipeline/evidence/changes-verify.log`)

| # | Check | Result |
|---|---|---|
| 1 | Typecheck | NOT RUN (Gradle blocked). Desk-checked imports, signatures, nullability, Hilt, Compose params above. |
| 2 | Lint | NOT RUN (Gradle blocked). |
| 3 | Scoped tests | NOT RUN - Skyler runs `.\gradlew.bat testDebugUnitTest --rerun-tasks`. |
| 4 | Production build | NOT RUN - Gradle blocked; Skyler's `installDebug` covers it. |
| 5 | Dev/start boot | N/A (Android app, no server; needs device). |
| 6 | Stray debug output / scratch files | PASS. grep of the 3 new main files: only the intentional `Log.i` default of `CardScanProvider.log`. No console.log/debugger/println. No scratch files created. |
| 7 | git status | PASS. Only intended files from this task changed/added (list in sidecar), on top of the pre-existing uncommitted tree (untouched). `scripts/scan_capture.log` and `.pipeline-archive/*` are pre-existing untracked. |

## Friction Notes
- `ParsedCardInfo` has non-default positional params; spec examples assumed named-only construction. Worth a default-everything test factory if more tests build it.
- Spec's `JSONObject.quote` stub warning was correct; handled by a newline-escaping stub in `ClaudeCardScannerTest` only.
