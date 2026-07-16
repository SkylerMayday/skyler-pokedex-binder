package com.skyler.pokedexbinder.data.local.backup

import android.content.Context
import android.util.Log
import androidx.room.migration.Migration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatabaseBackupManager(
    private val versionReader: SqliteVersionReader = FrameworkSqliteVersionReader(),
    private val maxBackups: Int = MAX_BACKUPS
) {
    /**
     * Call BEFORE Room.databaseBuilder(...).build(). If the on-disk schema version differs from
     * [targetVersion] AND no unbroken [migrations] path connects them (i.e. Room's destructive
     * fallback is about to fire), copies [dbFileName] + -wal/-shm sidecars (if present) into
     * context.filesDir/db_backups/ before returning, then rotates old backups down to
     * [maxBackups]. Best-effort: any failure is logged and swallowed — a failed backup must never
     * block app startup or prevent the (already-inevitable) migration/build from proceeding.
     *
     * Cheap on the common path: exactly one read-only PRAGMA user_version query when versions
     * already match or a full incremental path exists; the file copy only runs on the rare
     * destructive-bound path.
     */
    fun backupIfDestructiveMigrationImminent(
        context: Context,
        dbFileName: String,
        targetVersion: Int,
        migrations: Array<Migration>
    ) {
        try {
            val dbFile = context.getDatabasePath(dbFileName)
            val onDiskVersion = versionReader.readVersion(dbFile) ?: return // no file yet, or unreadable — nothing to protect
            if (onDiskVersion == targetVersion) return
            if (MigrationPathResolver.hasPath(migrations, onDiskVersion, targetVersion)) return

            Log.w(TAG, "Destructive migration imminent (on-disk v$onDiskVersion -> target v$targetVersion, no path) — backing up first")
            val backupDir = File(context.filesDir, BACKUP_DIR_NAME).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = "pokedex_binder_v${onDiskVersion}to${targetVersion}_$stamp"

            copyIfExists(dbFile, File(backupDir, "$baseName.db"))
            copyIfExists(File(dbFile.path + "-wal"), File(backupDir, "$baseName.db-wal"))
            copyIfExists(File(dbFile.path + "-shm"), File(backupDir, "$baseName.db-shm"))

            rotate(backupDir)
        } catch (e: Exception) {
            // Never let a backup failure block app startup or the migration it's insuring against.
            Log.e(TAG, "Pre-migration backup failed — proceeding without it", e)
        }
    }

    private fun copyIfExists(source: File, dest: File) {
        if (source.exists()) source.copyTo(dest, overwrite = true)
    }

    /** Keeps the newest [maxBackups] `.db` base names (by lastModified), deletes the rest + their sidecars. */
    private fun rotate(backupDir: File) {
        val dbFiles = backupDir.listFiles { f -> f.name.endsWith(".db") } ?: return
        val stale = dbFiles.sortedByDescending { it.lastModified() }.drop(maxBackups)
        for (old in stale) {
            val base = old.name.removeSuffix(".db")
            old.delete()
            File(backupDir, "$base.db-wal").delete()
            File(backupDir, "$base.db-shm").delete()
        }
    }

    companion object {
        private const val TAG = "DatabaseBackupManager"
        const val BACKUP_DIR_NAME = "db_backups"
        const val MAX_BACKUPS = 5
    }
}
