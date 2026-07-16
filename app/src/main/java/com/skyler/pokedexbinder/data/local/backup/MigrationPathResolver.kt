package com.skyler.pokedexbinder.data.local.backup

import androidx.room.migration.Migration

/**
 * Answers "does an unbroken chain of the given [Migration]s connect [from] to [to]?" via BFS
 * over each Migration's declared (startVersion, endVersion) edge — not a hardcoded assumption
 * that every step is exactly +1, since Room itself allows multi-version jump migrations.
 * Direction-agnostic: works for both upgrade gaps and downgrade gaps (today no downgrade
 * Migration is declared in this codebase, so any on-disk version > target always resolves to
 * "no path" — see PokedexDatabase Gotchas on why downgrades are never given a migration path).
 */
object MigrationPathResolver {
    fun hasPath(migrations: Array<Migration>, from: Int, to: Int): Boolean {
        if (from == to) return true
        val edges = migrations.groupBy { it.startVersion }
        val visited = mutableSetOf(from)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (m in edges[current].orEmpty()) {
                if (m.endVersion == to) return true
                if (visited.add(m.endVersion)) queue.add(m.endVersion)
            }
        }
        return false
    }
}
