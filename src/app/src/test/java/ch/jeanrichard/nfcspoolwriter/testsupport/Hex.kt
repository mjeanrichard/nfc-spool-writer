package ch.jeanrichard.nfcspoolwriter.testsupport

/** Test helpers for expressing byte arrays as the hex strings TAG_FORMAT_SPEC.md uses. */

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

/** Accepts spaces and newlines so vectors can be pasted in the spec's grouped layout. */
fun hexToBytes(hex: String): ByteArray {
    val compact = hex.filterNot { it.isWhitespace() }
    require(compact.length % 2 == 0) { "hex string must have an even number of digits" }
    return ByteArray(compact.length / 2) {
        compact.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
