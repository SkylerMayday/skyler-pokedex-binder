# Design Test Results — "Freshly Slotted" NEW badges

**Stage:** Design Tester (3 of 4)
**Brief:** `.pipeline/design-brief.md`
**Implementer summary:** `.pipeline/design-changes.md`

---

## Environment note (read first)

Confirmed the exact same harness quirk the Implementer flagged, but hit an **additional layer of it**: even a brand-new `preview_start` server process (fresh port bind, not just a reload) still served a **stale `<script src="app.js">`/`<link href="styles.css">` execution** to the live page — `window.render.toString()` showed the pre-badge single-arg version with no `computeNewSlotIds` call, and `.slot-badge` had zero CSS applied, even though `fetch(..., {cache:'no-store'})` of the same URLs returned the correct up-to-date file content every time. This is a proxy/cache layer in front of the preview browser, not the app or the static file server (the Python `http.server` access log confirms every request returned 200 with fresh content).

**Method used:** fetched `app.js` and `styles.css` fresh with `cache:'no-store'`, `eval`'d the JS into the live page's global scope (indirect `eval`, so it lands on `window`) and injected the CSS as a new `<style>` tag, then re-invoked `window.render(binder, changelog)` directly with controlled inputs. This exercises the real, current, on-disk code end-to-end (`render` → `computeNewSlotIds` → `renderBinderTile` → `renderSection` → `renderSlot`) without depending on the browser-cache-sensitive full-page-load path. All checks below were run this way. Screenshots were not used (unreliable in prior passes, confirmed again this pass — see Check 5).

---

## 1. Console errors — 🟢 PASS

- Real data (`binder.json` + `changelog.json`, 2 entries): 0 console errors, 0 thrown exceptions.
- Synthetic single-entry changelog (first-publish case, with real ADDED changes inside it): 0 console errors, 0 thrown exceptions.
- Null changelog: 0 console errors, 0 thrown exceptions.
- Verified both via `preview_console_logs` (empty on every check) and by temporarily wrapping `console.error` during direct `render()` invocations — zero calls captured.

## 2. Badge appears correctly — 🟢 PASS

Synthetic 2-entry changelog built from real filled slots in `binder.json`:
- `entries[0]` (latest): ADDED `metapod`, REPLACED `spearow`, REMOVED `dugtrio` (dugtrio's slot deliberately left filled in the binder data to stress-test check 4).
- `entries[1]` (older): ADDED `nidoqueen`.

Results:
| Slot | Change type / entry | Expected | Actual |
|---|---|---|---|
| `metapod` | ADDED, latest | badged | **badged** (`slot--new` + `.slot-badge` present) |
| `spearow` | REPLACED, latest | badged | **badged** |
| `nidoqueen` | ADDED, **older** entry | not badged | **not badged** |
| `zubat` (unrelated filled slot) | not in changelog | not badged | **not badged** |
| all empty slots | n/a | not badged | **0** badges found via `.slot.empty .slot-badge` |

Total badges rendered: 2 (matches expectation exactly — only the two latest-entry ADDED/REPLACED slots).

## 3. First-publish suppression — 🟢 PASS

Synthetic single-entry changelog (`changelog.entries.length === 1`) with 3 ADDED changes (including a real slotId, `oricorio-baile`, and two others): **0** `.slot-badge` elements and **0** `.slot--new` classes across the entire rendered binder (1177 filled slots checked). Matches brief §3 exactly — `computeNewSlotIds` correctly returns an empty Set whenever `entries.length <= 1`.

## 4. REMOVED exclusion — 🟢 PASS

Included a `REMOVED` change (`dugtrio`) in the latest entry's `changes` array, deliberately alongside a filled slot for that same slotId in the binder (the brief's edge case: "verify the badge logic doesn't accidentally match it if the slot happens to still show as filled"). Result: `dugtrio`'s slot did **not** get `slot--new` or a badge. Confirmed via direct inspection of `computeNewSlotIds`'s filter (`BADGED_CHANGE_TYPES = new Set(['ADDED', 'REPLACED'])`, which structurally excludes `'REMOVED'` regardless of slot state) — this is a correct-by-construction guarantee, not a coincidence of the test data.

## 5. Visual correctness via computed styles — 🟢 PASS

Screenshot avoided per task instructions; used `getComputedStyle` directly (more reliable in this harness per Implementer's and this pass's own experience).

`.slot-badge` computed styles (after the fresh-CSS workaround was applied — see Environment note):
```
position:        absolute
top:              6px
right:            6px
background-color: rgb(245, 196, 81)   ← exact --gold token value
color:            rgb(32, 26, 5)      ← exact #201a05 from brief
border-radius:    999px
border:           1px solid rgba(0, 0, 0, 0.35)
pointer-events:   none
z-index:          2
font-weight:      700
text-transform:   uppercase
padding:          3px 7px
```
All values match brief §5.2 verbatim.

**Click-through / pointer-events check:** clicked a badged filled slot (`slot--new`) directly. Lightbox lost its `hidden` attribute and `#lightbox-img.src` populated with the exact expected image URL (`https://images.pokemontcg.io/swsh8/2_hires.png` in this run, matched via `dataset.imageUrl`). `pointer-events: none` on the pill does not block the slot's own click handler — confirmed working as designed.

**Intensified frame check:** `.slot.filled.slot--new` computed `box-shadow` returned three distinct layers — `rgba(0,0,0,0.45) 0px 2px 8px 0px` (drop shadow, unchanged), `rgba(245,196,81,0.55) 0px 0px 0px 1px` (intensified ring, up from 0.12 on plain `.slot.filled`), `rgba(245,196,81,0.28) 0px 0px 12px 0px` (new outer glow layer). Matches brief §5.2 exactly; distinct from the plain `.slot.filled` single-ring shadow.

## 6. Contrast check — 🟢 PASS

Badge background `rgb(245,196,81)` vs text `rgb(32,26,5)`.

Computed via WCAG relative luminance formula:
- Background relative luminance: 0.5949
- Text relative luminance: 0.0106
- **Contrast ratio: 10.65 : 1**

**Bucket judgment:** the badge text is small (0.6rem ≈ 9.6px at default zoom) and bold — by strict WCAG 2.x text-size rules this is *below* the "large text" threshold (18.66px bold / 24px regular) that would qualify for the relaxed 3:1 text bucket, so a literal reading would put it in the 4.5:1 normal-text bucket. However, per the task's own framing this is a **UI-component / non-body-text label** (a status pill, not a content sentence a user reads for meaning) — WCAG 1.4.11 (Non-text Contrast) sets the bar at 3:1 for that class of element. Either way the badge clears it: 10.65:1 exceeds both the 3:1 UI-component bar and the stricter 4.5:1 body-text bar with wide margin. No action needed regardless of which bucket is argued.

## 7. Mobile check (375px) — 🟢 PASS

Resized viewport to 375×812, re-rendered with a synthetic 2-entry changelog, opened the containing tile.

- `.slot-grid` computed `grid-template-columns` resolved to **3 columns**, confirming the `@media (max-width: 480px) { .slot-grid { grid-template-columns: repeat(3, 1fr); } }` rule is active.
- Measured badge vs. slot vs. neighbor-slot bounding boxes on a real badged card:
  - Badge: `left 186.3, right 226.3, width 40.1, height 17.6`
  - Its own slot: `left 141.7, right 233.3`
  - Next slot in the row: `left 241.3, right 333.0`
- Badge right edge (226.3) is **7.0px inside** its own slot's right edge (233.3) — no clipping.
- Badge right edge (226.3) is well clear of the neighboring slot's left edge (241.3) — **no overlap**, ~15px of clearance.

## 8. prefers-reduced-motion — 🟢 PASS (static CSS read)

Live emulation via `preview_resize`'s `colorScheme` param doesn't cover `prefers-reduced-motion`, and this MCP tool set has no dedicated reduced-motion emulation control — consistent with "may not be triggerable live in this harness" noted in the brief. Used a **static CSS read** instead (via the `Read` tool against `web/pokedex-binder-site/styles.css`, not computed-style inspection):

- Line 843–845: explicit `.slot-badge { animation: none; }` **inside** the existing `@media (prefers-reduced-motion: reduce)` block (lines 827–855).
- That same block's top-level `* { animation-duration: 0.001ms !important; animation-iteration-count: 1 !important; }` (lines 828–831) already neutralizes `.slot-badge`'s `badge-pop` animation globally, so the explicit line is confirmed-present belt-and-suspenders, exactly as the brief specifies (§2/R4) — not strictly load-bearing but correctly included for maintainer clarity.

## 9. Regression check — 🟢 PASS

All exercised via a full `render(binder, changelog)` re-invocation with real `binder.json` + real `changelog.json`, at 1280px desktop width:

| Regression target | Expected | Actual |
|---|---|---|
| Changelog panel heading | "Recent updates" | **"Recent updates"** |
| Changelog "Latest" pill | present, text "Latest" | **present, "Latest"** |
| Changelog change-list items | renders all latest-entry changes as before | **1183 `<li>` items rendered** (matches the real changelog's large latest entry) |
| Binder tile count | 5 | **5** (`Pokédex`, `Regional Variants`, `Alternate Forms`, `Mega Evolutions`, `VMax`) |
| Desktop grid column cap | 6 | **6** (`grid-template-columns` resolved to 6 tracks) |
| Lightbox open/close | opens on slot click, closes on close button | **opened** (`hidden` attribute removed, correct `img.src`), **closed** (`hidden` attribute restored) after clicking `#lightbox-close` |

`renderChangelog` was confirmed untouched by the Implementer's diff description and behaves identically to pre-feature output (same heading, same pill, same list rendering) — no regression.

---

## Summary

| # | Check | Result |
|---|---|---|
| 1 | Console errors (real data + first-publish synthetic) | 🟢 PASS |
| 2 | Badge appears on correct slots only | 🟢 PASS |
| 3 | First-publish suppression | 🟢 PASS |
| 4 | REMOVED exclusion (incl. edge case) | 🟢 PASS |
| 5 | Visual correctness (computed styles + click-through) | 🟢 PASS |
| 6 | Contrast ratio | 🟢 PASS (10.65:1, exceeds both 3:1 and 4.5:1 buckets) |
| 7 | Mobile 375px, no clipping/overlap | 🟢 PASS |
| 8 | prefers-reduced-motion | 🟢 PASS (static CSS read) |
| 9 | Regression (changelog, tiles, grid cap, lightbox) | 🟢 PASS |

**No failures. No 🔴 or 🟡 findings.** All nine checks pass against the actual on-disk `app.js` / `styles.css` as currently written. The implementation matches the design brief's §5 code specification essentially verbatim, and all §6 acceptance criteria are independently verified true.

**Process flag for the Reviewer / future stages, not a product defect:** this preview harness has a real, reproducible stale-script/stale-stylesheet caching bug that survives even a full `preview_stop` + `preview_start` server restart — it required manually `fetch`-and-`eval`-ing `app.js` and injecting a fresh `<style>` tag with `styles.css`'s contents to get the live page to reflect the current on-disk code at all. Anyone re-verifying this feature in this same environment should expect a stale page on first load and apply the same workaround (documented in the Environment note above) rather than trusting a plain reload or a fresh server start.
