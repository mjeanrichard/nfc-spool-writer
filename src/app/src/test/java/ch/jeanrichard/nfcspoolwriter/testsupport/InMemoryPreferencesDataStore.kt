package ch.jeanrichard.nfcspoolwriter.testsupport

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [DataStore] for tests.
 *
 * The real file-backed `PreferenceDataStoreFactory` is unusable in JVM tests on Windows: it commits
 * writes by renaming a `.tmp` file over the target, which Windows rejects when the target already
 * exists, so any test doing a second write fails. Using a fake also keeps tests focused on the code
 * under test rather than on DataStore's persistence.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val writeLock = Mutex()

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences
    ): Preferences = writeLock.withLock {
        transform(state.value).also { state.value = it }
    }
}
