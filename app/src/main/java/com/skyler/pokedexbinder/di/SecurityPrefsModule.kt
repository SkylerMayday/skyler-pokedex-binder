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
        openSecrets(context, "settings_secrets", "settings_secrets_v2")

    @Provides @Singleton @Named("publishSecrets")
    fun providePublishSecurePrefs(@ApplicationContext context: Context): SharedPreferences =
        openSecrets(context, "publish_secrets", "publish_secrets_v2")

    private fun openSecrets(context: Context, oldName: String, newName: String): SharedPreferences {
        val newRaw = context.getSharedPreferences(newName, Context.MODE_PRIVATE)
        val store = KeystoreSecretStore(
            newRaw, newName, cacheOnSuccess { androidKeystoreAesKey(SECRETS_KEY_ALIAS) },
            log = { Log.w(TAG, "$newName value unreadable (${it::class.java.simpleName})") }
        )
        return migrateLegacySecrets(
            store, newRaw,
            oldHasData = { context.getSharedPreferences(oldName, Context.MODE_PRIVATE).all.isNotEmpty() },
            openOld = {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context, oldName, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            },
            deleteOld = { context.deleteSharedPreferences(oldName) },
            log = { Log.w(TAG, "$oldName migration: $it") }
        )
    }

    private const val TAG = "SecurityPrefs"
}

private const val LEGACY_RESERVED_PREFIX = "__androidx_security_crypto_encrypted_prefs_"

/**
 * Fail-safe legacy (EncryptedSharedPreferences) -> v2 copy. Returns the prefs to serve this launch
 * and never throws. The legacy file is deleted only after every copied value reads back equal; every
 * failure keeps it and retries next launch. Logs counts and exception classes only, never values.
 */
internal fun migrateLegacySecrets(
    newStore: SharedPreferences,
    newRaw: SharedPreferences,
    oldHasData: () -> Boolean,
    openOld: () -> SharedPreferences,
    deleteOld: () -> Boolean,
    log: (String) -> Unit
): SharedPreferences {
    if (!oldHasData()) return newStore
    val old = try {
        openOld()
    } catch (e: Exception) {
        log("legacy store unreadable (${e::class.java.simpleName}); keeping it")
        return newStore
    }
    var toCopy: Map<String, String> = emptyMap()
    try {
        val entries = old.all.filterKeys { !it.startsWith(LEGACY_RESERVED_PREFIX) }
            .mapValues { (_, v) -> v as? String ?: throw IllegalStateException("non-String legacy value") }
        toCopy = entries.filter { newStore.getString(it.key, null) == null }
        if (toCopy.isNotEmpty()) {
            val editor = newStore.edit()
            toCopy.forEach { (k, v) -> editor.putString(k, v) }
            check(editor.commit()) { "commit failed" }
            check(toCopy.all { (k, v) -> newStore.getString(k, null) == v }) { "read-back mismatch" }
        }
        if (!deleteOld()) log("legacy delete failed; retrying next launch")
        log("migrated ${toCopy.size} of ${entries.size}")
        return newStore
    } catch (e: Exception) {
        log("migration failed (${e::class.java.simpleName}); serving legacy, retrying next launch")
        try {
            val rollback = newRaw.edit()
            toCopy.keys.forEach { rollback.remove(it) }
            if (!rollback.commit()) log("rollback commit failed")
        } catch (r: Exception) {
            log("rollback failed (${r::class.java.simpleName})")
        }
        return old
    }
}
