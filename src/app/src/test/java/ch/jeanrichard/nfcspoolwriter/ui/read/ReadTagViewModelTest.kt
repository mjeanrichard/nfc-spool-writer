package ch.jeanrichard.nfcspoolwriter.ui.read

import android.nfc.Tag
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.FakeMifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.KeyDerivation
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareLayout
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.TagCodec
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.fakeSpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.testsupport.hexToBytes
import ch.jeanrichard.nfcspoolwriter.testsupport.realMaterialCatalog
import ch.jeanrichard.nfcspoolwriter.testsupport.testSpool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadTagViewModelTest {

    /** Shared with `MifareTagReaderWriter` so its `withContext` work is awaited by `runTest`. */
    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    /**
     * `android.nfc.Tag` is final and cannot be constructed in a unit test, so the ViewModel takes an
     * `openSession` function. The tag object itself is only ever passed through to it, never read.
     */
    private val tag: Tag = mockk(relaxed = true)

    private val uid = hexToBytes("11223344")

    private fun viewModel(
        session: MifareSession?,
        spools: List<Spool> = listOf(testSpool()),
        getError: SpoolmanError? = null,
        compatibility: DeviceCompatibility = DeviceCompatibility.Compatible,
        ioDispatcher: CoroutineDispatcher = testDispatcher,
        onOpenSession: () -> Unit = {},
        now: () -> Long = { 0L },
    ) = ReadTagViewModel(
        tagReaderWriter = MifareTagReaderWriter(
            ioDispatcher = ioDispatcher,
            retryDelayMillis = 0,
        ),
        materialCatalog = realMaterialCatalog(),
        spoolmanRepository = fakeSpoolmanRepository(spools, getError = getError),
        compatibility = compatibility,
        openSession = {
            onOpenSession()
            session
        },
        now = now,
    )

    /** Distinct tags need distinct ids; a relaxed mock reports the same empty one for every tag. */
    private fun tagWithId(hex: String): Tag = mockk(relaxed = true) {
        every { id } returns hexToBytes(hex)
    }

    private fun fields(
        materialId: String = "00001",
        colorRgb: String = "0000ff",
        weight: WeightBucket = WeightBucket.G1000,
        spoolId: Int = 42,
    ) = MappedFields(
        filamentCatalogId = materialId,
        colorRgb = colorRgb,
        weight = weight,
        spoolmanSpoolId = spoolId,
    )

    private fun writtenSession(fields: MappedFields = fields()) =
        FakeMifareSession.written(TagCodec.encode(fields), uid)

    // --- Verdicts ----------------------------------------------------------------------------

    @Test
    fun `a factory-fresh tag reads as blank`() = runTest {
        val vm = viewModel(FakeMifareSession.blank(uid))

        vm.onTagDiscovered(tag)

        assertEquals(ReadOutcome.Blank, vm.state.value.outcome)
        // A blank tag carries no spool ID, so there is nothing to look up.
        assertNull(vm.state.value.lookup)
    }

    @Test
    fun `a written tag is decoded into the confirm screen's terms`() = runTest {
        val vm = viewModel(writtenSession())

        vm.onTagDiscovered(tag)

        val summary = (vm.state.value.outcome as ReadOutcome.Written).tag
        assertEquals("00001", summary.materialId)
        assertTrue("expected a PLA name, was ${summary.materialName}",
            summary.materialName!!.contains("PLA"))
        assertEquals("0000FF", summary.colorRgb)
        assertEquals(1000, summary.weightGrams)
        assertEquals(42, summary.spoolId)
        assertEquals(MappedFields.DEFAULT_BATCH_NUMBER, summary.batchNumber)
        assertEquals(MappedFields.DEFAULT_DATE_CODE, summary.dateCode)
        assertEquals(MappedFields.CREALITY_SUPPLIER_ID, summary.supplierId)
    }

    /**
     * REQUIREMENTS.md §6: this path must never authenticate-and-write, and must never install a key
     * on a tag it inspects. A tag handed to this screen comes away byte-identical.
     */
    @Test
    fun `reading writes nothing to the tag`() = runTest {
        val blank = FakeMifareSession.blank(uid)
        val written = writtenSession()

        viewModel(blank).onTagDiscovered(tag)
        viewModel(written).onTagDiscovered(tag)

        assertEquals(emptyList<Pair<Int, ByteArray>>(), blank.writeLog)
        assertEquals(emptyList<Pair<Int, ByteArray>>(), written.writeLog)
        // Specifically: no key was installed, so a blank tag is still blank afterwards.
        assertTrue(
            blank.keyForSector(MifareLayout.PRIMARY_SECTOR)
                .contentEquals(KeyDerivation.DEFAULT_KEY)
        )
    }

    /** A tag may name a catalog ID this app does not ship; the rest of the tag still reads. */
    @Test
    fun `an unknown material id falls back to the bare id`() = runTest {
        val vm = viewModel(writtenSession(fields(materialId = "99999")))

        vm.onTagDiscovered(tag)

        val summary = (vm.state.value.outcome as ReadOutcome.Written).tag
        assertNull(summary.materialName)
        assertEquals("99999", summary.materialId)
    }

    @Test
    fun `a tag keyed by this format whose payload does not decode reads as corrupt`() = runTest {
        // Keyed with the derived key, but the content is not something TagCodec produced.
        val vm = viewModel(FakeMifareSession.written("X".repeat(TagCodec.PAYLOAD_LENGTH), uid))

        vm.onTagDiscovered(tag)

        assertEquals(ReadOutcome.Corrupt, vm.state.value.outcome)
        assertNull(vm.state.value.lookup)
    }

    // --- Failures ----------------------------------------------------------------------------

    @Test
    fun `a seven-byte uid is reported as incompatible and not retryable`() = runTest {
        val vm = viewModel(FakeMifareSession.blank(hexToBytes("11223344556677")))

        vm.onTagDiscovered(tag)

        val outcome = vm.state.value.outcome as ReadOutcome.Failed
        assertTrue(outcome.text.contains("4-byte"))
        assertEquals(false, outcome.retryable)
    }

    @Test
    fun `a tag on unrelated keys is reported as an unknown key scheme`() = runTest {
        val foreign = FakeMifareSession(
            uid = uid,
            sectorKeys = mapOf(MifareLayout.PRIMARY_SECTOR to hexToBytes("A0A1A2A3A4A5")),
        )
        val vm = viewModel(foreign)

        vm.onTagDiscovered(tag)

        val outcome = vm.state.value.outcome as ReadOutcome.Failed
        assertTrue(outcome.text.contains("keys this app doesn't know"))
        assertEquals(false, outcome.retryable)
    }

    @Test
    fun `a tag pulled out of range is reported as retryable`() = runTest {
        val vm = viewModel(FakeMifareSession.blank(uid).apply { failConnect = true })

        vm.onTagDiscovered(tag)

        assertTrue((vm.state.value.outcome as ReadOutcome.Failed).retryable)
    }

    @Test
    fun `a non-MifareClassic tag is reported without crashing`() = runTest {
        val vm = viewModel(session = null)

        vm.onTagDiscovered(tag)

        val outcome = vm.state.value.outcome as ReadOutcome.Failed
        assertTrue(outcome.text.contains("MIFARE Classic"))
        assertEquals(false, outcome.retryable)
    }

    // --- Spoolman lookup ---------------------------------------------------------------------

    @Test
    fun `a decoded spool id is looked up in Spoolman`() = runTest {
        val vm = viewModel(writtenSession())

        vm.onTagDiscovered(tag)

        val lookup = vm.state.value.lookup as SpoolLookup.Found
        assertEquals(42, lookup.spool.id)
        assertEquals("PolyTerra PLA Blue", lookup.spool.filament.name)
    }

    /**
     * On a tag written with the ID-only overwrite the serial still names the previous spool, so a
     * read that took the ID from there would show the user someone else's filament — and the printer
     * would load the reserve's spool regardless (DESIGN.md DEC-08).
     */
    @Test
    fun `a tag whose serial names another spool is read and looked up by its reserve`() = runTest {
        val diverged = TagCodec.withSpoolId(TagCodec.encode(fields(spoolId = 7)), 42)
        val vm = viewModel(FakeMifareSession.written(diverged, uid), spools = listOf(testSpool()))

        vm.onTagDiscovered(tag)

        assertEquals(42, (vm.state.value.outcome as ReadOutcome.Written).tag.spoolId)
        assertEquals(42, (vm.state.value.lookup as SpoolLookup.Found).spool.id)
    }

    @Test
    fun `an unreachable server leaves the tag's own fields readable`() = runTest {
        val vm = viewModel(
            writtenSession(),
            getError = SpoolmanError.Unreachable("http://spoolman.local", null),
        )

        vm.onTagDiscovered(tag)

        assertTrue(vm.state.value.outcome is ReadOutcome.Written)
        val lookup = vm.state.value.lookup as SpoolLookup.Unavailable
        assertTrue(lookup.text.contains("Could not reach Spoolman"))
    }

    @Test
    fun `an unconfigured server points at Settings without blocking the read`() = runTest {
        val vm = viewModel(writtenSession(), getError = SpoolmanError.NotConfigured)

        vm.onTagDiscovered(tag)

        assertTrue(vm.state.value.outcome is ReadOutcome.Written)
        assertTrue((vm.state.value.lookup as SpoolLookup.Unavailable).text.contains("Settings"))
    }

    /** A tag whose spool has since been deleted from Spoolman is a normal, quiet outcome. */
    @Test
    fun `a spool missing from Spoolman is reported as unavailable`() = runTest {
        val vm = viewModel(writtenSession(fields(spoolId = 7)), spools = listOf(testSpool(id = 42)))

        vm.onTagDiscovered(tag)

        assertTrue((vm.state.value.lookup as SpoolLookup.Unavailable).text.contains("no longer"))
    }

    // --- Scanning lifecycle ------------------------------------------------------------------

    /**
     * Unlike the write screen, a result is not terminal — working through unknown tags is the job.
     * The screen keeps reader mode bound throughout, so "armed" is simply "not busy"; that the next
     * tag is actually accepted is covered by `a different tag is read straight away`.
     */
    @Test
    fun `the reader goes idle again after a result`() = runTest {
        val vm = viewModel(FakeMifareSession.blank(uid))

        vm.onTagDiscovered(tag)

        assertEquals(false, vm.state.value.busy)
    }

    @Test
    fun `taps are ignored while a read is in flight`() = runTest {
        var opened = 0
        // A queued IO dispatcher parks the read mid-flight, so the second tap arrives while busy.
        val vm = viewModel(
            FakeMifareSession.blank(uid),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onOpenSession = { opened++ },
        )

        vm.onTagDiscovered(tag)
        vm.onTagDiscovered(tag)
        advanceUntilIdle()

        assertEquals(1, opened)
        assertEquals(ReadOutcome.Blank, vm.state.value.outcome)
    }

    // --- Rescan cooldown ---------------------------------------------------------------------
    //
    // Reader mode stays bound for the whole screen, so a tag left resting on the phone is
    // rediscovered as soon as the session closes. Without a cooldown that reads in a loop.

    @Test
    fun `a tag left on the reader is read once, not repeatedly`() = runTest {
        var opened = 0
        val vm = viewModel(FakeMifareSession.blank(uid), onOpenSession = { opened++ })
        val same = tagWithId("11223344")

        repeat(3) {
            vm.onTagDiscovered(same)
            advanceUntilIdle()
        }

        assertEquals(1, opened)
        assertEquals(ReadOutcome.Blank, vm.state.value.outcome)
    }

    @Test
    fun `a different tag is read straight away`() = runTest {
        var opened = 0
        val vm = viewModel(FakeMifareSession.blank(uid), onOpenSession = { opened++ })

        vm.onTagDiscovered(tagWithId("11223344"))
        advanceUntilIdle()
        vm.onTagDiscovered(tagWithId("AABBCCDD"))
        advanceUntilIdle()

        assertEquals(2, opened)
    }

    @Test
    fun `the same tag reads again once the cooldown has passed`() = runTest {
        var opened = 0
        var clock = 0L
        val vm = viewModel(
            FakeMifareSession.blank(uid),
            onOpenSession = { opened++ },
            now = { clock },
        )
        val same = tagWithId("11223344")

        vm.onTagDiscovered(same)
        advanceUntilIdle()
        clock = 2_000
        vm.onTagDiscovered(same)
        advanceUntilIdle()

        assertEquals(2, opened)
    }

    @Test
    fun `a failed read can be retried with the same tag immediately`() = runTest {
        var opened = 0
        // A null session is the "not a MIFARE Classic tag" path, which finishes as Failed.
        val vm = viewModel(session = null, onOpenSession = { opened++ })
        val same = tagWithId("11223344")

        vm.onTagDiscovered(same)
        advanceUntilIdle()
        vm.onTagDiscovered(same)
        advanceUntilIdle()

        assertEquals(2, opened)
        assertTrue(vm.state.value.outcome is ReadOutcome.Failed)
    }

    @Test
    fun `device compatibility is exposed for the screen to gate on`() = runTest {
        val vm = viewModel(
            FakeMifareSession.blank(uid),
            compatibility = DeviceCompatibility.NoMifareClassicSupport,
        )

        assertEquals(DeviceCompatibility.NoMifareClassicSupport, vm.compatibility)
    }
}
