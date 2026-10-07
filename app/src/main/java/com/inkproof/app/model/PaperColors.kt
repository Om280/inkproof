package com.inkproof.app.model

/**
 * Per-page paper colors. Paper color is a PAGE property, independent of
 * both the app theme (light/dark UI) and the page template (ruled/grid/…):
 * a user can run a light UI with black pages, a dark UI with white pages,
 * or any other combination. Changing paper color never touches ink.
 */
object PaperColors {
    val WHITE = 0xFFFCFBF8.toInt()
    val WARM_WHITE = 0xFFF8F3E9.toInt()
    val GREY = 0xFFE9EBEF.toInt()
    val SLATE = 0xFF3B4252.toInt()
    val DARK_GREY = 0xFF2A2D34.toInt()
    val NEAR_BLACK = 0xFF1A1C20.toInt()
    val BLACK = 0xFF0E0F12.toInt()

    /** Label → color, in picker order. */
    val all: List<Pair<String, Int>> = listOf(
        "White" to WHITE,
        "Warm white" to WARM_WHITE,
        "Grey" to GREY,
        "Slate" to SLATE,
        "Dark grey" to DARK_GREY,
        "Near black" to NEAR_BLACK,
        "Black" to BLACK
    )

    /** Relative luminance below ~0.5 counts as dark paper. */
    fun isDark(color: Int): Boolean {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) < 0.5f
    }
}
