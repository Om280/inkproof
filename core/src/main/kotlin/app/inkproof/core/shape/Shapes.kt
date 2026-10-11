package app.inkproof.core.shape

import app.inkproof.core.ink.distance
import app.inkproof.core.ink.distancePointToSegment
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.ShapeItem
import app.inkproof.core.model.ShapeKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Float, val y: Float)

fun lineEnds(shape: ShapeItem): Pair<Pt, Pt> = Pt(shape.x, shape.y) to Pt(shape.x + shape.w, shape.y + shape.h)

fun axisRect(shape: ShapeItem): List<Pt> {
    val l = min(shape.x, shape.x + shape.w)
    val r = max(shape.x, shape.x + shape.w)
    val t = min(shape.y, shape.y + shape.h)
    val b = max(shape.y, shape.y + shape.h)
    return listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b))
}

fun circleOf(shape: ShapeItem): Pair<Pt, Float> {
    val l = min(shape.x, shape.x + shape.w)
    val r = max(shape.x, shape.x + shape.w)
    val t = min(shape.y, shape.y + shape.h)
    val b = max(shape.y, shape.y + shape.h)
    val radius = min(r - l, b - t) / 2f
    return Pt((l + r) / 2f, (t + b) / 2f) to radius
}

fun ellipseBox(shape: ShapeItem): List<Pt> = axisRect(shape)

fun trianglePoints(shape: ShapeItem): List<Pt> {
    val l = min(shape.x, shape.x + shape.w)
    val r = max(shape.x, shape.x + shape.w)
    val t = min(shape.y, shape.y + shape.h)
    val b = max(shape.y, shape.y + shape.h)
    return listOf(Pt((l + r) / 2f, t), Pt(l, b), Pt(r, b))
}

fun arrowHead(shape: ShapeItem, size: Float = 18f): List<Pt> {
    val (a, b) = lineEnds(shape)
    val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
    val back = size * 1.15f
    val spread = 0.42
    fun polar(delta: Double, dist: Float): Pt {
        val th = ang + Math.PI + delta
        return Pt(
            (b.x + dist * kotlin.math.cos(th)).toFloat(),
            (b.y + dist * kotlin.math.sin(th)).toFloat(),
        )
    }
    return listOf(b, polar(-spread, back), polar(spread, back))
}

fun shapeFromDrag(
    id: String,
    kind: ShapeKind,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    color: Long,
    strokeWidth: Float,
): ShapeItem {
    when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> {
            return ShapeItem(id, kind, x0, y0, x1 - x0, y1 - y0, color, strokeWidth)
        }
        ShapeKind.SQUARE, ShapeKind.CIRCLE -> {
            val side = max(abs(x1 - x0), abs(y1 - y0))
            val sx = if (x1 >= x0) x0 else x0 - side
            val sy = if (y1 >= y0) y0 else y0 - side
            return ShapeItem(id, kind, sx, sy, side, side, color, strokeWidth)
        }
        else -> {
            val l = min(x0, x1)
            val t = min(y0, y1)
            val w = abs(x1 - x0)
            val h = abs(y1 - y0)
            return ShapeItem(id, kind, l, t, w, h, color, strokeWidth)
        }
    }
}

fun isAxisAlignedRectangle(shape: ShapeItem): Boolean {
    if (shape.kind != ShapeKind.RECTANGLE && shape.kind != ShapeKind.SQUARE) return false
    val pts = axisRect(shape)
    if (pts.size != 4) return false
    val top = pts[0].y
    val right = pts[1].x
    val bottom = pts[2].y
    val left = pts[3].x
    return abs(pts[1].y - top) < 0.01f &&
        abs(pts[2].x - right) < 0.01f &&
        abs(pts[3].y - bottom) < 0.01f &&
        abs(pts[0].x - left) < 0.01f &&
        right > left && bottom > top
}

data class ShapeSnap(
    val snapped: Boolean,
    val confidence: Float,
    val kind: ShapeKind? = null,
    val shape: ShapeItem? = null,
    val reason: String,
)

sealed class HoldCommit {
    data class KeepStroke(val points: List<InkPoint>) : HoldCommit()
    data class Snap(val snap: ShapeSnap) : HoldCommit()
}

fun commitAfterHold(
    points: List<InkPoint>,
    holdMillis: Long,
    enabled: Boolean = true,
    thresholdMs: Long = 450L,
    minConfidence: Float = 0.82f,
    id: String = "snap",
    color: Long = 0xFF1A1A1AL,
    strokeWidth: Float = 2.4f,
): HoldCommit {
    if (!enabled || holdMillis < thresholdMs) return HoldCommit.KeepStroke(points)
    val snap = evaluateHoldShape(points, minConfidence, id, color, strokeWidth)
    return if (snap.snapped && snap.shape != null) HoldCommit.Snap(snap) else HoldCommit.KeepStroke(points)
}

fun evaluateHoldShape(
    points: List<InkPoint>,
    minConfidence: Float = 0.82f,
    id: String = "snap",
    color: Long = 0xFF1A1A1AL,
    strokeWidth: Float = 2.4f,
): ShapeSnap {
    if (points.size < 8) return ShapeSnap(false, 0f, reason = "too few points")
    var length = 0f
    for (i in 1 until points.size) {
        length += distance(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y)
    }
    if (length < 36f) return ShapeSnap(false, 0.1f, reason = "too short")
    val endGap = distance(points.first().x, points.first().y, points.last().x, points.last().y)
    val closed = endGap / length < 0.12f
    val deviation = maxDeviationFromChord(points) / length
    val straightness = (1f - deviation * 4f).coerceIn(0f, 1f)

    if (!closed && straightness > 0.9f && endGap / length > 0.72f) {
        val kind = if (hasArrowHook(points)) ShapeKind.ARROW else ShapeKind.LINE
        val confidence = if (kind == ShapeKind.ARROW) straightness * 0.92f else straightness
        if (confidence >= minConfidence) {
            val shape = shapeFromDrag(
                id, kind, points.first().x, points.first().y, points.last().x, points.last().y, color, strokeWidth,
            )
            return ShapeSnap(true, confidence, kind, shape, "straight")
        }
    }

    val corners = significantCorners(points)
    val bounds = boundsOf(points)
    val bw = (bounds.right - bounds.left).coerceAtLeast(1f)
    val bh = (bounds.bottom - bounds.top).coerceAtLeast(1f)
    val aspect = max(bw, bh) / min(bw, bh)

    if (closed && corners.size == 4) {
        val rightAngles = corners.count { abs(it - 90.0) < 22.0 }
        val confidence = (0.55f + rightAngles * 0.1f + if (endGap / length < 0.08f) 0.08f else 0f).coerceIn(0f, 0.98f)
        val kind = if (aspect < 1.18f) ShapeKind.SQUARE else ShapeKind.RECTANGLE
        if (confidence >= minConfidence && rightAngles >= 3) {
            val shape = ShapeItem(id, kind, bounds.left, bounds.top, bw, bh, color, strokeWidth)
            return ShapeSnap(true, confidence, kind, shape, "four corners")
        }
        return ShapeSnap(false, confidence, reason = "rectangularity too low")
    }

    if (closed && corners.size == 3) {
        val ok = corners.all { it in 18.0..150.0 }
        val confidence = if (ok) 0.86f else 0.4f
        if (confidence >= minConfidence) {
            val shape = ShapeItem(id, ShapeKind.TRIANGLE, bounds.left, bounds.top, bw, bh, color, strokeWidth)
            return ShapeSnap(true, confidence, ShapeKind.TRIANGLE, shape, "three corners")
        }
    }

    if (closed) {
        val circ = circularity(points)
        val confidence = (1f - circ * 3.2f).coerceIn(0f, 0.97f)
        if (circ < 0.11f && confidence >= minConfidence) {
            val kind = if (aspect < 1.2f) ShapeKind.CIRCLE else ShapeKind.ELLIPSE
            val shape = if (kind == ShapeKind.CIRCLE) {
                val side = min(bw, bh)
                val cx = (bounds.left + bounds.right) / 2f
                val cy = (bounds.top + bounds.bottom) / 2f
                ShapeItem(id, kind, cx - side / 2f, cy - side / 2f, side, side, color, strokeWidth)
            } else {
                ShapeItem(id, kind, bounds.left, bounds.top, bw, bh, color, strokeWidth)
            }
            return ShapeSnap(true, confidence, kind, shape, "circular")
        }
        return ShapeSnap(false, confidence * 0.5f, reason = "closed but not a known shape")
    }

    return ShapeSnap(false, max(straightness * 0.4f, 0.15f), reason = "handwriting preserved")
}

private data class BBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun boundsOf(points: List<InkPoint>): BBox {
    var l = Float.POSITIVE_INFINITY
    var t = Float.POSITIVE_INFINITY
    var r = Float.NEGATIVE_INFINITY
    var b = Float.NEGATIVE_INFINITY
    for (p in points) {
        l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
    }
    return BBox(l, t, r, b)
}

private fun maxDeviationFromChord(points: List<InkPoint>): Float {
    val a = points.first()
    val b = points.last()
    var maxDev = 0f
    for (p in points) {
        maxDev = max(maxDev, distancePointToSegment(p.x, p.y, a.x, a.y, b.x, b.y))
    }
    return maxDev
}

private fun hasArrowHook(points: List<InkPoint>): Boolean {
    if (points.size < 12) return false
    val tail = points.takeLast(points.size / 5)
    val main = points.dropLast(tail.size)
    if (main.size < 4) return false
    val angMain = atan2(
        (main.last().y - main.first().y).toDouble(),
        (main.last().x - main.first().x).toDouble(),
    )
    val angTail = atan2(
        (tail.last().y - tail.first().y).toDouble(),
        (tail.last().x - tail.first().x).toDouble(),
    )
    var d = Math.toDegrees(angTail - angMain)
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return abs(d) in 28.0..140.0
}

private data class CornerMark(val x: Float, val y: Float, val angle: Double)

private fun turnBetween(a: InkPoint, b: InkPoint, c: InkPoint): Double {
    val a1 = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
    val a2 = atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble())
    var d = Math.toDegrees(a2 - a1)
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return abs(d)
}

private fun significantCorners(points: List<InkPoint>): List<Double> {
    val step = max(1, points.size / 64)
    val sampled = points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
    if (sampled.size < 6) return emptyList()
    val found = ArrayList<CornerMark>()
    fun consider(x: Float, y: Float, turn: Double) {
        if (turn < 48.0) return
        val near = found.indexOfLast { distance(it.x, it.y, x, y) < 30f }
        if (near >= 0) {
            if (turn > found[near].angle) found[near] = CornerMark(x, y, turn)
        } else {
            found.add(CornerMark(x, y, turn))
        }
    }
    for (i in 2 until sampled.size - 2) {
        val turn = turnBetween(sampled[i - 2], sampled[i], sampled[i + 2])
        consider(sampled[i].x, sampled[i].y, turn)
    }
    val length = pathLength(points).coerceAtLeast(1f)
    val closed = distance(points.first().x, points.first().y, points.last().x, points.last().y) / length < 0.14f
    if (closed && sampled.size > 4) {
        val join = turnBetween(sampled[sampled.lastIndex - 2], sampled.first(), sampled[2])
        consider(sampled.first().x, sampled.first().y, join)
    }
    return found.map { it.angle }
}

private fun circularity(points: List<InkPoint>): Float {
    var cx = 0f
    var cy = 0f
    for (p in points) {
        cx += p.x; cy += p.y
    }
    cx /= points.size
    cy /= points.size
    val dists = points.map { distance(it.x, it.y, cx, cy) }
    val mean = dists.average().toFloat().coerceAtLeast(1f)
    var varAcc = 0.0
    for (d in dists) varAcc += (d - mean) * (d - mean)
    val std = kotlin.math.sqrt(varAcc / dists.size).toFloat()
    return std / mean
}

fun pointInPolygon(x: Float, y: Float, poly: List<InkPoint>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val yi = poly[i].y
        val yj = poly[j].y
        val xi = poly[i].x
        val xj = poly[j].x
        val intersect = ((yi > y) != (yj > y)) &&
            (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-6f) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}

fun selectByLasso(objects: List<app.inkproof.core.model.PageObject>, polygon: List<InkPoint>): Set<String> {
    if (polygon.size < 3) return emptySet()
    val selected = LinkedHashSet<String>()
    for (obj in objects) {
        when (obj) {
            is app.inkproof.core.model.Stroke -> {
                if (obj.points.isEmpty()) continue
                val inside = obj.points.count { pointInPolygon(it.x, it.y, polygon) }
                if (inside.toFloat() / obj.points.size >= 0.35f) selected.add(obj.id)
            }
            else -> {
                val b = app.inkproof.core.model.objectBounds(obj)
                if (pointInPolygon(b.centerX, b.centerY, polygon)) selected.add(obj.id)
            }
        }
    }
    return selected
}

fun pathLength(points: List<InkPoint>): Float {
    var s = 0f
    for (i in 1 until points.size) s += distance(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y)
    return s
}
