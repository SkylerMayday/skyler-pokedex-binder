# Design Review Verdict — "Freshly Slotted" NEW badges

**Stage:** Design Reviewer (4 of 4) — final gate
**Brief:** `.pipeline/design-brief.md`
**Implementer summary:** `.pipeline/design-changes.md`
**Tester results:** `.pipeline/design-test-results.md`

---

## VERDICT: 🟢 SHIP

The feature is correct, on-brand, accessible, and regression-free. I independently
re-read the on-disk `app.js` and `styles.css`, drove the live preview against **real**
`binder.json` + `changelog.json` (not synthetic data), and visually inspected the badge
over the real Oricorio ex full-art card. Everything the Implementer and Tester claimed
holds up. No must-fix items. Two nice-to-have follow-ups, both cosmetic and non-blocking.

---

## What I independently verified (not just trusting prior stages)

### Code matches the two prior stages exactly
Read `app.js` lines 91–256 and `styles.css` lines 631–678 / 843–845 in full. Every
function signature, the `computeNewSlotIds` helper, the threading through
`renderBinderTile → renderSection → renderSlot`, the `.slot-badge` / `.slot.filled.slot--new`
CSS, the `badge-pop` keyframe, and the reduced-motion line are present and identical to
what `design-changes.md` and `design-test-results.md` describe. No drift.

### Real-data behavior (my own live run, cache-workaround applied)
Confirmed the documented stale-cache harness quirk myself: `window.renderSlot.length` was
`< 2` on the served page (old single-arg version executing) despite the on-disk file being
current. Applied the same fix (fetch `cache:'no-store'`, indirect-eval into global scope,
inject fresh `<style>`), then ran `render(binder, changelog)` with the **real** files:
- `computeNewSlotIds(changelog)` → Set of size **1**: `["oricorio-baile"]` — matches the
  real latest changelog entry (1 ADDED change) exactly.
- **1** `.slot-badge` rendered, **1** `.slot--new` class, **0** badges on empty slots.
- `changelog.entries.length === 2`, so first-publish suppression correctly does **not**
  fire on production data.

### R1 — badge over REAL card art (Tester used synthetic data; I checked the real thing)
The badged card is Oricorio ex, `images.pokemontcg.io/me2/110_hires.png` — a **full-art
card**, the hardest case for corner-badge collision. Measured against the real rendered
card (Pokédex tile open, desktop width):
- Card 116×161px; badge 24×11px, inset 7px from top and right.
- Badge occupies **7% vertical penetration**, does **not** cross the centerline, does
  **not** reach the name banner.
- Enlarged the slot and screenshotted: the gold "NEW" pill sits in the top-right HP/energy
  zone as the brief predicted. There **is** minor proximity to the HP text region on a
  full-art card — but the dark outline + drop shadow keep it fully legible over the art,
  and it obscures nothing important (face, name, art focal point all clear). This is
  exactly the accepted-tradeoff the brief called out in R1. **Confirmed acceptable.**

### R2 — REPLACED set-membership / REMOVED exclusion
`BADGED_CHANGE_TYPES = new Set(['ADDED', 'REPLACED'])` structurally includes REPLACED and
excludes REMOVED by construction — a REMOVED slotId can never enter the set regardless of
whether its slot is coincidentally still filled. Correct-by-construction, not
data-dependent. Matches Tester check 4.

### R3 — mobile corner collision
Tester measured 375px; I trust it and the geometry corroborates: at desktop the badge
already clears the corner by 7px with 21% top-edge width, and the 3-col mobile layout uses
*larger* cards, so the absolute-px badge is proportionally smaller and safer there.

### R4 — reduced-motion
`styles.css:843–845` has the explicit `.slot-badge { animation: none; }` inside the
`@media (prefers-reduced-motion: reduce)` block, on top of the pre-existing global `*`
animation-duration override at 828–831. Belt-and-suspenders as specified. Verified by
direct file read.

### First-publish suppression logic — read `computeNewSlotIds` myself
The `entries.length <= 1` guard is not arbitrary: it is the precise implementation of the
brief's §3 reasoning that a badge is a *diff* signal with no meaning when there is no
"before." One entry = first-ever publish = no prior state = every filled card would badge =
zero information. The guard also fail-safes null / non-array `entries` to an empty Set in
the same condition. Logic exactly matches the stated intent. Also correct: the `latest`
is `entries[0]` and only its changes are considered, so a card added in an older entry and
untouched since carries no badge — the "only the newest delta" property the brief wanted.

### Zero regressions (read the code, not just the Tester's table)
`renderChangelog` (app.js:63–89) is byte-for-byte untouched — the `.latest-pill`, "Recent
updates" heading, and change-list rendering are unchanged. The 5-tile bucketing
(`bucketSectionsIntoDisplayBinders`, `DISPLAY_BINDER_DEFS`), the 6-col grid cap
(`.slot-grid` at styles.css:544–549), and the lightbox handlers (app.js:309–349) are all
unmodified by this change — the `newSlotIds` Set is a purely additive trailing parameter
that defaults to empty. My live run reproduced all five tiles and the 6-col grid, and the
Tester confirmed lightbox open/close. No regression surface.

---

## 5-Dimension Critique

**First impression — does it read as "new" at a glance, or as noise?**
Reads as "new" cleanly. Because suppression keeps badges to the genuine delta (1 card in
the real data, not a wall of them), the gold pill is the exception on the grid, which is
exactly what gives it signal value. On a first publish it correctly vanishes entirely. This
is the single most important design call in the brief and it's implemented faithfully — a
lesser version would have lit up all 1000+ cards on the first publish and destroyed the
premium feel. Good.

**Usability.** The badge is decorative-informational, not interactive — `pointer-events:
none` means clicks fall straight through to the existing zoom handler (verified by Tester
check 5/11; the CSS confirms it). Nothing to learn, nothing to misclick. Correct.

**Visual hierarchy — competes with or complements the premium filled-slot glow?**
Complements. The intensified ring reuses the *same* `box-shadow` channel already on
`.slot.filled` (0.12 → 0.55 alpha + a new outer glow layer), so it's a turned-up version
of an existing gesture, not a second competing effect. Edge-treatment (ring) + corner-
treatment (pill) are different visual channels, so they stack without muddiness. On the
enlarged card the brighter frame + pill read as **one** "this is special" statement. This
was the brief's explicit goal and it lands.

**Consistency — genuinely part of the same system as `.latest-pill`?**
Yes. Compared the two rules directly: `.slot-badge` and `.latest-pill` share
`background: var(--gold)`, dark-brown text (`#201a05` / `#201a05`), `999px` radius,
`letter-spacing: 0.05em`, `text-transform: uppercase`, `font-weight: 700`. The only deltas
are `font-size` (0.6rem vs 0.65rem) and the badge's float treatment (outline + shadow +
`position:absolute`), both justified by context (smaller card real-estate; floating over
photographic art vs sitting on a flat panel). The user already learned "gold pill = latest
publish" from the changelog panel; this reuses that learned meaning on the card. Genuinely
one design system, not a bolt-on.

**Accessibility.**
- Contrast 10.65:1 (Tester's WCAG calc) — clears both the 3:1 UI-component bar and the
  stricter 4.5:1 body-text bar. Verified the token values in `:root` match.
- The pill `<span>` is `aria-hidden="true"` and the slot's `aria-label` gains the
  " — new in latest update" suffix, so a screen-reader user learns which cards are new
  *without* double-announcement. I confirmed the live aria-label reads
  "View Oricorio ex — new in latest update full size". Correct S6 implementation.
- Reduced-motion covered (R4 above).

---

## Must-fix before ship
**None.**

## Nice-to-have follow-ups (non-blocking, do not gate this ship)

1. **Badge visible text is `New` (title-case) uppercased via CSS `text-transform`.** This
   is per the brief's §5.1b explicit note and is fine — but worth being aware that a
   screen reader would see "New" (it's `aria-hidden`, so moot) and that anyone reading the
   markup sees `New` while the UI shows `NEW`. Purely cosmetic; leave as-is unless a future
   maintainer finds the mismatch confusing.

2. **Internal brief inconsistency (documentation only, not a code defect):** the brief's §2
   prose says the pill uses `2px 7px` padding (line 47) while its own §5.2 code block says
   `padding: 3px 7px` (line 228). The Implementer correctly followed the authoritative code
   block (`3px 7px`, confirmed in `styles.css:667`). No action needed on the code; flagging
   only so it isn't mistaken for an implementation error in a later audit. The resulting
   badge geometry (24×11px, 7% vertical penetration) is well within all targets either way.

3. **`pctOfTopEdge` measured 21% at current desktop width** vs the brief's "well under 20%"
   aspiration. This is a width-dependent artifact (24px absolute badge on a 116px card at
   this specific breakpoint) and is cosmetically irrelevant — the badge is small, clears
   the corner by 7px, and never crosses the centerline. Not worth a change; noted for
   completeness.

---

## Bottom line
Ship it. The implementation is faithful to the brief down to the token values, the one
genuinely load-bearing design decision (first-publish suppression) is implemented exactly
as reasoned, the badge holds up over real full-art cards, accessibility is handled
properly, and there is zero regression surface because the whole feature is an additive
defaulted parameter. The Tester's 9/9 pass is corroborated by my independent real-data run
and real-card-art visual check. No blocking issues.

**Process note carried forward:** the preview harness's stale-script/stylesheet cache
survives a full `preview_stop`/`preview_start` — any future re-verification in this
environment must apply the fetch-`no-store` + indirect-eval workaround (documented by both
prior stages and reconfirmed by me this pass) or it will silently test the old code.
