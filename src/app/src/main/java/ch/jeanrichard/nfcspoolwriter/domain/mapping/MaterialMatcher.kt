package ch.jeanrichard.nfcspoolwriter.domain.mapping

import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.data.materials.normalizeMaterial
import ch.jeanrichard.nfcspoolwriter.domain.model.Filament
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry

/**
 * Maps a Spoolman filament onto a Creality catalog entry (DESIGN.md DEC-04, "unmappable materials fail closed").
 *
 * The hard part isn't matching, it's **failing safely**. The printer uses the material ID to pick
 * nozzle and bed temperatures, so a wrong guess doesn't degrade gracefully — it prints ABS settings
 * onto PLA. Every rule below is therefore either an exact match or a documented same-family
 * substitution, and anything else is [MaterialMatch.NoMatch] for the user to resolve rather than a
 * best guess.
 */
class MaterialMatcher(private val catalog: MaterialCatalog) {

    fun match(filament: Filament): MaterialMatch {
        // 1. The filament is literally a catalog product, e.g. a genuine "Hyper PLA" spool tracked in
        //    Spoolman. Use its real profile rather than a generic one.
        filament.name?.let { name ->
            catalog.findByExactName(name)?.let { return MaterialMatch.Exact(it) }
        }

        val material = filament.material?.takeIf { it.isNotBlank() }
            ?: return MaterialMatch.NoMatch(null, "Spoolman has no material set for this filament")

        // 2. Exact family match against the Generic profiles — the common case.
        catalog.findGenericByType(material)?.let { return MaterialMatch.Exact(it) }

        // 3. Known spelling variants and trade names that mean an existing family.
        ALIASES[material.normalizeMaterial()]?.let { family ->
            catalog.findGenericByType(family)?.let {
                return MaterialMatch.Fallback(it, "'$material' treated as $family")
            }
        }

        // 4. Modifier stripping: vendors append grades and marketing suffixes to a family name that
        //    the catalog does carry. "PLA+" and "PETG Pro" are still PLA and PETG.
        stripModifiers(material)?.let { base ->
            catalog.findGenericByType(base)?.let {
                return MaterialMatch.Fallback(it, "'$material' reduced to $base")
            }
            ALIASES[base.normalizeMaterial()]?.let { family ->
                catalog.findGenericByType(family)?.let {
                    return MaterialMatch.Fallback(it, "'$material' reduced to $base, treated as $family")
                }
            }
        }

        // 5. A filled variant the catalog lacks, e.g. "PLA-GF". Dropping the filler keeps the polymer
        //    and therefore the temperatures roughly right, which is the last substitution defensible
        //    without guessing.
        stripFiller(material)?.let { base ->
            catalog.findGenericByType(base)?.let {
                return MaterialMatch.Fallback(
                    it,
                    "'$material' has no filled profile in the catalog; using unfilled $base",
                )
            }
        }

        return MaterialMatch.NoMatch(
            material,
            "'$material' has no catalog entry and no same-family substitute",
        )
    }

    /** Trailing grade/marketing modifiers that don't change the polymer family. */
    private fun stripModifiers(material: String): String? {
        val trimmed = material.trim()
        val base = MODIFIER_SUFFIXES.firstNotNullOfOrNull { suffix ->
            trimmed.takeIf { it.length > suffix.length && it.endsWith(suffix, ignoreCase = true) }
                ?.dropLast(suffix.length)
                ?.trim()
                ?.trimEnd('-', ' ')
        }
        return base?.takeIf { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
    }

    /** Drops a carbon/glass-fibre suffix to reach the unfilled polymer. */
    private fun stripFiller(material: String): String? {
        val normalized = material.normalizeMaterial()
        val base = FILLER_SUFFIXES.firstNotNullOfOrNull { suffix ->
            normalized.takeIf { it.length > suffix.length && it.endsWith(suffix) }
                ?.dropLast(suffix.length)
        }
        return base?.takeIf { it.isNotBlank() }
    }

    private companion object {
        /**
         * Spellings and trade names that unambiguously mean a family the catalog carries. Only
         * entries where the substitution is thermally sound belong here — this table is the place a
         * bad mapping would do real damage, so additions need a reason, not a hunch.
         */
        val ALIASES: Map<String, String> = mapOf(
            // Nylon is PA.
            "NYLON" to "PA",
            "PA6" to "PA",
            "PA12" to "PA",
            "PA612" to "PA",
            // Co-polyesters print as PETG.
            "CPE" to "PETG",
            "PETGHF" to "PETG",
            // Flexibles: TPE and the durometer-suffixed TPUs all use the TPU profile.
            "TPE" to "TPU",
            "TPU95A" to "TPU",
            "TPU85A" to "TPU",
            "TPU98A" to "TPU",
            "TPU64D" to "TPU",
            // Filled PLAs whose filler doesn't change the polymer's temperatures.
            "PLAWOOD" to "PLA",
            "WOOD" to "PLA",
            "PLAMATTE" to "PLA",
            "PLAGLOW" to "PLA",
            "PLAMARBLE" to "PLA",
            "SILK" to "PLA-Silk",
            "PLASILK" to "PLA-Silk",
            // Water-soluble supports.
            "PVOH" to "PVA",
        )

        /** Suffixes indicating a grade rather than a different polymer. */
        val MODIFIER_SUFFIXES = listOf("+", " PLUS", " PRO", " HF", " MAX", " ULTRA", " TOUGH")

        val FILLER_SUFFIXES = listOf("CF", "GF", "GLASSFIBER", "CARBONFIBER")
    }
}

sealed interface MaterialMatch {

    val entry: MaterialEntry?

    /** The catalog names this material, or its family, exactly. */
    data class Exact(override val entry: MaterialEntry) : MaterialMatch

    /**
     * A same-family substitution. Not silent: [reason] is shown on the confirm screen so the user sees
     * that the printer will apply a profile for something slightly different.
     */
    data class Fallback(override val entry: MaterialEntry, val reason: String) : MaterialMatch

    /**
     * No defensible mapping. The user must choose a material explicitly — writing a guess would give
     * the printer wrong temperatures with no warning.
     */
    data class NoMatch(val requested: String?, val reason: String) : MaterialMatch {
        override val entry: MaterialEntry? get() = null
    }
}
