package ch.jeanrichard.nfcspoolwriter.domain.model

/**
 * The structured tag content, after a Spoolman spool has been mapped onto Creality's fixed field
 * encodings. This is what the confirm screen shows the user and what `TagCodec` serializes into the
 * 96-character payload string (TAG_FORMAT_SPEC.md §9).
 *
 * Invariants are enforced here rather than in the codec so that a malformed payload cannot exist in
 * the first place — by the time a value reaches the NFC layer it is too late to discover that the
 * colour has five hex digits.
 *
 * **Field partition.** The first 17 characters divide as
 * `batch(3) | date(5) | supplier(4) | material(5)`, the offsets each property below documents itself
 * against. Where the boundaries fall decides what can vary independently, so it is worth stating
 * explicitly: the widely quoted vendor code `0276` straddles two of these fields and is therefore not
 * a field at all (TAG_FORMAT_SPEC.md §9, and [CREALITY_SUPPLIER_ID]).
 */
data class MappedFields(
    /**
     * `[0,3)` — batch number. Not meaningful for this app's purposes, so it is written as a constant.
     */
    val batchNumber: String = DEFAULT_BATCH_NUMBER,
    /**
     * `[3,8)` — a date code, reportedly `YYMDD`.
     *
     * **Stored opaquely rather than computed**, because the reported format does not parse against
     * observed tags: both a genuine Creality tag (`25027`) and the community's reference value
     * (`24027`) carry `0` in the month position, which is invalid 1-indexed. It parses only as a
     * 0-indexed month or as a day-of-year, and two samples cannot distinguish those (both give
     * 27 January). Writing a wrong date is worse than writing a proven constant, so this defaults to
     * [DEFAULT_DATE_CODE] until the encoding is confirmed.
     */
    val dateCode: String = DEFAULT_DATE_CODE,
    /** `[8,12)` — supplier ID. See [CREALITY_SUPPLIER_ID]. */
    val supplierId: String = CREALITY_SUPPLIER_ID,
    /** `[12,17)` — the 5-digit material-catalog ID. */
    val filamentCatalogId: String,
    /** 6 hex digits `RRGGBB`, without the structural `0` prefix the codec adds. Normalized upper. */
    val colorRgb: String,
    val weight: WeightBucket,
    /**
     * Spoolman's spool ID. Written to both the serial-number field and the reserve's leading 6
     * characters (§9) — the latter is the one a printer resolves the spool from.
     */
    val spoolmanSpoolId: Int,
) {
    init {
        require(batchNumber.length == 3 && batchNumber.all { it.isAsciiPrintable() }) {
            "batchNumber must be 3 printable ASCII characters, was '$batchNumber'"
        }
        require(dateCode.length == 5 && dateCode.all { it.isAsciiPrintable() }) {
            "dateCode must be 5 printable ASCII characters, was '$dateCode'"
        }
        require(supplierId.length == 4 && supplierId.all { it.isAsciiPrintable() }) {
            "supplierId must be 4 printable ASCII characters, was '$supplierId'"
        }
        require(filamentCatalogId.length == 5 && filamentCatalogId.all { it.isDigit() }) {
            "filamentCatalogId must be 5 digits, was '$filamentCatalogId'"
        }
        require(colorRgb.length == 6 && colorRgb.all { it.isHexDigit() }) {
            "colorRgb must be 6 hex digits, was '$colorRgb'"
        }
        require(spoolmanSpoolId in 0..MAX_SPOOL_ID) {
            "spoolmanSpoolId must fit in 6 digits (0..$MAX_SPOOL_ID), was $spoolmanSpoolId"
        }
    }

    /** Colour with case normalized, so an encode/decode round-trip compares equal. */
    val normalizedColorRgb: String get() = colorRgb.uppercase()

    companion object {
        /**
         * The three defaults below concatenate to `AB1` + `24027` + `6A21` = `AB1240276A21`, which is
         * exactly the prefix every community implementation writes and that printers are reported to
         * accept. They are defaults rather than computed values so that departing from a
         * proven-accepted byte sequence takes a deliberate act.
         */
        const val DEFAULT_BATCH_NUMBER = "AB1"

        /** See [dateCode] for why this is a constant rather than today's date. */
        const val DEFAULT_DATE_CODE = "24027"

        /**
         * Creality's supplier ID under the current field partition.
         *
         * Note this supersedes the widely-repeated claim that Creality's vendor code is `0276`: under
         * this partition no field equals `0276` — it is an artifact of the date field ending `027`
         * meeting a supplier ID starting `6`. Whether third-party/Spoolman-sourced spools should
         * reuse this value is settled in DESIGN.md DEC-03.
         */
        const val CREALITY_SUPPLIER_ID = "6A21"

        /** The serial-number field is 6 ASCII digits (§9). */
        const val MAX_SPOOL_ID = 999_999
    }
}

private fun Char.isAsciiPrintable(): Boolean = this in ' '..'~'

/** Shared by every place that validates or parses `RRGGBB` colour input. */
internal fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
