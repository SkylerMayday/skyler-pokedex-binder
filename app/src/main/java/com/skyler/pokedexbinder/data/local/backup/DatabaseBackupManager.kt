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
            backupNow(context, dbFileName, reason = "v${onDiskVersion}to$targetVersion")
        } catch (e: Exception) {
            // Never let this check block app startup or the migration it's insuring against.
            Log.e(TAG, "Pre-migration backup check failed — proceeding without it", e)
        }
    }

    /**
     * Unconditionally copies [dbFileName] + its `-wal`/`-shm` sidecars into
     * `context.filesDir/db_backups/` under a [reason]-tagged name, then rotates old backups down to
     * [maxBackups]. Returns true if a copy was made.
     *
     * Separate from [backupIfDestructiveMigrationImminent]'s gates so the destructive local `.db`
     * import path can take a recovery copy before it overwrites the live database — that swap needs
     * a backup precisely in the cases the migration check decides *not* to take one (a valid,
     * migratable version). Best-effort: any failure is logged and swallowed, never thrown.
     */
    fun backupNow(context: Context, dbFileName: String, reason: String): Boolean {
        return try {
            val dbFile = context.getDatabasePath(dbFileName)
            if (!dbFile.exists()) return false

            val backupDir = File(context.filesDir, BACKUP_DIR_NAME).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // Derived from the file actually being backed up, not a hardcoded name — two different
            // database files must not collide on one base name inside the shared rotation pool.
            val baseName = "${dbFileName.removeSuffix(".db")}_${reason}_$stamp"

            copyIfExists(dbFile, File(backupDir, "$baseName.db"))
            copyIfExists(File(dbFile.path + "-wal"), File(backupDir, "$baseName.db-wal"))
            copyIfExists(File(dbFile.path + "-shm"), File(backupDir, "$baseName.db-shm"))

            BackupRotation.keepNewest(backupDir, ".db", maxBackups, SIDECAR_SUFFIXES)
            true
        } catch (e: Exception) {
            // Never let a backup failure block app startup or the operation it's insuring against.
            Log.e(TAG, "DB backup failed — proceeding without it", e)
            false
        }
    }

    private fun copyIfExists(source: File, dest: File) {
        if (source.exists()) source.copyTo(dest, overwrite = true)
    }

    companion object {
        private const val TAG = "DatabaseBackupManager"
        const val BACKUP_DIR_NAME = "db_backups"
        const val MAX_BACKUPS = 5

        /** Suffixes SQLite keeps beside a `<base>.db` file; they must be rotated/copied with it. */
        val SIDECAR_SUFFIXES = listOf(".db-wal", ".db-shm")
    }
}
