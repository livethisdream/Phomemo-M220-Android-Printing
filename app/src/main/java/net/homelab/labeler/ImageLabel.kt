package net.homelab.labeler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlin.math.roundToInt

/**
 * Renders a shared image onto a label.
 *
 * The two conversion modes are not stylistic preferences, they solve different
 * problems. A photograph has continuous tone that a 1-bit head cannot
 * reproduce, so error diffusion trades spatial resolution for apparent grey. A
 * QR code or line drawing is already pure black and white, and diffusing error
 * across it destroys the very module edges a scanner looks for.
 */
object ImageLabel {

    enum class Mode {
        /** Hard cut at mid grey. Correct for QR codes, barcodes, line art. */
        THRESHOLD,

        /** Floyd-Steinberg error diffusion. Correct for photographs. */
        DITHER
    }

    /** Decodes a shared image, downsampling anything absurd on the way in. */
    fun load(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > maxDim * 2 ||
            bounds.outHeight / sample > maxDim * 2
        ) sample *= 2

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /**
     * Fits [source] inside the label, centred on white, and reduces it to pure
     * black and white so the raster packer has nothing left to decide.
     */
    fun render(source: Bitmap, widthMm: Int, heightMm: Int, mode: Mode): Bitmap {
        val width = ((widthMm * LabelRenderer.DOTS_PER_MM) / 8) * 8
        val height = heightMm * LabelRenderer.DOTS_PER_MM
        val margin = LabelRenderer.DOTS_PER_MM

        val canvasBmp = Bitmap.createBitmap(
            width.coerceAtLeast(8),
            height.coerceAtLeast(8),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.WHITE)

        val boxW = (width - margin * 2).coerceAtLeast(1)
        val boxH = (height - margin * 2).coerceAtLeast(1)

        // Contain, not cover. Cropping a QR code to fill the label would be a
        // silent way to make it unscannable.
        val scale = minOf(boxW.toFloat() / source.width, boxH.toFloat() / source.height)
        val drawW = (source.width * scale).roundToInt().coerceAtLeast(1)
        val drawH = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (width - drawW) / 2
        val top = (height - drawH) / 2

        canvas.drawBitmap(
            source,
            null,
            Rect(left, top, left + drawW, top + drawH),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )

        return when (mode) {
            Mode.THRESHOLD -> threshold(canvasBmp)
            Mode.DITHER -> dither(canvasBmp)
        }
    }

    /**
     * Scales into an exact dot box and flattens to 1-bit. Used by the design
     * renderer, where the element already has a size and the caller decides
     * placement, so there is no label margin or centring to apply.
     */
    fun renderInto(source: Bitmap, widthDots: Int, heightDots: Int, mode: Mode): Bitmap {
        val out = Bitmap.createBitmap(
            widthDots.coerceAtLeast(1),
            heightDots.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            source,
            null,
            Rect(0, 0, out.width, out.height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return when (mode) {
            Mode.THRESHOLD -> threshold(out)
            Mode.DITHER -> dither(out)
        }
    }

    private fun luma(pixel: Int): Int =
        ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000

    private fun threshold(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                row[x] = if (luma(row[x]) < 128) Color.BLACK else Color.WHITE
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }

    /**
     * Floyd-Steinberg: push each pixel's rounding error onto neighbours that
     * have not been decided yet, so large flat areas average out to the right
     * tone instead of banding.
     */
    private fun dither(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Error accumulates well outside 0..255, so carry it as float.
        val grey = FloatArray(w * h) { luma(pixels[it]).toFloat() }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = grey[i]
                val new = if (old < 128f) 0f else 255f
                grey[i] = new
                val err = old - new

                if (x + 1 < w) grey[i + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) grey[i + w - 1] += err * 3f / 16f
                    grey[i + w] += err * 5f / 16f
                    if (x + 1 < w) grey[i + w + 1] += err * 1f / 16f
                }
            }
        }

        for (i in pixels.indices) {
            pixels[i] = if (grey[i] < 128f) Color.BLACK else Color.WHITE
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}
