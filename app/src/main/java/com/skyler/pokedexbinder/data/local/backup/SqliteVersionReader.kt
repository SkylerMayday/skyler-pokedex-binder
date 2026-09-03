package com.skyler.pokedexbinder.data.local.backup

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Reads facts about an on-disk SQLite file without going through Room.
 *
 * Callers pass files this class does not own — including the **live** app database
 * ([DatabaseBackupManager] does, on every cold start). Opening a WAL-mode database creates
 * `-wal`/`-shm` sidecars next to it, but removing those is deliberately *not* done here: deleting
 * the live database's WAL before Room opens it discards committed-but-uncheckpointed transactions.
 * Whoever owns the file owns its sidecars — see [BackupImporter]'s import-preview temp-file
 * lifecycle, which is the only place that both creates a throwaway `.db` and cleans it up.
 */
interface SqliteVersionReader {
    /** Returns null if [dbFile] doesn't exist or can't be opened (corrupt/locked). */
    fun readVersion(dbFile: File): Int?

    /**
     * Names of the tables in [dbFile], or null if it can't be opened. Lets a caller confirm a
     * picked file is actually this app's database — `PRAGMA user_version` alone can't, since most
     * unrelated SQLite files report version 0, which passes any "not newer than mine" gate.
     */
    fun readTableNames(dbFile: File): Set<String>?
}

class FrameworkSqliteVersionReader : SqliteVersionReader {

    override fun readVersion(dbFile: File): Int? = readOnly(dbFile) { it.version }

    override fun readTableNames(dbFile: File): Set<String>? = readOnly(dbFile) { db ->
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun <T> readOnly(dbFile: File, read: (SQLiteDatabase) -> T): T? {
        if (!dbFile.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use(read)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${dbFile.name}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SqliteVersionReader"
    }
}
