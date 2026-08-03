package ch.jeanrichard.nfcspoolwriter.data.spoolman

import ch.jeanrichard.nfcspoolwriter.domain.model.Filament
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import ch.jeanrichard.nfcspoolwriter.domain.model.Vendor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Spoolman's wire shapes, declaring only the fields this app reads.
 *
 * Kept separate from the domain models rather than deserializing straight into them, because almost
 * everything in Spoolman's API is optional while the domain model wants values it can rely on. This is
 * the one place that optionality gets resolved, so the mapping layer never has to guess whether a null
 * meant "unset" or "failed to parse".
 *
 * `AppJson` ignores unknown keys, so the many fields omitted here (prices, temperatures, timestamps,
 * `extra`) are skipped and a newer Spoolman keeps working.
 */
@Serializable
data class SpoolDto(
    val id: Int,
    val filament: FilamentDto,
    @SerialName("remaining_weight") val remainingWeight: Double? = null,
    val location: String? = null,
    @SerialName("lot_nr") val lotNumber: String? = null,
    val archived: Boolean = false,
) {
    fun toDomain(): Spool = Spool(
        id = id,
        filament = filament.toDomain(),
        remainingWeightGrams = remainingWeight,
        location = location?.takeIf { it.isNotBlank() },
        lotNumber = lotNumber?.takeIf { it.isNotBlank() },
        archived = archived,
    )
}

@Serializable
data class FilamentDto(
    val id: Int,
    val name: String? = null,
    val vendor: VendorDto? = null,
    val material: String? = null,
    @SerialName("color_hex") val colorHex: String? = null,
    /**
     * Spoolman's `weight` is the net filament weight of a **full** spool — not what is left. That is
     * exactly what the tag's weight bucket encodes, hence the clearer domain name.
     */
    val weight: Double? = null,
) {
    fun toDomain(): Filament = Filament(
        id = id,
        name = name?.takeIf { it.isNotBlank() },
        vendor = vendor?.toDomain(),
        material = material?.takeIf { it.isNotBlank() },
        colorHex = colorHex?.takeIf { it.isNotBlank() },
        fullSpoolWeightGrams = weight,
    )
}

@Serializable
data class VendorDto(
    val id: Int,
    val name: String,
) {
    fun toDomain(): Vendor = Vendor(id = id, name = name)
}
