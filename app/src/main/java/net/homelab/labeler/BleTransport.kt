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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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

    /**
     * Devices seen by the most recent scan, kept so a connection can reuse the
     * scanner's own BluetoothDevice rather than rebuilding one from a MAC.
     */
    private val lastSeen = ConcurrentHashMap<String, BluetoothDevice>()

    class PrinterNotFound(msg: String) : IOException(msg)
    class PrinterProtocol(msg: String) : IOException(msg)

    data class Found(val address: String, val name: String?) {
        val label: String get() = name ?: address
    }

    /**
     * A writable characteristic the printer exposes. Surfaced to the UI because
     * a write to the wrong one cannot be detected from this side: BLE
     * write-without-response is unacknowledged, so the stack reports success
     * whether or not anything is listening. Only the user, looking at the
     * printer, can tell the difference.
     */
    data class Endpoint(
        val service: UUID,
        val characteristic: UUID,
        val properties: Int
    ) {
        val acceptsWithResponse: Boolean
            get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0

        val acceptsWithoutResponse: Boolean
            get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

        /** e.g. "ff02 in ff00  (write, write-no-response)" */
        val label: String
            get() {
                val modes = buildList {
                    if (acceptsWithResponse) add("write")
                    if (acceptsWithoutResponse) add("write-no-response")
                }.joinToString(", ")
                return "${shortUuid(characteristic)} in ${shortUuid(service)}\n($modes)"
            }
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
        val devices = scanRaw(filters = null, timeoutMs = timeoutMs, stopOnFirstMatch = false)
        return devices
            .map { Found(it.address, runCatching { it.name }.getOrNull()) }
            .sortedWith(
                compareByDescending<Found> { looksLikePrinter(it.name) }
                    .thenBy { it.name == null }
                    .thenBy { it.label }
            )
    }

    /**
     * Runs a scan and returns the live BluetoothDevice objects, caching them by
     * address. Handing back the real objects matters - see [resolve].
     */
    private fun scanRaw(
        filters: List<ScanFilter>?,
        timeoutMs: Long,
        stopOnFirstMatch: Boolean
    ): List<BluetoothDevice> {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw PrinterNotFound("Bluetooth is turned off")

        val seen = Collections.synchronizedMap(LinkedHashMap<String, BluetoothDevice>())
        val stopped = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seen.putIfAbsent(result.device.address, result.device)
                lastSeen[result.device.address] = result.device
                if (stopOnFirstMatch) stopped.countDown()
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
            scanner.startScan(filters, settings, callback)
            stopped.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: SecurityException) {
            throw PrinterNotFound("Bluetooth scan permission has not been granted")
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        // The stack needs a moment after a scan stops before it will accept a
        // connection. Connecting into the tail of a scan is a documented source
        // of spurious GATT failures.
        Thread.sleep(POST_SCAN_SETTLE_MS)

        return synchronized(seen) { seen.values.toList() }
    }

    /**
     * Turns a saved address back into a connectable device.
     *
     * `getRemoteDevice(mac)` is the obvious way to do this and it is a trap: a
     * device rebuilt from a bare MAC string is assumed to use a **public**
     * address. These printers advertise a **random** address, and connecting
     * with the wrong address type fails with the maddeningly generic GATT
     * error 133 - the same code Android uses for a dozen unrelated problems.
     *
     * The address type is not recoverable from the string, so the only reliable
     * fix is to use the actual BluetoothDevice the scanner handed us. Cached
     * from the last scan when possible, re-scanned for by address when not.
     */
    private fun resolve(address: String): BluetoothDevice {
        lastSeen[address]?.let { return it }

        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val found = scanRaw(listOf(filter), RESOLVE_SCAN_MS, stopOnFirstMatch = true)

        return found.firstOrNull { it.address == address }
            ?: throw PrinterNotFound(
                "Could not find the saved printer nearby. Check it is powered on " +
                    "and in range, or pick it again with Find printer."
            )
    }

    /**
     * Connects, lists every writable characteristic, disconnects. Feeds the
     * diagnostics screen so a wrong-endpoint guess can be corrected by hand
     * instead of presenting as a print that silently does nothing.
     */
    @Throws(IOException::class)
    fun endpoints(mac: String?): List<Endpoint> {
        val adapter = adapter ?: throw PrinterNotFound("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw PrinterNotFound("Bluetooth is turned off")
        val address = mac ?: throw PrinterNotFound(
            "No printer selected. Open Label settings and tap Find printer."
        )
        return Session(resolve(address)).use { it.enumerate() }
    }

    /**
     * Asks the printer to describe itself: paper, cover, battery, media type.
     *
     * The printer knows exactly why it refused a job and says so over BLE, but
     * it also flashes the same message on its own screen and clears it in well
     * under a second. This reads the version that stays still.
     */
    @Throws(IOException::class)
    fun status(mac: String?, preferred: UUID? = null): List<String> {
        val adapter = adapter ?: throw PrinterNotFound("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw PrinterNotFound("Bluetooth is turned off")
        val address = mac ?: throw PrinterNotFound(
            "No printer selected. Open Label settings and tap Find printer."
        )
        return Session(resolve(address)).use { it.queryStatus(preferred) }
    }

    /**
     * Opens a connection, writes the job, disconnects.
     *
     * [preferred] pins the characteristic the job goes to, overriding
     * discovery, and is what the diagnostics screen saves once the user has
     * confirmed which one actually produces a label.
     */
    @Throws(IOException::class)
    fun send(mac: String?, payload: ByteArray, preferred: UUID? = null) {
        val adapter = adapter ?: throw PrinterNotFound("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw PrinterNotFound("Bluetooth is turned off")

        val address = mac ?: throw PrinterNotFound(
            "No printer selected. Open Label settings and tap Find printer."
        )
        val device = resolve(address)

        // 133 is frequently transient - a busy stack, a peripheral still tearing
        // down a previous link. Retrying with a fresh GATT client clears it.
        //
        // Only ever retry a job that wrote nothing, though. Re-sending one that
        // died part way through would feed a second label through the head on
        // top of a half-printed one.
        var lastError: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val session = Session(device)
            try {
                session.use { it.write(payload, preferred) }
                return
            } catch (e: IOException) {
                lastError = e
                if (session.bytesWritten > 0) throw e
                if (attempt < CONNECT_ATTEMPTS - 1) Thread.sleep(RETRY_BACKOFF_MS)
            }
        }
        throw lastError ?: PrinterProtocol("Printing failed for an unknown reason")
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

        /** Status frames pushed by the printer, in arrival order. */
        private val notifications = LinkedBlockingQueue<ByteArray>()
        private val descriptorWritten = CountDownLatch(1)

        /** Guards the retry in [send] against reprinting a partial label. */
        @Volatile var bytesWritten = 0
            private set

        private var gatt: BluetoothGatt? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS -> connected.countDown()

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connected.count > 0L) failure = describeConnectFailure(status)
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

            // Two overloads: the value-carrying one arrives on API 33+, the
            // deprecated one below it on everything older.
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                notifications.offer(value)
            }

            @Deprecated("Superseded on API 33+ by the overload carrying the value.")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    characteristic.value?.let { notifications.offer(it.copyOf()) }
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: android.bluetooth.BluetoothGattDescriptor,
                status: Int
            ) {
                descriptorWritten.countDown()
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

        /** Connect + discover, shared by [write] and [enumerate]. */
        private fun open(): BluetoothGatt {
            gatt = connectOnMainThread()
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
            return g
        }

        /**
         * Subscribes to the printer's status characteristic. Best effort - a
         * printer that pushes nothing is still printable, we just lose the
         * ability to hear why a job failed.
         */
        private fun enableNotifications(g: BluetoothGatt): Boolean {
            val notifiable = g.services
                .filter { it.uuid !in HOUSEKEEPING_SERVICES }
                .flatMap { it.characteristics }
                .filter { it.properties and NOTIFIABLE != 0 }

            // ff03 is the documented status channel; fall back to whatever else
            // is notifiable so other models in the family still report.
            val notifyChar = notifiable.firstOrNull { it.uuid == STATUS_CHARACTERISTIC }
                ?: notifiable.firstOrNull()
                ?: return false

            if (!g.setCharacteristicNotification(notifyChar, true)) return false

            // Subscribing is only half of it: the Client Characteristic
            // Configuration descriptor is what actually tells the peripheral
            // to start sending. Without this write, nothing arrives.
            val cccd = notifyChar.getDescriptor(CCCD) ?: return false
            val enable = if (notifyChar.properties and
                BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            ) {
                android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    g.writeDescriptor(cccd)
                }
            }
            descriptorWritten.await(DESCRIPTOR_MS, TimeUnit.MILLISECONDS)
            return true
        }

        /** Asks the printer about itself and decodes whatever comes back. */
        fun queryStatus(preferred: UUID?): List<String> {
            val g = open()
            val lines = mutableListOf<String>()

            if (!enableNotifications(g)) {
                return listOf("This device pushes no status frames, so it cannot report its state.")
            }

            val target = resolveTarget(g, preferred)
            lines += "Asking on ${shortUuid(target.uuid)}"

            for ((name, command) in PhomemoStatus.QUERIES) {
                notifications.clear()
                val ack = CountDownLatch(1)
                writeAck = ack
                writeChunk(g, target, command, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                ack.await(WRITE_MS, TimeUnit.MILLISECONDS)

                val reply = notifications.poll(QUERY_REPLY_MS, TimeUnit.MILLISECONDS)
                lines += when {
                    reply == null -> "$name: no reply"
                    else -> PhomemoStatus.decode(reply) ?: "$name: ${PhomemoStatus.hex(reply)}"
                }
            }
            return lines
        }

        fun enumerate(): List<Endpoint> = open().services
            .filter { it.uuid !in HOUSEKEEPING_SERVICES }
            .flatMap { service ->
                service.characteristics
                    .filter { it.properties and WRITABLE != 0 }
                    .map { Endpoint(service.uuid, it.uuid, it.properties) }
            }

        private fun resolveTarget(
            g: BluetoothGatt,
            preferred: UUID?
        ): BluetoothGattCharacteristic = preferred?.let { wanted ->
            g.services.asSequence()
                .flatMap { it.characteristics.asSequence() }
                .firstOrNull { it.uuid == wanted && it.properties and WRITABLE != 0 }
                ?: throw PrinterProtocol(
                    "The chosen characteristic is not on this printer any more. " +
                        "Re-run Printer diagnostics."
                )
        } ?: pickCharacteristic(g) ?: throw PrinterProtocol(
            "Connected, but found nothing on this device that accepts a print job. " +
                "It may not be a printer."
        )

        fun write(payload: ByteArray, preferred: UUID?) {
            val g = open()
            enableNotifications(g)
            val target = resolveTarget(g, preferred)

            // Match the known-good web implementation: unacknowledged writes
            // where the characteristic supports them, falling back otherwise.
            val noResponse =
                target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val writeType = if (noResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            // 128 bytes, not MTU-3.
            //
            // Filling a negotiated 517-byte MTU is the obvious thing to do and
            // it overruns this print head: the working web implementation sends
            // 128-byte chunks with a 20 ms gap regardless of what the MTU
            // negotiation allows, and that pacing is not incidental. ATT
            // overhead is still 3 bytes, so a small MTU caps it further.
            val chunkSize = minOf(VENDOR_CHUNK, mtu - 3).coerceAtLeast(MIN_CHUNK)

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
                bytesWritten = offset
                // Pace every chunk, not just unacknowledged ones. The head
                // needs the gap to drain; an ATT-level ack only says the radio
                // took the bytes, not that the printer consumed them.
                Thread.sleep(CHUNK_DELAY_MS)
            }

            // Let the head finish before the connection drops.
            Thread.sleep(FINISH_MS)

            // The job was accepted at the transport layer, which says nothing
            // about whether it printed. If the printer pushed a complaint while
            // we were writing, that is the real outcome - surface it instead of
            // reporting a success nobody can see.
            drainComplaint()?.let { throw PrinterProtocol(it) }
        }

        /** The first pushed status frame that reads as a problem, if any. */
        private fun drainComplaint(): String? {
            val frames = mutableListOf<ByteArray>()
            notifications.drainTo(frames)
            for (frame in frames) {
                val reading = PhomemoStatus.decode(frame) ?: continue
                if (COMPLAINTS.any { reading.contains(it) }) {
                    return "Printer says: $reading"
                }
            }
            return null
        }

        /**
         * `connectGatt` is called from a background dispatcher here, and the
         * Bluetooth stack has a long history of returning status 133 when it is
         * invoked off the main thread. Hopping to the main looper for the call
         * itself costs nothing; callbacks still arrive on a binder thread and
         * are still consumed by the latches below.
         */
        private fun connectOnMainThread(): BluetoothGatt? {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            }

            val holder = arrayOfNulls<BluetoothGatt>(1)
            val posted = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                holder[0] = runCatching {
                    device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull()
                posted.countDown()
            }
            posted.await(CONNECT_DISPATCH_MS, TimeUnit.MILLISECONDS)
            return holder[0]
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

    /**
     * 133 is GATT_ERROR, the stack's catch-all. It means "something went wrong"
     * and nothing more, so say what is actually worth trying.
     */
    private fun describeConnectFailure(status: Int): String = when (status) {
        GATT_ERROR -> "Could not connect to the printer. Power-cycle the M220, " +
            "then try again. If it keeps failing, re-pick it with Find printer."
        GATT_CONNECTION_TIMEOUT -> "The printer did not respond. Check it is powered on and in range."
        else -> "Could not connect to the printer (status $status)"
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
         * ff02 in service ff00 is what the working web implementation writes
         * to; the Microchip transparent-UART characteristic covers models that
         * use that stack instead. Discovery handles anything else.
         */
        val PREFERRED_CHARACTERISTICS = setOf(
            UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")
        )

        /** ff03 - where the printer pushes its 0x1A status frames. */
        val STATUS_CHARACTERISTIC: UUID =
            UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")

        const val WRITABLE = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        const val NOTIFIABLE = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE

        /** Client Characteristic Configuration - the subscribe switch. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Readings that mean the job did not come out. */
        val COMPLAINTS = listOf("OUT", "OPEN", "ERROR", "overheated", "empty")

        const val DEFAULT_MTU = 23
        const val PREFERRED_MTU = 517
        /**
         * The chunk size the working web implementation uses, with its pacing.
         * Not derived from the MTU - filling a 517-byte MTU overruns the head.
         */
        const val VENDOR_CHUNK = 128
        const val CHUNK_DELAY_MS = 20L
        const val MIN_CHUNK = 20

        /** BluetoothGatt.GATT_ERROR - not public API, so spelled out. */
        const val GATT_ERROR = 133
        const val GATT_CONNECTION_TIMEOUT = 8

        const val CONNECT_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 700L
        const val POST_SCAN_SETTLE_MS = 250L
        const val CONNECT_DISPATCH_MS = 5_000L

        const val SCAN_MS = 6_000L
        const val RESOLVE_SCAN_MS = 8_000L
        const val CONNECT_MS = 15_000L
        const val DISCOVER_MS = 15_000L
        const val MTU_MS = 3_000L
        const val WRITE_MS = 10_000L
        const val FINISH_MS = 600L
        const val DESCRIPTOR_MS = 3_000L
        const val QUERY_REPLY_MS = 900L
    }
}

/**
 * `0000ff02-0000-1000-8000-00805f9b34fb` reads better as `ff02`.
 *
 * File scope rather than a class member because Endpoint is a nested class and
 * cannot reach the outer instance.
 */
private fun shortUuid(uuid: UUID): String {
    val full = uuid.toString().lowercase()
    return if (full.endsWith("-0000-1000-8000-00805f9b34fb")) {
        full.take(8).trimStart('0').ifEmpty { "0000" }
    } else {
        full.take(8)
    }
}
