package com.skyler.pokedexbinder.di

import android.content.SharedPreferences
import com.skyler.pokedexbinder.repository.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeystoreSecretStoreTest {
    private val aes: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val logged = mutableListOf<Exception>()

    private fun store(
        raw: SharedPreferences,
        name: String = "s",
        key: () -> SecretKey = { aes }
    ) = KeystoreSecretStore(raw, name, key) { logged += it }

    @Test
    fun `round trip via commit and apply, raw is not plaintext`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        assertTrue(s.edit().putString("a", "secret-one").commit())
        s.edit().putString("b", "secret-two").apply()
        assertEquals("secret-one", s.getString("a", null))
        assertEquals("secret-two", s.getString("b", null))
        val stored = raw.getString("a", null)!!
        assertNotEquals("secret-one", stored)
        assertFalse(stored.contains("secret-one"))
    }

    @Test
    fun `ciphertext copied to another key returns default`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        s.edit().putString("a", "v").commit()
        raw.edit().putString("b", raw.getString("a", null)).commit()
        assertEquals("def", s.getString("b", "def"))
        assertTrue(logged.single() is AEADBadTagException)
    }

    @Test
    fun `ciphertext moved to another store name returns default`() {
        val rawA = FakeSharedPreferences()
        val rawB = FakeSharedPreferences()
        store(rawA, "A").edit().putString("k", "v").commit()
        rawB.edit().putString("k", rawA.getString("k", null)).commit()
        assertEquals("def", store(rawB, "B").getString("k", "def"))
    }

    @Test
    fun `flipped byte, non base64 and short blob return default without throwing`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        s.edit().putString("a", "v").commit()
        val blob = Base64.getDecoder().decode(raw.getString("a", null))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        raw.edit().putString("a", Base64.getEncoder().encodeToString(blob))
            .putString("junk", "!!not base64!!")
            .putString("short", Base64.getEncoder().encodeToString(ByteArray(5))).commit()
        assertEquals("def", s.getString("a", "def"))
        assertEquals("def", s.getString("junk", "def"))
        assertEquals("def", s.getString("short", "def"))
        assertEquals(3, logged.size)
    }

    @Test
    fun `same value twice gives different ciphertext and 12 byte IVs`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        s.edit().putString("a", "same").commit()
        val first = raw.getString("a", null)!!
        s.edit().putString("a", "same").commit()
        val second = raw.getString("a", null)!!
        assertNotEquals(first, second)
        val ivs = listOf(first, second).map { Base64.getDecoder().decode(it).copyOfRange(0, 12).toList() }
        assertNotEquals(ivs[0], ivs[1])
        // iv 12 + plaintext 4 + tag 16
        assertEquals(12 + 4 + 16, Base64.getDecoder().decode(first).size)
    }

    @Test
    fun `transient key failure returns default, keeps raw, recovers`() {
        val raw = FakeSharedPreferences()
        store(raw).edit().putString("a", "v").commit()
        var failOnce = true
        val s = store(raw) { if (failOnce) { failOnce = false; throw KeyStoreException("hiccup") } else aes }
        assertEquals("def", s.getString("a", "def"))
        assertTrue(raw.contains("a"))
        assertEquals("v", s.getString("a", "def"))
    }

    @Test
    fun `failed encrypt writes nothing, commit false, apply does not throw`() {
        val raw = FakeSharedPreferences()
        store(raw).edit().putString("keep", "old").commit()
        val before = raw.getString("keep", null)
        var calls = 0
        val s = store(raw) { if (++calls == 2) throw KeyStoreException("boom") else aes }
        val ed = s.edit().putString("keep", "new1").putString("other", "new2")
        assertFalse(ed.commit())
        assertEquals(before, raw.getString("keep", null))
        assertFalse(raw.contains("other"))
        calls = 1 // second encrypt of the apply() attempt fails again
        ed.apply()
        assertEquals(before, raw.getString("keep", null))
        assertFalse(raw.contains("other"))
    }

    @Test
    fun `absent key never touches the key lambda`() {
        val s = store(FakeSharedPreferences()) { fail("key requested"); aes }
        assertEquals("def", s.getString("nope", "def"))
        assertNull(s.getString("nope", null))
    }

    @Test
    fun `remove, put null and clear delete entries`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        s.edit().putString("a", "1").putString("b", "2").putString("c", "3").commit()
        s.edit().remove("a").commit()
        s.edit().putString("b", null).commit()
        assertFalse(raw.contains("a"))
        assertFalse(raw.contains("b"))
        assertEquals("3", s.getString("c", null))
        s.edit().clear().commit()
        assertTrue(raw.all.isEmpty())
    }

    @Test
    fun `getAll decrypts good entries and omits a bad one, contains stays raw presence`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        s.edit().putString("a", "1").commit()
        raw.edit().putString("bad", "AAAA").commit()
        assertEquals(mapOf("a" to "1"), s.all)
        assertTrue(s.contains("bad"))
    }

    @Test
    fun `empty, unicode and very long values round trip`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        val long = "x".repeat(200_000)
        s.edit().putString("empty", "").putString("uni", "kéy-日本-🔑").putString("long", long).commit()
        assertEquals("", s.getString("empty", "def"))
        assertEquals("kéy-日本-🔑", s.getString("uni", null))
        assertEquals(long, s.getString("long", null))
    }

    @Test
    fun `blob of exactly IV size or tag-less size returns default`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        val enc = Base64.getEncoder()
        raw.edit().putString("iv", enc.encodeToString(ByteArray(12)))
            .putString("noTag", enc.encodeToString(ByteArray(20)))
            .putString("blank", "").commit()
        assertEquals("def", s.getString("iv", "def"))
        assertEquals("def", s.getString("noTag", "def"))
        assertEquals("def", s.getString("blank", "def"))
        assertTrue(raw.contains("iv") && raw.contains("noTag") && raw.contains("blank"))
    }

    @Test
    fun `clear then put in one editor keeps only the new value`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        s.edit().putString("old", "1").commit()
        assertTrue(s.edit().clear().putString("new", "2").commit())
        assertFalse(raw.contains("old"))
        assertEquals("2", s.getString("new", null))
    }

    @Test
    fun `repository call patterns work and overwrite replaces the value`() {
        val s = store(FakeSharedPreferences())
        assertEquals("", s.getString("gemini_api_key", "") ?: "")
        s.edit().putString("gemini_api_key", "k1").apply()
        assertEquals("k1", s.getString("gemini_api_key", ""))
        s.edit().putString("gemini_api_key", "k2").commit()
        assertEquals("k2", s.getString("gemini_api_key", null))
        s.edit().putString("gemini_api_key", "").apply()
        assertEquals("", s.getString("gemini_api_key", "def"))
    }

    @Test
    fun `logged exceptions never carry the plaintext`() {
        val raw = FakeSharedPreferences()
        val s = store(raw)
        s.edit().putString("a", "TOP-SECRET-VALUE").commit()
        raw.edit().putString("b", raw.getString("a", null)).putString("c", "!!bad!!").commit()
        s.getString("b", null); s.getString("c", null)
        assertTrue(logged.isNotEmpty())
        logged.forEach { assertFalse(it.message.orEmpty().contains("TOP-SECRET-VALUE")) }
    }

    @Test
    fun `unsupported types throw`() {
        val s = store(FakeSharedPreferences())
        try { s.getInt("x", 0); fail() } catch (_: UnsupportedOperationException) {}
        try { s.edit().putBoolean("x", true); fail() } catch (_: UnsupportedOperationException) {}
    }
}
