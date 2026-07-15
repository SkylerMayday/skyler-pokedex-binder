# Design Changes — "Freshly Slotted" NEW badges

**Stage:** Design Implementer (2 of 4)
**Brief:** `.pipeline/design-brief.md`

---

## What changed, per file

### `web/pokedex-binder-site/app.js`

All changes are additive per brief §5.1, threading a `newSlotIds` Set as a trailing
parameter through the existing render chain. No existing behavior was altered for
callers that don't pass the new argument.

1. **New helper `computeNewSlotIds(changelog)`** (added after `renderChangelog`,
   before `renderSlot` — brief §5.1a). Builds the Set of `slotId`s that were `ADDED`
   or `REPLACED` in the latest changelog entry only. Implements the §3 first-publish
   suppression rule (`entries.length <= 1` → empty Set) and the §5/R2 exclusion of
   `REMOVED`. Also fail-safe on null/malformed changelog (`!changelog`,
   non-array `entries`, missing `changes` all degrade to an empty Set — verified,
   see Verification below).

2. **`renderSlot(slot, newSlotIds = new Set())`** (brief §5.1b) — default param means
   any caller that doesn't pass the set gets identical output to before. When a
   filled slot's `slotId` is in the set: adds `slot--new` class, injects
   `<span class="slot-badge" aria-hidden="true">New</span>`, and appends
   " — new in latest update" to the `aria-label` (S6, accessibility). Empty slots are
   untouched — the badge branch only exists inside the `slot.cardId && slot.imageUrl`
   path.

3. **`renderSection(section, newSlotIds = new Set())`** (brief §5.1c) — forwards the
   set into the `renderSlot` map call.

4. **`renderBinderTile(displayBinder, newSlotIds = new Set())`** (brief §5.1d) —
   forwards the set into the `renderSection` map call.

5. **`render(binderSnapshot, changelog)`** (brief §5.1e) — computes
   `const newSlotIds = computeNewSlotIds(changelog);` right after `renderChangelog(changelog)`,
   and passes it through the `displayBinders.map(b => renderBinderTile(b, newSlotIds))` call.

`bucketSectionsIntoDisplayBinders` was left untouched per the brief's explicit note —
it only regroups sections and never renders a slot, so it doesn't need the set.
`renderChangelog` was **not** touched (brief §1 hard requirement).

### `web/pokedex-binder-site/styles.css`

All new rules, inserted after the `.slot.empty` block and before the skeleton section
(brief §5.2), zero existing rules modified:

- `.slot.filled.slot--new` — intensifies the existing gold hairline `box-shadow`
  channel (same property, stronger alpha + added outer glow layer) rather than
  introducing a second visual channel.
- `.slot.filled.slot--new:hover` inside the existing `@media (hover: hover)` guard —
  mirrors the plain `.slot.filled:hover` pattern already in the file.
- `.slot-badge` — the gold pill: `position: absolute; top: 6px; right: 6px`,
  `background: var(--gold)`, text `#201a05`, `999px` radius, uppercase, `0.05em`
  tracking, `pointer-events: none` (so clicks fall through to the slot's existing
  click-to-zoom handler — verified, see below), and the `badge-pop` entrance animation.
- `@keyframes badge-pop` — fade + scale from `0.6` to `1`, `0.35s ease-out`.
- One line added to the **existing** `@media (prefers-reduced-motion: reduce)` block:
  `.slot-badge { animation: none; }` — explicit belt-and-suspenders per brief §2/R4,
  on top of the pre-existing global `* { animation-duration: 0.001ms !important }`
  rule that already neutralizes it.

### `web/pokedex-binder-site/index.html`

No changes, as specified in brief §5.3. Confirmed nothing needed adding.

---

## Judgment calls

- **None of substance.** The brief's §5 code blocks were implemented essentially
  verbatim (line-number references in the brief matched the current file state
  closely enough that no re-derivation was needed). The only deviations from the
  brief's literal snippets are cosmetic (e.g. matching existing indentation/quote
  style already in the file), not behavioral.
- Kept the pill's visible text as `New` (title case in markup, uppercased via CSS
  `text-transform`) per the brief's explicit note in §5.1b, rather than writing `NEW`
  directly into the markup.

---

## Verification

Used `preview_start` against the existing `.claude/launch.json` config
(`pokedex-binder-site`, python http.server on :4173) and drove the page with
`preview_eval` — screenshots were skipped per the task instructions (unreliable in
prior passes this session); all checks below are DOM/computed-style assertions.

**Environment quirk hit and worked around:** the plain `<script src="app.js">` /
`<link href="styles.css">` tags have no cache-busting query string, and the preview
browser served a stale cached copy of both files across reloads (confirmed by
diffing `window.renderSlot.toString()` against a fresh `fetch(..., {cache:'no-store'})`
of the same file — the executing function was the pre-change single-arg version
while the on-disk/served-fresh file already had the new signature). This is a
pre-existing characteristic of the site's cache-busting (or lack thereof), not a bug
introduced by this change. Worked around by fetching the file with `cache: 'no-store'`
and `eval`-ing it into the page's global scope inside `preview_eval`, then re-invoking
`render()` directly with controlled inputs — this exercises the exact same code path
end-to-end (`render` → `renderBinderTile` → `renderSection` → `renderSlot`) without
relying on a browser-cache-sensitive full page reload.

Checks performed, all passing:

1. **Real data, multi-entry changelog** (`changelog.json`, 2 entries, latest has one
   `ADDED` change for `oricorio-baile`): exactly 1 `.slot-badge` rendered, on the
   correct slot; `aria-label` correctly suffixed " — new in latest update".
2. **Older-entry exclusion:** simulated a 2-entry changelog where `bulbasaur` is
   ADDED in the *older* entry and `venusaur` is ADDED in the *latest* entry — only
   `venusaur` got `slot--new` / a badge; `bulbasaur` did not.
3. **REPLACED triggers badge:** simulated `charizard` as `REPLACED` in the latest
   entry — badge and `slot--new` both applied, same as ADDED.
4. **REMOVED excluded:** included a `REMOVED` change in the latest entry's changes
   alongside an ADDED one — only the ADDED slot got a badge; total badge count matched
   expectation (verified via `computeNewSlotIds` Set contents, not just DOM count).
5. **First-publish suppression:** single-entry changelog (even with real changes
   inside it) → 0 badges, 0 `.slot--new` across the whole rendered binder.
6. **Null changelog:** `render(binderSnapshot, null)` → 0 badges, no console errors,
   grid renders normally (matches criterion 5).
7. **Malformed changelog** (`computeNewSlotIds({ entries: 'oops' })` called directly)
   → returns an empty Set, confirming the `Array.isArray` guard works. (Note: calling
   the *existing, untouched* `renderChangelog` with the same malformed input throws —
   that is pre-existing behavior in code this brief explicitly says not to touch, not
   a regression from this change; `computeNewSlotIds` itself is fail-safe as required.)
8. **Empty slots never badged:** `document.querySelectorAll('.slot.empty .slot-badge')`
   returned 0 in every scenario above.
9. **Computed styles on `.slot-badge`:** `position: absolute`, `top: 6px`, `right: 6px`,
   `background: rgb(245, 196, 81)` (`--gold`), `color: rgb(32, 26, 5)`, `border-radius: 999px`,
   `text-transform: uppercase`, `pointer-events: none` — all match brief §5.2 exactly.
10. **Computed `box-shadow` on `.slot.filled.slot--new`:** confirmed three-layer
    shadow (drop shadow + intensified ring + outer glow) present, distinct from the
    plain `.slot.filled` single-ring shadow.
11. **Click-through to lightbox:** clicked a badged slot directly (`slot.click()`);
    `#lightbox` lost its `hidden` attribute and `#lightbox-img.src` populated
    correctly — `pointer-events: none` on the pill does not block the slot's own
    click handler.
12. **Mobile 3-col crowding (360px viewport):** badge bounding box measured at
    ~24×10.5px against an ~87×120px card, inset ~7px from top/right — clears the
    card's rounded corner with margin, well under the brief's "under 20% of top edge"
    target.
13. **Reduced-motion rule present:** inspected the live `CSSStyleSheet` rules and
    confirmed a `.slot-badge { animation: none; }` declaration exists inside the
    `@media (prefers-reduced-motion: reduce)` block, in addition to the pre-existing
    global `*` rule.

No console errors were logged during any of the above scenarios (checked via
`preview_console_logs`).
