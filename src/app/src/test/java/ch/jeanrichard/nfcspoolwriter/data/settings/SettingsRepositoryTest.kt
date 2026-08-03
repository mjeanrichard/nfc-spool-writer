package ch.jeanrichard.nfcspoolwriter.data.settings

import androidx.datastore.core.DataStore
import ch.jeanrichard.nfcspoolwriter.testsupport.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Backed by an [InMemoryPreferencesDataStore] rather than the real file-backed one: what's under test
 * is this repository's URL normalization and clearing behavior, not DataStore's persistence.
 */
class SettingsRepositoryTest {

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        repository = SettingsRepository(InMemoryPreferencesDataStore())
    }

    @Test
    fun `base url is null before anything is stored`() = runTest {
        assertNull(repository.spoolmanBaseUrl.first())
    }

    @Test
    fun `stored base url is read back`() = runTest {
        repository.setSpoolmanBaseUrl("http://spoolman.local:7912")

        assertEquals("http://spoolman.local:7912", repository.spoolmanBaseUrl.first())
    }

    @Test
    fun `base url is trimmed and stripped of trailing slashes`() = runTest {
        repository.setSpoolmanBaseUrl("  http://spoolman.local:7912///  ")

        assertEquals("http://spoolman.local:7912", repository.spoolmanBaseUrl.first())
    }

    @Test
    fun `blank base url clears the setting`() = runTest {
        repository.setSpoolmanBaseUrl("http://spoolman.local:7912")

        repository.setSpoolmanBaseUrl("   ")

        assertNull(repository.spoolmanBaseUrl.first())
    }

    @Test
    fun `base url of only slashes clears the setting`() = runTest {
        repository.setSpoolmanBaseUrl("http://spoolman.local:7912")

        repository.setSpoolmanBaseUrl("//")

        assertNull(repository.spoolmanBaseUrl.first())
    }

    @Test
    fun `base url is overwritten by a later write`() = runTest {
        repository.setSpoolmanBaseUrl("http://old.local:7912")

        repository.setSpoolmanBaseUrl("http://new.local:7912")

        assertEquals("http://new.local:7912", repository.spoolmanBaseUrl.first())
    }
}
