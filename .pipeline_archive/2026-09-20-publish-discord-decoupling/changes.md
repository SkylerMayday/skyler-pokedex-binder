# Changes — Decouple binder.json upload from Discord/changelog notification

Implements `.pipeline/specs.md` exactly: two independent `PublishDiff` signals —
`contentChanged` (structural, gates binder.json upload) and `hasChanges` (owned-transition
deltas only, gates changelog/Discord) — replacing the single `hasChanges` gate that
previously controlled both.

## Files changed

### `app/src/main/java/com/skyler/pokedexbinder/publish/model/PublishDiff.kt`
Added `contentChanged: Boolean` field with the doc comment from the spec.

**Deviation from spec's literal code (documented, necessary):** spec's sample has no
default value. Grep confirmed two other test files (`DiscordEmbedBuilderTest.kt`,
`PublishViewModelTest.kt`, 7 call sites total, out of spec's stated scope) construct
`PublishDiff(...)` without `contentChanged`. A required field with no default would break
their compilation — the spec's 5-task breakdown only lists `PublishRepositoryTest.kt` for
test changes, so this is a build-correctness gap in the given code sample, not a scope
decision. Fix: `contentChanged: Boolean = true` (default never engages in production —
`computeDiff()` always passes it explicitly). This is the smaller diff versus editing 7
call sites across 2 unrelated files, and touches zero files outside the spec's stated set.
Documented inline in the field's own comment.

### `app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt`
- `computeDiff()`: added `val contentChanged = next.binders != baseline?.binders` and threaded
  it into the returned `PublishDiff`. Pure function of the existing in-scope `baseline`/`next`,
  no new params, no new logic branches in the diff-matrix `when` block.
- `publish()` line 131 gate: `!diff.hasChanges` → `!diff.contentChanged`.
- The `// a. Upload binder.json` block: untouched, still unconditional (re-verified after
  every edit — this was the spec's explicit warning to check for regression).
- `onStep(PublishStep.NotifyingDiscord)` moved to fire unconditionally before the new
  `if (diff.hasChanges)` block, so the UI progress step never appears skipped.
- Changelog-fetch body and Discord-send body moved verbatim inside `if (diff.hasChanges) { ... }
  else { Log.i(...) }` — re-indented only, no internal logic rewritten. Exception steps
  inside (`PublishFailedException(PublishStep.Uploading, ...)` for changelog PUT failure,
  `PublishStep.NotifyingDiscord` for Discord failure) are unchanged.

### `app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt`
- Added `assertTrue(diff.contentChanged)` to the 3 existing ADDED/REMOVED/REPLACED
  `computeDiff` tests, and `assertFalse(diff.contentChanged)` to the 2 existing no-change
  tests (both-present-same-card, both-null) — no existing assertions removed or weakened.
- 2 new `computeDiff`-level tests: new-unowned-slot is `contentChanged && !hasChanges`;
  identical `binders` with different `publishedAt` is `!contentChanged`.
- 3 new `publish()`-level tests: content-only publish (binder.json PUT called, changelog
  GET/PUT and Discord webhook NOT called); owned-transition change with webhook configured
  (binder.json PUT, changelog GET+PUT, Discord webhook ALL called — no prior test asserted
  all three together with a webhook); first-publish with zero owned cards (`Success`,
  binder.json PUT called, changelog/Discord skipped, `contentChanged=true`/`hasChanges=false`).
- `no changes returns NoChanges without PUT calls or webhook` test: untouched, unmodified.
- All new tests reuse existing helpers (`entry`, `slot`, `binderWith`, `pcCache`, `notFound`,
  `contentResponse`, `repository.buildSnapshot`) — no new test infrastructure added.

All 9 acceptance criteria from the spec have direct test coverage (mapped 1:1 above).

## Simplification & review pass (Phase 5)

- **Reuse**: no new helpers needed; every new test uses existing fixtures.
- **Quality**: no new abstractions, no nested conditionals added, doc comments explain WHY
  (the two signals' distinct purposes) not WHAT.
- **Efficiency**: `next.binders != baseline?.binders` is one `List.equals()` call per publish
  (already flagged as an accepted non-issue in the spec's own risk table at this data scale).
- **Correctness**: re-read the full `publish()` body post-edit end to end — binder.json upload
  block confirmed unconditional and byte-identical to the original; `onStep` ordering matches
  spec; exception steps inside the moved blocks unchanged.
- No findings required fixing beyond the one documented default-value deviation above.

## Verification (Item M — 7 checks)

Full verbatim output: `.pipeline/evidence/changes-verify.log`.

1. **Typecheck** (`:app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun-tasks`) —
   **blocked**, not a code failure. See "Environment blocker" below.
2. **Lint** (`:app:lintDebug --rerun-tasks`) — **blocked**, same cause.
3. **Scoped tests** (`PublishRepositoryTest`, `DiscordEmbedBuilderTest`, `PublishViewModelTest`)
   + **full suite** (`:app:testDebugUnitTest --rerun-tasks`, run since `PublishDiff` is a
   shared model touched by 2 other test files) — **blocked**, same cause.
4. **Production build** (`:app:assembleDebug --rerun-tasks`) — **blocked**, same cause.
5. **Dev/start server boots** — N/A, Android app with no server process.
6. **No stray console.log/debugger/scratch files** — confirmed clean:
   `git diff` on the 3 changed files grepped for `console.log|debugger|TODO|FIXME|System.out.println`
   → no matches. Scratch `.ps1` scripts used to invoke Gradle live in the session scratchpad
   (outside the repo), never touched the working tree.
7. **`git status` shows only intended files** — confirmed:
   ```
    M app/src/main/java/com/skyler/pokedexbinder/publish/PublishRepository.kt
    M app/src/main/java/com/skyler/pokedexbinder/publish/model/PublishDiff.kt
    M app/src/test/java/com/skyler/pokedexbinder/publish/PublishRepositoryTest.kt
   ```

### Environment blocker (checks 1-4)

Every Gradle invocation (both the batched run and an isolated `--no-daemon` retry) failed
identically before reaching any compilation step:

```
FAILURE: Build failed with an exception.
* What went wrong:
java.io.IOException: Unable to establish loopback connection
```

This is the documented Gradle daemon IPC wall for this project/sandbox
(`~/.claude/rules/lessons/subagent-sandbox-blocks-loopback-sockets-try-orchestrator-first.md`):
a subagent-sandbox restriction on loopback/AF_UNIX sockets, confirmed across at least 4 prior
occurrences in this project, that clears immediately when the *orchestrator* runs the identical
command directly — not a code or configuration defect. Per the task brief's explicit
instruction, stopping after 2 attempts (batched run + `--no-daemon` retry, both via a real
`.ps1` + `powershell.exe -File`, `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`, `--rerun-tasks`) rather
than cycling further workarounds the lesson file already lists as confirmed dead ends
(`usePlainSocketImpl`, forced `WindowsSelectorProvider`). **The orchestrator should run
checks 1-4 directly** — expected to clear on first try per the lesson's history.

Manual code-review substitute for checks 1-4 while blocked: traced every call site of
`PublishDiff(...)` (`Grep` for `PublishDiff\(`) and confirmed the new field's default value
keeps the 2 unrelated test files compiling; traced the only production `hasChanges` call site
(line 131, now `contentChanged`) and confirmed no other production/UI code references either
field (`PublishViewModel.kt`, `DiscordEmbedBuilderTest.kt` construct `PublishDiff` but don't
read `hasChanges`/`contentChanged` directly).

**Update (Tester stage, orchestrator-run — see `.pipeline/test-results.md`)**: the Gradle wall
cleared for the orchestrator, as expected. It surfaced 2 real test failures this diff introduced —
both new tests (`content-only publish...`, `first publish with zero owned cards...`) hit the new
`else` branch's `Log.i("PublishRepository", ...)` call, which this test file's `@Before` never
stubbed (only `Log.w` was) — the unstubbed static call threw under MockK, and `publish()`'s own
`catch (e: Throwable)` silently turned that into a `Failure` instead of the expected `Success`. Not
a logic bug in the `contentChanged`/`hasChanges` gating itself — confirmed via the full stack trace,
which failed at the very first `assertTrue(result is PublishResult.Success)` line in each test, not
at any of the gating assertions further down. Fixed by adding `every { Log.i(any(), any<String>()) }
returns 0` alongside the existing `Log.w` stub. After the fix: `testDebugUnitTest --rerun-tasks`:
**315/315 passing, fresh XML**; `assembleDebug lintDebug --rerun-tasks`: `BUILD SUCCESSFUL`.

## New dependency check (Item P)

N/A — no manifest changed, no new dependency added.

## Friction Notes

- Hit the documented Gradle daemon IPC wall (`Unable to establish loopback connection`) on
  every attempt (batched script + isolated `--no-daemon` retry) before reaching compilation.
  Confirms the existing lesson file's guidance still holds as of this session — no new
  workaround found, none attempted beyond what the lesson already marks as dead ends.
- The spec's exact code sample for `PublishDiff.kt` (no default value on the new field) would
  have broken compilation of 2 test files outside its own stated scope
  (`DiscordEmbedBuilderTest.kt`, `PublishViewModelTest.kt`) — worth flagging to whoever writes
  specs with literal before/after code blocks: a new required field on a shared data class
  needs either its default value stated or an explicit note to grep for other constructor call
  sites first.
