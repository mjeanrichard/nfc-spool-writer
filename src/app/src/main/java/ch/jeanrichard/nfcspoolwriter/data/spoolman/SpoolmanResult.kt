package ch.jeanrichard.nfcspoolwriter.data.spoolman

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
 * an action — "check the URL" is useful, "IOException" is not.
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
                "http://spoolman.local:7912"
    }

    /** Connection refused, DNS failure, timeout — the server could not be reached at all. */
    data class Unreachable(val url: String, val cause: Throwable?) : SpoolmanError {
        override val userMessage: String
            get() = "Could not reach Spoolman at $url. Check the address, and that the server is " +
                "running and on the same network."
    }

    /** The requested spool does not exist (or has been deleted). */
    data class SpoolNotFound(val spoolId: Int) : SpoolmanError {
        override val userMessage: String get() = "Spool $spoolId no longer exists in Spoolman."
    }

    /**
     * A non-2xx response. Split out from [Unreachable] because it means the server answered — so the
     * address is right and the problem is the request or the server itself.
     */
    data class HttpStatus(val code: Int, val body: String?) : SpoolmanError {
        override val userMessage: String
            get() = when (code) {
                401, 403 -> "Spoolman refused the request ($code). Spoolman itself has no login, so " +
                    "this usually means a reverse proxy in front of it requires authentication, " +
                    "which this app does not support."
                in 500..599 -> "The Spoolman server reported an error ($code). Check its logs."
                else -> "Spoolman returned an unexpected response ($code)."
            }
    }

    /**
     * The server answered but the body was not the JSON expected — a wrong host answering on that
     * port, or a Spoolman version whose schema changed incompatibly.
     */
    data class MalformedResponse(val detail: String?) : SpoolmanError {
        override val userMessage: String
            get() = "Spoolman's response could not be understood. Confirm the address points at " +
                "Spoolman and not another service."
    }
}
