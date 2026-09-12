# Handoff — Session 15 (2026-09-11 to 09-13, continuation of session 14)

## 1. Goals

Continuation from session 14: installed the `GUIDE_FRAME_WIDTH_RATIO=0.75` build, then chased a
live chain of real problems as Skyler actually tried to use it — a Gemini API 404 (caused by his
own quota-decoupling work between sessions), then a deep dive into whether a hash-based/on-device
matching approach (inspired by a competitor app, EyeRis) could replace Gemini as the primary
identification path. Ended with Skyler explicitly deferring the build and asking to spec it,
log it, and wrap up.

## 2. Current State

### `gemini-2.5-flash` → `gemini-3.6-flash` model swap: fixed, committed (`fd7752b`), pushed, installed

Skyler decoupled pokedex-binder onto its own Google Cloud project/key (per session 14's quota
finding) between sessions. Every scan then 404'd. Root-caused by replaying the exact request
directly against Google's API and reading the real error body (the app itself only surfaces the
numeric code, discards the message) — `gemini-2.5-flash` was retired for any API key/project
created after 2026-09-10, Google's own error names `gemini-3.6-flash` as the replacement. The old
shared key (created March 2026) had kept working the whole time; the 404 only appeared once a
genuinely new key/project existed to be new-user-gated. Confirmed the new model handles the app's
real request shape (image + JSON-mode prompt, not just a trivial text ping) before shipping.
296/296 tests pass, installed and verified live.

### Real root cause of the persisting scanner symptom: found, spec'd, NOT built

Skyler live-tested after both session-14 fixes (ratio 0.75, model swap) and got the identical
`number=null` failure on all 3 scans (Castform x2, Furfrou x1) — neither fix touched the actual
bottleneck. Traced two distinct, verified (not guessed) root causes:

1. **`PerceptualHasher.computeHash()` has a real, live bit-mapping bug** — `1L shl i` for `i` up to
   255 wraps via JVM's masked `Long.shl` (shift amount mod 64), so 4 different pixels OR onto the
   same final bit instead of each getting an independent one. Verified empirically: replayed the
   exact algorithm (real JVM shift semantics, not assumed) against 16 real card images fetched
   live from `api.pokemontcg.io` (8 Charizard prints + 8 clearly-different Pokémon) — several
   completely different cards hashed identically, average pairwise distance 6.0/64. A corrected
   8x8-resize version (matching a real 64-bit capacity) separated the same 16 images to 21.5/64
   average, no collisions — confirms the fix works, not just that the bug exists. Has silently
   degraded every hash-based pick since the hasher was introduced (`498d035`, 2026-05-20).
2. **Independent of the bug above: `SmartThresholdUseCase.evaluate()` structurally can never mark
   high confidence when Gemini's number is null**, no matter how good the hash is — it only checks
   a `numberMatch` among candidates, never consults the hasher's own result at all. This is why
   fixing #1 alone would not have fixed the reported symptom — confirmed by tracing the exact code
   path, not assumed.

Also researched a shipped competitor (EyeRis, App Store id6789088141) at Skyler's request — it
advertises on-device camera matching rather than cloud OCR. Verified this app's own narrower
design (keep Gemini for species ID, hash-rank same-species candidates) does **not** require
bundling a full card-image database — it reuses the existing per-scan candidate-fetch flow.
A more ambitious "skip Gemini entirely, match blind against the full catalog" design was
considered and explicitly rejected: verified `api.pokemontcg.io` alone has 20,479 cards, and the
hash's own measured separation is already thin among ~20 same-species candidates — full-catalog
matching with a plain 64-bit average hash would be materially worse, and would need a trained
embedding model (a much bigger, separate project) to work reliably at that scale.

**Full spec, both fixes plus the still-open `candidates.size == 1` short-circuit from session 14:**
`docs/specs/2026-09-13-scanner-hash-confidence.md`. **Explicitly not started** — Skyler said spec
it, log it, wrap up; do not begin `dev-team-pipeline` without his go-ahead.

## 3. Active Files

- `app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt` — model constant
  updated to `gemini-3.6-flash`, comment explaining the retirement.
- `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt` — **uncommitted**,
  temporary diagnostic block added (saves every scan's exact crop to
  `Android/data/com.skyler.pokedexbinder/files/scan_debug_*.jpg`, logs the path). Added `Context`
  injection to support it. Explicitly marked "TEMP DIAGNOSTIC ... remove once resolved" in its own
  comment — do not let this drift into a real commit without deciding it should stay.
- `docs/specs/2026-09-13-scanner-hash-confidence.md` — new spec, this session.
- `gaps.md`, `project-overview.md`, this file — updated this session.
- Session transcript re-archived (same continuing session as 14):
  `D:\Claude Projects\Digital Brain\raw-sources\conversations\2026-09-10-423b5118.md`. Not
  ingested — engineering-specific content already properly homed in this project's own docs, not
  personal/wiki-relevant knowledge.

## 4. Changes Made (commits, chronological)

- `fd7752b` — `gemini-2.5-flash` → `gemini-3.6-flash`, committed and pushed directly (contained,
  single-constant fix with a clear external cause, same class as prior direct tunes).
- Nothing else committed this session — the diagnostic block is deliberately left uncommitted
  (see Active Files above), and the hash-confidence fix is spec-only per Skyler's explicit
  instruction not to build yet.

## 5. Failed Attempts

- None on the coding side — the model-deprecation fix landed clean on the first real attempt,
  backed by reading Google's actual error body rather than guessing.
- claude-mem's observation timeline still had no entries scoped to this session's actual topics
  when checked for this handoff (global DB count is non-zero and grew during the session — 145 to
  180 — so the recorder is working, just hasn't compressed this specific session's content yet).
  Handoff is transcript-derived for that reason, same caveat as session 14's own note.

## 6. Next Steps

1. **Build `docs/specs/2026-09-13-scanner-hash-confidence.md` once there's enough session usage
   available** — this is the actual fix for the number-null / manual-list symptom he's been
   hitting all week. Skyler has already pre-authorized this explicitly: **either
   `dev-team-pipeline` or a direct implementation, orchestrator's call, just not this session**
   (usage-constrained, not a design objection). No further sign-off needed on approach — only on
   timing. Two margin-calibration open questions in the spec itself (no real data yet beyond the
   one 16-card sample) will need live S26 Ultra testing regardless of which path is taken.
2. **Decide what to do with the uncommitted temp diagnostic block in `ScannerViewModel.kt`** —
   either use it (scan a few more cards, pull the saved crops, actually see what the live capture
   looks like before assuming the ratio/model changes were sufficient) or revert it. Left
   uncommitted deliberately so it doesn't linger in git history either way.
3. **Gemini quota**: `pokedex-binder`'s own GCP project/key is now separate from `Claude-mem`'s —
   confirmed working (this session's whole investigation happened because of it, and the fix
   shipped). No further action needed unless it recurs.
4. **binder.json republish** — still gated on Skyler's own confidence that scanning works, per
   every prior session's note. Unchanged, now additionally blocked on item 1 above.
5. **Carried, unchanged from session 13**: the unchanged 400/401/403/404 path in
   `GeminiCardScanner.kt` still has no dedicated test — cheap follow-up whenever that file is next
   touched, not urgent.
