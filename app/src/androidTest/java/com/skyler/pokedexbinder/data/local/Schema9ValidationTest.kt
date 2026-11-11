package com.skyler.pokedexbinder.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the exported schema (app/schemas/.../9.json) is packaged as an androidTest asset, its SQL
 * runs, and the database opens at version 9.
 *
 * Does NOT detect drift between 9.json and the entities: at equal start/end versions
 * MigrationTestHelper validates the JSON against itself. Drift shows up as a `git diff` on 9.json,
 * because Room rewrites it on every KSP build. A real guard arrives with the first 8->9
 * runMigrationsAndValidate test once an 8.json exists.
 */
@RunWith(AndroidJUnit4::class)
class Schema9ValidationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PokedexDatabase::class.java,
    )

    @Test
    fun schema9_opensAndValidates() {
        helper.createDatabase("schema9-test", PokedexDatabase.SCHEMA_VERSION).close()
        helper.runMigrationsAndValidate("schema9-test", PokedexDatabase.SCHEMA_VERSION, true).close()
    }
}
