package net.homelab.labeler

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * All app state and every call into the printer.
 *
 * The transport, command builder and renderer below this are deliberately
 * untouched by the UI rewrite - that layer took a long time to get right
 * against real hardware, and a redesign is no reason to disturb it.
 */
class LabelerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val transport = BleTransport(app)

    /** Long-running printer work, surfaced so screens can show progress. */
    sealed interface Job {
        data object Idle : Job
        data class Busy(val what: String) : Job
        data class Done(val message: String) : Job
        data class Failed(val message: String) : Job
    }

    var job by mutableStateOf<Job>(Job.Idle)
        private set

    // --- The label being composed ---------------------------------------------

    var label by mutableStateOf<LabelRenderer.Label?>(null)
        private set

    var preview by mutableStateOf<Bitmap?>(null)
        private set

    // --- Settings, mirrored out of Prefs so Compose can observe them -----------

    var labelWidthMm by mutableStateOf(prefs.labelWidthMm)
        private set
    var labelHeightMm by mutableStateOf(prefs.labelHeightMm)
        private set
    var density by mutableStateOf(prefs.density)
        private set
    var headWidthMm by mutableStateOf(prefs.headWidthMm)
        private set
    var protocol by mutableStateOf(prefs.protocol)
        private set
    var printerName by mutableStateOf(prefs.printerName)
        private set
    var printerMac by mutableStateOf(prefs.printerMac)
        private set
    var characteristic by mutableStateOf(prefs.characteristicUuid)
        private set

    // --- Discovery and diagnostics results ------------------------------------

    var found by mutableStateOf<List<BleTransport.Found>>(emptyList())
        private set
    var endpoints by mutableStateOf<List<BleTransport.Endpoint>>(emptyList())
        private set
    var statusLines by mutableStateOf<List<String>>(emptyList())
        private set

    // --- Label composition ----------------------------------------------------

    fun setLabel(value: LabelRenderer.Label?) {
        label = value
        rerender()
    }

    fun setLabelSize(widthMm: Int, heightMm: Int) {
        labelWidthMm = widthMm.coerceIn(10, 200)
        labelHeightMm = heightMm.coerceIn(10, 200)
        prefs.labelWidthMm = labelWidthMm
        prefs.labelHeightMm = labelHeightMm
        rerender()
    }

    fun setDensity(value: Int) {
        density = value.coerceIn(1, 8)
        prefs.density = density
    }

    fun setHeadWidth(value: Int) {
        headWidthMm = value.coerceIn(16, 104)
        prefs.headWidthMm = headWidthMm
    }

    fun setProtocol(value: PhomemoM220.Protocol) {
        protocol = value
        prefs.protocol = value
    }

    fun clearJob() {
        job = Job.Idle
    }

    private fun rerender() {
        val current = label
        preview = if (current == null) {
            null
        } else {
            runCatching { LabelRenderer.render(current, labelWidthMm, labelHeightMm) }.getOrNull()
        }
    }

    // --- Printer work ---------------------------------------------------------

    fun scan() = background("Scanning for printers") {
        found = transport.scan()
        "Found ${found.size} device${if (found.size == 1) "" else "s"}"
    }

    fun choosePrinter(device: BleTransport.Found) {
        prefs.printerMac = device.address
        prefs.printerName = device.name
        printerMac = device.address
        printerName = device.name
        // A different printer invalidates a characteristic confirmed on the old
        // one, and a stale pin fails less legibly than rediscovery.
        prefs.characteristicUuid = null
        characteristic = null
    }

    fun readStatus() = background("Asking the printer") {
        statusLines = transport.status(prefs.printerMac, savedCharacteristic())
        "Status received"
    }

    fun listEndpoints() = background("Reading printer services") {
        endpoints = transport.endpoints(prefs.printerMac)
        "Found ${endpoints.size} writable endpoint${if (endpoints.size == 1) "" else "s"}"
    }

    fun testEndpoint(endpoint: BleTransport.Endpoint) {
        prefs.characteristicUuid = endpoint.characteristic.toString()
        characteristic = prefs.characteristicUuid
        background("Testing ${endpoint.characteristic}") {
            send(TEST_LABEL, endpoint.characteristic)
            "Sent. If a label printed, that endpoint is now saved."
        }
    }

    fun print() {
        val current = label ?: return
        background("Printing") {
            send(current, savedCharacteristic())
            "Printed"
        }
    }

    fun printTest() = background("Printing test label") {
        send(TEST_LABEL, savedCharacteristic())
        "Printed"
    }

    private suspend fun send(target: LabelRenderer.Label, characteristic: UUID?) {
        val bitmap = LabelRenderer.render(target, labelWidthMm, labelHeightMm)
        val steps = PhomemoM220.buildJob(
            raster = LabelRenderer.toRaster(bitmap),
            density = density,
            protocol = protocol,
            headWidthBytes = headWidthMm,
            mediaType = prefs.mediaType
        )
        transport.send(prefs.printerMac, steps, characteristic)
    }

    private fun savedCharacteristic(): UUID? =
        prefs.characteristicUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * Runs printer work off the main thread and funnels both outcomes into
     * [job], so every screen reports progress and failure the same way.
     */
    private fun background(what: String, work: suspend () -> String) {
        if (job is Job.Busy) return
        job = Job.Busy(what)
        viewModelScope.launch {
            job = runCatching { withContext(Dispatchers.IO) { work() } }.fold(
                onSuccess = { Job.Done(it) },
                onFailure = { Job.Failed(it.message ?: "Something went wrong") }
            )
        }
    }

    private companion object {
        val TEST_LABEL = LabelRenderer.Label(
            url = "http://test.local/a/000-001",
            title = "Test label",
            assetId = "000-001"
        )
    }
}
