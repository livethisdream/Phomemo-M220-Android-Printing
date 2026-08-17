package net.homelab.labeler

/**
 * Command stream for Phomemo label printers.
 *
 * Transcribed from the Web Bluetooth implementation at
 * phomymo.affordablemagic.net, which drives this hardware correctly. Earlier
 * versions of this file used the M110 sequence taken from
 * `vivier/phomemo-tools` (GPL-3.0) on the assumption it covered the whole
 * M-series. It does not: printers.json assigns the M220 the **m-series**
 * protocol, which shares almost nothing with the M110 one.
 *
 * The two are not variations on a theme:
 *
 *   m-series   ESC @ init, ESC 7 heat, GS | density, GS v 0 raster, ESC J feed
 *   m110       ESC N speed, ESC N density, 1F 11 media, GS v 0 raster, 1F F0 footer
 *
 * Both are kept because the family spans both, and because a device whose
 * advertised name matches no known pattern cannot be assigned one by
 * inspection.
 *
 * Commands are emitted as discrete steps with the reference implementation's
 * inter-command delays rather than as one concatenated buffer. ESC @ in
 * particular needs time to settle before anything follows it, and chunking a
 * single buffer at 128 bytes would split commands at arbitrary boundaries.
 */
object PhomemoM220 {

    enum class Protocol { M_SERIES, M110 }

    /**
     * One write plus the pause that follows it. [chunked] marks the bulk raster,
     * which the transport splits into 128-byte writes.
     */
    class Step(
        val bytes: ByteArray,
        val delayAfterMs: Long,
        val chunked: Boolean = false
    )

    const val MEDIA_LABEL_WITH_GAPS = 0x0a
    const val MEDIA_CONTINUOUS = 0x0b
    const val MEDIA_LABEL_WITH_MARKS = 0x26

    /**
     * Head width in bytes. At 8 dots/mm this equals the width in mm: 72 for
     * M220/M221/M260, 48 for M110/M120, 76 for M200.
     */
    const val DEFAULT_HEAD_WIDTH_BYTES = 72

    /** Dots fed after a label. The reference default. */
    const val DEFAULT_FEED_DOTS = 32

    /** ESC 7 heat time per density step 1-8. Higher is darker. */
    private val HEAT_TIMES = intArrayOf(40, 60, 80, 100, 120, 140, 160, 200)

    fun buildJob(
        raster: LabelRenderer.Raster,
        density: Int,
        protocol: Protocol = Protocol.M_SERIES,
        headWidthBytes: Int = DEFAULT_HEAD_WIDTH_BYTES,
        feedDots: Int = DEFAULT_FEED_DOTS,
        mediaType: Int = MEDIA_LABEL_WITH_GAPS
    ): List<Step> {
        val padded = padToHead(raster, headWidthBytes)
        val d = density.coerceIn(1, 8)

        return when (protocol) {
            Protocol.M_SERIES -> listOf(
                Step(byteArrayOf(0x1b, 0x40), 100),                       // ESC @
                Step(escHeat(HEAT_TIMES[d - 1]), 30),                     // ESC 7
                Step(byteArrayOf(0x1d, 0x7c, d.toByte()), 50),            // GS | n
                Step(rasterHeader(padded), 0),
                Step(padded.data, 300, chunked = true),
                Step(byteArrayOf(0x1b, 0x4a, feedDots.toByte()), 800)     // ESC J
            )

            Protocol.M110 -> listOf(
                Step(byteArrayOf(0x1b, 0x4e, 0x0d, 0x05), 30),            // ESC N speed
                Step(byteArrayOf(0x1b, 0x4e, 0x04, m110Density(d)), 30),  // ESC N density
                Step(byteArrayOf(0x1f, 0x11, mediaType.toByte()), 30),    // media type
                Step(rasterHeader(padded), 0),
                Step(padded.data, 300, chunked = true),
                Step(
                    byteArrayOf(0x1f, 0xf0.toByte(), 0x05, 0x00, 0x1f, 0xf0.toByte(), 0x03, 0x00),
                    500
                )
            )
        }
    }

    /** ESC 7: max dots, heat time, heat interval. */
    private fun escHeat(heatTime: Int) =
        byteArrayOf(0x1b, 0x37, 0x07, heatTime.toByte(), 0x02)

    /** The M110 scale runs to 15; map our 1-8 onto roughly 6-15. */
    private fun m110Density(d: Int) = (5 + d * 1.25).toInt().coerceIn(1, 15).toByte()

    /**
     * GS v 0. Width is a single byte plus a zero high byte - the head is never
     * wider than 255 bytes - while the line count is a full 16-bit
     * little-endian value, so one block covers any label.
     */
    private fun rasterHeader(raster: LabelRenderer.Raster) = byteArrayOf(
        0x1d, 0x76, 0x30, 0x00,
        raster.bytesPerLine.toByte(), 0x00,
        (raster.lines and 0xFF).toByte(), ((raster.lines shr 8) and 0xFF).toByte()
    )

    /**
     * Widens each line to the head width, centering the content.
     *
     * The printer reads exactly head-width bytes per line. A short line is not
     * a narrow label - it desynchronises the block, and every subsequent line
     * is assembled from the wrong bytes.
     *
     * Centered because that is what the reference uses for any device it does
     * not recognize, which includes units advertising under the Q... scheme.
     */
    private fun padToHead(
        raster: LabelRenderer.Raster,
        headWidthBytes: Int
    ): LabelRenderer.Raster {
        if (raster.bytesPerLine >= headWidthBytes) return raster

        val leftPad = (headWidthBytes - raster.bytesPerLine) / 2
        val out = ByteArray(headWidthBytes * raster.lines)
        for (y in 0 until raster.lines) {
            System.arraycopy(
                raster.data, y * raster.bytesPerLine,
                out, y * headWidthBytes + leftPad,
                raster.bytesPerLine
            )
        }
        return LabelRenderer.Raster(out, headWidthBytes, raster.lines)
    }
}
