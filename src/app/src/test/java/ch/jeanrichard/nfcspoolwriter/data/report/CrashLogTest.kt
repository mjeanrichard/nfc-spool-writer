package ch.jeanrichard.nfcspoolwriter.data.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val thread = Thread("worker-1")

    private fun log(file: File = File(folder.root, "crash.txt")) = CrashLog(file, appVersion = "1.2")

    // --- CrashLog ------------------------------------------------------------------------------

    @Test
    fun `a recorded crash reads back with version, thread and full trace`() {
        val log = log()

        log.record(thread, IllegalStateException("outer", RuntimeException("inner")))

        val text = log.read()!!
        assertTrue(text, text.startsWith("App version at crash: 1.2\nThread: worker-1\n\n"))
        assertTrue(text, text.contains("java.lang.IllegalStateException: outer"))
        assertTrue(text, text.contains("Caused by: java.lang.RuntimeException: inner"))
    }

    @Test
    fun `a later crash replaces an unanswered one`() {
        val log = log()

        log.record(thread, RuntimeException("first"))
        log.record(thread, RuntimeException("second"))

        val text = log.read()!!
        assertFalse(text.contains("first"))
        assertTrue(text.contains("second"))
    }

    @Test
    fun `nothing is read when nothing was recorded`() {
        assertNull(log().read())
    }

    @Test
    fun `clearing forgets the crash`() {
        val log = log()
        log.record(thread, RuntimeException("boom"))

        log.clear()

        assertNull(log.read())
    }

    /** A directory where the file should be makes every write and read fail with an IOException. */
    @Test
    fun `an unwritable or unreadable file is tolerated`() {
        val log = log(folder.newFolder("crash.txt"))

        log.record(thread, RuntimeException("boom"))

        assertNull(log.read())
    }

    // --- CrashRecordingHandler -----------------------------------------------------------------

    @Test
    fun `the handler records the crash and then hands it on`() {
        val log = log()
        val error = RuntimeException("boom")
        var handedOn: Pair<Thread, Throwable>? = null

        CrashRecordingHandler(log) { t, e -> handedOn = t to e }.uncaughtException(thread, error)

        assertTrue(log.read()!!.contains("boom"))
        val (handedThread, handedError) = handedOn!!
        assertSame(thread, handedThread)
        assertSame(error, handedError)
    }

    @Test
    fun `the handler still records when there is nothing to hand on to`() {
        val log = log()

        CrashRecordingHandler(log, next = null).uncaughtException(thread, RuntimeException("boom"))

        assertTrue(log.read()!!.contains("boom"))
    }

    @Test
    fun `install puts a recording handler in front of the existing default`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val handedOn = mutableListOf<Throwable>()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn += e }
            val log = log()

            CrashRecordingHandler.install(log)
            val error = RuntimeException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(thread, error)

            assertTrue(log.read()!!.contains("boom"))
            assertEquals(listOf(error), handedOn)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
