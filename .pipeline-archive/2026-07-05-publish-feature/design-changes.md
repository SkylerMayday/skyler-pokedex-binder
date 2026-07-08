# Design Changes — Implementer Stage (2 of 4, re-skin pass)

Implements `.pipeline/design-brief.md` in full ("The Collector's Shelf").

---

## `index.html`

- Added `skeleton-shelf` class to the existing `#skeleton-wrap` (brief §4, HTML item 1) — markup-only, no JS touch. `hideSkeleton()` still removes the whole `#skeleton-wrap` element unchanged.
- No other HTML changes (header, changelog, lightbox, footer untouched, per brief).

## `app.js` (presentational wrapper only, per guardrail)

**Untouched, verified byte-identical:** `loadJson`, `overallCompletion`, `formatPublishedAt`, `barHtml` (incl. pct math), `escapeHtml`, `changeIcon`, `renderChangeList`, `bucketSectionsIntoDisplayBinders`, `displayBinderCompletion`, the `KNOWN_GENERATION_NAMES`/`SECTION_*` constants, `attachBinderTileHandlers`, `openLightbox`/`closeLightbox`/`attachLightboxHandlers`/`initLightboxChrome`, `triggerBarFillAnimations`, `hideSkeleton`, `init`. Confirmed via grep that every guardrail function name still exists with its original signature and only `renderBinderTile` and one line inside `render()` changed.

1. **`render()`** — wrapped the joined tile output in `<div class="shelf-wrap"><div class="shelf">...</div></div>` exactly per brief §4/app.js item 1. `attachBinderTileHandlers(main)` and `attachLightboxHandlers(main)` still receive `main` and still find their targets (`.binder-tile-toggle`, `.slot.filled`) as descendants — confirmed by testing toggle clicks and lightbox opens after the wrapper change.
2. **`renderBinderTile()`** — re-skinned markup only, per brief §4/app.js item 2:
   - Added a per-tile spine color class via a new `SPINE_COLOR_CLASS_BY_ID` lookup (`display-pokedex`→oxblood, `display-regional`→forest, `display-alternate`→navy, `display-mega`→tobacco, `display-vmax`→slate) with `spine--other` (plum) as the fallback for any unrecognized id (the "Other" bucket) — derived purely from `displayBinder.id`, which was already available, no new data read.
   - Wrapped `.binder-tile-name` + `.binder-tile-count` in a new `.binder-label-plate` span, per the brief's suggested restructuring.
   - Kept the `.binder-tile-chevron` span (still present, restyled in CSS as a small down-caret) rather than removing it — brief left this as implementer's choice.
   - Preserved everything the brief mandates: the `<button class="binder-tile-toggle">` element and its `aria-expanded`/`aria-controls`, the `.binder-tile-body` with `id="binder-body-${id}"` and `hidden` toggling, `data-binder-id`, the `.open` class hook. `renderSection`/`renderSlot` output inside the body is completely unchanged.
3. Nothing else in `app.js` changed.

## `styles.css` (bulk of the work)

1. **Tokens** — added the full wood/leather/label token set from brief §2.5 verbatim (`--wood-1/2/3/--wood-hi/--grain`, `--spine-oxblood/forest/navy/tobacco/slate/other`, `--spine-edge/--spine-sheen`, `--label-plate/--label-plate-2/--label-text/--label-border`). No existing token removed or remapped.
2. **`.shelf-wrap` / `.shelf` layout — RISK 1 mechanism (see "RISK 1" section below for why this deviates from the brief's suggested primary approach).**
3. **`.spine`** (re-skinned `.binder-tile-toggle`) — 104×216px desktop (96×184 mobile), vertical leather gradient per color class (`color-mix` blend of the base `--spine-*` token toward black/white for the cylindrical sheen, centered per brief §2.2), `--spine-edge` border, top-corner `--radius` / bottom-corner `--radius-sm`, inset top highlight + two faint ring-band pseudo-elements, `:focus-visible` gold outline preserved.
4. **`.binder-label-plate`** — cream gradient (`--label-plate` → `--label-plate-2`), `--label-border` inset, `--radius-sm`, small inset shadow. Name (`--label-text`, 1rem/600) and count (`--label-text`, 0.8rem, `tabular-nums`) both render on the plate only, never directly on leather, per §2.5's contrast mandate.
5. **Open state** — `.binder-tile.open .spine` lifts `translateY(-6px)` with a stronger shadow; `.binder-tile.open .binder-label-plate` gains a 2px gold ring. `.binder-tile-body` gets a 2px gold top border as the "belongs to the lifted spine" connector, per §2.3 — no fold/3D animation, just the existing reveal.
6. **Hover** — `@media (hover:hover)` spine lift `translateY(-4px)` + shadow bloom.
7. **Skeleton (RISK 4)** — added `.skeleton-shelf` (flex row, spine-shaped `.skeleton-tile` overrides at 104×216) so the loading placeholder matches the shelf's shape instead of the old horizontal bars. Shimmer keyframes untouched.
8. **Chevron** — kept in markup, restyled smaller (8×8px) as a down-caret under the label; rotate-on-open rule kept as-is.
9. **Mobile `@media (max-width:480px)`** — shelf padding/gap drop a step, spines shrink to 92×184 (still ≥ the 8pt-grid minimum called out in the brief), body padding drops a step. Verified at 375px (see Testing).
10. **`prefers-reduced-motion: reduce`** — extended the existing block to neutralize `.spine`, `.spine:hover`, `.binder-tile.open .spine`, `.binder-label-plate`, `.binder-tile.open .binder-label-plate` (`transition: none; transform: none`), so final open/closed states hold with zero motion.

---

## RISK 1 — layout mechanism actually used (deviates from `display: contents`)

The brief's suggested primary approach was `.binder-tile { display: contents }` so the tile's own button/body children participate directly in `.shelf`'s flex row and normal block flow respectively, with zero DOM/logic restructuring.

**I implemented this first, then discovered a real rendering bug in this environment**: with `.binder-tile` set to `display: contents`, the descendant selector `.binder-tile.open .spine` (and `.binder-tile.open .binder-label-plate`) matched via `Element.matches()` and appeared correctly in `document.styleSheets` — but never applied in `getComputedStyle()`, and, critically, produced **zero visual movement** verified via `getBoundingClientRect()` (not just a computed-style read artifact). An identical rule applied correctly to an identical class combination on a plain (non-`contents`) wrapper. I did not chase this further once I had a working alternative, since brief §3 (RISK 1) explicitly pre-authorizes exactly this fallback:

> "If a clean grid break-out proves fiddly, the acceptable fallback is: spines in a flex-wrap row inside `.shelf`... each tile keeps its own body but bodies are visually stacked under the shelf using `order`/DOM flow."

**Mechanism shipped:** `.binder-tile` is a normal (non-`contents`) flex item — `display: flex; flex-direction: column` — inside `.shelf` (`display: flex; flex-wrap: wrap`). `.binder-tile-body` gets `flex-basis: 100%`, the standard "force a full-width break to a new wrapped row" trick inside a wrapping flex container: when a tile's body is un-hidden, it and everything after it in DOM order flows onto its own full-width row beneath the spines, while the closed-state spines still sit side-by-side on the shelf. This keeps `.binder-tile` as the exact same button+body owner it always was — `attachBinderTileHandlers`, `data-binder-id`, `aria-expanded`, and `hidden` toggling are all completely untouched — and it fixed the transform/box-shadow application issue immediately (confirmed both approaches with the same CSS rules; only the `display: contents` wrapper broke it).

**Second, unrelated environment quirk found during the same investigation (documented for the Tester):** in this preview harness, CSS transitions do not tick forward on their own — `Animation.currentTime` stays frozen at `0` with `playState: "running"` after a class toggle, so `getComputedStyle` reads the *pre-transition* value until the animation is manually finished (`animation.finish()`) or enough real time passes with an actively-painted tab. This affected verification of the lift/shadow motion, not the underlying CSS: forcing the animation to finish confirmed `translateY(-6px)` and the box-shadow both apply exactly as authored. This is very likely the same root cause behind this environment's known-unreliable `preview_screenshot` tool (no live compositor tick) — not a defect in the shipped code.

## Other judgment calls

- **Chevron kept, not removed** — brief allowed either; kept it as a small supplementary "open" cue beneath the label since it was already wired to `.open` and costs nothing to retain.
- **Leather gradient technique** — used `color-mix(in srgb, var(--spine-x) NN%, black/white)` blended into a 5-stop horizontal gradient for the cylindrical sheen (dark edges, lighter center third), rather than hand-picking separate hex stops — keeps each spine color defined by a single token and the sheen shape identical across all 6 colors.
- **Spine sizing** — 104×216 desktop / 92×184 mobile, both chosen within the brief's specified ranges (~96–120×200–240 desktop, min-width so it doesn't collapse) and kept on the 8pt grid.
- **`flex-basis: 100%` break vs. absolute positioning** — chosen over any `position:absolute` breakout because it needs no manual height/offset bookkeeping as multiple bodies open/close and as the shelf wraps to different row counts at different widths; it degrades correctly at every viewport tested (375px, 1280px) with multiple tiles open simultaneously (multi-open behavior from v1 preserved untouched).

## Testing / verification (environment constraints)

Used `preview_start` (existing `.claude/launch.json` config), then `preview_eval`/`preview_inspect`/`preview_snapshot`/`preview_resize` — `preview_screenshot` timed out in this sandbox exactly as flagged for prior stages, so all verification below is DOM/computed-style/bounding-rect based:

- **Structure:** confirmed `.shelf` (flex, wrap) contains all 5 `.spine` buttons with correct classes (`spine--oxblood/forest/navy/tobacco/slate`); confirmed 5 real binder names render exactly as listed in the brief's acceptance checks: "Pokédex", "Regional Variants", "Alternate Forms", "Mega Evolutions", "VMax".
- **Desktop (1280px):** no horizontal scroll (`scrollWidth === clientWidth === 1265`); spine dimensions computed at 104×216px as specced.
- **Narrow (375px):** no horizontal scroll (`scrollWidth === clientWidth === 375`); spines wrap to **2 shelf rows** (confirmed via distinct `getBoundingClientRect().top` values); all 5 spine widths computed at 92px; every binder name wraps without truncation (`scrollWidth <= clientWidth` on each `.binder-tile-name`, verified per-name) — "Regional Variants", "Alternate Forms", "Mega Evolutions" wrap to 2 lines (37px height vs. single-line 18px for "Pokédex"/"VMax"), matching brief §RISK 3's required behavior; name font-size held at 16px (1rem) and count font-size at 12.8px (0.8rem) — the brief's specified floor, never smaller.
- **Contrast:** `.binder-tile-name` computed color `rgb(43,36,25)` = `#2b2419` = `--label-text`, on the `--label-plate`/`--label-plate-2` cream gradient — matches the brief's ~11:1 claim by token identity (same tokens, unchanged).
- **Interaction unchanged:** toggled a spine via a dispatched click event — `.binder-tile` gains `.open`, `aria-expanded` flips to `"true"`, `.binder-tile-body[hidden]` is removed, body content (nested `renderSection` output) is present and correct. Confirmed multi-open still works (opened a second tile independently). Confirmed grep of guardrail function signatures shows no unintended edits.
- **Open-state visual (motion mechanics):** confirmed via `Animation.finish()` that `.binder-tile.open .spine` correctly resolves to `translateY(-6px)` (6px upward shift measured via `getBoundingClientRect()`) and the stronger box-shadow, and `.binder-tile.open .binder-label-plate` gets the gold ring — see the RISK 1 section above for why animations needed to be force-finished to observe this in-sandbox.
- **6-col grid cap / lightbox / changelog untouched:** confirmed `.slot-grid` still resolves to `repeat(3, 1fr)` under the existing 480px mobile breakpoint; confirmed a filled slot still opens the lightbox with the correct `imageUrl`, sets `body.style.overflow = "hidden"`, and `Escape` closes it and restores overflow — all via the pre-existing, untouched `app.js` functions.
- **`file://`-style fallback path:** confirmed via network log that `binder.json`/`changelog.json` are requested first (server in this run actually had real files present, 200 OK) with the `sample-*.json` fallback path still wired identically to v1 (unchanged `loadJson`).
- Not independently re-verified in this pass (already covered by v1 and untouched by this pass, per the guardrail): true `file://` protocol load, CDN-blocked font fallback, real device touch-hover.
