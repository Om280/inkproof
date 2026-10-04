package com.inkproof.app

import com.inkproof.app.check.LineSegmenter
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSegmenterTest {

    private fun strokeAt(x: Float, y: Float, w: Float = 60f, h: Float = 30f) = Stroke(
        pageId = "p",
        color = 0,
        baseWidth = 3f,
        points = listOf(
            StrokePoint(x, y, 1f, 0),
            StrokePoint(x + w, y + h, 1f, 10)
        )
    )

    @Test
    fun `strokes on separate lines are segmented`() {
        val line1 = listOf(strokeAt(0f, 0f), strokeAt(70f, 4f), strokeAt(150f, 2f))
        val line2 = listOf(strokeAt(0f, 120f), strokeAt(80f, 124f))
        val line3 = listOf(strokeAt(0f, 260f))
        val lines = LineSegmenter.segment((line1 + line2 + line3).shuffled())
        assertEquals(3, lines.size)
        assertEquals(3, lines[0].size)
        assertEquals(2, lines[1].size)
        assertEquals(1, lines[2].size)
    }

    @Test
    fun `strokes within a line are ordered left to right`() {
        val lines = LineSegmenter.segment(
            listOf(strokeAt(200f, 0f), strokeAt(0f, 3f), strokeAt(100f, 1f))
        )
        assertEquals(1, lines.size)
        val xs = lines[0].map { it.bounds().left }
        assertTrue(xs[0] < xs[1] && xs[1] < xs[2])
    }

    @Test
    fun `empty input yields no lines`() {
        assertTrue(LineSegmenter.segment(emptyList()).isEmpty())
    }
}
