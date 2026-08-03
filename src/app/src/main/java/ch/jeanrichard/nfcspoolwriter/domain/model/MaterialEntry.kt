package ch.jeanrichard.nfcspoolwriter.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry in Creality's material catalog — the 5-digit ID that goes in payload field `[12,17)`.
 *
 * The printer looks this ID up in its own firmware database to choose print settings, so writing an
 * ID the firmware doesn't know means the spool gets no usable profile. That is why an unmatched
 * material is surfaced as an explicit failure rather than guessed at (see `MaterialMatcher`).
 */
@Serializable
data class MaterialEntry(
    val id: String,
    val name: String,
    val brand: String,
    /**
     * Material family used for matching, e.g. `PLA`, `PETG`, `HIPS`. Null where the catalog name
     * doesn't state it unambiguously — `CR-Wood` is presumably a filled PLA, but that's an assumption
     * and a wrong family means wrong temperatures.
     */
    val type: String? = null,
    val deprecated: Boolean = false,
) {
    val isGeneric: Boolean get() = brand.equals(GENERIC_BRAND, ignoreCase = true)

    companion object {
        const val GENERIC_BRAND = "Generic"
    }
}

/** Wrapper matching the bundled `materials.json` shape. */
@Serializable
internal data class MaterialCatalogFile(
    val materials: List<MaterialEntry>,
    @SerialName("_comment") val comment: List<String> = emptyList(),
)
