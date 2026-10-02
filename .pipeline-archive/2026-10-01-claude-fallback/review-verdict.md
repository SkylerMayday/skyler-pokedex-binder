# Review verdict: Scanner dual-provider fallback (Gemini -> Claude)

**Verdict: SHIP** (score 93/100, no P0/P1). First review pass, no prior verdict to compare.
Scope: CardScanShared.kt, ClaudeCardScanner.kt, CardScanProvider.kt, GeminiCardScanner refactor, SettingsRepository/SettingsViewModel/SettingsScreen, ScannerViewModel/ScannerScreen, new tests. Read by code only; Gradle not run. Tests 388/0 failures per test-results.md (run by Skyler, fresh XML).

## Intent audit (specs.md traced)
| Requirement | Status | Evidence |
|---|---|---|
| Shared prompt/mapping/base64/retry, exceptions | DONE | `CardScanShared.kt` |
| Gemini refactor, same signature/baseUrl/header | DONE | diff: `okHttpClient.executeWithRetry(request, "Gemini")` |
| ClaudeCardScanner (headers, model, no sampling params) | DONE | `.header("x-api-key", apiKey)`, `.header("anthropic-version", ANTHROPIC_VERSION)` |
| Orchestrator semantics | DONE | `CardScanProvider.scan` |
| Anthropic key in SettingsRepository | DONE | `securePrefs.edit().putString(SecureKeys.ANTHROPIC_API_KEY, key)` |
| Settings masked field + toggle text | DONE | `visualTransformation = if (anthropicKeyVisible) ... PasswordVisualTransformation()` |
| VM wiring, NoApiKey catch before RateLimit, dead dep removed | DONE | VM diff |
| ScannerScreen NoApiKey text | DONE | line 822 |
| Tests (Claude, Provider, repo) | DONE | 16 + 12 + 1 |
| Task 8 docs | Skyler's wrapcon (out of this review) | |
No scope creep in the in-scope files. Dependencies/manifest untouched.

## Your 7 checks
1. **Security: PASS.** Grep of ClaudeCardScanner.kt and CardScanShared.kt for `Log.`/`println`: none. Key only via `.header("x-api-key", apiKey)`; URL is plain `.url(baseUrl)`; exception messages are `"$provider rejected the API key (HTTP $code)"` / `"$provider error: ${response.code}"` (no key, URL or headers). Provider logs use `it::class.java.simpleName` only, never `e.message`; test `assertNoKeysLogged` covers it, with a failure message containing the secret to prove messages are not logged. Claude key stored in the same `@Named("settingsSecrets")` store as Gemini's. Field is `PasswordVisualTransformation` with toggle. No `toString()` carrying the key anywhere (no data class holds it except pre-existing `AppSettings`, see F3).
2. **Anthropic request: PASS.** Matches spec's doc-verified shape: `x-api-key`, `anthropic-version: 2023-06-01`, content-type via `toRequestBody("application/json".toMediaType())`, `"max_tokens":$CLAUDE_MAX_TOKENS` (512), image block `"media_type":"image/jpeg"`, no temperature/top_p/top_k (asserted by test). Parse takes first `type=="text"` block with non-null text, `extractJsonObject` handles fences/prose, null/blank body -> IOException. 401/403 -> `InvalidApiKeyException` (no retry), 429 -> `RateLimitException("Claude")` (no retry), 5xx incl. 529 retried, other 4xx -> IOException. I did not re-open the skill docs; relied on spec's recorded verification of them (the code agrees with that record).
3. **Orchestrator: PASS.** `catch (e: CancellationException) { throw e }` precedes `catch (e: Exception)` in both blocks; no Claude key -> `if (ScanProvider.CLAUDE !in providers) throw e` (same instance); Claude-only skips Gemini; both blank -> `NoApiKeyException`; both fail -> `geminiError?.let { e.addSuppressed(it) }` then throws Claude's. Each branch has a test with a real assertion (assertSame, coVerify exactly=0, suppressed.contains).
4. **Gemini refactor behaviour-preserving: PASS.** Retry loop diffed line by line against the removed private version: identical except `provider` parameterisation. Messages: `"Gemini error: N"` and `"Gemini scan failed after retries"` unchanged; `RateLimitException()` default message `"Rate limited by Gemini API"` unchanged; retry count 4 and 500/1000/2000ms backoff unchanged; base64 (1024px, q80, NO_WRAP, recycle-if-scaled) moved verbatim. GeminiCardScannerTest unchanged by this feature and in the green 388.
5. **ScannerViewModel: PASS (code-read; no JVM test, as spec admitted).** Gemini 429 + Claude key is absorbed in the provider so never reaches `catch (e: RateLimitException)`; NoApiKey only from `catch (e: NoApiKeyException)` when both blank; `consecutiveRateLimits++` path unchanged and only reachable when no Claude key or Claude 429 itself.
6. **Prompt byte-identical: PASS with caveat.** The current Gemini prompt in HEAD predates language/printedName (those were added in the earlier uncommitted work), so I could not diff against a committed original. The moved text matches the language/printedName wording recorded in `.pipeline-archive/2026-10-01-tcgdex-first-search/specs.md:48` and `scripts/claude_scan_bakeoff.py:48`, and the pre-existing fields are identical to the HEAD text in the diff. UNVERIFIABLE only to the extent the intermediate uncommitted text is gone; no discrepancy seen.
7. **Tests: PASS.** Would fail on broken code: header/body-shape assertions against MockWebServer, request counts (1 for 429/401/403/400, 4 for 500/timeout), same-instance rethrow, `coVerify(exactly = 0)`. No new dependency.

## Findings
| # | Sev | Conf | Finding | Motivating line |
|---|---|---|---|---|
| F1 | P3 | 8 | `executeWithRetry`: the 429 and generic non-2xx branches throw without closing the `Response` (leaks the connection until GC). Pre-existing in the Gemini loop and preserved verbatim; now also true for Claude. Not a regression. | `if (response.code == 429) throw RateLimitException(provider)` and `if (!response.isSuccessful) throw IOException(...)` |
| F2 | P3 | 7 | Spec's own open item, still open: Gemini reply that parses to all-null does not fall back. Documented decision, not a bug. | `?: GeminiCardResult()` then `result.toParsedCardInfo()` |
| F3 | P3 | 6 (verify) | `AppSettings` is a data class holding both keys, so any future `toString()`/log of `settings` would leak them. Today no such log exists in the reviewed files; pre-existing pattern (Gemini key already there). | `val anthropicApiKey: String = ""` |
| F4 | P3 | 6 | Provider trims keys; Settings stores the raw pasted string. Harmless (trim at use), noted only. | `settings.getAnthropicApiKey().trim()` |

Pre-existing, out of scope (spec-noted): VM generic `catch (e: Exception)` swallows `CancellationException` into `Error`.

## What looks good
Single copy of prompt/mapping/retry; orchestrator carries all decision logic so it is JVM-testable; `.use {}` and `body?.string()` instead of `!!` in the new scanner; injectable log seam; no logging in new scanner files.

## Score
| Dimension | Pts | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | All requirements traced; -1 for prompt not diffable against its pre-refactor intermediate text |
| Correctness & bug-freedom | 24/25 | No bugs; -1 F1 connection-leak edge (inherited) |
| Security & reliability | 19/20 | Keys header-only, never logged, same encrypted store; -1 F3 latent toString exposure |
| Maintainability & simplification | 14/15 | Good reuse; -1 for unused `invalidKeyCodes` generality being only 2 callers is fine, but F4 asymmetry minor |
| Test evidence quality | 12/15 | 388/0 fresh XML and strong assertions; -3 because test-results.md has no verbatim per-class counts for the new tests and VM catch mapping has no JVM test (acknowledged) |
| **Total** | **93/100** | Ship threshold 85 met, no P0/blocking |
