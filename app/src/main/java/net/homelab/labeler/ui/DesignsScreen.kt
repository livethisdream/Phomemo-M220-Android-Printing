@file:OptIn(ExperimentalMaterial3Api::class)

package net.homelab.labeler.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import net.homelab.labeler.DesignStore
import net.homelab.labeler.LabelerViewModel
import java.text.DateFormat
import java.util.Date

/**
 * The library of saved designs.
 *
 * Each row shows the design rather than only its name. A list of labels called
 * "shelf", "shelf 2" and "bins" is unusable by the time there are ten of them;
 * the thumbnail is what makes the right one findable.
 */
@Composable
fun DesignsScreen(
    vm: LabelerViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpened: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<DesignStore.Saved?>(null) }

    LabelerScaffold(title = "Saved designs", onBack = onBack, snackbar = snackbar) { padding ->
        val designs = vm.designs
        if (designs.isEmpty()) {
            NoDesignsYet(Modifier.fillMaxSize().padding(padding).padding(24.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(designs, key = { it.id }) { saved ->
                    DesignRow(
                        vm = vm,
                        saved = saved,
                        open = {
                            vm.loadDesign(saved.id)
                            onOpened()
                        },
                        remove = { pendingDelete = saved }
                    )
                }
            }
        }
    }

    val doomed = pendingDelete
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${doomed.name}\"?") },
            text = { Text("The design is removed from this phone. Nothing already printed changes.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDesign(doomed.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DesignRow(
    vm: LabelerViewModel,
    saved: DesignStore.Saved,
    open: () -> Unit,
    remove: () -> Unit
) {
    // Decoded off the main thread and keyed by id, so scrolling a long library
    // does not stall on file reads.
    var thumbnail by remember(saved.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(saved.id, saved.savedAt) { thumbnail = vm.thumbnail(saved.id) }

    Card(
        Modifier.fillMaxWidth().clickable(onClick = open),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .width(96.dp)
                    .height(64.dp),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = thumbnail
                if (bitmap != null) {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        // Fit, not crop. A thumbnail that fills its box by
                        // cutting the edges off hides exactly the mistake -
                        // something running off the label - worth spotting.
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(4.dp)
                        )
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                Text(saved.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    saved.size,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (saved.savedAt > 0) {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(Date(saved.savedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = remove) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${saved.name}")
            }
        }
    }
}

@Composable
private fun NoDesignsYet(modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Icon(
            Icons.Filled.Bookmarks,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("No saved designs", style = MaterialTheme.typography.titleMedium)
        Text(
            "Lay a label out in the editor and tap save. It keeps the elements " +
                "and the stock size, ready to print again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Names a design on the way to disk.
 *
 * When a saved design is open there are two sensible things to mean by "save",
 * and guessing between them is how a library fills up with near-duplicates, so
 * both are offered by name: update the one that is open, or keep a copy.
 */
@Composable
fun SaveDesignDialog(
    initialName: String,
    isExisting: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, overwrite: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val valid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isExisting) "Save design" else "Name this design") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(name, isExisting) }
            ) { Text(if (isExisting) "Update" else "Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (isExisting) {
                    TextButton(enabled = valid, onClick = { onSave(name, false) }) {
                        Text("Save copy")
                    }
                }
            }
        }
    )
}
