package ch.jeanrichard.nfcspoolwriter.data.report

import ch.jeanrichard.nfcspoolwriter.data.nfc.TagFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorReportTest {

    @Test
    fun `a tag failure report lists the operation, failure and context in order`() {
        val report = ErrorReport.tagFailure(
            operation = "write",
            failure = TagFailure.UnknownKeyScheme,
            context = listOf("Overwrite mode" to "Replace", "Partially written" to false),
        )

        assertEquals(ErrorReport.Kind.TagFailure, report.kind)
        assertEquals(
            "Operation: write\n" +
                "Failure: UnknownKeyScheme\n" +
                "Overwrite mode: Replace\n" +
                "Partially written: false",
            report.details,
        )
    }

    @Test
    fun `every failure type is named`() {
        val names = listOf(
            TagFailure.IncompatibleUidLength,
            TagFailure.UnknownKeyScheme,
            TagFailure.TagLost(null),
            TagFailure.VerifyMismatch("x"),
            TagFailure.ExistingContentUnreadable("x"),
        ).map { ErrorReport.tagFailure("read", it).details }

        assertEquals(
            listOf(
                "Operation: read\nFailure: IncompatibleUidLength",
                "Operation: read\nFailure: UnknownKeyScheme",
                "Operation: read\nFailure: TagLost",
                "Operation: read\nFailure: VerifyMismatch",
                "Operation: read\nFailure: ExistingContentUnreadable",
            ),
            names,
        )
    }

    @Test
    fun `a lost tag carries its cause's stack trace`() {
        val cause = IOException("Tag was lost.", IllegalStateException("inner"))

        val details = ErrorReport.tagFailure("read", TagFailure.TagLost(cause)).details

        assertTrue(details, details.startsWith("Operation: read\nFailure: TagLost\n\n"))
        assertTrue(details, details.contains("java.io.IOException: Tag was lost."))
        assertTrue(details, details.contains("Caused by: java.lang.IllegalStateException: inner"))
        assertTrue(details, details.contains("\tat "))
    }

    /** These details hold the tag's content, which is spool data and must not be reported. */
    @Test
    fun `failure details that carry tag content are left out`() {
        val mismatch = ErrorReport.tagFailure(
            "write",
            TagFailure.VerifyMismatch("expected: 0AB12 secret payload"),
        ).details
        val unreadable = ErrorReport.tagFailure(
            "write",
            TagFailure.ExistingContentUnreadable("bad weight field 'secret'"),
        ).details

        assertFalse(mismatch, mismatch.contains("secret"))
        assertFalse(unreadable, unreadable.contains("secret"))
    }

    @Test
    fun `kinds have readable labels`() {
        assertEquals("crash", ErrorReport.Kind.Crash.label)
        assertEquals("tag error", ErrorReport.Kind.TagFailure.label)
    }
}
