# Spec — Discord Binder Sync: Live Lookup Bot

Status: **approved for `dev-team-pipeline`, go-ahead given 2026-09-04.** Second draft — the
Architecture Decision below reverses the first draft's chosen option; see "Revision History."

## Problem

Today, Discord only hears about the binder collection passively — a webhook post on manual
Publish, listing what changed. There's no way for anyone (Skyler or viewers) to ask "do you have
[card]?" without opening the app or the published site. Scope confirmed with Skyler
(2026-09-02, AskUserQuestion): **read-only live lookup**, not a control surface — Discord commands
never write back into the app's data. That rules out the bigger two-way-sync and cross-device-sync
readings of the original "Discord Binder Sync" name.

## Revision History

- **2026-09-02**: first draft. Architecture Decision chose a new standalone serverless repo
  (Cloudflare Workers), rejecting "fold into the existing SkeelerMeidai bot" (Option C) on the
  grounds that SkeelerMeidai was built for TAIOH!, a Discord community documented as retired/
  `status:planned`-not-yet-launched — coupling a live feature to a dead project's bot seemed like
  the wrong move.
- **2026-09-04**: that premise was wrong, corrected directly by Skyler this session. TAIOH! was
  **renamed** to Skyler's Lounge, not replaced — same server, same bot (SkeelerMeidai), still
  running there today. Skyler's own words: "the server is renamed to Skyler's lounge. But the bot
  exists in the server regardless." (The Digital Brain vault's `discord-bot.md`/`about-me.md` pages
  still describe Skyler's Lounge as `status:planned` — stale, flagged for a vault update, not
  actioned in this repo.) With that premise corrected, Option C's original rejection reasoning no
  longer holds, and Skyler independently confirmed Railway (SkeelerMeidai's existing host) over
  standing up a new Cloudflare Workers project. **Architecture Decision rewritten below —
  fold `/card` into the existing bot, no new repo, no new hosting, no new Discord application.**
  This document stays in `PokedexBinderV2/docs/specs/` (where the binder-side grounding lives) even
  though the actual implementation target is a different repo, `D:\Claude Projects\DiscordBot`.

## Grounding

- The publish pipeline (`PublishRepository`, `PokedexBinderV2/project-overview.md`) already writes
  a public `binder.json` via the GitHub Contents API on every manual publish — consumed today by
  the separate `skylermayday-site` repo (GitHub Pages). This lookup command reads the **same
  already-public file**, over plain HTTPS, with zero changes to the Android app or its publish
  mechanics.
- **`binder.json`'s real shape, confirmed live 2026-09-04** (fetched `https://skylermayday.github.io/binders-pokedex-binder/binder.json`, HTTP 200, 385,518 bytes at time of fetch):
  ```jsonc
  {
    "schemaVersion": 1,
    "publishedAt": "<ISO-8601 timestamp>",
    "binders": [
      {
        "id": "pokedex",              // also seen live: "cardHistory", "personalCollection"
        "name": "Pokédex",            // display name — non-ASCII, handle as UTF-8 throughout
        "sections": [
          {
            "name": "Generation I",   // binders with 1 section still use this shape (cardHistory)
            "slots": [
              {
                "dexNumber": 1,
                "slotName": "Bulbasaur",
                "slotType": "BASE",
                "slotId": "bulbasaur",
                "cardId": "sv3pt5-166",
                "cardName": "Bulbasaur",    // NOT always present — omitted on at least one real
                                             // cardHistory slot observed live; fall back to
                                             // slotName when absent, never assume it's there
                "cardSet": "151",
                "imageUrl": "https://images.pokemontcg.io/sv3pt5/166_hires.png",
                "owned": true,
                "language": "EN",
                "isLocked": true
              }
            ]
          }
        ]
      }
    ]
  }
  ```
  Match target for `/card <name>` is `slotName`/`cardName` (case-insensitive substring), iterating
  every section of every binder — **not a flat card list**, nesting is binder → section → slot.
  Only 3 of the app's 5 binder types were present in the live fetch (Connecting Art and Unown were
  absent) — confirms `project-overview.md`'s documented behavior that content-gated binders only
  publish when non-empty. The command must handle a `binders` array that doesn't always contain
  all 5 types, not assume a fixed set.
- Precedent for a hosted Discord bot already exists in Skyler's own setup: **SkeelerMeidai**
  (Node.js + discord.js v14, hosted on Railway) — the same bot, confirmed 2026-09-04 to be live and
  running in Skyler's Lounge (renamed from TAIOH!) today. Full detail: `DiscordBot/CLAUDE.md`,
  `DiscordBot/project-overview.md`. Its `git log` (8 commits ahead of what that repo's own stale
  `handoff.md` describes, all pushed to `origin/master`) is the ground truth for its current state,
  not its docs — Wave 1 and all of Wave 2 (10 features) are shipped and live, including layered
  search, DM modmail, and the stream↔Discord bridge.
- `binder.json`'s URL is confirmed: `githubOwner = "SkylerMayday"`,
  `githubRepo = "binders-pokedex-binder"` →
  `https://skylermayday.github.io/binders-pokedex-binder/binder.json` (verified live, HTTP 200,
  re-confirmed 2026-09-04 alongside the shape fetch above).

## Goals

- A Discord slash command `/card <name>` (hybrid — also works as `!card <name>` per this bot's
  established prefix+slash convention) that looks up current binder state from the live published
  `binder.json` and replies with what's owned/unowned, which binder(s)/section(s) it appears in,
  and the card image if the JSON carries an `imageUrl`.
- Data source is the same `binder.json` the website already publishes — no second source of truth,
  no direct access to the Android app's Room DB (impossible anyway — the DB lives on Skyler's
  phone, not a server).
- Works entirely independent of whether the Android app or Skyler's phone is running — reads a
  static published file, not a live connection to the device.

## Non-Goals

- No write-back to binder state from Discord. Read-only, confirmed scope.
- No real-time push notifications beyond the existing manual-publish webhook (that's a separate,
  already-working mechanism — not being changed or duplicated here).
- No per-user permission model beyond whatever Skyler's Lounge's own role/channel permissions
  already provide — this is a simple public lookup command, not an access-control system.
- No support for querying data older than the last publish — if Skyler hasn't published recently,
  the bot's answers are exactly as stale as the public website already is. Same staleness contract
  as the existing site, not a regression.
- No new Discord application, bot token, or hosting account — reuses SkeelerMeidai's existing
  application/token/Railway service entirely.

## Architecture Decision — new module inside the existing SkeelerMeidai bot

**Options considered (revised 2026-09-04):**

| Option | Assessment |
|---|---|
| **C. Fold into the existing SkeelerMeidai bot as a new module** (chosen, reversing the 2026-09-02 draft) | Zero new infra: reuses the live Railway deployment, the already-registered Discord application/bot token, and the existing hybrid prefix+slash command-loading machinery (`src/index.js`'s `boot()`, `src/events/ready.js`'s `registerSlashCommands()` — a new module exporting `commands: { card: {...} }` with an `options` array gets picked up and slash-registered automatically, no manual Discord Developer Portal step needed). Matches "money tight, time is the currency" better than any option requiring a new service. The 2026-09-02 rejection reasoning (coupling to a retired community's bot) is void — the server is live, renamed not replaced, same bot running there today. |
| A. Discord HTTP Interactions endpoint (new serverless repo, Cloudflare Workers) | 2026-09-02's original choice. Real trade-offs (cold-start latency, needs its own interaction-signature verification and slash-command registration from scratch) that only made sense if there were no existing always-on bot to extend. There is one — this option now adds infra for no benefit. Rejected. |
| B. Persistent gateway bot as a brand-new project (not reusing SkeelerMeidai) | Same "always-on process" cost as Option C without reusing anything already built/hosted/paid-for. Strictly worse than C now that C is available. Rejected. |

**Consequences:**
- New code lives in `D:\Claude Projects\DiscordBot`, not this repo — `src/modules/binder.js` (the
  command + Discord-facing embed formatting) and `src/utils/binder.js` (pure card-matching logic
  against a parsed `binder.json` object, zero discord.js/network dependency, unit-testable with
  zero mocking — matches this codebase's established pure-logic-extraction convention).
- `axios` is already a dependency (`package.json`, `^1.6.7`) — no new npm package needed for the
  HTTPS fetch.
- Runs through DiscordBot's own established process: a `dev-team-pipeline` pass
  (Planner→Coder→Tester→Reviewer), archived to `.pipeline/archive-binder-lookup/` after shipping,
  same as every other feature in that project.
- Deploys via the existing push-to-`origin/master` → Railway auto-deploy path already in use for
  every prior phase/feature in that repo. **Committing and pushing is a live production deploy**
  against Skyler's actual running bot — same standing caution `DiscordBot/handoff.md` already
  documents for every other feature; ask before pushing, don't auto-push.

## Requirements

**P0**
- `/card <name>` (and `!card <name>`) registered via the existing hybrid command convention —
  one inner function shared by `execute()`/`slashExecute()`, per `src/modules/status.js`'s pattern.
- Fetches current `binder.json` via `axios`, iterates every `binder → section → slot`, matches by
  name (case-insensitive substring against `slotName`, falling back from `cardName` when present —
  see Grounding's note that `cardName` isn't always populated).
- Replies with: owned/unowned status, which binder(s)/section(s) it appears in, card image
  (`imageUrl`) if present, via a Discord embed (matches `status.js`'s `EmbedBuilder` pattern).
- No match → a clear "not found" reply, not a silent failure or generic error.
- Multiple matches (a name can appear in more than one binder/section, or match multiple cards) →
  list them, don't silently pick one.

**P1**
- In-memory TTL cache for the parsed `binder.json` (module-scope `Map` or similar, e.g. 5-minute
  TTL) — this bot is a persistent single process, not serverless, so a simple in-memory cache is
  sufficient; no external cache store needed. Prevents refetching a ~385KB file on every single
  lookup between publishes.
- `interaction.deferReply()` before the fetch if there's any real chance it exceeds Discord's ~3s
  ack window — this exact codebase has hit a missed-`deferReply()` bug before
  (`lockdown.js`'s multi-channel slash sweep, per `project-overview.md`'s Process Note); don't
  repeat it here even though a single HTTPS GET is normally fast.

**P2**
- Richer embed formatting (set name/number beyond what's already in `cardSet`, card art thumbnail
  sizing).
- A second command (`/binder <name>`) that lists an entire binder/section's contents, not just one
  card lookup.

## Task Breakdown (for `dev-team-pipeline`'s Coder stage, in `DiscordBot`)

| # | Task | File(s) | Type | Size | Depends on |
|---|---|---|---|---|---|
| 1 | Pure matching logic: `matchCards(binderJson, query)` — iterates binders→sections→slots, case-insensitive substring match on `slotName`/`cardName` fallback, returns an array of `{binderName, sectionName, slot}` matches. Zero discord.js/network dependency. | `src/utils/binder.js` (new) | CORE | M | — |
| 2 | Fetch helper: `fetchBinderJson()` — `axios.get` the published URL, in-memory TTL cache, error handling for network failure/non-200/malformed JSON | `src/utils/binder.js` (same file, or a sibling if the Planner prefers separating I/O from pure logic — Coder's call, following whichever split this codebase's other utils already model) | CORE | S | — |
| 3 | `card` command module: hybrid `execute()`/`slashExecute()` sharing one inner function that calls `fetchBinderJson()` + `matchCards()`, builds the reply embed (found/not-found/multiple-matches cases) | `src/modules/binder.js` (new) | CORE | M | 1, 2 |
| 4 | Wire into `categories.js` category inference (new module → new or existing category, e.g. reuse `Utility` or add a small `Binder` category — Coder's call, following the established filename→category mapping convention) | `src/utils/categories.js` | INTG | S | 3 |
| 5 | `deferReply()` handling for the slash path per P1 | `src/modules/binder.js` | CORE | S | 3 |
| 6 | Unit tests: `matchCards()` against a realistic sample `binder.json` fixture (including the multi-section, missing-`cardName`, and no-match cases confirmed live in Grounding), zero mocking needed per the established pure-function-test convention | `__tests__/binder.test.js` (new) | TEST | M | 1 |

**Phase rollout:** 1-2 (pure logic + fetch, independent of Discord) → 3 (command wiring, depends on
both) → 4-5 (integration/polish) → 6 (tests, can start as soon as 1 is stable).

## Open Questions

- ~~Literal `githubOwner`/`githubRepo` values~~ — resolved 2026-09-04.
- ~~Which Discord server(s)~~ — resolved 2026-09-04: Skyler's Lounge (the renamed TAIOH! server),
  where SkeelerMeidai already runs.
- ~~Serverless host choice~~ — moot as of the 2026-09-04 architecture reversal; no new host needed.
- ~~New repo name/location~~ — moot; implementation lives in the existing `DiscordBot` repo.
- **Category naming for `categories.js`** (Task 4) — left to the Coder stage; not worth blocking
  planning on a cosmetic help-menu grouping choice.

## Timeline

No hard deadline. **Go-ahead given 2026-09-04** — `dev-team-pipeline` may start against this spec.
Push to Railway (production deploy) still needs Skyler's explicit go separately, per
`DiscordBot/handoff.md`'s standing convention for this project.
