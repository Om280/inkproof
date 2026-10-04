package com.inkproof.app.data.db

import com.inkproof.app.model.StrokePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs stroke points into a compact binary blob:
 * [count:int] then per point [x:float][y:float][pressure:float][t:int (ms offset)].
 *
 * This keeps page loads fast even with thousands of strokes, while remaining
 * a fully editable vector representation (never a bitmap).
 */
object StrokeCodec {
    private const val VERSION: Int = 1

    fun encode(points: List<StrokePoint>): ByteArray {
        val buf = ByteBuffer.allocate(8 + points.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(VERSION)
        buf.putInt(points.size)
        for (p in points) {
            buf.putFloat(p.x)
            buf.putFloat(p.y)
            buf.putFloat(p.pressure)
            buf.putInt(p.t.toInt())
        }
        return buf.array()
    }

    fun decode(bytes: ByteArray): List<StrokePoint> {
        if (bytes.size < 8) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.getInt()
        if (version != VERSION) return emptyList()
        val count = buf.getInt()
        if (count < 0 || bytes.size < 8 + count * 16) return emptyList()
        val out = ArrayList<StrokePoint>(count)
        repeat(count) {
            val x = buf.getFloat()
            val y = buf.getFloat()
            val pr = buf.getFloat()
            val t = buf.getInt().toLong()
            out.add(StrokePoint(x, y, pr, t))
        }
        return out
    }
}
