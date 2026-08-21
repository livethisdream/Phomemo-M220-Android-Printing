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
    private val store = DesignStore(app)

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

    /** The label as positioned elements. Empty means nothing to print. */
    var doc by mutableStateOf(LabelDesign.Doc())
        private set

    var selectedId by mutableStateOf<Long?>(null)
        private set

    /** Alignment lines the current drag locked onto, for the editor to draw. */
    var guides by mutableStateOf<List<Snapping.Guide>>(emptyList())
        private set

    var snapEnabled by mutableStateOf(true)
        private set

    var preview by mutableStateOf<Bitmap?>(null)
        private set

    private val undoStack = ArrayDeque<LabelDesign.Doc>()

    var canUndo by mutableStateOf(false)
        private set

    val selected: LabelDesign.Element? get() = doc.find(selectedId)

    // --- Saved designs ---------------------------------------------------------

    var designs by mutableStateOf<List<DesignStore.Saved>>(emptyList())
        private set

    /**
     * Which saved design is on the canvas, if any. Kept so that saving again
     * updates that design instead of leaving a second copy of it behind - the
     * usual reason to press save twice is that the first version was wrong.
     */
    var designId by mutableStateOf<String?>(null)
        private set

    var designName by mutableStateOf<String?>(null)
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
    var feedDots by mutableStateOf(prefs.feedDots)
        private set
    var feedMode by mutableStateOf(prefs.feedMode)
        private set
    var alignment by mutableStateOf(prefs.alignment)
        private set
    var protocol by mutableStateOf(prefs.protocol)
        private set
    var printerName by mutableStateOf(prefs.printerName)
        private set
    var printerMac by mutableStateOf(prefs.printerMac)
        private set
    var characteristic by mutableStateOf(prefs.characteristicUuid)
        private set
    var customSizes by mutableStateOf(prefs.customSizes)
        private set

    // --- Discovery and diagnostics results ------------------------------------

    var found by mutableStateOf<List<BleTransport.Found>>(emptyList())
        private set
    var endpoints by mutableStateOf<List<BleTransport.Endpoint>>(emptyList())
        private set
    var statusLines by mutableStateOf<List<String>>(emptyList())
        private set

    // --- Label composition ----------------------------------------------------

    /**
     * A share replaces the canvas rather than adding to it. Dropping a QR onto
     * whatever happened to be there last would make share-to-print
     * unpredictable, which is the one flow that has to stay a reflex.
     */
    fun updateLabel(value: LabelRenderer.Label?) {
        if (value == null) return
        replaceDoc(LabelDesign.fromLabel(value, labelWidthMm, labelHeightMm))
    }

    fun updateImage(bitmap: Bitmap) {
        replaceDoc(
            LabelDesign.fromImage(bitmap, labelWidthMm, labelHeightMm, ImageLabel.Mode.THRESHOLD)
        )
    }

    private fun replaceDoc(value: LabelDesign.Doc) {
        undoStack.clear()
        canUndo = false
        doc = value
        selectedId = null
        // Whatever was on the canvas is gone, so the design it came from is no
        // longer what is being edited. Keeping the link would make the next
        // save overwrite a design the user never opened.
        designId = null
        designName = null
        rerender()
    }

    // --- Saved designs ---------------------------------------------------------

    fun refreshDesigns() = background("Loading designs") {
        val list = store.list()
        withContext(Dispatchers.Main) { designs = list }
        ""
    }

    /**
     * Writes the canvas as a named design. A null [id] saves a copy; passing
     * [designId] overwrites the design already open.
     */
    fun saveDesign(name: String, id: String?) {
        if (doc.elements.isEmpty()) return
        val snapshot = doc
        val w = labelWidthMm
        val h = labelHeightMm
        background("Saving design") {
            val saved = store.save(id, name.trim(), snapshot, w, h)
            val list = store.list()
            withContext(Dispatchers.Main) {
                designs = list
                designId = saved.id
                designName = saved.name
            }
            "Saved \"${saved.name}\""
        }
    }

    /**
     * Opens a design, including the stock it was laid out on. Restoring the
     * elements without the label size would reproduce the coordinates but not
     * the layout, since every position is relative to an edge.
     */
    fun loadDesign(id: String) = background("Opening design") {
        val loaded = store.load(id) ?: throw IllegalStateException("That design is missing")
        withContext(Dispatchers.Main) {
            undoStack.clear()
            canUndo = false
            selectedId = null
            labelWidthMm = loaded.widthMm
            labelHeightMm = loaded.heightMm
            prefs.labelWidthMm = loaded.widthMm
            prefs.labelHeightMm = loaded.heightMm
            doc = loaded.doc
            designId = loaded.id
            designName = loaded.name
            rerender()
        }
        "Opened \"${loaded.name}\""
    }

    fun deleteDesign(id: String) = background("Deleting design") {
        store.delete(id)
        val list = store.list()
        withContext(Dispatchers.Main) {
            designs = list
            // The canvas keeps its contents; only its link to a design that no
            // longer exists is dropped, so a mistaken delete costs a name.
            if (designId == id) {
                designId = null
                designName = null
            }
        }
        "Deleted"
    }

    suspend fun thumbnail(id: String): Bitmap? =
        withContext(Dispatchers.IO) { store.thumbnail(id) }

    // --- Editing --------------------------------------------------------------

    fun select(id: Long?) {
        selectedId = id
    }

    fun toggleSnap() {
        snapEnabled = !snapEnabled
    }

    fun updateGuides(value: List<Snapping.Guide>) {
        guides = value
    }

    /** Records the document for undo. Call before a discrete edit, not per frame. */
    fun checkpoint() {
        undoStack.addLast(doc)
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        canUndo = true
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        doc = previous
        canUndo = undoStack.isNotEmpty()
        if (doc.find(selectedId) == null) selectedId = null
        rerender()
    }

    /** Applies an in-progress edit without touching the undo stack. */
    fun applyElement(element: LabelDesign.Element) {
        doc = doc.replace(element)
        rerender()
    }

    fun addText() {
        checkpoint()
        val element = LabelDesign.Element.Text(
            id = LabelDesign.newId(),
            x = labelWidthMm * 0.15f,
            y = labelHeightMm * 0.4f,
            w = labelWidthMm * 0.7f,
            h = 6f,
            text = "Text"
        )
        doc = doc.add(element)
        selectedId = element.id
        rerender()
    }

    fun addQr(content: String = "http://example.com") {
        checkpoint()
        val size = minOf(labelWidthMm, labelHeightMm) * 0.5f
        val element = LabelDesign.Element.Qr(
            id = LabelDesign.newId(),
            x = (labelWidthMm - size) / 2f,
            y = (labelHeightMm - size) / 2f,
            w = size,
            h = size,
            content = content
        )
        doc = doc.add(element)
        selectedId = element.id
        rerender()
    }

    fun addPicture(bitmap: Bitmap) {
        checkpoint()
        val box = minOf(labelWidthMm, labelHeightMm) * 0.6f
        val scale = minOf(box / bitmap.width, box / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val element = LabelDesign.Element.Picture(
            id = LabelDesign.newId(),
            x = (labelWidthMm - w) / 2f,
            y = (labelHeightMm - h) / 2f,
            w = w,
            h = h,
            bitmap = bitmap
        )
        doc = doc.add(element)
        selectedId = element.id
        rerender()
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        checkpoint()
        doc = doc.remove(id)
        selectedId = null
        rerender()
    }

    fun clearAll() {
        checkpoint()
        doc = LabelDesign.Doc()
        selectedId = null
        rerender()
    }

    /** True when the label is wider than the head can physically print. */
    val labelTooWide: Boolean get() = labelWidthMm > headWidthMm

    /**
     * Swaps the two dimensions. Which number is the width is the single thing
     * about label stock that cannot be worked out from the design - it is fixed
     * by the roll - and getting it backwards silently pushes the image off the
     * registered edge, so reversing it is worth one button.
     */
    fun swapLabelSize() = updateLabelSize(labelHeightMm, labelWidthMm)

    fun updateLabelSize(widthMm: Int, heightMm: Int) {
        // Elements keep their millimeter positions, so a size change moves the
        // label edges around them rather than rescaling the design.
        labelWidthMm = widthMm.coerceIn(10, 200)
        labelHeightMm = heightMm.coerceIn(10, 200)
        prefs.labelWidthMm = labelWidthMm
        prefs.labelHeightMm = labelHeightMm
        rerender()
    }

    /** Keeps the current size as a reusable preset, ignoring duplicates. */
    fun saveCurrentSize() {
        val size = LabelSizes.Size(labelWidthMm, labelHeightMm)
        if (size in customSizes || size in LabelSizes.STANDARD || size in LabelSizes.ROUND) return
        customSizes = customSizes + size
        prefs.customSizes = customSizes
    }

    fun forgetSize(size: LabelSizes.Size) {
        customSizes = customSizes - size
        prefs.customSizes = customSizes
    }

    fun updateDensity(value: Int) {
        density = value.coerceIn(1, 8)
        prefs.density = density
    }

    fun updateHeadWidth(value: Int) {
        headWidthMm = value.coerceIn(16, 104)
        prefs.headWidthMm = headWidthMm
    }

    /** Taken in millimeters because that is how label stock is measured. */
    fun updateFeedMm(mm: Float) {
        feedDots = (mm * LabelRenderer.DOTS_PER_MM).toInt().coerceIn(0, 240)
        prefs.feedDots = feedDots
    }

    fun updateFeedMode(value: PhomemoM220.FeedMode) {
        feedMode = value
        prefs.feedMode = value
    }

    fun updateAlignment(value: PhomemoM220.Alignment) {
        alignment = value
        prefs.alignment = value
    }

    fun updateProtocol(value: PhomemoM220.Protocol) {
        protocol = value
        prefs.protocol = value
    }

    fun clearJob() {
        job = Job.Idle
    }

    private fun rerender() {
        preview = runCatching { renderCurrent() }.getOrNull()
    }

    /** The single place a label becomes pixels, shared by preview and print. */
    private fun renderCurrent(): Bitmap? =
        if (doc.elements.isEmpty()) null
        else DesignRenderer.render(doc, labelWidthMm, labelHeightMm)

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
            send(
                LabelRenderer.render(TEST_LABEL, labelWidthMm, labelHeightMm),
                endpoint.characteristic
            )
            "Sent. If a label printed, that endpoint is now saved."
        }
    }

    fun print() {
        if (doc.elements.isEmpty()) return
        background("Printing") {
            val bitmap = renderCurrent() ?: throw IllegalStateException("Nothing to print")
            send(bitmap, savedCharacteristic())
            "Printed"
        }
    }

    /**
     * Prints the head-width scale. Sent at exactly the head width so nothing is
     * padded or cropped on the way out - the whole point is to show where the
     * head's own edges fall on the stock.
     */
    fun printRuler() = background("Printing measuring guide") {
        send(HeadRuler.render(headWidthMm), savedCharacteristic())
        "Printed. The largest number you can read is your printable width."
    }

    fun printTest() = background("Printing test label") {
        send(
            LabelRenderer.render(TEST_LABEL, labelWidthMm, labelHeightMm),
            savedCharacteristic()
        )
        "Printed"
    }

    private suspend fun send(bitmap: Bitmap, characteristic: UUID?) {
        val steps = PhomemoM220.buildJob(
            raster = LabelRenderer.toRaster(bitmap),
            density = density,
            protocol = protocol,
            headWidthBytes = headWidthMm,
            feedDots = feedDots,
            feedMode = feedMode,
            alignment = alignment,
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
        const val UNDO_DEPTH = 40

        val TEST_LABEL = LabelRenderer.Label(
            url = "http://test.local/a/000-001",
            title = "Test label",
            assetId = "000-001"
        )
    }
}
