# Public Binder Sharing & Discord Updates
**Date:** 2026-07-04
**Status:** Approved 2026-07-04 — proceeding to Phase 1

---

## Problem Statement

The binder collection lives only in a Room DB on Skyler's phone. Viewers and community members have no way to see the collection, and there is no announcement channel when it grows — a missed content/community touchpoint for a streamer whose brand includes Pokémon TCG. Secondarily, the phone is the single copy of years of collection tracking; losing it loses everything.

## Goals

1. Anyone with a link can view the current binder state in a browser, no app or account needed.
2. One tap in the app publishes the current state and announces exactly what changed on Discord.
3. The public link shows *what changed* in the latest update, not just the full binder.
4. Zero recurring cost (GitHub Pages + Discord webhook are free).
5. The published snapshot doubles as an off-device backup of collection state.

## Non-Goals

- **Real-time sync / auto-publish** — manual button only (decided 2026-07-04). Avoids Discord noise and network chatter.
- **Viewer accounts, comments, reactions** — it's a read-only showcase, not a platform.
- **Full backup/restore of the app** — the snapshot covers assignments, not app settings; restore-from-snapshot is P1.
- **Publishing Connecting Art / Personal Collection binders now** — they aren't built yet (spec 2026-05-24). The export schema must support them later (P2), but v1 ships without.
- **Custom domain** — `skylermayday.github.io/binders-pokedex-binder` is fine for v1. Skyler owns `SkylerMayday.com` and wants it wired up eventually (P2): apex domain, so this needs a `CNAME` file in the repo + apex `A` records (GitHub Pages IPs) at the DNS registrar, not just a CNAME record. Zero app-code changes either way.

## User Stories

- As Skyler, I want a "Publish" button that snapshots my binder to a public page so viewers can browse my collection.
- As Skyler, I want the Discord message to list which slots changed (added/replaced/removed) so the community sees progress without me writing an update.
- As Skyler, I want per-binder public toggles (Pokédex on by default) so I control what's visible.
- As a viewer, I want to open the link from Discord and immediately see what's new, then browse the full binder.
- As Skyler, I want publish to fail loudly (clear error, nothing half-announced) if the network or GitHub call fails.

## Architecture

```
[Android app] --"Publish" tap-->
  1. GET  binder.json from GitHub (Contents API; returns content + sha) = baseline
  2. Serialize enabled binders -> new snapshot JSON
  3. Diff baseline vs snapshot (per slot: added / replaced / removed)
  4. If no changes: toast "No changes since last publish", stop (no webhook)
  5. PUT  binder.json    (Contents API, with sha)
  6. PUT  changelog.json (prepend this publish's diff; keep last 50 publishes)
  7. POST Discord webhook (summary embed + link)
[GitHub Pages] serves static viewer (index.html + JS) that fetches binder.json + changelog.json
```

Diffing against the **remote** snapshot (not a locally stored copy) makes the public state the single source of truth — survives app reinstalls and guarantees the Discord message matches what the page actually shows.

**Auth:** fine-grained GitHub PAT (single repo, Contents read/write only), entered once in app Settings, stored via EncryptedSharedPreferences. Discord webhook URL likewise. Neither is baked into the APK.

**Snapshot format (versioned):**
```json
{
  "schemaVersion": 1,
  "publishedAt": "2026-07-04T21:00:00+08:00",
  "binders": [
    { "id": "pokedex", "name": "Pokédex",
      "sections": [ { "name": "Kanto", "slots": [
        { "dexNumber": 6, "slotName": "Charizard", "slotType": "BASE",
          "cardId": "xy2-69", "cardName": "Charizard", "cardSet": "Flashfire",
          "imageUrl": "https://..." } ] } ] } ]
}
```
Empty slots are included (null card fields) so the page renders the greyed-out grid like the app. `binders` is an array so future binders slot in without schema changes.

## Requirements

### Must-Have (P0)

1. **Schema v5→6:** add `assignedCardName`, `assignedCardSetName` (nullable) to `main_binder`; populate on every new assignment in `AssignCardUseCase`. Existing rows stay null — viewer and Discord fall back to the slot's `pokemonName`, which is always present.
   - [ ] New assignments store card name + set
   - [ ] Migration runs clean on an existing install
2. **Publish flow** as diagrammed, in `PublishBinderUseCase` + `PublishRepository`.
   - [ ] Given no changes, no webhook fires and no commit is made
   - [ ] Given GitHub PUT fails, no webhook fires and the user sees the error
   - [ ] First-ever publish announces "Initial publish — N cards" instead of listing hundreds
3. **Discord embed:** title "Pokédex Binder updated", lines like `+ Mega Charizard X (Flashfire)` / `↻ Pikachu (card swapped)` / `− Eternatus`, capped at 15 lines + "…and N more", completion count (e.g. `Pokédex: 412/1025`), link to the page's latest-changes anchor.
4. **Settings additions:** GitHub repo (owner/name), PAT, webhook URL, per-binder public toggles — Pokédex ON by default, Card History OFF by default. Publish button lives in the main binder top bar + Settings.
5. **Publish progress UI** (per ui-progress rule): step indicator dialog — Fetching current → Uploading → Notifying Discord → Done (with elapsed time); failures name the step.
6. **Static viewer** (plain HTML/JS/CSS, no build step) in the Pages repo:
   - [ ] Sections mirror the app (Kanto…Paldea, Regional, Alt Forms, Mega, VMax), lazy-loaded images, greyed empty slots
   - [ ] Per-section + overall completion bars
   - [ ] "Recent updates" panel from changelog.json, newest expanded, `#latest` anchor target
   - [ ] Usable on mobile (most Discord clicks will be phones)

### Nice-to-Have (P1)

- **Restore from snapshot:** app can import binder.json to rebuild assignments — turns the feature into a real backup. (Card image URLs and IDs are all in the snapshot.)
- **Backfill card names/sets** for pre-existing assignments via lazy API lookups by cardId.
- **OG meta tags** so the link unfurls nicely in Discord.
- **Changed-card highlight** on the viewer grid (badge on slots touched in the latest publish).

### Future (P2)

- Connecting Art + Personal Collection binders in the export once built (schema already supports).
- Custom domain; publish history browser (git log already stores it).

## Success Criteria

- Publish completes in <10s on a normal session's diff; a failed publish never produces a Discord message that the page doesn't back up.
- Skyler stops needing to manually screenshot/announce binder progress.

## Open Questions

- ~~Repo name?~~ **Resolved 2026-07-05:** `SkylerMayday/binders-pokedex-binder` (repo created), public.
- ~~Custom domain?~~ **Resolved 2026-07-04:** Skyler owns `SkylerMayday.com`, wire up in P2 (see Non-Goals).
- **Skyler:** which Discord channel gets the webhook (created in Discord server settings → Integrations → Webhooks; paste URL into app Settings). Still pending.
- **Non-blocking, eng:** single-commit atomicity for binder.json + changelog.json via Git Trees API — v1 uses two sequential PUTs; acceptable since changelog is decorative.

## Timeline / Phasing

1. **Phase 1 (P0):** schema v6 → publish pipeline → Discord embed → static viewer → settings. Ships the whole user-visible feature.
2. **Phase 2 (P1):** restore-from-snapshot, backfill, OG tags, highlights.

Depends on Skyler creating: the GitHub repo + fine-grained PAT, and the Discord webhook. App works without them configured (publish button prompts for setup).
