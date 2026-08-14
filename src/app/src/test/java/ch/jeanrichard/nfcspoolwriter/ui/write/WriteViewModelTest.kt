package ch.jeanrichard.nfcspoolwriter.ui.write

import android.nfc.Tag
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.FakeMifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareLayout
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.nfc.OverwriteMode
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagReadResult
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.TagCodec
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.fakeSpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.testsupport.hexToBytes
import ch.jeanrichard.nfcspoolwriter.testsupport.realFieldMappingService
import ch.jeanrichard.nfcspoolwriter.testsupport.testSpool
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WriteViewModelTest {

    /**
     * Shared by the rule and by `MifareTagReaderWriter` below. Without injecting it into the
     * reader/writer, its `withContext(Dispatchers.IO)` would run the write on a real background
     * thread that `runTest` never awaits, and every assertion would race the write.
     */
    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    /**
     * `android.nfc.Tag` is final and cannot be constructed in a unit test, so the ViewModel takes an
     * `openSession` function. The tag object itself is only ever passed through to it, never read.
     */
    private val tagA: Tag = mockk(relaxed = true)
    private val tagB: Tag = mockk(relaxed = true)

    private val uidA = hexToBytes("11223344")
    private val uidB = hexToBytes("AABBCCDD")

    private val readerWriter =
        MifareTagReaderWriter(ioDispatcher = testDispatcher, retryDelayMillis = 0)

    private fun viewModel(
        sessions: Map<Tag, MifareSession?>,
        spool: ch.jeanrichard.nfcspoolwriter.domain.model.Spool = testSpool(),
        getError: SpoolmanError? = null,
        compatibility: DeviceCompatibility = DeviceCompatibility.Compatible,
    ) = WriteViewModel(
        spoolId = spool.id,
        spoolmanRepository = fakeSpoolmanRepository(listOf(spool), getError = getError),
        fieldMappingService = realFieldMappingService(),
        tagReaderWriter = readerWriter,
        compatibility = compatibility,
        openSession = { sessions[it] },
    )

    // --- Loading ---------------------------------------------------------------------------

    @Test
    fun `loads and maps the spool before scanning`() = runTest {
        val vm = viewModel(emptyMap())

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals("00001", state.fields?.filamentCatalogId)
        assertTrue(state.canScan)
    }

    @Test
    fun `a load failure blocks scanning`() = runTest {
        val vm = viewModel(emptyMap(), getError = SpoolmanError.Unreachable("http://h", null))

        assertEquals(false, vm.state.value.canScan)
        assertTrue(vm.state.value.loadError != null)
    }

    @Test
    fun `an unmappable material blocks scanning`() = runTest {
        val vm = viewModel(emptyMap(), spool = testSpool(material = "PEEK", name = null))

        assertEquals(false, vm.state.value.canScan)
        assertTrue(vm.state.value.loadError!!.contains("PEEK"))
    }

    // --- One tag per write -------------------------------------------------------------------

    /** REQUIREMENTS.md §5: one verified write is the whole job — not the first half of one. */
    @Test
    fun `a single verified write is terminal`() = runTest {
        val vm = viewModel(mapOf(tagA to FakeMifareSession.blank(uidA)))

        vm.onTagDiscovered(tagA)

        val state = vm.state.value
        assertEquals(1, state.writtenTags.size)
        assertTrue(state.message is WriteMessage.Written)
        assertTrue(state.lastWriteVerified)
        // Reader mode unbinds, so a stray tap cannot start writing an unrelated tag.
        assertEquals(false, state.canScan)
    }

    @Test
    fun `write another tag re-arms without reloading`() = runTest {
        val vm = viewModel(
            mapOf(
                tagA to FakeMifareSession.blank(uidA),
                tagB to FakeMifareSession.blank(uidB),
            )
        )
        vm.onTagDiscovered(tagA)
        val fieldsAfterFirstWrite = vm.state.value.fields

        vm.writeAnother()

        assertNull(vm.state.value.message)
        assertTrue(vm.state.value.canScan)
        // Same instance, not a re-mapped equivalent: no reload happened.
        assertSame(fieldsAfterFirstWrite, vm.state.value.fields)

        vm.onTagDiscovered(tagB)
        assertEquals(2, vm.state.value.writtenTags.size)
    }

    /** "Write another tag" must repeat the confirmed data byte-for-byte, not re-derive it. */
    @Test
    fun `another tag receives an identical payload`() = runTest {
        val a = FakeMifareSession.blank(uidA)
        val b = FakeMifareSession.blank(uidB)
        val vm = viewModel(mapOf(tagA to a, tagB to b))

        vm.onTagDiscovered(tagA)
        vm.writeAnother()
        vm.onTagDiscovered(tagB)

        // Sector 2 is plaintext, so comparing it is enough to show the same payload was written.
        val plaintextOf = { s: FakeMifareSession ->
            MifareLayout.secondaryDataBlocks.map { s.blockOrNull(it)!! }.reduce(ByteArray::plus)
                .toString(Charsets.ISO_8859_1)
        }
        assertEquals(plaintextOf(a), plaintextOf(b))
    }

    /** A tag done this session is recognised by UID and reported, never silently rewritten. */
    @Test
    fun `re-tapping a written tag is reported rather than rewritten`() = runTest {
        val vm = viewModel(mapOf(tagA to FakeMifareSession.blank(uidA)))

        vm.onTagDiscovered(tagA)
        vm.writeAnother()
        vm.onTagDiscovered(tagA)

        assertEquals(1, vm.state.value.writtenTags.size)
        val message = vm.state.value.message
        assertTrue("expected Info, was $message", message is WriteMessage.Info)
        assertTrue((message as WriteMessage.Info).text.contains("already written"))
    }

    // --- Overwrite protection --------------------------------------------------------------

    @Test
    fun `an already-written tag prompts before overwriting`() = runTest {
        val written = FakeMifareSession.written(
            TagCodec.encode(realFieldMappingService().let { testMappedFields() }),
            uidA,
        )
        val vm = viewModel(mapOf(tagA to written))

        vm.onTagDiscovered(tagA)

        val prompt = vm.state.value.overwritePrompt
        assertTrue("expected a prompt", prompt != null)
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    /** The prompt carries both IDs so the dialog can name what an ID-only write would change. */
    @Test
    fun `the prompt reports the tag's current spool id and the new one`() = runTest {
        val other = testMappedFields().copy(spoolmanSpoolId = 7)
        val vm = viewModel(mapOf(tagA to FakeMifareSession.written(TagCodec.encode(other), uidA)))

        vm.onTagDiscovered(tagA)

        val prompt = vm.state.value.overwritePrompt!!
        assertEquals(7, prompt.existingSpoolId)
        assertEquals(testMappedFields().spoolmanSpoolId, prompt.newSpoolId)
        assertTrue(prompt.canWriteSpoolIdOnly)
    }

    @Test
    fun `cancelling the overwrite writes nothing`() = runTest {
        val written = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to written))

        vm.onTagDiscovered(tagA)
        vm.cancelOverwrite()

        assertNull(vm.state.value.overwritePrompt)
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    /** The tag connection can't survive a dialog, so overwriting needs a fresh tap — and says so. */
    @Test
    fun `confirming the overwrite asks for another tap`() = runTest {
        val written = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to written))

        vm.onTagDiscovered(tagA)
        vm.confirmOverwrite(OverwriteMode.Replace)

        assertNull(vm.state.value.overwritePrompt)
        val message = vm.state.value.message
        assertTrue((message as WriteMessage.Info).text.contains("again"))
    }

    @Test
    fun `the tap after confirming actually overwrites`() = runTest {
        val written = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to written))

        vm.onTagDiscovered(tagA)
        vm.confirmOverwrite(OverwriteMode.Replace)
        vm.onTagDiscovered(tagA)

        assertEquals(1, vm.state.value.writtenTags.size)
        val message = vm.state.value.message
        assertEquals(false, (message as WriteMessage.Written).spoolIdOnly)
    }

    /** A confirmation with no prompt showing must not silently approve the next tag tapped. */
    @Test
    fun `confirming with no prompt showing approves nothing`() = runTest {
        val written = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to written))

        vm.confirmOverwrite(OverwriteMode.Replace)
        vm.onTagDiscovered(tagA)

        assertTrue(vm.state.value.overwritePrompt != null)
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    // --- Changing only the spool ID ----------------------------------------------------------

    /** The tap after choosing "ID only" keeps the tag's data and moves only the spool ID. */
    @Test
    fun `the tap after choosing id only rewrites just the spool id`() = runTest {
        val existing = testMappedFields().copy(
            spoolmanSpoolId = 7,
            filamentCatalogId = "02002",
            colorRgb = "0000FF",
        )
        val session = FakeMifareSession.written(TagCodec.encode(existing), uidA)
        val vm = viewModel(mapOf(tagA to session))

        vm.onTagDiscovered(tagA)
        vm.confirmOverwrite(OverwriteMode.SpoolIdOnly)
        vm.onTagDiscovered(tagA)

        assertEquals(1, vm.state.value.writtenTags.size)
        assertEquals(true, (vm.state.value.message as WriteMessage.Written).spoolIdOnly)

        val onTag = (readerWriter.read(session.reopened()) as TagReadResult.Written).payload.fields
        assertEquals(testMappedFields().spoolmanSpoolId, onTag.spoolmanSpoolId)
        assertEquals("02002", onTag.filamentCatalogId)
        assertEquals("0000FF", onTag.colorRgb)
    }

    /** Choosing "ID only" says what the next tap will do, which is not the same as overwriting. */
    @Test
    fun `choosing id only asks for another tap in its own words`() = runTest {
        val written = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to written))

        vm.onTagDiscovered(tagA)
        vm.confirmOverwrite(OverwriteMode.SpoolIdOnly)

        val message = vm.state.value.message as WriteMessage.Info
        assertTrue("was '${message.text}'", message.text.contains("spool ID"))
    }

    /**
     * A tag whose content did not parse has nothing worth keeping, so the option is withheld rather
     * than offered and then failed.
     */
    @Test
    fun `id only is not offered when the tag's content does not parse`() = runTest {
        val session = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        session.forceBlock(MifareLayout.primaryDataBlocks.first(), ByteArray(16))
        val vm = viewModel(mapOf(tagA to session))

        vm.onTagDiscovered(tagA)

        val prompt = vm.state.value.overwritePrompt!!
        assertNull(prompt.existingSpoolId)
        assertEquals(false, prompt.canWriteSpoolIdOnly)
    }

    /**
     * The option is withheld for content that does not parse, but the tag can still be damaged
     * between the prompt and the tap that acts on it. The write must then fail and say what to do,
     * not fall back to replacing data the user asked to keep.
     */
    @Test
    fun `an id-only write reports a tag that stopped being readable`() = runTest {
        val session = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to session))

        vm.onTagDiscovered(tagA)
        vm.confirmOverwrite(OverwriteMode.SpoolIdOnly)
        session.forceBlock(MifareLayout.primaryDataBlocks.first(), ByteArray(16))
        vm.onTagDiscovered(tagA)

        val message = vm.state.value.message as WriteMessage.Failed
        assertTrue("was '${message.text}'", message.text.contains("overwrite"))
        assertEquals(false, message.retryable)
        assertEquals(false, message.partiallyWritten)
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    /**
     * That failure tells the user to tap again and choose a full overwrite, so the tap has to reach
     * the prompt. Keeping the spent ID-only approval would repeat the same failure forever.
     */
    @Test
    fun `a failed id-only write releases its approval so the next tap can choose again`() = runTest {
        val session = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to session))
        vm.onTagDiscovered(tagA)
        vm.confirmOverwrite(OverwriteMode.SpoolIdOnly)
        session.forceBlock(MifareLayout.primaryDataBlocks.first(), ByteArray(16))
        vm.onTagDiscovered(tagA)

        vm.onTagDiscovered(tagA)

        val prompt = vm.state.value.overwritePrompt
        assertTrue("expected the prompt back, was ${vm.state.value.message}", prompt != null)
        // ...and offering only the full overwrite, since there is no longer content worth keeping.
        assertEquals(false, prompt!!.canWriteSpoolIdOnly)
    }

    /** A retryable failure keeps its approval: for those, tapping again *is* the recovery. */
    @Test
    fun `a lost tag keeps the approval so the next tap resumes the chosen mode`() = runTest {
        val session = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to session))
        vm.onTagDiscovered(tagA)
        vm.confirmOverwrite(OverwriteMode.SpoolIdOnly)
        // Fails before the first block lands, so the tag is left intact for the retry.
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = Int.MAX_VALUE
        vm.onTagDiscovered(tagA)
        assertTrue(vm.state.value.message is WriteMessage.Failed)
        session.writeFailures.remove(MifareLayout.primaryDataBlocks.first())

        vm.onTagDiscovered(tagA)

        assertNull(vm.state.value.overwritePrompt)
        assertEquals(true, (vm.state.value.message as WriteMessage.Written).spoolIdOnly)
    }

    /** Approval is per tag: a different tag tapped afterwards is still asked about. */
    @Test
    fun `approving one tag does not approve the next`() = runTest {
        val vm = viewModel(
            mapOf(
                tagA to FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA),
                tagB to FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidB),
            )
        )

        vm.onTagDiscovered(tagA)
        val firstUid = vm.state.value.overwritePrompt!!.uid
        vm.confirmOverwrite(OverwriteMode.SpoolIdOnly)
        vm.onTagDiscovered(tagB)

        val second = vm.state.value.overwritePrompt
        assertTrue("expected a fresh prompt for the second tag", second != null)
        assertNotEquals(firstUid, second!!.uid)
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    @Test
    fun `taps are ignored while a prompt is showing`() = runTest {
        val written = FakeMifareSession.written(TagCodec.encode(testMappedFields()), uidA)
        val vm = viewModel(mapOf(tagA to written))

        vm.onTagDiscovered(tagA)
        vm.onTagDiscovered(tagA)

        assertTrue(vm.state.value.overwritePrompt != null)
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    // --- Failures --------------------------------------------------------------------------

    @Test
    fun `a non-MifareClassic tag is reported without crashing`() = runTest {
        val vm = viewModel(mapOf(tagA to null))

        vm.onTagDiscovered(tagA)

        val message = vm.state.value.message
        assertTrue((message as WriteMessage.Info).text.contains("MIFARE Classic"))
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    @Test
    fun `a wrong-length uid is reported as incompatible`() = runTest {
        val vm = viewModel(mapOf(tagA to FakeMifareSession.blank(hexToBytes("11223344556677"))))

        vm.onTagDiscovered(tagA)

        val message = vm.state.value.message as WriteMessage.Failed
        assertTrue(message.text.contains("4-byte"))
        assertEquals(false, message.retryable)
    }

    @Test
    fun `a lost tag is reported as retryable`() = runTest {
        val session = FakeMifareSession.blank(uidA).apply { failConnect = true }
        val vm = viewModel(mapOf(tagA to session))

        vm.onTagDiscovered(tagA)

        val message = vm.state.value.message as WriteMessage.Failed
        assertTrue(message.retryable)
        assertEquals(false, message.partiallyWritten)
    }

    /** REQUIREMENTS.md §5: a partly-written tag must be flagged as needing a full rewrite. */
    @Test
    fun `a partial write warns the tag is inconsistent`() = runTest {
        val session = FakeMifareSession.blank(uidA)
        session.writeFailures[MifareLayout.primaryDataBlocks.last()] = Int.MAX_VALUE
        val vm = viewModel(mapOf(tagA to session))

        vm.onTagDiscovered(tagA)

        val message = vm.state.value.message as WriteMessage.Failed
        assertTrue(message.partiallyWritten)
        assertEquals(0, vm.state.value.writtenTags.size)
    }

    /** A failure leaves scanning armed — tapping again is the recovery, so it must stay possible. */
    @Test
    fun `a failed write does not count as a written tag and keeps scanning armed`() = runTest {
        val session = FakeMifareSession.blank(uidA)
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = Int.MAX_VALUE
        val vm = viewModel(mapOf(tagA to session))

        vm.onTagDiscovered(tagA)

        assertEquals(0, vm.state.value.writtenTags.size)
        assertEquals(false, vm.state.value.lastWriteVerified)
        assertTrue(vm.state.value.canScan)
    }

    @Test
    fun `an incompatible device cannot scan`() = runTest {
        val vm = viewModel(
            mapOf(tagA to FakeMifareSession.blank(uidA)),
            compatibility = DeviceCompatibility.NoMifareClassicSupport,
        )

        assertEquals(DeviceCompatibility.NoMifareClassicSupport, vm.compatibility)
    }

    @Test
    fun `taps before loading finishes are ignored`() = runTest {
        val vm = viewModel(
            mapOf(tagA to FakeMifareSession.blank(uidA)),
            getError = SpoolmanError.NotConfigured,
        )

        vm.onTagDiscovered(tagA)

        assertEquals(0, vm.state.value.writtenTags.size)
    }

    /** The fields the default test spool maps to, for pre-writing a tag in the overwrite tests. */
    private fun testMappedFields() =
        (realFieldMappingService().map(testSpool())
            as ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingResult.Mapped).fields
}
