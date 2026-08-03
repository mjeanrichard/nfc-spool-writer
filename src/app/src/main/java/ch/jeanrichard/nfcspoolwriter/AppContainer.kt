package ch.jeanrichard.nfcspoolwriter

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogLoader
import ch.jeanrichard.nfcspoolwriter.data.nfc.AndroidNfcCapabilities
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.nfc.NfcCapabilities
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagDiagnostics
import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanApiClient
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.createSpoolmanHttpClient
import ch.jeanrichard.nfcspoolwriter.domain.mapping.FieldMappingService
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MaterialMatcher
import io.ktor.client.HttpClient

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

/**
 * Hand-rolled dependency container — the app is small enough that constructor wiring behind a
 * few `by lazy` singletons is easier to follow than a DI framework (DESIGN.md §1).
 *
 * Everything is lazy so that nothing (notably the HTTP client's OkHttp engine) is constructed
 * during `Application.onCreate`.
 */
class AppContainer(private val applicationContext: Context) {

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext.settingsDataStore)
    }

    val spoolmanHttpClient: HttpClient by lazy { createSpoolmanHttpClient() }

    val nfcCapabilities: NfcCapabilities by lazy {
        AndroidNfcCapabilities(applicationContext.packageManager)
    }

    /**
     * Evaluated on demand rather than cached: NFC capabilities are static for a device, but reading
     * them is cheap and a cached value would be one more thing to reason about at startup.
     */
    val deviceCompatibility: DeviceCompatibility
        get() = DeviceCompatibility.of(nfcCapabilities)

    val tagReaderWriter: MifareTagReaderWriter by lazy { MifareTagReaderWriter() }

    /** Diagnostic-only raw tag dumps, for the manual hardware checklist. */
    val tagDiagnostics: TagDiagnostics by lazy { TagDiagnostics() }

    /** Parsed once from the bundled asset; the catalog is static data. */
    val materialCatalog: MaterialCatalog by lazy {
        MaterialCatalogLoader.load(applicationContext)
    }

    val fieldMappingService: FieldMappingService by lazy {
        FieldMappingService(MaterialMatcher(materialCatalog))
    }

    val spoolmanApiClient: SpoolmanApiClient by lazy {
        SpoolmanApiClient(spoolmanHttpClient)
    }

    val spoolmanRepository: SpoolmanRepository by lazy {
        SpoolmanRepository(spoolmanApiClient, settingsRepository)
    }
}
