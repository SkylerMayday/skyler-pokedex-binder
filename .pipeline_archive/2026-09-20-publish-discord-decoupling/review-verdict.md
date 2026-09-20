# Review Verdict — Decouple binder.json upload from Discord/changelog notification

**Scope**: 3 files vs `main` — `PublishRepository.kt`, `publish/model/PublishDiff.kt`,
`PublishRepositoryTest.kt` (`git diff --stat`: 210 insertions, 77 deletions). Single-lens review
per orchestrator's sizing call — confirmed correct, no security-sensitive surface, no MCP/agent
surface (Agent-Native dimension skipped, doesn't apply). No prior `review-verdict.md` — first pass.

## Intent audit (Phase 2)

Clean. Exactly the 3 files the spec names, nothing else touched (`git status --short` confirms).
All 5 tasks from the spec's breakdown implemented; all 9 acceptance criteria have direct,
independently-verified test coverage (see Test Evidence below). No scope creep, no missing
requirements.

One documented, justified deviation: `PublishDiff.contentChanged` carries `= true` where the
spec's literal sample has no default. Verified real: `DiscordEmbedBuilderTest.kt` and
`PublishViewModelTest.kt` construct `PublishDiff(...)` without the field (confirmed via grep —
zero `contentChanged` references in either file), so a non-default field would break their
compilation. Neither file reads `contentChanged` or `hasChanges` in an assertion, so the default
is inert in both — verified by grepping both files for `contentChanged` (no matches) and
confirming `PublishViewModelTest.kt`'s only `PublishDiff(...)` call site (line 36, helper `diff()`)
never asserts on either field.

## Verification of the 5 specifically-flagged risk points

1. **`contentChanged` excludes `publishedAt` correctly** — confirmed. `BinderSnapshot` (model file)
   is `data class BinderSnapshot(val schemaVersion: Int, val publishedAt: String, val binders:
   List<SnapshotBinder>)` — `publishedAt` is a sibling top-level field, not nested inside
   `binders`. `val contentChanged = next.binders != baseline?.binders` (PublishRepository.kt:532)
   compares only `.binders`, so two snapshots differing only in `publishedAt` correctly compare
   equal. The new test `computeDiff identical binders but different publishedAt is not
   contentChanged` exercises exactly this and passes (fresh XML, see below).
2. **Upload block still unconditional** — confirmed by reading the code directly (not the diff
   summary): lines 140-158 (`// a. Upload binder.json` through the `if
   (!binderPutResponse.isSuccessful) throw ...`) sit between the `contentChanged` early-return gate
   (line 131) and the new `if (diff.hasChanges)` block (line 167), at the same indentation level as
   both — genuinely unconditional, not nested inside anything new.
3. **Changelog/Discord bodies moved verbatim** — confirmed via diff read: internal logic of both
   bodies is byte-identical, only re-indented. One real, verified side effect from the move (see
   Finding 1 below) — not what the spec's risk table anticipated, but in the same family it
   accepted.
4. **`contentChanged` default inertness** — confirmed above (Intent audit).
5. **Tester's `Log.i` root-cause claim** — confirmed by reading `@Before`: `every { Log.w(any(),
   any<String>()) } returns 0` (pre-existing) sits directly above the newly-added `every { Log.i(any(),
   any<String>()) } returns 0` (PublishRepositoryTest.kt:82-83), both before `repository =
   PublishRepository(...)` is constructed. Independently re-confirmed against the actual build
   artifacts on disk (not just the report's prose): `app/build/test-results/testDebugUnitTest/
   TEST-com.skyler.pokedexbinder.publish.PublishRepositoryTest.xml` shows `tests="52" failures="0"
   errors="0"`, file timestamp 2026-09-20 (today) — a fresh run, not stale cache. Summed across all
   32 result XMLs in that directory: `TOTAL: 315`, all `failures="0" errors="0"` — matches the
   claimed 315/315 exactly, from the artifacts themselves rather than trusting the prose claim.

## Findings

### Finding 1 (P2, confidence 8/10) — `currentStep` misattribution on unwrapped exceptions during changelog fetch

`currentStep = PublishStep.NotifyingDiscord` (PublishRepository.kt:164) now fires **before** the
changelog GET/PUT calls, not after them as in the original code. The generic fallback handler,
unchanged: `catch (e: Throwable) { PublishResult.Failure(currentStep, e.message ?: "Unknown
error") }` (line 262), reports whatever `currentStep` happens to be at the moment of an
**unwrapped** exception (i.e. one not already caught into a `PublishFailedException` with its own
explicit step, such as an `IOException` from `gitHubApi.getContent(...)` on a network drop, or a
`JsonDataException` from `moshi.adapter(Changelog::class.java).fromJson(it)` on a corrupted
changelog.json). Before this diff, such an exception during changelog fetch reported step
`Uploading`; after this diff, it reports step `NotifyingDiscord`. This is user-visible:
`PublishDialog.kt:127`, `Text("Failed at: ${stepLabel(state.step)}")`, would show "Failed at:
Notifying Discord" for a failure that actually happened while fetching/parsing changelog.json, not
while contacting Discord.

The spec's own risk table anticipated the general shape of this change but characterized it only
as a **progress-label** cosmetic issue during the success path ("UI progress label is briefly
'wrong' while changelog network calls run... UI only shows a step label, no functional impact") —
it did not address the **failure-label** consequence on the exception path, which does carry a
diagnostic (not functional/data) impact. Low likelihood (requires an unwrapped exception
specifically inside the changelog GET/parse window, all HTTP-level failures there are already
wrapped with the correct `PublishStep.Uploading` step at lines 186 and 230), low blast radius (a
misleading label in an error dialog, message body still accurate), consistent with the "Low/Low"
rating the spec already assigned to the parent risk category — recommend **accept-risk** (already
in the spirit of what Skyler explicitly signed off on) or a one-line **defer**: revert
`currentStep = PublishStep.NotifyingDiscord` to fire only in the `if (diff.hasChanges)` branch (the
`onStep` call for UI purposes can stay unconditional and separate from the `currentStep` var used
for error attribution) if the mislabeling risk is unacceptable.

## What looks good

- `contentChanged`/`hasChanges` split does exactly what the spec asked, no more: verified the
  `when` block inside `computeDiff()` (lines 488-522) has zero new branches — `contentChanged` is
  computed as a separate `List.equals()` outside the diff matrix, exactly as the spec specified.
- Exception step-tagging for all *known* HTTP failure branches (binder PUT, changelog GET/PUT,
  Discord webhook) is unchanged and correct — each already carries its own explicit
  `PublishFailedException(step, ...)`, independent of the `currentStep` var (only the generic
  `catch (e: Throwable)` fallback is affected by Finding 1).
- Test coverage is genuinely new, not rubber-stamped: 3 new `publish()`-level tests exercise the
  content-only, owned-transition-with-webhook, and first-publish-zero-owned-cards paths with real
  `coVerify(exactly = 0/1)` call-count assertions, not just return-value checks.
- Root-cause discipline on the test failure: Tester traced the actual stack trace to the exact
  `assertTrue` line before concluding it was a mock-stub gap rather than a logic bug — verified
  independently by reading the fix location.

## Score

| Dimension | Points | Reasoning |
|---|---|---|
| Spec compliance | 24/25 | All 5 tasks + 9 acceptance criteria implemented and traced to real diff content; exactly the 3 named files touched, no scope creep. -1 for the undocumented-until-now interaction between the default-value deviation and spec's literal sample (justified and inert, but the spec itself should have flagged the shared-model constructor-site risk). |
| Correctness & bug-freedom | 21/25 | Core `contentChanged`/`hasChanges` gating logic, upload-block unconditionality, and body-move verbatim-ness all verified correct by reading the actual code. -4 for Finding 1 (P2, real but narrow/low-impact). |
| Security & reliability | 17/20 | No security surface touched (no secrets, no new endpoints, no user input). -3 for Finding 1's reliability/diagnostic-accuracy angle (misleading failure-step label on an unwrapped-exception edge case). |
| Maintainability & simplification | 14/15 | No new abstractions, doc comments explain the two signals' distinct purposes, full reuse of existing test fixtures. -1 for the now quite long trailing block-comment on `contentChanged`'s declaration (readable but dense). |
| Test evidence quality | 13/15 | Independently re-verified against real build artifacts, not just trusted prose: `TEST-*.xml` files dated today, `TOTAL: 315` failures=0 summed across all 32 suites matches the claimed 315/315 exactly. -2 because no dedicated Tester-stage `.pipeline/evidence/*.log` sidecar exists (only the Coder-stage's all-blocked `changes-verify.log`) — had to fall back to inspecting `app/build/test-results/**/*.xml` directly instead of a prose-summarized log file. |

**Total: 89/100**

## Verdict: **Ship**

Score ≥85, no unresolved P0/blocking finding.

**Update (orchestrator, post-verdict)**: applied the recommended 2-line fix for Finding 1 anyway,
since it was cheap and already fully specified — `currentStep = PublishStep.NotifyingDiscord` now
only sets right before the actual Discord webhook call (matching this function's exact pre-diff
position/semantics), while `onStep(PublishStep.NotifyingDiscord)` stays unconditional and separate
for UI progress purposes. An unwrapped exception during changelog fetch/parse now correctly reports
step `Uploading` again, same as before this diff existed. Re-verified: `testDebugUnitTest
--rerun-tasks` 315/315 (fresh XML), `assembleDebug lintDebug --rerun-tasks` clean. Finding 1 is
closed, not merely accepted.
