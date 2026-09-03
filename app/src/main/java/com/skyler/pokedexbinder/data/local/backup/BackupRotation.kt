package com.skyler.pokedexbinder.data.local.backup

import java.io.File

/**
 * Shared "keep only the newest N" pruning for the directories this app fills with its own copies of
 * the database: `filesDir/db_backups/` ([DatabaseBackupManager]) and `filesDir/exports/`
 * ([BackupExporter]). Both live under `filesDir`, which Android never reclaims under storage
 * pressure, so every writer has to rotate explicitly or grow without bound.
 */
internal object BackupRotation {
    /**
     * Keeps the [keep] newest files in [dir] whose name ends with [suffix] (by `lastModified`) and
     * deletes the rest, together with each deleted file's [siblingSuffixes] companions — files
     * sharing its base name, e.g. a `.db`'s `.db-wal`/`.db-shm` sidecars.
     */
    fun keepNewest(dir: File, suffix: String, keep: Int, siblingSuffixes: List<String> = emptyList()) {
        val matching = dir.listFiles { f -> f.name.endsWith(suffix) } ?: return
        val stale = matching.sortedByDescending { it.lastModified() }.drop(keep)
        for (old in stale) {
            val base = old.name.removeSuffix(suffix)
            old.delete()
            for (sibling in siblingSuffixes) File(dir, base + sibling).delete()
        }
    }
}
