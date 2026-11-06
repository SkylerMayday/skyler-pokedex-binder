package com.skyler.pokedexbinder.di

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val SECRETS_KEY_ALIAS = "pokedexbinder_secrets_aes_v1"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val AES_KEY_BITS = 256
private val keyCreationLock = Any()

/**
 * Returns the AndroidKeyStore AES key for [alias], creating it if absent. If the alias exists but
 * cannot be loaded this throws and never regenerates: a fresh key would orphan every stored value
 * (a transient load failure must not destroy secrets). Creation is serialised app-wide so two
 * stores opening at once cannot both generate.
 */
internal fun androidKeystoreAesKey(alias: String): SecretKey = synchronized(keyCreationLock) {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (ks.getKey(alias, null) as? SecretKey)?.let { return it }
    if (ks.containsAlias(alias)) throw KeyStoreException("Keystore alias exists but its key could not be loaded")
    val spec = KeyGenParameterSpec.Builder(
        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(AES_KEY_BITS)
        .build()
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply { init(spec) }
        .generateKey()
}

/** Caches [load]'s result only on success: a throwing load is retried on the next call (Kotlin `lazy` does not cache exceptions). */
internal fun <T> cacheOnSuccess(load: () -> T): () -> T {
    val cached = lazy(load)
    return { cached.value }
}

/**
 * String-only [SharedPreferences] whose values are AES-256-GCM encrypted under [key] and stored in
 * the plain [raw] file as `Base64(iv[12] || ciphertext || tag[16])`. Key names stay in clear.
 * The AAD is `"$storeName/$key"`, so a ciphertext cannot be swapped between keys or files.
 *
 * A value that fails to decrypt reads as the default and is left in place (never deleted); the next
 * `putString` overwrites it. Unsupported getters/putters and listeners throw.
 */
class KeystoreSecretStore(
    private val raw: SharedPreferences,
    private val storeName: String,
    private val key: () -> SecretKey,
    private val log: (Exception) -> Unit
) : SharedPreferences {

    private fun aad(k: String) = "$storeName/$k".toByteArray(Charsets.UTF_8)

    private fun encrypt(k: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "unexpected IV size" }
        cipher.updateAAD(aad(k))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(k: String, stored: String): String {
        val blob = Base64.getDecoder().decode(stored)
        require(blob.size > GCM_IV_BYTES) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
        cipher.updateAAD(aad(k))
        return String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    private fun decryptOrNull(k: String, stored: String): String? = try {
        decrypt(k, stored)
    } catch (e: Exception) {
        log(e)
        null
    }

    override fun getString(key: String, defValue: String?): String? {
        val stored = raw.getString(key, null) ?: return defValue // D15: no Keystore touch when absent
        return decryptOrNull(key, stored) ?: defValue
    }

    /** True for any raw entry, including one that cannot be decrypted. */
    override fun contains(key: String) = raw.contains(key)

    override fun getAll(): MutableMap<String, *> {
        val out = mutableMapOf<String, String>()
        for ((k, v) in raw.all) {
            if (v is String) decryptOrNull(k, v)?.let { out[k] = it }
        }
        return out
    }

    override fun edit(): SharedPreferences.Editor = SecretEditor()

    private inner class SecretEditor : SharedPreferences.Editor {
        private var cleared = false
        private val removes = linkedSetOf<String>()
        private val puts = linkedMapOf<String, String>()

        override fun putString(key: String, value: String?) = apply {
            if (value == null) remove(key) else { removes -= key; puts[key] = value }
        }

        override fun remove(key: String) = apply { puts -= key; removes += key }

        override fun clear() = apply { cleared = true; puts.clear(); removes.clear() }

        override fun commit(): Boolean = write()

        // Synchronous commit is deliberate (D13): failures are logged and never thrown into callers.
        override fun apply() { write() }

        /** Encrypts everything first; any failure writes nothing (D13). */
        private fun write(): Boolean {
            val encrypted = try {
                puts.mapValues { (k, v) -> encrypt(k, v) }
            } catch (e: Exception) {
                log(e)
                return false
            }
            val editor = raw.edit()
            if (cleared) editor.clear()
            removes.forEach { editor.remove(it) }
            encrypted.forEach { (k, v) -> editor.putString(k, v) }
            return editor.commit()
        }

        override fun putStringSet(key: String, values: MutableSet<String>?) = unsupported()
        override fun putInt(key: String, value: Int) = unsupported()
        override fun putLong(key: String, value: Long) = unsupported()
        override fun putFloat(key: String, value: Float) = unsupported()
        override fun putBoolean(key: String, value: Boolean) = unsupported()
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?) = unsupported()
    override fun getInt(key: String, defValue: Int) = unsupported()
    override fun getLong(key: String, defValue: Long) = unsupported()
    override fun getFloat(key: String, defValue: Float) = unsupported()
    override fun getBoolean(key: String, defValue: Boolean) = unsupported()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        unsupported()
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        unsupported()
}

private fun unsupported(): Nothing =
    throw UnsupportedOperationException("KeystoreSecretStore only supports String values")
