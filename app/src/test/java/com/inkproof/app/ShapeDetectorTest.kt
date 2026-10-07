package com.inkproof.app

import com.inkproof.app.ink.ShapeDetector
import com.inkproof.app.model.ShapeType
import com.inkproof.app.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShapeDetectorTest {

    private fun pts(vararg xy: Pair<Float, Float>): List<StrokePoint> =
        xy.mapIndexed { i, (x, y) -> StrokePoint(x, y, 0.7f, i.toLong()) }

    private fun interpolate(corners: List<Pair<Float, Float>>, perEdge: Int = 14): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        var t = 0L
        for (i in 0 until corners.size - 1) {
            val (x1, y1) = corners[i]
            val (x2, y2) = corners[i + 1]
            for (s in 0 until perEdge) {
                val f = s / perEdge.toFloat()
                out.add(StrokePoint(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, 0.7f, t++))
            }
        }
        out.add(StrokePoint(corners.last().first, corners.last().second, 0.7f, t))
        return out
    }

    @Test
    fun `straight line is detected`() {
        val points = interpolate(listOf(0f to 0f, 300f to 2f))
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.LINE, shape!!.type)
        assertEquals(2, shape.points.size)
    }

    @Test
    fun `nearly horizontal line snaps to horizontal`() {
        val points = interpolate(listOf(10f to 100f, 400f to 108f))
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.LINE, shape!!.type)
        // Snapped: end y equals start y.
        assertEquals(shape.points[0].y, shape.points[1].y, 0.5f)
    }

    @Test
    fun `circle is detected`() {
        val points = (0..80).map { i ->
            val a = 2 * Math.PI * i / 80
            StrokePoint(
                200f + (100 * cos(a)).toFloat() + (i % 3) * 0.8f,
                200f + (100 * sin(a)).toFloat(),
                0.7f,
                i.toLong()
            )
        }
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.CIRCLE, shape!!.type)
    }

    @Test
    fun `ellipse is detected`() {
        val points = (0..80).map { i ->
            val a = 2 * Math.PI * i / 80
            StrokePoint(
                200f + (160 * cos(a)).toFloat(),
                200f + (70 * sin(a)).toFloat(),
                0.7f,
                i.toLong()
            )
        }
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.ELLIPSE, shape!!.type)
    }

    @Test
    fun `rectangle is detected`() {
        val points = interpolate(
            listOf(0f to 0f, 300f to 2f, 302f to 180f, 2f to 182f, 0f to 4f)
        )
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.RECTANGLE, shape!!.type)
    }

    @Test
    fun `square is detected for equal sides`() {
        val points = interpolate(
            listOf(0f to 0f, 200f to 0f, 200f to 204f, 0f to 200f, 0f to 4f)
        )
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.SQUARE, shape!!.type)
    }

    @Test
    fun `triangle is detected`() {
        val points = interpolate(
            listOf(150f to 0f, 300f to 260f, 0f to 262f, 148f to 4f)
        )
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.TRIANGLE, shape!!.type)
    }

    @Test
    fun `arrow is detected from shaft plus doubled-back head`() {
        // Shaft left->right (densely sampled, like real ink), then the head:
        // back-up to a barb, return to the tip, back-down to the other barb.
        val shaft = interpolate(listOf(0f to 0f, 260f to 0f), perEdge = 36)
        val head = interpolate(
            listOf(260f to 0f, 228f to 22f, 258f to 2f, 228f to -22f),
            perEdge = 6
        )
        val points = (shaft + head.drop(1)).mapIndexed { i, p -> p.copy(t = i.toLong()) }
        val shape = ShapeDetector.detect(points)
        assertNotNull(shape)
        assertEquals(ShapeType.ARROW, shape!!.type)
        assertEquals(5, shape.points.size)
        // Tip is at the end of the shaft.
        assertEquals(260f, shape.points[1].x, 8f)
        assertEquals(0f, shape.points[1].y, 8f)
    }

    @Test
    fun `tiny strokes are never snapped`() {
        val points = interpolate(listOf(0f to 0f, 10f to 1f))
        assertNull(ShapeDetector.detect(points))
    }

    @Test
    fun `handwriting-like squiggle is not snapped`() {
        // A wave: open, many direction changes — should not match any shape.
        val points = (0..60).map { i ->
            StrokePoint(i * 6f, (sin(i / 3.0) * 40).toFloat(), 0.7f, i.toLong())
        }
        assertNull(ShapeDetector.detect(points))
    }

    // ----- math-notation regression: handwriting must NEVER snap -----

    @Test
    fun `letter-o sized loop stays handwriting`() {
        // A handwritten "o"/"0" is a near-perfect small circle — the size
        // gate must keep it as ink no matter how clean it is.
        val points = (0..40).map { i ->
            val a = 2 * Math.PI * i / 40
            StrokePoint(
                100f + (28 * cos(a)).toFloat(),
                100f + (28 * sin(a)).toFloat(),
                0.7f, i.toLong()
            )
        }
        assertNull(ShapeDetector.detect(points))
    }

    @Test
    fun `parenthesis curve is not snapped`() {
        // Open C-curve like "(" — tall enough to pass the size gate but it
        // is neither a line (bowed) nor a closed shape.
        val points = (0..40).map { i ->
            val a = Math.PI * (0.3 + 0.4 * i / 40.0) // 54°..126° arc
            StrokePoint(
                200f + (90 * cos(a)).toFloat(),
                200f + (160 * sin(a)).toFloat(),
                0.7f, i.toLong()
            )
        }
        assertNull(ShapeDetector.detect(points))
    }

    @Test
    fun `square-root-like stroke is not snapped`() {
        // "√": short down-left tick, then a long diagonal up, then the bar.
        val points = interpolate(
            listOf(0f to 60f, 30f to 110f, 90f to 0f, 260f to 0f),
            perEdge = 14
        )
        assertNull(ShapeDetector.detect(points))
    }

    @Test
    fun `sine-curve sketch is not snapped`() {
        // Large plotted sin(x) curve — big, smooth, open; must stay ink.
        val points = (0..80).map { i ->
            StrokePoint(i * 5f, (sin(i / 8.0) * 120).toFloat(), 0.7f, i.toLong())
        }
        assertNull(ShapeDetector.detect(points))
    }

    @Test
    fun `five-corner scribble is not snapped to a polygon`() {
        // Closed 5-corner zigzag: previously a generic POLYGON fallback
        // would snap this; now anything beyond triangle/rect stays ink.
        val points = interpolate(
            listOf(
                0f to 0f, 220f to 40f, 140f to 200f,
                260f to 260f, 20f to 240f, 0f to 4f
            )
        )
        val shape = ShapeDetector.detect(points)
        if (shape != null) {
            // If anything matched it must be a *supported* shape with real
            // geometric confidence — never a generic polygon.
            assertEquals(true, shape.type != ShapeType.POLYGON)
        }
    }

    @Test
    fun `ambiguous lumpy quad stays handwriting`() {
        // Four corners nowhere near the bounding-box corners — the old
        // POLYGON fallback would have snapped it.
        val points = interpolate(
            listOf(
                100f to 0f, 300f to 90f, 180f to 260f, 0f to 120f, 96f to 4f
            )
        )
        assertNull(ShapeDetector.detect(points))
    }
}
