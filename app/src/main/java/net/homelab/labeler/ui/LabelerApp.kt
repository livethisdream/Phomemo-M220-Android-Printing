package net.homelab.labeler.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.homelab.labeler.LabelerViewModel

private object Route {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PRINTER = "printer"
    const val SCAN = "scan"
    const val STATUS = "status"
    const val ENDPOINTS = "endpoints"
    const val LABEL_SIZE = "label"
    const val QUALITY = "quality"
}

/**
 * Navigation shell.
 *
 * Screens that need the printer trigger their own load on arrival rather than
 * making the caller remember to - landing on a status screen that says nothing
 * until you press a button is a worse default than one that just asks.
 */
@Composable
fun LabelerApp(
    vm: LabelerViewModel,
    ensurePermission: (onGranted: () -> Unit) -> Unit
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    JobReporter(vm, snackbar)

    NavHost(navController = nav, startDestination = Route.HOME) {
        composable(Route.HOME) {
            HomeScreen(
                vm = vm,
                snackbar = snackbar,
                onSettings = { nav.navigate(Route.SETTINGS) }
            )
        }

        composable(Route.SETTINGS) {
            SettingsScreen(
                vm = vm,
                snackbar = snackbar,
                onBack = { nav.popBackStack() },
                onPrinter = { nav.navigate(Route.PRINTER) },
                onLabel = { nav.navigate(Route.LABEL_SIZE) },
                onQuality = { nav.navigate(Route.QUALITY) }
            )
        }

        composable(Route.PRINTER) {
            PrinterScreen(
                vm = vm,
                snackbar = snackbar,
                onBack = { nav.popBackStack() },
                onScan = { ensurePermission { nav.navigate(Route.SCAN) } },
                onStatus = { ensurePermission { nav.navigate(Route.STATUS) } },
                onDiagnostics = { ensurePermission { nav.navigate(Route.ENDPOINTS) } }
            )
        }

        composable(Route.SCAN) {
            LaunchOnce { vm.scan() }
            ScanScreen(
                vm = vm,
                snackbar = snackbar,
                onBack = { nav.popBackStack() },
                onPicked = { nav.popBackStack() }
            )
        }

        composable(Route.STATUS) {
            LaunchOnce { vm.readStatus() }
            StatusScreen(vm, snackbar) { nav.popBackStack() }
        }

        composable(Route.ENDPOINTS) {
            LaunchOnce { vm.listEndpoints() }
            EndpointScreen(vm, snackbar) { nav.popBackStack() }
        }

        composable(Route.LABEL_SIZE) {
            LabelSizeScreen(vm, snackbar) { nav.popBackStack() }
        }

        composable(Route.QUALITY) {
            QualityScreen(vm, snackbar) { nav.popBackStack() }
        }
    }
}

/** Fires once per navigation to a destination, not on every recomposition. */
@Composable
private fun LaunchOnce(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}
