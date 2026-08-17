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

    /** Below this a QR has too few dots per module to scan reliably. */
    private const val MIN_QR = 96

    /** Space reserved for text so the QR cannot squeeze it out entirely. */
    private const val MIN_TEXT_WIDTH = 12 * DOTS_PER_MM
    private const val MIN_TEXT_HEIGHT = 7 * DOTS_PER_MM

    data class Label(
        val url: String,
        val title: String?,
        val assetId: String?
    )

    fun render(label: Label, widthMm: Int, heightMm: Int): Bitmap {
        // Raster width must be a whole number of bytes.
        val width = ((widthMm * DOTS_PER_MM) / 8) * 8
        val height = heightMm * DOTS_PER_MM

        val bmp = Bitmap.createBitmap(
            width.coerceAtLeast(8),
            height.coerceAtLeast(8),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val margin = (1.5 * DOTS_PER_MM).toInt()
        val gutter = DOTS_PER_MM

        val innerW = width - margin * 2
        val innerH = height - margin * 2
        if (innerW < MIN_QR || innerH < MIN_QR) return bmp

        val title = label.title?.takeIf { it.isNotBlank() }
        val assetId = label.assetId?.takeIf { it.isNotBlank() }
        val hasText = title != null || assetId != null

        /*
         * Text goes beside the QR on stock wider than it is tall, and beneath
         * it otherwise.
         *
         * The previous layout always sized the QR to the label height and put
         * text to its right, which is correct for 50x30 and wrong for anything
         * portrait: on 50x70 the QR came out 536 dots square inside a 400 dot
         * wide bitmap and was simply clipped.
         */
        val beside = innerW >= innerH
        val maxQr = minOf(innerW, innerH)
        val budget = when {
            !hasText -> maxQr
            beside -> innerW - MIN_TEXT_WIDTH - gutter
            else -> innerH - MIN_TEXT_HEIGHT - gutter
        }
        val qrSize = budget.coerceIn(minOf(MIN_QR, maxQr), maxQr)

        val qrLeft: Int
        val qrTop: Int
        val textLeft: Int
        val textTop: Int
        val textWidth: Int
        val textHeight: Int

        if (beside) {
            qrLeft = margin
            qrTop = margin + (innerH - qrSize) / 2
            textLeft = margin + qrSize + gutter
            textTop = margin
            textWidth = width - textLeft - margin
            textHeight = innerH
        } else {
            qrLeft = margin + (innerW - qrSize) / 2
            qrTop = margin
            textLeft = margin
            textTop = margin + qrSize + gutter
            textWidth = innerW
            textHeight = height - textTop - margin
        }

        canvas.drawBitmap(
            encodeQr(label.url, qrSize),
            qrLeft.toFloat(),
            qrTop.toFloat(),
            null
        )

        if (hasText && textWidth > 20 && textHeight > 16) {
            drawText(canvas, title, assetId, textLeft, textTop, textWidth, textHeight)
        }

        return bmp
    }

    /** Title wrapped at the top of its box, asset ID pinned to the bottom. */
    private fun drawText(
        canvas: Canvas,
        title: String?,
        assetId: String?,
        left: Int,
        top: Int,
        maxWidth: Int,
        maxHeight: Int
    ) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Never taller than a third of the box, so two lines plus the asset
            // ID still fit when the box is short.
            textSize = minOf(4.5f * DOTS_PER_MM, maxHeight / 3f)
        }
        val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.MONOSPACE
            textSize = minOf(3.5f * DOTS_PER_MM, maxHeight / 4f)
        }

        // Shrink the title until at most two wrapped lines hold all of it.
        var titleLines: List<String> = emptyList()
        if (title != null) {
            while (titlePaint.textSize > 2f * DOTS_PER_MM) {
                titleLines = wrap(title, titlePaint, maxWidth, maxLines = 2)
                if (titleLines.joinToString(" ").length >= title.length) break
                titlePaint.textSize -= 2f
            }
            if (titleLines.isEmpty()) titleLines = wrap(title, titlePaint, maxWidth, maxLines = 2)
        }

        var y = top + titlePaint.textSize
        for (line in titleLines) {
            canvas.drawText(line, left.toFloat(), y, titlePaint)
            y += titlePaint.textSize * 1.15f
        }

        if (assetId != null) {
            val baseline = (top + maxHeight).toFloat()
            if (baseline > y - titlePaint.textSize) {
                canvas.drawText(assetId, left.toFloat(), baseline, idPaint)
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
