package net.homelab.labeler

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import net.homelab.labeler.ui.LabelerApp
import net.homelab.labeler.ui.LabelerTheme

class MainActivity : ComponentActivity() {

    private val vm: LabelerViewModel by viewModels()

    /** What to run once the user answers the permission dialog. */
    private var afterPermission: (() -> Unit)? = null

    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val next = afterPermission
        afterPermission = null
        if (results.values.all { it }) next?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            LabelerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LabelerApp(vm = vm, ensurePermission = ::withBluetoothPermission)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * A share lands here with the page URL and title; anything else leaves the
     * app on its own home screen.
     */
    private fun handleIntent(intent: Intent) {
        parseShare(intent)?.let(vm::updateLabel)
    }

    /**
     * Chrome populates EXTRA_TEXT with the page URL and EXTRA_SUBJECT with the
     * page title, which gives us the item name without an API call.
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

    // --- Permissions ----------------------------------------------------------

    /**
     * From Android 12 this is BLUETOOTH_SCAN + BLUETOOTH_CONNECT, with
     * neverForLocation in the manifest keeping location out of it. Below that
     * there is no such flag and a BLE scan returns nothing without a location
     * grant, whatever the app is actually doing.
     */
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun withBluetoothPermission(action: () -> Unit) {
        val granted = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            action()
        } else {
            afterPermission = action
            requestBluetooth.launch(requiredPermissions())
        }
    }

    private companion object {
        val URL_RE = Regex("""https?://\S+""")
        // Homebox asset URLs look like /a/000-001
        val ASSET_RE = Regex("""/a/([0-9]{3}-[0-9]{3})""")
    }
}
