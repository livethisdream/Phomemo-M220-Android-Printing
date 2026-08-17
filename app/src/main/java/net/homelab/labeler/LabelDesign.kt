package net.homelab.labeler

import android.graphics.Bitmap

/**
 * A label as a list of positioned elements.
 *
 * Coordinates are millimeters from the top-left of the label, not dots and not
 * screen pixels. Millimeters survive a change of label size, a change of print
 * head, and the difference between the editor's scale and the printer's 8
 * dots/mm - all three of which would otherwise need their own conversion at
 * every call site.
 */
object LabelDesign {

    sealed class Element {
        abstract val id: Long
        abstract val x: Float
        abstract val y: Float
        abstract val w: Float
        abstract val h: Float

        abstract fun moved(x: Float, y: Float): Element
        abstract fun resized(w: Float, h: Float): Element

        data class Text(
            override val id: Long,
            override val x: Float,
            override val y: Float,
            override val w: Float,
            override val h: Float,
            val text: String,
            val sizeMm: Float = 4f,
            val bold: Boolean = false,
            val monospace: Boolean = false,
            val align: Align = Align.LEFT,
            /** White on black. The only "color" a 1-bit head can offer. */
            val invert: Boolean = false
        ) : Element() {
            override fun moved(x: Float, y: Float) = copy(x = x, y = y)
            override fun resized(w: Float, h: Float) = copy(w = w, h = h)
        }

        data class Qr(
            override val id: Long,
            override val x: Float,
            override val y: Float,
            override val w: Float,
            override val h: Float,
            val content: String
        ) : Element() {
            override fun moved(x: Float, y: Float) = copy(x = x, y = y)

            // QR codes are square by definition; a rectangle would just be a
            // square with dead space, so keep the smaller edge.
            override fun resized(w: Float, h: Float) = minOf(w, h).let { copy(w = it, h = it) }
        }

        data class Picture(
            override val id: Long,
            override val x: Float,
            override val y: Float,
            override val w: Float,
            override val h: Float,
            val bitmap: Bitmap,
            val mode: ImageLabel.Mode = ImageLabel.Mode.THRESHOLD
        ) : Element() {
            override fun moved(x: Float, y: Float) = copy(x = x, y = y)
            override fun resized(w: Float, h: Float) = copy(w = w, h = h)
        }
    }

    enum class Align { LEFT, CENTER, RIGHT }

    data class Doc(val elements: List<Element> = emptyList()) {
        fun replace(element: Element): Doc =
            copy(elements = elements.map { if (it.id == element.id) element else it })

        fun remove(id: Long): Doc = copy(elements = elements.filterNot { it.id == id })

        fun add(element: Element): Doc = copy(elements = elements + element)

        fun find(id: Long?): Element? = elements.firstOrNull { it.id == id }
    }

    private var nextId = 1L
    fun newId(): Long = nextId++

    /**
     * The layout a share produces: QR beside the text on wide stock, above it
     * on tall. Matches what the app generated before the editor existed, so a
     * shared page still prints the same label without touching anything.
     */
    fun fromLabel(label: LabelRenderer.Label, widthMm: Int, heightMm: Int): Doc {
        val margin = 1.5f
        val gutter = 1f
        val innerW = widthMm - margin * 2
        val innerH = heightMm - margin * 2

        val beside = innerW >= innerH
        val hasText = !label.title.isNullOrBlank() || !label.assetId.isNullOrBlank()

        val minText = if (beside) 12f else 7f
        val maxQr = minOf(innerW, innerH)
        val budget = when {
            !hasText -> maxQr
            beside -> innerW - minText - gutter
            else -> innerH - minText - gutter
        }
        val qr = budget.coerceIn(minOf(12f, maxQr), maxQr)

        val elements = mutableListOf<Element>()
        val textX: Float
        val textY: Float
        val textW: Float
        val textH: Float

        if (beside) {
            elements += Element.Qr(newId(), margin, margin + (innerH - qr) / 2, qr, qr, label.url)
            textX = margin + qr + gutter
            textY = margin
            textW = widthMm - textX - margin
            textH = innerH
        } else {
            elements += Element.Qr(newId(), margin + (innerW - qr) / 2, margin, qr, qr, label.url)
            textX = margin
            textY = margin + qr + gutter
            textW = innerW
            textH = heightMm - textY - margin
        }

        val title = label.title?.takeIf { it.isNotBlank() }
        val assetId = label.assetId?.takeIf { it.isNotBlank() }

        if (title != null) {
            val titleH = minOf(textH * 0.6f, 9f)
            elements += Element.Text(
                newId(), textX, textY, textW, titleH,
                text = title,
                sizeMm = minOf(4.5f, titleH / 2f),
                bold = true
            )
        }
        if (assetId != null) {
            val idH = 4.5f
            elements += Element.Text(
                newId(), textX, textY + textH - idH, textW, idH,
                text = assetId,
                sizeMm = 3.5f,
                monospace = true
            )
        }

        return Doc(elements)
    }

    /** A single image filling the label, for a shared picture. */
    fun fromImage(bitmap: Bitmap, widthMm: Int, heightMm: Int, mode: ImageLabel.Mode): Doc {
        val margin = 1f
        val boxW = widthMm - margin * 2
        val boxH = heightMm - margin * 2
        val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        return Doc(
            listOf(
                Element.Picture(
                    newId(),
                    (widthMm - w) / 2,
                    (heightMm - h) / 2,
                    w, h, bitmap, mode
                )
            )
        )
    }
}
