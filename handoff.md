# Handoff — 2026-07-08

## What happened this session (continues from 2026-07-06's four-workstream batch)

Short session: status check, one test fix, live API research, and a spec that went through
three revisions before approval. No pipeline runs — implementation of the approved spec is
deliberately the NEXT session's job (approval arrived bundled with wrapcon).

## Done this session

1. **Confirmed the NEW-badges design pipeline shipped.** It re-ran ~02:00–02:30 SGT on
   2026-07-08 (files in `.pipeline/`), verdict SHIP, and the web repo commit (OG tags +
   badges) is pushed and in sync with `origin/main` on the Pages repo. Web side has nothing
   pending. Process note from that run: the preview harness's stale-script cache survives
   `preview_stop`/`preview_start` — future re-verification needs the fetch-`no-store` +
   indirect-eval workaround documented in `.pipeline/design-review-verdict.md`.
2. **Fixed `CardSearchRepositoryTest` — the unit suite is now fully green.** Root cause: both
   mocks stubbed pre-wildcard, pre-Pocket-filter query strings (`name:"Venusaur"` instead of
   `name:*Venusaur* number:"1" -set.series:Pocket`); the MockK miss threw inside
   `safeSearch`'s try/catch → silent empty list. Also made the second test non-vacuous (it
   was passing only because its stale stub never matched and it asserted emptiness).
3. **Promo-card re-check (stale beliefs corrected):** TCGdex now has `svp-213` Feraligatr and
   `mep-010` Riolu, and both come back from the exact `/cards?name=` endpoint the app already
   merges — assignable via name search today, but both lack scans (blank art until TCGdex
   adds images). Needs on-device confirmation. pokemontcg.io SVP still stops at #207.
   CLB Mr. Mime (TCG Classic) remains on neither API; TCGCSV (tcgcsv.com, free TCGplayer
   dumps, Pokémon category 3, group 23323) has it with product images if a source is ever
   wanted — but that'd be a local-cache integration (static daily dumps, no search API).
4. **og:image TODO added** to `web/pokedex-binder-site/README.md` (working copy only — README
   isn't deployed). `index.html` already has a placeholder comment where the tags go.
5. **Project notes updated + synced to Digital Brain; session archived** to
   `raw-sources/conversations/2026-07-08-277f342e.{jsonl,md}`.

## TWO approved specs — NEXT SESSION STARTS HERE

**Post-wrapcon addition:** after the initial wrap, Skyler approved a second spec —
`docs/superpowers/specs/2026-07-08-binder-page-flip-viewer.md` (web-only: clicking a spine
opens the binder below the shelf as a 3×3 two-page spread with arrow/swipe/key page
flipping, REPLACING the scrolling grid; lightbox + NEW badges unchanged). Its "Sequencing"
section defines the build order for both specs: app side of the details spec first, then ONE
web run implementing lightbox details panel + page-flip together (same files, avoids double
rework).

`docs/superpowers/specs/2026-07-08-card-details-and-language.md` — **APPROVED v3, do not
re-litigate.** Card details panel on the website's lightbox (name, artist, set·number,
rarity, language pill, optional version name) + per-card language tagging in the app.

Key facts the implementer needs (all verified live this session, reasoning in the spec):
- All display text is ENGLISH. No localization calls. The "version name" (JP "Lost Abyss" vs
  EN "Lost Origin") comes from a bundled static JSON suggestion table (SwSh+SV era, compile
  from Bulbapedia during the build) pre-filling an editable field. No API can provide this —
  TCGdex does NOT expose JP/TH/ID/ZH sets under `/v2/en/` (tested: `en/S11`, `en/S12a`,
  `en/SV2a` all 404).
- `rarity` is already in every pokemontcg.io response (no `select` param in use) — the DTO
  just doesn't declare it. Add field, persist, extend existing backfill. `tcgdex_`-prefixed
  rows can't backfill via pokemontcg.io → "—" in v1 (P1: TCGdex full-card fetch).
- Room v7→v8: 5 columns on `main_binder` (artist, number, rarity, cardLanguage DEFAULT 'EN',
  versionName) + `secondary_binder` parity. Migration test required. Remember
  `fallbackToDestructiveMigration()` is live — a wrong migration wipes silently.
- Publish diff must NOT emit REPLACED for metadata-only changes (same cardId) — verify
  explicitly, it's a P0 checkbox.
- Restore overlays the new fields (extend the existing overlay regression test).
- Language codes: EN/JA/TH/ID/ZH-TW/ZH-CN/KO.
- Planner must verify whether Connecting Art / Personal Collection entries appear in
  binder.json — parity scope follows the data.

Sequence per global rules: dev-team-pipeline for the app side, then a small second run for
the web lightbox panel, then full unit suite + preview verification (mind the stale-cache
workaround, point 1 above).

## Still open (unchanged from 2026-07-06 unless noted)

- **On-device verification of multi-binder/restore/backfill** — oldest open gap; only Skyler
  can do it. DB backup or publish first (untested v6→v7 migration + destructive fallback).
- ~~2 months of app-repo work uncommitted~~ **COMMITTED 2026-07-08** on Skyler's instruction,
  4 commits on master (local only — repo has NO remote): db2b43d gitignore (nested site repo
  ignored — it has its own git history; local.properties/.idea/.pipeline/.claude ignored),
  4c181ed app feature batch (87 files), c0dc297 notes/handoff/tooling, 5e5d247 docs/specs +
  pipeline archives. Secrets audited first: none hardcoded (all runtime-entered). fsck clean.
  **Environment quirk:** `git add`/`commit` intermittently fails with "unable to write file
  .git/objects/... Permission denied" (likely AV scanning) — an immediate retry always
  succeeds; don't misdiagnose as repo corruption, verify with `git fsck`.
- Custom domain (CNAME + DNS) and og:image — low urgency, og:image now tracked in site README.
- Unown letters deferred; "Braincheck" (Skyler's term, undefined trigger) — asked once, no
  answer; re-ask if it recurs.

## Environment notes (still true)

- `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`; `TEMP=TMP=C:\Windows\Temp` for Gradle runs.
- No Android emulator in this sandbox; on-device checks happen on Skyler's machine.
- Session JSONL for this project: `C:\Users\SkylerMayday\.claude\projects\D--Claude-Projects-PokedexBinderV2\`.
