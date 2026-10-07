package com.inkproof.app

import com.inkproof.app.ink.StrokeStabilizer
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeStabilizerTest {

    @Test
    fun `OFF passes every sample through untouched`() {
        val s = StrokeStabilizer(StrokeStabilizer.Level.OFF)
        s.reset()
        val out1 = s.filter(10f, 20f)
        val out2 = s.filter(10.3f, 20.7f)
        assertEquals(10f, out1[0], 0.0001f)
        assertEquals(20.7f, out2[1], 0.0001f)
    }

    @Test
    fun `first sample of a stroke is never moved`() {
        val s = StrokeStabilizer(StrokeStabilizer.Level.HIGH)
        s.reset()
        val out = s.filter(123.4f, 56.7f)
        assertEquals(123.4f, out[0], 0.0001f)
        assertEquals(56.7f, out[1], 0.0001f)
    }

    @Test
    fun `medium smoothing reduces jitter on a slow straight line`() {
        val rng = Random(42)
        val s = StrokeStabilizer(StrokeStabilizer.Level.MEDIUM)
        s.reset()
        var rawDeviation = 0f
        var smoothDeviation = 0f
        var x = 0f
        for (i in 0 until 200) {
            x += 1.2f // slow horizontal movement
            val jitterY = 100f + (rng.nextFloat() - 0.5f) * 3f // ±1.5 jitter
            val out = s.filter(x, jitterY)
            rawDeviation += abs(jitterY - 100f)
            smoothDeviation += abs(out[1] - 100f)
        }
        assertTrue(
            "smoothed $smoothDeviation should be well under raw $rawDeviation",
            smoothDeviation < rawDeviation * 0.6f
        )
    }

    @Test
    fun `fast intentional movement passes nearly unfiltered - corners survive`() {
        val s = StrokeStabilizer(StrokeStabilizer.Level.HIGH)
        s.reset()
        s.filter(0f, 0f)
        // A long, fast jump (well past cornerDistance) must land essentially
        // at the raw target, not be dragged back by smoothing.
        val out = s.filter(100f, 0f)
        assertTrue("fast move filtered too hard: ${out[0]}", out[0] > 95f)
    }

    @Test
    fun `last raw position is tracked for endpoint restoration`() {
        val s = StrokeStabilizer(StrokeStabilizer.Level.HIGH)
        s.reset()
        s.filter(0f, 0f)
        s.filter(1f, 1f)
        s.filter(2.5f, 3.5f)
        assertEquals(2.5f, s.lastRawX, 0.0001f)
        assertEquals(3.5f, s.lastRawY, 0.0001f)
    }

    @Test
    fun `level parses from settings strings with fallback to medium`() {
        assertEquals(StrokeStabilizer.Level.OFF, StrokeStabilizer.Level.fromName("off"))
        assertEquals(StrokeStabilizer.Level.HIGH, StrokeStabilizer.Level.fromName("HIGH"))
        assertEquals(StrokeStabilizer.Level.MEDIUM, StrokeStabilizer.Level.fromName("bogus"))
        assertEquals(StrokeStabilizer.Level.MEDIUM, StrokeStabilizer.Level.fromName(null))
    }
}
