package com.inkproof.app.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.inkproof.app.model.PageTemplate

/** Draws page background templates. Templates are visual only — switching
 *  them never touches stroke data. */
object TemplateRenderer {

    private val paperPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
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

    fun draw(canvas: Canvas, template: PageTemplate, width: Float, height: Float) {
        // Paper with a soft shadow edge.
        canvas.drawRect(3f, 5f, width + 3f, height + 5f, shadowPaint)
        canvas.drawRect(0f, 0f, width, height, paperPaint)

        when (template) {
            PageTemplate.BLANK -> Unit

            PageTemplate.RULED -> {
                linePaint.color = 0xFFE3E7EF.toInt()
                var y = RULE_SPACING * 2
                while (y < height - RULE_SPACING / 2) {
                    canvas.drawLine(32f, y, width - 32f, y, linePaint)
                    y += RULE_SPACING
                }
            }

            PageTemplate.GRID -> {
                linePaint.color = 0xFFE6EAF2.toInt()
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
                dotPaint.color = 0xFFCBD2E0.toInt()
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
                linePaint.color = 0xFFF0F3F8.toInt()
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
                linePaint.color = 0xFFDCE2EE.toInt()
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
                linePaint.color = 0xFFE3E7EF.toInt()
                var y = RULE_SPACING * 2
                while (y < height - RULE_SPACING / 2) {
                    canvas.drawLine(32f, y, width - 32f, y, linePaint)
                    y += RULE_SPACING
                }
                linePaint.color = 0xFFF2C4C4.toInt()
                canvas.drawLine(120f, 0f, 120f, height, linePaint)
            }
        }
    }
}
