package ch.jeanrichard.nfcspoolwriter.data.spoolman

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.Url
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SpoolmanErrorTest {

    private val base = "http://192.168.1.10:7912"
    private val withPort = Url("$base/api/v1/health")
    private val withoutPort = Url("http://192.168.1.10/api/v1/health")

    /** How OkHttp reports a socket failure: its own ConnectException around the platform one. */
    private fun okHttpWrapped(cause: Throwable) =
        ConnectException("Failed to connect to /192.168.1.10:7912").apply { initCause(cause) }

    private fun unreachable(cause: Throwable?, requested: Url? = withPort) =
        SpoolmanError.Unreachable(base, cause, requested)

    // --- Classification --------------------------------------------------------------------

    @Test
    fun `no cause is other`() {
        assertEquals(UnreachableReason.Other, UnreachableReason.of(null))
    }

    @Test
    fun `an unrelated io failure is other`() {
        assertEquals(UnreachableReason.Other, UnreachableReason.of(IOException("stream closed")))
    }

    @Test
    fun `a dns failure is host not found`() {
        assertEquals(UnreachableReason.HostNotFound, UnreachableReason.of(UnknownHostException("x")))
    }

    @Test
    fun `a tls failure is tls`() {
        assertEquals(UnreachableReason.Tls, UnreachableReason.of(SSLHandshakeException("bad cert")))
    }

    @Test
    fun `every timeout flavour is a timeout`() = runTest {
        val cancellation = runCatching { withTimeout(1) { delay(10) } }.exceptionOrNull()
        assertTrue(cancellation is TimeoutCancellationException)

        listOf(
            SocketTimeoutException("read timed out"),
            ConnectTimeoutException("connect timed out", null),
            HttpRequestTimeoutException("http://h", 15_000L),
            cancellation!!,
            okHttpWrapped(SocketTimeoutException("connect timed out")),
        ).forEach { cause ->
            assertEquals("$cause", UnreachableReason.Timeout, UnreachableReason.of(cause))
        }
    }

    @Test
    fun `no route to host is no route`() {
        assertEquals(UnreachableReason.NoRoute, UnreachableReason.of(NoRouteToHostException()))
    }

    /** Android leaves the errno only in the message of the wrapped exception. */
    @Test
    fun `an unreachable host or network errno is no route even inside a connect exception`() {
        listOf("EHOSTUNREACH (No route to host)", "ENETUNREACH (Network is unreachable)").forEach {
            val cause = okHttpWrapped(IOException("isConnected failed: $it"))
            assertEquals(it, UnreachableReason.NoRoute, UnreachableReason.of(cause))
        }
    }

    @Test
    fun `a connect exception is refused`() {
        val cause = okHttpWrapped(IOException("isConnected failed: ECONNREFUSED"))
        assertEquals(UnreachableReason.Refused, UnreachableReason.of(cause))
    }

    @Test
    fun `a refused message without a connect exception is refused`() {
        assertEquals(
            UnreachableReason.Refused,
            UnreachableReason.of(IOException("Connection Refused")),
        )
    }

    // --- Unreachable messages --------------------------------------------------------------

    @Test
    fun `host not found names the host and suggests checking the spelling`() {
        val message = SpoolmanError.Unreachable(
            "http://spolman:7912",
            UnknownHostException(),
            Url("http://spolman:7912/api/v1/health"),
        ).userMessage

        assertTrue(message, message.contains("'spolman'"))
        assertTrue(message, message.contains("spelling"))
    }

    @Test
    fun `host not found on a local name suggests the ip address instead`() {
        val message = SpoolmanError.Unreachable(
            "http://spoolman.LOCAL:7912",
            UnknownHostException(),
            Url("http://spoolman.LOCAL:7912/api/v1/health"),
        ).userMessage

        assertTrue(message, message.contains(".local names"))
    }

    @Test
    fun `host not found without a built url falls back to the typed address`() {
        val message = SpoolmanError.Unreachable("nas", UnknownHostException(), null).userMessage

        assertTrue(message, message.contains("'nas'"))
    }

    @Test
    fun `refused points at the port`() {
        val message = unreachable(ConnectException()).userMessage

        assertTrue(message, message.contains("refused"))
        assertTrue(message, message.contains(base))
        assertFalse(message, message.contains("no port number"))
    }

    @Test
    fun `refused without a port suggests spoolman's default port`() {
        val message = unreachable(ConnectException(), withoutPort).userMessage

        assertTrue(message, message.contains("http://192.168.1.10:7912"))
    }

    @Test
    fun `no route points at the network`() {
        val message = unreachable(NoRouteToHostException()).userMessage

        assertTrue(message, message.contains("mobile data"))
    }

    @Test
    fun `timeout points at the network and firewall`() {
        val message = unreachable(SocketTimeoutException()).userMessage

        assertTrue(message, message.contains("did not answer in time"))
        assertTrue(message, message.contains("firewall"))
        assertFalse(message, message.contains("no port number"))
    }

    @Test
    fun `timeout without a port suggests spoolman's default port`() {
        val message = unreachable(SocketTimeoutException(), withoutPort).userMessage

        assertTrue(message, message.contains("no port number"))
    }

    @Test
    fun `tls suggests plain http`() {
        val message = unreachable(SSLHandshakeException("x")).userMessage

        assertTrue(message, message.contains("http://"))
    }

    @Test
    fun `other shows the innermost cause's text as details`() {
        val cause = IOException("outer", IOException("unexpected end of stream"))

        val message = unreachable(cause).userMessage

        assertTrue(message, message.endsWith("\nDetails: unexpected end of stream"))
    }

    @Test
    fun `other without any cause text has no details line`() {
        assertFalse(unreachable(IOException()).userMessage.contains("Details"))
        assertFalse(unreachable(null).userMessage.contains("Details"))
    }

    @Test
    fun `the port hint is omitted when no url was built`() {
        assertFalse(unreachable(ConnectException(), null).userMessage.contains("no port number"))
    }

    // --- HTTP status messages --------------------------------------------------------------

    @Test
    fun `401 and 403 blame a reverse proxy`() {
        listOf(401, 403).forEach {
            assertTrue(SpoolmanError.HttpStatus(it, null).userMessage.contains("proxy"))
        }
    }

    @Test
    fun `404 shows the url requested and how to fix the address`() {
        val message = SpoolmanError.HttpStatus(404, null, Url("$base/api/v1/api/v1/health"))
            .userMessage

        assertTrue(message, message.contains("$base/api/v1/api/v1/health"))
        assertTrue(message, message.contains("without /api/v1"))
        assertFalse(message, message.contains("no port number"))
    }

    @Test
    fun `404 without a port suggests spoolman's default port`() {
        val message = SpoolmanError.HttpStatus(404, null, withoutPort).userMessage

        assertTrue(message, message.contains("no port number"))
    }

    @Test
    fun `404 without a built url still reads as a sentence`() {
        val message = SpoolmanError.HttpStatus(404, null).userMessage

        assertTrue(message, message.contains("at that address"))
    }

    @Test
    fun `5xx points at the server logs`() {
        assertTrue(SpoolmanError.HttpStatus(502, null).userMessage.contains("logs"))
    }

    @Test
    fun `another status names the url when there is one`() {
        assertEquals(
            "Spoolman returned an unexpected response (418) from $withPort.",
            SpoolmanError.HttpStatus(418, null, withPort).userMessage,
        )
        assertEquals(
            "Spoolman returned an unexpected response (418).",
            SpoolmanError.HttpStatus(418, null).userMessage,
        )
    }

    // --- Malformed response messages -------------------------------------------------------

    @Test
    fun `an html answer names the url and suspects another service`() {
        val message = SpoolmanError.MalformedResponse(null, withPort, receivedHtml = true).userMessage

        assertTrue(message, message.contains("web page answered at $withPort"))
        assertFalse(message, message.contains("no port number"))
    }

    @Test
    fun `an html answer without a port suggests spoolman's default port`() {
        val message =
            SpoolmanError.MalformedResponse(null, withoutPort, receivedHtml = true).userMessage

        assertTrue(message, message.contains("no port number"))
    }

    @Test
    fun `an html answer without a built url still reads as a sentence`() {
        val message = SpoolmanError.MalformedResponse(null, receivedHtml = true).userMessage

        assertTrue(message, message.contains("at that address"))
    }

    @Test
    fun `a json answer of the wrong shape suspects the version`() {
        assertTrue(SpoolmanError.MalformedResponse(null).userMessage.contains("up to date"))
    }

    @Test
    fun `no message leaks an exception type name`() {
        listOf(
            unreachable(UnknownHostException()),
            unreachable(ConnectException()),
            unreachable(NoRouteToHostException()),
            unreachable(SocketTimeoutException()),
            unreachable(SSLHandshakeException("x")),
            SpoolmanError.HttpStatus(404, null, withPort),
            SpoolmanError.MalformedResponse(null, withPort, receivedHtml = true),
        ).forEach { assertFalse("$it", it.userMessage.contains("Exception")) }
    }
}
