package com.skyler.pokedexbinder.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.security.GeneralSecurityException
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
        openEncryptedPrefs(context, "settings_secrets")

    @Provides @Singleton @Named("publishSecrets")
    fun providePublishSecurePrefs(@ApplicationContext context: Context): SharedPreferences =
        openEncryptedPrefs(context, "publish_secrets")

    private fun openEncryptedPrefs(context: Context, fileName: String): SharedPreferences =
        createOrResetOnDecryptFailure(
            create = {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context, fileName, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            },
            deleteFile = { context.deleteSharedPreferences(fileName) },
            log = { Log.w("SecurityPrefs", "$fileName unreadable (${it::class.java.simpleName}); recreating empty") }
        )
}

/**
 * An encrypted prefs file restored from a backup (or left after a Keystore reset) cannot be
 * decrypted, and `EncryptedSharedPreferences.create` then throws on every launch, so the app never
 * starts. Only [GeneralSecurityException] / [IOException] (AEAD tag mismatch, missing Keystore key,
 * corrupt keyset) count: the stored secrets are lost either way, so delete the file and create it
 * empty once. The keys must be re-entered; a second failure propagates. Logs the exception class only.
 */
internal fun createOrResetOnDecryptFailure(
    create: () -> SharedPreferences,
    deleteFile: () -> Unit,
    log: (Exception) -> Unit
): SharedPreferences = try {
    create()
} catch (e: Exception) {
    if (e !is GeneralSecurityException && e !is IOException) throw e
    log(e)
    deleteFile()
    create()
}
