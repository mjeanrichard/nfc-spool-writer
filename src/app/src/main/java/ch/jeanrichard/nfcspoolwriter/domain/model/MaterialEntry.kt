package ch.jeanrichard.nfcspoolwriter.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One entry in the material catalog — the 5-digit ID that goes in payload field `[12,17)`.
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
    /**
     * Where this entry comes from. Not persisted: it is a property of the merged catalog, assigned
     * when the built-in list and the user's changes are combined, so the same values stored as an
     * override and as a custom entry differ only here.
     */
    @Transient val source: MaterialSource = MaterialSource.BUILT_IN,
) {
    val isGeneric: Boolean get() = brand.equals(GENERIC_BRAND, ignoreCase = true)

    /** The catalog values alone, for deciding whether an edit actually changed anything. */
    fun values(): MaterialEntry = copy(source = MaterialSource.BUILT_IN)

    companion object {
        const val GENERIC_BRAND = "Generic"

        const val ID_LENGTH = 5

        fun isValidId(id: String): Boolean = id.length == ID_LENGTH && id.all { it.isDigit() }
    }
}

enum class MaterialSource {
    /** Shipped with the app, unchanged. */
    BUILT_IN,

    /** Shipped with the app, but the user has changed its values. */
    EDITED,

    /** Added by the user; not in the shipped list at all. */
    CUSTOM,
}

/** Wrapper matching the bundled `materials.json` shape. */
@Serializable
internal data class MaterialCatalogFile(
    val materials: List<MaterialEntry>,
    @SerialName("_comment") val comment: List<String> = emptyList(),
)
