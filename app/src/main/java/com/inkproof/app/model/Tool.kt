package com.inkproof.app.model

/** Drawing / interaction tools available in the editor. */
enum class ToolType {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPE,
    TEXT,
    PAN
}

/** Style of the currently selected ink tool. */
data class PenStyle(
    val tool: ToolType = ToolType.PEN,
    val color: Int = 0xFF1A2238.toInt(),
    val baseWidth: Float = 3.0f,
    val pressureEnabled: Boolean = true
)

object PenPalette {
    val INK_BLACK = 0xFF212121.toInt()
    val INK_NAVY = 0xFF1A2238.toInt()
    val INK_BLUE = 0xFF2458C5.toInt()
    val INK_RED = 0xFFC62828.toInt()
    val INK_GREEN = 0xFF2E7D32.toInt()
    val INK_PURPLE = 0xFF6A3AB2.toInt()
    val INK_ORANGE = 0xFFE07B00.toInt()
    val INK_TEAL = 0xFF00796B.toInt()

    val HL_YELLOW = 0x66FFEB3B
    val HL_GREEN = 0x6669F0AE
    val HL_BLUE = 0x6640C4FF
    val HL_PINK = 0x66FF80AB
    val HL_ORANGE = 0x66FFB74D

    val penColors = listOf(
        INK_BLACK, INK_NAVY, INK_BLUE, INK_RED, INK_GREEN, INK_PURPLE, INK_ORANGE, INK_TEAL
    )
    val highlighterColors = listOf(HL_YELLOW, HL_GREEN, HL_BLUE, HL_PINK, HL_ORANGE)
    val penWidths = listOf(1.5f, 2.5f, 3.5f, 5f, 8f)
    val highlighterWidths = listOf(12f, 18f, 26f)
}
