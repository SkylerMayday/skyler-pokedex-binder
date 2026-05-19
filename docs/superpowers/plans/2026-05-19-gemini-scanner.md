# Gemini AI Scanner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ML Kit OCR scanning engine in PokedexBinderV2 with Gemini 2.5 Flash vision AI, add on-demand perceptual hashing for disambiguation, wire the camera scanner into navigation, and add scan/search choice bottom sheets at every card entry point.

**Architecture:** `ScannerViewModel` calls `GeminiCardScanner` (Gemini REST via OkHttp) instead of `OcrCardParser`. Candidate cards from the TCG API are disambiguated by `PerceptualHasher` which downloads only the 3–5 candidate images on-demand. The user's Gemini API key lives in DataStore. `ScannerViewModel` absorbs slot-assignment logic so `ScannerScreen` is self-contained.

**Tech Stack:** Kotlin, Hilt, CameraX, OkHttp (already in project), Moshi, DataStore, MockWebServer (new test dep), Compose Material3 `ModalBottomSheet`.

---

## File Map

| Action | File |
|---|---|
| **Create** | `app/src/main/java/com/skyler/pokedexbinder/domain/ParsedCardInfo.kt` |
| **Create** | `app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt` |
| **Create** | `app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt` |
| **Create** | `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt` |
| **Create** | `app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt` |
| **Create** | `app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/repository/SettingsRepository.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsViewModel.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsScreen.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt` |
| **Modify** | `app/src/main/java/com/skyler/pokedexbinder/ui/secondarybinder/SecondaryBinderScreen.kt` |
| **Delete** | `app/src/main/java/com/skyler/pokedexbinder/domain/OcrCardParser.kt` |
| **Delete** | `app/src/main/java/com/skyler/pokedexbinder/domain/CardNameTranslator.kt` |
| **Modify** | `gradle/libs.versions.toml` — add mockwebserver, remove ML Kit |
| **Modify** | `app/build.gradle.kts` — add mockwebserver test dep, remove ML Kit deps |

---

## Task 1: Add MockWebServer test dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add mockwebserver entry to libs.versions.toml**

In `gradle/libs.versions.toml`, under `[libraries]`, add after `okhttp-logging`:
```toml
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
```

- [ ] **Step 2: Add test dependency in build.gradle.kts**

In `app/build.gradle.kts`, add after the `testImplementation(libs.turbine)` line:
```kotlin
testImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 3: Sync and verify**

Run: `./gradlew :app:dependencies --configuration testRuntimeClasspath | grep mockwebserver`

Expected output contains: `com.squareup.okhttp3:mockwebserver:4.12.0`

- [ ] **Step 4: Commit**
```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add MockWebServer test dependency"
```

---

## Task 2: Create ParsedCardInfo

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/domain/ParsedCardInfo.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.skyler.pokedexbinder.domain

data class ParsedCardInfo(
    val cardName: String?,
    val cardNumber: String?,
    val setTotal: String?,
    val hp: String?,
    val artist: String?
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/domain/ParsedCardInfo.kt
git commit -m "feat: add ParsedCardInfo domain model"
```

---

## Task 3: Create GeminiCardScanner

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt`
- Create: `app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt`:

```kotlin
package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiCardScannerTest {

    private lateinit var server: MockWebServer
    private lateinit var scanner: GeminiCardScanner
    private val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        scanner = GeminiCardScanner(OkHttpClient(), moshi, server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `scan returns ParsedCardInfo on success`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Pikachu\",\"number\":\"25\",\"setTotal\":\"185\",\"hp\":\"60\",\"artist\":\"Atsuko Nishida\"}"}]}}]}
        """.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertEquals("25", result.cardNumber)
        assertEquals("185", result.setTotal)
        assertEquals("60", result.hp)
        assertEquals("Atsuko Nishida", result.artist)
    }

    @Test
    fun `scan returns nulls for missing fields`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Pikachu\",\"number\":null,\"setTotal\":null,\"hp\":null,\"artist\":null}"}]}}]}
        """.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertNull(result.cardNumber)
    }

    @Test(expected = RateLimitException::class)
    fun `scan throws RateLimitException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        scanner.scan(bitmap, "test-key")
    }

    @Test(expected = java.io.IOException::class)
    fun `scan throws IOException on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        scanner.scan(bitmap, "test-key")
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.GeminiCardScannerTest"`

Expected: FAILED — `GeminiCardScanner` does not exist yet

- [ ] **Step 3: Create GeminiCardScanner.kt**

Create `app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt`:

```kotlin
package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

class RateLimitException : IOException("Rate limited by Gemini API")

@JsonClass(generateAdapter = true)
data class GeminiResponse(val candidates: List<GeminiCandidate>? = null)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(val content: GeminiContent? = null)

@JsonClass(generateAdapter = true)
data class GeminiContent(val parts: List<GeminiPart>? = null)

@JsonClass(generateAdapter = true)
data class GeminiPart(val text: String? = null)

@JsonClass(generateAdapter = true)
data class GeminiCardResult(
    val name: String? = null,
    val number: String? = null,
    val setTotal: String? = null,
    val hp: String? = null,
    val artist: String? = null
)

private const val GEMINI_BASE_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

class GeminiCardScanner @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String = GEMINI_BASE_URL
) {
    private val responseAdapter by lazy { moshi.adapter(GeminiResponse::class.java) }
    private val cardResultAdapter by lazy { moshi.adapter(GeminiCardResult::class.java) }

    suspend fun scan(bitmap: Bitmap, apiKey: String): ParsedCardInfo = withContext(Dispatchers.IO) {
        val base64 = bitmapToBase64(bitmap)
        val prompt = "Analyze this Pokémon TCG card. Return ONLY a JSON object with no markdown:\n" +
                "{\"name\":\"pokemon name or null\",\"number\":\"card number without leading zeros or null\"," +
                "\"setTotal\":\"total cards in set or null\",\"hp\":\"HP value or null\",\"artist\":\"artist name or null\"}"
        val body = buildRequestJson(prompt, base64)
        val request = Request.Builder()
            .url("$baseUrl?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (response.code == 429) throw RateLimitException()
        if (!response.isSuccessful) throw IOException("Gemini error: ${response.code}")

        val geminiResponse = responseAdapter.fromJson(response.body!!.string())
            ?: throw IOException("Empty Gemini response")
        val text = geminiResponse.candidates
            ?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw IOException("No text in Gemini response")

        val result = runCatching { cardResultAdapter.fromJson(text) }.getOrNull() ?: GeminiCardResult()
        ParsedCardInfo(
            cardName = result.name,
            cardNumber = result.number,
            setTotal = result.setTotal,
            hp = result.hp,
            artist = result.artist
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildRequestJson(prompt: String, base64: String): String =
        """{"contents":[{"parts":[{"text":${JSONObject.quote(prompt)}},{"inline_data":{"mime_type":"image/jpeg","data":"$base64"}}]}],"generationConfig":{"response_mime_type":"application/json"}}"""
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.GeminiCardScannerTest"`

Expected: 4 tests pass

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/domain/GeminiCardScanner.kt \
        app/src/test/java/com/skyler/pokedexbinder/domain/GeminiCardScannerTest.kt
git commit -m "feat: add GeminiCardScanner with Gemini 2.5 Flash"
```

---

## Task 4: Create PerceptualHasher

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt`
- Create: `app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt`:

```kotlin
package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.skyler.pokedexbinder.data.model.TcgCard
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerceptualHasherTest {

    private lateinit var server: MockWebServer
    private lateinit var hasher: PerceptualHasher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        hasher = PerceptualHasher(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `hammingDistance of identical hashes is zero`() {
        assertEquals(0, hasher.hammingDistance(0xFFL, 0xFFL))
    }

    @Test
    fun `hammingDistance of all-zeros vs all-ones is 64`() {
        assertEquals(64, hasher.hammingDistance(0L, -1L))
    }

    @Test
    fun `computeHash returns same value for identical bitmaps`() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertEquals(hasher.computeHash(bitmap), hasher.computeHash(bitmap))
    }

    @Test
    fun `findBestMatch returns null for empty list`() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertNull(hasher.findBestMatch(emptyList(), bitmap))
    }

    @Test
    fun `findBestMatch returns only card when list has one element`() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val card = TcgCard("id1", "Pikachu", "25", "Base Set", "http://example.com/img.jpg", listOf("Pikachu"))
        val result = hasher.findBestMatch(listOf(card), bitmap)
        assertEquals(card, result)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PerceptualHasherTest"`

Expected: FAILED — `PerceptualHasher` does not exist yet

- [ ] **Step 3: Create PerceptualHasher.kt**

Create `app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt`:

```kotlin
package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skyler.pokedexbinder.data.model.TcgCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class PerceptualHasher @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = ""
) {
    fun computeHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
        val pixels = IntArray(256)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        val grays = pixels.map { px ->
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val mean = grays.average()
        return grays.foldIndexed(0L) { i, acc, gray ->
            if (gray > mean) acc or (1L shl i) else acc
        }
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    suspend fun findBestMatch(candidates: List<TcgCard>, capturedBitmap: Bitmap): TcgCard? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val capturedHash = computeHash(capturedBitmap)
        return withContext(Dispatchers.IO) {
            candidates.minByOrNull { card ->
                val url = if (baseUrl.isNotEmpty()) "$baseUrl${card.imageUrl}" else card.imageUrl
                val cardBitmap = downloadBitmap(url) ?: return@minByOrNull Int.MAX_VALUE
                hammingDistance(capturedHash, computeHash(cardBitmap))
            }
        }
    }

    private suspend fun downloadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = okHttpClient.newCall(Request.Builder().url(url).build())
                .execute().body!!.bytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PerceptualHasherTest"`

Expected: 5 tests pass

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/domain/PerceptualHasher.kt \
        app/src/test/java/com/skyler/pokedexbinder/domain/PerceptualHasherTest.kt
git commit -m "feat: add PerceptualHasher for on-demand card image disambiguation"
```

---

## Task 5: Extend TcgCardDto, TcgCard, and CardSearchRepository

**Files:**
- Modify: `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt`
- Modify: `app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt`
- Modify: `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt`

- [ ] **Step 1: Add hp and artist to TcgCardDto**

Replace the `TcgCardDto` data class in `TcgCardDto.kt`:

```kotlin
@JsonClass(generateAdapter = true)
data class TcgCardDto(
    val id: String,
    val name: String,
    val number: String,
    val set: TcgSetDto,
    val images: TcgImagesDto,
    val hp: String? = null,
    val artist: String? = null
)
```

- [ ] **Step 2: Add hp and artist to TcgCard**

Replace the `TcgCard` data class in `TcgCard.kt`:

```kotlin
data class TcgCard(
    val id: String,
    val name: String,
    val number: String,
    val setName: String,
    val imageUrl: String,
    val pokemonNames: List<String>,
    val hp: String? = null,
    val artist: String? = null
) {
    val primaryPokemonName: String get() = pokemonNames.firstOrNull() ?: name
}
```

- [ ] **Step 3: Update toDomain() and add searchByParsedInfo() in CardSearchRepository**

Replace the full `CardSearchRepository.kt`:

```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardSearchRepository @Inject constructor(
    private val api: PokemonTcgApi
) {
    suspend fun searchByParsedInfo(info: ParsedCardInfo): List<TcgCard> {
        val name = info.cardName
        val number = info.cardNumber
        val total = info.setTotal
        val queries = buildList {
            if (name != null && number != null && total != null)
                add("${nameQuery(name)} number:\"$number\" set.total:$total")
            if (name != null && number != null)
                add("${nameQuery(name)} number:\"$number\"")
            if (number != null && total != null)
                add("number:\"$number\" set.total:$total")
            if (name != null)
                add(nameQuery(name))
            if (number != null)
                add("number:\"$number\"")
        }
        for (query in queries) {
            val results = safeSearch(query)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    suspend fun searchByNameAndNumber(name: String, number: String): List<TcgCard> =
        safeSearch("${nameQuery(name)} number:\"$number\"")
            .ifEmpty { safeSearch(nameQuery(name)) }

    suspend fun searchByName(name: String): List<TcgCard> =
        safeSearch(nameQuery(name))

    suspend fun searchByNumber(number: String): List<TcgCard> =
        safeSearch("number:\"$number\"")

    suspend fun searchByNumberAndTotal(number: String, total: String): List<TcgCard> =
        safeSearch("number:\"$number\" set.total:$total")
            .ifEmpty { safeSearch("number:\"$number\"") }

    suspend fun searchByDexNumber(dexNumber: Int): List<TcgCard> =
        safeSearch("nationalPokedexNumbers:$dexNumber")

    /** Wildcard name query safe for the Pokémon TCG API (Lucene dialect).
     *  Spaces/hyphens/colons → * to avoid Lucene operator conflicts. */
    private fun nameQuery(name: String): String {
        val sanitized = name.trim()
            .replace(" ", "*")
            .replace("-", "*")
            .replace(":", "*")
        return "name:*$sanitized*"
    }

    private suspend fun safeSearch(query: String): List<TcgCard> = try {
        api.searchCards(query = query).data.map { it.toDomain() }
    } catch (e: HttpException) {
        emptyList()
    }

    private fun TcgCardDto.toDomain() = TcgCard(
        id = id,
        name = name,
        number = number,
        setName = set.name,
        imageUrl = images.large,
        pokemonNames = name.split(" & ").map { it.substringBefore(" ").trim() },
        hp = hp,
        artist = artist
    )
}
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt \
        app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt \
        app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt
git commit -m "feat: add hp/artist to TcgCard and multi-field searchByParsedInfo"
```

---

## Task 6: Settings — persist Gemini API key

**Files:**
- Modify: `app/src/main/java/com/skyler/pokedexbinder/repository/SettingsRepository.kt`
- Modify: `app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add geminiApiKey to AppSettings and SettingsRepository**

Replace the full `SettingsRepository.kt`:

```kotlin
package com.skyler.pokedexbinder.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val showRegional: Boolean = false,
    val showMega: Boolean = false,
    val showGmax: Boolean = false,
    val showSecondaryBinder: Boolean = false,
    val geminiApiKey: String = ""
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SHOW_REGIONAL = booleanPreferencesKey("show_regional")
        val SHOW_MEGA = booleanPreferencesKey("show_mega")
        val SHOW_GMAX = booleanPreferencesKey("show_gmax")
        val SHOW_SECONDARY_BINDER = booleanPreferencesKey("show_secondary_binder")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            showRegional = prefs[Keys.SHOW_REGIONAL] ?: false,
            showMega = prefs[Keys.SHOW_MEGA] ?: false,
            showGmax = prefs[Keys.SHOW_GMAX] ?: false,
            showSecondaryBinder = prefs[Keys.SHOW_SECONDARY_BINDER] ?: false,
            geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: ""
        )
    }

    suspend fun getGeminiApiKey(): String =
        context.dataStore.data.map { it[Keys.GEMINI_API_KEY] ?: "" }.first()

    suspend fun setShowRegional(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_REGIONAL] = enabled }
    }

    suspend fun setShowMega(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_MEGA] = enabled }
    }

    suspend fun setShowGmax(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_GMAX] = enabled }
    }

    suspend fun setShowSecondaryBinder(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SECONDARY_BINDER] = enabled }
    }

    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { it[Keys.GEMINI_API_KEY] = key }
    }
}
```

- [ ] **Step 2: Add geminiApiKey methods to SettingsViewModel**

Replace the full `SettingsViewModel.kt`:

```kotlin
package com.skyler.pokedexbinder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.repository.AppSettings
import com.skyler.pokedexbinder.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setShowRegional(v: Boolean) = viewModelScope.launch { settingsRepository.setShowRegional(v) }
    fun setShowMega(v: Boolean) = viewModelScope.launch { settingsRepository.setShowMega(v) }
    fun setShowGmax(v: Boolean) = viewModelScope.launch { settingsRepository.setShowGmax(v) }
    fun setShowSecondaryBinder(v: Boolean) = viewModelScope.launch { settingsRepository.setShowSecondaryBinder(v) }
    fun setGeminiApiKey(key: String) = viewModelScope.launch { settingsRepository.setGeminiApiKey(key) }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/repository/SettingsRepository.kt \
        app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsViewModel.kt
git commit -m "feat: persist Gemini API key in DataStore"
```

---

## Task 7: Settings UI — API key field

**Files:**
- Modify: `app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add API key field to SettingsScreen**

Replace the full `SettingsScreen.kt`:

```kotlin
package com.skyler.pokedexbinder.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    var apiKeyText by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {

            SectionHeader("Gemini AI Scanner")

            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it; viewModel.setGeminiApiKey(it) },
                label = { Text("Gemini API Key") },
                supportingText = { Text("Free key at aistudio.google.com") },
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (apiKeyVisible) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader("Binder Sections")

            SettingToggleItem(
                title = "Regional Variants",
                description = "Show Alolan, Galarian, Hisuian and other regional forms",
                checked = settings.showRegional,
                onCheckedChange = { viewModel.setShowRegional(it) }
            )
            HorizontalDivider()
            SettingToggleItem(
                title = "Mega Evolutions",
                description = "Show Mega Evolution slots",
                checked = settings.showMega,
                onCheckedChange = { viewModel.setShowMega(it) }
            )
            HorizontalDivider()
            SettingToggleItem(
                title = "V-Max",
                description = "Show Gigantamax / V-Max slots",
                checked = settings.showGmax,
                onCheckedChange = { viewModel.setShowGmax(it) }
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader("Navigation")

            HorizontalDivider()
            SettingToggleItem(
                title = "Secondary Binder",
                description = "Show the Secondary Binder tab in the bottom bar",
                checked = settings.showSecondaryBinder,
                onCheckedChange = { viewModel.setShowSecondaryBinder(it) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/settings/SettingsScreen.kt
git commit -m "feat: add Gemini API key field to Settings screen"
```

---

## Task 8: ImageProxy extension

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt`

- [ ] **Step 1: Create ImageProxy → Bitmap extension**

Create `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt`:

```kotlin
package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val vuBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val vuSize = vuBuffer.remaining()
    val nv21 = ByteArray(ySize + vuSize)
    yBuffer.get(nv21, 0, ySize)
    vuBuffer.get(nv21, ySize, vuSize)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
    val bytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ImageProxyExt.kt
git commit -m "feat: add ImageProxy.toBitmap() extension for CameraX"
```

---

## Task 9: Upgrade ScannerViewModel

**Files:**
- Modify: `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt`
- Modify: `app/src/test/java/com/skyler/pokedexbinder/ui/MainBinderViewModelTest.kt` (if it references old ScannerState — check first)

- [ ] **Step 1: Replace ScannerViewModel.kt**

```kotlin
package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.GeminiCardScanner
import com.skyler.pokedexbinder.domain.PerceptualHasher
import com.skyler.pokedexbinder.domain.RateLimitException
import com.skyler.pokedexbinder.domain.SmartThresholdUseCase
import com.skyler.pokedexbinder.domain.AssignCardUseCase
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.CardSearchRepository
import com.skyler.pokedexbinder.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ScannerState {
    object Idle : ScannerState()
    object Scanning : ScannerState()
    object NoApiKey : ScannerState()
    object RateLimited : ScannerState()
    data class HighConfidence(val card: TcgCard) : ScannerState()
    data class LowConfidence(val cards: List<TcgCard>) : ScannerState()
    data class Error(val message: String) : ScannerState()
    data class Success(val card: TcgCard, val slotName: String) : ScannerState()
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val geminiCardScanner: GeminiCardScanner,
    private val cardSearchRepository: CardSearchRepository,
    private val perceptualHasher: PerceptualHasher,
    private val smartThresholdUseCase: SmartThresholdUseCase,
    private val assignCardUseCase: AssignCardUseCase,
    private val binderRepository: BinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val slotId: String = savedStateHandle.get<String>("slotId") ?: ""
    private val slotName: String = savedStateHandle.get<String>("pokemonName") ?: ""
    private val isSecondary: Boolean = savedStateHandle.get<Boolean>("isSecondary") ?: false

    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    val state: StateFlow<ScannerState> = _state

    private var capturedBitmap: Bitmap? = null

    fun processImage(imageProxy: ImageProxy) {
        _state.value = ScannerState.Scanning
        viewModelScope.launch {
            val apiKey = settingsRepository.getGeminiApiKey()
            if (apiKey.isBlank()) {
                imageProxy.close()
                _state.value = ScannerState.NoApiKey
                return@launch
            }
            try {
                val bitmap = imageProxy.toBitmap()
                imageProxy.close()
                capturedBitmap = bitmap

                val parsed = geminiCardScanner.scan(bitmap, apiKey)
                val candidates = cardSearchRepository.searchByParsedInfo(parsed)

                if (candidates.isEmpty()) {
                    _state.value = ScannerState.LowConfidence(emptyList())
                    return@launch
                }

                val best = perceptualHasher.findBestMatch(candidates, bitmap) ?: candidates.first()
                val confidence = smartThresholdUseCase.evaluate(candidates, best.name, best.number)
                _state.value = if (confidence.isHighConfidence && confidence.topCard != null) {
                    ScannerState.HighConfidence(confidence.topCard)
                } else {
                    ScannerState.LowConfidence(candidates)
                }
            } catch (e: RateLimitException) {
                imageProxy.close()
                _state.value = ScannerState.RateLimited
            } catch (e: Exception) {
                imageProxy.close()
                _state.value = ScannerState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun confirmCard(card: TcgCard) {
        viewModelScope.launch {
            if (isSecondary) {
                secondaryBinderDao.insertAtEnd(
                    SecondaryBinderEntry(
                        pokemonId = card.pokemonNames.firstOrNull() ?: "",
                        pokemonName = card.name,
                        cardId = card.id,
                        cardImageUrl = card.imageUrl
                    )
                )
                _state.value = ScannerState.Success(card, "Secondary Binder")
            } else {
                assignCardUseCase.assign(slotId, card)
                _state.value = ScannerState.Success(card, slotName)
            }
        }
    }

    fun reset() { _state.value = ScannerState.Idle }

    fun onCaptureError(message: String) {
        _state.value = ScannerState.Error(message)
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL (ScannerScreen will have compile errors — fix in next task)

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt
git commit -m "feat: upgrade ScannerViewModel to use Gemini + slot assignment"
```

---

## Task 10: Update ScannerScreen

**Files:**
- Modify: `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`

- [ ] **Step 1: Replace ScannerScreen.kt**

```kotlin
package com.skyler.pokedexbinder.ui.scanner

import android.Manifest
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.skyler.pokedexbinder.data.model.TcgCard

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onDone: () -> Unit,
    onSearchManually: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onBack: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val state by viewModel.state.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    LaunchedEffect(state) {
        if (state is ScannerState.Success) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is ScannerState.Idle -> {
                    if (cameraPermission.status.isGranted) {
                        CameraPreview(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onImageCaptureReady = { imageCapture = it }
                        )
                        Button(
                            onClick = {
                                imageCapture?.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            viewModel.processImage(image)
                                        }
                                        override fun onError(exc: ImageCaptureException) {
                                            viewModel.onCaptureError(exc.message ?: "Capture failed")
                                        }
                                    }
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(16.dp)
                        ) { Text("Capture") }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Camera permission required", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                is ScannerState.Scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ScannerState.NoApiKey -> {
                    ScannerMessage(
                        message = "No Gemini API key set. Add your free key in Settings to use the scanner.",
                        primaryLabel = "Go to Settings",
                        onPrimary = onNavigateToSettings,
                        secondaryLabel = "Search Manually",
                        onSecondary = onSearchManually
                    )
                }
                is ScannerState.RateLimited -> {
                    ScannerMessage(
                        message = "You've hit today's scan limit. Try again tomorrow.",
                        primaryLabel = "Search Manually",
                        onPrimary = onSearchManually
                    )
                }
                is ScannerState.HighConfidence -> {
                    CardConfirmation(
                        card = s.card,
                        onConfirm = { viewModel.confirmCard(s.card) },
                        onDismiss = { viewModel.reset() }
                    )
                }
                is ScannerState.LowConfidence -> {
                    CardSelectionList(
                        cards = s.cards,
                        onSelect = { viewModel.confirmCard(it) },
                        onBack = { viewModel.reset() }
                    )
                }
                is ScannerState.Error -> {
                    ScannerMessage(
                        message = s.message,
                        isError = true,
                        primaryLabel = "Retry",
                        onPrimary = { viewModel.reset() },
                        secondaryLabel = "Search Manually",
                        onSecondary = onSearchManually
                    )
                }
                is ScannerState.Success -> { /* handled by LaunchedEffect above */ }
            }
        }
    }
}

@Composable
private fun ScannerMessage(
    message: String,
    isError: Boolean = false,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier, onImageCaptureReady: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder().build()
                onImageCaptureReady(capture)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = modifier
    )
}

@Composable
private fun CardConfirmation(card: TcgCard, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Is this the right card?", style = MaterialTheme.typography.titleMedium)
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Text("${card.name} · ${card.setName} · #${card.number}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Wrong card") }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm") }
        }
    }
}

@Composable
private fun CardSelectionList(cards: List<TcgCard>, onSelect: (TcgCard) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (cards.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No cards found — try scanning again or search manually")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(cards) { card ->
                    ListItem(
                        headlineContent = { Text(card.name) },
                        supportingContent = { Text("${card.setName} · #${card.number}") },
                        leadingContent = {
                            AsyncImage(
                                model = card.imageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        },
                        modifier = Modifier.clickable { onSelect(card) }
                    )
                    HorizontalDivider()
                }
            }
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Back") }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL (AppNavigation will have errors until Task 11)

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt
git commit -m "feat: update ScannerScreen with Gemini error states and new UI flow"
```

---

## Task 11: Wire ScannerScreen into navigation + scan/search bottom sheets

**Files:**
- Modify: `app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt`

- [ ] **Step 1: Replace AppNavigation.kt**

```kotlin
package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.skyler.pokedexbinder.R
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderScreen
import com.skyler.pokedexbinder.ui.manualsearch.ManualSearchScreen
import com.skyler.pokedexbinder.ui.quickscan.QuickScanScreen
import com.skyler.pokedexbinder.ui.scanner.ScannerScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderViewModel
import com.skyler.pokedexbinder.ui.settings.SettingsScreen
import com.skyler.pokedexbinder.ui.settings.SettingsViewModel
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object MainBinder : Screen("main_binder")
    object SecondaryBinder : Screen("secondary_binder")
    object Settings : Screen("settings")
    object SlotDetail : Screen("slot_detail/{slotId}") {
        fun createRoute(slotId: String) = "slot_detail/$slotId"
    }
    object QuickScan : Screen("quick_scan?pokemonName={pokemonName}&slotId={slotId}&replacing={replacing}") {
        fun createRoute(pokemonName: String = "", slotId: String = "", replacing: Boolean = false): String {
            val enc = StandardCharsets.UTF_8.toString()
            fun encode(s: String) = URLEncoder.encode(s, enc).replace("+", "%20")
            return "quick_scan?pokemonName=${encode(pokemonName)}&slotId=${encode(slotId)}&replacing=$replacing"
        }
    }
    object Scanner : Screen("scanner?slotId={slotId}&pokemonName={pokemonName}&isSecondary={isSecondary}") {
        fun createRoute(slotId: String = "", pokemonName: String = "", isSecondary: Boolean = false): String {
            val enc = StandardCharsets.UTF_8.toString()
            fun encode(s: String) = URLEncoder.encode(s, enc).replace("+", "%20")
            return "scanner?slotId=${encode(slotId)}&pokemonName=${encode(pokemonName)}&isSecondary=$isSecondary"
        }
    }
    object AddToSecondary : Screen("add_to_secondary")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination

    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsState()

    var scanChoiceSlot by remember { mutableStateOf<PokemonSlot?>(null) }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentDest?.hierarchy?.any { it.route == Screen.MainBinder.route } == true,
                    onClick = { navigateTo(Screen.MainBinder.route) },
                    icon = { Icon(painterResource(R.drawable.ic_pokeball), contentDescription = "Binder") },
                    label = { Text("Pokédex") }
                )
                if (settings.showSecondaryBinder) {
                    NavigationBarItem(
                        selected = currentDest?.hierarchy?.any { it.route == Screen.SecondaryBinder.route } == true,
                        onClick = { navigateTo(Screen.SecondaryBinder.route) },
                        icon = { Icon(Icons.Default.Menu, contentDescription = "Secondary") },
                        label = { Text("Secondary") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.MainBinder.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.MainBinder.route) {
                MainBinderScreen(
                    onSlotClick = { slot ->
                        if (slot.isOccupied) {
                            navController.navigate(Screen.SlotDetail.createRoute(slot.id))
                        } else {
                            scanChoiceSlot = slot
                        }
                    },
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.SecondaryBinder.route) {
                SecondaryBinderScreen(
                    onScanCard = {
                        navController.navigate(Screen.Scanner.createRoute(isSecondary = true))
                    },
                    onSearchCard = { navController.navigate(Screen.AddToSecondary.route) }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.SlotDetail.route,
                arguments = listOf(navArgument("slotId") { type = NavType.StringType })
            ) { backStack ->
                val slotId = backStack.arguments?.getString("slotId") ?: ""
                SlotDetailScreen(
                    pokemonId = slotId,
                    onBack = { navController.popBackStack() },
                    onSearch = { pokemonName, sid, replacing ->
                        navController.navigate(Screen.QuickScan.createRoute(pokemonName, sid, replacing))
                    }
                )
            }
            composable(
                route = Screen.QuickScan.route,
                arguments = listOf(
                    navArgument("pokemonName") { type = NavType.StringType; defaultValue = "" },
                    navArgument("slotId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("replacing") { type = NavType.BoolType; defaultValue = false }
                )
            ) {
                QuickScanScreen(
                    onDone = { navController.popBackStack(Screen.MainBinder.route, false) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.Scanner.route,
                arguments = listOf(
                    navArgument("slotId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("pokemonName") { type = NavType.StringType; defaultValue = "" },
                    navArgument("isSecondary") { type = NavType.BoolType; defaultValue = false }
                )
            ) {
                ScannerScreen(
                    onDone = { navController.popBackStack(Screen.MainBinder.route, false) },
                    onSearchManually = {
                        navController.popBackStack()
                        // Re-open search based on context — pop to prior slot or secondary
                    },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.AddToSecondary.route) {
                val secondaryVm: SecondaryBinderViewModel = hiltViewModel()
                ManualSearchScreen(
                    onCardSelected = { card ->
                        secondaryVm.addCard(card)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    // Scan / Search choice bottom sheet — shown when an empty slot is tapped
    scanChoiceSlot?.let { slot ->
        ModalBottomSheet(onDismissRequest = { scanChoiceSlot = null }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Add Card for ${slot.name}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scanChoiceSlot = null
                        navController.navigate(
                            Screen.Scanner.createRoute(slotId = slot.id, pokemonName = slot.name)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Card") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scanChoiceSlot = null
                        navController.navigate(
                            Screen.QuickScan.createRoute(pokemonName = slot.name, slotId = slot.id)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Search Manually") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL (SecondaryBinderScreen will have errors until Task 12)

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt
git commit -m "feat: wire ScannerScreen into navigation with scan/search bottom sheet"
```

---

## Task 12: SecondaryBinderScreen — scan/search bottom sheet

**Files:**
- Modify: `app/src/main/java/com/skyler/pokedexbinder/ui/secondarybinder/SecondaryBinderScreen.kt`

- [ ] **Step 1: Update SecondaryBinderScreen signature and add bottom sheet**

Replace only the composable signature and FAB section. Find the `SecondaryBinderScreen` function signature and FAB:

Old signature:
```kotlin
fun SecondaryBinderScreen(
    onAddCard: () -> Unit = {},
    viewModel: SecondaryBinderViewModel = hiltViewModel()
)
```

New signature and FAB section — replace from the function signature through the `floatingActionButton` block:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryBinderScreen(
    onScanCard: () -> Unit = {},
    onSearchCard: () -> Unit = {},
    viewModel: SecondaryBinderViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        viewModel.reorder(from.index, to.index)
    }
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Card History") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add card")
            }
        }
    ) { padding ->
```

Also add the bottom sheet before the closing `}` of the composable (after the `Scaffold` block):

```kotlin
    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Add Card", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showAddSheet = false; onScanCard() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Card") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showAddSheet = false; onSearchCard() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Search Manually") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
```

Also add these imports at the top of the file if not already present:
```kotlin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: All tests pass

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/secondarybinder/SecondaryBinderScreen.kt
git commit -m "feat: add scan/search choice bottom sheet to Secondary Binder"
```

---

## Task 13: Cleanup — delete dead code and remove ML Kit

**Files:**
- Delete: `app/src/main/java/com/skyler/pokedexbinder/domain/OcrCardParser.kt`
- Delete: `app/src/main/java/com/skyler/pokedexbinder/domain/CardNameTranslator.kt`
- Delete: `app/src/test/java/com/skyler/pokedexbinder/domain/OcrCardParserTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Delete dead domain files**

```bash
rm app/src/main/java/com/skyler/pokedexbinder/domain/OcrCardParser.kt
rm app/src/main/java/com/skyler/pokedexbinder/domain/CardNameTranslator.kt
rm app/src/test/java/com/skyler/pokedexbinder/domain/OcrCardParserTest.kt
```

- [ ] **Step 2: Remove ML Kit versions from libs.versions.toml**

In `gradle/libs.versions.toml`, remove these two lines from `[versions]`:
```toml
mlkitTextRecognition = "16.0.0"
mlkitTranslate = "17.0.3"
```

Remove these five lines from `[libraries]`:
```toml
mlkit-text-recognition = { group = "com.google.mlkit", name = "text-recognition", version.ref = "mlkitTextRecognition" }
mlkit-text-recognition-korean = { group = "com.google.mlkit", name = "text-recognition-korean", version.ref = "mlkitTextRecognition" }
mlkit-text-recognition-japanese = { group = "com.google.mlkit", name = "text-recognition-japanese", version.ref = "mlkitTextRecognition" }
mlkit-text-recognition-chinese = { group = "com.google.mlkit", name = "text-recognition-chinese", version.ref = "mlkitTextRecognition" }
mlkit-translate = { group = "com.google.mlkit", name = "translate", version.ref = "mlkitTranslate" }
```

- [ ] **Step 3: Remove ML Kit dependencies from build.gradle.kts**

In `app/build.gradle.kts`, remove these six lines:
```kotlin
implementation(libs.mlkit.text.recognition)
implementation(libs.mlkit.text.recognition.korean)
implementation(libs.mlkit.text.recognition.japanese)
implementation(libs.mlkit.text.recognition.chinese)
implementation(libs.mlkit.translate)
```

Also remove:
```kotlin
implementation(libs.coroutines.play.services)
```

- [ ] **Step 4: Final compile and test**

Run: `./gradlew :app:testDebugUnitTest`

Expected: All tests pass

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL — APK produced

- [ ] **Step 5: Commit**
```bash
git add -A
git commit -m "chore: remove ML Kit OCR and CardNameTranslator, replaced by Gemini"
```
