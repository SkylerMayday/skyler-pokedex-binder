import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load signing credentials from keystore.properties (never commit this file)
val keystoreProps = Properties().also { props ->
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) props.load(propsFile.inputStream())
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
        testInstrumentationRunner = "com.skyler.pokedexbinder.CustomTestRunner"
    }

    signingConfigs {
        create("release") {
            val sf = keystoreProps["storeFile"] as? String
            if (sf != null) {
                storeFile = file(sf)
                storePassword = keystoreProps["storePassword"] as? String ?: ""
                keyAlias = keystoreProps["keyAlias"] as? String ?: ""
                keyPassword = keystoreProps["keyPassword"] as? String ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Android Studio Narwhal (AI-253 / Kotlin plugin 2.2.x) requests this task during project sync
tasks.register("prepareKotlinBuildScriptModel")

// androidx.graphics:graphics-path resolves to 1.0.1 transitively via composeBom 2026.06.00's own
// androidx.compose.ui:ui-graphics entry, which hasn't bumped its own floor yet — one of the 3
// natives originally flagged by the 16KB page-size warning (see gaps.md, 2026-08-28 entry).
// Force the latest stable release directly; verify this actually clears the warning (1.1.0's own
// changelog doesn't call out 16KB alignment explicitly, so don't assume it's fixed without
// rechecking the build output).
configurations.all {
    resolutionStrategy {
        force("androidx.graphics:graphics-path:1.1.0")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coroutines.android)

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

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Coil
    implementation(libs.coil.compose)

    // Material Components (provides Theme.MaterialComponents for themes.xml / edge-to-edge)
    implementation(libs.material)

    // Reorderable (drag-to-reorder for LazyColumn)
    implementation(libs.reorderable)

    // DataStore (settings persistence)
    implementation(libs.datastore.preferences)

    // EncryptedSharedPreferences (publish secrets: GitHub PAT, Discord webhook URL)
    implementation(libs.androidx.security.crypto)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
