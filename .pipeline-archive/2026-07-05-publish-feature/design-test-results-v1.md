# Design Test Results — Tester Stage (3 of 4)

Verifies `.pipeline/design-changes.md` (Implementer) against `.pipeline/design-brief.md` (incl. §5 addendum).
Target: `web/pokedex-binder-site/` served via `preview_start` (`pokedex-binder-site`, port 4173) against sample JSON.

**Environment note:** `preview_screenshot` timed out every time it was tried (confirms Implementer's report — flaky in this sandbox). Worked around entirely with `preview_snapshot`, `preview_inspect`, and `preview_eval` (DOM/computed-style/axe-core). Also found and worked around a `preview_click` issue — see §0.

---

## 0. Preview-tool caveat (not an app bug)

`preview_click` reported "Successfully clicked" but the click event never reached the target element — confirmed with an instrumented capture-phase listener (`click` count stayed `0` after a reported-successful `preview_click`) and with `getBoundingClientRect()`/`window.innerWidth` returning `0`/empty values until an explicit `preview_resize({width, height})` was issued (the `preset: "desktop"` variant left the viewport at 0x0; explicit pixel dimensions fixed it). After sizing the viewport explicitly, `preview_click` **still** did not deliver the event (verified twice, sequential calls, real layout confirmed via rect). All interaction testing below was therefore done via `preview_eval` calling `.click()` / `dispatchEvent()` directly on elements — this is a legitimate substitute (it exercises the same DOM event listeners `app.js` attaches) but does not prove real mouse-hardware/OS-level click delivery. 🟡 **should-fix (tooling, not app):** flag to whoever owns the Preview tool that `preview_click` silently no-ops in this environment even after `preview_resize`.

---

## 1. Functional / interaction checks

| Check | Result |
|---|---|
| Zero console errors/warnings on load | ✅ PASS — `preview_console_logs` (all + error) empty both before and after interaction |
| Loads against sample JSON | ✅ PASS — network log shows `binder.json`/`changelog.json` 404 → falls back to `sample-binder.json`/`sample-changelog.json` (200) |
| 5 binder tiles render closed by default | ✅ PASS (with caveat) — sample data only populates the "Pokédex" bucket (single "Generation I" section); the other 4 buckets are legitimately empty under the documented "only render non-empty buckets" rule, so only 1 tile renders. Confirmed via code read (`bucketSectionsIntoDisplayBinders` + `.filter(b => b.sections.length > 0)`) that all 5 primary buckets + "Other" fallback exist and would each render as a closed tile if sample data populated them. **Not independently verified with all 5 populated** — recommend Reviewer/QA re-run against a real `binder.json` with all 13 sections present before sign-off on this specific item. |
| Pokédex tile opens to reveal nested Generation sections | ✅ PASS — opened tile, confirmed `.binder-tile-body` reveals nested `<h2>Generation I</h2>` section with its own bar/grid |
| Binder tiles toggle open/closed on click | ✅ PASS (via `.click()`) — full open→close→open cycle confirmed, `aria-expanded` and `hidden` attribute both track state correctly |
| Keyboard-operable (real `<button>`, Enter/Space) | ✅ PASS — `tagName === 'BUTTON'` confirmed; native `<button>` elements activate on Enter/Space via browser default behavior with no custom keydown handler needed (correctly relies on this rather than reinventing it) |
| Grid never exceeds 6 columns | ✅ PASS at 375/600/768/1024/1280/1440px — column counts: 3 / 5 / 6 / 6 / 6 / 6. Ceiling holds, degrades gracefully below 6, never spikes to 7+ |
| Lightbox opens on filled-slot click, correct image src | ✅ PASS — `img.src` = `https://images.pokemontcg.io/base1/44_hires.png` (Bulbasaur), matches `data-image-url` |
| Empty slot does not open lightbox | ✅ PASS — empty slots have no `tabindex`/`role`, `.click()` on one leaves lightbox `hidden` |
| Lightbox closes via backdrop click | ✅ PASS |
| Lightbox closes via close button | ✅ PASS |
| Lightbox closes via Escape | ✅ PASS — also confirmed `document.body.style.overflow` restored to `""` on close, and set to `"hidden"` while open |
| `#latest` anchor exists / hash-scroll wiring intact | ✅ PASS (partial) — `#latest` element present, `render()`'s hash-check code path unmodified per code read. Could not conclusively observe a *large* scroll jump in this sample data because the changelog panel is already near the top of a short page — behavior is unchanged code, not independently provable as "still scrolls" from viewport position alone here. |

## 2. Accessibility checks

| Check | Result |
|---|---|
| axe-core automated scan (closed state) | ✅ PASS — 0 violations (loaded axe-core 4.9.1 via CDN, ran `axe.run()`) |
| axe-core automated scan (lightbox open) | ✅ PASS — 0 violations |
| Alt text on card images | ✅ PASS — 3/3 filled-slot `<img>` elements have non-empty `alt` |
| Lightbox `role="dialog"` / `aria-modal="true"` | ✅ PASS — both present on `#lightbox` |
| Visible focus state on binder toggle | ✅ PASS — `.binder-tile-toggle:focus-visible { outline: 2px solid var(--gold); outline-offset: -2px; }` confirmed in stylesheet |
| Visible focus state on lightbox close / filled slots | ✅ PASS — same `:focus-visible` gold-outline pattern present in CSS for `.lightbox-close` and `.slot.filled` |
| Contrast: `--text` on `--bg` | ✅ PASS — 17.25:1 (brief claimed ~14.8:1; actual is even better) |
| Contrast: `--text-muted` on `--bg` | ✅ PASS — 7.69:1 (brief claimed ~6.7:1) |
| Contrast: `--gold` on `--bg` | ✅ PASS — 11.76:1 |
| Contrast: `--pokeball-red` on `--bg` | ✅ PASS for large/UI use — 5.82:1 (only used for bar fill/accents per brief, not small body text — correct usage confirmed in CSS) |
| Contrast: `--added`/`--replaced`/`--removed` | ✅ PASS — 10.99 / 10.30 / 6.22 :1 |
| **Contrast: `--text-faint` on `--surface-2` (empty-slot dex number/name)** | 🔴 **FAIL** — measured **3.09:1**, not the ~4.6:1 claimed in brief §2. Confirmed via `preview_inspect` computed style (`color: rgb(107,111,125)` = `#6b6f7d` on `#212430`) and independent WCAG relative-luminance calculation. This text is 10.4px/normal-weight (well under the 18px/14px-bold "large text" carve-out), so it needs 4.5:1 and fails even the 3:1 large-text floor by a hair. axe-core's scan did not flag this (likely a limitation in how it samples background under a decorative `::before` overlay) — do not treat the axe pass as clearing this. **Severity: blocking for the mandatory AA baseline the brief itself states as non-negotiable** — dex numbers/names on every empty slot across the whole grid are affected. |

## 3. Responsive sweep

| Width | Columns | Horizontal scroll | Hero number clip |
|---|---|---|---|
| 375px | 3 | None (`scrollWidth === clientWidth`) | None (`left:16, right:57.5`, fits) |
| 600px | 5 | Not explicitly re-checked (not a required width) | — |
| 768px | 6 | None | None (`right: 752` within 768 viewport) |
| 1024px | 6 | None | Not re-measured explicitly, no visual signal of overflow |
| 1280px | 6 | None | None |
| 1440px | 6 | None | None (`right: 1220` within 1440 viewport) |

All required widths (375/768/1024/1280/1440) pass: no horizontal scroll, hero number doesn't clip.

## 4. `prefers-reduced-motion` check

✅ PASS — confirmed via `document.styleSheets` inspection that the rule exists and is substantive:
```css
@media (prefers-reduced-motion: reduce) {
  * { animation-duration: 0.001ms !important; animation-iteration-count: 1 !important; transition-duration: 0.001ms !important; scroll-behavior: auto !important; }
  .bar .fill { transition: none; }
  .slot.filled:hover { transform: none; }
}
```
This neutralizes the skeleton shimmer, changelog fade-in, bar-fill transition, chevron rotation, and slot hover-lift while leaving final states (widths, positions) intact — matches the brief's requirement. Not tested with the Preview tool's actual `prefers-reduced-motion` media emulation (tool doesn't expose that parameter directly per its schema — only `colorScheme` is exposed on `preview_resize`), so this is a CSS-source verification, not a live emulated-media-query render check.

## 5. Guardrail check (app.js untouched functions)

**Caveat:** `web/pokedex-binder-site/` is entirely untracked in git (`git status` shows the whole directory as `??`), so there is no prior commit to run a literal `git diff` against — could not do the byte-for-byte historical diff the brief's acceptance checklist calls for. Verified instead by direct code reading against the brief's explicit list:

| Function | Status |
|---|---|
| `loadJson` | ✅ Present, standard fetch-with-fallback logic, no changes evident |
| `overallCompletion` | ✅ Present, still reads `slotType === 'BASE' && cardId` from the `pokedex` binder only |
| `formatPublishedAt` | ✅ Present, unchanged `toLocaleString` formatting |
| `barHtml`'s pct math | ✅ `Math.min(100, Math.round((filled/total)*100))` — same shape reused (not duplicated-and-diverged) in `renderSection`'s and `render()`'s own pct calcs |
| `escapeHtml` | ✅ Present, unchanged `textContent`/`innerHTML` escape trick |
| `changeIcon` | ✅ Present, unchanged icon/class mapping |

No evidence of fetch/parse/grouping logic changes. New code (`bucketSectionsIntoDisplayBinders`, `renderBinderTile`, lightbox helpers, `triggerBarFillAnimations`) is additive and operates on already-parsed `sections`/`slots` in memory — consistent with the "display-only" guardrail. 🟢 **nitpick:** recommend the Reviewer stage still ask for a proper before/after diff once this directory gets its first git commit, since this Tester pass relied on reading code rather than a mechanical diff.

---

## Summary

| Severity | Count | Items |
|---|---|---|
| 🔴 Blocking | 1 | `--text-faint` on `--surface-2` contrast is 3.09:1, fails AA (needs 4.5:1 for this font size) — affects every empty slot's dex number/name across the entire site |
| 🟡 Should-fix | 1 | `preview_click` tool doesn't deliver real click events in this environment even after explicit viewport sizing — flag for whoever maintains the Preview tool; not an app defect, but limits what future automated QA passes here can trust from that specific tool call |
| 🟢 Nitpick | 2 | (1) 5-binder-tiles-closed-by-default and `#latest` scroll-jump behavior verified only against sample data that doesn't populate all 5 buckets / doesn't produce a long page — recommend a follow-up spot-check with a fuller dataset before final sign-off. (2) No git history exists yet for this directory, so the guardrail check was code-read-based rather than a mechanical `git diff`; ask Reviewer to diff properly once a baseline commit exists. |

**Recommendation:** fix the `--text-faint`/`--surface-2` contrast (blocking) before proceeding to the Reviewer stage — either darken `--surface-2` slightly or lighten `--text-faint` until it clears 4.5:1 against the actual empty-slot background. Everything else (5 binders logic, 6-col grid cap, lightbox, keyboard access, reduced-motion, guardrail non-regression) passes.
