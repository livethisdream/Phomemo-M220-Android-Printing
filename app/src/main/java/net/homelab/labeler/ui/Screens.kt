@file:OptIn(ExperimentalMaterial3Api::class)

package net.homelab.labeler.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.homelab.labeler.BleTransport
import net.homelab.labeler.LabelerViewModel
import net.homelab.labeler.PhomemoM220

// =============================================================================
// Shared scaffolding
// =============================================================================

@Composable
fun LabelerScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    snackbar: SnackbarHostState,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        content = content
    )
}

/**
 * Surfaces whatever the printer is doing. Every long operation reports through
 * one channel, so progress and failure look the same wherever they happen.
 */
@Composable
fun JobReporter(vm: LabelerViewModel, snackbar: SnackbarHostState) {
    val job = vm.job
    LaunchedEffect(job) {
        when (job) {
            is LabelerViewModel.Job.Done -> {
                snackbar.showSnackbar(job.message)
                vm.clearJob()
            }

            is LabelerViewModel.Job.Failed -> {
                snackbar.showSnackbar(job.message)
                vm.clearJob()
            }

            else -> Unit
        }
    }
}

@Composable
private fun BusyRow(what: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(what, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

// =============================================================================
// Home / preview
// =============================================================================

@Composable
fun HomeScreen(
    vm: LabelerViewModel,
    snackbar: SnackbarHostState,
    onSettings: () -> Unit
) {
    LabelerScaffold(
        title = "Label Printer",
        snackbar = snackbar,
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val bitmap = vm.preview
            if (bitmap != null) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Label preview",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                Text(
                    "${vm.labelWidthMm} x ${vm.labelHeightMm} mm",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                EmptyState()
            }

            val job = vm.job
            if (job is LabelerViewModel.Job.Busy) BusyRow(job.what)

            Button(
                onClick = { if (bitmap != null) vm.print() else vm.printTest() },
                enabled = job !is LabelerViewModel.Job.Busy && vm.printerMac != null,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Filled.Print, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (bitmap != null) "Print label" else "Print test label")
            }

            if (vm.printerMac == null) {
                Text(
                    "No printer selected yet. Open settings to find one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.Print,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Nothing to print yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Share a page from Chrome and it lands here as a QR label. " +
                    "Or print a test label to check the printer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =============================================================================
// Settings
// =============================================================================

@Composable
fun SettingsScreen(
    vm: LabelerViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onPrinter: () -> Unit,
    onLabel: () -> Unit,
    onQuality: () -> Unit
) {
    LabelerScaffold(title = "Settings", onBack = onBack, snackbar = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsRow(
                icon = { Icon(Icons.Filled.Bluetooth, null) },
                title = "Printer",
                subtitle = vm.printerName ?: vm.printerMac ?: "None selected",
                onClick = onPrinter
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Straighten, null) },
                title = "Label size",
                subtitle = "${vm.labelWidthMm} x ${vm.labelHeightMm} mm",
                onClick = onLabel
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Tune, null) },
                title = "Print quality",
                subtitle = "Density ${vm.density} of 8",
                onClick = onQuality
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickableRow(onClick)
    )
}

// =============================================================================
// Printer settings
// =============================================================================

@Composable
fun PrinterScreen(
    vm: LabelerViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onDiagnostics: () -> Unit,
    onStatus: () -> Unit
) {
    LabelerScaffold(title = "Printer", onBack = onBack, snackbar = snackbar) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Connected printer") {
                Text(
                    vm.printerName ?: vm.printerMac ?: "None selected",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "No pairing needed. This connects over BLE, which is why the " +
                        "printer never stays paired in Bluetooth settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text("Find printer")
                }
            }

            SectionCard("Command set") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        PhomemoM220.Protocol.M_SERIES to "m-series",
                        PhomemoM220.Protocol.M110 to "m110"
                    )
                    options.forEachIndexed { index, (value, text) ->
                        SegmentedButton(
                            selected = vm.protocol == value,
                            onClick = { vm.setProtocol(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size)
                        ) { Text(text) }
                    }
                }
                Text(
                    "m-series drives M220, M221, M260 and M200. m110 is for M110 and M120. " +
                        "Change this only if printing stops working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("Diagnostics") {
                Text(
                    "Print endpoint: " + (vm.characteristic ?: "chosen automatically"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = onStatus, modifier = Modifier.fillMaxWidth()) {
                    Text("Ask printer for status")
                }
                TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose print endpoint")
                }
            }

            val job = vm.job
            if (job is LabelerViewModel.Job.Busy) BusyRow(job.what)
        }
    }
}

@Composable
fun ScanScreen(
    vm: LabelerViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onPicked: () -> Unit
) {
    LabelerScaffold(
        title = "Find printer",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { vm.scan() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Scan again")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val job = vm.job
            if (job is LabelerViewModel.Job.Busy) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) { BusyRow(job.what) }
            }

            Text(
                "Likely printers are listed first. The advertised name often differs " +
                    "from the one on the printer's screen, so pick by trying if unsure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.found) { device ->
                    ListItem(
                        headlineContent = { Text(device.label) },
                        supportingContent = { Text(device.address) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow {
                            vm.choosePrinter(device)
                            onPicked()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StatusScreen(vm: LabelerViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    LabelerScaffold(title = "Printer status", onBack = onBack, snackbar = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val job = vm.job
            if (job is LabelerViewModel.Job.Busy) BusyRow(job.what)

            SectionCard("Reported by the printer") {
                if (vm.statusLines.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    vm.statusLines.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            FilledTonalButton(onClick = { vm.readStatus() }, modifier = Modifier.fillMaxWidth()) {
                Text("Ask again")
            }
        }
    }
}

@Composable
fun EndpointScreen(vm: LabelerViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    LabelerScaffold(title = "Print endpoint", onBack = onBack, snackbar = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val job = vm.job
            if (job is LabelerViewModel.Job.Busy) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) { BusyRow(job.what) }
            }
            Text(
                "Load a label and tap each entry until one prints. Whichever works is " +
                    "saved. Trying the wrong one is harmless.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.endpoints) { endpoint ->
                    ListItem(
                        headlineContent = { Text(endpoint.label) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow { vm.testEndpoint(endpoint) }
                    )
                }
            }
        }
    }
}

// =============================================================================
// Label size and quality
// =============================================================================

@Composable
fun LabelSizeScreen(vm: LabelerViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    LabelerScaffold(title = "Label size", onBack = onBack, snackbar = snackbar) { padding ->
        var width by remember { mutableStateOf(vm.labelWidthMm.toString()) }
        var height by remember { mutableStateOf(vm.labelHeightMm.toString()) }

        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Custom size") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter(Char::isDigit) },
                        label = { Text("Width mm") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter(Char::isDigit) },
                        label = { Text("Height mm") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    onClick = {
                        vm.setLabelSize(
                            width.toIntOrNull() ?: vm.labelWidthMm,
                            height.toIntOrNull() ?: vm.labelHeightMm
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Apply") }
            }

            Text(
                "Measure the label itself, not the backing. Width runs across the roll; " +
                    "height is how far it feeds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun QualityScreen(vm: LabelerViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    LabelerScaffold(title = "Print quality", onBack = onBack, snackbar = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Density ${vm.density} of 8") {
                Slider(
                    value = vm.density.toFloat(),
                    onValueChange = { vm.setDensity(it.toInt()) },
                    valueRange = 1f..8f,
                    steps = 6
                )
                Text(
                    "Higher is darker. Raise it if QR codes scan unreliably.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("Print head width") {
                var head by remember { mutableStateOf(vm.headWidthMm.toString()) }
                OutlinedTextField(
                    value = head,
                    onValueChange = { head = it.filter(Char::isDigit) },
                    label = { Text("Millimetres") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "The printer's width, not the label's: 72 for M220, 48 for M110. " +
                        "Every raster line is padded to it, so a wrong value stops printing entirely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = { vm.setHeadWidth(head.toIntOrNull() ?: vm.headWidthMm) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Apply") }
            }
        }
    }
}

/** List rows are tappable across their whole width, ripple included. */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
