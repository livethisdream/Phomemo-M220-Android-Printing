package net.homelab.labeler

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val transport by lazy { BleTransport(this) }

    private var pending: LabelRenderer.Label? = null
    private var preview: ImageView? = null
    private var status: TextView? = null

    /** What to run once the user answers the permission dialog. */
    private var afterPermission: (() -> Unit)? = null

    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val next = afterPermission
        afterPermission = null
        if (results.values.all { it }) {
            next?.invoke()
        } else {
            toast("Bluetooth permission is required to reach the printer")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val label = parseShare(intent)
        if (label == null) {
            showSettings()
        } else {
            pending = label
            showPreview(label)
        }
    }

    // --- Share intent parsing -------------------------------------------------

    /**
     * Chrome populates EXTRA_TEXT with the page URL and EXTRA_SUBJECT with the
     * page title, which conveniently gives us the item name for free.
     */
    private fun parseShare(intent: Intent): LabelRenderer.Label? {
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val url = URL_RE.find(text)?.value ?: return null

        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.substringBefore(" | ")   // strip the site suffix Homebox appends
            ?.trim()

        return LabelRenderer.Label(
            url = url,
            title = subject,
            assetId = ASSET_RE.find(url)?.groupValues?.getOrNull(1)
        )
    }

    // --- Screens --------------------------------------------------------------

    private fun showPreview(label: LabelRenderer.Label) {
        val bmp = LabelRenderer.render(label, prefs.labelWidthMm, prefs.labelHeightMm)

        val root = column()
        root.addView(heading("Print label"))
        root.addView(body(label.title ?: label.url))

        preview = ImageView(this).apply {
            setImageBitmap(bmp)
            setBackgroundColor(Color.WHITE)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.topMargin = 24; it.bottomMargin = 24 }
        }
        root.addView(preview)

        status = body("")
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Print"
            setOnClickListener { requestPrint() }
        })
        root.addView(Button(this).apply {
            text = "Label settings"
            setOnClickListener { showSettings() }
        })

        setContentView(root)
    }

    private fun showSettings() {
        val root = column()
        root.addView(heading("Label settings"))
        root.addView(body("Size of your label stock in millimetres. The M220 head is 72 mm wide; anything wider than your roll will be clipped."))

        val width = numberField("Width (mm)", prefs.labelWidthMm)
        val height = numberField("Height (mm)", prefs.labelHeightMm)
        val density = numberField("Density (1-15)", prefs.density)
        root.addView(width)
        root.addView(height)
        root.addView(density)

        // No pairing step: BLE needs no bond, so the printer is identified by
        // the address the user picked out of a scan rather than by a bond the
        // system holds. That is the whole reason this app stopped caring about
        // Settings > Bluetooth.
        root.addView(
            body(
                prefs.printerMac?.let { mac ->
                    "Printer: " + (prefs.printerName ?: mac)
                } ?: "No printer chosen yet. Tap Find printer with the M220 powered on."
            )
        )

        // Scanning replaces this screen, so commit any edits first rather than
        // silently discarding them on the way to the picker.
        val commitFields = {
            prefs.labelWidthMm = width.value() ?: prefs.labelWidthMm
            prefs.labelHeightMm = height.value() ?: prefs.labelHeightMm
            prefs.density = density.value() ?: prefs.density
        }

        root.addView(Button(this).apply {
            text = "Find printer"
            setOnClickListener {
                commitFields()
                withBluetoothPermission { scanForPrinters() }
            }
        })

        if (prefs.printerMac != null) {
            root.addView(
                body(
                    prefs.characteristicUuid?.let { "Print endpoint: $it" }
                        ?: "Print endpoint: chosen automatically. If printing reports success but nothing comes out, run diagnostics and try each one."
                )
            )
            root.addView(Button(this).apply {
                text = "Ask printer for status"
                setOnClickListener {
                    commitFields()
                    withBluetoothPermission { showStatus() }
                }
            })
            root.addView(Button(this).apply {
                text = "Printer diagnostics"
                setOnClickListener {
                    commitFields()
                    withBluetoothPermission { showDiagnostics() }
                }
            })
        }

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                commitFields()
                toast("Saved")
                pending?.let { showPreview(it) }
            }
        })

        setContentView(root)
    }

    // --- Printer discovery ----------------------------------------------------

    private fun scanForPrinters() {
        val root = column()
        root.addView(heading("Finding printers"))
        root.addView(body("Scanning for nearby Bluetooth devices..."))
        setContentView(root)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { transport.scan() } }
            result.fold(
                onSuccess = { showPicker(it) },
                onFailure = { e ->
                    toast(e.message ?: "Scan failed")
                    showSettings()
                }
            )
        }
    }

    /**
     * Every device found, not just ones matching a name pattern. The advertised
     * name often differs from what the printer shows on its own screen, so a
     * name filter would be capable of hiding the very device being looked for.
     */
    private fun showPicker(devices: List<BleTransport.Found>) {
        val root = column()
        root.addView(heading("Choose your printer"))

        if (devices.isEmpty()) {
            root.addView(body("Nothing found. Check the M220 is powered on and in range, then try again."))
        } else {
            root.addView(body("Likely printers are listed first. If nothing here looks right, power-cycle the M220 and scan again."))
            for (device in devices) {
                root.addView(Button(this).apply {
                    text = device.label
                    setOnClickListener {
                        prefs.printerMac = device.address
                        prefs.printerName = device.name
                        toast("Saved ${device.label}")
                        showSettings()
                    }
                })
            }
        }

        root.addView(Button(this).apply {
            text = "Scan again"
            setOnClickListener { scanForPrinters() }
        })
        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { showSettings() }
        })

        // A scan in a populated room returns far more than fits on a screen.
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // --- Diagnostics ----------------------------------------------------------

    /**
     * Lists every writable characteristic and lets the user test each in turn.
     *
     * This exists because the failure it diagnoses is invisible from software:
     * writing a print job to the wrong characteristic succeeds at every layer
     * Android can see, and the only evidence of the mistake is that no label
     * comes out. So the person holding the printer does the last step.
     */
    private fun showDiagnostics() {
        val root = column()
        root.addView(heading("Printer diagnostics"))
        root.addView(body("Connecting..."))
        setContentView(root)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { transport.endpoints(prefs.printerMac) }
            }
            result.fold(
                onSuccess = { showEndpoints(it) },
                onFailure = { e ->
                    toast(e.message ?: "Could not reach the printer")
                    showSettings()
                }
            )
        }
    }

    /** Shows what the printer reports about itself, verbatim. */
    private fun showStatus() {
        val loading = column()
        loading.addView(heading("Printer status"))
        loading.addView(body("Asking the printer..."))
        setContentView(loading)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { transport.status(prefs.printerMac, savedCharacteristic()) }
            }

            val root = column()
            root.addView(heading("Printer status"))
            result.fold(
                onSuccess = { lines ->
                    root.addView(body(lines.joinToString("\n")))
                },
                onFailure = { e ->
                    root.addView(body(e.message ?: "Could not reach the printer"))
                }
            )
            root.addView(Button(this@MainActivity).apply {
                text = "Back"
                setOnClickListener { showSettings() }
            })
            setContentView(ScrollView(this@MainActivity).apply { addView(root) })
        }
    }

    private fun showEndpoints(endpoints: List<BleTransport.Endpoint>) {
        val root = column()
        root.addView(heading("Print endpoints"))

        if (endpoints.isEmpty()) {
            root.addView(body("This device exposes nothing writable, so it cannot be the printer. Go back and pick a different device."))
        } else {
            root.addView(
                body(
                    "Load a label, then tap each entry until one prints. Whichever works " +
                        "is saved and used from then on. Nothing is damaged by trying the wrong one."
                )
            )
            for (endpoint in endpoints) {
                root.addView(Button(this).apply {
                    text = endpoint.label
                    setOnClickListener { testEndpoint(endpoint) }
                })
            }
        }

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { showSettings() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun testEndpoint(endpoint: BleTransport.Endpoint) {
        prefs.characteristicUuid = endpoint.characteristic.toString()
        toast("Testing ${endpoint.characteristic}")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = LabelRenderer.render(
                        TEST_LABEL, prefs.labelWidthMm, prefs.labelHeightMm
                    )
                    val job = PhomemoM220.buildJob(
                        raster = LabelRenderer.toRaster(bmp),
                        speed = prefs.speed,
                        density = prefs.density,
                        mediaType = prefs.mediaType
                    )
                    transport.send(prefs.printerMac, job, endpoint.characteristic)
                }
            }
            result.fold(
                onSuccess = { toast("Sent. If a label printed, you are done - tap Back.") },
                onFailure = { e -> toast(e.message ?: "Failed") }
            )
        }
    }

    // --- Printing -------------------------------------------------------------

    private fun requestPrint() = withBluetoothPermission { doPrint() }

    /**
     * From Android 12 this is BLUETOOTH_SCAN + BLUETOOTH_CONNECT, and
     * neverForLocation in the manifest keeps location out of it.
     *
     * Below Android 12 there is no such flag: a BLE scan silently returns zero
     * results without a location grant, whatever the app is actually doing, so
     * ACCESS_FINE_LOCATION has to be asked for on those versions.
     */
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBluetoothPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun withBluetoothPermission(action: () -> Unit) {
        if (hasBluetoothPermission()) {
            action()
        } else {
            afterPermission = action
            requestBluetooth.launch(requiredPermissions())
        }
    }

    private fun doPrint() {
        val label = pending ?: return
        status?.text = "Connecting..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp: Bitmap = LabelRenderer.render(
                        label, prefs.labelWidthMm, prefs.labelHeightMm
                    )
                    val job = PhomemoM220.buildJob(
                        raster = LabelRenderer.toRaster(bmp),
                        speed = prefs.speed,
                        density = prefs.density,
                        mediaType = prefs.mediaType
                    )
                    transport.send(prefs.printerMac, job, savedCharacteristic())
                }
            }

            result.fold(
                onSuccess = {
                    status?.text = "Printed"
                    finish()   // drop straight back to Chrome
                },
                onFailure = { e ->
                    status?.text = e.message ?: "Print failed"
                }
            )
        }
    }

    // --- Tiny view helpers (no XML layouts, keeps the module minimal) ---------

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 64, 48, 48)
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 22f
        setPadding(0, 0, 0, 16)
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, 0, 0, 16)
    }

    private fun numberField(hint: String, value: Int) = EditText(this).apply {
        this.hint = hint
        setText(value.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        gravity = Gravity.START
    }

    private fun EditText.value(): Int? = text.toString().trim().toIntOrNull()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Null (fall back to discovery) if nothing has been confirmed by hand. */
    private fun savedCharacteristic(): UUID? =
        prefs.characteristicUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private companion object {
        val URL_RE = Regex("""https?://\S+""")
        // Homebox asset URLs look like /a/000-001
        val ASSET_RE = Regex("""/a/([0-9]{3}-[0-9]{3})""")

        /** Self-contained so diagnostics work without a shared URL to hand. */
        val TEST_LABEL = LabelRenderer.Label(
            url = "http://test.local/a/000-001",
            title = "Test label",
            assetId = "000-001"
        )
    }
}
