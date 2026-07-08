# Pokédex Binder Android App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Android app for tracking a Pokémon TCG card binder with camera-based card scanning, pokemontcg.io card lookup, and a 3×3 grid binder UI with a secondary binder for replaced cards.

**Architecture:** MVVM + Repository pattern. Room for local persistence, Retrofit for pokemontcg.io API calls, ML Kit Text Recognition for on-device OCR, CameraX for camera access, and Jetpack Compose for UI.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Retrofit + Moshi, ML Kit Text Recognition, CameraX, Coil, Hilt, Navigation Compose, Coroutines/Flow

---

## File Structure

```
PokedexBinder/
├── scripts/
│   └── generate_pokemon_slots.py          # One-time script to generate bundled JSON
│
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── res/raw/
│   │   │   │   └── pokemon_slots.json     # Pre-generated, bundled with app
│   │   │   └── java/com/skyler/pokedexbinder/
│   │   │       ├── PokedexBinderApp.kt    # Application class (@HiltAndroidApp)
│   │   │       ├── MainActivity.kt        # Single activity, nav host
│   │   │       │
│   │   │       ├── data/
│   │   │       │   ├── model/
│   │   │       │   │   ├── PokemonSlot.kt          # Domain model for a binder slot
│   │   │       │   │   └── TcgCard.kt              # Domain model for a card
│   │   │       │   ├── local/
│   │   │       │   │   ├── MainBinderEntry.kt      # Room entity
│   │   │       │   │   ├── SecondaryBinderEntry.kt # Room entity
│   │   │       │   │   ├── MainBinderDao.kt        # DAO
│   │   │       │   │   ├── SecondaryBinderDao.kt   # DAO
│   │   │       │   │   └── PokedexDatabase.kt      # RoomDatabase
│   │   │       │   └── remote/
│   │   │       │       ├── PokemonTcgApi.kt        # Retrofit interface
│   │   │       │       └── TcgCardDto.kt           # API response DTO
│   │   │       │
│   │   │       ├── di/
│   │   │       │   ├── DatabaseModule.kt           # Hilt: Room + DAO bindings
│   │   │       │   └── NetworkModule.kt            # Hilt: Retrofit + API bindings
│   │   │       │
│   │   │       ├── repository/
│   │   │       │   ├── BinderRepository.kt         # Reads slots JSON, wraps Room DAOs
│   │   │       │   └── CardSearchRepository.kt     # Wraps pokemontcg.io API calls
│   │   │       │
│   │   │       ├── domain/
│   │   │       │   ├── AssignCardUseCase.kt        # Core assign/replace/secondary logic
│   │   │       │   ├── SmartThresholdUseCase.kt    # Confidence scoring for card matches
│   │   │       │   └── OcrCardParser.kt            # Parses ML Kit output → name + number
│   │   │       │
│   │   │       └── ui/
│   │   │           ├── theme/
│   │   │           │   └── Theme.kt
│   │   │           ├── navigation/
│   │   │           │   └── AppNavigation.kt        # NavHost + bottom nav + routes
│   │   │           ├── mainbinder/
│   │   │           │   ├── MainBinderScreen.kt
│   │   │           │   └── MainBinderViewModel.kt
│   │   │           ├── slotdetail/
│   │   │           │   ├── SlotDetailScreen.kt
│   │   │           │   └── SlotDetailViewModel.kt
│   │   │           ├── scanner/
│   │   │           │   ├── ScannerScreen.kt
│   │   │           │   └── ScannerViewModel.kt
│   │   │           ├── manualsearch/
│   │   │           │   ├── ManualSearchScreen.kt
│   │   │           │   └── ManualSearchViewModel.kt
│   │   │           └── secondarybinder/
│   │   │               ├── SecondaryBinderScreen.kt
│   │   │               └── SecondaryBinderViewModel.kt
│   │   │
│   │   ├── test/java/com/skyler/pokedexbinder/
│   │   │   ├── domain/
│   │   │   │   ├── AssignCardUseCaseTest.kt
│   │   │   │   ├── SmartThresholdUseCaseTest.kt
│   │   │   │   └── OcrCardParserTest.kt
│   │   │   ├── repository/
│   │   │   │   ├── BinderRepositoryTest.kt
│   │   │   │   └── CardSearchRepositoryTest.kt
│   │   │   └── ui/
│   │   │       ├── MainBinderViewModelTest.kt
│   │   │       └── SlotDetailViewModelTest.kt
│   │   │
│   │   └── androidTest/java/com/skyler/pokedexbinder/
│   │       └── data/
│   │           └── PokedexDatabaseTest.kt
│   │
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── local.properties
```

---

## Task 1: Project Scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (project root)
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `local.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PokedexBinder"
include(":app")
```

- [ ] **Step 2: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.9.0"
kotlin = "2.1.20"
coreKtx = "1.13.1"
lifecycleRuntimeKtx = "2.8.3"
activityCompose = "1.9.0"
composeBom = "2024.06.00"
navigationCompose = "2.7.7"
room = "2.6.1"
retrofit = "2.11.0"
okhttp = "4.12.0"
moshi = "1.15.1"
hilt = "2.51.1"
hiltNavigationCompose = "1.2.0"
ksp = "2.1.20-1.0.32"
mlkitTextRecognition = "16.0.0"
camerax = "1.3.4"
coil = "2.6.0"
coroutines = "1.8.1"
junit = "4.13.2"
junitExt = "1.2.1"
espresso = "3.6.1"
mockk = "1.13.11"
turbine = "1.1.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-moshi = { group = "com.squareup.retrofit2", name = "converter-moshi", version.ref = "retrofit" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
moshi-kotlin = { group = "com.squareup.moshi", name = "moshi-kotlin", version.ref = "moshi" }
moshi-codegen = { group = "com.squareup.moshi", name = "moshi-kotlin-codegen", version.ref = "moshi" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
mlkit-text-recognition = { group = "com.google.mlkit", name = "text-recognition", version.ref = "mlkitTextRecognition" }
camerax-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-play-services = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-play-services", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
junit-ext = { group = "androidx.test.ext", name = "junit", version.ref = "junitExt" }
espresso = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: Create `build.gradle.kts` (project root)**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 4: Create `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.skyler.pokedexbinder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.skyler.pokedexbinder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Retrofit + Moshi
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ML Kit
    implementation(libs.mlkit.text.recognition)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Coil
    implementation(libs.coil.compose)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
```

- [ ] **Step 5: Create `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 6: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />

    <application
        android:name=".PokedexBinderApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.PokedexBinder">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.PokedexBinder">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 7: Create `app/src/main/res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">Pokédex Binder</string>
</resources>
```

- [ ] **Step 8: Create `app/src/main/res/values/themes.xml`**

```xml
<resources>
    <style name="Theme.PokedexBinder" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 9: Create `local.properties`** (replace path with your Android SDK location)

```properties
sdk.dir=C\:\\Users\\SkylerMayday\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 10: Commit**

```bash
git init
git add .
git commit -m "chore: initial Android project scaffold"
```

---

## Task 2: Domain Models

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/model/PokemonSlot.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt`

- [ ] **Step 1: Write failing test for PokemonSlot**

Create `app/src/test/java/com/skyler/pokedexbinder/data/model/PokemonSlotTest.kt`:

```kotlin
package com.skyler.pokedexbinder.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonSlotTest {

    @Test
    fun `isOccupied returns false when no card assigned`() {
        val slot = PokemonSlot(
            id = "bulbasaur", name = "Bulbasaur", dexNumber = 1,
            dexOrder = 1, slotType = SlotType.BASE,
            assignedCardId = null, assignedCardImageUrl = null
        )
        assertFalse(slot.isOccupied)
    }

    @Test
    fun `isOccupied returns true when card assigned`() {
        val slot = PokemonSlot(
            id = "bulbasaur", name = "Bulbasaur", dexNumber = 1,
            dexOrder = 1, slotType = SlotType.BASE,
            assignedCardId = "xy1-1", assignedCardImageUrl = "https://example.com/card.png"
        )
        assertTrue(slot.isOccupied)
    }

    @Test
    fun `displayName returns name for base pokemon`() {
        val slot = PokemonSlot(
            id = "charizard", name = "Charizard", dexNumber = 6,
            dexOrder = 6, slotType = SlotType.BASE,
            assignedCardId = null, assignedCardImageUrl = null
        )
        assertEquals("Charizard", slot.name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PokemonSlotTest" -q`
Expected: FAIL — `PokemonSlot` class not found

- [ ] **Step 3: Create `PokemonSlot.kt` and `TcgCard.kt`**

`app/src/main/java/com/skyler/pokedexbinder/data/model/PokemonSlot.kt`:
```kotlin
package com.skyler.pokedexbinder.data.model

enum class SlotType { BASE, REGIONAL, MEGA, GMAX }

data class PokemonSlot(
    val id: String,
    val name: String,
    val dexNumber: Int,
    val dexOrder: Int,
    val slotType: SlotType,
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null
) {
    val isOccupied: Boolean get() = assignedCardId != null
}
```

`app/src/main/java/com/skyler/pokedexbinder/data/model/TcgCard.kt`:
```kotlin
package com.skyler.pokedexbinder.data.model

data class TcgCard(
    val id: String,
    val name: String,
    val number: String,
    val setName: String,
    val imageUrl: String,
    val pokemonNames: List<String>
) {
    val primaryPokemonName: String get() = pokemonNames.firstOrNull() ?: name
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PokemonSlotTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/data/model/
git add app/src/test/java/com/skyler/pokedexbinder/data/model/
git commit -m "feat: add PokemonSlot and TcgCard domain models"
```

---

## Task 3: Generate pokemon_slots.json

**Files:**
- Create: `scripts/generate_pokemon_slots.py`
- Create: `app/src/main/res/raw/pokemon_slots.json` (generated by running the script)

The script pulls from PokéAPI for base Pokémon, checks pokemontcg.io for regional variant cards, hardcodes all Mega Evolutions, and includes only G-Max forms with visually distinct designs.

- [ ] **Step 1: Create `scripts/generate_pokemon_slots.py`**

```python
#!/usr/bin/env python3
"""
Generates pokemon_slots.json bundled with the app.
Run once: python scripts/generate_pokemon_slots.py
Output:   app/src/main/res/raw/pokemon_slots.json
Requires: pip install requests
"""

import json
import time
import requests

POKEAPI = "https://pokeapi.co/api/v2"
TCG_API = "https://api.pokemontcg.io/v2"
OUT_PATH = "app/src/main/res/raw/pokemon_slots.json"

# G-Max forms with visually distinct designs (not just oversized base forms)
GMAX_DISTINCT = {
    "charizard", "butterfree", "pikachu", "meowth", "machamp", "gengar",
    "kingler", "lapras", "eevee", "snorlax", "garbodor", "melmetal",
    "corviknight", "orbeetle", "drednaw", "coalossal", "flapple", "appletun",
    "sandaconda", "toxtricity", "centiskorch", "hatterene", "grimmsnarl",
    "alcremie", "copperajah", "duraludon", "urshifu"
}

# All Mega Evolutions (Pokémon name → list of mega form names as they appear in PokéAPI)
# PokéAPI variety names for mega forms use "-mega", "-mega-x", "-mega-y" suffixes
MEGA_VARIETIES = [
    "venusaur-mega", "charizard-mega-x", "charizard-mega-y", "blastoise-mega",
    "beedrill-mega", "pidgeot-mega", "slowbro-mega", "gengar-mega",
    "kangaskhan-mega", "pinsir-mega", "gyarados-mega", "aerodactyl-mega",
    "mewtwo-mega-x", "mewtwo-mega-y", "ampharos-mega", "scizor-mega",
    "heracross-mega", "houndoom-mega", "tyranitar-mega", "blaziken-mega",
    "gardevoir-mega", "mawile-mega", "aggron-mega", "medicham-mega",
    "manectric-mega", "banette-mega", "absol-mega", "garchomp-mega",
    "lucario-mega", "abomasnow-mega", "alakazam-mega", "steelix-mega",
    "sceptile-mega", "swampert-mega", "sableye-mega", "sharpedo-mega",
    "camerupt-mega", "altaria-mega", "glalie-mega", "salamence-mega",
    "metagross-mega", "latias-mega", "latios-mega", "rayquaza-mega",
    "lopunny-mega", "gallade-mega", "audino-mega", "diancie-mega"
]


def tcg_has_card(query: str) -> bool:
    """Returns True if pokemontcg.io has at least one card matching the query."""
    try:
        r = requests.get(f"{TCG_API}/cards", params={"q": query, "pageSize": 1}, timeout=10)
        data = r.json()
        return data.get("totalCount", 0) > 0
    except Exception:
        return False


def fetch_all_base_pokemon() -> list[dict]:
    """Fetches all 1025 base Pokémon from PokéAPI."""
    slots = []
    r = requests.get(f"{POKEAPI}/pokemon?limit=1025&offset=0").json()
    for i, p in enumerate(r["results"], start=1):
        slots.append({
            "id": p["name"],
            "name": p["name"].replace("-", " ").title(),
            "dex_number": i,
            "dex_order": i,
            "slot_type": "base"
        })
        if i % 100 == 0:
            print(f"  Fetched base {i}/1025...")
    return slots


def fetch_regional_variants(base_slots: list[dict]) -> list[dict]:
    """
    Checks each base Pokémon for regional/form variants.
    Only includes a variant if pokemontcg.io has at least one card for it.
    """
    variants = []
    regional_keywords = ["alola", "galar", "hisui", "paldea"]
    order = 1026

    for slot in base_slots:
        try:
            r = requests.get(f"{POKEAPI}/pokemon-species/{slot['id']}").json()
        except Exception:
            continue

        varieties = r.get("varieties", [])
        for v in varieties:
            vname = v["pokemon"]["name"]
            if vname == slot["id"]:
                continue
            if not any(kw in vname for kw in regional_keywords):
                continue
            # Check pokemontcg.io
            display = vname.replace("-", " ").title()
            query = f'name:"{slot["name"]}"'
            if tcg_has_card(query):
                variants.append({
                    "id": vname,
                    "name": display,
                    "dex_number": slot["dex_number"],
                    "dex_order": order,
                    "slot_type": "regional"
                })
                print(f"  Regional variant with card: {vname}")
                order += 1
            time.sleep(0.1)

    return variants


def build_mega_slots(start_order: int) -> list[dict]:
    """Returns slots for all Mega Evolutions."""
    slots = []
    for i, mega_id in enumerate(MEGA_VARIETIES):
        name = mega_id.replace("-", " ").title()
        base_name = mega_id.split("-mega")[0]
        # Get dex number from PokéAPI
        try:
            r = requests.get(f"{POKEAPI}/pokemon/{mega_id}").json()
            dex_number = r["id"]
        except Exception:
            dex_number = 0
        slots.append({
            "id": mega_id,
            "name": name,
            "dex_number": dex_number,
            "dex_order": start_order + i,
            "slot_type": "mega"
        })
    return slots


def build_gmax_slots(start_order: int) -> list[dict]:
    """Returns slots for G-Max forms with visually distinct designs."""
    slots = []
    order = start_order
    for base_name in sorted(GMAX_DISTINCT):
        gmax_id = f"{base_name}-gmax"
        try:
            r = requests.get(f"{POKEAPI}/pokemon/{gmax_id}").json()
            dex_number = r["id"]
        except Exception:
            dex_number = 0
        slots.append({
            "id": gmax_id,
            "name": f"{base_name.title()} (Gigantamax)",
            "dex_number": dex_number,
            "dex_order": order,
            "slot_type": "gmax"
        })
        order += 1
    return slots


def main():
    import os
    os.makedirs("app/src/main/res/raw", exist_ok=True)

    print("Fetching base Pokémon...")
    base = fetch_all_base_pokemon()

    print("Checking regional variants against pokemontcg.io...")
    regional = fetch_regional_variants(base)

    print("Building Mega Evolution slots...")
    mega_start = (regional[-1]["dex_order"] + 1) if regional else 1026
    megas = build_mega_slots(mega_start)

    print("Building G-Max slots...")
    gmax_start = (megas[-1]["dex_order"] + 1) if megas else mega_start
    gmax = build_gmax_slots(gmax_start)

    all_slots = base + regional + megas + gmax
    print(f"\nTotal slots: {len(all_slots)}")
    print(f"  Base: {len(base)}, Regional: {len(regional)}, Mega: {len(megas)}, GMax: {len(gmax)}")

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(all_slots, f, indent=2, ensure_ascii=False)

    print(f"Written to {OUT_PATH}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run the generation script**

```bash
pip install requests
python scripts/generate_pokemon_slots.py
```

Expected output ends with: `Written to app/src/main/res/raw/pokemon_slots.json`
Verify the file exists and contains 1200+ entries.

- [ ] **Step 3: Commit**

```bash
git add scripts/generate_pokemon_slots.py
git add app/src/main/res/raw/pokemon_slots.json
git commit -m "feat: add pokemon slots generation script and bundled JSON"
```

---

## Task 4: Room Database Layer

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/local/MainBinderEntry.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/local/SecondaryBinderEntry.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/local/MainBinderDao.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/local/SecondaryBinderDao.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/local/PokedexDatabase.kt`
- Test: `app/src/androidTest/java/com/skyler/pokedexbinder/data/PokedexDatabaseTest.kt`

- [ ] **Step 1: Create Room entities**

`MainBinderEntry.kt`:
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "main_binder")
data class MainBinderEntry(
    @PrimaryKey val pokemonId: String,
    val pokemonName: String,
    val dexOrder: Int,
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null
)
```

`SecondaryBinderEntry.kt`:
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secondary_binder")
data class SecondaryBinderEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pokemonId: String,
    val pokemonName: String,
    val cardId: String,
    val cardImageUrl: String
)
```

- [ ] **Step 2: Create DAOs**

`MainBinderDao.kt`:
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MainBinderDao {
    @Query("SELECT * FROM main_binder ORDER BY dexOrder ASC")
    fun observeAll(): Flow<List<MainBinderEntry>>

    @Query("SELECT * FROM main_binder WHERE pokemonId = :pokemonId")
    suspend fun getByPokemonId(pokemonId: String): MainBinderEntry?

    @Upsert
    suspend fun upsert(entry: MainBinderEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MainBinderEntry>)
}
```

`SecondaryBinderDao.kt`:
```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SecondaryBinderDao {
    @Query("SELECT * FROM secondary_binder ORDER BY id DESC")
    fun observeAll(): Flow<List<SecondaryBinderEntry>>

    @Insert
    suspend fun insert(entry: SecondaryBinderEntry)
}
```

- [ ] **Step 3: Create `PokedexDatabase.kt`**

```kotlin
package com.skyler.pokedexbinder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MainBinderEntry::class, SecondaryBinderEntry::class],
    version = 1,
    exportSchema = false
)
abstract class PokedexDatabase : RoomDatabase() {
    abstract fun mainBinderDao(): MainBinderDao
    abstract fun secondaryBinderDao(): SecondaryBinderDao
}
```

- [ ] **Step 4: Write instrumented database test**

`app/src/androidTest/java/com/skyler/pokedexbinder/data/PokedexDatabaseTest.kt`:
```kotlin
package com.skyler.pokedexbinder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.skyler.pokedexbinder.data.local.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PokedexDatabaseTest {

    private lateinit var db: PokedexDatabase
    private lateinit var mainDao: MainBinderDao
    private lateinit var secondaryDao: SecondaryBinderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PokedexDatabase::class.java
        ).allowMainThreadQueries().build()
        mainDao = db.mainBinderDao()
        secondaryDao = db.secondaryBinderDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun insertAndObserveMainBinder() = runTest {
        val entry = MainBinderEntry("bulbasaur", "Bulbasaur", 1)
        mainDao.upsert(entry)
        mainDao.observeAll().test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("bulbasaur", items[0].pokemonId)
            assertNull(items[0].assignedCardId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertUpdatesExistingEntry() = runTest {
        val entry = MainBinderEntry("bulbasaur", "Bulbasaur", 1)
        mainDao.upsert(entry)
        mainDao.upsert(entry.copy(assignedCardId = "xy1-1", assignedCardImageUrl = "https://img.url"))
        val result = mainDao.getByPokemonId("bulbasaur")
        assertEquals("xy1-1", result?.assignedCardId)
    }

    @Test
    fun secondaryBinderOrdersByIdDesc() = runTest {
        secondaryDao.insert(SecondaryBinderEntry(pokemonId = "bulbasaur", pokemonName = "Bulbasaur", cardId = "card-1", cardImageUrl = "url1"))
        secondaryDao.insert(SecondaryBinderEntry(pokemonId = "bulbasaur", pokemonName = "Bulbasaur", cardId = "card-2", cardImageUrl = "url2"))
        secondaryDao.observeAll().test {
            val items = awaitItem()
            assertEquals(2, items.size)
            assertEquals("card-2", items[0].cardId) // newest first
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 5: Run instrumented tests**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*.PokedexDatabaseTest"`
Expected: all 3 tests PASS (requires connected device or emulator)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/data/local/
git add app/src/androidTest/java/com/skyler/pokedexbinder/data/PokedexDatabaseTest.kt
git commit -m "feat: add Room database entities, DAOs, and database"
```

---

## Task 5: pokemontcg.io Retrofit API

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/remote/TcgCardDto.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/data/remote/PokemonTcgApi.kt`

- [ ] **Step 1: Create `TcgCardDto.kt`**

```kotlin
package com.skyler.pokedexbinder.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TcgCardDto(
    val id: String,
    val name: String,
    val number: String,
    val set: TcgSetDto,
    val images: TcgImagesDto
)

@JsonClass(generateAdapter = true)
data class TcgSetDto(val name: String)

@JsonClass(generateAdapter = true)
data class TcgImagesDto(
    val small: String,
    val large: String
)

@JsonClass(generateAdapter = true)
data class TcgCardsResponse(
    val data: List<TcgCardDto>,
    val totalCount: Int
)
```

- [ ] **Step 2: Create `PokemonTcgApi.kt`**

```kotlin
package com.skyler.pokedexbinder.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface PokemonTcgApi {
    @GET("cards")
    suspend fun searchCards(
        @Query("q") query: String,
        @Query("pageSize") pageSize: Int = 20,
        @Query("orderBy") orderBy: String = "name"
    ): TcgCardsResponse
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/data/remote/
git commit -m "feat: add pokemontcg.io Retrofit API interface and DTOs"
```

---

## Task 6: Hilt Dependency Injection

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/PokedexBinderApp.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/di/NetworkModule.kt`

- [ ] **Step 1: Create `PokedexBinderApp.kt`**

```kotlin
package com.skyler.pokedexbinder

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PokedexBinderApp : Application()
```

- [ ] **Step 2: Create `DatabaseModule.kt`**

```kotlin
package com.skyler.pokedexbinder.di

import android.content.Context
import androidx.room.Room
import com.skyler.pokedexbinder.data.local.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase =
        Room.databaseBuilder(context, PokedexDatabase::class.java, "pokedex_binder.db").build()

    @Provides
    fun provideMainBinderDao(db: PokedexDatabase): MainBinderDao = db.mainBinderDao()

    @Provides
    fun provideSecondaryBinderDao(db: PokedexDatabase): SecondaryBinderDao = db.secondaryBinderDao()
}
```

- [ ] **Step 3: Create `NetworkModule.kt`**

```kotlin
package com.skyler.pokedexbinder.di

import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.pokemontcg.io/v2/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun providePokemonTcgApi(retrofit: Retrofit): PokemonTcgApi =
        retrofit.create(PokemonTcgApi::class.java)
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/PokedexBinderApp.kt
git add app/src/main/java/com/skyler/pokedexbinder/di/
git commit -m "feat: add Hilt DI modules for database and network"
```

---

## Task 7: BinderRepository

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/repository/BinderRepository.kt`
- Test: `app/src/test/java/com/skyler/pokedexbinder/repository/BinderRepositoryTest.kt`

The repository reads `pokemon_slots.json` from raw resources on first run to seed the `main_binder` table, then serves all slot data by merging the static list with the live Room state.

- [ ] **Step 1: Write failing tests**

`BinderRepositoryTest.kt`:
```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.Assert.*
import org.junit.Test

class BinderRepositoryTest {

    private val mainDao = mockk<MainBinderDao>(relaxed = true)

    @Test
    fun `observeSlots maps DB entries to PokemonSlot with card info`() = runTest {
        val entry = MainBinderEntry(
            pokemonId = "bulbasaur",
            pokemonName = "Bulbasaur",
            dexOrder = 1,
            assignedCardId = "xy1-1",
            assignedCardImageUrl = "https://img.pokemontcg.io/xy1/1.png"
        )
        coEvery { mainDao.observeAll() } returns flowOf(listOf(entry))

        val repo = BinderRepository(mainDao)
        repo.observeSlots().test {
            val slots = awaitItem()
            assertEquals(1, slots.size)
            assertEquals("xy1-1", slots[0].assignedCardId)
            assertTrue(slots[0].isOccupied)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getSlotByPokemonId returns null when not found`() = runTest {
        coEvery { mainDao.getByPokemonId("missingno") } returns null
        val repo = BinderRepository(mainDao)
        assertNull(repo.getSlotByPokemonId("missingno"))
    }

    @Test
    fun `assignCard upserts entry with card info`() = runTest {
        val existing = MainBinderEntry("charizard", "Charizard", 6)
        coEvery { mainDao.getByPokemonId("charizard") } returns existing
        val repo = BinderRepository(mainDao)

        repo.assignCard("charizard", "base4-4", "https://img.pokemontcg.io/base4/4.png")

        coVerify {
            mainDao.upsert(
                MainBinderEntry(
                    pokemonId = "charizard",
                    pokemonName = "Charizard",
                    dexOrder = 6,
                    assignedCardId = "base4-4",
                    assignedCardImageUrl = "https://img.pokemontcg.io/base4/4.png"
                )
            )
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BinderRepositoryTest" -q`
Expected: FAIL — `BinderRepository` not found

- [ ] **Step 3: Create `BinderRepository.kt`**

```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderRepository @Inject constructor(
    private val mainBinderDao: MainBinderDao
) {
    fun observeSlots(): Flow<List<PokemonSlot>> =
        mainBinderDao.observeAll().map { entries -> entries.map { it.toDomain() } }

    suspend fun getSlotByPokemonId(pokemonId: String): PokemonSlot? =
        mainBinderDao.getByPokemonId(pokemonId)?.toDomain()

    suspend fun assignCard(pokemonId: String, cardId: String, cardImageUrl: String) {
        val existing = mainBinderDao.getByPokemonId(pokemonId) ?: return
        mainBinderDao.upsert(
            existing.copy(assignedCardId = cardId, assignedCardImageUrl = cardImageUrl)
        )
    }

    suspend fun clearCard(pokemonId: String) {
        val existing = mainBinderDao.getByPokemonId(pokemonId) ?: return
        mainBinderDao.upsert(existing.copy(assignedCardId = null, assignedCardImageUrl = null))
    }

    suspend fun seedFromJson(slots: List<MainBinderEntry>) {
        mainBinderDao.insertAll(slots)
    }

    private fun MainBinderEntry.toDomain() = PokemonSlot(
        id = pokemonId,
        name = pokemonName,
        dexNumber = 0,
        dexOrder = dexOrder,
        slotType = SlotType.BASE,
        assignedCardId = assignedCardId,
        assignedCardImageUrl = assignedCardImageUrl
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BinderRepositoryTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/repository/BinderRepository.kt
git add app/src/test/java/com/skyler/pokedexbinder/repository/BinderRepositoryTest.kt
git commit -m "feat: add BinderRepository with slot observation and card assignment"
```

---

## Task 8: CardSearchRepository

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt`
- Test: `app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.remote.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CardSearchRepositoryTest {

    private val api = mockk<PokemonTcgApi>()

    @Test
    fun `searchByNameAndNumber builds correct query and maps results`() = runTest {
        val dto = TcgCardDto(
            id = "xy1-1", name = "Venusaur-EX", number = "1",
            set = TcgSetDto("XY"),
            images = TcgImagesDto("https://small.url", "https://large.url")
        )
        coEvery { api.searchCards("name:\"Venusaur\" number:\"1\"") } returns
            TcgCardsResponse(data = listOf(dto), totalCount = 1)

        val repo = CardSearchRepository(api)
        val results = repo.searchByNameAndNumber("Venusaur", "1")

        assertEquals(1, results.size)
        assertEquals("xy1-1", results[0].id)
        assertEquals("https://large.url", results[0].imageUrl)
    }

    @Test
    fun `searchByName builds name-only query`() = runTest {
        coEvery { api.searchCards("name:\"Pikachu\"") } returns
            TcgCardsResponse(data = emptyList(), totalCount = 0)

        val repo = CardSearchRepository(api)
        val results = repo.searchByName("Pikachu")
        assertTrue(results.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CardSearchRepositoryTest" -q`
Expected: FAIL

- [ ] **Step 3: Create `CardSearchRepository.kt`**

```kotlin
package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardSearchRepository @Inject constructor(
    private val api: PokemonTcgApi
) {
    suspend fun searchByNameAndNumber(name: String, number: String): List<TcgCard> =
        api.searchCards(query = "name:\"$name\" number:\"$number\"").data.map { it.toDomain() }

    suspend fun searchByName(name: String): List<TcgCard> =
        api.searchCards(query = "name:\"$name\"").data.map { it.toDomain() }

    private fun TcgCardDto.toDomain() = TcgCard(
        id = id,
        name = name,
        number = number,
        setName = set.name,
        imageUrl = images.large,
        // Tag Team cards (e.g. "Pikachu & Zekrom GX") split on " & " to get both names.
        // Single-Pokémon cards produce a one-element list.
        pokemonNames = name.split(" & ").map { it.substringBefore(" ").trim() }
    )
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CardSearchRepositoryTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/repository/CardSearchRepository.kt
git add app/src/test/java/com/skyler/pokedexbinder/repository/CardSearchRepositoryTest.kt
git commit -m "feat: add CardSearchRepository wrapping pokemontcg.io API"
```

---

## Task 9: OcrCardParser

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/domain/OcrCardParser.kt`
- Test: `app/src/test/java/com/skyler/pokedexbinder/domain/OcrCardParserTest.kt`

Parses raw ML Kit `Text` output into a card name and optional set number. The card name is the longest all-caps or title-case word cluster near the top of the text; the set number matches the pattern `\d{1,3}/\d{1,3}`.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.skyler.pokedexbinder.domain

import org.junit.Assert.*
import org.junit.Test

class OcrCardParserTest {

    private val parser = OcrCardParser()

    @Test
    fun `parses card name and set number from standard card text`() {
        val rawText = "Charizard\nHP 120\nFire\n4/102\nBase Set"
        val result = parser.parse(rawText)
        assertEquals("Charizard", result.cardName)
        assertEquals("4", result.cardNumber)
    }

    @Test
    fun `handles three digit set numbers`() {
        val rawText = "Pikachu V\nHP 90\n025/185\nVivid Voltage"
        val result = parser.parse(rawText)
        assertEquals("Pikachu V", result.cardName)
        assertEquals("025", result.cardNumber)
    }

    @Test
    fun `returns null number when no set number found`() {
        val rawText = "Bulbasaur\nHP 40\nSeed Pokémon"
        val result = parser.parse(rawText)
        assertEquals("Bulbasaur", result.cardName)
        assertNull(result.cardNumber)
    }

    @Test
    fun `handles tag team card names`() {
        val rawText = "Pikachu & Zekrom GX\nHP 240\nTag Team\n33/181"
        val result = parser.parse(rawText)
        assertEquals("Pikachu & Zekrom GX", result.cardName)
        assertEquals("33", result.cardNumber)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OcrCardParserTest" -q`
Expected: FAIL

- [ ] **Step 3: Create `OcrCardParser.kt`**

```kotlin
package com.skyler.pokedexbinder.domain

import javax.inject.Inject

data class ParsedCardInfo(
    val cardName: String,
    val cardNumber: String?
)

class OcrCardParser @Inject constructor() {

    // Matches formats: 4/102, 025/185, 033/181
    private val setNumberRegex = Regex("""(\d{1,3})/\d{1,3}""")

    // HP line signals end of name area
    private val hpRegex = Regex("""HP\s+\d+""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): ParsedCardInfo {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val cardNumber = setNumberRegex.find(rawText)?.groupValues?.get(1)

        // Card name is text before the HP line, joined. Exclude lines that are just numbers.
        val nameLines = mutableListOf<String>()
        for (line in lines) {
            if (hpRegex.containsMatchIn(line)) break
            if (line.matches(Regex("""^\d+$"""))) continue
            nameLines.add(line)
        }

        val cardName = nameLines
            .joinToString(" ")
            .trim()
            .ifEmpty { lines.firstOrNull() ?: "" }

        return ParsedCardInfo(cardName = cardName, cardNumber = cardNumber)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OcrCardParserTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/domain/OcrCardParser.kt
git add app/src/test/java/com/skyler/pokedexbinder/domain/OcrCardParserTest.kt
git commit -m "feat: add OcrCardParser for ML Kit text output"
```

---

## Task 10: SmartThresholdUseCase

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCase.kt`
- Test: `app/src/test/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCaseTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.model.TcgCard
import org.junit.Assert.*
import org.junit.Test

class SmartThresholdUseCaseTest {

    private val useCase = SmartThresholdUseCase()

    private fun card(id: String, name: String, number: String) = TcgCard(
        id = id, name = name, number = number,
        setName = "Test Set", imageUrl = "https://img.url",
        pokemonNames = listOf(name)
    )

    @Test
    fun `single result is high confidence`() {
        val result = useCase.evaluate(
            cards = listOf(card("xy1-1", "Charizard", "1")),
            parsedName = "Charizard",
            parsedNumber = "1"
        )
        assertTrue(result.isHighConfidence)
        assertEquals("xy1-1", result.topCard?.id)
    }

    @Test
    fun `empty results returns low confidence with null topCard`() {
        val result = useCase.evaluate(emptyList(), "Pikachu", null)
        assertFalse(result.isHighConfidence)
        assertNull(result.topCard)
    }

    @Test
    fun `multiple results with exact name and number match is high confidence`() {
        val result = useCase.evaluate(
            cards = listOf(
                card("xy1-1", "Charizard", "1"),
                card("base1-4", "Charizard", "4")
            ),
            parsedName = "Charizard",
            parsedNumber = "1"
        )
        assertTrue(result.isHighConfidence)
        assertEquals("xy1-1", result.topCard?.id)
    }

    @Test
    fun `multiple results with name match but no number is low confidence`() {
        val result = useCase.evaluate(
            cards = listOf(
                card("xy1-1", "Charizard", "1"),
                card("base1-4", "Charizard", "4")
            ),
            parsedName = "Charizard",
            parsedNumber = null
        )
        assertFalse(result.isHighConfidence)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SmartThresholdUseCaseTest" -q`
Expected: FAIL

- [ ] **Step 3: Create `SmartThresholdUseCase.kt`**

```kotlin
package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.model.TcgCard
import javax.inject.Inject

data class SearchConfidence(
    val cards: List<TcgCard>,
    val isHighConfidence: Boolean,
    val topCard: TcgCard?
)

class SmartThresholdUseCase @Inject constructor() {

    fun evaluate(cards: List<TcgCard>, parsedName: String, parsedNumber: String?): SearchConfidence {
        if (cards.isEmpty()) return SearchConfidence(cards, false, null)
        if (cards.size == 1) return SearchConfidence(cards, true, cards.first())

        // Find card matching both name and number exactly
        val exactMatch = cards.firstOrNull { card ->
            card.name.equals(parsedName, ignoreCase = true) &&
                parsedNumber != null && card.number == parsedNumber
        }

        return if (exactMatch != null) {
            SearchConfidence(cards, true, exactMatch)
        } else {
            SearchConfidence(cards, false, cards.first())
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SmartThresholdUseCaseTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCase.kt
git add app/src/test/java/com/skyler/pokedexbinder/domain/SmartThresholdUseCaseTest.kt
git commit -m "feat: add SmartThresholdUseCase for card match confidence scoring"
```

---

## Task 11: AssignCardUseCase

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/domain/AssignCardUseCase.kt`
- Test: `app/src/test/java/com/skyler/pokedexbinder/domain/AssignCardUseCaseTest.kt`

Core logic: check if slot is occupied → if yes, move old card to secondary binder → assign new card.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.BinderRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AssignCardUseCaseTest {

    private val binderRepo = mockk<BinderRepository>(relaxed = true)
    private val secondaryDao = mockk<SecondaryBinderDao>(relaxed = true)
    private val useCase = AssignCardUseCase(binderRepo, secondaryDao)

    private fun slot(pokemonId: String, cardId: String? = null) = PokemonSlot(
        id = pokemonId, name = pokemonId.replaceFirstChar { it.uppercase() },
        dexNumber = 1, dexOrder = 1, slotType = SlotType.BASE,
        assignedCardId = cardId,
        assignedCardImageUrl = cardId?.let { "https://img.url/$it" }
    )

    private fun card(id: String, name: String) = TcgCard(
        id = id, name = name, number = "1", setName = "XY",
        imageUrl = "https://img.url/$id", pokemonNames = listOf(name)
    )

    @Test
    fun `assign to empty slot does not touch secondary binder`() = runTest {
        coEvery { binderRepo.getSlotByPokemonId("bulbasaur") } returns slot("bulbasaur")

        useCase.assign("bulbasaur", card("xy1-1", "Bulbasaur"))

        coVerify(exactly = 0) { secondaryDao.insert(any()) }
        coVerify { binderRepo.assignCard("bulbasaur", "xy1-1", "https://img.url/xy1-1") }
    }

    @Test
    fun `assign to occupied slot moves old card to secondary binder`() = runTest {
        coEvery { binderRepo.getSlotByPokemonId("charizard") } returns
            slot("charizard", cardId = "base1-4")

        useCase.assign("charizard", card("xy1-11", "Charizard"))

        coVerify {
            secondaryDao.insert(
                SecondaryBinderEntry(
                    pokemonId = "charizard",
                    pokemonName = "Charizard",
                    cardId = "base1-4",
                    cardImageUrl = "https://img.url/base1-4"
                )
            )
        }
        coVerify { binderRepo.assignCard("charizard", "xy1-11", "https://img.url/xy1-11") }
    }

    @Test
    fun `assign does nothing when slot not found`() = runTest {
        coEvery { binderRepo.getSlotByPokemonId("missingno") } returns null

        useCase.assign("missingno", card("xy1-1", "MissingNo"))

        coVerify(exactly = 0) { secondaryDao.insert(any()) }
        coVerify(exactly = 0) { binderRepo.assignCard(any(), any(), any()) }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*.AssignCardUseCaseTest" -q`
Expected: FAIL

- [ ] **Step 3: Create `AssignCardUseCase.kt`**

```kotlin
package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.BinderRepository
import javax.inject.Inject

class AssignCardUseCase @Inject constructor(
    private val binderRepository: BinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao
) {
    suspend fun assign(pokemonId: String, card: TcgCard) {
        val slot = binderRepository.getSlotByPokemonId(pokemonId) ?: return

        if (slot.isOccupied) {
            secondaryBinderDao.insert(
                SecondaryBinderEntry(
                    pokemonId = slot.id,
                    pokemonName = slot.name,
                    cardId = slot.assignedCardId!!,
                    cardImageUrl = slot.assignedCardImageUrl!!
                )
            )
        }

        binderRepository.assignCard(pokemonId, card.id, card.imageUrl)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.AssignCardUseCaseTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/domain/AssignCardUseCase.kt
git add app/src/test/java/com/skyler/pokedexbinder/domain/AssignCardUseCaseTest.kt
git commit -m "feat: add AssignCardUseCase with replace and secondary binder logic"
```

---

## Task 12: App Entry Point + Navigation

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/MainActivity.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/navigation/AppNavigation.kt`

- [ ] **Step 1: Create `Theme.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFCC0000),
    onPrimary = Color.White,
    secondary = Color(0xFF3B5BA5),
    background = Color(0xFFF5F5F5)
)

@Composable
fun PokedexBinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
```

- [ ] **Step 2: Create `AppNavigation.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderScreen
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailScreen

sealed class Screen(val route: String) {
    object MainBinder : Screen("main_binder")
    object SecondaryBinder : Screen("secondary_binder")
    object SlotDetail : Screen("slot_detail/{pokemonId}") {
        fun createRoute(pokemonId: String) = "slot_detail/$pokemonId"
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val bottomItems = listOf(
        Triple(Screen.MainBinder, "Binder", Icons.Default.Book),
        Triple(Screen.SecondaryBinder, "History", Icons.Default.List)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDest = navBackStackEntry?.destination
                bottomItems.forEach { (screen, label, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
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
                MainBinderScreen(onSlotClick = { pokemonId ->
                    navController.navigate(Screen.SlotDetail.createRoute(pokemonId))
                })
            }
            composable(Screen.SecondaryBinder.route) { SecondaryBinderScreen() }
            composable(
                route = Screen.SlotDetail.route,
                arguments = listOf(navArgument("pokemonId") { type = NavType.StringType })
            ) { backStack ->
                SlotDetailScreen(
                    pokemonId = backStack.arguments?.getString("pokemonId") ?: "",
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
```

- [ ] **Step 3: Create `MainActivity.kt`**

```kotlin
package com.skyler.pokedexbinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.skyler.pokedexbinder.ui.navigation.AppNavigation
import com.skyler.pokedexbinder.ui.theme.PokedexBinderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PokedexBinderTheme { AppNavigation() }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/MainActivity.kt
git add app/src/main/java/com/skyler/pokedexbinder/ui/theme/
git add app/src/main/java/com/skyler/pokedexbinder/ui/navigation/
git commit -m "feat: add MainActivity, app theme, and navigation scaffold"
```

---

## Task 13: Main Binder Screen

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/mainbinder/MainBinderViewModel.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/mainbinder/MainBinderScreen.kt`
- Test: `app/src/test/java/com/skyler/pokedexbinder/ui/MainBinderViewModelTest.kt`

The ViewModel seeds Room from `pokemon_slots.json` on first launch if the table is empty, then exposes a `Flow<List<PokemonSlot>>`.

- [ ] **Step 1: Write failing ViewModel test**

```kotlin
package com.skyler.pokedexbinder.ui

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MainBinderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val binderRepo = mockk<BinderRepository>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `slots state reflects repository flow`() = runTest {
        val slot = PokemonSlot("bulbasaur", "Bulbasaur", 1, 1, SlotType.BASE)
        every { binderRepo.observeSlots() } returns flowOf(listOf(slot))

        val vm = MainBinderViewModel(binderRepo)
        vm.slots.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("bulbasaur", items[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*.MainBinderViewModelTest" -q`
Expected: FAIL

- [ ] **Step 3: Create `MainBinderViewModel.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.mainbinder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.R
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

@HiltViewModel
class MainBinderViewModel @Inject constructor(
    private val binderRepository: BinderRepository,
    @ApplicationContext private val context: Context? = null
) : ViewModel() {

    val slots: StateFlow<List<PokemonSlot>> = binderRepository.observeSlots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { seedIfEmpty() }
    }

    private suspend fun seedIfEmpty() {
        val current = binderRepository.observeSlots()
        // Only seed if table is empty — check by getting first emission
        val slots = binderRepository.getSlotByPokemonId("bulbasaur")
        if (slots == null && context != null) {
            val json = context.resources.openRawResource(R.raw.pokemon_slots)
                .bufferedReader().readText()
            val arr = JSONArray(json)
            val entries = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MainBinderEntry(
                    pokemonId = obj.getString("id"),
                    pokemonName = obj.getString("name"),
                    dexOrder = obj.getInt("dex_order")
                )
            }
            binderRepository.seedFromJson(entries)
        }
    }
}
```

- [ ] **Step 4: Create `MainBinderScreen.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.mainbinder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.model.PokemonSlot

@Composable
fun MainBinderScreen(
    onSlotClick: (String) -> Unit,
    viewModel: MainBinderViewModel = hiltViewModel()
) {
    val slots by viewModel.slots.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Pokédex Binder") })
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.padding(padding)
        ) {
            items(slots, key = { it.id }) { slot ->
                SlotCard(slot = slot, onClick = { onSlotClick(slot.id) })
            }
        }
    }
}

@Composable
private fun SlotCard(slot: PokemonSlot, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(0.72f)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (slot.isOccupied) {
                AsyncImage(
                    model = slot.assignedCardImageUrl,
                    contentDescription = slot.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "#${slot.dexOrder}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = slot.name,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run ViewModel tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*.MainBinderViewModelTest" -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/mainbinder/
git add app/src/test/java/com/skyler/pokedexbinder/ui/MainBinderViewModelTest.kt
git commit -m "feat: add Main Binder screen with 3x3 grid and seeding logic"
```

---

## Task 14: Slot Detail Screen

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/slotdetail/SlotDetailViewModel.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/slotdetail/SlotDetailScreen.kt`
- Test: `app/src/test/java/com/skyler/pokedexbinder/ui/SlotDetailViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel test**

```kotlin
package com.skyler.pokedexbinder.ui

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SlotDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val binderRepo = mockk<BinderRepository>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `slot state loads by pokemonId`() = runTest {
        val slot = PokemonSlot("pikachu", "Pikachu", 25, 25, SlotType.BASE)
        coEvery { binderRepo.getSlotByPokemonId("pikachu") } returns slot

        val vm = SlotDetailViewModel(binderRepo)
        vm.loadSlot("pikachu")
        advanceUntilIdle()

        vm.slot.test {
            assertEquals("pikachu", awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SlotDetailViewModelTest" -q`
Expected: FAIL

- [ ] **Step 3: Create `SlotDetailViewModel.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.slotdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SlotDetailViewModel @Inject constructor(
    private val binderRepository: BinderRepository
) : ViewModel() {

    private val _slot = MutableStateFlow<PokemonSlot?>(null)
    val slot: StateFlow<PokemonSlot?> = _slot

    fun loadSlot(pokemonId: String) {
        viewModelScope.launch {
            _slot.value = binderRepository.getSlotByPokemonId(pokemonId)
        }
    }
}
```

- [ ] **Step 4: Create `SlotDetailScreen.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.slotdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage

@Composable
fun SlotDetailScreen(
    pokemonId: String,
    onBack: () -> Unit,
    onScanCard: (String) -> Unit = {},
    onManualSearch: (String) -> Unit = {},
    viewModel: SlotDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(pokemonId) { viewModel.loadSlot(pokemonId) }
    val slot by viewModel.slot.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(slot?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (slot?.isOccupied == true) {
                AsyncImage(
                    model = slot!!.assignedCardImageUrl,
                    contentDescription = "Assigned card",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Button(onClick = { onScanCard(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Replace with Scanned Card")
                }
                OutlinedButton(onClick = { onManualSearch(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Search Manually")
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No card assigned yet", style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = { onScanCard(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan a Card")
                }
                OutlinedButton(onClick = { onManualSearch(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Search Manually")
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SlotDetailViewModelTest" -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/slotdetail/
git add app/src/test/java/com/skyler/pokedexbinder/ui/SlotDetailViewModelTest.kt
git commit -m "feat: add Slot Detail screen"
```

---

## Task 15: Scanner Screen

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerViewModel.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreen.kt`

The scanner uses CameraX to capture a photo, passes the image to ML Kit Text Recognition, parses the result with `OcrCardParser`, searches pokemontcg.io via `CardSearchRepository`, and applies `SmartThresholdUseCase` to determine whether to auto-confirm or show a selection list.

- [ ] **Step 1: Create `ScannerViewModel.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.scanner

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.OcrCardParser
import com.skyler.pokedexbinder.domain.SearchConfidence
import com.skyler.pokedexbinder.domain.SmartThresholdUseCase
import com.skyler.pokedexbinder.repository.CardSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed class ScannerState {
    object Idle : ScannerState()
    object Scanning : ScannerState()
    data class HighConfidence(val card: TcgCard) : ScannerState()
    data class LowConfidence(val cards: List<TcgCard>) : ScannerState()
    data class Error(val message: String) : ScannerState()
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val cardSearchRepository: CardSearchRepository,
    private val ocrCardParser: OcrCardParser,
    private val smartThresholdUseCase: SmartThresholdUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    val state: StateFlow<ScannerState> = _state

    fun processImage(imageProxy: ImageProxy) {
        _state.value = ScannerState.Scanning
        viewModelScope.launch {
            try {
                val image = InputImage.fromMediaImage(
                    imageProxy.image!!,
                    imageProxy.imageInfo.rotationDegrees
                )
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val result = recognizer.process(image).await()
                imageProxy.close()

                val parsed = ocrCardParser.parse(result.text)
                if (parsed.cardName.isBlank()) {
                    _state.value = ScannerState.Error("Could not read card text. Try again.")
                    return@launch
                }

                val cards = if (parsed.cardNumber != null) {
                    cardSearchRepository.searchByNameAndNumber(parsed.cardName, parsed.cardNumber)
                } else {
                    cardSearchRepository.searchByName(parsed.cardName)
                }

                val confidence = smartThresholdUseCase.evaluate(cards, parsed.cardName, parsed.cardNumber)
                _state.value = if (confidence.isHighConfidence && confidence.topCard != null) {
                    ScannerState.HighConfidence(confidence.topCard)
                } else {
                    ScannerState.LowConfidence(cards)
                }
            } catch (e: Exception) {
                imageProxy.close()
                _state.value = ScannerState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun searchManually(query: String) {
        _state.value = ScannerState.Scanning
        viewModelScope.launch {
            try {
                val cards = cardSearchRepository.searchByName(query)
                _state.value = ScannerState.LowConfidence(cards)
            } catch (e: Exception) {
                _state.value = ScannerState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun reset() { _state.value = ScannerState.Idle }
}
```

- [ ] **Step 2: Create `ScannerScreen.kt`**

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
import androidx.compose.material.icons.filled.ArrowBack
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(
    pokemonId: String,
    onCardSelected: (TcgCard) -> Unit,
    onBack: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val state by viewModel.state.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                                            viewModel.reset()
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                        ) { Text("Capture") }
                    } else {
                        Text("Camera permission required", modifier = Modifier.padding(16.dp))
                    }
                }
                is ScannerState.Scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ScannerState.HighConfidence -> {
                    CardConfirmation(
                        card = s.card,
                        onConfirm = { onCardSelected(s.card) },
                        onDismiss = { viewModel.reset() }
                    )
                }
                is ScannerState.LowConfidence -> {
                    CardSelectionList(
                        cards = s.cards,
                        onSelect = { onCardSelected(it) },
                        onBack = { viewModel.reset() }
                    )
                }
                is ScannerState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.reset() }) { Text("Try Again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
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
                Text("No cards found")
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

- [ ] **Step 3: Add `accompanist-permissions` to `libs.versions.toml` and `app/build.gradle.kts`**

In `libs.versions.toml`, add:
```toml
# under [versions]
accompanist = "0.34.0"

# under [libraries]
accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions", version.ref = "accompanist" }
```

In `app/build.gradle.kts`, add:
```kotlin
implementation(libs.accompanist.permissions)
```

- [ ] **Step 4: Compile check**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/scanner/
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add Scanner screen with CameraX, ML Kit OCR, and smart threshold"
```

---

## Task 16: Manual Search Screen

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/manualsearch/ManualSearchViewModel.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/manualsearch/ManualSearchScreen.kt`

- [ ] **Step 1: Create `ManualSearchViewModel.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.manualsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.CardSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Results(val cards: List<TcgCard>) : SearchState()
    data class Error(val message: String) : SearchState()
}

@HiltViewModel
class ManualSearchViewModel @Inject constructor(
    private val cardSearchRepository: CardSearchRepository
) : ViewModel() {

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state

    fun search(query: String) {
        if (query.isBlank()) return
        _state.value = SearchState.Loading
        viewModelScope.launch {
            try {
                val cards = cardSearchRepository.searchByName(query)
                _state.value = SearchState.Results(cards)
            } catch (e: Exception) {
                _state.value = SearchState.Error(e.message ?: "Search failed")
            }
        }
    }
}
```

- [ ] **Step 2: Create `ManualSearchScreen.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.manualsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.model.TcgCard

@Composable
fun ManualSearchScreen(
    onCardSelected: (TcgCard) -> Unit,
    onBack: () -> Unit,
    viewModel: ManualSearchViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search Cards") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Card name or set number") },
                trailingIcon = {
                    IconButton(onClick = { viewModel.search(query) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            when (val s = state) {
                is SearchState.Idle -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Search for a card above")
                    }
                }
                is SearchState.Loading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is SearchState.Results -> {
                    if (s.cards.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No cards found")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.cards) { card ->
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
                                    modifier = Modifier.clickable { onCardSelected(card) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                is SearchState.Error -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/manualsearch/
git commit -m "feat: add Manual Search screen"
```

---

## Task 17: Secondary Binder Screen

**Files:**
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/secondarybinder/SecondaryBinderViewModel.kt`
- Create: `app/src/main/java/com/skyler/pokedexbinder/ui/secondarybinder/SecondaryBinderScreen.kt`

- [ ] **Step 1: Create `SecondaryBinderViewModel.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.secondarybinder

import androidx.lifecycle.ViewModel
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class SecondaryBinderViewModel @Inject constructor(
    private val secondaryBinderDao: SecondaryBinderDao
) : ViewModel() {
    val entries: Flow<List<SecondaryBinderEntry>> = secondaryBinderDao.observeAll()
}
```

- [ ] **Step 2: Create `SecondaryBinderScreen.kt`**

```kotlin
package com.skyler.pokedexbinder.ui.secondarybinder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage

@Composable
fun SecondaryBinderScreen(
    viewModel: SecondaryBinderViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("Card History") }) }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No replaced cards yet")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.cardId) },
                        supportingContent = { Text("Replaced from: ${entry.pokemonName}") },
                        leadingContent = {
                            AsyncImage(
                                model = entry.cardImageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire Scanner and Manual Search into navigation**

Update `AppNavigation.kt` to add Scanner and Manual Search routes, and pass `onCardSelected` callbacks that call `AssignCardUseCase`. Add routes:

```kotlin
// Add to sealed class Screen:
object Scanner : Screen("scanner/{pokemonId}") {
    fun createRoute(pokemonId: String) = "scanner/$pokemonId"
}
object ManualSearch : Screen("manual_search/{pokemonId}") {
    fun createRoute(pokemonId: String) = "manual_search/$pokemonId"
}

// In SlotDetailScreen call, pass navigation lambdas:
SlotDetailScreen(
    pokemonId = ...,
    onBack = { navController.popBackStack() },
    onScanCard = { pid -> navController.navigate(Screen.Scanner.createRoute(pid)) },
    onManualSearch = { pid -> navController.navigate(Screen.ManualSearch.createRoute(pid)) }
)

// Add composable destinations:
composable(
    route = Screen.Scanner.route,
    arguments = listOf(navArgument("pokemonId") { type = NavType.StringType })
) { backStack ->
    val pokemonId = backStack.arguments?.getString("pokemonId") ?: ""
    val assignmentVm: AssignmentViewModel = hiltViewModel()
    ScannerScreen(
        pokemonId = pokemonId,
        onCardSelected = { card ->
            assignmentVm.assign(pokemonId, card)
            navController.popBackStack()
        },
        onBack = { navController.popBackStack() }
    )
}
```

Note: Card assignment on selection requires a shared ViewModel or a dedicated `AssignmentViewModel`. Add `AssignmentViewModel.kt`:

```kotlin
package com.skyler.pokedexbinder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.AssignCardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssignmentViewModel @Inject constructor(
    private val assignCardUseCase: AssignCardUseCase
) : ViewModel() {
    fun assign(pokemonId: String, card: TcgCard) {
        viewModelScope.launch { assignCardUseCase.assign(pokemonId, card) }
    }
}
```

Update `ScannerScreen` and `ManualSearchScreen` to accept `onCardSelected: (TcgCard) -> Unit` and navigate back after calling `assignmentViewModel.assign(pokemonId, card)`.

- [ ] **Step 4: Full build and compile check**

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL — generates `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/skyler/pokedexbinder/ui/
git commit -m "feat: add Secondary Binder screen, AssignmentViewModel, and wire full navigation"
```

---

## Task 18: Install and Smoke Test

- [ ] **Step 1: Install on device or emulator**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Smoke test checklist**

- [ ] App launches without crash
- [ ] Main Binder loads 3×3 grid (first few slots visible after seeding)
- [ ] Tapping a slot navigates to Slot Detail
- [ ] Slot Detail shows "No card assigned yet" for empty slot
- [ ] "Scan a Card" button opens Scanner with camera preview
- [ ] "Search Manually" opens Manual Search with text input
- [ ] Searching "Charizard" in Manual Search returns results
- [ ] Selecting a card from results assigns it to the slot
- [ ] Returning to Main Binder shows the assigned card image in the slot
- [ ] Assigning a second card to the same slot moves the first to Card History
- [ ] Secondary Binder tab shows replaced card

- [ ] **Step 3: Final commit**

```bash
git add .
git commit -m "chore: verified smoke test pass — app functional end to end"
```
