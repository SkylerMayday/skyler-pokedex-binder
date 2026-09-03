package com.skyler.pokedexbinder.publish.model

import com.squareup.moshi.JsonClass

/**
 * Wraps a [BinderSnapshot] for the local JSON backup/export/import path (`data/local/backup/
 * BackupExporter`/`BackupImporter`) — distinct from the cloud publish/restore path, which reads
 * [BinderSnapshot] directly off `binder.json` with no wrapping envelope.
 *
 * [roomSchemaVersion] is compared against [com.skyler.pokedexbinder.data.local.PokedexDatabase.SCHEMA_VERSION]
 * on import: an envelope from a newer app version than the one importing it is rejected outright,
 * since [snapshot]'s shape can't be assumed forward-compatible.
 */
@JsonClass(generateAdapter = true)
data class BackupEnvelope(
    val appVersionName: String,
    val roomSchemaVersion: Int,
    val exportedAt: String,      // ISO-8601 with offset, same format as BinderSnapshot.publishedAt
    val snapshot: BinderSnapshot
)
