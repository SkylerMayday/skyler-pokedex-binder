# Implementation Plan — Public Binder Sharing (Phase 1 / P0)

**Source spec:** `docs/superpowers/specs/2026-07-04-public-binder-sharing.md`
**Scope:** P0 only. Do NOT implement P1 (restore, backfill, OG tags, highlights) or P2 (other binders, custom domain).
**Author:** Planner stage. Coder follows this literally.

---

## 0. Grounding facts (verified against the codebase — do not re-derive)

- **HTTP/JSON stack already present:** Retrofit 2.11.0 + Moshi 1.15.1 (`KotlinJsonAdapterFactory`, codegen via KSP) + OkHttp 4.12.0 logging. **Reuse these. Do NOT add kotlinx.serialization, Gson, or Ktor.**
- **DI:** Hilt. `NetworkModule` (object, `SingletonComponent`) provides Moshi, OkHttpClient, and per-API Retrofit instances using `@Named` qualifiers for the second base URL (see `provideTcgdexRetrofit` / `@Named("tcgdex")`). Mirror that exact pattern for GitHub + Discord.
- **DB:** Room 2.6.1. `PokedexDatabase` is `version = 5`, `exportSchema = false`, entities `MainBinderEntry`, `SecondaryBinderEntry`. Migrations are declared as `Migration` objects in the `companion object` and wired in `DatabaseModule.provideDatabase(...)` via `.addMigrations(...)`. Currently only `MIGRATION_4_5` is registered; **note `.fallbackToDestructiveMigration()` is currently present — keep it, but our migration must still be correct so real installs are not wiped.**
- **`main_binder` entity** (`MainBinderEntry.kt`): `pokemonId` (PK, String), `pokemonName`, `dexNumber`, `dexOrder`, `slotType` (String; values BASE/REGIONAL/ALTERNATE_FORM/MEGA/GMAX uppercased), `assignedCardId: String?`, `assignedCardImageUrl: String?`.
- **Assignment flow:** `AssignCardUseCase.assign(pokemonId, card: TcgCard)` → calls `binderRepository.assignCard(pokemonId, card.id, card.imageUrl)`. `BinderRepository.assignCard(pokemonId, cardId, cardImageUrl)` does an upsert of `existing.copy(...)`. `TcgCard` has `name: String` and `setName: String` — these are the two new fields we must persist. Callers: `AssignmentViewModel`, `QuickScanViewModel`, `ScannerViewModel` (all go through the use case — no direct repo calls to change beyond the repo method itself).
- **Settings:** `SettingsRepository` uses Jetpack DataStore Preferences (NOT EncryptedSharedPreferences). `AppSettings` data class + per-field setters + a single `settings: Flow<AppSettings>`. `SettingsViewModel` exposes `StateFlow<AppSettings>` and `fun setX(v)` launchers. `SettingsScreen` composes `SectionHeader` + `SettingToggleItem` + `OutlinedTextField` (password field pattern with `PasswordVisualTransformation` + visibility toggle already exists for the Gemini key).
- **EncryptedSharedPreferences is NOT a dependency.** `androidx.security:security-crypto` must be ADDED (see §4).
- **Main binder top bar:** `MainBinderScreen.kt`, `TopAppBar` with an `actions = { IconButton(onSettingsClick) }`. Add the Publish action here.
- **Sections/grouping logic** lives in `MainBinderViewModel.buildGrouped(...)`: generations by dex range (`GENERATIONS` list), then "Regional Variants", "Alternate Forms", "Mega Evolutions", "VMax" (GMAX). Slot sort within section: `compareBy({ dexNumber }, { dexOrder })`. **The snapshot serializer and the web viewer must mirror these exact section names and ordering.**
- **Nav:** `AppNavigation.kt` — `Screen` sealed class + `NavHost`. Settings route already exists.

---

## 1. Room schema v5 → v6 (add card name + set to main_binder)

### 1.1 Modify `MainBinderEntry.kt`
Add two nullable columns after `assignedCardImageUrl`:
```kotlin
@Entity(tableName = "main_binder")
data class MainBinderEntry(
    @PrimaryKey val pokemonId: String,
    val pokemonName: String,
    val dexNumber: Int = 0,
    val dexOrder: Int,
    val slotType: String = "BASE",
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null,
    val assignedCardName: String? = null,      // NEW
    val assignedCardSetName: String? = null     // NEW
)
```

### 1.2 Add migration to `PokedexDatabase.kt`
- Bump `version = 5` → `version = 6`.
- Add to the `companion object`:
```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE main_binder ADD COLUMN assignedCardName TEXT")
        db.execSQL("ALTER TABLE main_binder ADD COLUMN assignedCardSetName TEXT")
    }
}
```
(SQLite `ADD COLUMN` with no `NOT NULL`/`DEFAULT` yields nullable columns defaulting to NULL — correct for existing rows.)

### 1.3 Register migration in `DatabaseModule.kt`
```kotlin
.addMigrations(PokedexDatabase.MIGRATION_4_5, PokedexDatabase.MIGRATION_5_6)
```
Leave `.fallbackToDestructiveMigration()` as-is (existing behavior).

### 1.4 Populate on new assignments
**`BinderRepository.assignCard` signature change** — thread the two new fields through:
```kotlin
suspend fun assignCard(
    pokemonId: String,
    cardId: String,
    cardImageUrl: String,
    cardName: String?,
    cardSetName: String?
) {
    val existing = mainBinderDao.getByPokemonId(pokemonId) ?: return
    mainBinderDao.upsert(
        existing.copy(
            assignedCardId = cardId,
            assignedCardImageUrl = cardImageUrl,
            assignedCardName = cardName,
            assignedCardSetName = cardSetName
        )
    )
}
```
`clearCard` must also null the two new fields:
```kotlin
existing.copy(assignedCardId = null, assignedCardImageUrl = null,
              assignedCardName = null, assignedCardSetName = null)
```

**`AssignCardUseCase.assign`** — pass `card.name` and `card.setName`:
```kotlin
binderRepository.assignCard(pokemonId, card.id, card.imageUrl, card.name, card.setName)
```
(The secondary-binder move logic above it is unchanged.)

### 1.5 Tests to update/add
- `AssignCardUseCaseTest.kt`: the two `coVerify { binderRepo.assignCard(...) }` calls now need the extra args, e.g. `binderRepo.assignCard("bulbasaur", "xy1-1", "https://img.url/xy1-1", "Bulbasaur", "XY")`. The `card(...)` helper builds `setName = "XY"`, so expected set name is `"XY"` and expected name is the passed `name`.
- Add a Room migration instrumented test (androidTest, `room.testing` + `MigrationTestHelper` already available via `libs.room.testing`): create DB at v5, insert a row, run `MIGRATION_5_6`, assert the two new columns exist and are NULL. File: `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration5to6Test.kt`. If no existing `MigrationTestHelper` schema JSON exists (because `exportSchema = false`), instead write a plain unit-level check by running the migration SQL against an in-memory `SupportSQLiteDatabase` — **Coder: prefer the `exportSchema` route only if flipping it on is trivial; otherwise assert via a `@Database`-less raw SQLite open of the migrated file. Do not spend more than one attempt here — a straightforward "columns are added and nullable" assertion is enough.**

### Edge cases
- Existing assigned rows keep `assignedCardName = null`; the viewer/Discord fall back to `pokemonName` (slot name). Handle the null everywhere downstream.
- Re-assignment over an occupied slot: existing behavior moves old card to secondary binder using only id+imageUrl (secondary entity has no name/set columns — **do NOT extend the secondary binder schema in P0**, out of scope).

---

## 2. Publish pipeline (`PublishBinderUseCase` + `PublishRepository`)

New package: `com.skyler.pokedexbinder.publish`.

### 2.1 Snapshot data model (Moshi)
New file `publish/model/BinderSnapshot.kt`. Use `@JsonClass(generateAdapter = true)` (Moshi codegen is wired). Field names must match the spec JSON exactly.
```kotlin
const val SNAPSHOT_SCHEMA_VERSION = 1

@JsonClass(generateAdapter = true)
data class BinderSnapshot(
    val schemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
    val publishedAt: String,           // ISO-8601 with offset, e.g. 2026-07-04T21:00:00+08:00
    val binders: List<SnapshotBinder>
)

@JsonClass(generateAdapter = true)
data class SnapshotBinder(
    val id: String,                    // "pokedex" | "cardHistory"
    val name: String,                  // "Pokédex" | "Card History"
    val sections: List<SnapshotSection>
)

@JsonClass(generateAdapter = true)
data class SnapshotSection(
    val name: String,                  // "Generation I", ..., "Regional Variants", "Alternate Forms", "Mega Evolutions", "VMax"
    val slots: List<SnapshotSlot>
)

@JsonClass(generateAdapter = true)
data class SnapshotSlot(
    val dexNumber: Int,
    val slotName: String,              // the slot's pokemonName (always present)
    val slotType: String,              // BASE/REGIONAL/ALTERNATE_FORM/MEGA/GMAX
    val slotId: String,                // pokemonId — stable key for diffing
    val cardId: String?,               // null = empty slot
    val cardName: String?,             // fallback handled by viewer/Discord, not here
    val cardSet: String?,
    val imageUrl: String?
)
```
> **Diff key:** `slotId` (pokemonId) is the stable identity. Spec's example omits it but per-slot diff needs a stable key that survives card swaps; include it. `binders` stays an array for future binders (P2).

### 2.2 Changelog data model
New file `publish/model/Changelog.kt`.
```kotlin
const val CHANGELOG_MAX_ENTRIES = 50

@JsonClass(generateAdapter = true)
data class Changelog(
    val entries: List<ChangelogEntry>
)

@JsonClass(generateAdapter = true)
data class ChangelogEntry(
    val publishedAt: String,
    val summary: PublishSummaryCounts,      // added/replaced/removed counts + completion
    val changes: List<SlotChange>
)

@JsonClass(generateAdapter = true)
data class PublishSummaryCounts(
    val added: Int,
    val replaced: Int,
    val removed: Int,
    val pokedexComplete: Int,               // e.g. 412
    val pokedexTotal: Int                    // e.g. 1025
)

@JsonClass(generateAdapter = true)
data class SlotChange(
    val type: String,                        // "ADDED" | "REPLACED" | "REMOVED"
    val slotId: String,
    val slotName: String,                    // display name (card name if present else slotName)
    val cardSet: String?                     // for the "(Flashfire)" suffix; null ok
)
```

### 2.3 Diff model (in-memory, not serialized) — `publish/model/PublishDiff.kt`
```kotlin
enum class ChangeType { ADDED, REPLACED, REMOVED }

data class SlotDelta(
    val type: ChangeType,
    val slotId: String,
    val displayName: String,     // cardName ?: slotName
    val cardSet: String?
)

data class PublishDiff(
    val deltas: List<SlotDelta>,
    val isFirstPublish: Boolean,     // true when no baseline binder.json existed (404)
    val pokedexComplete: Int,
    val pokedexTotal: Int
) {
    val hasChanges: Boolean get() = deltas.isNotEmpty()
    val added get() = deltas.count { it.type == ChangeType.ADDED }
    val replaced get() = deltas.count { it.type == ChangeType.REPLACED }
    val removed get() = deltas.count { it.type == ChangeType.REMOVED }
}
```

### 2.4 GitHub Contents API — Retrofit interface + DTOs
New file `data/remote/GitHubApi.kt`. GitHub Contents API base: `https://api.github.com/`. Endpoints operate on `repos/{owner}/{repo}/contents/{path}`.
```kotlin
interface GitHubApi {
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContent(
        @Header("Authorization") auth: String,       // "Bearer <PAT>"
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") ref: String = "main"
    ): retrofit2.Response<GitHubContentDto>            // Response<> so 404 is catchable, not thrown

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putContent(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Body body: GitHubPutRequest
    ): retrofit2.Response<GitHubPutResponse>
}

@JsonClass(generateAdapter = true)
data class GitHubContentDto(
    val content: String?,     // base64, may contain newlines
    val sha: String?,
    val encoding: String?
)

@JsonClass(generateAdapter = true)
data class GitHubPutRequest(
    val message: String,          // commit message
    val content: String,          // base64-encoded new file content
    val sha: String? = null,      // required on update, omitted on create
    val branch: String = "main"
)

@JsonClass(generateAdapter = true)
data class GitHubPutResponse(
    val content: GitHubContentDto?,
    val commit: GitHubCommitDto?
)

@JsonClass(generateAdapter = true)
data class GitHubCommitDto(val sha: String?)
```
- **Accept header:** add `@Headers("Accept: application/vnd.github+json")` on both methods (or a client-level interceptor). Use method-level `@Headers` to avoid touching the shared OkHttp client.
- **Base64:** use `android.util.Base64` with `Base64.NO_WRAP` when ENCODING for PUT. When DECODING GitHub's `content`, strip whitespace/newlines first (`content.replace("\n","")`) then `Base64.decode(..., Base64.DEFAULT)`.

### 2.5 Discord webhook — Retrofit interface + DTOs
New file `data/remote/DiscordApi.kt`. Webhook URL is a full URL supplied at call time; use `@Url`.
```kotlin
interface DiscordApi {
    @POST
    suspend fun sendWebhook(
        @Url webhookUrl: String,
        @Body body: DiscordWebhookPayload
    ): retrofit2.Response<Unit>
}

@JsonClass(generateAdapter = true)
data class DiscordWebhookPayload(
    val embeds: List<DiscordEmbed>
)

@JsonClass(generateAdapter = true)
data class DiscordEmbed(
    val title: String,
    val description: String,
    val url: String,          // link to page #latest anchor
    val color: Int? = 0xE3350D  // pokeball red; optional
)
```

### 2.6 DI wiring — extend `NetworkModule.kt`
Add three providers, mirroring the existing `@Named("tcgdex")` pattern:
```kotlin
@Provides @Singleton @Named("github")
fun provideGitHubRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
    Retrofit.Builder().baseUrl("https://api.github.com/").client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi)).build()

@Provides @Singleton
fun provideGitHubApi(@Named("github") retrofit: Retrofit): GitHubApi =
    retrofit.create(GitHubApi::class.java)

@Provides @Singleton @Named("discord")
fun provideDiscordRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
    Retrofit.Builder().baseUrl("https://discord.com/").client(okHttp)   // overridden by @Url
        .addConverterFactory(MoshiConverterFactory.create(moshi)).build()

@Provides @Singleton
fun provideDiscordApi(@Named("discord") retrofit: Retrofit): DiscordApi =
    retrofit.create(DiscordApi::class.java)
```
(`@Url` ignores the base URL but Retrofit still requires a valid base URL to build.)

### 2.7 `PublishRepository`
New file `publish/PublishRepository.kt`. `@Singleton`, `@Inject constructor`. Injects: `GitHubApi`, `DiscordApi`, `Moshi`, `PublishSettingsRepository` (§4), `BinderRepository`.

Constants:
```kotlin
private const val BINDER_JSON_PATH = "binder.json"
private const val CHANGELOG_JSON_PATH = "changelog.json"
private const val POKEDEX_TOTAL = 1025            // matches Gen IX end range
```

Public API — a single suspend function driving the whole flow, emitting progress via a callback so the ViewModel can render steps. Use a sealed progress type:
```kotlin
sealed interface PublishStep {
    data object FetchingCurrent : PublishStep
    data object Uploading : PublishStep
    data object NotifyingDiscord : PublishStep
    data class Done(val diff: PublishDiff) : PublishStep
    data object NoChanges : PublishStep
}

sealed interface PublishResult {
    data class Success(val diff: PublishDiff, val elapsedMs: Long) : PublishResult
    data object NoChanges : PublishResult
    data class Failure(val step: PublishStep, val message: String) : PublishResult
}

class PublishFailedException(val step: PublishStep, message: String) : Exception(message)
```

Methods (all `suspend`, `internal` except `publish`):
```kotlin
suspend fun publish(onStep: (PublishStep) -> Unit): PublishResult
```
Flow inside `publish`:
1. Load config from `PublishSettingsRepository`. If PAT blank OR owner/repo blank → return `Failure(FetchingCurrent, "GitHub not configured — add PAT and repo in Settings")` **before any network call**. (Webhook missing is handled later — non-fatal-ish; see edge cases.)
2. `onStep(FetchingCurrent)`. `getContent(...)` for `binder.json`.
   - HTTP 200 → decode base64 → parse `BinderSnapshot` baseline; keep `sha`.
   - HTTP 404 → baseline = null, `sha = null`, `isFirstPublish = true`.
   - Any other non-2xx / IOException → `throw PublishFailedException(FetchingCurrent, "Failed to fetch current binder.json: HTTP <code> / <msg>")`.
3. Build the new `BinderSnapshot` from the DB (§2.8). Compute `publishedAt` = `OffsetDateTime.now(ZoneOffset.of("+08:00"))` formatted ISO (SGT per user tz). (Use `java.time`; minSdk 26 so it is available without desugaring.)
4. Compute `PublishDiff` (§2.9). If `!diff.hasChanges && !isFirstPublish` → `onStep(NoChanges)`; return `PublishResult.NoChanges` — **no network writes, no webhook.**
5. `onStep(Uploading)`.
   a. PUT `binder.json` with `sha` (baseline sha or null). On non-2xx → `throw PublishFailedException(Uploading, "GitHub upload of binder.json failed: HTTP <code>")`. **This must happen before the webhook.**
   b. Fetch existing `changelog.json` (getContent). 404 → start empty `Changelog(emptyList())`, sha null. Prepend the new `ChangelogEntry`, trim to `CHANGELOG_MAX_ENTRIES`. PUT with its own sha. On failure → `throw PublishFailedException(Uploading, "GitHub upload of changelog.json failed: HTTP <code>")`.
      - **Note:** binder.json is already committed at this point. Failing changelog after binder.json succeeded is acceptable per spec (changelog is decorative) BUT we must still fail loudly and NOT fire the webhook — the page's binder is updated but the "recent updates" would be stale. Treat changelog PUT failure as a hard failure (throw) so the user retries; a retry re-diffs against the now-updated binder.json and will report "no changes" for binder but can still repair changelog... **Simplify for P0: on changelog PUT failure, throw `PublishFailedException(Uploading, ...)`. Do not fire webhook.** Document this known imperfection in a code comment (see Open Questions in spec).
6. `onStep(NotifyingDiscord)`. If webhook URL blank → skip Discord entirely, proceed to Done (log a warning). Else POST embed (§3). On webhook failure → `throw PublishFailedException(NotifyingDiscord, "Discord notification failed: HTTP <code>")` (binder is already published — the page is correct; only the announcement failed).
7. `onStep(Done(diff))`; return `PublishResult.Success(diff, elapsedMs)`.

Wrap steps 2–6 in try/catch converting `PublishFailedException` → `PublishResult.Failure(step, message)` and any other `Throwable` → `PublishResult.Failure(<currentStep>, throwable.message ?: "Unknown error")`. Track `currentStep` in a local var updated at each `onStep`.

### 2.8 Snapshot builder — `PublishRepository.buildSnapshot(...)`
- Read all slots: `binderRepository.getAllSlots()` returns `List<PokemonSlot>` (has `assignedCardId`, `assignedCardImageUrl`, but NOT card name/set). **Problem:** `PokemonSlot`/`BinderRepository.getAllSlots()` does not expose `assignedCardName`/`assignedCardSetName`. **Fix:** add a repository method that returns the raw entities, or extend `PokemonSlot`. Chosen approach for P0: **add `suspend fun getAllEntries(): List<MainBinderEntry>` to `BinderRepository`** (thin wrapper over `mainBinderDao.getAll()`), and build the snapshot from entities directly so name/set are available. Do NOT bloat `PokemonSlot` with card name/set for P0.
- Group into sections using the **same logic as `MainBinderViewModel.buildGrouped`** — extract the section-assignment rule into shared code to avoid drift:
  - Create `publish/SnapshotSections.kt` with a pure function `fun sectionNameFor(entry: MainBinderEntry): String` and the `GENERATIONS` list (copy the exact ranges/names from `MainBinderViewModel`). Section order: the 9 generations (I..IX) in order, then "Regional Variants", "Alternate Forms", "Mega Evolutions", "VMax".
  - BASE slots → generation section by `dexNumber` range. REGIONAL → "Regional Variants". ALTERNATE_FORM → "Alternate Forms". MEGA → "Mega Evolutions". GMAX → "VMax".
  - Within each section sort `compareBy({ dexNumber }, { dexOrder })`.
  - **Include ALL slots regardless of the app's per-section display toggles** — the public binder always shows the full Pokédex grid (empty slots greyed). (Rationale: `showMega` etc. are personal view prefs, not publish scope. The per-**binder** public toggle from §4 controls whether the whole Pokédex binder is published, not individual sections.)
- Each entry → `SnapshotSlot(dexNumber, slotName=pokemonName, slotType, slotId=pokemonId, cardId=assignedCardId, cardName=assignedCardName, cardSet=assignedCardSetName, imageUrl=assignedCardImageUrl)`.
- Wrap in one `SnapshotBinder(id="pokedex", name="Pokédex", sections=...)`.
- **Card History binder:** per §4 default OFF. If its toggle is ON, emit a second `SnapshotBinder(id="cardHistory", name="Card History", ...)` built from the secondary binder (`SecondaryBinderDao`). For P0, if the toggle is OFF (default), omit it. Structure of Card History: a single section "Card History" listing each secondary entry as a slot (cardId/imageUrl present; dexNumber from the entry's `pokemonId` lookup is not needed — use `dexNumber = 0`, `slotName = pokemonName`). **Keep this minimal; it is behind an off-by-default toggle.**
- Only include binders whose public toggle is ON. If Pokédex toggle is OFF and Card History OFF → snapshot has empty `binders` list; still valid, still diffable (everything becomes "removed"). Coder: allow it; do not special-case.

### 2.9 Diff logic — `PublishRepository.computeDiff(baseline: BinderSnapshot?, next: BinderSnapshot): PublishDiff`
- Flatten both snapshots to `Map<slotId, SnapshotSlot>` across all binders/sections.
- `isFirstPublish = (baseline == null)`.
- For first publish: `deltas` = for every slot in `next` that has a non-null `cardId`, one `ADDED` delta. (Do NOT list empty slots.) This is what powers "Initial publish — N cards" (the embed will summarize rather than list, see §3).
- For subsequent publishes, per slotId present in either map:
  - baseline cardId null/absent, next cardId non-null → **ADDED**.
  - baseline cardId non-null, next cardId null → **REMOVED**.
  - both non-null and `baseline.cardId != next.cardId` → **REPLACED**.
  - both non-null and equal → no change.
  - both null → no change.
  - slot only in next (new slot added to app) with a card → ADDED; slot only in baseline with a card, gone from next → REMOVED.
- `displayName` = `next.cardName ?: next.slotName` for ADDED/REPLACED; for REMOVED use `baseline.slotName` (or baseline.cardName) since it's gone from next.
- `cardSet` = the relevant snapshot's `cardSet`.
- `pokedexComplete` = count of `next` slots (in the "pokedex" binder) with non-null cardId. `pokedexTotal = POKEDEX_TOTAL` (1025). (Count only BASE-section completion for the "412/1025" figure — filter to `slotType == "BASE"` so megas/forms don't inflate it past 1025. Coder: pokedexComplete = count of BASE slots with a card; pokedexTotal = 1025.)

### 2.10 `PublishBinderUseCase`
New file `domain/PublishBinderUseCase.kt` (mirror `AssignCardUseCase` location/style). Thin orchestrator so the ViewModel doesn't touch the repo directly:
```kotlin
class PublishBinderUseCase @Inject constructor(
    private val publishRepository: PublishRepository
) {
    suspend fun publish(onStep: (PublishStep) -> Unit): PublishResult =
        publishRepository.publish(onStep)
}
```

### 2.11 Tests
- `PublishRepositoryTest` (unit, MockWebServer available via `libs.okhttp.mockwebserver`, or mockk the APIs — prefer mockk for the API interfaces + real Moshi):
  - No changes → returns `NoChanges`, no PUT calls, no webhook (`coVerify(exactly=0)`).
  - GitHub binder PUT fails → returns `Failure(step=Uploading, ...)`, webhook never called.
  - First publish (getContent 404) → `isFirstPublish=true`, deltas are all ADDED for occupied slots, PUT called with `sha=null`.
  - Webhook not configured → success without Discord call.
  - Diff correctness: added / replaced / removed produced from crafted baseline vs next snapshots (test `computeDiff` directly — make it `internal` and test it).
- `computeDiff` null-vs-present matrix test explicitly (baseline null card, next present = ADDED; etc.).

### Edge cases (recap)
- First-ever publish: 404 → create file (sha null), embed says "Initial publish — N cards".
- PAT not configured: fail fast before network, clear message.
- Webhook not configured: publish succeeds, Discord skipped, UI still shows Done.
- Network drop mid-flow: whichever step is active names itself in the failure.
- binder.json PUT ok but changelog PUT fails: hard failure, no webhook (documented imperfection).

---

## 3. Discord embed format

Builder: `publish/DiscordEmbedBuilder.kt`, pure function.
```kotlin
const val MAX_EMBED_LINES = 15
fun buildEmbed(diff: PublishDiff, pageUrl: String): DiscordEmbed
```
- **title:** `"Pokédex Binder updated"`
- **url:** `pageUrl` + `"#latest"` (e.g. `https://skylermayday.github.io/pokedex-binder/#latest`). Page base URL is a constant `PUBLIC_PAGE_URL` in `PublishRepository` companion (owner-derived: `https://<owner>.github.io/<repo>/`). Compute from configured owner/repo.
- **description:** built from lines.
  - **First publish:** single line `"Initial publish — ${diff.added} cards"` (do NOT enumerate).
  - **Otherwise:** one line per delta, ordered ADDED, then REPLACED, then REMOVED (stable, readable). Prefixes:
    - ADDED: `+ <displayName> (<cardSet>)` — omit ` (<set>)` if cardSet null.
    - REPLACED: `↻ <displayName> (card swapped)`
    - REMOVED: `− <displayName>` (use U+2212 MINUS SIGN `−`, matching spec, not ASCII hyphen).
  - Cap at `MAX_EMBED_LINES`; if more, keep first 15 and append `"…and ${remaining} more"`.
  - Append a blank line then the completion count: `"Pokédex: ${diff.pokedexComplete}/${diff.pokedexTotal}"`.
- Discord embed description hard limit is 4096 chars — with a 15-line cap we're safe; no extra truncation needed.

Test `DiscordEmbedBuilderTest`: >15 changes → 15 lines + "…and N more"; first publish → single "Initial publish" line; set-null ADDED omits parens; completion line present; url ends `#latest`.

---

## 4. Settings — GitHub config, PAT, webhook, per-binder toggles (EncryptedSharedPreferences for secrets)

### 4.1 Add dependency
`gradle/libs.versions.toml`:
```toml
[versions]
securityCrypto = "1.1.0-alpha06"
[libraries]
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
```
`app/build.gradle.kts` dependencies:
```kotlin
implementation(libs.androidx.security.crypto)
```
> `security-crypto` 1.1.0-alpha06 is the commonly used version; if the Coder finds a newer stable (`1.1.0-beta01`/`1.0.0`) at build time, use the resolvable one. 1.0.0 is stable but has a known keyset-corruption edge; alpha06 is widely used. Coder: pick whichever resolves; do not block on this.

### 4.2 New `PublishSettingsRepository`
New file `repository/PublishSettingsRepository.kt`. **Non-secret** fields (owner, repo, per-binder toggles) → DataStore (reuse the existing `app_settings` DataStore pattern or a new one). **Secrets** (PAT, webhook URL) → `EncryptedSharedPreferences`.

Rationale for splitting rather than folding into `SettingsRepository`: keeps secret storage isolated and avoids putting `EncryptedSharedPreferences` (SharedPreferences-based) into the DataStore-based `SettingsRepository`. Both can coexist.

```kotlin
data class PublishConfig(
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubPat: String = "",
    val discordWebhookUrl: String = "",
    val publishPokedex: Boolean = true,      // default ON
    val publishCardHistory: Boolean = false  // default OFF
)

@Singleton
class PublishSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // EncryptedSharedPreferences for PAT + webhook
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "publish_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    // owner/repo/toggles via DataStore (new datastore name "publish_settings")
    // Flow<PublishConfig> combining DataStore values + securePrefs reads
    suspend fun getConfig(): PublishConfig
    val config: Flow<PublishConfig>          // toggles+owner+repo reactive; secrets read on demand
    suspend fun setGithubOwner(v: String)
    suspend fun setGithubRepo(v: String)
    suspend fun setGithubPat(v: String)       // securePrefs.edit().putString(...).apply()
    suspend fun setDiscordWebhookUrl(v: String)
    suspend fun setPublishPokedex(v: Boolean)
    suspend fun setPublishCardHistory(v: Boolean)
}
```
- Keys: DataStore `stringPreferencesKey("github_owner")`, `("github_repo")`, `booleanPreferencesKey("publish_pokedex")` (default true — **remember DataStore default: `prefs[KEY] ?: true`**), `("publish_card_history")` (default false). SecurePrefs keys `"github_pat"`, `"discord_webhook_url"`.
- `config: Flow<PublishConfig>` maps DataStore + reads secrets via `securePrefs.getString(...)` inside the map. (Secrets aren't reactive but that's fine — they're read fresh at publish time via `getConfig()`.)
- **Note:** `EncryptedSharedPreferences.create` is synchronous disk I/O; call it lazily and access off the main thread (repository methods are `suspend` / called from `viewModelScope` on `Dispatchers.IO` — wrap secure reads/writes in `withContext(Dispatchers.IO)`).

### 4.3 Extend `SettingsViewModel`
Inject `PublishSettingsRepository`. Expose:
```kotlin
val publishConfig: StateFlow<PublishConfig>   // stateIn, initial PublishConfig()
fun setGithubOwner(v: String) = launch{...}
fun setGithubRepo(v: String) = ...
fun setGithubPat(v: String) = ...
fun setDiscordWebhookUrl(v: String) = ...
fun setPublishPokedex(v: Boolean) = ...
fun setPublishCardHistory(v: Boolean) = ...
```
(Keep existing `settings` StateFlow untouched.)

### 4.4 Extend `SettingsScreen`
Add a new section after the existing ones:
```kotlin
SectionHeader("Public Binder / Sharing")
```
Fields (reuse `OutlinedTextField` + the password visibility pattern already in the file for PAT and webhook):
- `OutlinedTextField` GitHub Owner (`publishConfig.githubOwner`, `setGithubOwner`).
- `OutlinedTextField` GitHub Repo (default hint "pokedex-binder").
- `OutlinedTextField` GitHub PAT — password field (`PasswordVisualTransformation` + eye toggle), supportingText "Fine-grained token, Contents read/write on the repo only".
- `OutlinedTextField` Discord Webhook URL — password-style (it's a secret-ish URL), supportingText "Server Settings → Integrations → Webhooks".
- `SettingToggleItem` "Publish Pokédex" (default ON) → `setPublishPokedex`.
- `SettingToggleItem` "Publish Card History" (default OFF) → `setPublishCardHistory`.
- A `Button("Publish now")` that triggers publish (see §5) — routes to the shared publish dialog. Reuse the same dialog component as the top-bar button.

### 4.5 Publish button in main binder top bar
`MainBinderScreen.kt` — add to `TopAppBar.actions` a new `IconButton` with `Icons.Default.CloudUpload` (from material-icons-extended, already a dep) before/after the Settings icon:
```kotlin
IconButton(onClick = onPublishClick) {
    Icon(Icons.Default.CloudUpload, contentDescription = "Publish")
}
```
Add `onPublishClick: () -> Unit = {}` param to `MainBinderScreen`. Wire in `AppNavigation` to open the publish dialog (§5). Since publish is a modal action (not a screen), the cleanest wiring: host the publish dialog + a `PublishViewModel` at the `MainBinderScreen` level (state in the screen), OR host it in `AppNavigation` as an overlay. **Chosen: host `PublishDialog` inside `MainBinderScreen`** controlled by a local `var showPublish by remember { mutableStateOf(false) }`; `onPublishClick` sets it true. Settings screen hosts its own copy the same way. This keeps nav routes untouched.

---

## 5. Publish progress UI (step-indicator dialog with elapsed time)

No existing async-progress dialog in the app (SlotDetail/ManualSearch just use Flows with implicit loading). Build a new reusable composable.

### 5.1 `PublishViewModel`
New file `ui/publish/PublishViewModel.kt`.
```kotlin
sealed interface PublishUiState {
    data object Idle : PublishUiState
    data class Running(val step: PublishStep, val elapsedMs: Long) : PublishUiState
    data class Done(val diff: PublishDiff, val elapsedMs: Long) : PublishUiState
    data object NoChanges : PublishUiState
    data class Error(val step: PublishStep, val message: String, val elapsedMs: Long) : PublishUiState
}

@HiltViewModel
class PublishViewModel @Inject constructor(
    private val publishBinderUseCase: PublishBinderUseCase
) : ViewModel() {
    val state: StateFlow<PublishUiState>   // MutableStateFlow(Idle)
    fun startPublish() { /* launch, tick elapsed every ~200ms while Running, call useCase.publish{ step -> update } */ }
    fun dismiss() { /* reset to Idle */ }
}
```
- Elapsed time: capture `start = SystemClock.elapsedRealtime()` at launch; a `while(active)` ticker updating `elapsedMs` (or compute elapsed at each step emission + a coroutine that updates every 200ms so the dialog shows a live timer per the ui-progress rule). On terminal state, freeze elapsed = final.
- Map `PublishResult` → `Done` / `NoChanges` / `Error(step, message, elapsed)`.

### 5.2 `PublishDialog` composable
New file `ui/publish/PublishDialog.kt`. `AlertDialog` (Material3) driven by `PublishUiState`:
- **Running:** title "Publishing…". Body = a vertical step list: "Fetching current", "Uploading", "Notifying Discord", "Done". Each row shows: a check (done), a `CircularProgressIndicator` (16dp) for the active step, or a dim/idle icon for pending. Determine per-step status from the current `PublishStep`'s ordinal (FetchingCurrent=0, Uploading=1, NotifyingDiscord=2, Done=3). Below the list show `"Elapsed: %.1fs"`. Per ui-progress rule: live timer visible, steps act as a live log. No dismiss while running (or allow cancel — P0: no cancel; dialog is non-dismissable while Running: `AlertDialog(onDismissRequest = { if (state !is Running) dismiss() })`).
- **Done:** title "Published". Body summary: "Added X, Replaced Y, Removed Z • Pokédex A/1025" + "Took %.1fs". Confirm button "Close" → `dismiss()`. Optional "View page" button opening `PUBLIC_PAGE_URL` via `Intent(ACTION_VIEW)`.
- **NoChanges:** title "No changes", body "No changes since last publish." — **also acceptable to show as a Toast per spec** ("toast \"No changes since last publish\"") instead of a dialog state; either is fine. Coder: show it in the dialog for consistency, single confirm "OK".
- **Error:** title "Publish failed". Body: `"Failed at: ${stepLabel(step)}"` + the message. Confirm "Close". Name the step clearly (map FetchingCurrent→"Fetching current", etc.).
- Host: in `MainBinderScreen` and `SettingsScreen`, `val publishVm: PublishViewModel = hiltViewModel()`; when `showPublish` becomes true call `publishVm.startPublish()` once (LaunchedEffect), render `PublishDialog(state, onDismiss={ showPublish=false; publishVm.dismiss() })`.

### 5.3 Tests
- `PublishViewModelTest` (turbine + coroutines-test): startPublish emits Running(Fetching) → ... → Done; failure path emits Error with the right step; NoChanges path.

---

## 6. Static viewer (plain HTML/CSS/JS, no build step)

**Location in THIS repo:** create under `web/pokedex-binder-site/`. **This is destined for a SEPARATE GitHub repo `SkylerMayday/pokedex-binder` (GitHub Pages).** Add a `web/pokedex-binder-site/README.md` stating clearly: "These files are the GitHub Pages site. Push the CONTENTS of this directory to the root of `SkylerMayday/pokedex-binder` (branch `main`, Pages source = root). `binder.json` and `changelog.json` are written to that repo's root by the app's Publish action; do not commit sample copies as the source of truth." Coder: put a note at the top of the plan output too.

Files:
- `web/pokedex-binder-site/index.html`
- `web/pokedex-binder-site/styles.css`
- `web/pokedex-binder-site/app.js`
- `web/pokedex-binder-site/README.md` (deployment note above)
- `web/pokedex-binder-site/sample-binder.json` + `sample-changelog.json` — **for local preview only**, clearly named `sample-*` so they're never mistaken for the live files. `app.js` fetches `binder.json`/`changelog.json` (same-origin, relative paths) and falls back to `sample-*` if those 404 (so the page renders in the repo before the first publish and for local dev).

### 6.1 Data fetch
`app.js`:
```js
const BINDER_URL = 'binder.json';
const CHANGELOG_URL = 'changelog.json';
async function loadJson(url, fallback){ try{ const r=await fetch(url,{cache:'no-store'}); if(!r.ok) throw 0; return await r.json(); } catch(e){ const f=await fetch(fallback); return f.ok? f.json():null; } }
```
Load binder + changelog in parallel (`Promise.all`), then render.

### 6.2 Rendering
- **Header:** binder name + `publishedAt` (formatted human-readable) + overall completion bar.
- **Recent updates panel** (from `changelog.json`), placed at top with `id="latest"` on the newest entry so `#latest` scrolls to it. Newest entry expanded (show its `changes` list with +/↻/− icons and set names + summary counts + completion). Older entries collapsed (`<details>` elements).
- **Sections:** for each binder in `binders` (P0: just "pokedex", plus "cardHistory" if present), render each `section` with a heading and a completion bar for that section (`filled = slots with cardId / total slots`). Section names come straight from the JSON (already the app's names).
- **Grid:** CSS grid of slots (mirror the app's 3-per-row on mobile, more on wider). Each slot:
  - has card → lazy-loaded `<img loading="lazy" src="imageUrl">`.
  - empty → greyed placeholder showing `#dexNumber` + `slotName` (mirror the app's `SlotCard` empty state).
- **Completion bars:** overall = BASE-section filled / 1025 (match the app's number). Per-section = filled/total in that section. Simple `<div class="bar"><div class="fill" style="width:X%"></div></div>`.
- **Lazy images:** native `loading="lazy"` on `<img>`.

### 6.3 Mobile
- Viewport meta tag; responsive grid via `grid-template-columns: repeat(auto-fill, minmax(90px, 1fr))`; touch-friendly; recent-updates panel stacks above grid. Test at 375px width.

### 6.4 No framework / no build
- Vanilla JS, one `styles.css`, no bundler, no npm. Must open directly via `file://` for dev (hence `sample-*` fallback), and work when served by GitHub Pages.

### 6.5 (Optional, low effort) `CNAME` note
Do NOT add a CNAME file (custom domain is P2). Just mention in README that P2 will add it.

---

## Build & verification checklist (Coder must run before handoff)
1. `./gradlew :app:compileDebugKotlin` (or full assembleDebug) — compiles clean.
2. `./gradlew :app:testDebugUnitTest` — all unit tests pass (updated AssignCardUseCaseTest + new PublishRepositoryTest, DiscordEmbedBuilderTest, PublishViewModelTest, computeDiff test).
3. Room migration test green (or the raw-SQLite fallback assertion).
4. Manually load `web/pokedex-binder-site/index.html` with the `sample-*` files (a browser / the preview tooling) at mobile + desktop widths; confirm grid, bars, recent-updates `#latest` anchor, lazy images, greyed empty slots.
5. Confirm no new lib beyond `androidx.security:security-crypto` was added; Retrofit/Moshi/OkHttp reused.

## File manifest (new)
- `app/.../data/local/` — (edit) MainBinderEntry.kt, PokedexDatabase.kt, MainBinderDao unaffected.
- `app/.../di/` — (edit) NetworkModule.kt, DatabaseModule.kt.
- `app/.../data/remote/GitHubApi.kt`, `DiscordApi.kt` (new).
- `app/.../publish/PublishRepository.kt`, `SnapshotSections.kt`, `DiscordEmbedBuilder.kt` (new).
- `app/.../publish/model/BinderSnapshot.kt`, `Changelog.kt`, `PublishDiff.kt` (new).
- `app/.../domain/PublishBinderUseCase.kt` (new); (edit) AssignCardUseCase.kt.
- `app/.../repository/PublishSettingsRepository.kt` (new); (edit) BinderRepository.kt.
- `app/.../ui/publish/PublishViewModel.kt`, `PublishDialog.kt` (new).
- `app/.../ui/settings/` — (edit) SettingsViewModel.kt, SettingsScreen.kt.
- `app/.../ui/mainbinder/MainBinderScreen.kt` — (edit) add Publish action + dialog host.
- `app/.../ui/navigation/AppNavigation.kt` — (edit) pass `onPublishClick` (or none if dialog hosted inside screen — preferred, may need no nav change).
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — (edit) add security-crypto.
- `web/pokedex-binder-site/{index.html,styles.css,app.js,README.md,sample-binder.json,sample-changelog.json}` (new).
- Tests: (edit) AssignCardUseCaseTest.kt; (new) PublishRepositoryTest.kt, DiscordEmbedBuilderTest.kt, PublishViewModelTest.kt, Migration5to6Test.kt.
