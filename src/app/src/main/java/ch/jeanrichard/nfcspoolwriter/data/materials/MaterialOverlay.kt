package ch.jeanrichard.nfcspoolwriter.data.materials

import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import kotlinx.serialization.Serializable

/**
 * The user's changes to the material catalog, kept apart from the built-in list.
 *
 * Only the delta is stored, so an app update that ships new firmware IDs reaches the user without
 * disturbing what they changed, and reverting a single entry means dropping its override. The
 * merged result is a [MaterialCatalog]; see [MaterialCatalog.merged].
 */
@Serializable
data class MaterialOverlay(
    /** Entries the user added. Their IDs never collide with a built-in one. */
    val custom: List<MaterialEntry> = emptyList(),
    /** Built-in entries the user edited, keyed by the built-in ID they replace. */
    val overrides: Map<String, MaterialEntry> = emptyMap(),
) {
    val isEmpty: Boolean get() = custom.isEmpty() && overrides.isEmpty()

    companion object {
        val EMPTY = MaterialOverlay()
    }
}
