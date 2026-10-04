package ch.jeanrichard.nfcspoolwriter.data.materials

import ch.jeanrichard.nfcspoolwriter.data.spoolman.AppJson
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialCatalogFile
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialSource

/**
 * The material catalog in memory: the built-in list with the user's changes applied.
 *
 * Immutable — a new instance is built whenever the user's changes change, which
 * [MaterialCatalogRepository] does behind a flow. Pure and Android-free, so the whole catalog and every
 * lookup is unit testable against the real bundled asset.
 */
class MaterialCatalog(entries: List<MaterialEntry>) {

    /** Every entry, in catalog order: built-in entries first, then the user's additions. */
    val all: List<MaterialEntry> = entries.toList()

    /**
     * Entries safe to select automatically: deprecated profiles are excluded, since the firmware may
     * treat them as unsupported and they exist only so a tag already carrying one can be read back.
     */
    val selectable: List<MaterialEntry> = all.filterNot { it.deprecated }

    /** The `Generic` profiles — the fallback target set for third-party filament. */
    val generics: List<MaterialEntry> = selectable.filter { it.isGeneric }

    /** Distinct brands in order of first appearance, for suggesting one when editing an entry. */
    val brands: List<String> = all.map { it.brand }.distinct()

    /** Distinct families in order of first appearance, for suggesting one when editing an entry. */
    val families: List<String> = all.mapNotNull { it.type }.distinct()

    val hasUserChanges: Boolean = all.any { it.source != MaterialSource.BUILT_IN }

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
        /** The built-in catalog alone, as shipped in `materials.json`. */
        fun fromJson(json: String): MaterialCatalog = MaterialCatalog(parseBuiltIn(json))

        fun parseBuiltIn(json: String): List<MaterialEntry> =
            AppJson.decodeFromString<MaterialCatalogFile>(json).materials

        /**
         * Applies the user's changes to the built-in list. An override replaces the built-in entry
         * in place, keeping its ID and position; custom entries follow the built-in ones. A custom
         * entry whose ID has meanwhile become built-in (a newer app shipping it) is dropped in favour
         * of the built-in one, since the firmware's meaning for that ID is the one that counts.
         */
        fun merged(builtIn: List<MaterialEntry>, overlay: MaterialOverlay): MaterialCatalog {
            val builtInIds = builtIn.map { it.id }.toSet()
            val entries = builtIn.map { entry ->
                overlay.overrides[entry.id]
                    ?.copy(id = entry.id, source = MaterialSource.EDITED)
                    ?: entry.copy(source = MaterialSource.BUILT_IN)
            } + overlay.custom
                .filterNot { it.id in builtInIds }
                .map { it.copy(source = MaterialSource.CUSTOM) }
            return MaterialCatalog(entries)
        }
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
