package com.skyler.pokedexbinder.data.local.backup

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationPathResolverTest {

    private val realMigrations = PokedexDatabase.ALL_MIGRATIONS

    @Test
    fun `contiguous forward chain from v4 to v9 through the 5 real migrations resolves true`() {
        assertTrue(MigrationPathResolver.hasPath(realMigrations, from = 4, to = 9))
    }

    @Test
    fun `missing a single link in the chain resolves false`() {
        val brokenChain = realMigrations.filterNot { it.startVersion == 6 }.toTypedArray()
        assertFalse(MigrationPathResolver.hasPath(brokenChain, from = 4, to = 9))
    }

    @Test
    fun `same version resolves true trivially`() {
        assertTrue(MigrationPathResolver.hasPath(realMigrations, from = 7, to = 7))
    }

    @Test
    fun `downgrade direction with only forward migrations declared resolves false`() {
        assertFalse(MigrationPathResolver.hasPath(realMigrations, from = 9, to = 7))
    }

    @Test
    fun `a multi-version jump migration is honored by the graph search, not just adjacent steps`() {
        val jumpMigration = object : Migration(5, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // no-op: only edge shape (startVersion/endVersion) matters for this resolver
            }
        }
        val migrationsWithJump = arrayOf<Migration>(jumpMigration)

        assertTrue(MigrationPathResolver.hasPath(migrationsWithJump, from = 5, to = 9))
        assertFalse(MigrationPathResolver.hasPath(migrationsWithJump, from = 4, to = 9))
    }
}
