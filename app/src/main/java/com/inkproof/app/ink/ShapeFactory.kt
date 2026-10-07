package com.inkproof.app.ink

import com.inkproof.app.model.ShapeType
import com.inkproof.app.model.StrokePoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds clean vector shapes for the explicit SHAPE tool: the user picks a
 * shape kind, drags from (x0,y0) to (x1,y1), and gets a real editable vector
 * stroke (movable, resizable, recolorable, erasable, lasso-selectable).
 */
object ShapeFactory {

    fun create(kind: ShapeType, x0: Float, y0: Float, x1: Float, y1: Float): List<StrokePoint> =
        when (kind) {
            ShapeType.LINE -> listOf(pt(x0, y0, 0), pt(x1, y1, 1))
            ShapeType.ARROW -> arrow(x0, y0, x1, y1)
            ShapeType.RECTANGLE -> rect(x0, y0, x1, y1)
            ShapeType.SQUARE -> square(x0, y0, x1, y1)
            ShapeType.ELLIPSE -> ellipse(x0, y0, x1, y1, forceCircle = false)
            ShapeType.CIRCLE -> ellipse(x0, y0, x1, y1, forceCircle = true)
            ShapeType.TRIANGLE -> triangle(x0, y0, x1, y1)
            ShapeType.POLYGON -> rect(x0, y0, x1, y1)
        }

    private fun pt(x: Float, y: Float, t: Long) = StrokePoint(x, y, 0.7f, t)

    private fun arrow(x0: Float, y0: Float, x1: Float, y1: Float): List<StrokePoint> {
        val chord = hypot(x1 - x0, y1 - y0)
        val head = (chord * 0.22f).coerceIn(14f, 64f)
        val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val a1 = angle + Math.toRadians(150.0)
        val a2 = angle - Math.toRadians(150.0)
        return listOf(
            pt(x0, y0, 0),
            pt(x1, y1, 1),
            pt(x1 + head * cos(a1).toFloat(), y1 + head * sin(a1).toFloat(), 2),
            pt(x1, y1, 3),
            pt(x1 + head * cos(a2).toFloat(), y1 + head * sin(a2).toFloat(), 4)
        )
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float): List<StrokePoint> {
        val l = min(x0, x1); val t = min(y0, y1)
        val r = max(x0, x1); val b = max(y0, y1)
        return listOf(pt(l, t, 0), pt(r, t, 1), pt(r, b, 2), pt(l, b, 3), pt(l, t, 4))
    }

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float): List<StrokePoint> {
        val side = max(abs(x1 - x0), abs(y1 - y0))
        val sx = if (x1 >= x0) 1f else -1f
        val sy = if (y1 >= y0) 1f else -1f
        return rect(x0, y0, x0 + side * sx, y0 + side * sy)
    }

    private fun ellipse(
        x0: Float, y0: Float, x1: Float, y1: Float, forceCircle: Boolean
    ): List<StrokePoint> {
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        var rx = abs(x1 - x0) / 2f
        var ry = abs(y1 - y0) / 2f
        if (forceCircle) {
            val r = (rx + ry) / 2f
            rx = r; ry = r
        }
        val n = 48
        return (0..n).map { i ->
            val a = i * 2.0 * Math.PI / n
            pt(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat(), i.toLong())
        }
    }

    private fun triangle(x0: Float, y0: Float, x1: Float, y1: Float): List<StrokePoint> {
        val l = min(x0, x1); val t = min(y0, y1)
        val r = max(x0, x1); val b = max(y0, y1)
        val apexX = (l + r) / 2f
        return listOf(pt(apexX, t, 0), pt(r, b, 1), pt(l, b, 2), pt(apexX, t, 3))
    }
}
