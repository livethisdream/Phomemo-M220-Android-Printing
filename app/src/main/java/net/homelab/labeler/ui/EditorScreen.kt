@file:OptIn(ExperimentalMaterial3Api::class)

package net.homelab.labeler.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import net.homelab.labeler.ImageLabel
import net.homelab.labeler.LabelDesign
import net.homelab.labeler.LabelerViewModel
import net.homelab.labeler.Snapping
import kotlin.math.abs

/** Hit area for the resize corner, in screen dp rather than label mm. */
private val HANDLE_DP = 28.dp

@Composable
fun EditorScreen(
    vm: LabelerViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onPickImage: () -> Unit
) {
    LabelerScaffold(
        title = "Edit label",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { vm.toggleSnap() }) {
                Icon(
                    Icons.Filled.GridOn,
                    contentDescription = if (vm.snapEnabled) "Snapping on" else "Snapping off",
                    tint = if (vm.snapEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) {
                Icon(Icons.Filled.Undo, contentDescription = "Undo")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EditorCanvas(vm)
            AddRow(vm, onPickImage)
            Properties(vm)
        }
    }
}

// =============================================================================
// Canvas
// =============================================================================

@Composable
private fun EditorCanvas(vm: LabelerViewModel) {
    val bitmap = vm.preview
    val labelW = vm.labelWidthMm.toFloat()
    val labelH = vm.labelHeightMm.toFloat()
    val density = LocalDensity.current
    val handlePx = with(density) { HANDLE_DP.toPx() }

    // Canvas size in screen pixels, learned from layout. Every gesture converts
    // through this, so a stale value would put touches in the wrong place.
    var canvasW by remember { mutableStateOf(1f) }
    var canvasH by remember { mutableStateOf(1f) }
    val pxPerMm = canvasW / labelW

    // Drag bookkeeping. Held here rather than in the view model because it is
    // per-gesture scratch, not document state.
    var mode by remember { mutableStateOf(DragMode.NONE) }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(labelW / labelH)
                .background(Color.White)
                .onSizeChanged {
                    canvasW = it.width.toFloat()
                    canvasH = it.height.toFloat()
                }
                /*
                 * Keyed on Unit deliberately.
                 *
                 * These were previously keyed on vm.doc, which changes on every
                 * frame of a drag - so Compose tore down and rebuilt the gesture
                 * detector mid-gesture, cancelling the drag. That is why moving
                 * an element only worked in short bursts with a lift between
                 * each one. Current state is read inside the callbacks instead,
                 * where it is always fresh without restarting anything.
                 */
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val perMmX = size.width / vm.labelWidthMm.toFloat()
                        val perMmY = size.height / vm.labelHeightMm.toFloat()
                        // Topmost first: later elements draw over earlier ones,
                        // so they should also win the tap.
                        vm.select(hitTest(vm, offset.x / perMmX, offset.y / perMmY))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val perMmX = size.width / vm.labelWidthMm.toFloat()
                            val perMmY = size.height / vm.labelHeightMm.toFloat()
                            val current = vm.selected

                            val onHandle = current != null &&
                                abs(offset.x - (current.x + current.w) * perMmX) < handlePx &&
                                abs(offset.y - (current.y + current.h) * perMmY) < handlePx

                            if (onHandle) {
                                mode = DragMode.RESIZE
                                vm.checkpoint()
                            } else {
                                // Grab whatever is under the finger, so dragging
                                // works without having to tap to select first.
                                val hit = hitTest(vm, offset.x / perMmX, offset.y / perMmY)
                                if (hit == null) {
                                    mode = DragMode.NONE
                                } else {
                                    vm.select(hit)
                                    mode = DragMode.MOVE
                                    vm.checkpoint()
                                }
                            }
                        },
                        onDragEnd = {
                            mode = DragMode.NONE
                            vm.updateGuides(emptyList())
                        },
                        onDragCancel = {
                            mode = DragMode.NONE
                            vm.updateGuides(emptyList())
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        if (mode == DragMode.NONE) return@detectDragGestures
                        val element = vm.selected ?: return@detectDragGestures

                        val perMmX = size.width / vm.labelWidthMm.toFloat()
                        val perMmY = size.height / vm.labelHeightMm.toFloat()
                        val dxMm = dragAmount.x / perMmX
                        val dyMm = dragAmount.y / perMmY

                        when (mode) {
                            DragMode.MOVE -> {
                                val others = vm.doc.elements.filter { it.id != element.id }
                                val raw = Snapping.clamp(
                                    element.x + dxMm, element.y + dyMm,
                                    element.w, element.h,
                                    vm.labelWidthMm, vm.labelHeightMm
                                )
                                val snapped = Snapping.snap(
                                    raw.first, raw.second, element.w, element.h,
                                    others, vm.labelWidthMm, vm.labelHeightMm, vm.snapEnabled
                                )
                                vm.updateGuides(snapped.guides)
                                vm.applyElement(element.moved(snapped.x, snapped.y))
                            }

                            DragMode.RESIZE -> {
                                vm.applyElement(
                                    element.resized(
                                        (element.w + dxMm).coerceAtLeast(3f),
                                        (element.h + dyMm).coerceAtLeast(3f)
                                    )
                                )
                            }

                            DragMode.NONE -> Unit
                        }
                    }
                }
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Label",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Overlay(vm, pxPerMm, canvasH / labelH, handlePx)
        }
    }

    Text(
        if (vm.selected == null) {
            "Tap an element to select it. Drag to move, or drag its corner to resize."
        } else {
            "Drag to move. The square handle resizes."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

private enum class DragMode { NONE, MOVE, RESIZE }

@Composable
private fun Overlay(
    vm: LabelerViewModel,
    pxPerMmX: Float,
    pxPerMmY: Float,
    handlePx: Float
) {
    val outline = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.tertiary

    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        for (guide in vm.guides) {
            if (guide.vertical) {
                val x = guide.positionMm * pxPerMmX
                drawLine(guideColor, androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2f)
            } else {
                val y = guide.positionMm * pxPerMmY
                drawLine(guideColor, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2f)
            }
        }

        vm.selected?.let { element ->
            val left = element.x * pxPerMmX
            val top = element.y * pxPerMmY
            val w = element.w * pxPerMmX
            val h = element.h * pxPerMmY

            drawRect(
                color = outline,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(w, h),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
            val handle = handlePx / 2.5f
            drawRect(
                color = outline,
                topLeft = androidx.compose.ui.geometry.Offset(left + w - handle / 2, top + h - handle / 2),
                size = androidx.compose.ui.geometry.Size(handle, handle)
            )
        }
    }
}

// =============================================================================
// Toolbars
// =============================================================================

@Composable
private fun AddRow(vm: LabelerViewModel, onPickImage: () -> Unit) {
    // Text only. An icon plus a label does not fit three across on a phone,
    // and "Image" was the one that overflowed.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = { vm.addText() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            modifier = Modifier.weight(1f)
        ) { Text("Text", maxLines = 1) }
        FilledTonalButton(
            onClick = { vm.addQr() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            modifier = Modifier.weight(1f)
        ) { Text("QR code", maxLines = 1) }
        FilledTonalButton(
            onClick = onPickImage,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            modifier = Modifier.weight(1f)
        ) { Text("Image", maxLines = 1) }
    }
}

@Composable
private fun Properties(vm: LabelerViewModel) {
    val element = vm.selected
    if (element == null) {
        Text(
            "Nothing selected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (element) {
                is LabelDesign.Element.Text -> TextProps(vm, element)
                is LabelDesign.Element.Qr -> QrProps(vm, element)
                is LabelDesign.Element.Picture -> PictureProps(vm, element)
            }

            FilledTonalButton(
                onClick = { vm.deleteSelected() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Delete", maxLines = 1)
            }
        }
    }
}

@Composable
private fun TextProps(vm: LabelerViewModel, element: LabelDesign.Element.Text) {
    OutlinedTextField(
        value = element.text,
        onValueChange = { vm.applyElement(element.copy(text = it)) },
        label = { Text("Text") },
        modifier = Modifier.fillMaxWidth()
    )

    Text("Size ${"%.1f".format(element.sizeMm)} mm", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = element.sizeMm,
        onValueChange = { vm.applyElement(element.copy(sizeMm = it)) },
        valueRange = 2f..14f
    )

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val aligns = listOf(
            LabelDesign.Align.LEFT to "Left",
            LabelDesign.Align.CENTER to "Center",
            LabelDesign.Align.RIGHT to "Right"
        )
        aligns.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = element.align == value,
                onClick = { vm.applyElement(element.copy(align = value)) },
                shape = SegmentedButtonDefaults.itemShape(index, aligns.size)
            ) { Text(text) }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = element.bold,
            onClick = { vm.applyElement(element.copy(bold = !element.bold)) },
            label = { Text("Bold") }
        )
        FilterChip(
            selected = element.monospace,
            onClick = { vm.applyElement(element.copy(monospace = !element.monospace)) },
            label = { Text("Mono") }
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Switch(
            checked = element.invert,
            onCheckedChange = { vm.applyElement(element.copy(invert = it)) }
        )
        Column {
            Text("White on black", style = MaterialTheme.typography.bodyMedium)
            Text(
                "The only color a thermal head has.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QrProps(vm: LabelerViewModel, element: LabelDesign.Element.Qr) {
    OutlinedTextField(
        value = element.content,
        onValueChange = { vm.applyElement(element.copy(content = it)) },
        label = { Text("Encoded content") },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Shorter content means fewer, larger modules and a code that scans more " +
            "reliably off thermal stock.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PictureProps(vm: LabelerViewModel, element: LabelDesign.Element.Picture) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val modes = listOf(
            ImageLabel.Mode.THRESHOLD to "Sharp",
            ImageLabel.Mode.DITHER to "Photo"
        )
        modes.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = element.mode == value,
                onClick = { vm.applyElement(element.copy(mode = value)) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size)
            ) { Text(text) }
        }
    }
    Text(
        if (element.mode == ImageLabel.Mode.THRESHOLD) {
            "Hard black and white. Right for QR codes, barcodes and line art."
        } else {
            "Dithered to fake gray. Right for photographs, wrong for QR codes."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Topmost element containing the point, in label millimeters. */
private fun hitTest(vm: LabelerViewModel, mmX: Float, mmY: Float): Long? =
    vm.doc.elements.lastOrNull {
        mmX >= it.x && mmX <= it.x + it.w && mmY >= it.y && mmY <= it.y + it.h
    }?.id
