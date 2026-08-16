package net.homelab.labeler

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM/SPP), not BLE.
 *
 * Why: vivier's CUPS driver reaches the M220 over rfcomm, and a plain socket
 * sidesteps everything painful about GATT - no service discovery, no MTU
 * negotiation, no write-without-response flow control. It also means the
 * device is bonded in system Settings, so there is no picker on every print.
 *
 * If your unit turns out to expose only BLE, swap this class for a
 * BluetoothGatt implementation and chunk writes to (MTU - 3) bytes. Nothing
 * else in the app changes.
 */
@SuppressLint("MissingPermission") // Caller checks BLUETOOTH_CONNECT first.
class SppTransport {

    class PrinterNotFound(msg: String) : IOException(msg)

    fun bondedPrinters(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices.orEmpty().filter { device ->
            val name = device.name.orEmpty()
            KNOWN_PREFIXES.any { name.contains(it, ignoreCase = true) }
        }
    }

    /**
     * Opens a socket, writes the job, closes. Reconnecting per print is fast
     * enough on a bonded device that holding the socket open is rarely worth
     * the lifecycle complexity.
     */
    @Throws(IOException::class)
    fun send(mac: String?, payload: ByteArray) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw PrinterNotFound("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw PrinterNotFound("Bluetooth is turned off")

        val device = when {
            mac != null -> adapter.getRemoteDevice(mac)
            else -> bondedPrinters().firstOrNull()
                ?: throw PrinterNotFound(
                    "No paired Phomemo printer found. Pair the M220 in " +
                        "Settings > Bluetooth first."
                )
        }

        // Discovery running during connect is a classic cause of flaky RFCOMM.
        if (adapter.isDiscovering) adapter.cancelDiscovery()

        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()

            val stream = socket.outputStream
            // Small chunks with a brief pause: the print head buffer is modest
            // and blasting the whole job can drop lines mid-label.
            var offset = 0
            while (offset < payload.size) {
                val n = minOf(CHUNK, payload.size - offset)
                stream.write(payload, offset, n)
                stream.flush()
                offset += n
                Thread.sleep(20)
            }
            // Let the head finish before the socket drops.
            Thread.sleep(400)
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Nothing useful to do on a failed close.
            }
        }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val CHUNK = 512
        val KNOWN_PREFIXES = listOf("M220", "M221", "M200", "M110", "M120", "Phomemo")
    }
}
