package net.homelab.labeler

/**
 * Known label stock.
 *
 * The standard list is transcribed from the vendor's own web tooling, so these
 * are sizes Phomemo actually sells rather than guesses. Third-party rolls are
 * common though, so anything can be entered by hand and kept.
 */
object LabelSizes {

    data class Size(val widthMm: Int, val heightMm: Int) {
        /** Round dies are square stock; the die just cuts a circle out of it. */
        val isSquare: Boolean get() = widthMm == heightMm

        val label: String
            get() = if (isSquare) "$widthMm mm round" else "$widthMm × $heightMm"

        /** Round-trips through preferences as a single string. */
        fun encode(): String = "${widthMm}x$heightMm"

        companion object {
            fun decode(value: String): Size? {
                val parts = value.split("x")
                if (parts.size != 2) return null
                val w = parts[0].toIntOrNull() ?: return null
                val h = parts[1].toIntOrNull() ?: return null
                return Size(w, h)
            }
        }
    }

    val STANDARD: List<Size> = listOf(
        Size(50, 30),
        Size(40, 30),
        Size(30, 20),
        Size(50, 25),
        Size(30, 40),
        Size(40, 60),
        Size(50, 80),
        Size(60, 40),
        Size(25, 50),
        Size(20, 30),
        Size(15, 30),
        Size(12, 40)
    )

    val ROUND: List<Size> = listOf(
        Size(20, 20),
        Size(30, 30),
        Size(40, 40),
        Size(50, 50)
    )
}
