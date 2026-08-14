package ch.jeanrichard.nfcspoolwriter.ui.spoollist

import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolPage
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import ch.jeanrichard.nfcspoolwriter.testsupport.InMemoryPreferencesDataStore
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.fakeSpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.testsupport.testSpool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SpoolListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val spools = listOf(
        testSpool(id = 1, name = "PolyTerra PLA Blue", material = "PLA", vendorName = "Polymaker", location = "Shelf A"),
        testSpool(id = 2, name = "eSUN PETG Black", material = "PETG", vendorName = "eSUN", location = "Drawer"),
        testSpool(id = 3, name = "Creality Hyper PLA", material = "PLA", vendorName = "Creality", location = "Shelf A"),
    )

    /** Defaults to an empty settings store, which is the unconfigured first-run case. */
    private fun spoolList(
        repository: SpoolmanRepository,
        settingsRepository: SettingsRepository = SettingsRepository(InMemoryPreferencesDataStore()),
    ) = SpoolListViewModel(repository, settingsRepository)

    @Test
    fun `loads spools on creation`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals(listOf(1, 2, 3), state.visibleSpools.map { it.id })
    }

    @Test
    fun `an unconfigured server is a call to action not an error`() = runTest {
        val vm = spoolList(
            fakeSpoolmanRepository(listError = SpoolmanError.NotConfigured)
        )

        val state = vm.state.value
        assertTrue(state.notConfigured)
        assertNull("must not also show a red error", state.error)
    }

    @Test
    fun `a server failure surfaces its user message`() = runTest {
        val error = SpoolmanError.Unreachable("http://h", null)
        val vm = spoolList(fakeSpoolmanRepository(listError = error))

        val state = vm.state.value
        assertEquals(error.userMessage, state.error)
        assertEquals(false, state.notConfigured)
    }

    @Test
    fun `retry reloads`() = runTest {
        val repo = fakeSpoolmanRepository(spools)
        val vm = spoolList(repo)

        vm.load()

        assertEquals(listOf(1, 2, 3), vm.state.value.visibleSpools.map { it.id })
    }

    // --- Reacting to the configured server ---------------------------------------------------

    /**
     * The first run: the list is the start destination, so it — and this ViewModel — survive the trip
     * to settings and back. Without reacting to the saved address it would sit on its "configure a
     * server" call to action until the user thought to pull to refresh.
     */
    @Test
    fun `saving a server address loads the list`() = runTest {
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        val repo = mockk<SpoolmanRepository>(relaxed = true)
        coEvery { repo.loadAllSpools() } returns
            SpoolmanResult.Failure(SpoolmanError.NotConfigured) andThen
            SpoolmanResult.Success(SpoolPage(spools, spools.size))
        val vm = spoolList(repo, settings)
        assertTrue(vm.state.value.notConfigured)

        settings.setSpoolmanBaseUrl("http://spoolman.local:7912")

        assertEquals(false, vm.state.value.notConfigured)
        assertEquals(listOf(1, 2, 3), vm.state.value.visibleSpools.map { it.id })
    }

    /** Pointing the app at a different Spoolman must not leave the previous server's spools up. */
    @Test
    fun `changing the server address reloads the list`() = runTest {
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setSpoolmanBaseUrl("http://old.local")
        val repo = fakeSpoolmanRepository(spools)
        spoolList(repo, settings)

        settings.setSpoolmanBaseUrl("http://new.local")

        coVerify(exactly = 2) { repo.loadAllSpools() }
    }

    /** Re-saving the address the app is already using is not a reason to re-fetch. */
    @Test
    fun `saving the same address again does not reload`() = runTest {
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setSpoolmanBaseUrl("http://spoolman.local")
        val repo = fakeSpoolmanRepository(spools)
        spoolList(repo, settings)

        settings.setSpoolmanBaseUrl("http://spoolman.local/")

        coVerify(exactly = 1) { repo.loadAllSpools() }
    }

    // --- Pull to refresh -------------------------------------------------------------------

    /** The whole point of the separate flag: a pull must not blank the list back to a spinner. */
    @Test
    fun `a refresh keeps the list on screen instead of a full-screen spinner`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = spoolList(gatedRepository(gate))

        vm.refresh()

        val duringRefresh = vm.state.value
        assertTrue("shows the pull indicator", duringRefresh.refreshing)
        assertEquals("must not show the full-screen spinner", false, duringRefresh.loading)
        assertEquals(listOf(1, 2, 3), duringRefresh.visibleSpools.map { it.id })

        gate.complete(Unit)

        assertEquals(false, vm.state.value.refreshing)
        assertEquals(listOf(1, 2, 3), vm.state.value.visibleSpools.map { it.id })
    }

    /** A first load has nothing to keep on screen, so it does get the full-screen spinner. */
    @Test
    fun `a first load shows the full-screen spinner`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = spoolList(gatedRepository(gate, gateFirstCall = true))

        val duringLoad = vm.state.value
        assertTrue(duringLoad.loading)
        assertEquals(false, duringLoad.refreshing)

        gate.complete(Unit)

        assertEquals(false, vm.state.value.loading)
    }

    @Test
    fun `a refresh keeps the search query`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))
        vm.onQueryChange("petg")

        vm.refresh()

        assertEquals("petg", vm.state.value.query)
        assertEquals(listOf(2), vm.state.value.visibleSpools.map { it.id })
    }

    /** A momentary network blip during a refresh shouldn't cost the user the list they had. */
    @Test
    fun `a failed refresh reports the error but keeps the list`() = runTest {
        val error = SpoolmanError.Unreachable("http://h", null)
        val repo = mockk<SpoolmanRepository>(relaxed = true)
        coEvery { repo.loadAllSpools() } returns
            SpoolmanResult.Success(SpoolPage(spools, spools.size)) andThen
            SpoolmanResult.Failure(error)
        val vm = spoolList(repo)

        vm.refresh()

        val state = vm.state.value
        assertEquals(error.userMessage, state.error)
        assertEquals(listOf(1, 2, 3), state.visibleSpools.map { it.id })
        assertEquals(false, state.refreshing)
    }

    /** A failed first load has no list to keep — the full-screen error with a retry is all there is. */
    @Test
    fun `a failed first load leaves no list`() = runTest {
        val vm = spoolList(
            fakeSpoolmanRepository(listError = SpoolmanError.Unreachable("http://h", null))
        )

        assertTrue(vm.state.value.spools.isEmpty())
    }

    /**
     * A repository whose load suspends until [gate] completes, so a test can inspect the state a
     * user would see *during* a load rather than only after it.
     */
    private fun gatedRepository(
        gate: CompletableDeferred<Unit>,
        gateFirstCall: Boolean = false,
    ): SpoolmanRepository {
        var calls = 0
        val repo = mockk<SpoolmanRepository>(relaxed = true)
        coEvery { repo.loadAllSpools() } coAnswers {
            if (calls++ > 0 || gateFirstCall) gate.await()
            SpoolmanResult.Success(SpoolPage(spools, spools.size))
        }
        return repo
    }

    // --- Local search ----------------------------------------------------------------------

    @Test
    fun `search matches material`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        vm.onQueryChange("petg")

        assertEquals(listOf(2), vm.state.value.visibleSpools.map { it.id })
    }

    @Test
    fun `search matches vendor`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        vm.onQueryChange("esun")

        assertEquals(listOf(2), vm.state.value.visibleSpools.map { it.id })
    }

    @Test
    fun `search matches location`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        vm.onQueryChange("shelf")

        assertEquals(listOf(1, 3), vm.state.value.visibleSpools.map { it.id })
    }

    @Test
    fun `search matches spool id`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        vm.onQueryChange("3")

        assertEquals(listOf(3), vm.state.value.visibleSpools.map { it.id })
    }

    /** Multiple terms narrow rather than widen, so "polymaker pla" isn't a union. */
    @Test
    fun `multiple search terms must all match`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        vm.onQueryChange("pla polymaker")

        assertEquals(listOf(1), vm.state.value.visibleSpools.map { it.id })
    }

    @Test
    fun `search is case insensitive`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        vm.onQueryChange("POLYTERRA")

        assertEquals(listOf(1), vm.state.value.visibleSpools.map { it.id })
    }

    @Test
    fun `a blank query shows everything`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools))

        vm.onQueryChange("   ")

        assertEquals(3, vm.state.value.visibleSpools.size)
    }

    /** "Nothing matches your search" and "you have no spools" need different messages. */
    @Test
    fun `distinguishes no matches from no spools`() = runTest {
        val withSpools = spoolList(fakeSpoolmanRepository(spools))
        withSpools.onQueryChange("nothing-matches-this")

        assertTrue(withSpools.state.value.isEmptyResult)
        assertEquals(false, withSpools.state.value.hasNoSpoolsAtAll)

        val empty = spoolList(fakeSpoolmanRepository(emptyList()))

        assertTrue(empty.state.value.isEmptyResult)
        assertTrue(empty.state.value.hasNoSpoolsAtAll)
    }

    @Test
    fun `reports a truncated list`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools, totalCount = 500))

        assertTrue(vm.state.value.truncated)
        assertEquals(500, vm.state.value.totalCount)
    }

    @Test
    fun `a complete list is not reported as truncated`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools, totalCount = 3))

        assertEquals(false, vm.state.value.truncated)
    }

    /** With no server total there is nothing to compare against, so truncation must not be claimed. */
    @Test
    fun `an unknown total is shown as the number of spools loaded`() = runTest {
        val vm = spoolList(fakeSpoolmanRepository(spools, totalCount = null))

        assertEquals(false, vm.state.value.truncated)
        assertEquals(spools.size, vm.state.value.totalCount)
    }
}
