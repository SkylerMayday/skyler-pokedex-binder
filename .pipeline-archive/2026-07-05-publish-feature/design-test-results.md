# Design Test Results — Tester Stage (3 of 4, re-skin pass)

Verifies `.pipeline/design-changes.md` ("The Collector's Shelf") against `.pipeline/design-brief.md`. Served via `preview_start` (`.claude/launch.json` → `python -m http.server 4173 --directory web/pokedex-binder-site`). `preview_screenshot` not attempted for verification purposes — matches the Implementer's note that this harness's compositor doesn't tick; all checks below are DOM/computed-style/network-log based, which is strictly stronger evidence for these particular checks anyway.

Overall verdict: **PASS**, zero blocking defects. One 🟡 doc-hygiene nit (stale code comment) and one 🟢 nitpick (untestable-in-harness item, verified by static read only, as pre-authorized by the brief).

---

## 1. Console errors/warnings on load

**PASS 🟢** — `preview_console_logs` (level: all) returned "No console logs." across multiple reloads, both on the sample-data fallback path (`binder.json`/`changelog.json` 404 → `sample-*.json` 200) and once real `binder.json`/`changelog.json` files were present (200 OK) later in the session. Zero errors, zero warnings, either path.

## 2. All 5 spines render with distinct colors + legible label

**PASS 🟢**

Computed via `preview_eval` reading each `.spine`'s class + `background-image` + dimensions:

| Tile id | Class | Width×Height |
|---|---|---|
| `display-pokedex` | `spine--oxblood` | 104×216px |
| `display-regional` | `spine--forest` | 104×216px |
| `display-alternate` | `spine--navy` | 104×216px |
| `display-mega` | `spine--tobacco` | 104×216px |
| `display-vmax` | `spine--slate` | 104×216px |

All 5 distinct color classes present, each resolving to a distinct `linear-gradient` (confirmed different `color(srgb …)` stops per class). Matches brief §4's id→color mapping exactly. No 6th "Other" spine rendered (current data has no unrecognized section names — correct per brief, "6th spine only if data produces it").

**Contrast (label text on label plate):**
- `--label-text` (`#2b2419`) on `--label-plate` (`#ece3d0`): **12.03:1**
- `--label-text` on `--label-plate-2` (`#d8ccb2`, the gradient's darker stop): **9.64:1**

Both well above the 4.5:1 AA floor for body text (and above 7:1 AAA). Computed `.binder-tile-name` color is `rgb(43,36,25)` = `#2b2419` exactly — confirms the rendered text actually uses the claimed token, not just the CSS source.

## 3. Toggle behavior unchanged

**PASS 🟢**

Dispatched a real `.click()` on a `.binder-tile-toggle`:
- Before: `aria-expanded="false"`, `.open` absent, body `hidden` present.
- After: `aria-expanded="true"`, `.open` present, body `hidden` removed.

Pokédex tile body, once opened, contains exactly 9 `section.binder-section` elements with `h2` text `Generation I` through `Generation IX`, in order — nesting is intact and untouched.

## 4. Responsive shelf wrapping

**PASS 🟢** — tested all 4 requested breakpoints:

| Viewport | scrollWidth vs clientWidth | H-scroll | Spine rows |
|---|---|---|---|
| 375px | 375 / 375 | No | **2** (rows at y=26392, y=26584 — distinct `getBoundingClientRect().top`) |
| 768px | 753 / 753 | No | 1 |
| 1024px | 1009 / 1009 | No | 1 |
| 1280px | 1265 / 1265 | No | 1 |

At 375px, checked all 5 real binder names individually for truncation (`scrollWidth <= clientWidth` per name) and count wrapping:

| Name | Lines | Truncated? |
|---|---|---|
| Pokédex | 1 | No |
| Regional Variants | 2 | No |
| Alternate Forms | 2 | No |
| Mega Evolutions | 2 | No |
| VMax | 1 | No |

All 5 counts (`1023/1025`, `56/58`, `72/117`, `18/96`, `13/33`) render on a single line (height ~18px, not wrapped). No mid-word truncation anywhere. Matches brief §RISK 3's exact requirement.

## 5. Regression check (things this pass should not have touched)

**PASS 🟢** on both spot-checked highest-risk items, plus the others:

- **6-col grid cap:** at 1280px, `grid-template-columns` resolves to 6 tracks (`repeat(auto-fill, minmax(max(90px, 16.6667% - 6.66667px), 1fr))` → 6 columns computed). At 375px, the existing mobile override correctly takes over: `repeat(3, 1fr)`. Untouched, works exactly as v1 shipped it.
- **Lightbox:** opened via a `.slot.filled` click → `#lightbox` unhidden, `img.src` set to the real card image URL, `body.style.overflow = "hidden"`. Verified all three close paths: `Escape` keydown, backdrop click (`e.target === lightbox`), and the explicit close button — all three correctly re-hide the lightbox, clear `img.src`, and restore `body.style.overflow = ""`. Empty slots confirmed inert (`tabindex` is `null` on `.slot.empty` — no accidental lightbox wiring).
- **Hero completion stat:** renders `100%` / `1023/1025 complete` from the live data's pokedex math (unchanged `overallCompletion`).
- **Changelog `#latest`:** navigating with `location.hash = '#latest'` then reloading scrolls `#latest` into view (`getBoundingClientRect().top` = 229.9, within viewport) — scroll-to-hash behavior intact.

## 6. `prefers-reduced-motion`

**PASS 🟢** (static-CSS verification, per brief's own allowance — "you may not be able to observe animation directly in this harness")

Read `styles.css` lines 778–802. The existing reduced-motion block was extended to explicitly zero `transition`/`transform` for `.spine`, `.spine:hover`, `.binder-tile.open .spine`, `.binder-label-plate`, `.binder-tile.open .binder-label-plate`, on top of the pre-existing universal `* { animation-duration: 0.001ms !important; transition-duration: 0.001ms !important; ... }` catch-all. This covers every new spine-lift/shadow/gold-ring transition introduced by this pass. No gaps found — every new motion-bearing rule introduced in this pass (`.spine` transition on line 375, `.spine:hover` on 419-425, `.binder-tile.open .spine` on 477-483, `.binder-label-plate` transition on 445, `.binder-tile.open .binder-label-plate` on 485-490) has a corresponding neutralization entry.

`window.matchMedia('(prefers-reduced-motion: reduce)').matches` returned `false` in this environment (no OS-level toggle available to the preview harness, and `preview_resize` only supports `colorScheme` emulation, not `prefers-reduced-motion`) — so the live cascade wasn't observed at runtime, only read statically. This is a harness limitation, not a defect; flagged as 🟢 nitpick below.

## 7. Guardrail check (`app.js` unchanged functions)

**PASS 🟢** — read the full current `app.js` and compared against `.pipeline/design-changes-v1.md`'s description of each function's behavior:

`loadJson`, `overallCompletion`, `formatPublishedAt`, `barHtml` (pct math: `Math.min(100, Math.round((filled/total)*100))`, unchanged), `escapeHtml`, `changeIcon`, `bucketSectionsIntoDisplayBinders` (section-bucketing function), and the lightbox functions (`openLightbox`/`closeLightbox`/`attachLightboxHandlers`/`initLightboxChrome`) are all present with identical logic to what v1 documented. Confirmed live behavior matches static read for `attachBinderTileHandlers` (toggle test, §3 above) and the lightbox functions (§5 above).

Only `renderBinderTile()` (re-skinned markup, spine color class + label plate wrapper added) and one line inside `render()` (shelf wrapper markup) changed — exactly as `design-changes.md` claims.

---

## Issues found

**🟡 Should-fix — stale code comment in `app.js` (lines 232-236)**

```js
// Design brief §4 / RISK 1: wrap tiles in a shelf container. Each .binder-tile
// keeps full ownership of its own toggle button + body (untouched); the shelf
// layout mechanism (styles.css) uses `.binder-tile { display: contents }` so the
// spine button participates in the `.shelf` flex row while the body falls to
// normal block flow beneath the whole shelf. No DOM/logic restructuring here.
```

This comment describes the brief's **primary** suggested mechanism (`display: contents`), but the Implementer's own `design-changes.md` and the actual shipped `styles.css` (lines 288-309) document and implement the **fallback** mechanism instead (`.binder-tile` as a normal flex item + `.binder-tile-body { flex-basis: 100% }`), because `display: contents` broke transform/box-shadow application in this environment. The `styles.css` comment block is accurate and detailed; the `app.js` comment was not updated to match and will mislead the next reader of `app.js` into thinking `display: contents` is in effect. Not a functional bug — purely a documentation drift between the two files — but should be corrected to avoid confusing the Reviewer or a future maintainer who only reads `app.js`.

**🟢 Nitpick — `prefers-reduced-motion` verified statically only, not via live media-query toggle**

`preview_resize` in this harness only supports `colorScheme` emulation, not `prefers-reduced-motion`. Confirmed the CSS rule exists and covers every new transition/transform (§6 above) by direct file read, which is sufficient given the brief's own acknowledgment that this may not be observable in-harness. Recommend a manual OS-level reduced-motion check before ship if higher confidence is wanted, but not blocking.

---

## Summary table

| # | Check | Result |
|---|---|---|
| 1 | Zero console errors/warnings | PASS |
| 2 | 5 distinct spine colors + legible label (≥4.5:1) | PASS (12.03:1 / 9.64:1) |
| 3 | Toggle behavior unchanged, Pokédex 9-section nesting | PASS |
| 4 | Responsive wrap: 375/768/1024/1280px, no h-scroll, no truncation | PASS |
| 5 | Regression: 6-col grid cap, lightbox, hero stat, `#latest` | PASS |
| 6 | `prefers-reduced-motion` neutralizes new motion | PASS (static read) |
| 7 | Guardrail functions unchanged | PASS |

**No blocking (🔴) issues found. Recommend proceeding to Reviewer stage**, with the one 🟡 stale-comment cleanup in `app.js` as a quick fix (does not require re-testing — comment-only change).
