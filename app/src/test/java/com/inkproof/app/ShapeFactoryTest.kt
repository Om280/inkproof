package com.inkproof.app

import com.inkproof.app.ink.ShapeFactory
import com.inkproof.app.model.ClipboardOps
import com.inkproof.app.model.ShapeType
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ShapeFactoryTest {

    @Test
    fun `line is two points`() {
        val pts = ShapeFactory.create(ShapeType.LINE, 10f, 10f, 200f, 50f)
        assertEquals(2, pts.size)
        assertEquals(10f, pts[0].x, 0.01f)
        assertEquals(200f, pts[1].x, 0.01f)
    }

    @Test
    fun `arrow is five points with the tip at the drag end`() {
        val pts = ShapeFactory.create(ShapeType.ARROW, 0f, 0f, 300f, 0f)
        assertEquals(5, pts.size)
        assertEquals(300f, pts[1].x, 0.01f)
        assertEquals(300f, pts[3].x, 0.01f) // returns to the tip between barbs
        assertTrue(pts[2].x < 300f && pts[4].x < 300f) // barbs point backward
    }

    @Test
    fun `rectangle is a closed loop matching the drag box`() {
        val pts = ShapeFactory.create(ShapeType.RECTANGLE, 220f, 180f, 20f, 40f)
        assertEquals(5, pts.size)
        assertEquals(pts.first().x, pts.last().x, 0.01f)
        assertEquals(pts.first().y, pts.last().y, 0.01f)
        assertEquals(20f, pts.minOf { it.x }, 0.01f)
        assertEquals(220f, pts.maxOf { it.x }, 0.01f)
    }

    @Test
    fun `square has equal sides regardless of drag aspect`() {
        val pts = ShapeFactory.create(ShapeType.SQUARE, 0f, 0f, 200f, 80f)
        val w = pts.maxOf { it.x } - pts.minOf { it.x }
        val h = pts.maxOf { it.y } - pts.minOf { it.y }
        assertEquals(w, h, 0.01f)
        assertEquals(200f, w, 0.01f)
    }

    @Test
    fun `circle points are equidistant from the center`() {
        val pts = ShapeFactory.create(ShapeType.CIRCLE, 0f, 0f, 200f, 100f)
        val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
        val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
        val radii = pts.map { hypot(it.x - cx, it.y - cy) }
        val spread = radii.max() - radii.min()
        assertTrue("circle radius spread $spread", spread < 1.5f)
    }

    @Test
    fun `triangle closes and spans the drag box`() {
        val pts = ShapeFactory.create(ShapeType.TRIANGLE, 0f, 0f, 300f, 200f)
        assertEquals(4, pts.size)
        assertEquals(pts.first().x, pts.last().x, 0.01f)
        assertEquals(200f, pts.maxOf { it.y }, 0.01f)
    }

    @Test
    fun `scaled stroke scales around the pivot and keeps identity`() {
        val s = Stroke(
            pageId = "p", color = 1, baseWidth = 4f,
            points = listOf(StrokePoint(100f, 100f, 1f, 0), StrokePoint(200f, 100f, 1f, 1))
        )
        val scaled = s.scaled(2f, 2f, 100f, 100f)
        assertEquals(s.id, scaled.id)
        assertEquals(100f, scaled.points[0].x, 0.01f)
        assertEquals(300f, scaled.points[1].x, 0.01f)
        assertEquals(8f, scaled.baseWidth, 0.01f)
    }

    @Test
    fun `paste clones are fully independent of the originals`() {
        val original = listOf(
            Stroke(
                pageId = "pageA", color = 5, baseWidth = 3f,
                points = listOf(StrokePoint(10f, 10f, 1f, 0), StrokePoint(60f, 20f, 1f, 1))
            )
        )
        val pasted = ClipboardOps.cloneForPaste(original, "pageA")

        assertEquals(1, pasted.size)
        // New identity, same appearance, offset position.
        assertNotEquals(original[0].id, pasted[0].id)
        assertEquals(original[0].color, pasted[0].color)
        assertEquals(original[0].baseWidth, pasted[0].baseWidth, 0.01f)
        assertTrue(abs(pasted[0].points[0].x - original[0].points[0].x - 48f) < 0.01f)
        // Moving the copy must not move the original (no shared references).
        val moved = pasted[0].translated(500f, 0f)
        assertEquals(10f, original[0].points[0].x, 0.01f)
        assertEquals(558f, moved.points[0].x, 0.01f)
    }
}
