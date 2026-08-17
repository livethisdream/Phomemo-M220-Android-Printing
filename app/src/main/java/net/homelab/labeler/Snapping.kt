package net.homelab.labeler

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Alignment snapping for the editor.
 *
 * A 50 x 30 mm label is 400 x 240 dots. Displayed on a phone that is a few
 * hundred screen pixels, so a fingertip covers something like a millimetre of
 * label - far coarser than the placement people expect. Snapping is what makes
 * dragging at this scale produce deliberate results rather than approximate
 * ones.
 *
 * Candidates are the label's own edges and centre lines, plus the edges and
 * centres of every other element, plus a coarse grid as a fallback.
 */
object Snapping {

    /** A line the moving element aligned to, for the editor to draw. */
    data class Guide(val vertical: Boolean, val positionMm: Float)

    data class Result(val x: Float, val y: Float, val guides: List<Guide>)

    private const val THRESHOLD_MM = 1.2f
    private const val GRID_MM = 0.5f

    /**
     * Snaps a proposed top-left position. [others] should exclude the element
     * being moved, or it will helpfully snap to itself.
     */
    fun snap(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        others: List<LabelDesign.Element>,
        labelWidthMm: Int,
        labelHeightMm: Int,
        enabled: Boolean
    ): Result {
        if (!enabled) return Result(x, y, emptyList())

        val verticals = mutableListOf<Float>()
        val horizontals = mutableListOf<Float>()

        verticals += listOf(0f, labelWidthMm / 2f, labelWidthMm.toFloat())
        horizontals += listOf(0f, labelHeightMm / 2f, labelHeightMm.toFloat())

        for (other in others) {
            verticals += listOf(other.x, other.x + other.w / 2f, other.x + other.w)
            horizontals += listOf(other.y, other.y + other.h / 2f, other.y + other.h)
        }

        // The moving element offers three lines of its own: leading edge,
        // centre, trailing edge. Each can land on any candidate.
        val (snappedX, guideX) = axis(x, w, verticals)
        val (snappedY, guideY) = axis(y, h, horizontals)

        val guides = buildList {
            guideX?.let { add(Guide(vertical = true, positionMm = it)) }
            guideY?.let { add(Guide(vertical = false, positionMm = it)) }
        }
        return Result(snappedX, snappedY, guides)
    }

    /** Returns the snapped origin and the line it locked onto, if any. */
    private fun axis(origin: Float, size: Float, candidates: List<Float>): Pair<Float, Float?> {
        var best: Float? = null
        var bestDelta = THRESHOLD_MM
        var bestLine: Float? = null

        for (candidate in candidates) {
            // leading edge, centre, trailing edge of the moving element
            for ((offset, line) in listOf(
                0f to candidate,
                size / 2f to candidate,
                size to candidate
            )) {
                val proposed = line - offset
                val delta = abs(proposed - origin)
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = proposed
                    bestLine = line
                }
            }
        }

        // No alignment nearby, so fall back to a grid - still better than
        // leaving a value with six decimal places behind a fingertip.
        val result = best ?: ((origin / GRID_MM).roundToInt() * GRID_MM)
        return result to bestLine
    }

    /** Keeps an element from being dragged entirely off the label. */
    fun clamp(x: Float, y: Float, w: Float, h: Float, widthMm: Int, heightMm: Int): Pair<Float, Float> {
        val minVisible = 2f
        return x.coerceIn(minVisible - w, widthMm - minVisible) to
            y.coerceIn(minVisible - h, heightMm - minVisible)
    }
}
