package com.inkproof.app.ink

import com.inkproof.app.model.Stroke
import kotlin.math.hypot

/**
 * Geometric hit testing for the eraser (and any pointer-vs-stroke test).
 *
 * The old implementation only tested distance to stroke POINTS, which made
 * snapped shapes (a straight line is just 2 points) impossible to erase in
 * the middle. This tests distance to every SEGMENT of the stroke.
 */
object StrokeHitTester {

    /** True when the circle (px, py, radius) touches the rendered stroke. */
    fun hits(stroke: Stroke, px: Float, py: Float, radius: Float): Boolean {
        val reach = radius + stroke.baseWidth / 2f
        val pts = stroke.points
        if (pts.isEmpty()) return false
        if (pts.size == 1) {
            return hypot(pts[0].x - px, pts[0].y - py) <= reach
        }
        for (i in 0 until pts.size - 1) {
            if (distanceToSegment(px, py, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y) <= reach) {
                return true
            }
        }
        return false
    }

    fun distanceToSegment(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
