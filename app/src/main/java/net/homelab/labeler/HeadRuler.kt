package net.homelab.labeler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * A calibration print that measures the roll instead of guessing at it.
 *
 * Every geometry problem this app has had came from the same blind spot: the
 * preview draws the label, so it can show a design sitting neatly inside its
 * margins while the real thing runs off the paper. The preview cannot show that
 * error, because the error is not in the design - it is in the relationship
 * between the entered width and the stock actually loaded, and nothing on
 * screen knows the second one.
 *
 * This prints at the full head width with a scale running in from the right,
 * which is the edge a right-registered roll is aligned to. Whatever is missing
 * fell off the paper, so the largest number still readable is the stock width,
 * measured rather than assumed. It also shows at a glance whether the roll is
 * registered to the right at all: if the bar is cut off at the right-hand end
 * too, it is not.
 */
object HeadRuler {

    private const val DPMM = LabelRenderer.DOTS_PER_MM

    /** Tall enough for a legible scale, short enough not to waste a label. */
    private const val HEIGHT_MM = 28

    fun render(headWidthBytes: Int): Bitmap {
        val width = headWidthBytes.coerceIn(16, 104) * 8
        val height = HEIGHT_MM * DPMM

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 3.4f * DPMM
        }

        // A solid bar across the whole head. Any part of it missing from the
        // printed label is head that overhangs the paper.
        val barHeight = 2.5f * DPMM
        canvas.drawRect(0f, 0f, width.toFloat(), barHeight, ink)

        val baseline = barHeight + 1f * DPMM
        val headMm = width / DPMM

        for (mm in 0..headMm) {
            // Ticks in from the right, because that is the registered edge.
            val x = width - mm * DPMM.toFloat()
            val long = mm % 10 == 0
            val medium = mm % 5 == 0

            val length = when {
                long -> 5f * DPMM
                medium -> 3f * DPMM
                else -> 1.5f * DPMM
            }
            val thickness = if (long) 3f else 1.5f
            canvas.drawRect(
                (x - thickness / 2).coerceAtLeast(0f),
                baseline,
                (x + thickness / 2).coerceAtMost(width.toFloat()),
                baseline + length,
                ink
            )

            if (long && mm > 0) {
                val label = mm.toString()
                val advance = text.measureText(label)
                // Numbers sit to the right of their tick so that the digits
                // stay on the paper for as long as the tick itself does.
                val left = (x + 1.5f).coerceAtMost(width - advance)
                canvas.drawText(label, left, baseline + length + 3.6f * DPMM, text)
            }
        }

        // A wedge pointing at the head's last dot, with its number inside the
        // print rather than beyond it.
        //
        // This is the half of the measurement that the scale alone cannot give.
        // The numbers say how much head landed on the label; the wedge says
        // whether the head reached the label's right edge at all. Blank paper to
        // the right of it means the roll is sitting outboard of the head, and
        // no alignment setting can print on paper the head cannot cover.
        // Clear of the numerals, which run to roughly 9 mm below the baseline.
        val wedgeTop = baseline + 11f * DPMM
        val wedge = android.graphics.Path().apply {
            moveTo(width.toFloat(), wedgeTop)
            lineTo(width.toFloat(), wedgeTop + 5f * DPMM)
            lineTo(width - 4f * DPMM, wedgeTop + 2.5f * DPMM)
            close()
        }
        canvas.drawPath(wedge, ink)
        val zero = "0"
        canvas.drawText(
            zero,
            width - 4f * DPMM - text.measureText(zero) - 1f * DPMM,
            wedgeTop + 4f * DPMM,
            text
        )

        val caption = "mm in from the head's right edge"
        text.textSize = 2.6f * DPMM
        canvas.drawText(
            caption,
            (width - text.measureText(caption)).coerceAtLeast(0f) / 2f,
            height - 1.5f * DPMM,
            text
        )

        return bmp
    }
}
