package com.skyler.pokedexbinder.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * English set names, which TCGdex's brief card DTO lacks (it only carries the set code).
 * Best-effort and memoised; a failure returns an empty map and is remembered for 15 min so `/sets`
 * is not re-hit on every call.
 */
@Singleton
class TcgdexSetCatalog @Inject constructor(private val api: TcgdexApi) {

    private val namesLock = Mutex()
    @Volatile private var names: Map<String, String>? = null
    private var retryAt = 0L // guarded by namesLock
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** English set names keyed by lowercase set id; empty when unreachable (retried after the cooldown). */
    suspend fun englishNames(): Map<String, String> {
        names?.let { return it }
        return namesLock.withLock {
            names ?: run {
                if (nowMs() < retryAt) return@withLock emptyMap()
                try {
                    api.getSets("en").associate { it.id.lowercase() to it.name }.also { names = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryAt = nowMs() + SETS_RETRY_MS
                    emptyMap()
                }
            }
        }
    }

    private companion object {
        const val SETS_RETRY_MS = 15L * 60 * 1000
    }
}
