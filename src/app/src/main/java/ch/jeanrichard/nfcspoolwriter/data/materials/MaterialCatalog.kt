package ch.jeanrichard.nfcspoolwriter.data.materials

import ch.jeanrichard.nfcspoolwriter.data.spoolman.AppJson
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialCatalogFile
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry

/**
 * The bundled Creality material catalog, in memory.
 *
 * Pure and Android-free — parsing takes a JSON string, so the whole catalog and every lookup is unit
 * testable against the real bundled asset. Reading the asset itself is [MaterialCatalogLoader]'s job.
 */
class MaterialCatalog(entries: List<MaterialEntry>) {

    /** Every entry, in catalog order. */
    val all: List<MaterialEntry> = entries.toList()

    /**
     * Entries safe to select automatically: deprecated profiles are excluded, since the firmware may
     * treat them as unsupported and they exist only so a tag already carrying one can be read back.
     */
    val selectable: List<MaterialEntry> = all.filterNot { it.deprecated }

    /** The `Generic` profiles — the fallback target set for third-party filament. */
    val generics: List<MaterialEntry> = selectable.filter { it.isGeneric }

    private val byId: Map<String, MaterialEntry> = all.associateBy { it.id }

    fun findById(id: String): MaterialEntry? = byId[id]

    /**
     * The `Generic` profile for a material family, e.g. `PLA` → `00001`.
     *
     * Generic is preferred over a brand-specific profile for automatic selection because the catalog
     * holds several profiles per family (five Creality PLAs alone) with different temperatures, and
     * picking between them from a Spoolman material string would be a guess with print-quality
     * consequences. Brand matching is available via [findByExactName] where the name is unambiguous.
     */
    fun findGenericByType(type: String): MaterialEntry? =
        generics.firstOrNull { it.type.equalsNormalized(type) }

    /**
     * An exact catalog-name match, e.g. a Spoolman filament literally named `Hyper PLA`. Lets a
     * genuine Creality spool get its real profile instead of the generic one.
     */
    fun findByExactName(name: String): MaterialEntry? =
        selectable.firstOrNull { it.name.equalsNormalized(name) }

    /** All entries of a family, generic first. */
    fun findAllByType(type: String): List<MaterialEntry> =
        selectable.filter { it.type.equalsNormalized(type) }
            .sortedByDescending { it.isGeneric }

    companion object {
        fun fromJson(json: String): MaterialCatalog =
            MaterialCatalog(AppJson.decodeFromString<MaterialCatalogFile>(json).materials)
    }
}

/**
 * Compares material names/types ignoring case and the separator punctuation that varies between
 * sources — Spoolman is free text, so `PLA-CF`, `PLA CF` and `pla_cf` must all match `PLA-CF`.
 */
internal fun String?.equalsNormalized(other: String?): Boolean =
    this?.normalizeMaterial() == other?.normalizeMaterial()

/**
 * Drops separators and case, but **keeps `+`**: a dash or space between `PLA` and `CF` is noise, while
 * the `+` in `PLA+` is a grade claim. Folding it away would make `PLA+` an *exact* match for Generic
 * PLA and so hide the substitution from the confirm screen — the user should be told that a `PLA+`
 * spool is being written with the plain PLA profile.
 */
internal fun String.normalizeMaterial(): String =
    uppercase().filter { it.isLetterOrDigit() || it == '+' }
