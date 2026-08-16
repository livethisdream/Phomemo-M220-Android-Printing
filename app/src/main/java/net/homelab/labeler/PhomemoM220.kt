package net.homelab.labeler

import java.io.ByteArrayOutputStream

/**
 * Command stream for the M110/M120/M220 family.
 *
 * Protocol per vivier/phomemo-tools (GPL-3.0), reverse-engineered from packet
 * captures of the vendor app. This file expresses the same byte sequences; if
 * you redistribute this app, check whether that lineage obliges you to release
 * under GPL-3.0 too.
 *
 * The M02 family uses a DIFFERENT header and footer - do not reuse this for an
 * M02 without checking.
 */
object PhomemoM220 {

    const val MEDIA_LABEL_WITH_GAPS = 0x0a
    const val MEDIA_CONTINUOUS = 0x0b
    const val MEDIA_LABEL_WITH_MARKS = 0x26

    /** GS v 0 tops out at 255 lines, so tall labels are sent as several blocks. */
    private const val MAX_LINES_PER_BLOCK = 255

    fun buildJob(
        raster: LabelRenderer.Raster,
        speed: Int,
        density: Int,
        mediaType: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // --- Header ---
        out.write(byteArrayOf(0x1b, 0x4e, 0x0d, speed.toByte()))      // print speed
        out.write(byteArrayOf(0x1b, 0x4e, 0x04, density.toByte()))    // print density
        out.write(byteArrayOf(0x1f, 0x11, mediaType.toByte()))        // media type

        // --- Raster blocks ---
        var line = 0
        while (line < raster.lines) {
            val chunk = minOf(MAX_LINES_PER_BLOCK, raster.lines - line)

            out.write(byteArrayOf(0x1d, 0x76, 0x30, 0x00))            // GS v 0, mode 0
            out.write(le16(raster.bytesPerLine))                      // bytes per line
            out.write(le16(chunk))                                    // lines in block

            val start = line * raster.bytesPerLine
            out.write(raster.data, start, chunk * raster.bytesPerLine)

            line += chunk
        }

        // --- Footer: feed to the gap and finish ---
        out.write(byteArrayOf(0x1f, 0xf0.toByte(), 0x05, 0x00))
        out.write(byteArrayOf(0x1f, 0xf0.toByte(), 0x03, 0x00))

        return out.toByteArray()
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
