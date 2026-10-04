package ch.jeanrichard.nfcspoolwriter.data.spoolman

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.Url
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Outcome of a Spoolman call. A sealed result rather than exceptions so the UI renders failure states
 * declaratively, and rather than `kotlin.Result` so the error cases are enumerable and each can carry
 * its own user-facing text (REQUIREMENTS.md REQ-06: every failure state needs a real message, not a
 * stack trace).
 */
sealed interface SpoolmanResult<out T> {
    data class Success<T>(val value: T) : SpoolmanResult<T>
    data class Failure(val error: SpoolmanError) : SpoolmanResult<Nothing>
}

inline fun <T, R> SpoolmanResult<T>.map(transform: (T) -> R): SpoolmanResult<R> = when (this) {
    is SpoolmanResult.Success -> SpoolmanResult.Success(transform(value))
    is SpoolmanResult.Failure -> this
}

fun <T> SpoolmanResult<T>.valueOrNull(): T? = (this as? SpoolmanResult.Success)?.value

/**
 * Why a Spoolman call failed.
 *
 * [userMessage] is the text shown in the UI. Each one names the likely cause and, where there is one,
 * an action — "check the URL" is useful, "IOException" is not. Users report connection problems by
 * quoting this text, so it is as specific as the failure allows: which host, which URL, and which of
 * the distinct network failures happened.
 *
 * `requested` is the full URL the request went to, when one was built. It is shown where the path
 * matters (an HTTP error, or the wrong service answering), and its port drives [missingPortHint].
 */
sealed interface SpoolmanError {

    val userMessage: String

    /** No server URL configured yet. Expected on first run, not really an error. */
    data object NotConfigured : SpoolmanError {
        override val userMessage: String
            get() = "No Spoolman server configured. Set the server address in Settings."
    }

    data class InvalidUrl(val url: String) : SpoolmanError {
        override val userMessage: String
            get() = "'$url' is not a valid server address. Expected something like " +
                "http://192.168.1.10:7912"
    }

    /** The server could not be reached at all; [reason] says which way the connection failed. */
    data class Unreachable(
        val url: String,
        val cause: Throwable?,
        val requested: Url? = null,
    ) : SpoolmanError {

        val reason: UnreachableReason get() = UnreachableReason.of(cause)

        override val userMessage: String
            get() = when (reason) {
                UnreachableReason.HostNotFound -> hostNotFoundMessage()
                UnreachableReason.Refused ->
                    "The server at $url refused the connection. It is reachable, but nothing is " +
                        "listening on that port: check the port number and that Spoolman is running." +
                        missingPortHint(requested)
                UnreachableReason.NoRoute ->
                    "No network route to $url. Check that the phone is on the same network as the " +
                        "server: Wi-Fi rather than mobile data, and not a guest network."
                UnreachableReason.Timeout ->
                    "$url did not answer in time. Check that the phone is on the same network as the " +
                        "server (Wi-Fi rather than mobile data, and not a guest network), and that no " +
                        "firewall blocks the port." + missingPortHint(requested)
                UnreachableReason.Tls ->
                    "A secure (https) connection to $url failed. If Spoolman is served over plain " +
                        "HTTP, start the address with http:// instead. A self-signed certificate is " +
                        "not trusted by Android."
                UnreachableReason.Other ->
                    "Could not reach Spoolman at $url. Check the address, and that the server is " +
                        "running and on the same network." +
                        cause?.rootMessage()?.let { "\nDetails: $it" }.orEmpty()
            }

        private fun hostNotFoundMessage(): String {
            val host = requested?.host ?: url
            val lookupAdvice = if (host.endsWith(".local", ignoreCase = true)) {
                "Many Android phones cannot look up .local names. Use the server's IP address " +
                    "instead, e.g. http://192.168.1.10:7912."
            } else {
                "Check the spelling, or use the server's IP address instead."
            }
            return "Could not find a server named '$host'. $lookupAdvice"
        }
    }

    /** The requested spool does not exist (or has been deleted). */
    data class SpoolNotFound(val spoolId: Int) : SpoolmanError {
        override val userMessage: String get() = "Spool $spoolId no longer exists in Spoolman."
    }

    /**
     * A non-2xx response. Split out from [Unreachable] because it means the server answered — so the
     * host is right and the problem is the path, the request or the server itself.
     */
    data class HttpStatus(
        val code: Int,
        val body: String?,
        val requested: Url? = null,
    ) : SpoolmanError {
        override val userMessage: String
            get() = when (code) {
                401, 403 -> "Spoolman refused the request ($code). Spoolman itself has no login, so " +
                    "this usually means a reverse proxy in front of it requires authentication, " +
                    "which this app does not support."
                404 -> "Spoolman's API was not found at ${requested ?: "that address"} (404). Enter " +
                    "the address that opens Spoolman's web page, without /api/v1 or a page such as " +
                    "/spool after it." + missingPortHint(requested)
                in 500..599 -> "The Spoolman server reported an error ($code). Check its logs."
                else -> "Spoolman returned an unexpected response ($code)" +
                    requested?.let { " from $it" }.orEmpty() + "."
            }
    }

    /**
     * The server answered but the body was not the JSON expected — another service answering on that
     * address, or a Spoolman version whose schema changed incompatibly. [receivedHtml] singles out the
     * first, by far the more common, so the message can name it.
     */
    data class MalformedResponse(
        val detail: String?,
        val requested: Url? = null,
        val receivedHtml: Boolean = false,
    ) : SpoolmanError {
        override val userMessage: String
            get() = if (receivedHtml) {
                "A web page answered at ${requested ?: "that address"} instead of Spoolman, " +
                    "usually a router, NAS or other service on the same address." +
                    missingPortHint(requested)
            } else {
                "Spoolman's response could not be understood. Confirm the address points at " +
                    "Spoolman and not another service, and that Spoolman is up to date."
            }
    }
}

/** The distinct ways a connection can fail, each pointing at a different fix. */
enum class UnreachableReason {
    /** DNS could not resolve the host name. */
    HostNotFound,

    /** The host answered but nothing listens on the port. */
    Refused,

    /** The host is not reachable from this network at all. */
    NoRoute,

    /** Nothing answered in time: a different network, a firewall dropping packets, a sleeping host. */
    Timeout,

    /** The TLS handshake failed: https to a plain-HTTP server, or an untrusted certificate. */
    Tls,

    Other;

    companion object {

        /**
         * Walks the whole cause chain, since OkHttp wraps the socket-level exception in its own
         * `ConnectException("Failed to connect to …")`. On Android the errno survives only as text in
         * a message, hence the message checks.
         */
        fun of(cause: Throwable?): UnreachableReason {
            val chain = cause?.causeChain().orEmpty()
            val messages = chain.mapNotNull { it.message }.joinToString(" ")
            return when {
                chain.any { it is UnknownHostException } -> HostNotFound
                chain.any { it is SSLException } -> Tls
                chain.any { it.isTimeout() } -> Timeout
                chain.any { it is NoRouteToHostException } ||
                    "EHOSTUNREACH" in messages || "ENETUNREACH" in messages -> NoRoute
                chain.any { it is ConnectException } ||
                    messages.contains("refused", ignoreCase = true) -> Refused
                else -> Other
            }
        }

        private fun Throwable.isTimeout(): Boolean =
            this is SocketTimeoutException ||
                this is ConnectTimeoutException ||
                this is HttpRequestTimeoutException ||
                this is TimeoutCancellationException
    }
}

/**
 * Without a port the request goes to 80 or 443, while Spoolman listens on 7912 unless a reverse proxy
 * fronts it. That is the most common wrong address, and none of its symptoms — refused, timed out,
 * some other web page — mention the port.
 */
private fun missingPortHint(requested: Url?): String =
    if (requested != null && requested.specifiedPort == DEFAULT_PORT) {
        " The address has no port number; Spoolman normally uses 7912, e.g. " +
            "${requested.protocol.name}://${requested.host}:7912."
    } else {
        ""
    }

private fun Throwable.causeChain(): List<Throwable> =
    generateSequence(this) { it.cause }.toList()

private fun Throwable.rootMessage(): String? = causeChain().last().message
