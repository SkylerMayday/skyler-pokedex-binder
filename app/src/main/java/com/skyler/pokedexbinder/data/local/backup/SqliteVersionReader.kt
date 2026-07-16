package com.skyler.pokedexbinder.data.local.backup

import java.io.File

/** Reads the on-disk SQLite `PRAGMA user_version` without opening the file via Room. */
interface SqliteVersionReader {
    /** Returns null if [dbFile] doesn't exist or can't be opened (corrupt/locked). */
    fun readVersion(dbFile: File): Int?
}

class FrameworkSqliteVersionReader : SqliteVersionReader {
    override fun readVersion(dbFile: File): Int? {
        if (!dbFile.exists()) return null
        return try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
        } catch (e: Exception) {
            android.util.Log.w("SqliteVersionReader", "Could not read on-disk version for ${dbFile.name}", e)
            null
        }
    }
}
