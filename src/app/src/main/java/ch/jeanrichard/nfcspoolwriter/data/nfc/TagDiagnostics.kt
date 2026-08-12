package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.TagCodec
import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.TagDecodeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Produces a raw, human-readable dump of a tag for the manual hardware checks.
 *
 * Exists because several of those checks can only be answered by looking at bytes the normal read
 * path deliberately hides — in particular the sector-1 trailer's access bits, which this format must
 * preserve and which a bug could silently clobber, and the raw reserve field, whose acceptance by
 * real hardware is recorded in DESIGN.md DEC-01, "serial number and reserve fields".
 *
 * Every hex line is derived from the bytes as read, never from decoded text. Deriving hex from a
 * string re-encodes it, which prints bytes the tag never held — that mistake once reported a tag's
 * high byte as `0x3F`.
 *
 * Diagnostic only: nothing in the write path depends on it.
 */
class TagDiagnostics(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {

    suspend fun report(session: MifareSession): String = withContext(ioDispatcher) {
        val lines = mutableListOf<String>()
        try {
            session.use {
                it.connect()
                val uid = it.uid
                lines += "UID:          ${uid.toHexDump()} (${uid.size} bytes)"

                if (uid.size != KeyDerivation.UID_LENGTH) {
                    lines += "INCOMPATIBLE: this format requires a 4-byte UID (spec §3)."
                    return@withContext lines.joinToString("\n")
                }

                val derivedKey = KeyDerivation.deriveSectorKey(uid)
                lines += "Derived key:  ${derivedKey.toHexDump()}"

                val securedByUs =
                    it.authenticateSectorWithKeyA(MifareLayout.PRIMARY_SECTOR, derivedKey)
                val onDefault = !securedByUs && it.authenticateSectorWithKeyA(
                    MifareLayout.PRIMARY_SECTOR,
                    KeyDerivation.DEFAULT_KEY,
                )
                lines += "Sector 1:     " + when {
                    securedByUs -> "authenticates with the DERIVED key -> already written by us"
                    onDefault -> "authenticates with the DEFAULT key -> blank"
                    else -> "authenticates with NEITHER key -> unrelated key scheme"
                }
                if (!securedByUs && !onDefault) return@withContext lines.joinToString("\n")

                // The bytes this format promises never to change (spec §8).
                val trailer = it.readBlock(MifareLayout.PRIMARY_TRAILER_BLOCK)
                lines += "S1 trailer:   ${trailer.toHexDump()}"
                lines += "  keyA[0:6]   ${trailer.copyOfRange(0, 6).toHexDump()}  " +
                    "(reads as zeros on most tags — not readable by design)"
                lines += "  access[6:9] ${trailer.copyOfRange(6, 9).toHexDump()}   <- must be unchanged"
                lines += "  gpb[9]      ${trailer.copyOfRange(9, 10).toHexDump()}       <- must be unchanged"
                lines += "  keyB[10:16] ${trailer.copyOfRange(10, 16).toHexDump()}"

                if (!securedByUs) {
                    lines += ""
                    lines += "No payload to decode — tag is blank."
                    return@withContext lines.joinToString("\n")
                }

                val primary = MifareLayout.primaryDataBlocks
                    .map(it::readBlock)
                    .reduce(ByteArray::plus)
                val part1Bytes = PayloadCipher.decrypt(primary)

                val part2Bytes = if (it.authenticateSectorWithKeyA(
                        MifareLayout.SECONDARY_SECTOR,
                        KeyDerivation.DEFAULT_KEY,
                    )
                ) {
                    MifareLayout.secondaryDataBlocks
                        .map(it::readBlock)
                        .reduce(ByteArray::plus)
                } else {
                    lines += "Sector 2:     does NOT authenticate with the default key (unexpected)"
                    ByteArray(0)
                }

                val part1 = part1Bytes.toTagText()
                val part2 = part2Bytes.toTagText()

                lines += ""
                lines += "Part 1 len ${part1.length}: '${part1.escapeNonPrintable()}'"
                lines += "Part 1 hex: ${part1Bytes.toHexDump()}"
                lines += "Part 2 len ${part2.length}: '${part2.escapeNonPrintable()}'"
                lines += "Part 2 hex: ${part2Bytes.toHexDump()}"

                val raw = part1 + part2
                try {
                    val decoded = TagCodec.decode(raw)
                    lines += ""
                    lines += "Decoded:"
                    lines += "  batch       ${decoded.fields.batchNumber}"
                    lines += "  date        ${decoded.fields.dateCode}   <- reportedly YYMDD; " +
                        "preserved verbatim, not parsed"
                    lines += "  supplier    ${decoded.fields.supplierId}"
                    lines += "  material    ${decoded.fields.filamentCatalogId}"
                    lines += "  colour      #${decoded.fields.colorRgb}"
                    lines += "  weight      ${decoded.fields.weight.grams} g " +
                        "(code ${decoded.fields.weight.code})"
                    lines += "  spool ID    ${decoded.fields.spoolmanSpoolId}"
                    lines += "  reserve     ${decoded.reserve.escapeNonPrintable()}"
                    lines += "              ^ non-zero here is this project's deviation; check the " +
                        "printer tolerates it"
                } catch (e: TagDecodeException) {
                    lines += ""
                    lines += "DOES NOT DECODE: ${e.message}"
                }
            }
        } catch (e: IOException) {
            lines += "TAG LOST: ${e.message}"
        }
        lines.joinToString("\n")
    }
}
