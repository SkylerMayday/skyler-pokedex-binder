# Spec — Discord Binder Sync: Live Lookup Bot

Status: draft, pending Skyler approval. **Do not start `dev-team-pipeline` on this until Skyler
explicitly says go** — planning only for now. First-ever spec for this feature; previously it
existed only as a name in `handoff.md`, no prior detail anywhere in this repo.

## Problem

Today, Discord only hears about the binder collection passively — a webhook post on manual
Publish, listing what changed. There's no way for anyone (Skyler or viewers) to ask "do you have
[card]?" without opening the app or the published site. Scope confirmed with Skyler
(2026-09-02, AskUserQuestion): **read-only live lookup**, not a control surface — Discord commands
never write back into the app's data. That rules out the bigger two-way-sync and cross-device-sync
readings of the original "Discord Binder Sync" name.

## Grounding

- The publish pipeline (`PublishRepository`, project-overview.md) already writes a public
  `binder.json` via the GitHub Contents API on every manual publish — consumed today by the
  separate `skylermayday-site` repo (GitHub Pages). This lookup bot can read the **same already-
  public file**, over plain HTTPS, with zero changes to the Android app or its publish mechanics.
- Precedent for a hosted Discord bot already exists in Skyler's own setup:
  **SkeelerMeidai** (Node.js + discord.js v14, hosted on Railway) — built for the now-retired
  TAIOH! Discord server (`about-me.md`). Confirms he has working Node.js/Discord-bot hosting
  experience to draw on, though that project is unrelated in purpose.
- `binder.json`'s URL **pattern** is now confirmed directly from `PublishRepository.kt`:
  `https://${config.githubOwner}.github.io/${config.githubRepo}/binder.json`
  (`BINDER_JSON_PATH = "binder.json"`, `publicUrl` builder, `PublishRepository.kt:44`/`92-94`).
  The actual `githubOwner`/`githubRepo` values are stored in the Android app's local Settings
  (encrypted `SharedPreferences`, per `androidx.security.crypto` in `build.gradle.kts`) — not
  recorded anywhere in this repo's source, so still needs Skyler to supply the literal values
  before implementation, but the URL *shape* is no longer a guess.

## Goals

- A Discord slash command (e.g. `/card charizard`) that looks up current binder state from the
  live published `binder.json` and replies with what's owned/unowned, which binder(s) it appears
  in, and (if available) the card image.
- Data source is the same `binder.json` the website already publishes — no second source of truth,
  no direct access to the Android app's Room DB (impossible anyway — the DB lives on Skyler's
  phone, not a server).
- Works entirely independent of whether the app or Skyler's phone is running — reads a static
  published file, not a live connection to the device.

## Non-Goals

- No write-back to binder state from Discord. Read-only, confirmed scope.
- No real-time push notifications beyond the existing manual-publish webhook (that's a separate,
  already-working mechanism — not being changed or duplicated here).
- No per-user permission model beyond whatever the target Discord server's own role/channel
  permissions already provide — this is a simple public lookup command, not an access-control
  system.
- No support for querying data older than the last publish — if Skyler hasn't published recently,
  the bot's answers are exactly as stale as the public website already is. Same staleness contract
  as the existing site, not a regression.

## Architecture Decision — serverless HTTP Interactions endpoint, not a persistent gateway bot

**Options considered:**

| Option | Assessment |
|---|---|
| **A. Discord HTTP Interactions endpoint** (chosen) — Discord calls a webhook URL directly per slash-command invocation, no persistent process | Matches the actual workload (occasional read-only lookups against a rarely-changing file) — no gateway connection to hold open, deployable to a free/near-free serverless host (e.g. Cloudflare Workers), lowest ongoing cost. Cold-start latency is the only real trade-off, acceptable for a lookup command. |
| B. Persistent gateway bot (discord.js, like SkeelerMeidai) | Familiar pattern (real precedent), but needs an always-on process — costs money/uptime-babysitting for a bot that's idle 99% of the time answering occasional lookups. Doesn't match "money tight, time is the currency" — rejected as more infra than this workload needs. |
| C. Fold into the existing SkeelerMeidai bot as a new command | Reuses existing hosted infra (no new deploy), but couples an unrelated feature (binder lookup) to a bot built for a different, now-retired community server — muddies that bot's scope for a feature that doesn't need its gateway connection anyway. Rejected. |

**Consequences:** new codebase to stand up (not Kotlin — this is a Node.js/TypeScript or similar
serverless function, a genuinely separate small project, likely its own repo sibling to
`skylermayday-site`, not inside `PokedexBinderV2`). Needs Discord application registration
(bot token, public key for interaction signature verification, slash-command registration via
Discord's API) — new one-time setup, not reusing SkeelerMeidai's existing Discord application.

## Requirements

**P0**
- `/card <name>` slash command registered on the target Discord server.
- Fetches current `binder.json`, finds matching card(s) by name (case-insensitive, partial match
  acceptable given the underlying data already handles fuzzy TCG name matching elsewhere in this
  project).
- Replies with: owned/unowned status, which binder(s) contain it, card image if the JSON carries
  an image URL.
- No match → a clear "not found" reply, not a silent failure or generic error.

**P1**
- Basic response caching (even a short TTL) so multiple lookups between publishes don't refetch
  `binder.json` on every single command.
- Reasonable handling of Discord's slash-command signature verification and interaction timeout
  (~3s to ack, per Discord's own API contract) — may need a deferred-response pattern if the fetch
  is slow.

**P2**
- Richer embed formatting (set name/number, card art thumbnail) beyond a plain text reply.
- A second command (`/binder <name>`) that lists an entire binder section's contents, not just one
  card lookup.

## Task Breakdown (for `dev-team-pipeline`'s Coder stage, when authorized)

| # | Task | Repo/File(s) | Type | Size | Depends on |
|---|---|---|---|---|---|
| 1 | Get the literal `githubOwner`/`githubRepo` values from Skyler (URL *shape* already confirmed: `https://<owner>.github.io/<repo>/binder.json`, `PublishRepository.kt`) | — (one question, not code) | INFRA | S | — |
| 2 | Register a new Discord application (bot token, interactions public key), register the `/card` slash command via Discord's REST API | New repo/project | INFRA | S | — |
| 3 | Serverless function: verify interaction signature, fetch + parse `binder.json`, match by card name, build reply payload | New repo, e.g. `discord-binder-lookup/` | CORE | M | 1, 2 |
| 4 | Deploy to chosen serverless host (Cloudflare Workers recommended — free tier fits this workload) | New repo | INFRA | S | 3 |
| 5 | "Not found" / error-path handling, response-time budget (defer-then-edit if the fetch is slow) | New repo | CORE | S | 3 |
| 6 | Basic tests: name-matching logic, JSON-shape parsing against a real `binder.json` sample | New repo | TEST | S | 3 |

**Phase rollout:** 1-2 (infra/registration) → 3-5 (core function) → 6 (tests). This whole feature
is additive and isolated — zero risk to `PokedexBinderV2`'s own build or runtime, since it only
reads a file that repo already publishes.

## Open Questions

- Literal `githubOwner`/`githubRepo` values for the URL — not yet confirmed (task 1 above); URL
  shape itself is confirmed from source.
- Which Discord server(s) get this command — Skyler's own, a future community server, or both?
- Serverless host choice — Cloudflare Workers recommended (free tier, no cold-start-sensitive
  gateway connection needed) but not yet confirmed against Skyler's existing hosting setup
  (Railway, per SkeelerMeidai precedent) — worth checking if consolidating onto one host is
  preferred over adding a second.
- New repo name/location — sibling to `skylermayday-site`, or elsewhere?

## Timeline

No hard deadline. **Do not start `dev-team-pipeline` until Skyler explicitly says go.**
