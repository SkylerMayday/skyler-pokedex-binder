## What This Is
Android Pokémon TCG binder app (Kotlin/Compose/Hilt/Room) that lets users track which cards they own for every Pokémon slot, with a public web-shareable snapshot on GitHub Pages.

## Project Plan
- [x] Public binder sharing (Phase 1) — shipped and verified live 2026-07-05.
- [x] Web viewer visual redesign (dark "Display Case", leather spines, lightbox) — live 2026-07-05.
- [x] Multi-binder system (schema v7, nav drawer, Connecting Art, Personal Collection, Pocket filter) — built and reviewed 2026-07-06, NOT yet installed/verified on-device.
- [x] Restore-from-snapshot + automatic card name/set backfill — built and reviewed 2026-07-06.
- [x] OG meta tags + changed-card "NEW" badges on the web viewer — shipped; badge design pipeline re-verified SHIP and pushed live 2026-07-08 (~02:27 SGT).
- [x] Housekeeping: ALL unit tests now green — final holdout `CardSearchRepositoryTest` fixed 2026-07-08 (stale query-string mocks predating wildcard `nameQuery` + Pocket exclusion; second test was passing vacuously, made meaningful).
- [x] Spec for next feature APPROVED: card details panel + per-card language (see Next Steps).
- [ ] **Deferred to Skyler:** install current build on his device, try multi-binder/restore/backfill for real (no emulator in dev sandbox). Back up `pokedex_binder.db` or publish first (untested v6→v7 migration + `fallbackToDestructiveMigration()`).
- [ ] `Migration5to6Test`/`Migration6to7Test` never run on a real device — statically/DDL-verified only.
- [ ] ~2 months of app-repo work UNCOMMITTED (master's last commit 2026-05-24; everything since — publish, multi-binder, web/ — is modified/untracked). Possibly deliberate pending on-device verification; needs a decision.
- [ ] Custom domain (SkylerMayday.com → Pages): CNAME + DNS A records, zero urgency.
- [ ] og:image for the web viewer — tracked in the site's README TODO (added 2026-07-08); when an asset exists, wire it into index.html's placeholder comment and flip twitter:card to summary_large_image.

## Current Status
Active

## What's Built
- Main Pokédex binder (1025 base slots) + Alternate Forms, Regional Variants, Mega, VMax sections.
- Dual-API card search: pokemontcg.io (primary, global `-set.series:Pocket` filter) + TCGdex (secondary, merged on name searches).
- Gemini card scanner; Quick scan; Card History (drawer-only).
- Public binder sharing: Publish → binder.json/changelog.json via GitHub Contents API → Discord embed; secrets in EncryptedSharedPreferences.
- Public web viewer (`web/pokedex-binder-site/` → `SkylerMayday/binders-pokedex-binder`, Pages live): Display Case theme, 5 leather-spine binders, 6-col grid, lightbox, OG tags, gold NEW badges (suppressed on first publish).
- Multi-binder system (schema v7): nav drawer with Pokédex / Connecting Art / Personal Collection / Card History; Connecting Art = named groups with grid presets + search-assign + drag-reorder + cascade-delete; Personal Collection = 5 fixed Pokémon, pull-to-refresh, owned-state survives refresh.
- Restore-from-snapshot (overlay-not-rebuild, regression-tested) + automatic on-launch card name/set backfill (race-guarded, 300ms pacing).

## Recent Changes
- **2026-07-08 (this session):**
  1. Confirmed the NEW-badges design pipeline (run ~02:00–02:30 SGT) came back SHIP and the web commit is pushed/synced on the Pages repo.
  2. Fixed `CardSearchRepositoryTest` (last failing test): mocks stubbed the pre-wildcard, pre-Pocket-filter query strings; MockK miss was swallowed by `safeSearch`'s catch → empty results. Updated both stubs to real queries; made the vacuous second test assert real mapping. Full suite green.
  3. Added og:image TODO to the site README.
  4. **Promo-card availability re-check (big finding):** TCGdex now has `svp-213` Feraligatr and `mep-010` Riolu (MEP Black Star Promos, 60 cards) and both return from the `/cards?name=` endpoint the app already merges — assignable via name search today, though both lack scans on TCGdex (blank art until they add images). pokemontcg.io still tops out at SVP #207. CLB Mr. Mime (TCG Classic) on neither API; TCGCSV (tcgcsv.com, free TCGplayer dumps, group 23323) has it with images if ever needed.
  5. **Language capability research:** TCGdex serves ja/th/id/zh-tw/zh-cn/ko. Western langs (fr/de/es/it) share card IDs with EN (same card, localized name/image — verified `swsh3-136`); JA/TH/ID/ZH/KO are separate set universes (no EN↔JP ID mapping). Names fully localized; images sparse (~10% in sampled TH/zh-tw sets). TCGdex does NOT expose JP/TH/ID/ZH sets under its `en` locale — English renderings of foreign version names ("Lost Abyss") are community-wiki data only, not API-obtainable.
  6. **Spec written and APPROVED (v3):** card details panel + per-card language — `docs/superpowers/specs/2026-07-08-card-details-and-language.md`.
  7. **Second spec APPROVED (post-wrapcon addition):** binder page-flip viewer — `docs/superpowers/specs/2026-07-08-binder-page-flip-viewer.md`. Web-only: spine click opens a 3×3 two-page spread below the shelf (single page mobile), arrows + swipe + arrow keys, REPLACES the scrolling grid entirely; card click keeps lightbox+details; only current spread in DOM (perf win for 1000+ slots).

## Key Decisions
- **Language feature lives in the APP, not a website admin login** (2026-07-08): a static Pages site has no backend — "login" would mean a PAT in the browser (worse than EncryptedSharedPreferences) and a second writer to binder.json (publish/restore race). App stays the single writer; site gets data via normal publish.
- **All card details display in English** (Skyler, 2026-07-08): no localized/native-script fields. The "version name" (e.g. JP "Lost Abyss" vs EN "Lost Origin") comes from a bundled static suggestion table (SwSh+SV era, Bulbapedia-compiled) that pre-fills an always-editable field — because no API provides English renderings of foreign version names (verified).
- **Rarity is API-derivable for free**: pokemontcg.io returns `rarity` in every response; the DTO just never declared it. Persist + backfill like artist/number. `tcgdex_`-sourced rows can't be backfilled via pokemontcg.io → rarity "—" in v1, TCGdex full-card fetch is P1.
- **Earlier decisions still in force:** GitHub Pages hosting, manual publish, restore-overlays-never-rebuilds, automatic backfill (non-destructive), Pocket filter pokemontcg.io-only, fallbackToDestructiveMigration accepted (dev-phone-only installs), pure CSS/SVG shelf art. See git history / prior notes.

## Open Questions
- ~~CLB Mr. Mime, MEP Riolu, SVP Feraligatr absent from both APIs~~ Resolved 2026-07-08 for 2 of 3 (see Recent Changes #4); CLB Mr. Mime still manual-entry-only (or TCGCSV if ever integrated).
- Unown letters (28 slots) deferred.
- Custom domain timing; og:image asset decision (tracked in site README).
- Whether the uncommitted app-repo work should be committed before or after on-device verification (Skyler to decide).
- "Braincheck" — Skyler used this term 2026-07-08; not a defined trigger anywhere; he didn't clarify when asked. Ask again if it recurs.

## Review
Short, productive session: closed the last failing unit test (suite fully green for the first time), verified the overnight badge pipeline shipped, and turned a vague "other languages?" question into an approved v3 spec through three rounds of scope-tightening — each round REMOVED complexity (localized display → English-only + suggestion table; website admin login → app-side tagging). The API research was all verified live against the endpoints rather than from memory, which caught two stale "impossible card" beliefs and one wrong design assumption (that TCGdex could translate version names — it can't). Spec approval arrived bundled with wrapcon, so implementation is intentionally NOT started; next session begins it with fresh context.

## Next Steps
1. **Run the pipelines on BOTH approved specs**, in this order: (a) dev-team-pipeline on the app side of `2026-07-08-card-details-and-language.md` (Room v8 + rarity DTO + capture + backfill + suggestion table + publish/restore); (b) ONE web-side pipeline run implementing the lightbox details panel AND `2026-07-08-binder-page-flip-viewer.md` together (same files — serial implementation avoids double rework); (c) full tests + preview verification. Specs are complete — do not re-litigate decisions.
2. Skyler: on-device install + eyeball of multi-binder/restore/backfill (still the oldest open gap; DB backup first).
3. Decide on committing the app repo's uncommitted work.
4. Low-urgency: custom domain, og:image, P1 items from the spec.
