package com.skyler.pokedexbinder.data.local.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.skyler.pokedexbinder.MainActivity
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import com.skyler.pokedexbinder.publish.RestoreRepository
import com.skyler.pokedexbinder.publish.RestoreResult
import com.skyler.pokedexbinder.publish.RestoreStep
import com.skyler.pokedexbinder.publish.model.BackupEnvelope
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

private enum class BackupFileType { JSON, DB, UNRECOGNIZED }

/** Suffix of the staging file the `.db` import writes beside the live database before swapping. */
private const val IMPORTING_SUFFIX = ".importing"

/**
 * Tables every Pokédex Binder database has carried since schema v4 (the oldest version with a
 * declared migration edge). Used to reject a picked `.db` that is *some* SQLite file rather than
 * this app's — `PRAGMA user_version` can't tell them apart, since unrelated databases report 0.
 */
private val REQUIRED_TABLES = setOf("main_binder", "secondary_binder")

/**
 * Survives the forced restart [BackupImporter.applyDb] triggers on a failed `.db` swap.
 *
 * That restart is unconditional once [PokedexDatabase] has been closed — Room's own contract is
 * that `RoomDatabase.Builder.build()` is the only supported way to get a usable instance back, so
 * [BackupImporter] cannot just hand a closed singleton back to the caller and hope the UI shows an
 * error gracefully; it has to restart, same as the success path. But a restart also means the
 * failure can no longer reach the user through [ImportViewModel]'s `StateFlow` — the process that
 * would emit it is gone. Persisting the message to disk and reading it back once, at the next cold
 * start (`MainActivity`), is the only way it survives that restart.
 */
object PendingImportError {
    private const val PREFS_NAME = "backup_import_state"
    private const val KEY_MESSAGE = "pending_import_error"

    @VisibleForTesting
    internal fun persist(context: Context, message: String) {
        // commit = true: synchronous, since the process is about to be killed and the async
        // apply() would race it.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putString(KEY_MESSAGE, message)
        }
    }

    /** Reads and clears any message left by [persist]. Call once, at cold start. */
    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_MESSAGE, null) ?: return null
        prefs.edit { remove(KEY_MESSAGE) }
        return message
    }
}

sealed interface ImportPreview {
    data class Json(val envelope: BackupEnvelope) : ImportPreview
    data class Db(val tempFile: File, val onDiskVersion: Int) : ImportPreview
}

sealed interface ImportPrepareResult {
    data class Ready(val preview: ImportPreview) : ImportPrepareResult
    data class Rejected(val message: String) : ImportPrepareResult
}

/**
 * Local, GitHub-independent import path. [prepare] only reads/parses the picked file(s) and
 * version-gates them against [PokedexDatabase.SCHEMA_VERSION] — it never touches any local app
 * data. [applyJson]/[applyDb] perform the actual replace and must only run after the caller's own
 * confirmation step (enforced by the UI, not here).
 */
@Singleton
class BackupImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi,
    private val restoreRepository: RestoreRepository,
    private val database: PokedexDatabase,
    private val versionReader: SqliteVersionReader,
    private val databaseBackupManager: DatabaseBackupManager
) {

    /** Reads/parses the picked [uris] and version-gates them. Touches no local app data. */
    suspend fun prepare(uris: List<Uri>): ImportPrepareResult = withContext(Dispatchers.IO) {
        // displayName() runs a ContentResolver query, so the whole routing step belongs on IO too,
        // not just the two branches it dispatches to.
        val byType = uris.groupBy { fileType(displayName(it)) }
        // If both a .json and a .db are picked together (both live in the same export folder),
        // prefer the JSON — the non-destructive path (no live-DB swap, no process restart) that
        // carries identical data, rather than asking Skyler to disambiguate for every import.
        val jsonUri = byType[BackupFileType.JSON]?.firstOrNull()
        val dbUri = byType[BackupFileType.DB]?.firstOrNull()
        when {
            jsonUri != null -> prepareJson(jsonUri)
            dbUri != null -> prepareDb(dbUri)
            else -> ImportPrepareResult.Rejected("No .json or .db backup file recognized in the selection")
        }
    }

    private fun prepareJson(uri: Uri): ImportPrepareResult {
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull() ?: return ImportPrepareResult.Rejected("Could not read the selected JSON file")

        val envelope = runCatching { moshi.adapter(BackupEnvelope::class.java).fromJson(json) }.getOrNull()
            ?: return ImportPrepareResult.Rejected("Selected file is not a valid backup JSON")

        if (envelope.roomSchemaVersion > PokedexDatabase.SCHEMA_VERSION) {
            return ImportPrepareResult.Rejected(
                "This backup needs app schema v${envelope.roomSchemaVersion}, but this install is " +
                    "only on v${PokedexDatabase.SCHEMA_VERSION} — update the app before importing it."
            )
        }
        return ImportPrepareResult.Ready(ImportPreview.Json(envelope))
    }

    private fun prepareDb(uri: Uri): ImportPrepareResult {
        // SqliteVersionReader needs a real java.io.File — a SAF content:// Uri (Drive, Telegram,
        // etc.) doesn't reliably expose one, so copy to a cacheDir temp file first.
        val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}.db")
        val result = runCatching { inspect(uri, tempFile) }
            .getOrElse { ImportPrepareResult.Rejected("Could not read the selected .db file") }
        // This function owns the temp file until a Ready preview hands it to the caller: every
        // other exit — rejected, unreadable, or an unexpected throw — must leave nothing behind,
        // including the `-wal`/`-shm` sidecars that reading the version created next to it.
        if (result !is ImportPrepareResult.Ready) deleteDbAndSidecars(tempFile)
        return result
    }

    /** Copies [uri] into [tempFile] and runs every gate a `.db` must pass before it can be applied. */
    private fun inspect(uri: Uri, tempFile: File): ImportPrepareResult {
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        if (copied == null) return ImportPrepareResult.Rejected("Could not read the selected .db file")

        val onDiskVersion = versionReader.readVersion(tempFile)
            ?: return ImportPrepareResult.Rejected("Selected file is not a valid SQLite database")

        val tables = versionReader.readTableNames(tempFile).orEmpty()
        if (!tables.containsAll(REQUIRED_TABLES)) {
            return ImportPrepareResult.Rejected(
                "This doesn't look like a Pokédex Binder backup — it's a SQLite file, but it has " +
                    "none of this app's binder tables."
            )
        }

        if (onDiskVersion > PokedexDatabase.SCHEMA_VERSION) {
            return ImportPrepareResult.Rejected(
                "This backup's database is schema v$onDiskVersion, but this install is only on " +
                    "v${PokedexDatabase.SCHEMA_VERSION} — update the app before importing it."
            )
        }
        // Lower bound: Room's builder ends in fallbackToDestructiveMigration(), so an older version
        // with no migration path would be accepted here, overwrite the live database, and then be
        // dropped table-by-table on the next cold start — losing both the old and the imported data.
        val migratable = onDiskVersion == PokedexDatabase.SCHEMA_VERSION ||
            MigrationPathResolver.hasPath(
                PokedexDatabase.ALL_MIGRATIONS, onDiskVersion, PokedexDatabase.SCHEMA_VERSION
            )
        if (!migratable) {
            return ImportPrepareResult.Rejected(
                "This backup's database is schema v$onDiskVersion, which this app can no longer " +
                    "upgrade to v${PokedexDatabase.SCHEMA_VERSION} — importing it would wipe every table."
            )
        }
        return ImportPrepareResult.Ready(ImportPreview.Db(tempFile, onDiskVersion))
    }

    /** Routes [preview]'s embedded snapshot through the exact same apply logic cloud restore uses. */
    suspend fun applyJson(preview: ImportPreview.Json, onStep: (RestoreStep) -> Unit): RestoreResult =
        restoreRepository.restoreFromSnapshot(preview.envelope.snapshot, onStep)

    /**
     * Deletes any temp resources tied to a not-yet-applied [preview] — call when the user cancels
     * the confirmation dialog instead of confirming. [ImportPreview.Json] has no temp file to
     * clean; [ImportPreview.Db]'s [ImportPreview.Db.tempFile] (already version-checked and sitting
     * in [Context.getCacheDir]) would otherwise leak permanently, since nothing else ever revisits
     * it once the confirmation dialog is dismissed without confirming.
     */
    suspend fun discardPreview(preview: ImportPreview): Unit = withContext(Dispatchers.IO) {
        if (preview is ImportPreview.Db) {
            deleteDbAndSidecars(preview.tempFile)
        }
    }

    /**
     * Replaces the live `.db` file with [preview]'s already-version-checked temp file and
     * force-restarts the process so Room reopens cold with a fresh Hilt graph — no in-process
     * hot-swap attempted. Does not return normally on a real device, **on success or failure**:
     * [database] is closed unconditionally below, before the swap is attempted, and Room's own
     * contract (a manually-closed instance has no reopen path — only a fresh
     * `RoomDatabase.Builder.build()` after a restart) means there is no safe way to hand control
     * back to the caller once that close has run. A failed swap still restarts; it leaves its
     * message in [PendingImportError] for `MainActivity` to show after the process comes back,
     * instead of the graceful in-app dialog a closed live database can no longer support.
     */
    suspend fun applyDb(preview: ImportPreview.Db): Unit = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(PokedexDatabase.DB_FILE_NAME)
        database.close()

        // From here on `database` can never be reused in this process. Every exit below — success
        // or failure — must therefore restart: leaving this closed instance in place while control
        // returns to the caller is exactly the bug this structure exists to prevent.
        try {
            // Recovery copy of the CURRENT database before anything overwrites it. This is the
            // only irreversible operation in the feature, and the pre-migration check won't cover
            // it — that one deliberately returns early exactly when the import gate says the file
            // is migratable.
            databaseBackupManager.backupNow(context, PokedexDatabase.DB_FILE_NAME, reason = "preimport")
            try {
                swapInDatabaseFile(source = preview.tempFile, target = dbFile)
            } finally {
                deleteDbAndSidecars(preview.tempFile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PendingImportError.persist(
                context,
                e.message ?: "Could not replace the database file — your data is unchanged"
            )
            restartProcess()
            return@withContext
        }

        restartProcess()
    }

    /** Force-restarts the process so Room's next `PokedexDatabase` is a fresh, cold-opened one. */
    private fun restartProcess() {
        val restartIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(restartIntent)
        Process.killProcess(Process.myPid())
    }

    /**
     * Puts [source]'s bytes at [target] without ever leaving [target] in a half-written state.
     *
     * `copyTo(overwrite = true)` unlinks the destination *before* it starts writing, so a failure
     * mid-stream (ENOSPC, a dying SAF stream) would leave a truncated file where the binder used to
     * be. Staging beside the target and moving avoids that: an atomic move within one directory is
     * a `rename(2)`, so the file is either the old database or the new one, never half of each. On
     * any failure the staging file is removed and [target] is left exactly as it was.
     *
     * [Files.move] rather than [File.renameTo]: `renameTo` reports failure as a bare `false` with
     * no reason, and whether it replaces an existing target is explicitly platform-dependent.
     *
     * Extracted from [applyDb] so this — the only irreversible step in the feature — is testable
     * without the `Intent`/`Process` machinery that surrounds it.
     */
    @VisibleForTesting
    internal fun swapInDatabaseFile(source: File, target: File) {
        val staging = File(target.path + IMPORTING_SUFFIX)
        try {
            source.copyTo(staging, overwrite = true)
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            staging.delete()
            throw IOException(
                "Could not replace the database file — your data is unchanged (${e.message})", e
            )
        }
        // A stale WAL/SHM sidecar belonging to the OLD database, replayed against the file that
        // just replaced it, would corrupt it. Safe to drop only now that the swap has succeeded.
        deleteSidecars(target)
    }

    private fun deleteDbAndSidecars(dbFile: File) {
        dbFile.delete()
        deleteSidecars(dbFile)
    }

    private fun deleteSidecars(dbFile: File) {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun fileType(fileName: String?): BackupFileType = when {
        fileName == null -> BackupFileType.UNRECOGNIZED
        fileName.endsWith(".json", ignoreCase = true) -> BackupFileType.JSON
        fileName.endsWith(".db", ignoreCase = true) -> BackupFileType.DB
        else -> BackupFileType.UNRECOGNIZED
    }
}
