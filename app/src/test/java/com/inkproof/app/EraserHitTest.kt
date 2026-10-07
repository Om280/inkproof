package com.inkproof.app

import com.inkproof.app.ink.StrokeHitTester
import com.inkproof.app.model.ShapeType
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eraser must operate on actual rendered geometry. The historical bug:
 * hit-testing only stroke POINTS meant a snapped straight line (2 points)
 * could not be erased in the middle.
 */
class EraserHitTest {

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float = 3f) = Stroke(
        pageId = "p1", color = 1, baseWidth = width,
        points = listOf(StrokePoint(x0, y0, 1f, 0), StrokePoint(x1, y1, 1f, 1)),
        shapeType = ShapeType.LINE
    )

    @Test
    fun `erasing the MIDDLE of a two-point line hits it`() {
        val s = line(0f, 0f, 400f, 0f)
        assertTrue(StrokeHitTester.hits(s, 200f, 5f, radius = 10f))
    }

    @Test
    fun `far from the line is a miss`() {
        val s = line(0f, 0f, 400f, 0f)
        assertFalse(StrokeHitTester.hits(s, 200f, 80f, radius = 10f))
        assertFalse(StrokeHitTester.hits(s, -60f, 0f, radius = 10f))
    }

    @Test
    fun `wide strokes are reachable at their rendered edge`() {
        val s = line(0f, 0f, 400f, 0f, width = 20f)
        // 12 away from the centerline: within radius 5 + half width 10? No (15 < 12+? )
        assertTrue(StrokeHitTester.hits(s, 200f, 14f, radius = 5f)) // 14 <= 5 + 10
        assertFalse(StrokeHitTester.hits(s, 200f, 16f, radius = 5f))
    }

    @Test
    fun `single point stroke is erasable`() {
        val dot = Stroke(
            pageId = "p1", color = 1, baseWidth = 4f,
            points = listOf(StrokePoint(50f, 50f, 1f, 0))
        )
        assertTrue(StrokeHitTester.hits(dot, 55f, 50f, radius = 6f))
        assertFalse(StrokeHitTester.hits(dot, 80f, 50f, radius = 6f))
    }

    @Test
    fun `sparse rectangle corners are erasable along the edges`() {
        val rect = Stroke(
            pageId = "p1", color = 1, baseWidth = 3f,
            points = listOf(
                StrokePoint(0f, 0f, 1f, 0),
                StrokePoint(300f, 0f, 1f, 1),
                StrokePoint(300f, 200f, 1f, 2),
                StrokePoint(0f, 200f, 1f, 3),
                StrokePoint(0f, 0f, 1f, 4)
            ),
            shapeType = ShapeType.RECTANGLE
        )
        // Middle of the top edge — far from every corner point.
        assertTrue(StrokeHitTester.hits(rect, 150f, 2f, radius = 8f))
        // Center of the rectangle — not on any edge.
        assertFalse(StrokeHitTester.hits(rect, 150f, 100f, radius = 8f))
    }
}
