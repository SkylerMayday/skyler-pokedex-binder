# Design Brief — Pokédex Binder Public Viewer

**Stage:** Design Consultant (1 of 4)
**Target:** `web/pokedex-binder-site/` — `index.html`, `styles.css`, `app.js`
**Scope:** Visual/UX polish pass only. No changes to data-fetching, JSON schema handling, or section/grouping logic.
**Next stage:** Implementer builds from the "What Changes in the Files" section verbatim.

---

## 1. Current State Summary

**What it is today:** A functional but visually generic page. Light `#f5f5f7` iOS-settings-grey background, white cards, one red accent (`--pokeball-red #e3350d`), system font stack. Sticky header with title + overall completion bar. A changelog panel, then stacked sections each with an `<h2>`, a thin completion bar, and a `repeat(auto-fill, minmax(90px, 1fr))` grid of card slots. Empty slots are flat grey boxes with dex number + name. On mobile (<480px) the grid forces 3 columns.

**Honest assessment — what's weak:**
- **Reads like an admin dashboard, not a collector's showcase.** Flat light grey + system font is the default "internal tool" look. Card art is the hero content here and a light background makes holographic card scans look washed out — dark backgrounds are the category convention (TCG apps, PSA/PriceCharting, Collectr, the Pokémon TCG Live client) precisely because they make card art pop.
- **No visual hierarchy between chrome and content.** The header, changelog, and every section are all the same white-card-on-grey treatment at the same elevation. Nothing signals "this is the headline number" vs "this is supporting detail."
- **The completion number is buried.** `412/1025 complete` in muted 0.8rem text under a thin bar is the single most interesting fact for a viewer ("how close is he?") and it's styled like a form caption.
- **The changelog — the thing a Discord clicker lands on — has no visual pull.** The `#latest` entry is just text with colored `+`/`↻`/`−` glyphs. First-time landers won't immediately register "here's what's new."
- **Empty slots are dead weight.** Flat grey boxes with tiny text. On a 375px phone showing 3-wide, a mostly-incomplete section is a wall of grey noise with no texture or life.
- **Zero motion/personality.** Nothing acknowledges this is a Pokémon product. No loading state — a blank white page until fetch resolves (slow on mobile data from a Discord tap).

**Hard constraints carried forward (non-negotiable):** zero build step; no framework; `app.js` fetch/parse/group logic untouched; usable at 375px; works over `file://`; no new image/font assets beyond what's already fetched (card images + system fonts — though a Google Fonts `<link>` is acceptable as it's zero-build and CDN-hosted; see typography risk note).

---

## 2. Proposed Direction — One Coherent Package

**Concept: "Display Case."** Treat the page like a lit glass display cabinet at a card shop — a dark, near-black backdrop so the card art is the only thing that glows, with a restrained premium frame around it. Not a flashy holo-explosion (that fights the card art and ages badly); a calm, gallery-dark stage that makes someone else's collection feel valuable. The Pokémon identity comes through in one confident accent (Poké Ball red), tasteful rarity-tier edge treatment on filled slots, and a single hero completion stat — not in cartoon skinning.

### Aesthetic
Dark gallery. Deep charcoal-to-near-black background, content on slightly-elevated dark surfaces with soft borders (not heavy cards). Filled card slots get a subtle glow/lift so a wall of collected cards reads as a lit trophy case; empty slots recede as quiet dashed placeholders rather than competing grey blocks. Overall feeling: premium, focused, "look what he's built."

### Color System (dark, WCAG-checked)
```
--bg:            #0e0f13   (near-black page)
--surface:       #191b22   (elevated panels: header, changelog, section wells)
--surface-2:     #212430   (empty-slot fill, track backgrounds)
--border:        #2a2d38   (hairline dividers, low emphasis)
--border-strong: #3a3e4d   (empty-slot dashed edge)
--text:          #f2f3f5   (primary — ~14.8:1 on --bg, passes AA/AAA)
--text-muted:    #a0a4b0   (secondary — ~6.7:1 on --bg, passes AA body)
--text-faint:    #6b6f7d   (dex numbers on empty slots — ~4.6:1 on --surface-2, passes AA)
--pokeball-red:  #ff4d3d   (brightened from #e3350d for dark-bg contrast; ~4.6:1 on --bg — passes AA large/UI; use for the fill bar, accents, NOT small body text)
--red-deep:      #e3350d   (original, for gradient depth only)
--gold:          #f5c451   (rarity/hero accent — hero stat number, "NEW" badge; ~9:1 on --bg)
--added:         #4ade80   (green — was #1a7f37, brightened for dark; ~8:1)
--replaced:      #fbbf24   (amber — was #9a6700; ~10:1)
--removed:       #f87171   (red — was #cf222e; ~6:1)
```
Rarity accent rationale: rather than parse rarity from data (out of scope — no rarity field in the schema), *all filled slots* get one consistent premium edge treatment (thin warm border + soft shadow glow), and the single hero completion % uses gold. This reads as "premium collection" without inventing data we don't have.

### Typography
- **Display / numerals:** Add one Google Fonts link — a strong geometric or rounded-grotesk for the wordmark and the big completion number. Proposed: **"Outfit"** (variable, one weight axis, ~15KB, zero-build via `<link>`). It gives the hero stat a confident modern-collector feel the system font can't. If the Implementer/Reviewer judges the CDN dependency unacceptable for a `file://`-must-work page, fall back to the existing system stack with heavier weights (600/700) — the layout does not depend on the custom font, only benefits from it. **Graceful degradation is mandatory: `font-family` must list Outfit first, then the full system stack, so a blocked/offline CDN silently falls back.**
- **Body:** keep the system stack (`-apple-system … sans-serif`) for changelog and labels — fast, familiar, no penalty.
- **Scale (type ramp, rem):** hero number 2.75 / h1 wordmark 1.5 / section h2 1.05 / body 0.9 / caption 0.78. Tighten hero number letter-spacing to -0.02em; set numerals `font-variant-numeric: tabular-nums` so counts don't jitter.

### Spacing (8pt grid)
Normalize everything to an 8pt grid (current values are close but ad-hoc: 16/12/8/4/24). Tokens: `--s1:4px --s2:8px --s3:12px --s4:16px --s6:24px --s8:32px`. Section vertical rhythm goes to 32px (`--s8`) for clearer separation on the dark bg; grid gap stays 8px; panel padding 16px. Add a `--radius:12px` (up from 8px) for a slightly softer, more premium corner, `--radius-sm:8px` for slots.

### Layout
Structure stays identical (header → changelog → sections → footer) to honor the "no grouping-logic change" rule. Refinements:
- **Header becomes a proper hero band.** Wordmark + a small Poké Ball mark (pure CSS, see below) on the left; the overall completion as a **large gold number with % and a fraction beneath** on the right, over a full-width slim progress bar. Sticky, with a subtle bottom border + slight backdrop blur so cards scrolling under it don't clash.
- **Changelog gets a "What's new" identity.** Panel gets a left accent rule + a small gold "LATEST" pill on the newest entry, so a Discord lander instantly sees the fresh content. Older entries stay in `<details>`, restyled.
- **Sections become "wells."** Each section sits in a subtly recessed surface with the title + an inline `filled/total` count and a right-aligned mini percentage, bar beneath. Grid unchanged structurally.
- **Slots:** filled = card art with rounded corners + soft premium shadow + faint warm border, gentle hover lift (desktop). Empty = dashed-border recessed placeholder with a faint centered Poké Ball silhouette watermark behind the dex number/name, so incomplete sections have texture instead of dead grey.
- **Mobile (375px):** keep 3-col grid. Hero stacks (wordmark row above, big number + bar below full-width). Ensure tap targets and text remain legible; reduce section well padding to 12px.

### Motion / Interaction
All CSS, all cheap, all respecting `prefers-reduced-motion`:
- **Loading state:** replace the blank-white wait with skeleton shimmer slot placeholders (or a simple centered spinning Poké Ball) shown until data renders. (Presentational JS: inject skeleton markup on load, swap on render — does NOT touch fetch/parse logic.)
- **Bars animate from 0 → target width** on first render (already have `transition: width` — add a rAF/next-tick trigger so it visibly fills; presentational only).
- **Slot hover (desktop):** 120ms lift + shadow bloom on filled cards. No hover effects that break touch.
- **Newest changelog entry:** subtle one-time fade/slide-in.
- **Respect `prefers-reduced-motion: reduce`** — disable transforms/animations, keep final states.

---

## 3. SAFE vs RISK Breakdown

### SAFE (category-expected baseline — low risk, high polish return)
- **Dark background.** This is the *conservative* choice for a card showcase, not a bold one — every serious TCG/collectible app is dark because it flatters card art. Going dark is meeting the category bar, not departing from it.
- **8pt grid + type ramp + tabular numerals.** Standard craft. Zero downside.
- **WCAG-AA-checked color tokens.** All body/muted text verified ≥4.5:1, UI/large ≥3:1 (values noted inline above). Non-negotiable baseline.
- **Elevating the hero completion stat.** The single most-wanted fact made prominent — obvious IA fix.
- **Loading skeleton + bar fill animation.** Expected feedback for a network-fetched page on mobile (aligns with the project's own ui-progress rule for long-running/fetched UI).
- **Restyled changelog with a "latest" marker.** Directly serves the primary user story (Discord lander sees what's new first).

### RISK (deliberate departures — worth it, with rationale)
- **RISK 1 — Custom web font via Google Fonts CDN (`Outfit`).** *Departure:* adds an external network dependency to a page that must also work over `file://`. *Why worth it:* the hero number and wordmark are the page's personality; the system font makes a showcase feel like a spreadsheet. *Mitigation making it safe:* font is purely additive — full system-stack fallback listed first-class, layout never depends on it, and `file://`/offline degrades silently to the current look-but-better-weighted. If the Reviewer objects, dropping the `<link>` is a one-line revert with no layout breakage. **Flagged for explicit Reviewer sign-off.**
- **RISK 2 — Pure-CSS Poké Ball motifs (header mark, empty-slot watermark, loading spinner).** *Departure:* injecting decorative brand iconography built from CSS gradients/borders rather than staying content-only. *Why worth it:* it's the cheapest possible way to say "this is Pokémon" without asset files, and it turns dead-grey empty slots into something with texture and theme. *Risk:* a badly-done CSS Poké Ball looks amateur. *Mitigation:* keep it minimal and low-opacity (watermark ~6–8% on empty slots), and it's decorative-only — if it reads cheap, the Reviewer can cut it without affecting function or layout.
- **RISK 3 — "Premium edge" glow/shadow on every filled slot.** *Departure:* applying a rarity-tier visual treatment uniformly when we have no rarity data. *Why worth it:* a wall of softly-glowing cards is what makes a collection read as a *trophy case* vs a grid of thumbnails — the core emotional goal. *Risk:* overdone glow looks gaudy / hurts performance with many slots. *Mitigation:* very restrained (small blur-radius box-shadow + 1px warm border, GPU-cheap), tuned so 100+ slots stay smooth; measured against 375px mid-tier phone.
- **RISK 4 — Backdrop-blur on the sticky header.** *Departure:* `backdrop-filter` has cost and imperfect old-browser support. *Why worth it:* cards scrolling under a solid bar clash; a blurred bar keeps the "glass display case" metaphor coherent. *Mitigation:* fall back to solid `--surface` where unsupported (`@supports`); purely aesthetic.

*(No zero-risk-blandness here: the dark display-case aesthetic + brand motifs + uniform premium edge are the deliberate bets that separate this from a default dashboard.)*

---

## 4. What Changes in the Files (Implementer Spec)

**Guardrail for Implementer:** In `app.js`, you may ONLY touch presentational rendering (markup strings emitted by `renderSlot`/`renderSection`/`renderChangelog`/`render`, plus new skeleton-injection and animation-trigger helpers). **Do NOT modify** `loadJson`, `overallCompletion`, `formatPublishedAt`, `barHtml`'s pct math, the section/binder iteration order, or any fetch/schema/grouping logic. Adding CSS classes and wrapper elements to emitted markup is fine; changing what data is read or how sections are grouped is not.

### `index.html`
1. Add (RISK 1, revertable) a Google Fonts preconnect + stylesheet `<link>` for **Outfit** (weights 400/600/700) in `<head>`, before `styles.css`.
2. Add `<meta name="theme-color" content="#0e0f13">` for mobile browser chrome matching the dark bg.
3. Header markup: give `#page-header` an inner wrapper `<div class="header-inner">` so hero content can flex-layout (wordmark+mark left, stat right) within the same `max-width` as `main`. Add a `<span class="pokeball-mark" aria-hidden="true"></span>` before the `<h1>`. (JS still rewrites header innerHTML in `render()` — so mirror this structure there; see app.js note.)
4. Add a loading placeholder container inside `#binders` (e.g. `<div class="skeleton-wrap">…</div>`) OR have JS inject it — Implementer's choice, but skeleton must show before data resolves.
5. Restyle footer copy unchanged (text stays).

### `styles.css` (the bulk of the work)
1. Replace `:root` tokens with the dark color system + spacing/radius/type tokens from §2. Keep the existing variable *names* where they exist (`--bg --surface --text --text-muted --border --bar-track --bar-fill --empty-bg`) remapped to new values so nothing referencing them breaks; ADD the new ones (`--surface-2 --border-strong --text-faint --gold --added/replaced/removed --s1..--s8 --radius --radius-sm`).
2. `body`: dark bg, set base font to `"Outfit", -apple-system, …` system stack (fallback-first-class).
3. **Header → hero band:** flex `.header-inner` (row on desktop, column on ≤480px), `.pokeball-mark` pure-CSS Poké Ball (~22px), backdrop-blur with `@supports` solid fallback, bottom border. Hero stat: `.hero-stat` big gold tabular number (2.75rem) + `.hero-frac` muted fraction; the overall `.bar` spans full width beneath.
4. **Bars:** track `--surface-2`, fill gradient `--pokeball-red → --red-deep`, taller (10px) with inner subtle highlight; keep `transition: width`.
5. **Changelog panel:** `--surface` well, left `3px` `--gold` accent rule, `--radius`. `.changelog-entry-latest` gets a `LATEST` gold pill (add via CSS `::before` on a class, or a `<span>` the Implementer adds in `renderChangelog` markup — presentational). Recolor `.change-added/.change-replaced/.change-removed` to the new brightened tokens. Restyle `<details>` for dark.
6. **Sections → wells:** `section.binder-section` gets `--surface` bg, `--radius`, padding `--s4`, `--s8` bottom margin. Header row: title left, `filled/total` + right-aligned mini `%` (Implementer adds the `%` span in `renderSection` markup — presentational, computed from existing `filled`/`total`).
7. **Slots:**
   - `.slot` filled: `--radius-sm`, soft premium `box-shadow` + 1px warm border (RISK 3, restrained), `transition: transform/shadow`; `:hover` lift `translateY(-2px)` (wrap in `@media (hover:hover)`).
   - `.slot.empty`: `--surface-2` fill, `1px dashed --border-strong`, faint centered CSS Poké Ball watermark (RISK 2, ~7% opacity) behind `.dex-number`/`.slot-name`; text uses `--text-faint`.
8. **Skeleton + motion:** `.skeleton` shimmer keyframes (dark-appropriate gradient sweep); `@keyframes` for the one-time changelog fade-in. Wrap ALL animations/transitions in a `@media (prefers-reduced-motion: reduce){ … }` override that neutralizes them.
9. **Mobile `@media (max-width:480px)`:** keep 3-col grid; stack `.header-inner` to column; reduce section well padding to `--s3` (12px); ensure hero number scales down (~2.1rem) so it doesn't overflow at 375px. Verify no horizontal scroll at 375px.

### `app.js` (presentational-only, per guardrail)
1. `render()` header `innerHTML`: rebuild to match the new hero structure — `.header-inner` wrapper, `.pokeball-mark`, wordmark, and a `.hero-stat` block showing the **percentage** (compute `Math.round(filled/total*100)` — same math shape as `barHtml`, no new data source) as the big number + `filled/total` fraction beneath, plus the full-width bar. (Uses existing `filled`/`total` from `overallCompletion` — do not change that function.)
2. `renderSection()`: add a right-aligned `%` span in the section header row (from existing `filled`/`total`). No change to slot iteration.
3. `renderSlot()` empty branch: structure can stay; just ensure classes exist for the watermark styling (no logic change). Filled branch unchanged except any wrapper class needed for hover.
4. `renderChangelog()`: add a `<span class="latest-pill">LATEST</span>` (or equivalent) into the `.changelog-entry-latest` markup. No change to which entries are shown or ordering.
5. **New presentational helpers:** (a) inject skeleton markup into `#binders` at start of `init()` before `await` resolves; (b) after `render()`, trigger bar fill (set widths to 0 in markup, then `requestAnimationFrame` to target — OR rely on CSS transition from an added class). Keep these isolated; they must not alter fetched data.
6. **Do not touch:** `loadJson`, `overallCompletion`, `formatPublishedAt`, `barHtml` math, `escapeHtml`, `changeIcon`, iteration order, the `#latest` scroll behavior (keep it working).

### Acceptance checks for downstream stages
- Renders correctly with `sample-binder.json` / `sample-changelog.json` over `file://` **and** with the CDN font blocked (fallback proves out).
- No horizontal scroll at 375px; hero number never clips.
- All text meets AA: body/muted ≥4.5:1, hero/large/UI ≥3:1 (Tester to spot-check with computed styles).
- `prefers-reduced-motion: reduce` disables all motion, final states intact.
- `#latest` anchor still scrolls on load.
- `app.js` diff touches only presentational markup + new helpers — no fetch/parse/group logic changed (Reviewer to confirm via diff).
- RISK 1 (font CDN) and RISK 2 (CSS Poké Ball motifs) explicitly flagged for Reviewer go/no-go.

---

## 5. Addendum — Skyler's Mandatory Requirements (2026-07-05, post-brief review)

Skyler reviewed this brief before implementation started and requires the following. **These are mandatory, not proposals** — they supersede any conflicting default above (chiefly the "Layout" section's assumption of 13 sections shown inline). Everything else in this brief (color system, typography, spacing, slot styling, motion, RISK 1/2/3/4) still stands and applies *within* the structure below.

### A. Reorganize into 5 click-to-open "binders" (was: 13 sections shown inline)

Currently the page renders every section from the fetched JSON flat, one after another: Generation I…IX, then Regional Variants, Alternate Forms, Mega Evolutions, VMax (13 blocks total, all visible at once). Skyler wants **5 binders**, closed by default, click to open:

1. **"Pokédex"** — combines all 9 Generation sections. Clicking this binder tile opens it to reveal the existing per-generation sub-sections (each keeps its own name/count/bar/grid exactly as today), just nested inside one openable container instead of sitting bare on the page.
2. **"Regional Variants"** — the existing section of that name, now its own standalone binder.
3. **"Alternate Forms"** — same treatment.
4. **"Mega Evolutions"** — same treatment.
5. **"VMax"** — same treatment.

This is a **client-side grouping of already-fetched sections for display purposes only** — it does not change `binder.json`'s schema, the Kotlin app's `SECTION_ORDER`/`sectionNameFor`, or how `app.js` parses the response. Group by matching section `name` against the known "Generation I".."Generation IX" set → nest under the "Pokédex" binder tile; the other 4 known section names each become their own single-section binder tile. (If a future section name doesn't match any of the 13 known names, fail safely — e.g. drop it into a 6th "Other" binder rather than crashing, so the page doesn't break if the schema grows before this page is updated.)

**Closed-state binder tile:** shows binder name, an overall completion stat for that binder (e.g. "Pokédex — 412/1025" or "Regional Variants — 38/61"), and a visual affordance that it's clickable (chevron, or a closed-binder-spine visual consistent with the dark "Display Case" aesthetic — Implementer's call, keep it restrained per the brief's existing RISK guidance, not a new independent risk).

**Open-state:** reveals the binder's contents (for Pokédex: the 9 generation sub-sections stacked as they render today, each with its own mini header/bar/grid; for the other 4: that section's grid + bar directly, no extra nesting needed since it's already a single section).

**Interaction model:** accordion-style is the default assumption (opening one binder doesn't require closing others, but Implementer may choose either simultaneous-multi-open or single-open-at-a-time — pick whichever is less code/state and note the choice in `design-changes.md`). Must be keyboard-accessible (the tile is a real `<button>`, Enter/Space toggles, not a div with only a click handler) and must not require JS to render *something* on first paint (i.e., closed tiles are present in the initial render, not injected only after a click).

**`app.js` impact (still presentational, not data logic):** `render()`/`renderSection()` need restructuring into a `renderBinderTile()` (or similar) that: (a) buckets the fetched `sections` array into the 5 (or 6, with the "Other" fallback) display groups client-side, (b) renders each as a closed tile by default, (c) toggles an `.open` class / `hidden` attribute on click to reveal contents already rendered in the DOM (simplest, no re-render on toggle) or lazily builds the inner markup on first open (also fine). Either is acceptable; do not refetch or reparse JSON on toggle — the full dataset is already in memory from the initial fetch.

### B. Card grid — hard cap of 6 columns per row

Current CSS: `grid-template-columns: repeat(auto-fill, minmax(90px, 1fr))`, which lets the column count grow unbounded on wide desktop viewports. Skyler wants **never more than 6 columns**, at any viewport width. Mobile (375px) keeps degrading to fewer columns (3, per the existing mobile rule) — 6 is the *ceiling*, not a fixed count at every width. Implementer should verify no viewport between 375px and 1440px+ ever renders a 7th column (e.g. via `repeat(auto-fill, minmax(max(90px, calc((100% - 5 * var(--gap)) / 6)), 1fr))`, or explicit breakpoint steps topping out at `repeat(6, 1fr)` — either approach acceptable).

### C. Click-to-zoom on card images

Clicking (or tapping) a **filled** slot's card image opens a lightbox showing the card at full/larger size over a dimmed backdrop. Closes on: clicking the backdrop, an explicit close control, or Esc. **Empty slots are not clickable for zoom** (nothing to show). This reads the same `imageUrl` already present in the fetched slot data for that card — no new network fetch, no schema change.

**Implementation approach:** one reusable lightbox container (e.g. `#lightbox`, hidden by default) added once in `index.html` or injected once by `app.js` on init; a click handler on filled slots sets the lightbox `<img>` src to that slot's existing `imageUrl` and toggles it visible. Give it baseline accessibility: `role="dialog"`, `aria-modal="true"`, Esc-to-close, and don't trap page scroll in a way that breaks mobile usability (locking body scroll while open is fine and typical for a lightbox).

### Updated acceptance checks (in addition to §4's list above)
- Page loads with all 5 (or 6, with fallback) binders **closed** by default; no section content renders "for free" until its binder is opened (or is present but visually collapsed — either satisfies this, as long as closed is the default visible state).
- Each binder tile is keyboard-operable (Tab to it, Enter/Space toggles open/closed) and has a visible focus state.
- No viewport width from 375px up to at least 1440px ever shows more than 6 grid columns in any section.
- Clicking a filled card opens the lightbox with the correct full-size image; clicking an empty slot does nothing (no dead lightbox with no image).
- Lightbox closes via backdrop click, close button, and Esc; page scroll/interaction isn't left broken after closing.
- All of the above still respects `prefers-reduced-motion` (no forced open/close animation for users who've opted out) and the `app.js` fetch/schema/grouping-logic guardrail from §4 (bucketing sections into binders for *display* is allowed; it must not alter what's fetched or how the underlying section list is parsed).
