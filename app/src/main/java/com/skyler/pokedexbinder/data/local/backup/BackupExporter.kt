package com.skyler.pokedexbinder.data.local.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.skyler.pokedexbinder.BuildConfig
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.publish.PublishRepository
import com.skyler.pokedexbinder.publish.model.BackupEnvelope
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import com.skyler.pokedexbinder.repository.PublishConfig
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val EXPORT_DIR_NAME = "exports"

/**
 * How many export pairs `filesDir/exports/` keeps. Same budget as [DatabaseBackupManager.MAX_BACKUPS]:
 * filesDir is never reclaimed by Android under storage pressure, so an un-rotated export directory
 * grows by a full database copy on every Backup tap, forever.
 */
private const val MAX_EXPORTS = 5

/**
 * Appended to the application id to form the FileProvider authority. Must stay in sync with
 * `android:authorities="${applicationId}.fileprovider"` in `AndroidManifest.xml` — a mismatch only
 * fails at runtime, inside [FileProvider.getUriForFile].
 */
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

sealed interface BackupExportResult {
    data class Success(val shareIntent: Intent) : BackupExportResult
    data class Failure(val message: String) : BackupExportResult
}

/**
 * Local, GitHub-independent export path: checkpoints and copies the raw `.db` file, builds a
 * version-stamped JSON envelope of every binder (reusing [PublishRepository.buildSnapshot] so this
 * can never drift from what cloud publish reads), and hands back a share-sheet [Intent] carrying
 * both files via [androidx.core.content.FileProvider].
 */
@Singleton
class BackupExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: PokedexDatabase,
    private val moshi: Moshi,
    private val publishRepository: PublishRepository,
    private val binderRepository: BinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao,
    private val connectingArtRepository: ConnectingArtRepository,
    private val personalCollectionRepository: PersonalCollectionRepository,
    private val unownBinderRepository: UnownBinderRepository
) {
    // withContext(IO), matching BackupImporter: a full-database copy, a WAL checkpoint and a JSON
    // write are all blocking file work, and the only caller launches this on viewModelScope (Main).
    suspend fun export(): BackupExportResult = withContext(Dispatchers.IO) {
        try {
            // Flush the WAL so the copied .db file reflects every committed write, not a stale
            // snapshot. PRAGMA wal_checkpoint returns a result row (busy/log/checkpointed counts),
            // so it must go through query(), not execSQL() — execSQL() rejects any SQL that returns
            // data ("Queries can be performed using SQLiteDatabase query or rawQuery methods only").
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

            val exportDir = File(context.filesDir, EXPORT_DIR_NAME).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            val dbDest = File(exportDir, "pokedex-binder-$stamp.db")
            context.getDatabasePath(PokedexDatabase.DB_FILE_NAME).copyTo(dbDest, overwrite = true)

            // A local backup is a full safety copy, not a mirror of the cloud-publish toggles —
            // always include every binder regardless of Settings' publish toggles.
            val snapshot = publishRepository.buildSnapshot(
                entries = binderRepository.getAllEntries(),
                secondaryEntries = secondaryBinderDao.getAll(),
                config = PublishConfig(publishPokedex = true, publishCardHistory = true),
                connectingArtGroups = connectingArtRepository.getAllGroups(),
                connectingArtSlots = connectingArtRepository.getAllSlots(),
                personalCache = personalCollectionRepository.getAllCache(),
                personalEntries = personalCollectionRepository.getAllEntries(),
                unownEntries = unownBinderRepository.getAllEntries()
            )

            val envelope = BackupEnvelope(
                appVersionName = BuildConfig.VERSION_NAME,
                roomSchemaVersion = PokedexDatabase.SCHEMA_VERSION,
                exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                snapshot = snapshot
            )
            val jsonDest = File(exportDir, "pokedex-binder-backup-$stamp.json")
            jsonDest.writeText(moshi.adapter(BackupEnvelope::class.java).toJson(envelope))

            // The two files of one export don't share a base name, so each extension is rotated on
            // its own; both were just written, so both survive as the newest of their kind.
            BackupRotation.keepNewest(exportDir, ".db", MAX_EXPORTS)
            BackupRotation.keepNewest(exportDir, ".json", MAX_EXPORTS)

            val authority = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX
            val uris = arrayListOf(
                FileProvider.getUriForFile(context, authority, jsonDest),
                FileProvider.getUriForFile(context, authority, dbDest)
            )

            val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            BackupExportResult.Success(Intent.createChooser(sendIntent, "Backup Pokédex Binder"))
        } catch (e: Exception) {
            BackupExportResult.Failure(e.message ?: "Unknown error")
        }
    }
}
