# Design Brief — Binder Spines on a Shelf (Re-skin Pass)

**Stage:** Design Consultant (1 of 4)
**Target:** `web/pokedex-binder-site/` — `index.html`, `styles.css`, `app.js`
**Scope:** Re-skin the CLOSED-state visual appearance of the 5 binder tiles into physical binder spines standing on a wooden shelf. May add an open/close transition. Nothing else.
**Next stage:** Implementer builds from §4 "What Changes in the Files" verbatim.

This brief re-skins only. The dark "Display Case" aesthetic, the 5-binder click-to-open structure, the section-bucketing logic, the 6-column grid cap, the lightbox, and every `app.js` data/parse/math function shipped in the prior pass (`design-brief-v1.md` / `design-changes-v1.md`) stay exactly as they are. Read the two v1 files for that context; this brief assumes it.

---

## 1. Current State Summary (what's being re-skinned)

Today each binder is rendered by `renderBinderTile()` (app.js) as a **full-width horizontal panel**, stacked vertically one per row:

- `.binder-tile` — a `--surface` well, `--radius` corners, 1px `--border`, `margin-bottom: --s6`, `overflow:hidden`.
- Inside it, `.binder-tile-toggle` — a real `<button>`, flex row, `space-between`: on the left `.binder-tile-name` (e.g. "Pokédex"), on the right `.binder-tile-stats` = `.binder-tile-count` ("412/1025") + `.binder-tile-chevron` (a CSS-border chevron that rotates on `.open`).
- Below the button, `.binder-tile-body` (`hidden` when closed) holds the already-rendered `renderSection()` output for that bucket. Toggling flips `.open` + the `hidden` attribute — no re-render, no refetch.

So the closed state is **5 flat clickable bars, name-left / count+chevron-right, stacked vertically.** That flat-bar treatment is what this pass replaces with a shelf-of-spines look. The tiles are functionally solid but visually generic — they read like an accordion in a settings screen, not like a collection of binders you'd pull off a shelf.

**Data available per tile for the label** (already computed, do not change): `displayBinder.name` (string) and `displayBinderCompletion()` → `{ filled, total }`. The Pokédex tile also nests 9 generation sub-sections in its body; the other 4 (and any "Other" fallback) hold a single section each. There is no rarity/color field in the schema — spine colors must be assigned client-side by tile id, not read from data.

---

## 2. Proposed Direction — One Coherent Package

**Concept: "The Collector's Shelf."** The 5 binders stand upright, side by side, as closed **binder spines** slotted into a single **warm wood-toned shelf**, sitting inside the existing dark Display Case. Think the top shelf of a display cabinet: a wooden plank with a front lip, and five leather-look ring-binder spines leaning on it, each with an embossed label showing its title and completion count. Click a spine → it visibly "pulls out" / lifts, and that binder's contents open in a panel below the whole shelf. It's the same click-to-open accordion, dressed as pulling a binder off a shelf.

This is a pure re-skin of the *closed tile chrome* + a nicer open transition. The body content (nested sections / grids) is untouched.

### 2.1 The shelf (container around the 5 spines)

One horizontal wooden shelf that the spines stand on. Built entirely from CSS:

- **Wood surface + grain:** a warm wood gradient as the base (`--wood-1` → `--wood-2`, a mid-to-dark oak/walnut band), with a **repeating-linear-gradient grain overlay** at low opacity (thin, slightly irregular vertical-to-diagonal streaks) layered on top via a second background. No image files. Grain is subtle (~6–10% contrast) so it reads as texture, not stripes.
- **Front lip / depth:** the shelf has a slightly lighter top edge (highlight) and a darker `--wood-3` front lip along the bottom (a 10–14px band with an inset shadow above it) so the plank reads as having thickness. A soft drop-shadow beneath the shelf grounds it on the dark bg.
- **Shelf back shadow:** a subtle inner shadow at the top-back of the shelf where the spines meet the wood, so spines look like they're resting *in front of* a back panel, not floating.

The shelf is a new wrapper. To avoid touching the toggle DOM (each `.binder-tile` owns its own body), the shelf is created **structurally in the render output** (see §4 app.js) as a `.shelf` element that contains the 5 spine buttons, with each tile's `.binder-tile-body` living full-width *below* the shelf. Concretely: use a CSS grid on `#binders` so spines flow in a row and bodies span full width underneath (spec in §4).

### 2.2 The spine (closed-state binder tile)

Each spine is the existing `.binder-tile-toggle` `<button>`, re-skinned from a horizontal bar into a **vertical binder spine**:

- **Shape:** a tall, narrow rounded rectangle standing on the shelf. Target ~96–120px wide × ~200–240px tall on desktop; they sit in a row on the plank. Slight `border-radius` on the top corners only (like a binder's rounded top edge), squarer at the base where it meets the wood.
- **Leather-look body:** a vertical linear-gradient giving a soft cylindrical sheen (darker at both edges, lighter down the center third) so each spine reads as a rounded 3D surface catching the display-case light. Each of the 5 gets a distinct **muted, premium leather color** (deep oxblood, forest green, navy, tobacco brown, slate — a restrained collector palette, NOT primary/cartoon colors), assigned by tile id in CSS. Colors are desaturated and dark enough that the light label text stays AA-legible on them.
- **Edge / binding detail:** a thin darker border all around + a subtle inner top highlight (`inset` box-shadow) for an embossed, raised look. Optional: two faint horizontal "ring-binder band" lines near top and bottom (thin low-opacity rules) to sell the binder read — decorative, cuttable.
- **Label plate:** a lighter inset **label area** in the vertical center of the spine (a slightly recessed panel: light-cream or bone gradient with a 1px inset border + tiny shadow, like a stuck-on label), holding:
  - the **binder name** (`.binder-tile-name`), and
  - the **completion count** (`.binder-tile-count`, "412/1025").
  - Because the spine is narrow and tall, the **name reads horizontally on the label plate** (label is wide enough), NOT rotated vertically — rotated text is a legibility/mobile risk (see RISK 2 for the vertical-text option). Keep it horizontal on a centered label plate.
- **Chevron → affordance:** the rotating chevron is replaced by the spine's own affordance (hover lift + "pull" cue). Keep a small open-state indicator (see §2.3). Drop the standalone chevron glyph or repurpose it as a tiny down-caret beneath the label meaning "open."

### 2.3 What changes when a binder is "open"

Interaction logic is **unchanged** (`attachBinderTileHandlers` still toggles `.open` + `hidden`). Only the visual of open changes:

- **On the spine:** the opened spine gets a "pulled out" look — lifts up off the shelf a few px (`translateY(-6px)`), gains a stronger drop-shadow and a brighter edge highlight, so it's obvious *which* binder is open. Its label plate can gain a subtle gold ring (`--gold`, already in palette) to tie into the Display Case accent.
- **The contents:** the existing `.binder-tile-body` opens as a panel **below the shelf**, full width — same nested sections/grids as today, just visually presented as "the open binder's pages" (give the body a subtle top connector or a thin gold rule so it reads as belonging to the lifted spine). The body is NOT folded/animated open as a 3D book; that's over-engineering and a `prefers-reduced-motion` headache. It simply reveals below (with a short height/opacity ease when motion is allowed), exactly like today.
- **Multi-open stays** (per v1 implementer choice): more than one binder can be pulled out at once; each opens its own body panel below the shelf in DOM order. This is fine — it reads as "you pulled several binders out to flip through."

### 2.4 Arrangement — real shelf row vs. grid

**Proposed: a real shelf row.** 5 spines standing side-by-side on one plank is the whole point of the metaphor and is what sells it. Layout:

- **Desktop / tablet (≥ ~560px):** all 5 spines in one horizontal row on a single shelf, evenly spaced, resting on the wood. Bodies open full-width beneath the shelf.
- **Narrow / mobile (< ~560px, incl. 375px):** 5 full-height spines side-by-side would each get too thin to fit a horizontal label legibly. **Fall back to a 2-row shelf** (spines wrap onto a second plank) OR shrink spine height and stack the label text — the concrete, safe choice specced in §4 is: **wrap the spines onto multiple shelf rows** (flex-wrap), each wrapped row still sitting on wood, so at 375px you get e.g. 2 rows of spines (3 + 2) rather than 5 unreadably-thin slivers. This keeps every label horizontal and AA-legible, keeps the shelf metaphor, and avoids vertical rotated text on mobile. (See RISK 3.)

This "shelf row that wraps" is the SAFE arrangement. A single non-wrapping row that shrinks spines to slivers on mobile is the thing we're explicitly avoiding.

### 2.5 Palette additions (wood + leather), WCAG-checked

Add these tokens; **do not remove or remap any existing token.** All existing Display Case tokens stay.

```
/* Wood shelf */
--wood-1:  #6b4a2f   (plank mid oak/walnut)
--wood-2:  #503722   (plank shade, gradient bottom)
--wood-3:  #3d2a19   (front lip / darkest)
--wood-hi: #8a6440   (top highlight edge)
--grain:   rgba(30, 18, 8, 0.5)  (grain streak color, used low-opacity in repeating gradient)

/* Leather spines (muted collector palette; label text is light --spine-label-text) */
--spine-oxblood: #6e2b2b
--spine-forest:  #2f5138
--spine-navy:    #26364f
--spine-tobacco: #5a3d24
--spine-slate:   #3a4048
--spine-other:   #4a3550   (6th "Other" fallback, muted plum)

/* Spine detailing */
--spine-edge:    rgba(0,0,0,0.45)     (dark binding border)
--spine-sheen:   rgba(255,255,255,0.10) (center highlight for cylindrical look)

/* Label plate */
--label-plate:   #ece3d0   (cream label background)
--label-plate-2: #d8ccb2   (label gradient shade)
--label-text:    #2b2419   (dark ink on the cream label — high contrast)
--label-border:  rgba(0,0,0,0.25)
```

**Contrast:** the binder name + count render as `--label-text` (#2b2419) on the cream `--label-plate` (#ece3d0) → ~11:1, passes AA/AAA comfortably. Do NOT put the label text directly on the leather (some leather tones are marginal); it always sits on the cream plate. The gold open-ring is decorative (not text), so its contrast is not a text-AA concern.

### 2.6 8pt grid / craft

- Spine width, height, gaps, label padding all on the 8pt grid (existing `--s1..--s8`). E.g. spine width 96–112 (multiples of 8), shelf padding `--s4`, spine gap `--s3`, label plate padding `--s2`.
- Label name: keep `.binder-tile-name` at ~1rem 600 weight; count `.binder-tile-count` at ~0.8rem, `tabular-nums` (already set) so digits don't jitter.
- Radius: spine top corners `--radius`, base corners `--radius-sm` or square; label plate `--radius-sm`.

### 2.7 Motion (all CSS, respect `prefers-reduced-motion`)

- Hover (desktop, `@media (hover:hover)`): spine lifts `translateY(-4px)` + shadow bloom — "about to pull it out."
- Open: spine `translateY(-6px)` + stronger shadow + gold label ring; body reveals with a short ease (reuse existing reveal — no new fold animation).
- All of it must be neutralized under the existing `@media (prefers-reduced-motion: reduce)` block (extend it to cover the new transforms/shadows so final open/closed states hold with zero motion). The shelf/spine/wood are all static gradients — no ambient animation.

---

## 3. SAFE vs RISK Breakdown

### SAFE (low risk, high metaphor payoff)
- **Wood shelf via CSS gradient + repeating-gradient grain.** Standard technique, zero assets, cheap to render. This is the conservative way to get a wood surface without image files.
- **Leather-look spines via vertical gradient + inset highlight + dark border.** Standard "3D pill/spine" CSS. Muted collector palette (not cartoon colors) keeps it premium and on-brand with Display Case.
- **Cream label plate holding horizontal name + count.** Keeps text AA-legible (~11:1) and avoids the mobile pitfalls of rotated text. This is the safe label treatment.
- **Shelf row that flex-wraps to multiple planks on narrow viewports.** Preserves the metaphor AND legibility at 375px — no unreadable slivers, every label horizontal.
- **Reusing the existing open/close interaction + reveal.** No new JS logic, no fold animation, `prefers-reduced-motion` stays trivially satisfiable.

### RISK (deliberate departures, with mitigation)
- **RISK 1 — Restructuring `#binders` layout so spines sit in a row with bodies full-width below.** *Departure:* the current DOM is 5 independent stacked `.binder-tile` blocks, each `body` nested inside its tile. To make spines share one shelf row while their open bodies span full width beneath, the Implementer must change the *container layout* (and lightly the render wrapper) without changing the toggle logic or the tile→body ownership. *Why worth it:* a real side-by-side shelf row is the core of the ask. *Mitigation / concrete safe approach:* keep each `.binder-tile` exactly as it is (button + its own body inside), but render all tiles inside a new `.shelf-wrap` and use **CSS grid on the tile-set with the body forced to a full-row span** — i.e. spines auto-flow into columns, and `.binder-tile.open .binder-tile-body` is positioned to break out to full width below via `grid-column: 1 / -1`. If a clean grid break-out proves fiddly, the acceptable fallback is: spines in a flex-wrap row inside `.shelf`, and a **single shared body region** is NOT introduced (that would touch logic) — instead each tile keeps its own body but bodies are visually stacked under the shelf using `order`/DOM flow. **The Implementer must pick whichever keeps `attachBinderTileHandlers` and the tile/body ownership untouched; document the chosen mechanism in `design-changes.md`.** This is a layout/markup-wrapper risk, not a logic risk.
- **RISK 2 — Vertical (rotated) spine text.** *Departure:* real binder spines run their title vertically. *Decision: DO NOT rotate the primary label by default* — rotated text hurts scannability and is fragile at 375px. The horizontal cream label plate is the shipped choice. *Optional stretch (Reviewer go/no-go only):* a faint **decorative** vertical embossed title on the leather *behind/above* the horizontal label plate, low-opacity, purely ornamental and never the sole carrier of the name. Flagged for Reviewer; default is to skip it.
- **RISK 3 — Spines too thin / labels clipping on mobile.** *Departure risk from the row metaphor.* *Mitigation:* the flex-wrap-to-multiple-rows behavior (§2.4) plus a min spine width; verify at exactly 375px that names don't truncate mid-word and counts stay on one line. If a name is long ("Regional Variants", "Mega Evolutions"), the label plate wraps to 2 lines rather than shrinking the font below ~0.8rem. Tester must check every one of the 5 real names at 375px.
- **RISK 4 — Skeleton loader now mismatches the shelf.** The current `#skeleton-wrap` shows 5 horizontal `.skeleton-tile` bars (68px tall). Once tiles become upright spines, the skeleton should ideally show 5 upright spine placeholders on a (plain, un-textured) shelf so the loading→loaded transition doesn't jump shape. *Mitigation:* restyle `.skeleton-tile` to the spine footprint (tall/narrow, in a row) — presentational only, no JS change to skeleton injection/removal. Low risk but call it out so the loader isn't left looking like the old layout.

*(No zero-risk blandness: the wood shelf + leather spines + pull-out-on-open are the deliberate bets that turn a flat accordion into a collector's shelf. The restraint is in the muted palette and keeping labels horizontal/legible, not in avoiding the metaphor.)*

---

## 4. What Changes in the Files (Implementer Spec)

**Guardrail (unchanged from v1, restated):** In `app.js` you may ONLY touch presentational markup wrappers/classes emitted by the render functions, plus a new shelf wrapper element. **Strictly OFF-LIMITS — do not modify in any way:** `loadJson`, `overallCompletion`, `formatPublishedAt`, `barHtml` (incl. its pct math), `escapeHtml`, `changeIcon`, `renderChangeList`, `bucketSectionsIntoDisplayBinders`, `displayBinderCompletion`, the section/generation name constants, the toggle logic in `attachBinderTileHandlers`, the lightbox functions, `triggerBarFillAnimations`, `hideSkeleton`, and `init`. Do NOT change what data is read, how sections are bucketed, iteration order, the 6-col grid math, or the lightbox wiring. This pass changes the *closed-tile chrome markup + CSS* and the `#binders` layout wrapper only.

### `index.html`
1. **Optional:** the existing `#skeleton-wrap` (5 `.skeleton-tile` divs) can stay as-is markup-wise; only its CSS changes (§styles). If the Implementer wants the skeleton spines to sit in a row, they may add a `.skeleton-shelf` class to `#skeleton-wrap` — markup-only, no JS touch (skeleton is still removed by the untouched `hideSkeleton()`).
2. No other HTML changes required. (The header, changelog, lightbox, footer are untouched.)

### `app.js` (presentational wrapper only)
1. **`render()` — wrap the tile set in a shelf container.** Currently: `main.innerHTML = displayBinders.map(renderBinderTile).join('')`. Change ONLY this to wrap the joined tiles in a shelf structure, e.g.:
   `main.innerHTML = '<div class="shelf-wrap"><div class="shelf">' + displayBinders.map(renderBinderTile).join('') + '</div></div>';`
   — OR whichever wrapper structure the chosen CSS layout (RISK 1) needs. The set of tiles, their order, and `renderBinderTile` output are otherwise unchanged. `attachBinderTileHandlers(main)` and `attachLightboxHandlers(main)` still receive `main` and still work (they query descendants). **Verify the new wrappers don't break those two querySelectorAll calls** (they won't — they select `.binder-tile-toggle` / `.slot.filled`, which are still descendants of `main`).
2. **`renderBinderTile()` — re-skin markup only.** You may:
   - assign a per-tile spine color class, e.g. add `class="binder-tile spine spine--${index or id}"` (derive the color class from the tile's position or `displayBinder.id`, both already available — no new data). Map the 5 known ids to the 5 leather colors; `display-other` → `--spine-other`.
   - restructure the *inner* markup of the button (`.binder-tile-toggle`) so name + count sit on a `.binder-label-plate` wrapper suitable for the spine label (e.g. wrap `.binder-tile-name` and `.binder-tile-count` in `<span class="binder-label-plate">…</span>`). The chevron span may be kept (repurposed as a down-caret) or removed.
   - **Must preserve:** the `<button class="binder-tile-toggle" aria-expanded aria-controls>` element and its attributes, the `.binder-tile-body` element with its `id="binder-body-${id}"` and `hidden` toggling, the `data-binder-id`, and the `.open` class hook. `attachBinderTileHandlers` depends on `.binder-tile-toggle`, `.binder-tile`, `.binder-tile-body`, `data-binder-id`, and `aria-expanded` — keep all of them. Keep `displayBinderCompletion()`'s `{filled,total}` output rendered as the count text.
   - Do NOT change `renderSection`/`renderSlot` output — the body content stays identical.
3. Nothing else in `app.js` changes.

### `styles.css` (the bulk of the work)
1. **Add the wood + leather + label tokens** from §2.5 to `:root`. Do not remove/remap existing tokens.
2. **`#binders` / `.shelf-wrap` / `.shelf` layout (RISK 1):**
   - `.shelf` holds the spines in a row (flex row with `flex-wrap: wrap` so it degrades to multiple plank-rows on narrow widths, per §2.4), sitting on the wood background (wood gradient + repeating-linear-gradient grain overlay + front-lip via border-bottom/box-shadow + grounding drop-shadow).
   - The open **bodies must render full-width below the shelf.** Concrete approach: since each `.binder-tile` contains both its spine button and its body, set `.binder-tile` to `display: contents` (so the spine button participates in the `.shelf` flex row and the body is a sibling in normal flow below) — OR use the grid break-out described in RISK 1. Implementer picks the one that keeps DOM/logic intact; whichever is chosen, the closed state shows a shelf of spines and the open state drops a full-width panel beneath. **Document the mechanism in `design-changes.md`.**
   - Ensure the shelf itself has the wood look only behind the spines (the top band), not behind the opened body panels.
3. **`.spine` (re-skinned `.binder-tile-toggle`):** vertical binder spine — fixed-ish width (~96–112px, min-width so it doesn't collapse), tall (~200–240px desktop), leather vertical gradient using the per-tile `--spine-*` color mixed with `--spine-sheen` center highlight, `--spine-edge` border, top-corner `--radius`, inset top highlight for emboss, optional 2 faint ring-band rules. `cursor: pointer`. Keep `:focus-visible` gold outline (currently on `.binder-tile-toggle:focus-visible` — preserve it).
4. **`.binder-label-plate`:** centered recessed cream label (`--label-plate`→`--label-plate-2` gradient, `--label-border` inset, `--radius-sm`, small inset shadow) holding the name (`--label-text`, ~1rem 600) and count (`--label-text` or a slightly muted ink, ~0.8rem tabular-nums). Name wraps to 2 lines rather than shrinking below ~0.8rem. Never render label text directly on leather.
5. **Open state:** `.binder-tile.open .spine` (or `.binder-tile.open .binder-tile-toggle`) → `translateY(-6px)`, stronger shadow, brighter edge, gold ring on `.binder-label-plate`. `.binder-tile.open .binder-tile-body` → full-width panel below the shelf with a subtle top gold rule/connector; reuse existing reveal (no new fold animation).
6. **Hover:** `@media (hover:hover)` spine `translateY(-4px)` + shadow bloom. Keep touch clean (no stuck hover).
7. **Skeleton (RISK 4):** restyle `.skeleton-tile` to the spine footprint (tall/narrow) and lay `#skeleton-wrap` out as a row so the loading state matches the shelf shape. Keep the existing shimmer keyframes. No JS change.
8. **Chevron:** if kept, restyle/reposition as a small down-caret under the label meaning "open"; if removed from markup, drop its CSS. Its rotate-on-open rule can stay or go with it.
9. **Mobile `@media (max-width:480px)` (and verify at 375px):** spines wrap to multiple plank-rows (flex-wrap already handles this; ensure spine min-width + shelf padding keep 2–3 per row legibly); label text stays horizontal and AA-legible; no horizontal page scroll. Keep the existing 3-col slot-grid mobile rule and the header mobile stacking untouched.
10. **`@media (prefers-reduced-motion: reduce)`:** extend the existing block so the new spine hover/open transforms + shadows are neutralized (final open/closed states hold, zero motion). Wood/leather are static — nothing else to disable.

### Off-limits recap for the Implementer
Do not touch: any `app.js` function listed in the guardrail; the bucketing/section/slot render output; the lightbox; the 6-col grid math; the header/changelog/footer markup; existing color tokens. Only the shelf/spine/label chrome and the `#binders` layout wrapper change.

---

## 5. Acceptance Checks (for Tester + Reviewer)

- Closed state renders as **5 binder spines standing on a wood-toned shelf**, each with a legible horizontal cream label showing name + count. (6th "Other" spine only if data produces it.)
- All 5 real binder names ("Pokédex", "Regional Variants", "Alternate Forms", "Mega Evolutions", "VMax") fit their label plate at **375px** without mid-word truncation or count wrapping awkwardly; long names wrap to 2 lines, font never drops below ~0.8rem.
- No horizontal page scroll at 375px; spines wrap to multiple plank-rows rather than becoming unreadable slivers.
- Label text (`--label-text` on `--label-plate`) meets AA (verified ~11:1); Tester spot-checks computed contrast.
- **Interaction unchanged:** each spine is still a keyboard-operable `<button>` (Tab, Enter/Space toggles), still has a visible gold focus state, `aria-expanded` still flips, the body still reveals/hides via `.open` + `hidden`. Multi-open still works. Confirmed via `app.js` diff that `attachBinderTileHandlers` and all guardrail functions are byte-unchanged.
- Open state visibly distinguishes the pulled-out spine (lift + shadow + gold label ring) and drops its contents in a full-width panel below the shelf; nested sections/grids inside are identical to before.
- **6-col grid cap, lightbox, changelog, header hero stat, skeleton removal, `#latest` scroll** all still work exactly as in v1 (nothing in those paths was touched).
- `prefers-reduced-motion: reduce` disables all spine hover/open motion; final states intact.
- Works over `file://` with zero build step, zero new dependencies, zero image files (wood grain + leather + label are all CSS gradients/box-shadows). Verify against `sample-binder.json` fallback path.
- **Reviewer go/no-go flags:** RISK 1 (the `#binders` layout mechanism chosen — is the shelf-row-with-full-width-bodies clean and unbroken?), RISK 2 (optional decorative vertical embossed title — default skipped; approve only if it adds without hurting legibility).
