package com.skyler.pokedexbinder.di

import android.content.SharedPreferences
import com.skyler.pokedexbinder.repository.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecurityPrefsModuleTest {
    private val aes: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val newRaw = FakeSharedPreferences()
    private var key: () -> SecretKey = { aes }
    private val newStore = KeystoreSecretStore(newRaw, "new", { key() }) {}
    private val old = FakeSharedPreferences()
    private var opens = 0
    private var deletes = 0
    private var deleteResult = true
    private val logs = mutableListOf<String>()

    private fun fillOld() {
        old.edit().putString("gemini_api_key", "gem-1").putString("anthropic_api_key", "ant-2")
            .putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "reserved").commit()
    }

    private fun migrate(
        store: SharedPreferences = newStore,
        openOld: () -> SharedPreferences = { opens++; old }
    ) = migrateLegacySecrets(
        store, newRaw,
        oldHasData = { old.all.isNotEmpty() },
        openOld = openOld,
        deleteOld = { deletes++; deleteResult },
        log = { logs += it }
    )

    @Test
    fun `copies string entries, skips reserved keys, deletes old once`() {
        fillOld()
        assertSame(newStore, migrate())
        assertEquals("gem-1", newStore.getString("gemini_api_key", null))
        assertEquals("ant-2", newStore.getString("anthropic_api_key", null))
        assertFalse(newRaw.contains("__androidx_security_crypto_encrypted_prefs_key_keyset__"))
        assertEquals(1, deletes)
        assertTrue(logs.contains("migrated 2 of 2"))
    }

    @Test
    fun `no old data never opens old`() {
        assertSame(newStore, migrate())
        assertEquals(0, opens)
        assertEquals(0, deletes)
    }

    @Test
    fun `openOld failure serves new store and deletes nothing`() {
        fillOld()
        assertSame(newStore, migrate(openOld = { throw GeneralSecurityException("keystore") }))
        assertEquals(0, deletes)
        assertTrue(newRaw.all.isEmpty())
    }

    @Test
    fun `write failure serves old, keeps it, leaves new empty`() {
        fillOld()
        key = { throw KeyStoreException("down") }
        assertSame(old, migrate())
        assertEquals(0, deletes)
        assertTrue(newRaw.all.isEmpty())
    }

    @Test
    fun `read-back mismatch serves old and rolls back copied keys`() {
        fillOld()
        val lying = object : SharedPreferences by newStore {
            override fun getString(key: String, defValue: String?): String? =
                newStore.getString(key, defValue)?.reversed()
        }
        assertSame(old, migrate(store = lying))
        assertEquals(0, deletes)
        assertTrue(newRaw.all.isEmpty())
    }

    @Test
    fun `failed delete serves new store and retries next launch`() {
        fillOld()
        deleteResult = false
        assertSame(newStore, migrate())
        assertSame(newStore, migrate())
        assertEquals("gem-1", newStore.getString("gemini_api_key", null))
        assertEquals(2, deletes)
    }

    @Test
    fun `newer value in new store beats older legacy value`() {
        newStore.edit().putString("k", "newer").commit()
        old.edit().putString("k", "older").commit()
        migrate()
        assertEquals("newer", newStore.getString("k", null))
    }

    @Test
    fun `undecryptable new entry is replaced by the legacy value`() {
        newRaw.edit().putString("k", "AAAA").commit()
        old.edit().putString("k", "legacy").commit()
        migrate()
        assertEquals("legacy", newStore.getString("k", null))
    }

    @Test
    fun `non-String legacy value serves old and deletes nothing`() {
        old.edit().putString("k", "v").putInt("n", 1).commit()
        assertSame(old, migrate())
        assertEquals(0, deletes)
    }
}
