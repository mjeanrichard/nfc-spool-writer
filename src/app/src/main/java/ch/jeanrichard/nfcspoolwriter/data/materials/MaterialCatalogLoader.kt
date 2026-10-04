package ch.jeanrichard.nfcspoolwriter.data.materials

import android.content.Context
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry

/**
 * Reads the built-in catalog asset. The only Android-dependent part of the material layer — kept
 * separate so [MaterialCatalog] and all matching logic stay JVM-testable.
 */
object MaterialCatalogLoader {

    const val ASSET_NAME = "materials.json"

    fun loadBuiltIn(context: Context): List<MaterialEntry> =
        MaterialCatalog.parseBuiltIn(
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        )
}
