package com.inkproof.app

import com.inkproof.app.data.db.StrokeCodec
import com.inkproof.app.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeCodecTest {

    @Test
    fun `roundtrip preserves all point data`() {
        val points = (0 until 500).map {
            StrokePoint(it * 1.5f, it * -2.25f, (it % 10) / 10f + 0.05f, it * 8L)
        }
        val decoded = StrokeCodec.decode(StrokeCodec.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (a, b) ->
            assertEquals(a.x, b.x, 1e-5f)
            assertEquals(a.y, b.y, 1e-5f)
            assertEquals(a.pressure, b.pressure, 1e-5f)
            assertEquals(a.t, b.t)
        }
    }

    @Test
    fun `empty list roundtrips`() {
        assertTrue(StrokeCodec.decode(StrokeCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `garbage bytes decode to empty, never crash`() {
        assertTrue(StrokeCodec.decode(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(StrokeCodec.decode(ByteArray(0)).isEmpty())
        assertTrue(StrokeCodec.decode(ByteArray(64) { 0x7F }).isEmpty())
    }
}
