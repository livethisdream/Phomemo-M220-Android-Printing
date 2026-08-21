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
     * Where the label stock sits under the print head.
     *
     * Every raster line is padded to the full head width, so this decides where
     * the padding goes - and therefore where the image lands on the paper. It
     * is a property of the printer's paper path, not of the label: printers.json
     * records the M220 as a "right-aligned roll" while other models center.
     *
     * Getting it wrong shifts the whole design sideways and pushes one edge off
     * the label, which the on-screen preview cannot show, because the preview is
     * the label and the error is in where the label sits.
     */
    enum class Alignment { LEFT, CENTER, RIGHT }

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

    /**
     * Dots fed after a label: 15 mm at 8 dots/mm.
     *
     * The reference default is 32 dots, which is 4 mm - far short of the
     * head-to-tear-bar distance on this hardware, so the label stops before it
     * can be torn off. A default that does not accomplish the thing it exists
     * for is worse than no default, because it looks like the feature is
     * broken rather than merely unset.
     */
    const val DEFAULT_FEED_DOTS = 120

    /** ESC 7 heat time per density step 1-8. Higher is darker. */
    private val HEAT_TIMES = intArrayOf(40, 60, 80, 100, 120, 140, 160, 200)

    /**
     * How the label is advanced once printed.
     *
     * Three methods because the obvious one is not reliable on this hardware.
     * The reference implementation notes, in its D-series path, that "ESC J
     * feed is ignored in continuous mode" and works around it by padding the
     * image - so a printer quietly discarding the feed command is a known
     * behavior, not a fault in the sequence.
     */
    enum class FeedMode {
        /**
         * ESC J. What the reference sends. Confirmed inert on this M220 - the
         * write succeeds and the paper does not move - so it is no longer the
         * default, only kept for printers that do honor it.
         */
        COMMAND,

        /**
         * Blank raster lines appended to the image. Cannot be ignored, because
         * the paper has to move for the printer to print the nothing on it.
         */
        BLANK_ROWS,

        /**
         * The M110 footer, which advances to the next die-cut gap. Right
         * distance every time when supported, since the printer measures the
         * stock rather than being told a number.
         */
        GAP
    }

    fun buildJob(
        raster: LabelRenderer.Raster,
        density: Int,
        protocol: Protocol = Protocol.M_SERIES,
        headWidthBytes: Int = DEFAULT_HEAD_WIDTH_BYTES,
        feedDots: Int = DEFAULT_FEED_DOTS,
        feedMode: FeedMode = FeedMode.BLANK_ROWS,
        alignment: Alignment = Alignment.RIGHT,
        mediaType: Int = MEDIA_LABEL_WITH_GAPS
    ): List<Step> {
        val padded = padToHead(raster, headWidthBytes, alignment)
        val d = density.coerceIn(1, 8)

        // Padding the image is the only method that changes the raster itself,
        // so it has to happen before the header quotes a line count.
        val body = if (feedMode == FeedMode.BLANK_ROWS) withBlankRows(padded, feedDots) else padded

        val tail = when (feedMode) {
            FeedMode.COMMAND -> Step(byteArrayOf(0x1b, 0x4a, feedDots.toByte()), 800)
            FeedMode.BLANK_ROWS -> Step(ByteArray(0), 800)
            FeedMode.GAP -> Step(FOOTER_FEED_TO_GAP, 800)
        }

        return when (protocol) {
            Protocol.M_SERIES -> listOf(
                Step(byteArrayOf(0x1b, 0x40), 100),                       // ESC @
                Step(escHeat(HEAT_TIMES[d - 1]), 30),                     // ESC 7
                Step(byteArrayOf(0x1d, 0x7c, d.toByte()), 50),            // GS | n
                Step(rasterHeader(body), 0),
                Step(body.data, 300, chunked = true),
                tail
            )

            Protocol.M110 -> listOf(
                Step(byteArrayOf(0x1b, 0x4e, 0x0d, 0x05), 30),            // ESC N speed
                Step(byteArrayOf(0x1b, 0x4e, 0x04, m110Density(d)), 30),  // ESC N density
                Step(byteArrayOf(0x1f, 0x11, mediaType.toByte()), 30),    // media type
                Step(rasterHeader(padded), 0),
                Step(padded.data, 300, chunked = true),
                Step(FOOTER_FEED_TO_GAP, 500)
            )
        }
    }

    /** Advance to the next die-cut gap. The M110 family's end-of-job footer. */
    private val FOOTER_FEED_TO_GAP =
        byteArrayOf(0x1f, 0xf0.toByte(), 0x05, 0x00, 0x1f, 0xf0.toByte(), 0x03, 0x00)

    /**
     * Appends blank lines to the raster.
     *
     * Zero bits are white, so this prints nothing - but the paper still has to
     * travel under the head for it to do so, which is the point: a printer
     * cannot ignore this the way it can ignore a feed command.
     */
    private fun withBlankRows(
        raster: LabelRenderer.Raster,
        rows: Int
    ): LabelRenderer.Raster {
        if (rows <= 0) return raster
        val extended = raster.data.copyOf(raster.data.size + rows * raster.bytesPerLine)
        return LabelRenderer.Raster(extended, raster.bytesPerLine, raster.lines + rows)
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
     * Widens each line to the head width, placing the content per [alignment].
     *
     * The printer reads exactly head-width bytes per line. A short line is not
     * a narrow label - it desynchronises the block, and every subsequent line
     * is assembled from the wrong bytes.
     */
    private fun padToHead(
        raster: LabelRenderer.Raster,
        headWidthBytes: Int,
        alignment: Alignment
    ): LabelRenderer.Raster {
        if (raster.bytesPerLine >= headWidthBytes) return raster

        val slack = headWidthBytes - raster.bytesPerLine
        val leftPad = when (alignment) {
            Alignment.LEFT -> 0
            Alignment.CENTER -> slack / 2
            Alignment.RIGHT -> slack
        }
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
