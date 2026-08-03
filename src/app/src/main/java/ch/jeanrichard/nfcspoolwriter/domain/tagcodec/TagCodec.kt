package ch.jeanrichard.nfcspoolwriter.domain.tagcodec

import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket

/**
 * Converts between [MappedFields] and the 96-character ASCII payload string
 * (TAG_FORMAT_SPEC.md §7, §9).
 *
 * Every field is literal ASCII digits/letters — nothing is binary-packed. The string splits into two
 * 48-character halves: the first is encrypted into sector 1, the second is written as plaintext to
 * sector 2. All defined fields live in the first half; the second is space padding.
 *
 * Pure and Android-free, so it is fully unit testable.
 *
 * **Decoding is deliberately permissive about fields whose meaning is unknown.** Reading a genuine
 * Creality tag (2026-08-04) showed the format's documented "constants" are not constant, so the codec
 * validates only what it actually relies on: the structural prefixes, the weight-bucket code, and the
 * numeric fields. Everything else is preserved verbatim.
 */
object TagCodec {

    const val PAYLOAD_LENGTH = 96
    const val HALF_LENGTH = 48

    /** Structural prefix on the colour field: the literal digit `0` then 6 hex digits. */
    private const val COLOR_PREFIX = "0"

    private val BATCH_NUMBER = 0..<3
    private val DATE_CODE = 3..<8
    private val SUPPLIER_ID = 8..<12
    private val MATERIAL_ID = 12..<17
    private val COLOR = 17..<24
    private val LENGTH_CODE = 24..<28
    private val SERIAL_NUMBER = 28..<34
    private val RESERVE = 34..<48

    /**
     * @return exactly [PAYLOAD_LENGTH] characters. [MappedFields] validates its own field widths, so
     *   the concatenation is already 48 characters before the second half is padded.
     */
    fun encode(fields: MappedFields): String {
        val serial = fields.spoolmanSpoolId.toString().padStart(SERIAL_NUMBER.count(), '0')

        val part1 = buildString {
            append(fields.batchNumber)
            append(fields.dateCode)
            append(fields.supplierId)
            append(fields.filamentCatalogId)
            append(COLOR_PREFIX).append(fields.normalizedColorRgb)
            append(fields.weight.code)
            append(serial)
            append(reserveFor(serial))
        }
        check(part1.length == HALF_LENGTH) {
            "assembled sector-1 half must be $HALF_LENGTH chars, was ${part1.length}"
        }

        // Sector 2 carries no fields; it is filled with the format's space padding (§7).
        return part1 + " ".repeat(HALF_LENGTH)
    }

    /**
     * The reserve field duplicates the spool ID in its first 6 characters and zero-fills the
     * remaining 8. **This is a project decision, not part of the format** (§9, DESIGN.md DEC-01).
     *
     * A genuine Creality tag was observed holding `000000` then `0x76` then seven NULs here, so the
     * field is neither all-zero nor pure ASCII in practice — meaning this write does overwrite
     * something whose purpose is unknown. Flagged for hardware validation, not assumed safe.
     */
    private fun reserveFor(serial: String): String = serial.padEnd(RESERVE.count(), '0')

    /**
     * Parses a payload string back into structured fields.
     *
     * @throws TagDecodeException if the input is not a payload this format produced. Decoding is a
     *   trust boundary — the bytes come off a physical tag that may hold anything — so failures are
     *   a typed exception rather than [IllegalArgumentException] from [MappedFields]'s own checks.
     */
    fun decode(payload: String): DecodedPayload {
        if (payload.length != PAYLOAD_LENGTH) {
            throw TagDecodeException(
                "payload must be $PAYLOAD_LENGTH characters, was ${payload.length}"
            )
        }

        val color = payload.substring(COLOR)
        if (!color.startsWith(COLOR_PREFIX)) {
            throw TagDecodeException("colour must start with '$COLOR_PREFIX', was '$color'")
        }

        val lengthCode = payload.substring(LENGTH_CODE)
        val weight = WeightBucket.fromCode(lengthCode)
            ?: throw TagDecodeException("unrecognized weight-bucket code '$lengthCode'")

        val serial = payload.substring(SERIAL_NUMBER)
        val spoolId = serial.toIntOrNull()
            ?: throw TagDecodeException("serial number must be digits, was '$serial'")

        val fields = try {
            MappedFields(
                batchNumber = payload.substring(BATCH_NUMBER),
                dateCode = payload.substring(DATE_CODE),
                supplierId = payload.substring(SUPPLIER_ID),
                filamentCatalogId = payload.substring(MATERIAL_ID),
                colorRgb = color.removePrefix(COLOR_PREFIX),
                weight = weight,
                spoolmanSpoolId = spoolId,
            )
        } catch (e: IllegalArgumentException) {
            throw TagDecodeException("payload fields failed validation: ${e.message}", e)
        }

        return DecodedPayload(fields = fields, reserve = payload.substring(RESERVE))
    }
}

/**
 * [MappedFields] plus the raw reserve bytes. The reserve is surfaced separately rather than folded
 * into the model because it is not a user-facing field — it is derived from the spool ID on write,
 * and on read it is only interesting for diagnosing what a tag actually contains (e.g. comparing
 * against the `000000` + `0x76` + NULs a genuine Creality tag was observed to hold).
 *
 * May contain non-printable bytes; escape it before display.
 */
data class DecodedPayload(
    val fields: MappedFields,
    val reserve: String,
)

class TagDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
