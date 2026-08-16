package net.homelab.labeler

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Low Energy (GATT).
 *
 * The original design here was Bluetooth Classic (RFCOMM/SPP), on the theory
 * that vivier's CUPS driver reaches the M220 over rfcomm on Linux. That does
 * not carry over: the unit advertises BLE only to Android, so a Classic bond
 * never sticks and `createRfcommSocketToServiceRecord` has nothing to talk to.
 *
 * The giveaway was that a Web Bluetooth page drives this printer successfully.
 * Web Bluetooth cannot speak Classic SPP at all - the spec only exposes GATT -
 * so anything a browser can print to is reachable over BLE by definition.
 *
 * A happy consequence: GATT needs no bond. There is no pairing step to lose,
 * which is what the Settings > Bluetooth entry kept dropping.
 *
 * The byte stream is unchanged. PhomemoM220 and LabelRenderer do not know or
 * care which transport carries them.
 */
@SuppressLint("MissingPermission") // Caller checks permissions before every entry point.
class BleTransport(context: Context) {

    private val appContext = context.applicationContext

    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    class PrinterNotFound(msg: String) : IOException(msg)
    class PrinterProtocol(msg: String) : IOException(msg)

    data class Found(val address: String, val name: String?) {
        val label: String get() = name ?: address
    }

    /**
     * Blocking scan. Returns everything nearby, sorted so plausible printers
     * float to the top, rather than filtering by name.
     *
     * Filtering would be a trap. The vendor's own web tooling matches prefixes
     * as broad as "M", "D", "P" and "Q" - an M110S advertises as "Q199E..." -
     * and the advertised name routinely differs from what the device prints on
     * its own screen. A name gate that guesses wrong makes a working printer
     * invisible with no way to override it, so the user picks from the list.
     */
    fun scan(timeoutMs: Long = SCAN_MS): List<Found> {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw PrinterNotFound("Bluetooth is turned off")

        val seen = ConcurrentHashMap<String, Found>()
        val stopped = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                seen.putIfAbsent(result.device.address, Found(result.device.address, name))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                stopped.countDown()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
            stopped.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: SecurityException) {
            throw PrinterNotFound("Bluetooth scan permission has not been granted")
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        return seen.values.sortedWith(
            compareByDescending<Found> { looksLikePrinter(it.name) }
                .thenBy { it.name == null }
                .thenBy { it.label }
        )
    }

    /** Opens a connection, writes the job, disconnects. */
    @Throws(IOException::class)
    fun send(mac: String?, payload: ByteArray) {
        val adapter = adapter ?: throw PrinterNotFound("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw PrinterNotFound("Bluetooth is turned off")

        val address = mac ?: throw PrinterNotFound(
            "No printer selected. Open Label settings and tap Find printer."
        )
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            throw PrinterNotFound("Saved printer address is not usable: $address")
        }

        Session(device).use { it.write(payload) }
    }

    /**
     * One connect/write/disconnect cycle. GATT is callback-driven on a binder
     * thread while `send` is called from a background dispatcher, so each step
     * parks on a latch with its own timeout - a printer that goes away
     * mid-connection must surface as an error, not a hang.
     */
    private inner class Session(private val device: BluetoothDevice) : Closeable {

        private val connected = CountDownLatch(1)
        private val discovered = CountDownLatch(1)
        private val mtuSettled = CountDownLatch(1)

        @Volatile private var writeAck: CountDownLatch? = null
        @Volatile private var failure: String? = null
        @Volatile private var mtu = DEFAULT_MTU

        private var gatt: BluetoothGatt? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS -> connected.countDown()

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connected.count > 0L) failure = "Could not connect to the printer (status $status)"
                        else if (failure == null) failure = "The printer dropped the connection (status $status)"
                        releaseAll()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failure = "Could not read the printer's services (status $status)"
                }
                discovered.countDown()
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                mtuSettled.countDown()
            }

            @Deprecated("Kept for API < 33; the newer overload delegates to it.")
            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) failure = "The printer rejected a write (status $status)"
                writeAck?.countDown()
            }
        }

        private fun releaseAll() {
            connected.countDown()
            discovered.countDown()
            mtuSettled.countDown()
            writeAck?.countDown()
        }

        fun write(payload: ByteArray) {
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw PrinterNotFound("Could not open a connection to the printer")
            val g = gatt!!

            await(connected, CONNECT_MS, "Timed out connecting to the printer")

            if (!g.discoverServices()) throw PrinterProtocol("Could not start service discovery")
            await(discovered, DISCOVER_MS, "Timed out reading the printer's services")

            // Best effort. A refused MTU bump just means smaller chunks.
            if (g.requestMtu(PREFERRED_MTU)) {
                mtuSettled.await(MTU_MS, TimeUnit.MILLISECONDS)
            }
            failure?.let { throw PrinterProtocol(it) }

            val target = pickCharacteristic(g) ?: throw PrinterProtocol(
                "Connected, but found nothing on this device that accepts a print job. " +
                    "It may not be a printer."
            )

            val noResponse =
                target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val writeType = if (noResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            // ATT overhead is 3 bytes: one opcode, two handle.
            val chunkSize = (mtu - 3).coerceIn(MIN_CHUNK, MAX_CHUNK)

            var offset = 0
            while (offset < payload.size) {
                val n = minOf(chunkSize, payload.size - offset)
                val ack = CountDownLatch(1)
                writeAck = ack

                if (!writeChunk(g, target, payload.copyOfRange(offset, offset + n), writeType)) {
                    throw PrinterProtocol("The printer would not accept the job")
                }
                if (!ack.await(WRITE_MS, TimeUnit.MILLISECONDS)) {
                    throw PrinterProtocol("Timed out part way through sending the label")
                }
                failure?.let { throw PrinterProtocol(it) }

                offset += n
                // Unacknowledged writes have no back-pressure of their own and
                // the print head buffer is small, so pace them by hand.
                if (noResponse) Thread.sleep(NO_RESPONSE_PACING_MS)
            }

            // Let the head finish before the connection drops.
            Thread.sleep(FINISH_MS)
        }

        private fun await(latch: CountDownLatch, timeoutMs: Long, message: String) {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw PrinterProtocol(message)
            failure?.let { throw PrinterProtocol(it) }
        }

        private fun writeChunk(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            data: ByteArray,
            writeType: Int
        ): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = data
                    g.writeCharacteristic(characteristic)
                }
            }

        override fun close() {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            gatt = null
        }
    }

    /**
     * Finds the characteristic that takes the print job.
     *
     * Discovery rather than a hardcoded UUID, because these printers are split
     * across at least two vendor service families and the mapping to model
     * number is not something worth betting a silent failure on. Known UUIDs
     * are tried first as a fast path; anything writable is the fallback, which
     * is what actually makes this portable across the range.
     */
    private fun pickCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val writable = gatt.services
            .asSequence()
            .filter { it.uuid !in HOUSEKEEPING_SERVICES }
            .flatMap { it.characteristics.asSequence() }
            .filter { it.properties and WRITABLE != 0 }
            .toList()

        return writable.firstOrNull { it.uuid in PREFERRED_CHARACTERISTICS }
            // Unacknowledged writes are how these printers are normally driven.
            ?: writable.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            }
            ?: writable.firstOrNull()
    }

    private fun looksLikePrinter(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        return KNOWN_PREFIXES.any { n.startsWith(it, ignoreCase = true) }
    }

    private companion object {
        /**
         * Sort hints only, never a filter. Taken from the prefixes the vendor's
         * own web tooling matches: M110/M220/M260, D30/D110, P12/PM-241, T02,
         * A30, the Mr.in series, and M110S units that advertise as "Q199E...".
         */
        val KNOWN_PREFIXES = listOf("Phomemo", "Mr.in", "M", "D", "P", "Q", "T", "A")

        /** Generic Access, Generic Attribute, Device Information, Battery. */
        val HOUSEKEEPING_SERVICES = setOf(
            UUID.fromString("00001800-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("00001801-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        )

        /**
         * Fast path. The ff00 family and the Microchip transparent-UART service
         * cover the units documented publicly; discovery handles the rest.
         */
        val PREFERRED_CHARACTERISTICS = setOf(
            UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")
        )

        const val WRITABLE = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        const val DEFAULT_MTU = 23
        const val PREFERRED_MTU = 517
        const val MIN_CHUNK = 20
        const val MAX_CHUNK = 512

        const val SCAN_MS = 6_000L
        const val CONNECT_MS = 15_000L
        const val DISCOVER_MS = 15_000L
        const val MTU_MS = 3_000L
        const val WRITE_MS = 10_000L
        const val NO_RESPONSE_PACING_MS = 8L
        const val FINISH_MS = 600L
    }
}
