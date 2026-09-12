# Spec — Perceptual-Hash Confidence Path for the Scanner Match Pipeline

Status: draft, pending Skyler approval. **Do not start `dev-team-pipeline` on this until Skyler
explicitly says go** — planning only for now.

## Context

Session 14 (2026-09-10/13) live-tested the scanner after two unrelated fixes (`GUIDE_FRAME_WIDTH_
RATIO` 0.5→0.75, `gemini-2.5-flash`→`gemini-3.6-flash`) and got the same two real failures both
times:

1. **Furfrou misread as "Hisuian Zorua"** — a different species entirely, confidently assigned.
2. **Furfrou and Castform both twice returned `parsed.cardNumber = null`** — Gemini correctly read
   the species but not the tiny printed card number, so the app fell back to a manual-pick list
   both times instead of assigning a card.

Neither the ratio bump nor the model swap touched either root cause. Both were traced directly in
code, not guessed:

- **(1)** is `SmartThresholdUseCase.evaluate()` (line 18) and `PerceptualHasher.findBestMatch()`
  (line 39) both short-circuiting to auto-high-confidence whenever the name-based search returns
  exactly 1 candidate — skipping the perceptual-hash visual check entirely on that path. Gemini's
  wrong species name narrowed the search to 1 (wrong) candidate before any visual check could run.
- **(2)** is `SmartThresholdUseCase.evaluate()` requiring a Gemini-read card number to ever mark
  high confidence when there's more than 1 candidate (line 21-33) — it never consults the
  perceptual hasher's own result at all. A perfect hash still hits this wall whenever Gemini can't
  read the number, which is common for the small printed text even when the species is read fine.

**Separately found while investigating (2), not caused by anything above**: `PerceptualHasher.
computeHash()` (`PerceptualHasher.kt:18-33`) has a real, live bug. It loops over all 256 pixels of
a 16x16 downsample but folds the bit position via `1L shl i` — Kotlin/JVM's `Long.shl` masks the
shift amount to the low 6 bits, so `i` and `i+64` (and `i+128`, `i+192`) OR onto the *same* final
bit instead of each pixel getting its own bit. Verified empirically, not theoretically: replayed
the exact algorithm (JVM shift semantics faithfully reproduced) against 16 real card images fetched
live from pokemontcg.io (8 different Charizard prints + 8 clearly-different Pokémon) —
**several completely different cards hashed identically** (multiple Pikachu prints, Eevee, and
Detective Pikachu all collapsed to `0xffffffffffffffff`), average pairwise distance across all 16
genuinely-different cards was **6.0 out of a possible 64**, several pairs at exact distance 0. This
predates all three sessions' worth of scanner work — it's been silently degrading `findBestMatch`'s
picks since the hasher was first introduced (`498d035`, 2026-05-20).

## Competitive research (informs, doesn't drive, the decision below)

Looked at EyeRis (App Store: "Binder Scan & Price," Cards Of Iris PTE. LTD.), a shipped competitor
solving the identical problem (identify a physical TCG card from a photo). Its listing advertises
**on-device camera matching** ("no typing, no guesswork") rather than a cloud-OCR-first flow like
this app's Gemini call. Free tier is explicitly limited (subscription unlocks "higher scan limits
and additional features") — its exact technique isn't publicly documented, and its free-tier
capability may not represent what it can actually do, so treat this as inspiration for the
direction, not a spec to match.

Skyler asked whether replicating this means bundling a full local image database of every card.
**Verified: no, not for the design below.** That's only required for a more ambitious "skip
Gemini's species ID entirely, match blind against the full catalog" design — rejected below, not
in scope. `pokemontcg.io` alone has 20,479 cards (`api.pokemontcg.io/v2/cards?pageSize=1` →
`totalCount`); a bundled/pre-hashed full-catalog index at that scale, with only a 64-bit average
hash, would face the same discriminative-power ceiling probed below, likely worse (full catalog,
not just same-species candidates).

## Decision

Keep Gemini for species identification (already reliable outside rare misreads) and for the number
when it's readable. Add the perceptual hash as a **second, independent path to high confidence**
when Gemini's number comes back null, gated on the hash's own **margin** (best match clearly ahead
of the second-best) rather than trusting a bare "lowest distance wins" pick blindly. Fix the hash
algorithm first — nothing downstream can be trusted until it produces real bits.

### Options considered

| Option | Assessment |
|---|---|
| **A. Fix hash + margin-gated confidence path** (chosen) | Reuses the existing per-scan candidate-fetch flow (`CardSearchRepository` already fetches candidates + image URLs; `PerceptualHasher` already downloads and hashes them) — no new data pipeline. Verified against real card images: separates most same-species prints well (14-36 bits apart in the Charizard sample) but not all (one real pair only 4 bits apart) — a margin gate, not a bare top-pick, is required for this to be trustworthy. |
| B. Full local/bundled image+hash database, skip Gemini's species ID entirely | Rejected. Verified 20,479-card catalog size (pokemontcg.io alone); a 64-bit average hash's real measured separation (§ above) is already thin among ~8-22 same-species candidates — at full-catalog scale, with visually near-identical border/layout templates across the whole set, it would be materially worse. Would need a trained embedding model (on-device CNN/ViT + precomputed embedding index) to work reliably at that scale — a much larger, separate project, not a hash-algorithm fix. |
| C. Do nothing, keep today's number-required gate | Rejected — this is the status quo that produces both reported failures every time Gemini can't read the number, which live-testing shows is common. |

### Consequences

- Fixes `computeHash()` for its *existing* role (ranking same-name candidates) regardless of
  whether the margin-gate (below) ships — a real, independent improvement.
- Introduces a new, uncalibrated constant (the margin threshold below high-confidence) — same
  class of decision as `GUIDE_FRAME_WIDTH_RATIO`/`SCAN_RETRY_DELAY_MS`: a first-guess value,
  explicitly re-tunable, not a blocker to shipping.
- Does **not** fix the Furfrou→Zorua misread by itself — that's the separate `candidates.size == 1`
  short-circuit, included as its own task below since it's a different code path, but real
  calibration data for it is still limited to n=1.
- Does not require any new remote data source, storage, or bundled dataset.

## Requirements

**P0**
- `PerceptualHasher.computeHash()` fixed so 256 sampled pixels map to 256 independent hash bits
  (or an equivalent redesign — e.g. resize to 8x8 to match a genuine 64-bit capacity) with no
  wraparound collision. Regression test using real/synthetic images asserting no false-identical
  hash between two distinguishably-different inputs.
- `SmartThresholdUseCase.evaluate()` (or a new function it delegates to) gains a second
  high-confidence path: when `parsedNumber` is null but the hasher's best-vs-second-best distance
  exceeds a margin threshold, mark high confidence off the hash alone. When the top two are within
  the margin, fall through to today's low-confidence list — this is exactly the case a bare
  top-pick would get wrong.
- `PerceptualHasher.findBestMatch()` (or its caller) exposes the margin (distance to the runner-up),
  not just the winning candidate — `evaluate()` needs this to gate correctly.

**P1**
- `candidates.size == 1` short-circuit in both `SmartThresholdUseCase.evaluate()` and
  `PerceptualHasher.findBestMatch()` removed or narrowed so a single-candidate result still gets
  visually checked against the captured photo before being trusted — closes the Zorua-class
  misread. Needs the same margin-style reasoning (compare against *something*, even with only 1
  candidate — e.g. an absolute distance ceiling, not just a relative margin, since there's no
  second candidate to compare against).
- Diagnostic logging (`ScannerMatch` tag, existing convention) extended to log the hash margin and
  which confidence path fired (number-match vs hash-margin vs neither), so a future "still wrong"
  report can distinguish the three cases instead of collapsing back to "something in here is
  wrong."

**P2**
- Re-evaluate whether `PerceptualHasher.findBestMatch()`'s per-candidate network image download
  (one HTTP GET per candidate, ~20-22 candidates typical) is fast enough now that it's doing more
  work (margin computation, not just a single pick) — not expected to change materially, worth
  confirming with real timing once built, not blocking.

## Task Breakdown (for `dev-team-pipeline`'s Coder stage, when authorized)

| # | Task | File(s) | Type | Size | Depends on |
|---|---|---|---|---|---|
| 1 | Fix `computeHash()`'s bit-mapping bug (resize target and/or hash width change) | `PerceptualHasher.kt` | CORE | S | — |
| 2 | Regression test: two distinguishably-different images must not hash identically; same image (re-encoded) should hash close | `PerceptualHasherTest.kt` (new or existing) | TEST | S | 1 |
| 3 | Expose a margin (best-vs-second-best distance) from `findBestMatch()`, not just the winning candidate | `PerceptualHasher.kt` | CORE | S | 1 |
| 4 | Add the margin-gated high-confidence path to `SmartThresholdUseCase.evaluate()` (or a sibling function), first-guess threshold constant, clearly commented as re-tunable | `SmartThresholdUseCase.kt`, `ScannerViewModel.kt` | CORE | M | 3 |
| 5 | Unit tests for the new confidence path: number-match still wins when present, hash-margin wins when number is null and margin is wide, falls through to low-confidence when margin is narrow | `SmartThresholdUseCaseTest.kt` | TEST | M | 4 |
| 6 | Extend `ScannerMatch` logging to show margin + which path fired | `ScannerViewModel.kt` | CORE | S | 4 |
| 7 (P1) | Remove/narrow the `candidates.size == 1` short-circuit in both `evaluate()` and `findBestMatch()`; add an absolute-distance ceiling for the no-second-candidate case | `PerceptualHasher.kt`, `SmartThresholdUseCase.kt` | CORE | M | 1, 3 |
| 8 (P1) | Unit tests for task 7 — single-candidate case now rejects a visually-dissimilar match | `PerceptualHasherTest.kt`, `SmartThresholdUseCaseTest.kt` | TEST | S | 7 |

**Phase rollout:** 1-2 (hash fix) first, independently shippable. 3-6 (margin-gated confidence)
next, the actual fix for the reported number-null symptom. 7-8 (single-candidate case) can run in
parallel with 3-6 — different code path, same underlying fixed hash from task 1.

## Open Questions (blocking `dev-team-pipeline` start)

- **Margin threshold value** — no calibration data yet beyond the one 16-card sample in this doc.
  First-guess, live-test, re-tune — same pattern as every other constant in this scanner's history.
  Needs Skyler's real device to generate genuine same-species multi-candidate cases to tune against.
- **Absolute-distance ceiling for the single-candidate case (task 7)** — same problem, worse: only
  one real data point exists (the Zorua misread) to calibrate against. May need to ship a
  conservative first guess and rely on live-testing to catch both false-accepts and false-rejects.
- Whether task 7's fix would have actually caught the real Zorua case — not verified, since that
  exact captured photo was never saved (this session's separate temp diagnostic capture-to-disk
  addition, still uncommitted in the working tree, exists for exactly this kind of follow-up).

## Timeline

No hard deadline. **Do not start `dev-team-pipeline` until Skyler explicitly says go.**
