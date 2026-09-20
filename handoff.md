# Handoff — Session 16 (2026-09-17 to 09-20)

## 1. Goals

Continued from session 15's deferral: build the hash-margin confidence spec, then chase the real
root cause when live-testing showed it wasn't enough, then (per Skyler's explicit instruction) fix
every remaining standing gap that doesn't require his physical involvement.

## 2. Current State

Four separate `dev-team-pipeline` runs shipped this session, all committed and pushed to
`origin/master` (`b1e68fb..b04cd36`):

### 1. Hash-margin confidence path (`b1e68fb`) — shipped, live-tested, holding up so far

Fixed `PerceptualHasher.computeHash()`'s real bit-mapping bug (8x8 resize, no more `1L shl i`
overflow), added a margin-gated `HASH_MARGIN` confidence path for when Gemini can't OCR the card
number, narrowed the `candidates.size==1` auto-trust short-circuit. Ship at 95/100. **Live-tested**:
5 real post-install scans all correctly avoided false-accepts (real improvement over session
14/15's actual wrong-assigns), though none hit high confidence yet either — margins observed were
all thin (0-5), consistent with `HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD=12` not yet confirmed to
catch a true positive. Full detail: `project-overview.md` item 15.

### 2. Scanner capture bugs: sideways images + guide-box clipping (`c39ddef`) — shipped, NOT live-tested

Live-testing #1 above led to pulling the actual `scan_debug_*.jpg` files off the phone and looking
at them directly — found two real, previously-undiagnosed bugs: captured images were never rotated
upright (`ImageProxyExt.kt` used `rotationDegrees` only for crop math, never applied the actual
pixel rotation), and the guide box gave too little visual/physical margin (87.7% of canvas height,
corner-brackets only), causing real header/footer clipping even when the card looked "in frame."
Fixed both: `Matrix().postRotate()` for rotation, a full `drawRect` border + new
`CROP_MARGIN_FACTOR=1.10f` for the margin. Ship at 97/100. Full detail: `project-overview.md` item 16.

**This is the one thing still blocking calling this session fully done** — needs a real scan on the
S26 Ultra, then `adb pull` the resulting `scan_debug_*.jpg` to confirm (a) right-side-up, (b) full
card visible header-to-footer, not just the middle band.

### 3. Gap-audit batch (`5e563e5`) — shipped, no live-device dependency

Skyler asked to "fix everything that doesn't need me to run." Ran a manual security audit (found
the Gemini API key was stored in plaintext, unlike the GitHub PAT/Discord webhook) and a dependency
audit (no active CVEs on pinned versions, no bump made). Shipped 5 fixes: encrypted the Gemini API
key with a migration for Skyler's existing key, closed the hash-margin download-failure P2 from fix
#1's own gap list, excluded `backup_import_state.xml` from Auto Backup, added the missing
`GeminiCardScanner` 404 test, documented `PendingImportError`'s deliberate SharedPreferences choice.
**Reviewer caught a real P1** in the first pass — the key migration's `.apply()` could race the
plaintext clear and lose the key on a process death — fixed via `.commit()`, re-reviewed clean at
95/100 (up from 77/100). Full detail: `project-overview.md` item 17.

### 4. Decouple binder.json upload from Discord/changelog (`b04cd36`) — shipped, no live-device dependency

Skyler answered the Personal Collection policy question from #3 directly: show all cards
(grayscale until owned — already correct in-app), Discord/changelog only on an actual owned-card
change. Tracing that exposed a real bug: `PublishDiff.hasChanges` (owned-transitions only) was the
sole gate for uploading binder.json at all, so a newly-cached *unowned* card produced zero deltas
and the whole publish short-circuited to `NoChanges` — new prints never reached the site. Added a
second signal, `PublishDiff.contentChanged` (structural diff of `binders`, excluding
`publishedAt`), now gating the upload; `hasChanges` keeps its exact prior meaning and now
exclusively gates changelog/Discord. Ship at 89/100, then closed the one P2 finding (an error-step
misattribution regression the same diff introduced) rather than accepting it — 100 after. Two real
bugs caught and fixed mid-pipeline (a missing `Log.i` mock stub, the step-misattribution). 315/315
tests. Full detail: `project-overview.md`'s "Publish Diff Semantics" section.

## 3. Active Files

All committed, nothing uncommitted. `.pipeline/` is empty (all 4 runs archived under
`.pipeline_archive/2026-09-17-scanner-hash-confidence/`, `.pipeline_archive/2026-09-20-scanner-rotation-crop-margin/`,
`.pipeline_archive/2026-09-20-gap-audit-batch/`, `.pipeline_archive/2026-09-20-publish-discord-decoupling/`,
all committed).

## 4. Changes Made (commits, chronological)

- `b1e68fb` — hash-margin confidence path (session 16 start).
- `c39ddef` — scanner rotation fix + guide-box border/margin fix.
- `5e563e5` — gap-audit batch (API key encryption + 4 smaller fixes).
- `b04cd36` — decouple binder.json upload from Discord/changelog notification.

All pushed to `origin/master` this session (were 1-2 commits behind before — pushed at Skyler's
explicit request to sync for his home Android Studio setup).

## 5. Failed Attempts

- None on the coding/logic side across all 4 pipeline runs. Real bugs were caught by
  Tester/Reviewer stages before shipping in 3 of the 4 runs (a bogus MockK import in run #2, the
  `.apply()`/`.commit()` durability race in run #3, a missing `Log.i` mock stub + an error-step
  misattribution in run #4) — all fixed within the same pipeline run, no wasted cycles.
- One Reviewer subagent hit the account's session rate limit mid-run (run #3's re-review pass) —
  orchestrator substituted directly, same precedent as session 13.
- The sandbox's Gradle daemon IPC wall fired for every Coder-stage subagent across all 4 runs,
  100% reproducible this session (not just intermittent, per this session's own friction notes) —
  orchestrator's direct runs cleared every time, no exceptions.

## 6. Next Steps

1. **Live-test the rotation + guide-box fix (`c39ddef`)** — the one thing actually blocking calling
   this session's scanner work done. Take a real scan on the S26 Ultra; ask Claude to `adb pull` the
   resulting `scan_debug_*.jpg` and confirm it's upright and shows the full card.
2. **Retune 3 uncalibrated constants once live data exists**: `HASH_MARGIN_HIGH_CONFIDENCE_
   THRESHOLD=12`, `HASH_SINGLE_CANDIDATE_MAX_DISTANCE=16`, `CROP_MARGIN_FACTOR=1.10`. All first-guess,
   same standing pattern as `GUIDE_FRAME_WIDTH_RATIO`'s entire history.
3. **One policy question left, only Skyler can answer**: is it worth introducing Robolectric to get
   real test coverage on the `EncryptedSharedPreferences`-backed repositories
   (`SettingsRepository`, `PublishSettingsRepository`), which currently have none?
4. **Next real publish will behave differently** — worth doing once, deliberately, to confirm:
   Personal Collection's cached-but-unowned cards should now show up in `binder.json` even without
   an owned-card change riding along, and Discord should stay silent for that publish. Not
   live-tested against the real GitHub/Discord endpoints this session (only mocked).
5. **Deliberately not touched, needs live-camera verification if ever picked up**: the
   `CameraControl$OperationCanceledException` focus-race noise (not blocking), `detectCardInFrame()`'s
   duplicated geometry (cosmetic dedup, deferred 3 times now).
6. **binder.json republish** — still gated on Skyler's own confidence that scanning works, per
   every prior session's note. Unchanged.
