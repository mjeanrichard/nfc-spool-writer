package ch.jeanrichard.nfcspoolwriter.data.materials

import android.content.Context

/**
 * Reads the bundled catalog asset. The only Android-dependent part of the material layer — kept
 * separate so [MaterialCatalog] and all matching logic stay JVM-testable.
 */
object MaterialCatalogLoader {

    const val ASSET_NAME = "materials.json"

    fun load(context: Context): MaterialCatalog =
        MaterialCatalog.fromJson(
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        )
}
