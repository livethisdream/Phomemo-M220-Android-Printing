package net.homelab.labeler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.roundToInt

/**
 * Draws a [LabelDesign.Doc] at print resolution.
 *
 * The editor displays this bitmap rather than drawing its own approximation of
 * it, so the preview cannot drift from what the head receives. Selection
 * handles and guides are overlaid on top, never baked in.
 */
object DesignRenderer {

    private const val DPMM = LabelRenderer.DOTS_PER_MM

    fun render(doc: LabelDesign.Doc, widthMm: Int, heightMm: Int): Bitmap {
        val width = ((widthMm * DPMM) / 8) * 8
        val height = heightMm * DPMM

        val bmp = Bitmap.createBitmap(
            width.coerceAtLeast(8),
            height.coerceAtLeast(8),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        for (element in doc.elements) {
            when (element) {
                is LabelDesign.Element.Qr -> drawQr(canvas, element)
                is LabelDesign.Element.Text -> drawText(canvas, element)
                is LabelDesign.Element.Picture -> drawPicture(canvas, element)
            }
        }
        return bmp
    }

    private fun dots(mm: Float) = (mm * DPMM).roundToInt()

    private fun drawQr(canvas: Canvas, el: LabelDesign.Element.Qr) {
        val size = minOf(dots(el.w), dots(el.h))
        if (size < 8 || el.content.isEmpty()) return

        val hints = mapOf(
            // M is the sweet spot: L is fragile on thermal stock, H bloats the
            // module count until each module is too small to resolve.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = runCatching {
            QRCodeWriter().encode(el.content, BarcodeFormat.QR_CODE, size, size, hints)
        }.getOrNull() ?: return

        val qr = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                qr.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        canvas.drawBitmap(qr, dots(el.x).toFloat(), dots(el.y).toFloat(), null)
    }

    private fun drawPicture(canvas: Canvas, el: LabelDesign.Element.Picture) {
        val w = dots(el.w)
        val h = dots(el.h)
        if (w < 1 || h < 1) return

        // Convert in isolation so the element's own mode applies, then blit.
        val flattened = ImageLabel.renderInto(el.bitmap, w, h, el.mode)
        canvas.drawBitmap(
            flattened,
            null,
            Rect(dots(el.x), dots(el.y), dots(el.x) + w, dots(el.y) + h),
            null
        )
    }

    private fun drawText(canvas: Canvas, el: LabelDesign.Element.Text) {
        if (el.text.isBlank()) return

        val left = dots(el.x)
        val top = dots(el.y)
        val boxW = dots(el.w)
        val boxH = dots(el.h)
        if (boxW < 4 || boxH < 4) return

        if (el.invert) {
            canvas.drawRect(
                left.toFloat(), top.toFloat(),
                (left + boxW).toFloat(), (top + boxH).toFloat(),
                Paint().apply { color = Color.BLACK }
            )
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (el.invert) Color.WHITE else Color.BLACK
            typeface = when {
                el.monospace -> Typeface.MONOSPACE
                el.bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.DEFAULT
            }
            textSize = (el.sizeMm * DPMM).coerceAtLeast(6f)
        }

        // Shrink until the wrapped text fits the box it was given. The element
        // has an explicit size, so overflowing it would be a lie about layout.
        var lines = wrap(el.text, paint, boxW)
        while (
            paint.textSize > 6f &&
            (lines.size * paint.textSize * 1.15f > boxH || lines.any { paint.measureText(it) > boxW })
        ) {
            paint.textSize -= 1f
            lines = wrap(el.text, paint, boxW)
        }

        var y = top + paint.textSize
        for (line in lines) {
            if (y > top + boxH + paint.textSize) break
            val x = when (el.align) {
                LabelDesign.Align.LEFT -> left.toFloat()
                LabelDesign.Align.CENTER -> left + (boxW - paint.measureText(line)) / 2f
                LabelDesign.Align.RIGHT -> left + boxW - paint.measureText(line)
            }
            canvas.drawText(line, x, y, paint)
            y += paint.textSize * 1.15f
        }
    }

    /** Greedy word wrap, breaking over-long words rather than overflowing. */
    private fun wrap(text: String, paint: Paint, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            var current = StringBuilder()
            for (word in paragraph.split(" ")) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                    continue
                }
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                var rest = word
                while (paint.measureText(rest) > maxWidth && rest.length > 1) {
                    var cut = rest.length
                    while (cut > 1 && paint.measureText(rest.take(cut)) > maxWidth) cut--
                    lines += rest.take(cut)
                    rest = rest.drop(cut)
                }
                current = StringBuilder(rest)
            }
            lines += current.toString()
        }
        return lines
    }
}
