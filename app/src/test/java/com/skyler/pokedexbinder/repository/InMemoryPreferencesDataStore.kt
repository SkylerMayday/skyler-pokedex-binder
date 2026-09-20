package com.skyler.pokedexbinder.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real, in-memory [DataStore]<[Preferences]> for JVM tests — not a mock, a correct implementation
 * of the same 2-member interface the real file-backed one satisfies. [updateData] is serialized via
 * a [Mutex], matching DataStore's own atomic-sequential-update contract, so `edit {}`'s generic
 * extension (which calls `updateData` under the hood) works transparently against this.
 *
 * Exists specifically to avoid a well-documented Windows-only bug in the real
 * `PreferenceDataStoreFactory`-created, file-backed DataStore: calling `.edit()` more than once
 * against the same temp-file-backed instance in a JVM unit test throws
 * `IOException: Unable to rename ... This likely means that there are multiple instances of
 * DataStore for this file` — a real file-rename/locking quirk on Windows, not a logic bug in this
 * app's code (confirmed live this session: 3/6 new tests hit it consistently; the 3 that only ever
 * call `.edit()` 0-1 times never did). Google's own `nowinandroid` reference app hit the identical
 * issue and fixed it the same way — an in-memory DataStore for tests, not a workaround like a retry
 * or delay.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
}
