package net.homelab.labeler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Prints the label's own outline, so registration can be seen rather than
 * inferred.
 *
 * A clipped QR says something is wrong but not what: the design could be too
 * big, the width could be wrong, or the paper could have started in the wrong
 * place. A rectangle drawn at the exact label bounds separates those. Every
 * edge that lands on the label is a dimension that is right, and every edge
 * that is missing names its own problem:
 *
 *   left or right edge missing   the width is wrong, or the roll sits off-center
 *   top or bottom edge missing   the paper is not starting where the print does
 *   one long edge, tapering      the label is skewed in the paper path
 *
 * The top and bottom edges are labelled, because a print that has drifted far
 * enough is indistinguishable from one that has not drifted at all - you cannot
 * tell a missing top from a missing bottom without being told which is which.
 */
object EdgeTest {

    private const val DPMM = LabelRenderer.DOTS_PER_MM

    fun render(widthMm: Int, heightMm: Int): Bitmap {
        val width = (((widthMm * DPMM) / 8) * 8).coerceAtLeast(8)
        val height = (heightMm * DPMM).coerceAtLeast(8)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 3f * DPMM
            textAlign = Paint.Align.CENTER
        }

        // The border sits on the outermost dots, not inside a margin. A margin
        // would survive a few millimetres of drift and report success for a
        // print that is already sliding off the label.
        val stroke = 3f
        canvas.drawRect(0f, 0f, width.toFloat(), stroke, ink)
        canvas.drawRect(0f, height - stroke, width.toFloat(), height.toFloat(), ink)
        canvas.drawRect(0f, 0f, stroke, height.toFloat(), ink)
        canvas.drawRect(width - stroke, 0f, width.toFloat(), height.toFloat(), ink)

        // Corner arms, thicker than the border so a corner clipped by a
        // millimetre still reads as a corner rather than as a broken line.
        val arm = minOf(6f * DPMM, width / 3f, height / 3f)
        val thick = 6f
        for (left in listOf(true, false)) {
            for (top in listOf(true, false)) {
                val x0 = if (left) 0f else width - arm
                val y0 = if (top) 0f else height - thick
                canvas.drawRect(x0, y0, x0 + arm, y0 + thick, ink)
                val x1 = if (left) 0f else width - thick
                val y1 = if (top) 0f else height - arm
                canvas.drawRect(x1, y1, x1 + thick, y1 + arm, ink)
            }
        }

        val centreX = width / 2f
        canvas.drawText("TOP", centreX, stroke + 4f * DPMM, text)
        canvas.drawText("BOTTOM", centreX, height - stroke - 1.5f * DPMM, text)

        text.textSize = 4f * DPMM
        canvas.drawText("$widthMm × $heightMm", centreX, height / 2f + 1.4f * DPMM, text)

        return bmp
    }
}
