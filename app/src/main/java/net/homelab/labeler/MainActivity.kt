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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val transport = SppTransport()

    private var pending: LabelRenderer.Label? = null
    private var preview: ImageView? = null
    private var status: TextView? = null

    /** What to run once the user answers the BLUETOOTH_CONNECT dialog. */
    private var afterPermission: (() -> Unit)? = null

    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val next = afterPermission
        afterPermission = null
        if (granted) next?.invoke() else toast("Bluetooth permission is required to reach the printer")
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

        // Listing bonded devices needs BLUETOOTH_CONNECT on Android 12+, and
        // this screen is reachable straight from the launcher, before the user
        // has ever seen the permission dialog. Ask, rather than assume.
        val hasPermission = hasConnectPermission()
        val paired = if (hasPermission) transport.bondedPrinters() else emptyList()

        root.addView(
            body(
                when {
                    !hasPermission ->
                        "Bluetooth permission has not been granted yet, so paired printers cannot be listed."
                    paired.isEmpty() ->
                        "No paired Phomemo printer detected. Pair the M220 in Settings > Bluetooth, then reopen this screen."
                    else ->
                        "Printer: " + paired.joinToString { it.name ?: it.address }
                }
            )
        )

        if (!hasPermission) {
            root.addView(Button(this).apply {
                text = "Grant Bluetooth permission"
                // Re-render the screen so the printer line fills in.
                setOnClickListener { withBluetoothPermission { showSettings() } }
            })
        }

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                prefs.labelWidthMm = width.value() ?: prefs.labelWidthMm
                prefs.labelHeightMm = height.value() ?: prefs.labelHeightMm
                prefs.density = density.value() ?: prefs.density
                // Only overwrite a stored MAC when there is a candidate to
                // replace it with. Otherwise visiting this screen without the
                // permission grant would wipe a printer that was working.
                paired.firstOrNull()?.let { prefs.printerMac = it.address }
                toast("Saved")
                pending?.let { showPreview(it) }
            }
        })

        setContentView(root)
    }

    // --- Printing -------------------------------------------------------------

    private fun requestPrint() = withBluetoothPermission { doPrint() }

    /**
     * BLUETOOTH_CONNECT is a runtime permission from Android 12. Below that it
     * is the install-time BLUETOOTH permission, already granted from the
     * manifest, so there is nothing to ask for.
     */
    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    private fun withBluetoothPermission(action: () -> Unit) {
        if (hasConnectPermission()) {
            action()
        } else {
            afterPermission = action
            requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
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
                    transport.send(prefs.printerMac, job)
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

    private companion object {
        val URL_RE = Regex("""https?://\S+""")
        // Homebox asset URLs look like /a/000-001
        val ASSET_RE = Regex("""/a/([0-9]{3}-[0-9]{3})""")
    }
}
