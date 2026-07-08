# Design Changes — Implementer Stage (2 of 4)

Implements `.pipeline/design-brief.md` in full, including the mandatory addendum (§5).

---

## `index.html`

- Added Google Fonts preconnect + stylesheet `<link>` for **Outfit** (400/600/700) — brief §4.1 / RISK 1. Font list in CSS puts Outfit first, full system stack after, so a blocked CDN degrades silently (verified — see Testing).
- Added `<meta name="theme-color" content="#0e0f13">` — §4.2.
- Restructured header into `.header-inner` > `.header-title-row` (pokeball mark + `<h1>`) — §4.3. The hero stat block and bar are injected by `app.js`'s `render()` since the header's `innerHTML` is rewritten there anyway (brief note: "JS still rewrites header innerHTML in render() — mirror this structure there").
- Added a `#skeleton-wrap` inside `#binders` with 5 skeleton tiles, present at first paint (no JS injection needed) — §4.4. Removed by `hideSkeleton()` once data resolves.
- Added one reusable `#lightbox` container (hidden by default, `role="dialog"`, `aria-modal="true"`) per addendum §5.C — built once in HTML rather than injected by JS, since the brief allows either.
- Footer text unchanged.

## `styles.css`

- Replaced `:root` tokens with the full dark "Display Case" palette from brief §2 (color, spacing `--s1`..`--s8`, `--radius`/`--radius-sm`). Kept the original variable **names** (`--bg --surface --text --text-muted --border --bar-track --bar-fill --empty-bg`) remapped to new values so any code/inline styles referencing them still work, and added the new tokens (`--surface-2 --border-strong --text-faint --gold --added --replaced --removed`).
- Body font: `"Outfit", -apple-system, …` — Outfit first, system stack fallback, per RISK 1 mitigation.
- Header → hero band: flex `.header-inner`, pure-CSS `.pokeball-mark` (gradient halves + centered button), `backdrop-filter: blur(10px)` with `@supports not (...)` fallback to solid `--surface` (RISK 4). `.hero-stat` at 2.75rem gold tabular-nums, `.hero-frac` muted beneath, full-width bar under both.
- Bars: taller (10px), gradient fill `--pokeball-red → --red-deep`, inner highlight via `box-shadow: inset`. `barHtml`'s own pct math untouched (see app.js notes).
- Changelog: `--surface` well, `3px` gold left accent rule, `.latest-pill` (gold pill, dark text) added to the latest entry's summary line, brightened `--added/--replaced/--removed` colors, dark-restyled `<details>`.
- **Binder tiles** (new, addendum §5.A): `.binder-tile` (well), `.binder-tile-toggle` (real `<button>`, flex row, hover/focus-visible states), `.binder-tile-chevron` (rotates open via `.open` class), `.binder-tile-body` (toggled via `hidden` attribute, no display:none needed beyond that).
- Sections → wells: `section.binder-section` now uses `--surface-2` (one step lighter than the binder tile's `--surface`, so nesting reads as a visible sub-level) with `--radius-sm`, `.section-header-row` holding the title + new right-aligned `.section-pct`.
- **Grid — 6-column hard cap** (addendum §5.B): `grid-template-columns: repeat(auto-fill, minmax(max(90px, calc((100% - 5 * var(--gap)) / 6)), 1fr))`. Verified by computed-style inspection (see Testing) that this yields exactly 6 columns at 700px, 1440px, and holds at 6 (never 7+) at any width above ~650px, degrades to fewer columns (down to the existing 3-col mobile breakpoint) as width shrinks — ceiling, not fixed count, per spec.
- Slots: `.slot.filled` gets warm 1px border + soft box-shadow (RISK 3) and `cursor: zoom-in`; hover lift wrapped in `@media (hover:hover)` so touch devices don't get a stuck hover state. `.slot.empty` gets dashed `--border-strong` edge + a `::before` pseudo-element Poké Ball watermark at 7% opacity (RISK 2) using the same gradient-halves trick as the header mark, purely decorative (`pointer-events: none`).
- Skeleton shimmer keyframes + changelog fade-in keyframe, both neutralized under `@media (prefers-reduced-motion: reduce)` along with all other transitions/animations (global `!important` override plus explicit `.bar .fill` / `.slot.filled:hover` resets so final states hold without motion).
- Mobile `@media (max-width:480px)`: header stacks to column, hero number drops to 2.1rem, section/tile padding drops to `--s3`. Existing 3-col grid media query kept as-is (still the mobile floor).
- **Lightbox** (addendum §5.C): fixed full-viewport dimmed backdrop, centered image capped at `min(90vw,720px)` / `85vh`, circular close button, `hidden` attribute toggling.

## `app.js` (presentational-only, per guardrail)

**Untouched, verified byte-identical:** `loadJson`, `formatPublishedAt`, `barHtml` (including its pct math), `escapeHtml`, `changeIcon`, `overallCompletion`, `renderChangeList`. Confirmed by direct read-back after edits.

Changes:
1. `renderSlot()` — filled branch now wraps the `<img>` in a `.slot.filled` div with `tabindex="0" role="button"` and `data-image-url` / `data-image-alt` (reusing the already-fetched `imageUrl` — no new fetch). Empty branch structurally unchanged (existing `.dex-number`/`.slot-name` classes reused by the new watermark CSS).
2. `renderSection()` — adds a `.section-header-row` wrapper and a `.section-pct` span computed the same way `barHtml` computes pct (`Math.round(filled/total*100)`), not by changing `barHtml` itself.
3. `renderChangelog()` — added `<span class="latest-pill">Latest</span>` inside the latest entry's summary. No change to which entries are shown, their order, or `renderChangeList`.
4. **New, addendum §5.A — display-only section bucketing:**
   - `KNOWN_GENERATION_NAMES` / `SECTION_REGIONAL_VARIANTS` / etc. mirror the Kotlin-side names in `SnapshotSections.kt` (`GENERATIONS`, `SECTION_REGIONAL_VARIANTS`, `SECTION_ALTERNATE_FORMS`, `SECTION_MEGA_EVOLUTIONS`, `SECTION_VMAX`) — these are display-side string constants only, not schema, and don't read from or alter the fetched JSON's shape.
   - `bucketSectionsIntoDisplayBinders(binderSnapshot)` flattens `binderSnapshot.binders.flatMap(b => b.sections)` (all already-parsed sections, regardless of which snapshot-level "binder" id they came from) and buckets by name match into 5 named tiles + a 6th "Other" fallback for anything unrecognized, so a future new section name degrades safely instead of crashing or silently vanishing.
   - `renderBinderTile()` renders a closed-by-default `<button class="binder-tile-toggle">` plus a `.binder-tile-body` containing the already-rendered inner `renderSection()` output for every section in that bucket — **present in the DOM at first paint, no click-triggered fetch or re-render**, satisfying "closed tiles present at first paint, not injected only after a click."
   - `attachBinderTileHandlers()` toggles `.open` / `hidden` on click — pure DOM state flip, no data touched. Module-level `binderTileOpenState` object persists which tiles are open only within a session's `render()` calls (not persisted across reloads — see Judgment Calls).
5. **New, addendum §5.C — lightbox:** `openLightbox`/`closeLightbox`/`attachLightboxHandlers`/`initLightboxChrome`. Reads `slot.dataset.imageUrl`/`imageAlt` set in step 1 above — same `imageUrl` already in memory from the initial fetch, no new network call. Wired to filled slots only (`.slot.filled` selector — empty slots never get a listener). Closes on backdrop click (`e.target === lightbox`), the explicit close button, and `Escape` (global `keydown` listener). Locks `document.body.style.overflow` while open, restores on close.
6. **New, presentational bar-fill animation:** `triggerBarFillAnimations()` — since `barHtml()` (untouched) emits the final width inline via `style="width:${pct}%"`, this helper stashes each fill's target width in `dataset.targetWidth`, snaps `style.width` to `0%` with `transition:none`, forces a reflow, then restores the target width on the next animation frame so the existing CSS `transition: width` animates it. Does not read or alter the pct math itself.
7. `hideSkeleton()` removes `#skeleton-wrap` once `init()`'s `Promise.all` resolves (success or fallback-to-sample), regardless of which JSON source (real or sample) served the data.
8. `init()` now also calls `initLightboxChrome()` once at startup (idempotent, no data dependency) and `hideSkeleton()` right after the fetch resolves, before the `!binderSnapshot` failure check — so the skeleton clears even on total load failure rather than lingering forever.

## Judgment calls (brief left to Implementer discretion)

- **Accordion model:** chose **multi-open** (each binder tile toggles independently, opening one does not close others) over single-open-at-a-time. Less state to track (a plain map of id→bool vs. tracking "which one is currently open"), and nothing in the brief implies only one binder should ever be viewable at once — a collector comparing two binders side-by-open seemed like the more natural use case.
- **Binder tile visual affordance:** used a chevron (rotates 90°→-45° via `.open` class) rather than a "binder spine" illustration — simpler, matches the restrained-decoration instruction ("keep it minimal," not a *new* independent risk per the addendum's own guidance).
- **Section well nesting:** gave `section.binder-section` (inside an open Pokédex tile) `--surface-2` instead of reusing `--surface`, so the nested per-generation sections visually read as one level "deeper" than the binder tile itself (`--surface`) — this wasn't specified numerically in the brief but follows its own elevation logic (bg → surface → surface-2 as a depth ladder).
- **Skeleton tile count:** 5 skeleton placeholders (matching the 5 primary binders) rather than trying to guess final content shape — arbitrary but harmless, since it's removed as soon as real content renders.
- **"Other" bucket ordering:** appended last, only rendered if non-empty, so it never appears as a dead/empty 6th tile in the current data (all 13 known section names always match one of the 5 primary buckets today).

## Testing / verification (environment constraints)

No `binder.json`/`changelog.json` exist in the repo (by design — those are the app's publish output); verified against `sample-binder.json`/`sample-changelog.json` via the existing `loadJson` fallback path, which is the documented way this page is meant to be exercised locally.

Used the Claude Preview tool (`preview_start` against a local `python -m http.server` serving `web/pokedex-binder-site/`, config added at `.claude/launch.json`) plus `preview_eval`/`preview_snapshot`/`preview_inspect` (screenshot tool itself timed out in this sandbox, but DOM/computed-style inspection fully substituted for it):

- Confirmed real HTTP fetch of `binder.json`/`changelog.json` 404s and falls back to the sample files (network log), proving the `file://`-style fallback path still works with the new JS.
- Confirmed Google Fonts request succeeds in this environment (200 for the Outfit woff2); font-family fallback order was verified by reading the CSS rather than by simulating a blocked CDN, since this sandbox has network access — the fallback chain (`"Outfit", -apple-system, …`) is unconditional and doesn't depend on JS, so a blocked/offline CDN cannot break layout by construction.
- Accessibility snapshot (`preview_snapshot`) confirmed: hero stat renders "0%" / "3/1025 complete" from the sample data's 3-filled-of-1025 pokedex math (unchanged `overallCompletion`); one "Pokédex 3/5" binder tile renders closed by default; clicking it (`preview_click`) reveals the nested "Generation I" section at "60%" (3/5), matching `renderSection`'s pct math; filled slots expose as `button` role with correct accessible names ("View Bulbasaur full size" etc.); the empty slot has no role/tabindex (confirmed via `preview_eval`, ruling out accidental lightbox wiring on empty slots).
- Lightbox: clicked a filled slot, confirmed via `preview_eval` that `#lightbox` becomes visible with `img.src` set to the correct card's real `imageUrl` (`base1/44_hires.png` for Bulbasaur) and `document.body.style.overflow` becomes `"hidden"`; dispatched a synthetic `Escape` keydown and confirmed the lightbox re-hides and body overflow is restored to `""`.
- 375px viewport: `document.documentElement.scrollWidth === clientWidth` (no horizontal scroll); grid computed to 3 columns via the existing mobile breakpoint.
- 6-column cap (addendum §5.B): computed `grid-template-columns` inspected via `preview_eval` at 700px and 1440px viewports — both yield exactly 6 track values (~93px and ~143px respectively); at 500px (between the 480px mobile floor and the point where 6×90px stops fitting) it correctly falls back to 4 columns rather than clipping or overflowing to 7 — confirms "ceiling of 6, never more, degrades on mobile" holds across the full requested range.
- `#latest` element presence confirmed (anchor-scroll target still exists for the existing hash-scroll behavior in `render()`, which was not modified).
- Verified via direct file read-back that `loadJson`, `formatPublishedAt`, `barHtml`, `escapeHtml`, `changeIcon`, `overallCompletion` are unmodified from the pre-implementation version.

Not independently verified in this environment (flagged for Tester stage): true `file://` protocol load (only tested via local HTTP server, since the preview tool operates over HTTP), an actual CDN-blocked scenario (network wasn't restricted in this sandbox), and real device touch-hover behavior for `@media (hover:hover)`.
