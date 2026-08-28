package com.skyler.pokedexbinder

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps in [HiltTestApplication] as the instrumented-test [Application] so `@HiltAndroidTest`
 * classes (e.g. [com.skyler.pokedexbinder.ui.navigation.AppNavigationScreenTest]) get a real Hilt
 * component graph. [HiltTestApplication] is a drop-in [Application] replacement -- existing
 * non-Hilt instrumented tests (Room migration tests, [com.skyler.pokedexbinder.di.DatabaseModuleWiringTest],
 * etc.) are unaffected by this runner swap.
 */
class CustomTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
