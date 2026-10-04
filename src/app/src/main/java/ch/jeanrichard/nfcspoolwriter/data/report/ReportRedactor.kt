package ch.jeanrichard.nfcspoolwriter.data.report

import java.net.URI
import java.net.URISyntaxException

/**
 * Removes what could identify the user or their setup from diagnostic text before it is offered for
 * sending: server addresses, tag IDs, and data echoed back from inputs.
 *
 * Stack frames carry none of that — class, method, file and line — so what is at stake is exception
 * *messages*, which libraries freely fill with the URL they failed to reach or the bytes they were
 * handed. The rules therefore err towards removing too much: a redacted message is still a usable
 * clue next to its stack trace, whereas a leaked address cannot be taken back.
 */
object ReportRedactor {

    const val SERVER = "<server>"
    const val URL = "<url>"
    const val IP = "<ip>"
    const val HEX = "<hex>"
    const val QUOTED = "\"<redacted>\""

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""") to URL,
        // Quoted text in an exception message is almost always input echoed back: a host name that
        // would not resolve, a value that would not parse.
        Regex("\"[^\"\\n]*\"") to QUOTED,
        // kotlinx.serialization appends the whole document it failed on, which here is spool data.
        Regex("""(JSON input:).*""") to "$1 <redacted>",
        // Tag UIDs, in Android's and this app's spaced form ("04 A2 3B 11") and run together. Ahead
        // of the IPv6 rule, which would otherwise claim a colon-separated UID.
        Regex("""\b(?:[0-9A-F]{2}[ :]){3,}[0-9A-F]{2}\b""") to HEX,
        Regex("""\b[0-9A-F]{8,}\b""") to HEX,
        Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])""") to IP,
        Regex("""(?<![\w:])(?=[0-9A-Fa-f:]*[0-9A-Fa-f])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![\w:])""") to IP,
    )

    /**
     * @param serverAddresses addresses the user has configured. Each is removed outright, along with
     *   its bare host name, which the generic rules cannot recognise on its own ("spoolman.lan").
     */
    fun redact(text: String, serverAddresses: List<String> = emptyList()): String {
        val known = serverAddresses
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .flatMap { listOfNotNull(it, hostOf(it)) }
            .distinct()
            // Longest first, so an address is replaced whole before its host is matched inside it.
            .sortedByDescending { it.length }

        val withoutKnown = known.fold(text) { acc, address ->
            acc.replace(address, SERVER, ignoreCase = true)
        }
        return rules.fold(withoutKnown) { acc, (pattern, replacement) ->
            pattern.replace(acc, replacement)
        }
    }

    private fun hostOf(address: String): String? = try {
        URI(address).host
    } catch (_: URISyntaxException) {
        null
    }
}
