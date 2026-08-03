package ch.jeanrichard.nfcspoolwriter.testsupport

import java.io.File

/**
 * Reads a real `src/main/assets` file from disk so JVM unit tests can exercise the data the app
 * actually ships. Android's `AssetManager` isn't available here, and a fixture copy would defeat the
 * point — the bundled catalog is data the printer depends on, so it is the thing worth testing.
 */
fun bundledAsset(name: String): String {
    val candidates = listOf(
        // Gradle runs unit tests with the module directory as the working directory; the fallbacks
        // cover being run from the repo or Gradle root instead.
        File("src/main/assets/$name"),
        File("app/src/main/assets/$name"),
        File("src/app/src/main/assets/$name"),
    )
    val file = candidates.firstOrNull { it.exists() }
        ?: error(
            "could not find asset '$name'. Looked in:\n" +
                candidates.joinToString("\n") { "  ${it.absolutePath}" }
        )
    return file.readText()
}

fun bundledMaterialCatalogJson(): String = bundledAsset("materials.json")
