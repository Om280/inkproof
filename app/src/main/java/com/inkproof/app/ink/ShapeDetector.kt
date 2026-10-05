package com.inkproof.app.ink

import com.inkproof.app.model.DetectedShape
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
 * Pure-geometry shape recognition for DRAW -> HOLD -> SNAP TO SHAPE.
 *
 * Runs ONCE when the hold threshold fires — never continuously during
 * writing. Deterministic and fully unit-tested.
 */
object ShapeDetector {

    /** Minimum stroke size (page units) worth snapping. */
    private const val MIN_SIZE = 24f

    fun detect(points: List<StrokePoint>): DetectedShape? {
        if (points.size < 8) return null
        val b = bounds(points)
        if (max(b.width, b.height) < MIN_SIZE) return null

        detectArrow(points)?.let { return it }
        detectLine(points)?.let { return it }
        detectCircleOrEllipse(points)?.let { return it }
        detectPolygon(points)?.let { return it }
        return null
    }

    // ----- arrow -----

    /**
     * Single-stroke arrow: a dominant straight shaft followed by a short
     * doubling-back arrowhead near the tip (corners only in the tail).
     */
    private fun detectArrow(points: List<StrokePoint>): DetectedShape? {
        val n = points.size
        val corners = findCorners(points)
        if (corners.isEmpty()) return null
        val firstCorner = corners.first()
        // Shaft must dominate the stroke; all corners live in the tail.
        if (firstCorner < n * 0.55f) return null

        val start = points.first()
        val tip = points[firstCorner]
        val chord = hypot(tip.x - start.x, tip.y - start.y)
        if (chord < MIN_SIZE) return null

        val shaft = points.subList(0, firstCorner + 1)
        val maxDev = shaft.maxOf { distanceToSegment(it, start, tip) }
        if (maxDev > chord * 0.08f + 6f) return null

        // The tail must stay close to the tip (it is the head, not a new edge).
        val tail = points.subList(firstCorner, n)
        val headSpan = tail.maxOf { hypot(it.x - tip.x, it.y - tip.y) }
        if (headSpan > chord * 0.45f || headSpan < chord * 0.07f) return null

        val angle = atan2(tip.y - start.y, tip.x - start.x).toDouble()
        val headLen = headSpan.coerceIn(chord * 0.12f, chord * 0.3f)
        val a1 = angle + Math.toRadians(150.0)
        val a2 = angle - Math.toRadians(150.0)
        val barb1 = StrokePoint(
            tip.x + headLen * cos(a1).toFloat(),
            tip.y + headLen * sin(a1).toFloat(), 0.7f, 2
        )
        val barb2 = StrokePoint(
            tip.x + headLen * cos(a2).toFloat(),
            tip.y + headLen * sin(a2).toFloat(), 0.7f, 4
        )
        return DetectedShape(
            type = ShapeType.ARROW,
            points = listOf(
                StrokePoint(start.x, start.y, 0.7f, 0),
                StrokePoint(tip.x, tip.y, 0.7f, 1),
                barb1,
                StrokePoint(tip.x, tip.y, 0.7f, 3),
                barb2
            ),
            confidence = 0.8f
        )
    }

    // ----- line / arrow -----

    private fun detectLine(points: List<StrokePoint>): DetectedShape? {
        val first = points.first()
        val last = points.last()
        val chord = hypot(last.x - first.x, last.y - first.y)
        if (chord < MIN_SIZE) return null

        val maxDeviation = points.maxOf { distanceToSegment(it, first, last) }
        if (maxDeviation > chord * 0.07f + 6f) return null

        val snapped = snapAngle(first, last)
        return DetectedShape(
            type = ShapeType.LINE,
            points = listOf(
                StrokePoint(first.x, first.y, 0.7f, 0),
                StrokePoint(snapped.first, snapped.second, 0.7f, 1)
            ),
            confidence = 1f - (maxDeviation / (chord + 1f))
        )
    }

    /** Snap nearly-horizontal/vertical/45° lines to the exact angle. */
    private fun snapAngle(first: StrokePoint, last: StrokePoint): Pair<Float, Float> {
        val dx = last.x - first.x
        val dy = last.y - first.y
        val len = hypot(dx, dy)
        val angle = atan2(dy, dx)
        val step = Math.PI / 4
        val snappedAngle = (Math.round(angle / step) * step)
        return if (abs(angle - snappedAngle.toFloat()) < Math.toRadians(7.0).toFloat()) {
            Pair(
                first.x + len * cos(snappedAngle).toFloat(),
                first.y + len * sin(snappedAngle).toFloat()
            )
        } else {
            Pair(last.x, last.y)
        }
    }

    // ----- circle / ellipse -----

    private fun detectCircleOrEllipse(points: List<StrokePoint>): DetectedShape? {
        val first = points.first()
        val last = points.last()
        val b = bounds(points)
        val size = max(b.width, b.height)
        // Closed-ish curve?
        if (hypot(last.x - first.x, last.y - first.y) > size * 0.35f) return null

        val cx = b.centerX
        val cy = b.centerY
        val rx = b.width / 2f
        val ry = b.height / 2f
        if (rx < MIN_SIZE / 2 || ry < MIN_SIZE / 2) return null

        // Points should hug the fitted ellipse.
        var err = 0f
        for (p in points) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            err += abs(hypot(nx, ny) - 1f)
        }
        err /= points.size
        if (err > 0.24f) return null

        // Corner-ness check: rectangles also "hug" an ellipse loosely, but
        // have large flat runs; measure direction-change distribution.
        if (cornerCount(points) >= 3) return null

        val isCircle = abs(rx - ry) < 0.18f * max(rx, ry)
        val type = if (isCircle) ShapeType.CIRCLE else ShapeType.ELLIPSE
        val r = (rx + ry) / 2f
        val out = ArrayList<StrokePoint>(65)
        for (i in 0..64) {
            val a = 2 * Math.PI * i / 64
            val px = if (isCircle) cx + r * cos(a).toFloat() else cx + rx * cos(a).toFloat()
            val py = if (isCircle) cy + r * sin(a).toFloat() else cy + ry * sin(a).toFloat()
            out.add(StrokePoint(px, py, 0.7f, i.toLong()))
        }
        return DetectedShape(type, out, 1f - err)
    }

    // ----- polygons (triangle / rectangle / square / generic) -----

    private fun detectPolygon(points: List<StrokePoint>): DetectedShape? {
        val first = points.first()
        val last = points.last()
        val b = bounds(points)
        val size = max(b.width, b.height)
        if (hypot(last.x - first.x, last.y - first.y) > size * 0.4f) return null

        val interior = findCorners(points)
        val window = (points.size / 12).coerceIn(2, 8)
        val vertices = ArrayList<StrokePoint>()
        // The stroke's start/end seam of a closed shape is usually a corner
        // itself (people start drawing rectangles at a corner) — findCorners
        // only sees interior indices, so test the seam explicitly.
        if (seamIsCorner(points, window)) {
            val seam = points.first()
            val nearDuplicate = interior.firstOrNull()?.let { idx ->
                hypot(points[idx].x - seam.x, points[idx].y - seam.y) < size * 0.08f
            } ?: false
            if (!nearDuplicate) vertices.add(seam)
        }
        interior.mapTo(vertices) { points[it] }
        if (vertices.size < 3 || vertices.size > 8) return null

        return when (vertices.size) {
            3 -> DetectedShape(ShapeType.TRIANGLE, closeRing(vertices), 0.85f)
            4 -> {
                // Axis-aligned-ish quadrilateral -> rectangle/square
                val rect = tryRectangle(vertices, b)
                rect ?: DetectedShape(ShapeType.POLYGON, closeRing(vertices), 0.7f)
            }
            else -> DetectedShape(ShapeType.POLYGON, closeRing(vertices), 0.65f)
        }
    }

    private fun tryRectangle(vertices: List<StrokePoint>, b: B): DetectedShape? {
        // Check whether the 4 corners are near the bounding box corners.
        val boxCorners = listOf(
            Pair(b.left, b.top), Pair(b.right, b.top),
            Pair(b.right, b.bottom), Pair(b.left, b.bottom)
        )
        val tolerance = 0.22f * max(b.width, b.height)
        val matched = BooleanArray(4)
        for (v in vertices) {
            var bestI = -1
            var bestD = Float.MAX_VALUE
            boxCorners.forEachIndexed { i, c ->
                val d = hypot(v.x - c.first, v.y - c.second)
                if (d < bestD) { bestD = d; bestI = i }
            }
            if (bestD <= tolerance && !matched[bestI]) matched[bestI] = true
        }
        if (matched.count { it } < 4) return null

        val isSquare = abs(b.width - b.height) < 0.15f * max(b.width, b.height)
        val type = if (isSquare) ShapeType.SQUARE else ShapeType.RECTANGLE
        val (l, t, r, bo) = if (isSquare) {
            val s = (b.width + b.height) / 2f
            listOf(b.centerX - s / 2, b.centerY - s / 2, b.centerX + s / 2, b.centerY + s / 2)
        } else {
            listOf(b.left, b.top, b.right, b.bottom)
        }
        val ring = listOf(
            StrokePoint(l, t, 0.7f, 0),
            StrokePoint(r, t, 0.7f, 1),
            StrokePoint(r, bo, 0.7f, 2),
            StrokePoint(l, bo, 0.7f, 3)
        )
        return DetectedShape(type, closeRing(ring), 0.9f)
    }

    private fun closeRing(vertices: List<StrokePoint>): List<StrokePoint> =
        vertices + vertices.first().copy(t = vertices.size.toLong())

    // ----- corner detection -----

    internal fun findCorners(points: List<StrokePoint>): List<Int> {
        val n = points.size
        if (n < 8) return emptyList()
        val window = (n / 12).coerceIn(2, 8)
        val corners = ArrayList<Int>()
        var lastCorner = -window * 2
        for (i in window until n - window) {
            val a = points[i - window]
            val b = points[i]
            val c = points[i + window]
            val v1x = b.x - a.x; val v1y = b.y - a.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val l1 = hypot(v1x, v1y); val l2 = hypot(v2x, v2y)
            if (l1 < 1e-3 || l2 < 1e-3) continue
            val dot = (v1x * v2x + v1y * v2y) / (l1 * l2)
            val angle = Math.acos(dot.coerceIn(-1f, 1f).toDouble())
            if (angle > Math.toRadians(45.0) && i - lastCorner > window * 2) {
                corners.add(i)
                lastCorner = i
            }
        }
        return corners
    }

    private fun cornerCount(points: List<StrokePoint>): Int = findCorners(points).size

    /** Is the junction between stroke end and stroke start itself a corner? */
    private fun seamIsCorner(points: List<StrokePoint>, window: Int): Boolean {
        val n = points.size
        if (n < window * 2 + 2) return false
        val endA = points[n - 1 - window]
        val endB = points[n - 1]
        val startA = points[0]
        val startB = points[window]
        var v1x = endB.x - endA.x; var v1y = endB.y - endA.y
        var v2x = startB.x - startA.x; var v2y = startB.y - startA.y
        val l1 = hypot(v1x, v1y); val l2 = hypot(v2x, v2y)
        if (l1 < 1e-3f || l2 < 1e-3f) return false
        v1x /= l1; v1y /= l1; v2x /= l2; v2y /= l2
        val dot = (v1x * v2x + v1y * v2y).coerceIn(-1f, 1f)
        return Math.acos(dot.toDouble()) > Math.toRadians(45.0)
    }

    // ----- helpers -----

    private data class B(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    private fun bounds(points: List<StrokePoint>): B {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return B(minX, minY, maxX, maxY)
    }

    private fun distanceToSegment(p: StrokePoint, a: StrokePoint, b: StrokePoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
        t = t.coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
