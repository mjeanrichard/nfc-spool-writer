package ch.jeanrichard.nfcspoolwriter

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogLoader
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.data.nfc.AndroidNfcCapabilities
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.nfc.NfcCapabilities
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagDiagnostics
import ch.jeanrichard.nfcspoolwriter.data.report.CrashLog
import ch.jeanrichard.nfcspoolwriter.data.report.ErrorReportComposer
import ch.jeanrichard.nfcspoolwriter.data.report.ReportEnvironment
import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanApiClient
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.createSpoolmanHttpClient
import ch.jeanrichard.nfcspoolwriter.domain.mapping.FieldMappingService
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import java.io.File

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

    /** Diagnostic-only raw tag dumps, for validating tag behaviour on real hardware. */
    val tagDiagnostics: TagDiagnostics by lazy { TagDiagnostics() }

    /** The built-in list is parsed once; the user's changes come from the settings store. */
    val materialCatalogRepository: MaterialCatalogRepository by lazy {
        MaterialCatalogRepository(
            builtIn = MaterialCatalogLoader.loadBuiltIn(applicationContext),
            dataStore = applicationContext.settingsDataStore,
        )
    }

    val fieldMappingService: FieldMappingService by lazy {
        FieldMappingService(materialCatalogRepository.catalog)
    }

    val spoolmanApiClient: SpoolmanApiClient by lazy {
        SpoolmanApiClient(spoolmanHttpClient)
    }

    val spoolmanRepository: SpoolmanRepository by lazy {
        SpoolmanRepository(spoolmanApiClient, settingsRepository)
    }

    /**
     * The one exception to staying untouched in `Application.onCreate`: the crash handler needs it
     * before anything else can fail, and it is no more than a file path.
     *
     * In no-backup storage, because a crash belongs to this install and must not follow a restore.
     */
    val crashLog: CrashLog by lazy {
        CrashLog(
            file = File(applicationContext.noBackupFilesDir, "crash-report.txt"),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    val errorReportComposer: ErrorReportComposer by lazy {
        ErrorReportComposer(
            environment = ReportEnvironment(
                appVersion = BuildConfig.VERSION_NAME,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
            ),
            serverAddresses = { listOfNotNull(settingsRepository.spoolmanBaseUrl.first()) },
        )
    }
}
