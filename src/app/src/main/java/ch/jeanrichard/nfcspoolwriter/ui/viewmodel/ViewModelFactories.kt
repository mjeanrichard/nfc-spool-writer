package ch.jeanrichard.nfcspoolwriter.ui.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.jeanrichard.nfcspoolwriter.AppContainer
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareClassicSession
import ch.jeanrichard.nfcspoolwriter.ui.confirm.ConfirmViewModel
import ch.jeanrichard.nfcspoolwriter.ui.debug.TagHarnessViewModel
import ch.jeanrichard.nfcspoolwriter.ui.materials.MaterialEditViewModel
import ch.jeanrichard.nfcspoolwriter.ui.materials.MaterialListViewModel
import ch.jeanrichard.nfcspoolwriter.ui.read.ReadTagViewModel
import ch.jeanrichard.nfcspoolwriter.ui.report.CrashReportViewModel
import ch.jeanrichard.nfcspoolwriter.ui.settings.SettingsViewModel
import ch.jeanrichard.nfcspoolwriter.ui.spoollist.SpoolListViewModel
import ch.jeanrichard.nfcspoolwriter.ui.write.WriteViewModel

/**
 * Manual ViewModel wiring, matching the hand-rolled [AppContainer] approach — no DI framework
 * (DESIGN.md §1). Collected in one file so the navigation graph stays about navigation.
 */

fun settingsViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            SettingsViewModel(
                settingsRepository = container.settingsRepository,
                spoolmanRepository = container.spoolmanRepository,
                materialCatalogRepository = container.materialCatalogRepository,
            )
        }
    }

fun spoolListViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            SpoolListViewModel(
                spoolmanRepository = container.spoolmanRepository,
                settingsRepository = container.settingsRepository,
            )
        }
    }

fun confirmViewModelFactory(
    container: AppContainer,
    spoolId: Int,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        ConfirmViewModel(
            spoolId = spoolId,
            spoolmanRepository = container.spoolmanRepository,
            fieldMappingService = container.fieldMappingService,
            materialCatalogRepository = container.materialCatalogRepository,
        )
    }
}

fun writeViewModelFactory(
    container: AppContainer,
    spoolId: Int,
    chosenMaterialId: String?,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        WriteViewModel(
            spoolId = spoolId,
            chosenMaterialId = chosenMaterialId,
            spoolmanRepository = container.spoolmanRepository,
            fieldMappingService = container.fieldMappingService,
            tagReaderWriter = container.tagReaderWriter,
            compatibility = container.deviceCompatibility,
            openSession = MifareClassicSession::open,
        )
    }
}

fun readTagViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            ReadTagViewModel(
                tagReaderWriter = container.tagReaderWriter,
                materialCatalogRepository = container.materialCatalogRepository,
                spoolmanRepository = container.spoolmanRepository,
                compatibility = container.deviceCompatibility,
                openSession = MifareClassicSession::open,
            )
        }
    }

fun harnessViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            TagHarnessViewModel(
                readerWriter = container.tagReaderWriter,
                diagnostics = container.tagDiagnostics,
                compatibility = container.deviceCompatibility,
            )
        }
    }

fun materialListViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MaterialListViewModel(materialCatalogRepository = container.materialCatalogRepository)
        }
    }

fun materialEditViewModelFactory(
    container: AppContainer,
    materialId: String?,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        MaterialEditViewModel(
            materialId = materialId,
            materialCatalogRepository = container.materialCatalogRepository,
        )
    }
}

fun crashReportViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { CrashReportViewModel(crashLog = container.crashLog) }
    }
