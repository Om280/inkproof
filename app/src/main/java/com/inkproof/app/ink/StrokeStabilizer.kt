package com.inkproof.app.ink

import kotlin.math.hypot
import kotlin.math.min

/**
 * Real-time handwriting stabilization.
 *
 * An adaptive exponential moving average smooths sub-millimetre jitter while
 * deliberately letting fast, intentional movement through almost unfiltered —
 * that is what preserves corners and natural handwriting character.
 *
 * LATENCY: zero added frames. Every input sample produces an output sample
 * immediately; smoothing only nudges WHERE the point lands, never WHEN it
 * appears. The caller preserves the raw final position on stroke completion
 * so strokes always reach exactly where the pen lifted.
 */
class StrokeStabilizer(var level: Level = Level.MEDIUM) {

    enum class Level(
        /** Base smoothing factor: fraction of the raw sample kept (1 = off). */
        internal val baseAlpha: Float,
        /** Travel distance (page units) at which smoothing fully yields. */
        internal val cornerDistance: Float
    ) {
        OFF(1f, 0f),
        LOW(0.55f, 10f),
        MEDIUM(0.38f, 14f),
        HIGH(0.24f, 18f);

        companion object {
            fun fromName(name: String?): Level =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
        }
    }

    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f

    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set

    /** Call at every stroke start. */
    fun reset() {
        hasLast = false
    }

    /**
     * Filter one incoming sample; returns the point to render/store.
     * The first sample of a stroke always passes through untouched.
     */
    fun filter(rawX: Float, rawY: Float): FloatArray {
        lastRawX = rawX
        lastRawY = rawY
        if (level == Level.OFF || !hasLast) {
            hasLast = true
            lastX = rawX
            lastY = rawY
            return floatArrayOf(rawX, rawY)
        }
        val travel = hypot(rawX - lastX, rawY - lastY)
        // Fast movement (long travel) = intent: let it through (alpha → 1).
        // Slow movement (short travel) = jitter: smooth it (alpha → base).
        val passThrough = if (level.cornerDistance <= 0f) 1f
        else min(1f, travel / level.cornerDistance)
        val alpha = level.baseAlpha + (1f - level.baseAlpha) * passThrough
        lastX += (rawX - lastX) * alpha
        lastY += (rawY - lastY) * alpha
        return floatArrayOf(lastX, lastY)
    }
}
