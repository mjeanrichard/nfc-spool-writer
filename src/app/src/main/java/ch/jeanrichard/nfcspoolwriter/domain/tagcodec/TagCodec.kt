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
     * `[34, 40)` — the reserve's leading 6 characters: the field a printer actually resolves a
     * Spoolman spool from, and therefore the one field [decode] takes the spool ID from. The
     * remaining `[40, 48)` is where genuine tags carry bytes of unidentified purpose, and is not part
     * of any consumer's record (§9).
     */
    private val RESERVE_SPOOL_ID = 34..<40

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
     * Returns [payload] with [RESERVE_SPOOL_ID] set to [spoolId] and **every other byte
     * byte-identical** — the "change the Spoolman ID only" overwrite (REQUIREMENTS.md `REQ-16`).
     *
     * The point of this operation is a tag whose content this app did not author: a genuine Creality
     * tag carries a real batch number, date code, serial number and reserve bytes, and re-pointing it
     * at a different Spoolman spool should not cost the user any of that. So nothing is re-derived
     * here — not even the fields [encode] would write as constants.
     *
     * `[34, 40)` is the only field that has to move, because it is the only one a printer resolves the
     * Spoolman spool from (§9). **The serial number is left alone** even though [encode] writes the ID
     * there too: nothing reads it, it is a genuine tag's own data, and preserving unauthored bytes is
     * the entire point of this path (DESIGN.md DEC-08).
     *
     * @throws TagDecodeException if [payload] is not something [decode] can read. Splicing a field
     *   into bytes we cannot parse would leave the tag as broken as it already is, only differently —
     *   and the caller cannot have meant to preserve content that is not intact.
     */
    fun withSpoolId(payload: String, spoolId: Int): String {
        // Trust boundary, same as decode: the bytes came off a physical tag and may hold anything.
        decode(payload)
        require(spoolId in 0..MappedFields.MAX_SPOOL_ID) {
            "spoolId must fit in ${RESERVE_SPOOL_ID.count()} digits, was $spoolId"
        }
        return payload.replaceRange(
            RESERVE_SPOOL_ID,
            spoolId.toString().padStart(RESERVE_SPOOL_ID.count(), '0'),
        )
    }

    /**
     * The reserve field repeats the spool ID in its first 6 characters and zero-fills the remaining 8.
     *
     * The repetition is a project decision (DESIGN.md DEC-01), but the placement is not: `[34, 40)` is
     * the field a printer reads the Spoolman spool ID from, and the serial number is not consulted
     * (§9). The trailing 8 are the project's own — a genuine Creality tag was observed holding `0x76`
     * then seven NULs there, so this write does overwrite something whose purpose is unknown. A
     * printer has been observed accepting a tag with those bytes zeroed.
     */
    private fun reserveFor(serial: String): String = serial.padEnd(RESERVE.count(), '0')

    /**
     * Parses a payload string back into structured fields.
     *
     * [MappedFields.spoolmanSpoolId] comes from [RESERVE_SPOOL_ID], never from the serial number.
     * The two hold the same value on a tag this app wrote in full, but [withSpoolId] leaves the
     * serial naming the previous spool, and a genuine Creality tag carries an unrelated serial with
     * an empty reserve — so the reserve is the only field that answers "which spool is this?"
     * (DESIGN.md DEC-08). The serial is surfaced verbatim as [DecodedPayload.serialNumber] and is
     * not validated: nothing resolves a spool from it, so rejecting a tag over its content would
     * only cost the user a readable tag.
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

        val reserveId = payload.substring(RESERVE_SPOOL_ID)
        val spoolId = reserveId.takeIf { id -> id.all { it in '0'..'9' } }?.toInt()
            ?: throw TagDecodeException("reserve spool ID must be digits, was '$reserveId'")

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

        return DecodedPayload(
            fields = fields,
            serialNumber = payload.substring(SERIAL_NUMBER),
            reserve = payload.substring(RESERVE),
        )
    }
}

/**
 * [MappedFields] plus the raw serial-number and reserve bytes. Both are surfaced separately rather
 * than folded into the model because neither is a user-facing field — both are derived from the
 * spool ID on write, and on read they are only interesting for diagnosing what a tag actually
 * contains (e.g. comparing against the `000000` + `0x76` + NULs a genuine Creality tag was observed
 * to hold, or seeing which spool a tag named before an ID-only overwrite).
 *
 * [serialNumber] is `[28, 34)` exactly as the tag holds it. It is **not** a second opinion about the
 * spool ID: [fields] carries the one the printer resolves, read from the reserve.
 *
 * [reserve] is all of `[34, 48)`, its leading 6 characters therefore repeating
 * [MappedFields.spoolmanSpoolId].
 *
 * Both may contain non-printable bytes; escape them before display.
 */
data class DecodedPayload(
    val fields: MappedFields,
    val serialNumber: String,
    val reserve: String,
)

class TagDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
