package ch.jeanrichard.nfcspoolwriter.domain.model

/**
 * A Spoolman spool, reduced to what this app needs.
 *
 * Deliberately not Spoolman's wire shape: almost everything in its API is optional, and pushing that
 * optionality into the mapping layer is what produces silent wrong values on a tag. Phase 4's DTO
 * conversion is where nulls get resolved or rejected; by the time a [Spool] exists, [id] and
 * [filament] are known good.
 */
data class Spool(
    /** Spoolman's spool ID. Written to the tag's serial-number and reserve fields. */
    val id: Int,
    val filament: Filament,
    /** Grams of filament left. Not what the tag encodes — see [Filament.fullSpoolWeightGrams]. */
    val remainingWeightGrams: Double? = null,
    val location: String? = null,
    val lotNumber: String? = null,
    val archived: Boolean = false,
)

data class Filament(
    val id: Int,
    /** e.g. `PolyTerra PLA Blue`. Used for an exact catalog-name match before falling back to type. */
    val name: String? = null,
    val vendor: Vendor? = null,
    /** Free text from Spoolman, e.g. `PLA`, `PLA+`, `PETG`, `HIPS`. */
    val material: String? = null,
    /** 6 hex digits, no `#`. Spoolman may also carry an 8-digit value with alpha, or none at all. */
    val colorHex: String? = null,
    /**
     * Net filament weight of a **full** spool, in grams — this is what the tag's weight bucket
     * encodes, not the remaining weight. A half-used 1 kg spool is still a 1 kg spool as far as the
     * printer's profile is concerned.
     */
    val fullSpoolWeightGrams: Double? = null,
)

data class Vendor(
    val id: Int,
    val name: String,
)
