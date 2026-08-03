package ch.jeanrichard.nfcspoolwriter.ui.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.jeanrichard.nfcspoolwriter.AppContainer
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareClassicSession
import ch.jeanrichard.nfcspoolwriter.ui.confirm.ConfirmViewModel
import ch.jeanrichard.nfcspoolwriter.ui.debug.TagHarnessViewModel
import ch.jeanrichard.nfcspoolwriter.ui.read.ReadTagViewModel
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
            materialCatalog = container.materialCatalog,
        )
    }
}

fun writeViewModelFactory(
    container: AppContainer,
    spoolId: Int,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        WriteViewModel(
            spoolId = spoolId,
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
                materialCatalog = container.materialCatalog,
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
