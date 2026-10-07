package com.inkproof.app.ink

import android.graphics.Canvas
import android.graphics.Paint
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.PaperColors

/**
 * Draws page background templates. Templates and paper colors are visual
 * only — switching them never touches stroke data.
 *
 * Paper COLOR is independent of TEMPLATE: any combination works
 * (black + grid, slate + ruled, warm white + blank, …). Template line
 * colors adapt automatically so rules/grids stay subtle-but-visible on
 * both light and dark paper.
 */
object TemplateRenderer {

    private val paperPaint = Paint().apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    const val RULE_SPACING = 56f
    const val GRID_SPACING = 48f

    /** True when ink/lines need to be light to stay visible on this paper. */
    fun isDarkPaper(paperColor: Int): Boolean = PaperColors.isDark(paperColor)

    /** Subtle line color (fine grids, rules) adapted to the paper. */
    fun subtleLine(paperColor: Int): Int =
        if (isDarkPaper(paperColor)) 0x2EFFFFFF else 0xFFE3E7EF.toInt()

    /** Slightly stronger line color (major grids, dots). */
    fun strongLine(paperColor: Int): Int =
        if (isDarkPaper(paperColor)) 0x4DFFFFFF else 0xFFCBD2E0.toInt()

    /** Accent line color (worksheet margin). */
    fun accentLine(paperColor: Int): Int =
        if (isDarkPaper(paperColor)) 0x59FF8A80 else 0xFFF2C4C4.toInt()

    fun draw(
        canvas: Canvas,
        template: PageTemplate,
        width: Float,
        height: Float,
        paperColor: Int = PaperColors.WHITE
    ) {
        // Paper with a soft shadow edge.
        canvas.drawRect(3f, 5f, width + 3f, height + 5f, shadowPaint)
        paperPaint.color = paperColor
        canvas.drawRect(0f, 0f, width, height, paperPaint)

        val subtle = subtleLine(paperColor)
        val strong = strongLine(paperColor)

        when (template) {
            PageTemplate.BLANK -> Unit

            PageTemplate.RULED -> {
                linePaint.color = subtle
                var y = RULE_SPACING * 2
                while (y < height - RULE_SPACING / 2) {
                    canvas.drawLine(32f, y, width - 32f, y, linePaint)
                    y += RULE_SPACING
                }
            }

            PageTemplate.GRID -> {
                linePaint.color = subtle
                var x = GRID_SPACING
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height, linePaint)
                    x += GRID_SPACING
                }
                var y = GRID_SPACING
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, linePaint)
                    y += GRID_SPACING
                }
            }

            PageTemplate.DOT_GRID -> {
                dotPaint.color = strong
                var x = GRID_SPACING
                while (x < width) {
                    var y = GRID_SPACING
                    while (y < height) {
                        canvas.drawCircle(x, y, 1.6f, dotPaint)
                        y += GRID_SPACING
                    }
                    x += GRID_SPACING
                }
            }

            PageTemplate.ENGINEERING -> {
                // Fine grid
                linePaint.color =
                    if (isDarkPaper(paperColor)) 0x17FFFFFF else 0xFFF0F3F8.toInt()
                val fine = GRID_SPACING / 4f
                var x = fine
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height, linePaint)
                    x += fine
                }
                var y = fine
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, linePaint)
                    y += fine
                }
                // Major grid
                linePaint.color = strong
                x = GRID_SPACING
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height, linePaint)
                    x += GRID_SPACING
                }
                y = GRID_SPACING
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, linePaint)
                    y += GRID_SPACING
                }
            }

            PageTemplate.MATH_WORKSHEET -> {
                // Ruled body with a left margin — a classic worksheet layout.
                linePaint.color = subtle
                var y = RULE_SPACING * 2
                while (y < height - RULE_SPACING / 2) {
                    canvas.drawLine(32f, y, width - 32f, y, linePaint)
                    y += RULE_SPACING
                }
                linePaint.color = accentLine(paperColor)
                canvas.drawLine(120f, 0f, 120f, height, linePaint)
            }
        }
    }
}
