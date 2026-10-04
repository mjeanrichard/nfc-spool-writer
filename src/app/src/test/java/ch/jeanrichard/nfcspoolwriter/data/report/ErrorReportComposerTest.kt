package ch.jeanrichard.nfcspoolwriter.data.report

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReportComposerTest {

    private val environment = ReportEnvironment(
        appVersion = "1.2-debug",
        androidRelease = "15",
        sdkInt = 35,
        device = "Google Pixel 8",
    )

    private fun composer(vararg servers: String) =
        ErrorReportComposer(environment, serverAddresses = { servers.toList() })

    @Test
    fun `the draft is addressed to the developer and names the kind and version`() = runTest {
        val draft = composer().compose(ErrorReport(ErrorReport.Kind.Crash, "boom"))

        assertEquals("apps@jean-richard.ch", draft.to)
        assertEquals("NFC Spool Writer crash report (1.2-debug)", draft.subject)
    }

    @Test
    fun `the body invites a description, then lists the environment and the details`() = runTest {
        val draft = composer().compose(ErrorReport(ErrorReport.Kind.TagFailure, "the details"))

        assertEquals(
            "What were you doing when this happened? (optional)\n\n\n" +
                "---\n" +
                "Report: tag error\n" +
                "App: 1.2-debug\n" +
                "Android: 15 (API 35)\n" +
                "Device: Google Pixel 8\n\n" +
                "the details",
            draft.body,
        )
    }

    @Test
    fun `details are redacted, including the configured server`() = runTest {
        val draft = composer("http://spoolman.lan:7912").compose(
            ErrorReport(
                ErrorReport.Kind.Crash,
                "cannot reach spoolman.lan; tag 04 A2 3B 11; peer 10.0.0.7",
            )
        )

        assertTrue(draft.body, draft.body.endsWith("cannot reach <server>; tag <hex>; peer <ip>"))
        assertFalse(draft.body, draft.body.contains("spoolman.lan"))
    }

    @Test
    fun `details at the limit are kept whole`() = runTest {
        val details = "x".repeat(ErrorReportComposer.MAX_DETAILS_LENGTH)

        val body = composer().compose(ErrorReport(ErrorReport.Kind.Crash, details)).body

        assertTrue(body.endsWith("\n\n$details"))
    }

    @Test
    fun `longer details are truncated and say so`() = runTest {
        val details = "x".repeat(ErrorReportComposer.MAX_DETAILS_LENGTH + 1)

        val body = composer().compose(ErrorReport(ErrorReport.Kind.Crash, details)).body

        val kept = "x".repeat(ErrorReportComposer.MAX_DETAILS_LENGTH)
        assertTrue(body.endsWith("\n\n$kept${ErrorReportComposer.TRUNCATED}"))
    }
}
