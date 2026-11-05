# Test results: Batch 1 scanner (branch worktree-gaps-batch1-scanner)

## STATUS: PASS (unit layer). Item 11 / T7: NOT RUN (PKIX wall on androidTest artifacts). No production defect found, so Stage 4 is not needed.

| Check | Result |
|---|---|
| `./run_build.bat testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL, 31/31 tasks executed, exit 0 |
| Fresh XML count (48 files, `app/build/test-results/testDebugUnitTest`) | tests=579 skipped=0 failures=0 errors=0 (baseline 552; Coder 577; +2 mine) |
| Mutation spot-checks | 6 distinct mutations, all RED in the intended test, all reverted, tree clean |
| Tree after everything | `git status --short` empty; HEAD f98e5f6 |
| Item 11 | not run, see below |

Full verbatim output of the final run: `.pipeline/evidence/test-results.log` (ends `BUILD SUCCESSFUL in 2m 23s`, `EXIT=0`). Decisive excerpt:

```
BUILD SUCCESSFUL in 2m 23s
31 actionable tasks: 31 executed
EXIT=0
```
XML tally (script over all 48 result files, taken right after that run): `48 files tests 579 skipped 0 failures 0 errors 0`.

## Test layer
Pure JVM unit tests (MockWebServer, MockK, runTest). Android UI/androidTest layer not applicable to this diff beyond item 11. Environment: existing Gradle/JUnit runner works; no setup needed.

## Test code added by Tester (commit f98e5f6, trailer present)
`app/src/test/.../data/remote/TcgcsvJapanSourceTest.kt`, two tests, found via review (only the 401 branch of `retryDelayMs` was tested):
- `a 403 from the groups call also backs off for hours`
- `a 503 from the groups call is retried after 15 minutes, not hours`

Red-green proof (logs in `.pipeline/evidence/`):
- `mut-403.log`: `retryDelayMs` reduced to `e.code() == 401` -> `TcgcsvJapanSourceTest > a 403 from the groups call also backs off for hours FAILED`, `26 tests completed, 1 failed`.
- `mut-503.log`: condition forced to `(true)` -> `TcgcsvJapanSourceTest > a 503 from the groups call is retried after 15 minutes, not hours FAILED`, `26 tests completed, 1 failed`.
- `mut-403-green.log`: unmutated code, same class, exit 0.

## Mutation spot-checks of the spec table (`.pipeline/evidence/mutation-spotcheck.log`)
Four applied together in four different files, run on the four owning test classes (`83 tests completed, 4 failed`), then `git checkout -- app/src/main`:
```
TcgcsvJapanSourceTest > a cooled-down group spends no FetchBudget FAILED            (budget/cooldown line swap)
CardScanProviderTest > empty gemini reply plus claude network failure returns the empty result FAILED   (provider=none log before empty return)
PerceptualHasherTest > cancellation propagates FAILED                               (async -> GlobalScope.async)
SyncCardImagesUseCaseTest > successive auto runs rotate so every blank id is reached FAILED   (start forced to 0)
```
Each failure is exactly the test the spec names, and no other test went red. Tree clean after revert (`git status --short` empty), then the full suite above is green. Plus the two retryDelayMs mutations above. The other 8 table rows were not re-applied by me; the Coder's logs `mutation-batch1.log`/`mutation-batch2.log` are the only evidence for them.

## Adversarial review (read of the code, then grep of all callers)
| Probe | Finding |
|---|---|
| Key in 429 body | Redaction (`String.replace`, literal not regex) runs on a 2048-byte peek BEFORE the 300-char cap, so a key straddling the cap cannot leak a prefix. Header values trimmed upstream. Failed peek swallowed, `RateLimitException` still thrown (covered by Coder's unpeekable-body test). Residual: a key echoed JSON-escaped (non-ASCII/quote characters) would not match; Gemini/Anthropic keys are plain ASCII, so not a realistic leak. |
| Rotation math | Modulo only evaluated when `limit < first.size`, so `first.size >= 1`: no divide-by-zero. Empty or small list gives `start = 0`. `(now/day)*limit` is Long*Int, no overflow at 400. Manual sync (`Int.MAX_VALUE`) never rotates. List is `sorted()` before `associateWith`, which preserves order. No defect. |
| `retryDelayMs(null)` | Parameter is non-null `Exception`; no null path. Real gap found was 403 and non-blocked HTTP codes untested: fixed above. |
| Malformed vs network | `isNetworkFailure` excludes `MalformedReplyException`; `fromReply` maps EOF/JsonEncoding (IOException), `JsonDataException` and null to it. `InterruptedIOException` catch precedes the generic `IOException` catch (correct order). |
| Call sites of changed API | grep over main, test and androidTest: every `RateLimitException(`, `RateLimited(`, `rateLimitMessage(`, `executeWithRetry(` uses the enum; no remaining `EmptyScanException`; androidTest has no scanner/provider use (only the `useCameraScanner` setting in `AppNavigationScreenTest.kt`). |
| Known limit, not a bug | Gemini HTTP 400 for a bad key is not classed as invalid key (spec out-of-scope). Log `outcome=http400` on the phone will show it. |

## Item 11 (T7): not run
- Emulator `pokedex_test` booted headless, `adb devices` listed only `emulator-5554` (no phone attached at that time). Ran `ANDROID_SERIAL=emulator-5554 ./run_build.bat connectedDebugAndroidTest` (log: `.pipeline/evidence/item11-connected.log`).
- Result: `BUILD FAILED in 23s`, `EXIT=1`, before any test executed. Cause is the sandbox PKIX wall, not the app: `Failed to transform monitor-1.7.1.aar ... Could not HEAD 'https://dl.google.com/dl/android/maven2/androidx/test/runner/1.6.1/runner-1.6.1.aar' ... PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors` (same for multidex-2.0.1.aar). Not retried, per instruction.
- So the original "exit 1" cause of item 11 stays unexplained here; the hypothesis (second device / report step) was not testable. Skyler can run `$env:ANDROID_SERIAL="emulator-5554"; .\gradlew.bat connectedDebugAndroidTest` from his own PowerShell where the cert chain resolves.
- Emulator shut down with `adb -s emulator-5554 emu kill`; `adb devices` empty afterwards; `adb kill-server` run; `run_build.bat --stop` reports no Gradle daemons. Phone never touched.

## Coverage gaps remaining
- androidTest layer (item 11 and the deferred share-sheet rotation test, 7f) unverified in this environment.
- Lint (`lintDebug`) not runnable (same PKIX wall), per the Coder's `build-lint.log`.
- 8 of the 14 mutation rows rest on the Coder's logs only.

## Friction Notes
- Compound Bash is rejected in this sandbox, and so is a quoted `--tests "*Class"` glob; unquoted fully qualified class names work, one plain command per call.
- A Gradle build running in the background compiles whatever is on disk: I edited a test file mid-run, so the first full-run log was superseded by a second full rerun.
- The device roster changes between sessions (phone R5GL53ZDT5F was attached at the start, gone later): always check `adb devices` before any connected task.
