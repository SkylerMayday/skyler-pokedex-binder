# Spec — Binder Page-Flip Viewer (web) (2026-07-08)

**Status:** APPROVED v1 (Skyler, 2026-07-08) — ready for pipeline
**Owner:** Skyler
**Scope:** Web viewer only (`web/pokedex-binder-site/` — `index.html`, `styles.css`, `app.js`)
**Companion:** `2026-07-08-card-details-and-language.md` (approved) — the two compose; see
Sequencing.

## Problem Statement

Clicking a binder spine currently expands a scrolling 6-column card grid — functional, but
nothing like handling a real binder, and a 1000+ slot binder becomes one giant scroll. Skyler
wants the site to feel like flipping through the physical object the app models.

## Goals

1. Clicking a binder spine on the shelf opens that binder BELOW the shelf as a two-page
   3×3-pocket spread (18 slots per view), like a binder lying open.
2. Users flip pages via left/right arrow buttons, swipe on touch devices, and arrow keys on
   desktop, with a "Page X–Y / N" indicator.
3. The page view REPLACES the scrolling grid entirely — one browsing metaphor.
4. Clicking a card in a page keeps the existing zoom lightbox (and, once the companion spec
   ships, its details panel). NEW badges keep working per card.

## Non-Goals (v1)

- **Realistic 3D page-turn animation.** v1 uses a simple slide/instant transition;
  a CSS 3D flip is P1 polish, and must respect `prefers-reduced-motion` regardless.
- **Keeping the old grid as an alternate view.** Explicitly replaced (Skyler's call).
- **Changing the shelf/spine design, changelog panel, or publish data.** Zero binder.json
  changes — this is pure presentation.
- **App-side changes.** None.

## User Stories

- As a visitor, I click the Pokédex spine and an open binder appears below the shelf showing
  the first 18 slots as two 9-pocket pages; I flip through with arrows/swipe/keys.
- As a visitor on a phone, I see one 3×3 page at a time and swipe to flip.
- As a visitor, I click a card in a pocket and get the zoom lightbox, exactly as today.
- As a visitor, I open a different binder from the shelf and the open binder below switches
  to it, starting at its first spread.
- As a returning visitor mid-session, reopening a binder returns me to the spread I was on
  (P1, sessionStorage).

## Design

### Structure

- Shelf stays as-is. Clicking a spine opens/switches the binder viewport below the shelf
  (clicking the open binder's spine again closes it, matching current toggle behavior).
- Each display binder's slots (existing bucketing into the 5 display binders is unchanged)
  fill 9-pocket pages in current display order, including EMPTY slots — pockets are part of
  the binder metaphor. Pages pair into spreads: (1,2), (3,4), ... Last spread may have a
  lone left page.
- Desktop/tablet: two pages side by side with a center "spine" gutter. Mobile (existing
  breakpoint): single page per view; flipping advances one page instead of two.
- Page indicator between/below the arrows: "Page 3–4 / 114" (desktop) / "Page 3 / 114"
  (mobile).

### Controls

- Left/right arrow buttons flanking the spread (large hit targets, disabled/dimmed at the
  ends).
- Touch: horizontal swipe (threshold-based, no library).
- Desktop: ArrowLeft/ArrowRight when a binder is open and the lightbox is CLOSED (the
  lightbox keeps its own key handling; no double-binding).
- Section headers (Pokédex/Alt Forms/etc. within a display binder, if present in current
  rendering) become small page-corner labels or are dropped within pages — Implementer's
  judgment, consistency with Display Case styling decides.

### Rendering & performance

- Only the current spread (+ optionally adjacent spreads for preload) exists in the DOM —
  replaces today's render-everything grid. This is a performance WIN for the 1000+ slot
  binder; keep image `loading="lazy"` regardless.
- NEW badges (`computeNewSlotIds` / `.slot--new`) and the lightbox handlers apply to slots
  as rendered per page — the existing per-slot render function should be reused, not forked.

### Accessibility

- Arrow buttons: real `<button>`s with `aria-label="Previous/Next page"`.
- Page indicator is `aria-live="polite"`.
- Keyboard flipping as above; focus stays sane after a flip (focus moves to the spread
  container, not lost to body).
- `prefers-reduced-motion`: any transition (v1 slide or P1 flip) drops to instant.

### Styling

Display Case system throughout: pages read as cream/leather pocket sheets against the dark
case, gold accents consistent with `.latest-pill`/badges, pocket cells echo the current
`.slot` treatment (filled glow, empty state) so cards look identical to today inside a
pocket.

## Requirements

### P0 — Must have
- [ ] Spine click opens the binder below the shelf as a 3×3 two-page spread (single page on
      mobile); switching binders works; re-click closes.
- [ ] All slots of a binder are reachable by flipping; empty slots render as empty pockets;
      slot order matches the current grid order exactly.
- [ ] Arrows + swipe + arrow keys + page indicator; ends handled (no wrap, buttons dim).
- [ ] Old scrolling grid removed; no dead code left behind.
- [ ] Lightbox and NEW badges work unchanged on cards inside pages.
- [ ] Only current(±1) spreads in the DOM; no regression in initial page load.
- [ ] Reduced-motion respected; arrow buttons accessible; works against real AND sample
      binder.json.
- [ ] Verified via preview tools at desktop + mobile widths (mind the documented
      stale-cache workaround in `.pipeline/design-review-verdict.md`).

### P1 — Nice to have
- [ ] CSS 3D page-flip animation (reduced-motion → instant).
- [ ] Remember last-viewed spread per binder for the session (sessionStorage).
- [ ] Jump-to-section shortcut (e.g. small section tabs above the spread).

### P2 — Future
- Deep-linkable page URLs (hash routing) for sharing a specific spread.

## Sequencing vs. the companion spec

Both specs touch `app.js`/`styles.css`/lightbox surface. Recommended order next session:
1. dev-team-pipeline: companion spec's APP side (schema v8, capture, backfill, publish).
2. ONE web-side pipeline run implementing BOTH the lightbox details panel and this page-flip
   view together — serial implementation of the same files avoids double rework. (Whether
   that run is dev-team or design-team: it adds interaction functionality, so dev-team, with
   an optional design-team polish pass after — decide at the time.)

## Success Criteria

Skyler opens the live site on phone + desktop, flips through the Pokédex binder end to end,
opens a card's lightbox from a middle page, sees NEW badges where expected, and it feels like
a binder. No console errors, no perf regression.

## Open Questions

- None blocking. (Implementer judgment items are marked inline: section-header treatment
  within pages.)
