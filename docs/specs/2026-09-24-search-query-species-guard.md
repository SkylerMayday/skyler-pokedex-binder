# Spec — Species-Agnostic Query Ordering + Query Visibility in `CardSearchRepository`

Status: draft, pending Skyler approval. **Do not start `dev-team-pipeline` on this until Skyler
explicitly says go** — planning only for now.

## Context

Live-tested tonight (2026-09-24) after bumping `GeminiCardScanner`'s retry budget (503 resilience,
unrelated, already shipped). Three real scans, `ScannerMatch` log:

| Scan | Gemini output | Result |
|---|---|---|
| Furfrou | `number=144 setTotal=195 dexNumber=676` | 20 candidates (broad name match), `NONE` |
| Castform | `number=null setTotal=null dexNumber=351` | 22 candidates (broad name match), `NONE` |
| Castform | `number=116 setTotal=172 dexNumber=351` | **1 candidate: `Munna #116`**, `NONE` (saved by luck) |

The third scan is the real finding. Gemini correctly read the species (`Castform`) and a dex number
(`351`), but the search returned a single wrong-species candidate (`Munna #116`) — a card that
happens to share the same printed number/total as whatever Gemini misread off the physical card.
`SmartThresholdUseCase`'s single-candidate distance ceiling (16) rejected it this time (hash
distance 23), so nothing was falsely assigned — but that's a property of this specific hash value,
not a structural guarantee.

### Root cause (traced in code, not guessed)

`CardSearchRepository.searchByParsedInfo()`'s `specificQueries` list, in order:

```
1. name + number + total
2. name + number
3. number + total            <- no name, no dex — species-agnostic
4. dex + number + total
5. dex + number
```

The loop returns on the **first non-empty result**, in list order. Query 3 requires neither a name
nor a dex match — it is the only query in the entire cascade capable of returning a completely
unrelated species. Tonight, queries 1-2 (both name-gated) evidently returned empty — Gemini's
`number`/`setTotal` combination doesn't match any real Castform print exactly — so the cascade fell
through to query 3, which is species-blind by construction, and matched `Munna #116` purely on the
number+total pair. Query 4 (`dex+number+total`) was never reached, even though `dexNumber=351` was
available and would have kept the search pinned to the correct species — it sits *after* the unsafe
query in the list, not before it.

### Secondary problem: this required deduction, not observation

Diagnosing the above required reading source and ruling out alternatives — the `ScannerMatch` log
line shows the parsed fields and the result count, never the actual query string that matched. A
future "wrong card again" report can't distinguish "which of the 8 possible queries fired" without
repeating this same manual trace.

## Decision

Reorder the specific-query cascade so no species-blind query can run while a species-pinning signal
(name or dex) is still available and untried, and log the exact query string that produced a
non-empty result so this stops needing deduction.

### Options considered

| Option | Assessment |
|---|---|
| **A. Move dex-based queries above the bare `number+total` query; log the winning query string** (chosen) | Minimal, contained change — reordering a `buildList {}` block plus one new log field. Dex number is a direct numeric field from Gemini's own JSON, not fuzzy text — pinning on it is as cheap as pinning on name and closes the exact gap tonight's scan hit. |
| B. Remove the bare `number+total` query entirely | Rejected for this spec — it's the only remaining signal when Gemini reads neither a usable name nor a dex number (e.g. a badly-cropped capture). Removing it outright trades tonight's false-single-candidate risk for a different regression (fewer real matches found at all) without live data on how often it's the only thing that hits. Worth revisiting with real data after option A ships, not blindly cut now. |
| C. Do nothing, rely on the hash ceiling as the only guard | Rejected — already demonstrated tonight to be luck-dependent (distance 23 happened to clear the ceiling of 16; a closer wrong-species distance would not). |

### Consequences

- Closes the specific failure mode observed tonight (species-blind query short-circuiting before a
  safer species-pinned query gets a chance) without touching the hash-confidence pipeline
  (`SmartThresholdUseCase`, `PerceptualHasher`) at all — orthogonal fix, different file. The bare
  `number+total` query still exists after this change, just demoted to last resort — it only fires
  once every species-pinning query (name- and dex-based) has already come up empty.
- Does not fix the underlying reason Gemini's `number`/`setTotal` didn't match a real Castform
  print in the first place (queries 1-2 returning empty) — that's a Gemini OCR-accuracy question,
  out of scope here.
- Query-string logging is diagnostic-only, matches the existing `ScannerMatch`/`ScannerFocus`
  logging convention already used throughout the scanner — no behavior change.

## Requirements

**P0**
- `CardSearchRepository.searchByParsedInfo()`'s `specificQueries` list reordered so both
  dex-inclusive queries (`dex+number+total`, `dex+number`) are tried before the bare
  `number+total` query. Name-inclusive queries (1-2) stay first, unchanged — name is still the
  strongest available signal when present.
- Unit test: given `name` that doesn't match any fixture card by the exact name+number(+total)
  queries, but a `dex+number(+total)` combination that does, the dex-based match wins over a
  same-number-different-species fixture that only the bare `number+total` query would have hit.
  Regression test for tonight's exact failure shape.

**P1**
- `ScannerMatch`'s existing `"search candidates=${candidates.size}"` log line extended to also
  show which query (by position/description, e.g. `"query=dex+number+total"`) produced the
  non-empty result — or `"query=none-matched"` if the cascade fell through to the broad-name/dex/
  number-alone tail. Exact field name/format is an implementation detail for the Coder stage.

**P2**
- Revisit whether the bare `number+total` query should exist at all, once live data shows how often
  it's the sole hit vs. a wrong-species hit — needs real scans after P0 ships, not decidable now.

## Task Breakdown (for `dev-team-pipeline`'s Coder stage, when authorized)

| # | Task | File(s) | Type | Size | Depends on |
|---|---|---|---|---|---|
| 1 | Reorder `specificQueries` — dex-based queries before bare `number+total` | `CardSearchRepository.kt` | CORE | S | — |
| 2 | Regression test: dex-pinned match wins over a same-number-different-species fixture | `CardSearchRepositoryTest.kt` | TEST | S | 1 |
| 3 (P1) | Expose/return which query matched from `searchByParsedInfo()` (or log it directly at the call site) | `CardSearchRepository.kt` | CORE | S | 1 |
| 4 (P1) | Extend `ScannerMatch` log line in `ScannerViewModel.kt` with the matched-query field | `ScannerViewModel.kt` | CORE | S | 3 |
| 5 (P1) | Unit/log-format test confirming the new field is present and correct for a name-matched vs. dex-matched vs. no-match case | `CardSearchRepositoryTest.kt` or `ScannerViewModelTest.kt` | TEST | S | 3, 4 |

**Phase rollout:** 1-2 (reorder + regression test) is the actual fix, independently shippable. 3-5
(query visibility) is pure diagnostics, no functional change — can ship separately or together.

## Open Questions (blocking `dev-team-pipeline` start)

- **Does reordering change behavior for any currently-working scan?** Not expected — the reorder
  only changes which query runs first when queries 1-2 (name-gated) are already empty; it doesn't
  touch cases where a name-gated query already succeeds today. Worth confirming with the existing
  `CardSearchRepositoryTest.kt` suite (if one exists) before shipping, not just the new regression
  test.
- **P2's removal question** — explicitly deferred, needs live data this session doesn't have yet.

## Timeline

No hard deadline. **Do not start `dev-team-pipeline` until Skyler explicitly says go.**
