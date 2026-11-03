package com.skyler.pokedexbinder.di

import android.content.SharedPreferences
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

class SecurityPrefsModuleTest {
    private val prefs = mockk<SharedPreferences>()

    private class Calls {
        var creates = 0
        var deletes = 0
        val logged = mutableListOf<Exception>()
    }

    private fun run(calls: Calls, create: (Int) -> SharedPreferences): SharedPreferences =
        createOrResetOnDecryptFailure(
            create = { create(++calls.creates) },
            deleteFile = { calls.deletes++ },
            log = { calls.logged += it }
        )

    @Test
    fun `healthy create is returned untouched`() {
        val calls = Calls()
        assertSame(prefs, run(calls) { prefs })
        assertEquals(1, calls.creates)
        assertEquals(0, calls.deletes)
    }

    @Test
    fun `decrypt failure deletes the file and recreates once`() {
        val calls = Calls()
        val result = run(calls) { n -> if (n == 1) throw AEADBadTagException("bad tag") else prefs }
        assertSame(prefs, result)
        assertEquals(2, calls.creates)
        assertEquals(1, calls.deletes)
        assertTrue(calls.logged.single() is AEADBadTagException)
    }

    @Test
    fun `io failure from a corrupt keyset is also recovered`() {
        val calls = Calls()
        run(calls) { n -> if (n == 1) throw IOException("corrupt keyset") else prefs }
        assertEquals(1, calls.deletes)
    }

    @Test
    fun `second failure propagates after a single reset`() {
        val calls = Calls()
        try {
            run(calls) { throw GeneralSecurityException("keystore gone") }
            fail("expected GeneralSecurityException")
        } catch (e: GeneralSecurityException) {
            assertEquals(2, calls.creates)
            assertEquals(1, calls.deletes)
        }
    }

    @Test
    fun `unrelated exceptions are not swallowed and delete nothing`() {
        val calls = Calls()
        try {
            run(calls) { throw IllegalStateException("not a crypto problem") }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(1, calls.creates)
            assertEquals(0, calls.deletes)
        }
    }
}
