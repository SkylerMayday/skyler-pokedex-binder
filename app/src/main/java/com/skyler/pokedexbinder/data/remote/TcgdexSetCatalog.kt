package com.skyler.pokedexbinder.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * English set names, which TCGdex's brief card DTO lacks (it only carries the set code).
 * Best-effort and memoised; a failure returns an empty map and is never cached, so a later call retries.
 */
@Singleton
class TcgdexSetCatalog @Inject constructor(private val api: TcgdexApi) {

    private val namesLock = Mutex()
    @Volatile private var names: Map<String, String>? = null

    /** English set names keyed by lowercase set id; empty (and retried next call) when unreachable. */
    suspend fun englishNames(): Map<String, String> {
        names?.let { return it }
        return namesLock.withLock {
            names ?: try {
                api.getSets("en").associate { it.id.lowercase() to it.name }.also { names = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}
