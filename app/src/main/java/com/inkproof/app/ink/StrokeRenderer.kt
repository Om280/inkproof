package com.inkproof.app.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import com.inkproof.app.model.ToolType
import kotlin.math.hypot

/**
 * Renders vector strokes.
 *
 * Pen strokes are rendered as a filled, pressure-tapered ribbon so ink looks
 * like real pen writing. Highlighters are flat translucent strokes.
 * All geometry is computed in page space — sharp at any zoom.
 */
object StrokeRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val highlighterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        when (stroke.tool) {
            ToolType.HIGHLIGHTER -> {
                highlighterPaint.color = stroke.color
                highlighterPaint.strokeWidth = stroke.baseWidth
                canvas.drawPath(centerlinePath(stroke.points), highlighterPaint)
            }
            else -> {
                if (stroke.shapeType != null || stroke.points.size < 3) {
                    // Shapes and dots: uniform width centerline.
                    strokePaint.color = stroke.color
                    strokePaint.strokeWidth = stroke.baseWidth
                    if (stroke.points.size == 1) {
                        val p = stroke.points[0]
                        fillPaint.color = stroke.color
                        canvas.drawCircle(p.x, p.y, stroke.baseWidth / 2f, fillPaint)
                    } else {
                        canvas.drawPath(centerlinePath(stroke.points), strokePaint)
                    }
                } else {
                    fillPaint.color = stroke.color
                    canvas.drawPath(ribbonPath(stroke.points, stroke.baseWidth), fillPaint)
                }
            }
        }
    }

    /** Smooth centerline through the points using quadratic midpoints. */
    fun centerlinePath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 1) {
            path.lineTo(points[0].x + 0.1f, points[0].y)
            return path
        }
        for (i in 1 until points.size - 1) {
            val midX = (points[i].x + points[i + 1].x) / 2f
            val midY = (points[i].y + points[i + 1].y) / 2f
            path.quadTo(points[i].x, points[i].y, midX, midY)
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    /**
     * Builds a filled outline whose width follows pressure, producing
     * natural tapered ink. Pure geometry — unit-testable.
     */
    fun ribbonPath(points: List<StrokePoint>, baseWidth: Float): Path {
        val n = points.size
        val half = FloatArray(n)
        for (i in 0 until n) {
            val pr = points[i].pressure.coerceIn(0.05f, 1.5f)
            half[i] = (baseWidth * (0.45f + 0.75f * pr)) / 2f
        }
        // Light smoothing of widths to avoid wobble.
        for (pass in 0 until 2) {
            for (i in 1 until n - 1) {
                half[i] = (half[i - 1] + half[i] * 2 + half[i + 1]) / 4f
            }
        }

        val leftX = FloatArray(n); val leftY = FloatArray(n)
        val rightX = FloatArray(n); val rightY = FloatArray(n)
        for (i in 0 until n) {
            val prev = points[(i - 1).coerceAtLeast(0)]
            val next = points[(i + 1).coerceAtMost(n - 1)]
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            val len = hypot(dx, dy)
            if (len < 1e-4f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            // Normal
            val nx = -dy; val ny = dx
            leftX[i] = points[i].x + nx * half[i]
            leftY[i] = points[i].y + ny * half[i]
            rightX[i] = points[i].x - nx * half[i]
            rightY[i] = points[i].y - ny * half[i]
        }

        val path = Path()
        path.moveTo(leftX[0], leftY[0])
        for (i in 1 until n - 1) {
            path.quadTo(leftX[i], leftY[i], (leftX[i] + leftX[i + 1]) / 2f, (leftY[i] + leftY[i + 1]) / 2f)
        }
        path.lineTo(leftX[n - 1], leftY[n - 1])
        // Round end cap
        path.lineTo(rightX[n - 1], rightY[n - 1])
        for (i in n - 2 downTo 1) {
            path.quadTo(rightX[i], rightY[i], (rightX[i] + rightX[i - 1]) / 2f, (rightY[i] + rightY[i - 1]) / 2f)
        }
        path.lineTo(rightX[0], rightY[0])
        path.close()
        // Start/end caps as circles for smooth round ends.
        path.addCircle(points[0].x, points[0].y, half[0], Path.Direction.CW)
        path.addCircle(points[n - 1].x, points[n - 1].y, half[n - 1], Path.Direction.CW)
        return path
    }
}
