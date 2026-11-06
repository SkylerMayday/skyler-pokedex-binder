package com.skyler.pokedexbinder.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.util.UUID
import javax.inject.Named
import javax.inject.Singleton

/**
 * Replaces [SecurityPrefsModule] for every `@HiltAndroidTest`. `HiltAndroidRule` builds a fresh
 * SingletonComponent per test, so the real module opened a second DataStore on the same on-device
 * file each time ("multiple DataStores active for the same file"). It also needs the Keystore (key creation,
 * legacy ESP migration) and touches real settings/publish data.
 *
 * Each binding here gets its own uniquely named temp DataStore file / plain SharedPreferences, so
 * components never collide or leak into each other and the real files are never opened. Every
 * `@Provides` must mirror [SecurityPrefsModule] (same `@Named` qualifier and type), since
 * `replaces = [SecurityPrefsModule::class]` replaces the whole module. Encryption behaviour is
 * deliberately untested here.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SecurityPrefsModule::class])
object FakeSecurityPrefsModule {

    @Provides @Singleton @Named("settingsDataStore")
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        tempDataStore(context, "settings")

    @Provides @Singleton @Named("publishDataStore")
    fun providePublishDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        tempDataStore(context, "publish")

    @Provides @Singleton @Named("settingsSecrets")
    fun provideSettingsSecurePrefs(@ApplicationContext context: Context): SharedPreferences =
        tempPrefs(context, "settings")

    @Provides @Singleton @Named("publishSecrets")
    fun providePublishSecurePrefs(@ApplicationContext context: Context): SharedPreferences =
        tempPrefs(context, "publish")

    // ponytail: scope never cancelled and temp files are never deleted; orphans accumulate in
    // cacheDir until the app is uninstalled or the cache cleared (tiny). Add a teardown hook if it matters.
    // The file must end in .preferences_pb and must NOT be pre-created (an empty file reads as corrupt).
    private fun tempDataStore(context: Context, name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "test_${name}_${UUID.randomUUID()}.preferences_pb") }
        )

    private fun tempPrefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences("test_${name}_${UUID.randomUUID()}", Context.MODE_PRIVATE)
}
