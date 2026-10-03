package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.remote.TcgcsvJapanSource
import com.skyler.pokedexbinder.data.remote.TcgdexRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JapanTcgcsvResolverTest {
    private val source = mockk<TcgcsvJapanSource>()
    private val resolver = JapanTcgcsvResolver(source)

    @Test
    fun `ja ref resolves through the source with set id and local id`() = runTest {
        coEvery { source.imageUrl("M6", "048", null) } returns "https://cdn/709201_400w.jpg"

        assertEquals("https://cdn/709201_400w.jpg", resolver.resolve(TcgdexRef("ja", "M6-048")))
    }

    @Test
    fun `non-ja refs never touch the source`() = runTest {
        assertNull(resolver.resolve(TcgdexRef("en", "sv08-048")))
        assertNull(resolver.resolve(TcgdexRef("ko", "SV8-048")))

        coVerify(exactly = 0) { source.imageUrl(any(), any(), any()) }
    }
}
