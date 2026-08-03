package ch.jeanrichard.nfcspoolwriter.data.nfc

/**
 * Byte/text conversion for tag payloads.
 *
 * **Latin-1, deliberately not US-ASCII.** The format is described as ASCII, and everything this app
 * writes is ASCII, but real tags are not: a genuine Creality tag was found holding a byte ≥ `0x80` in
 * the reserve field. Decoding that with `US_ASCII` silently substitutes U+FFFD and **destroys the
 * value** — the byte cannot be recovered, and re-encoding turns it into `0x3F` (`?`), which is
 * actively misleading because it looks like real data.
 *
 * ISO-8859-1 maps bytes `0x00`–`0xFF` one-to-one onto U+0000–U+00FF, so payloads round-trip
 * losslessly whatever a tag actually contains. That matters for correctness, not just diagnostics:
 * the write path verifies by comparing a re-read payload against what was intended, and a lossy
 * decode would make unrelated bytes compare equal.
 */
internal val TAG_CHARSET = Charsets.ISO_8859_1

internal fun String.toTagBytes(): ByteArray = toByteArray(TAG_CHARSET)

internal fun ByteArray.toTagText(): String = String(this, TAG_CHARSET)

/** Space-separated uppercase hex, matching how TAG_FORMAT_SPEC.md presents byte sequences. */
internal fun ByteArray.toHexDump(): String = joinToString(" ") { "%02X".format(it) }

/**
 * Renders text with non-printable and non-ASCII characters escaped as `\xNN`, so a `0x00` or a
 * high byte is visible rather than silently invisible in a log or on screen.
 */
internal fun String.escapeNonPrintable(): String = buildString {
    this@escapeNonPrintable.forEach { c ->
        if (c in ' '..'~') append(c) else append("\\x%02X".format(c.code))
    }
}
