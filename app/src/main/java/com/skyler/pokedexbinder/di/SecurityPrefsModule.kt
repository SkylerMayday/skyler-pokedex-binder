package com.skyler.pokedexbinder.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityPrefsModule {

    @Provides @Singleton @Named("settingsDataStore")
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("app_settings") })

    @Provides @Singleton @Named("publishDataStore")
    fun providePublishDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("publish_settings") })

    @Provides @Singleton @Named("settingsSecrets")
    fun provideSettingsSecurePrefs(@ApplicationContext context: Context): SharedPreferences =
        openSecrets(context, "settings_secrets_v2")

    @Provides @Singleton @Named("publishSecrets")
    fun providePublishSecurePrefs(@ApplicationContext context: Context): SharedPreferences =
        openSecrets(context, "publish_secrets_v2")

    private fun openSecrets(context: Context, name: String): SharedPreferences {
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return KeystoreSecretStore(
            raw, name, cacheOnSuccess { androidKeystoreAesKey(SECRETS_KEY_ALIAS) },
            log = { Log.w(TAG, "$name value unreadable (${it::class.java.simpleName})") }
        )
    }

    private const val TAG = "SecurityPrefs"
}
