package net.homelab.labeler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders the label locally. No server round-trip, which is why the app needs
 * no INTERNET permission.
 *
 * Note there is deliberately NO dithering here. Dithering exists for
 * photographs; applying it to a QR code destroys module edges and wrecks
 * scannability. Everything drawn is already pure black or white, so a hard
 * threshold is both correct and sharper.
 */
object LabelRenderer {

    /** 203.2 dpi works out to exactly 8 dots per millimetre. */
    const val DOTS_PER_MM = 8

    data class Label(
        val url: String,
        val title: String?,
        val assetId: String?
    )

    fun render(label: Label, widthMm: Int, heightMm: Int): Bitmap {
        // Raster width must be a whole number of bytes.
        val rawWidth = widthMm * DOTS_PER_MM
        val width = (rawWidth / 8) * 8
        val height = heightMm * DOTS_PER_MM

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val margin = (1.5 * DOTS_PER_MM).toInt()
        val gutter = DOTS_PER_MM

        // QR is square and fills the label height minus margins.
        val qrSize = height - (margin * 2)
        val qr = encodeQr(label.url, qrSize)
        canvas.drawBitmap(qr, margin.toFloat(), margin.toFloat(), null)

        // Text column occupies whatever is left to the right of the QR.
        val textLeft = margin + qrSize + gutter
        val textWidth = width - textLeft - margin
        if (textWidth > 20) {
            drawTextColumn(canvas, label, textLeft.toFloat(), textWidth, height, margin)
        }

        return bmp
    }

    private fun drawTextColumn(
        canvas: Canvas,
        label: Label,
        left: Float,
        maxWidth: Int,
        height: Int,
        margin: Int
    ) {
        val title = label.title?.takeIf { it.isNotBlank() }
        val assetId = label.assetId?.takeIf { it.isNotBlank() }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 4.5f * DOTS_PER_MM
        }
        val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.MONOSPACE
            textSize = 3.5f * DOTS_PER_MM
        }

        // Shrink the title until at most two wrapped lines fit.
        var titleLines: List<String> = emptyList()
        if (title != null) {
            while (titlePaint.textSize > 2f * DOTS_PER_MM) {
                titleLines = wrap(title, titlePaint, maxWidth, maxLines = 2)
                if (titleLines.joinToString(" ").length >= title.length) break
                titlePaint.textSize -= 2f
            }
        }

        var y = margin + titlePaint.textSize
        for (line in titleLines) {
            canvas.drawText(line, left, y, titlePaint)
            y += titlePaint.textSize * 1.15f
        }

        if (assetId != null) {
            // Pin the asset ID to the bottom of the label.
            val baseline = (height - margin).toFloat()
            if (baseline > y) {
                canvas.drawText(assetId, left, baseline, idPaint)
            }
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Int, maxLines: Int): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines += current.toString()
        return lines.take(maxLines)
    }

    private fun encodeQr(content: String, size: Int): Bitmap {
        val hints = mapOf(
            // M is the sweet spot: L is fragile on thermal stock, H bloats the
            // module count and makes each module too small to scan.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    /**
     * Packs to 1bpp, MSB first, set bit = black dot, which is what
     * ESC/POS `GS v 0` expects.
     */
    fun toRaster(bmp: Bitmap): Raster {
        val width = bmp.width
        val height = bmp.height
        val bytesPerLine = width / 8
        val out = ByteArray(bytesPerLine * height)
        val row = IntArray(width)

        for (y in 0 until height) {
            bmp.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val p = row[x]
                val luma = ((p shr 16 and 0xFF) * 299 +
                        (p shr 8 and 0xFF) * 587 +
                        (p and 0xFF) * 114) / 1000
                if (luma < 128) {
                    val idx = y * bytesPerLine + (x / 8)
                    out[idx] = (out[idx].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        return Raster(out, bytesPerLine, height)
    }

    data class Raster(val data: ByteArray, val bytesPerLine: Int, val lines: Int)
}
