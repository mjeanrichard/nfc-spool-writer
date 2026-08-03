package ch.jeanrichard.nfcspoolwriter.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.jeanrichard.nfcspoolwriter.AppContainer
import ch.jeanrichard.nfcspoolwriter.BuildConfig
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.ui.confirm.ConfirmScreen
import ch.jeanrichard.nfcspoolwriter.ui.debug.TagHarnessScreen
import ch.jeanrichard.nfcspoolwriter.ui.read.ReadTagScreen
import ch.jeanrichard.nfcspoolwriter.ui.settings.SettingsScreen
import ch.jeanrichard.nfcspoolwriter.ui.spoollist.SpoolListScreen
import ch.jeanrichard.nfcspoolwriter.ui.write.WriteScreen
import ch.jeanrichard.nfcspoolwriter.ui.viewmodel.confirmViewModelFactory
import ch.jeanrichard.nfcspoolwriter.ui.viewmodel.harnessViewModelFactory
import ch.jeanrichard.nfcspoolwriter.ui.viewmodel.readTagViewModelFactory
import ch.jeanrichard.nfcspoolwriter.ui.viewmodel.settingsViewModelFactory
import ch.jeanrichard.nfcspoolwriter.ui.viewmodel.spoolListViewModelFactory
import ch.jeanrichard.nfcspoolwriter.ui.viewmodel.writeViewModelFactory

/**
 * The write flow: spool list → confirm → write, with settings reachable throughout.
 *
 * Screens are addressed by spool ID rather than by passing objects between them, so each screen
 * re-reads and re-maps from Spoolman. That costs an extra request but means the values a user confirms —
 * and then writes — are current, rather than a snapshot from whenever the list was last loaded.
 *
 * [READ] hangs off the spool list rather than off that flow: checking a tag of unknown provenance
 * starts from the tag, not from a spool (REQUIREMENTS.md §6).
 */
object Routes {
    const val SPOOLS = "spools"
    const val SETTINGS = "settings"
    const val READ = "read"
    const val HARNESS = "harness"
    const val CONFIRM = "confirm/{spoolId}"
    const val WRITE = "write/{spoolId}"

    const val ARG_SPOOL_ID = "spoolId"

    fun confirm(spoolId: Int) = "confirm/$spoolId"
    fun write(spoolId: Int) = "write/$spoolId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleFor(route))) },
                navigationIcon = {
                    // Same destination as the system back gesture, which navigation-compose already
                    // wires to the back stack.
                    if (route != null && route != Routes.SPOOLS) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    if (route == Routes.SPOOLS) {
                        OverflowMenu(
                            onReadTag = { navController.navigate(Routes.READ) },
                            onSettings = { navController.navigate(Routes.SETTINGS) },
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SPOOLS,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.SPOOLS) {
                SpoolListScreen(
                    viewModel = viewModel(factory = spoolListViewModelFactory(container)),
                    onSpoolSelected = { navController.navigate(Routes.confirm(it)) },
                    onConfigureServer = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel(factory = settingsViewModelFactory(container)),
                    // The single place the harness is gated: in a release build there is neither an
                    // entry point nor a registered route, so it cannot be reached at all.
                    onOpenHarness = if (BuildConfig.DEBUG) {
                        { navController.navigate(Routes.HARNESS) }
                    } else {
                        null
                    },
                )
            }

            composable(Routes.READ) {
                ReadTagScreen(viewModel = viewModel(factory = readTagViewModelFactory(container)))
            }

            if (BuildConfig.DEBUG) {
                composable(Routes.HARNESS) {
                    TagHarnessScreen(
                        viewModel = viewModel(factory = harnessViewModelFactory(container)),
                    )
                }
            }

            composable(
                route = Routes.CONFIRM,
                arguments = listOf(navArgument(Routes.ARG_SPOOL_ID) { type = NavType.IntType }),
            ) { entry ->
                val spoolId = entry.arguments!!.getInt(Routes.ARG_SPOOL_ID)
                ConfirmScreen(
                    viewModel = viewModel(
                        // Keyed by spool so navigating to a different spool doesn't reuse the
                        // previous spool's ViewModel.
                        key = "confirm-$spoolId",
                        factory = confirmViewModelFactory(container, spoolId),
                    ),
                    onWrite = { navController.navigate(Routes.write(spoolId)) },
                )
            }

            composable(
                route = Routes.WRITE,
                arguments = listOf(navArgument(Routes.ARG_SPOOL_ID) { type = NavType.IntType }),
            ) { entry ->
                val spoolId = entry.arguments!!.getInt(Routes.ARG_SPOOL_ID)
                WriteScreen(
                    viewModel = viewModel(
                        key = "write-$spoolId",
                        factory = writeViewModelFactory(container, spoolId),
                    ),
                    // Back to the list, dropping confirm+write so the flow can start cleanly again.
                    onDone = {
                        navController.popBackStack(route = Routes.SPOOLS, inclusive = false)
                    },
                )
            }
        }
    }
}

/** The standard Android overflow: destinations that are not part of the write flow live in here. */
@Composable
private fun OverflowMenu(onReadTag: () -> Unit, onSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.action_more),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_read_tag)) },
            onClick = {
                expanded = false
                onReadTag()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_settings)) },
            onClick = {
                expanded = false
                onSettings()
            },
        )
    }
}

private fun titleFor(route: String?): Int = when (route) {
    Routes.SETTINGS -> R.string.title_settings
    Routes.READ -> R.string.title_read
    Routes.HARNESS -> R.string.title_harness
    Routes.CONFIRM -> R.string.title_confirm
    Routes.WRITE -> R.string.title_write
    else -> R.string.title_spools
}
