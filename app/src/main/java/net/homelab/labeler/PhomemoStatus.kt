package net.homelab.labeler

/**
 * Decoder for the status frames the printer pushes over its notify
 * characteristic.
 *
 * Frames are `0x1A <type> <value...>`. The type-to-meaning mapping and the
 * magic value bytes are transcribed from the Web Bluetooth implementation at
 * phomymo.affordablemagic.net, which is known to drive this hardware
 * correctly - so these are observed values, not guesses.
 *
 * This exists because the printer is the only party that knows why a job was
 * rejected. It flashes a message on its own screen and clears it faster than
 * anyone can read, but it sends the same information over BLE.
 */
object PhomemoStatus {

    /** Queries the printer answers, as `0x1F 0x11 <what>`. */
    val QUERIES: List<Pair<String, ByteArray>> = listOf(
        "Paper" to byteArrayOf(0x1F, 0x11, 0x11),
        "Cover" to byteArrayOf(0x1F, 0x11, 0x12),
        "Battery" to byteArrayOf(0x1F, 0x11, 0x08),
        "Label type" to byteArrayOf(0x1F, 0x11, 0x19),
        "Firmware" to byteArrayOf(0x1F, 0x11, 0x07),
        "Serial" to byteArrayOf(0x1F, 0x11, 0x09),
        "Version" to byteArrayOf(0x1F, 0x11, 0x33),
        "Power" to byteArrayOf(0x1F, 0x11, 0x0E)
    )

    fun hex(data: ByteArray): String =
        data.joinToString(" ") { "%02x".format(it) }

    /**
     * Returns a human-readable reading, or null when the frame is not one of
     * the documented shapes. Unrecognised frames are still worth showing as
     * raw hex, which the caller does.
     */
    fun decode(data: ByteArray): String? {
        if (data.size == 2 && data[0] == 0x01.toByte()) return "Result: ${data[1].toInt() and 0xFF}"
        if (data.size == 3 && data[0] == 0x02.toByte()) return "Printer type: ${data[1].toInt() and 0xFF}"

        if (data.size < 3 || data[0] != 0x1A.toByte()) return null

        val type = data[1].toInt() and 0xFF
        val v = data[2].toInt() and 0xFF

        return when (type) {
            0x03 -> "Head: " + when (v) {
                0xA9 -> "overheated"
                0xA8 -> "normal"
                else -> "warm ($v)"
            }

            0x04 -> "Battery: " + when (v) {
                0xA4 -> "empty"
                0xA3 -> "very low"
                0xA2 -> "low"
                0xA1 -> "10%"
                else -> "$v"
            }

            0x05 -> "Cover: " + when (v) {
                0x98 -> "OPEN"
                0x99 -> "closed"
                else -> "unknown ($v)"
            }

            0x06 -> "Paper: " + if (v == 0x88) "OUT" else "ok"

            0x07 -> "Firmware: " + dotted(data, 2)
            0x08 -> "Serial: " + ascii(data, 2)
            0x09 -> "Power: $v"

            0x0B -> "Print status: " + if (v == 0xB8) "ERROR" else "$v"

            0x0C -> "Label type: " + when (v) {
                0x0B -> "continuous"
                0x26 -> "black mark"
                else -> "die-cut with gaps"
            }

            0x0D -> "MAC: " + ascii(data, 2)
            0x0F -> "Print status: " + if (v == 0x0C) "ok" else "$v"
            0x11 -> "Version: " + dotted(data, 2)
            0x17 -> "Chip: $v"

            else -> null
        }
    }

    private fun dotted(data: ByteArray, start: Int): String =
        (start until data.size).joinToString(".") { (data[it].toInt() and 0xFF).toString() }

    private fun ascii(data: ByteArray, start: Int): String =
        String(data, start, data.size - start, Charsets.US_ASCII).trim { it <= ' ' }
}
