# Design Brief — Changed-Card Highlight Badges

**Stage:** Design Consultant (1 of 4)
**Target:** `web/pokedex-binder-site/` — `index.html`, `styles.css`, `app.js`
**Feature:** on-brand "NEW" badges on grid cards that were ADDED or REPLACED in the most recent publish.

---

## 1. Current State — What Exists, and the Specific Gap

The site is live and working: dark "Display Case" theme, a wood shelf holding 5 collapsible leather-spine binder tiles, a 6-column-capped card grid inside each tile, and a click-to-zoom lightbox.

**Both data files are already fetched in parallel** in `init()` (app.js:356–359):

```js
const [binderSnapshot, changelog] = await Promise.all([
  loadJson(BINDER_URL, SAMPLE_BINDER_URL),
  loadJson(CHANGELOG_URL, SAMPLE_CHANGELOG_URL)
]);
render(binderSnapshot, changelog);
```

`render(binderSnapshot, changelog)` receives the changelog and passes it to `renderChangelog(changelog)` — the **"Recent updates" text panel** at the top of the page (`#changelog-panel`). That panel already lists each change as a colored `<li>` with a `+ / ↻ / −` glyph (`renderChangeList`, app.js:48–55). **This feature is NOT that panel — leave `renderChangelog` untouched.**

**The gap:** the render chain that produces the actual card grid is:

```
render() → bucketSectionsIntoDisplayBinders() → renderBinderTile() → renderSection() → renderSlot()
```

None of these functions receive the changelog. `renderSlot(slot)` (app.js:91–99) only sees the slot object. So a card that was just ADDED or REPLACED looks identical in the grid to one added a year ago. The changed-slot information is loaded into memory but **never threaded down to the grid renderers.** This feature threads it down and paints a badge.

**Join key:** `slot.slotId` (present on every binder slot — see sample-binder.json) is identical to `change.slotId` in the changelog (see sample-changelog.json). Clean, direct join — no fuzzy matching, no name normalization needed.

**The existing filled-slot treatment we must not fight:** every `.slot.filled` already carries a subtle gold hairline ring — `box-shadow: 0 0 0 1px rgba(245, 196, 81, 0.12)` at rest, brightening to `0.25` on hover (styles.css:565, 573). That is the "premium glow" the ask refers to. `.slot` is already `position: relative` (styles.css:560), so a corner-absolute child needs zero positioning changes to the slot itself.

---

## 2. Proposed Direction — One Coherent Package

### "Freshly slotted" — a single gold corner pill reading **NEW**

**One badge, one meaning: "this card entered the binder in the latest publish."** A small gold pill in the **top-right corner** of the card, reading `NEW` in the same uppercase micro-type already used by the `.latest-pill` in the changelog panel. This deliberately mirrors the existing "Latest" pill's visual language (gold fill, dark text, `999px` radius, `0.05em` tracking, uppercase) so the badge reads as part of the same design system, not a bolted-on afterthought. The user already learns "gold pill = the latest publish" from the changelog panel; we reuse that learned meaning on the card.

**Placement — top-right corner, inset:** Pokémon card art is portrait; the visually important subject (the Pokémon, the name banner, the art box) sits center and lower-center. The **top-right corner** is the safest dead zone — it typically holds only the card's HP text / energy symbol, never the primary subject. Inset by `6px` from the corner (not flush) so it reads as a deliberate sticker, not a print defect, and so the card's own rounded corner (`--radius-sm`) isn't visually clipped by it. The pill sits **on top of** the art via `position: absolute` — it must not reflow or shrink the image.

**Shape & size:** rounded pill (`999px` radius), ~`0.6rem` font, `2px 7px` padding — small enough that at the 6-column desktop cap (~90–150px cards) and the 3-column mobile layout it occupies well under 20% of the card's top edge and never crosses the horizontal centerline or reaches the name banner. Text `NEW`, uppercase, weight 700, letter-spacing `0.05em`.

**Color — gold, matching the brand's "latest/attention" token:** background `var(--gold)` (`#f5c451`), text `#201a05` (the exact dark-brown text the `.latest-pill` already uses). This is the single palette the site reserves for "look here / newest." Red (`--pokeball-red`) is reserved for progress bars and the pokéball mark; green/amber/red (`--added` / `--replaced` / `--removed`) are reserved for the changelog panel's semantic text list. Keeping the grid badge gold-only means the grid speaks one attention-color and doesn't leak the changelog's three-color semantic system onto the cards.

To keep the pill legible over any card art (bright or dark), it carries a thin dark outline + drop shadow: `border: 1px solid rgba(0,0,0,0.35)` and `box-shadow: 0 1px 3px rgba(0,0,0,0.5)`. This is the same "float above the image" treatment the shelf/spines already use.

### ADDED vs REPLACED — same badge, deliberately

**They look identical. Both say `NEW`.** Justification: from the *public viewer's* perspective, "ADDED" (empty slot → now has a card) and "REPLACED" (had a card → now a different/better card) are the same signal — *this card is new to what you're looking at.* The viewer never saw the old card; "replaced" is an authoring detail, not a viewer-facing distinction. The changelog panel already preserves the ADDED/REPLACED/REMOVED distinction in text for anyone who wants it (`+` vs `↻` glyphs). Duplicating that three-way semantic onto the grid would (a) require a second color, reintroducing the green/amber fight the grid is trying to avoid, and (b) make the viewer parse two badge variants for a distinction they don't care about. One badge, one job: *"new here."* This is the senior call — resist the urge to encode authoring state the viewer can't act on.

**REMOVED gets no badge** (as the ask states) — a removed card leaves an empty slot; there's nothing in the grid to badge. The changelog panel already reports removals in text.

### Interaction with the existing premium-glow — they stack, they don't fight

The gold hairline ring (`box-shadow`) is a **whole-card edge** treatment; the NEW pill is a **single-corner** treatment. Different visual channels (edge vs corner), so they coexist without muddiness. To make the freshly-added cards feel genuinely "pulled forward" without a second competing element, we **intensify the card's existing gold ring** on `.slot.filled.slot--new` — bump the ring from `0.12` to a stronger gold and add a soft outer gold glow. This is the *same* box-shadow property already in play, just turned up — so it's one coherent "this card is special" gesture (brighter frame + corner pill) rather than two unrelated effects. The badge is the label; the brighter frame is the emphasis. They're one package.

### Motion — one-time, gentle, reduced-motion-safe

A single, subtle entrance: the pill fades + scales in once on load (`badge-pop`, ~350ms, `ease-out`, no loop). Reusing the site's existing `fade-slide-in` vocabulary in spirit. **No infinite pulse** — a perpetually pulsing badge on a wall of cards would be visual noise and cheapen the premium feel. The existing global `@media (prefers-reduced-motion: reduce)` block (styles.css:778–802) already forces `animation-duration: 0.001ms` on `*`, so the pop is automatically neutralized under reduced-motion with **no new rule needed** — but we add an explicit belt-and-suspenders line for clarity.

---

## 3. First-Ever-Publish Edge Case — THE CALL

**Question:** if the latest changelog entry IS the first-ever publish (everything is `ADDED`), should the entire first-ever binder light up with `NEW` badges?

**Call: SUPPRESS badges when the latest entry is the first-ever publish.** No badges appear if the changelog has exactly one entry (`changelog.entries.length <= 1`).

**Justification:** a badge is a *diff* signal — "this changed relative to what was here before." On the first publish there is no "before," so *every* filled card would be badged. A wall of 100% NEW badges carries zero information (if everything is new, nothing stands out) and actively harms the premium look — it turns the whole grid into a field of stickers. The signal only has value when it's the exception, not the rule. "Everything is new" is not meaningful signal; it's noise. From the second publish onward, badges mark the genuine delta and the feature does its job.

**Concretely:** the set of changed slotIds passed to the renderers is **empty** whenever `changelog.entries.length <= 1` (or the changelog is null/empty). This also naturally covers the "no changelog at all" case — empty set → no badges → identical to today's behavior. Fail-safe: any uncertainty about changelog shape → empty set → no badges.

Note: from publish #2 on, a card ADDED in the first publish and never touched since will correctly carry **no** badge (it's not in the *latest* entry's changes) — only the newest delta is highlighted, which is the whole point.

---

## 4. SAFE vs RISK

### SAFE (do all of these)
- **S1.** Compute the changed-slotId `Set` once in `render()` from the latest changelog entry, gated by the first-publish rule (§3). Pure read of already-loaded data, no new fetch.
- **S2.** Thread that `Set` as a new **last positional argument** through `bucketSectionsIntoDisplayBinders` → `renderBinderTile` → `renderSection` → `renderSlot`. Additive parameter, defaults to an empty set, so any un-updated caller degrades to "no badges" rather than throwing.
- **S3.** In `renderSlot`, when the slot is filled AND its `slotId` is in the set, add class `slot--new` and inject the badge `<span>`. Empty slots and non-changed slots are byte-for-byte unchanged.
- **S4.** New CSS: `.slot-badge` (the pill) + `.slot.filled.slot--new` (intensified ring) + `badge-pop` keyframe + one reduced-motion line. All additive; no existing rule is modified.
- **S5.** Badge is `aria-hidden="true"`? **No** — see S6. Keep it accessible.
- **S6.** Accessibility: the badge text `NEW` is real text (not a background image), so screen readers announce it. But to give context, the slot's existing `aria-label` ("View {name} full size") gains a " — new in latest update" suffix **only** for changed slots, so a SR user knows *which* cards are new without relying on the visual pill. The pill `<span>` itself gets `aria-hidden="true"` to avoid double-announcing, since the label already carries the info.

### RISK (flag; implement carefully or defer)
- **R1 — Badge overlapping card HP/energy text.** Top-right on real Pokémon art sometimes holds the HP number. The inset + small size keeps overlap minimal, but on some cards the pill will sit near the HP text. *Mitigation:* the pill's dark outline + shadow keeps it legible regardless of what's under it; accept minor proximity. Do **not** move to top-left (that's where the card's stage/evolution + name banner start — worse). Top-right remains the best-of-available dead zone. **Verify visually** with real cards in the Tester stage.
- **R2 — Set-membership correctness across REPLACED.** A REPLACED slot's `slotId` is unchanged (same species, new card) and IS in the latest changes — so it correctly gets a badge. Confirm the Set is built from *all* ADDED **and** REPLACED changes, and explicitly **excludes** REMOVED (a removed slotId no longer has a filled slot to badge, but excluding it defensively avoids badging a coincidentally-refilled slot). Build the set as: `type === 'ADDED' || type === 'REPLACED'`.
- **R3 — Mobile 3-col crowding.** At 3 columns cards are larger, so the badge is comfortable; but verify the pill doesn't collide with the card's rounded corner at the smallest breakpoint. Sizing in §2 is chosen to clear it; Tester should confirm at 360px width.
- **R4 — Reduced-motion.** The global `*` reduced-motion rule already zeroes animations, so the pop is covered. Adding an explicit line is defensive, not strictly required — but include it so the intent is legible to future maintainers.

---

## 5. What Changes in the Files (Implementer Spec)

### 5.1 `app.js`

**(a) New helper — build the changed-slotId set. Add near the other changelog helpers (after `renderChangelog`, before `renderSlot`):**

```js
/*
 * Changed-card badges — build the set of slotIds that were ADDED or REPLACED in the
 * LATEST publish only. Empty set => no badges (identical to prior behavior).
 * First-ever-publish rule: if there is 0 or 1 changelog entries, "everything is new"
 * is not meaningful signal, so return an empty set (no badges on a first-ever binder).
 * REMOVED is intentionally excluded — a removed slot has no filled card to badge.
 */
const BADGED_CHANGE_TYPES = new Set(['ADDED', 'REPLACED']);

function computeNewSlotIds(changelog) {
  if (!changelog || !Array.isArray(changelog.entries) || changelog.entries.length <= 1) {
    return new Set();
  }
  const latest = changelog.entries[0];
  const changes = (latest && Array.isArray(latest.changes)) ? latest.changes : [];
  return new Set(
    changes
      .filter(c => BADGED_CHANGE_TYPES.has(c.type) && c.slotId)
      .map(c => c.slotId)
  );
}
```

**(b) `renderSlot` — add trailing param `newSlotIds` (default empty set) and paint the badge on changed filled slots.** Replace the current function (app.js:91–99) with:

```js
function renderSlot(slot, newSlotIds = new Set()) {
  if (slot.cardId && slot.imageUrl) {
    const isNew = slot.slotId && newSlotIds.has(slot.slotId);
    const newClass = isNew ? ' slot--new' : '';
    const label = escapeHtml(slot.cardName || slot.slotName) + (isNew ? ' — new in latest update' : '');
    const badge = isNew ? `<span class="slot-badge" aria-hidden="true">New</span>` : '';
    return `<div class="slot filled${newClass}" tabindex="0" role="button" aria-label="View ${label} full size" data-image-url="${escapeHtml(slot.imageUrl)}" data-image-alt="${escapeHtml(slot.cardName || slot.slotName)}"><img loading="lazy" src="${slot.imageUrl}" alt="${escapeHtml(slot.cardName || slot.slotName)}">${badge}</div>`;
  }
  return `<div class="slot empty">
    <div class="dex-number">#${slot.dexNumber || ''}</div>
    <div class="slot-name">${escapeHtml(slot.slotName)}</div>
  </div>`;
}
```

> Note on label text: keep the visible pill text as `New` (title case reads softer at small size; CSS uppercases it via `text-transform`). The aria suffix is the full "— new in latest update" phrase.

**(c) `renderSection` — add trailing param and forward it into the `renderSlot` map.** Replace the `.map(renderSlot)` line (app.js:108). Full function:

```js
function renderSection(section, newSlotIds = new Set()) {
  const filled = section.slots.filter(s => s.cardId).length;
  const total = section.slots.length;
  const pct = total > 0 ? Math.min(100, Math.round((filled / total) * 100)) : 0;
  let html = `<section class="binder-section">`;
  html += `<div class="section-header-row"><h2>${escapeHtml(section.name)}</h2><span class="section-pct">${pct}%</span></div>`;
  html += `<div class="section-bar-wrap">${barHtml(filled, total)}<div class="completion-label">${filled}/${total}</div></div>`;
  html += `<div class="slot-grid">${section.slots.map(s => renderSlot(s, newSlotIds)).join('')}</div>`;
  html += `</section>`;
  return html;
}
```

**(d) `renderBinderTile` — add trailing param and forward into the `renderSection` map.** Change the `innerSections` line (app.js:184). The signature becomes `renderBinderTile(displayBinder, newSlotIds = new Set())` and:

```js
const innerSections = displayBinder.sections.map(s => renderSection(s, newSlotIds)).join('');
```

**(e) `render()` — compute the set and pass it into the tile map.** In `render(binderSnapshot, changelog)` (app.js:209), after `renderChangelog(changelog);` and before/around the `displayBinders.map(renderBinderTile)` call (app.js:239), add:

```js
const newSlotIds = computeNewSlotIds(changelog);
```

and change the tile render line to:

```js
main.innerHTML = '<div class="shelf-wrap"><div class="shelf">' +
  displayBinders.map(b => renderBinderTile(b, newSlotIds)).join('') + '</div></div>';
```

> `bucketSectionsIntoDisplayBinders` does **not** need the set threaded through it — it only regroups sections and doesn't render slots. The set flows from `render` → `renderBinderTile` → `renderSection` → `renderSlot`. Leave `bucketSectionsIntoDisplayBinders` untouched (contradicts nothing in S2; S2's chain starts at `renderBinderTile`).

### 5.2 `styles.css`

Add a new block **after** the `.slot.empty …` rules and before the skeleton section (i.e. after styles.css:629). All rules are additive.

```css
/* ---------- Changed-card badges (design-brief.md — "Freshly slotted") ---------- */

/* Intensified gold frame on a freshly-added/replaced card: same box-shadow channel
 * already used for the filled-slot premium ring, just turned up + a soft outer glow.
 * This is the "emphasis"; the pill is the "label". One coherent gesture, not two. */
.slot.filled.slot--new {
  border-color: color-mix(in srgb, var(--gold) 55%, #4a3a24);
  box-shadow:
    0 2px 8px rgba(0, 0, 0, 0.45),
    0 0 0 1px rgba(245, 196, 81, 0.55),
    0 0 12px rgba(245, 196, 81, 0.28);
}

@media (hover: hover) {
  .slot.filled.slot--new:hover {
    box-shadow:
      0 6px 16px rgba(0, 0, 0, 0.55),
      0 0 0 1px rgba(245, 196, 81, 0.7),
      0 0 16px rgba(245, 196, 81, 0.38);
  }
}

/* The "New" pill — top-right corner, gold, mirrors the changelog .latest-pill language. */
.slot-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 2;
  background: var(--gold);
  color: #201a05;
  font-size: 0.6rem;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 999px;
  border: 1px solid rgba(0, 0, 0, 0.35);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.5);
  pointer-events: none; /* clicks pass through to the slot's zoom handler */
  transform-origin: top right;
  animation: badge-pop 0.35s ease-out both;
}

@keyframes badge-pop {
  from { opacity: 0; transform: scale(0.6); }
  to   { opacity: 1; transform: scale(1); }
}
```

Then extend the **existing** reduced-motion block (styles.css:778) by adding one selector inside it (defensive; the global `*` rule already covers it):

```css
  .slot-badge {
    animation: none;
  }
```

### 5.3 `index.html`

**No changes required.** The badge markup is injected by `renderSlot`; no static HTML, no new element, no new script. Confirm there is nothing to add here.

---

## 6. Acceptance Criteria (for Tester / Reviewer stages)

1. With a multi-entry changelog, only cards whose `slotId` is in the **latest** entry's ADDED/REPLACED changes show a gold `NEW` pill in the grid.
2. Cards added in older (non-latest) entries show **no** pill.
3. REMOVED slots show no pill (slot is empty).
4. A single-entry (first-ever) changelog produces **zero** pills across the whole binder.
5. Null / missing / malformed changelog → zero pills, no console errors, grid renders as today.
6. The pill sits top-right, does not obscure the Pokémon's face/name, and passes through clicks to the lightbox (badged card still zooms on click).
7. Under `prefers-reduced-motion: reduce`, the pill appears with no animation.
8. The changed cards also carry the intensified gold frame; the frame and pill read as one treatment, not two clashing effects.
9. Screen reader announces changed cards with the "— new in latest update" suffix in their label; the pill span itself is `aria-hidden`.
10. The "Recent updates" text panel (`renderChangelog`) is unchanged.
