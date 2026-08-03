package ch.jeanrichard.nfcspoolwriter.domain.mapping

import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket

/**
 * Turns a Spoolman [Spool] into the [MappedFields] that get written to a tag.
 *
 * Spoolman's model and Creality's fixed encodings don't line up, so this is best-effort by nature
 * (REQUIREMENTS.md §4). Every approximation it makes is reported in [MappingResult.notes] so the
 * confirm screen can show the user what was assumed before it's burned onto a tag — that visibility is
 * the whole reason the confirm step exists.
 */
class FieldMappingService(
    private val materialMatcher: MaterialMatcher,
) {

    fun map(spool: Spool): MappingResult {
        val notes = mutableListOf<String>()

        val materialMatch = materialMatcher.match(spool.filament)
        if (materialMatch is MaterialMatch.Fallback) notes += materialMatch.reason
        val material = materialMatch.entry
            ?: return MappingResult.Unmappable(
                reason = (materialMatch as MaterialMatch.NoMatch).reason,
                materialMatch = materialMatch,
            )

        val weight = resolveWeight(spool, notes)
        val colour = resolveColour(spool, notes)

        return MappingResult.Mapped(
            fields = MappedFields(
                supplierId = SUPPLIER_ID,
                filamentCatalogId = material.id,
                colorRgb = colour,
                weight = weight,
                spoolmanSpoolId = spool.id,
            ),
            materialMatch = materialMatch,
            notes = notes,
        )
    }

    /**
     * Uses the **full** spool weight, not the remaining weight: the bucket encodes the spool's nominal
     * size, which doesn't change as filament is consumed.
     */
    private fun resolveWeight(spool: Spool, notes: MutableList<String>): WeightBucket {
        val grams = spool.filament.fullSpoolWeightGrams
        if (grams == null || grams <= 0) {
            notes += "Spoolman has no full-spool weight; assuming ${DEFAULT_WEIGHT.grams} g"
            return DEFAULT_WEIGHT
        }
        val bucket = WeightBucket.nearestTo(grams.toInt())
        if (bucket.grams != grams.toInt()) {
            notes += "${grams.toInt()} g rounded to the nearest bucket, ${bucket.grams} g"
        }
        return bucket
    }

    /**
     * Spoolman colours are free-form enough to need normalizing: a leading `#`, lowercase hex, or an
     * 8-digit value with an alpha channel are all possible. Alpha is dropped — the tag has no concept
     * of transparency, and the printer only shows a solid swatch.
     */
    private fun resolveColour(spool: Spool, notes: MutableList<String>): String {
        val raw = spool.filament.colorHex?.trim()?.removePrefix("#")
        if (raw.isNullOrEmpty()) {
            notes += "Spoolman has no colour; defaulting to $DEFAULT_COLOUR"
            return DEFAULT_COLOUR
        }
        if (!raw.all { it.isHexDigit() }) {
            notes += "Colour '$raw' is not valid hex; defaulting to $DEFAULT_COLOUR"
            return DEFAULT_COLOUR
        }
        return when (raw.length) {
            6 -> raw.uppercase()
            8 -> raw.take(6).uppercase().also {
                notes += "Colour '$raw' has an alpha channel; using RGB $it"
            }
            3 -> raw.map { "$it$it" }.joinToString("").uppercase().also {
                notes += "Short colour '$raw' expanded to $it"
            }
            else -> DEFAULT_COLOUR.also {
                notes += "Colour '$raw' has an unexpected length; defaulting to $it"
            }
        }
    }

    companion object {
        /**
         * **Decision (DESIGN.md DEC-03):** every spool is written with Creality's own supplier ID,
         * regardless of who actually made the filament.
         *
         * Reasoning: this is the only value observed on genuine tags and therefore the only one with any
         * evidence of being accepted; no registered ID exists for anyone else; and whether the field
         * affects printer behaviour at all is still unknown. Inventing a value would add an untested
         * variable for no benefit. Revisit if the printer turns out to reject or special-case it — the
         * field is a plain constant here, so changing it is a one-line edit.
         */
        const val SUPPLIER_ID = MappedFields.CREALITY_SUPPLIER_ID

        /** The commonest spool size, used only when Spoolman doesn't record one. */
        val DEFAULT_WEIGHT = WeightBucket.G1000

        /** Neutral mid-grey: obviously a placeholder on the printer's swatch, unlike black or white. */
        const val DEFAULT_COLOUR = "808080"
    }
}

sealed interface MappingResult {

    /** @param notes approximations made, for display on the confirm screen. */
    data class Mapped(
        val fields: MappedFields,
        val materialMatch: MaterialMatch,
        val notes: List<String>,
    ) : MappingResult

    /**
     * No tag can be written from this spool as-is. Currently only when the material can't be mapped —
     * everything else has a defensible default, but a material does not (see [MaterialMatcher]).
     */
    data class Unmappable(
        val reason: String,
        val materialMatch: MaterialMatch,
    ) : MappingResult
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
