package app.inkproof.core.ink

import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.InkTool
import app.inkproof.core.model.StabilizerLevel
import app.inkproof.core.model.Stroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DrawStyle(
    val width: Float,
    val alpha: Float,
    val translucent: Boolean,
    val tool: InkTool,
)

fun styleFor(tool: InkTool, width: Float, markerAlpha: Float): DrawStyle = when (tool) {
    InkTool.PEN -> DrawStyle(width = width, alpha = 1f, translucent = false, tool = tool)
    InkTool.MARKER -> DrawStyle(
        width = width,
        alpha = markerAlpha.coerceIn(0.12f, 0.72f),
        translucent = true,
        tool = tool,
    )
}

fun pressureWidth(base: Float, pressure: Float, tool: InkTool): Float {
    val p = pressure.coerceIn(0.05f, 1f)
    return if (tool == InkTool.MARKER) base * (0.9f + 0.1f * p) else base * (0.35f + 0.8f * p)
}

fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

fun distancePointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 <= 1e-6f) return distance(px, py, ax, ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    return distance(px, py, ax + t * dx, ay + t * dy)
}

private fun turnDegrees(a: InkPoint, b: InkPoint, c: InkPoint): Double {
    val a1 = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
    val a2 = atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble())
    var d = Math.toDegrees(a2 - a1)
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return abs(d)
}

fun detectCornerIndices(points: List<InkPoint>, angleDeg: Double, minDist: Float): Set<Int> {
    if (points.size < 3) return emptySet()
    val corners = mutableSetOf<Int>()
    var lastIndex = -1
    for (i in 1 until points.lastIndex) {
        if (turnDegrees(points[i - 1], points[i], points[i + 1]) >= angleDeg) {
            if (lastIndex < 0 || distance(points[lastIndex].x, points[lastIndex].y, points[i].x, points[i].y) >= minDist) {
                corners.add(i)
                lastIndex = i
            }
        }
    }
    return corners
}

/**
 * Smooths completed ink without moving endpoints or sharp corners.
 * The live tip is never delayed: callers must append the raw latest point after this.
 */
fun stabilize(points: List<InkPoint>, level: StabilizerLevel): List<InkPoint> {
    if (level == StabilizerLevel.OFF || points.size < 3) return points
    val strength = when (level) {
        StabilizerLevel.LOW -> 0.35f
        StabilizerLevel.MEDIUM -> 0.62f
        StabilizerLevel.HIGH -> 0.88f
        StabilizerLevel.OFF -> 0f
    }
    val cornerAngle = when (level) {
        StabilizerLevel.LOW -> 62.0
        StabilizerLevel.MEDIUM -> 48.0
        StabilizerLevel.HIGH -> 36.0
        StabilizerLevel.OFF -> 180.0
    }
    val length = pathLength(points).coerceAtLeast(1f)
    val straight = maxChordDeviation(points) / length < 0.08f
    val anchors = if (straight) {
        listOf(0, points.lastIndex)
    } else {
        (listOf(0) + detectCornerIndices(points, cornerAngle, minDist = 12f).sorted() + listOf(points.lastIndex))
            .distinct()
            .sorted()
    }
    val out = points.toMutableList()
    for (segment in 0 until anchors.size - 1) {
        val start = anchors[segment]
        val end = anchors[segment + 1]
        val span = (end - start).coerceAtLeast(1)
        for (i in start + 1 until end) {
            val t = (i - start).toFloat() / span
            val chordX = points[start].x + (points[end].x - points[start].x) * t
            val chordY = points[start].y + (points[end].y - points[start].y) * t
            out[i] = points[i].copy(
                x = points[i].x + (chordX - points[i].x) * strength,
                y = points[i].y + (chordY - points[i].y) * strength,
            )
        }
    }
    return out
}

class ActiveStrokeController {
    private val raw = ArrayList<InkPoint>()
    var pageId: String? = null
        private set
    var tool: InkTool = InkTool.PEN
        private set
    var color: Long = 0xFF1A1A1AL
        private set
    var width: Float = 2.6f
        private set
    var alpha: Float = 1f
        private set
    var level: StabilizerLevel = StabilizerLevel.MEDIUM
        private set
    var questionId: String? = null
    var region: app.inkproof.core.model.InkRegion = app.inkproof.core.model.InkRegion.FREE
    val active: Boolean get() = pageId != null

    fun begin(
        pageId: String,
        first: InkPoint,
        tool: InkTool,
        color: Long,
        width: Float,
        alpha: Float,
        level: StabilizerLevel,
    ) {
        raw.clear()
        this.pageId = pageId
        this.tool = tool
        this.color = color
        this.width = width
        this.alpha = alpha
        this.level = level
        questionId = null
        region = app.inkproof.core.model.InkRegion.FREE
        raw.add(first)
    }

    fun add(point: InkPoint) {
        if (pageId == null) return
        val last = raw.lastOrNull()
        if (last != null && distance(last.x, last.y, point.x, point.y) < 0.35f) return
        raw.add(point)
    }

    fun rawPoints(): List<InkPoint> = raw.toList()

    /**
     * Preview keeps the newest raw point as the tip so stabilization cannot add input latency.
     */
    fun preview(): List<InkPoint> {
        if (raw.size < 4 || level == StabilizerLevel.OFF) return raw.toList()
        val head = stabilize(raw.subList(0, raw.size - 1), level)
        return head + raw.last()
    }

    fun finishStabilized(): List<InkPoint> = stabilize(raw.toList(), level)

    fun cancel() {
        raw.clear()
        pageId = null
    }

    fun toStroke(id: String, stabilized: Boolean): Stroke? {
        val pts = if (stabilized) finishStabilized() else raw.toList()
        if (pts.size < 1 || pageId == null) return null
        val stroke = Stroke(
            id = id,
            points = if (pts.size == 1) listOf(pts[0], pts[0].copy(x = pts[0].x + 0.4f)) else pts,
            color = color,
            width = width,
            tool = tool,
            alpha = alpha,
            questionId = questionId,
            region = region,
        )
        cancel()
        return stroke
    }
}

fun resample(points: List<InkPoint>, spacing: Float): List<InkPoint> {
    if (points.size < 2) return points
    val out = ArrayList<InkPoint>()
    out.add(points.first())
    var prev = points.first()
    var carry = 0f
    for (i in 1 until points.size) {
        val next = points[i]
        var seg = distance(prev.x, prev.y, next.x, next.y)
        if (seg < 1e-4f) {
            prev = next
            continue
        }
        var distAlong = spacing - carry
        while (distAlong <= seg) {
            val t = distAlong / seg
            out.add(
                InkPoint(
                    x = prev.x + (next.x - prev.x) * t,
                    y = prev.y + (next.y - prev.y) * t,
                    pressure = prev.pressure + (next.pressure - prev.pressure) * t,
                    t = prev.t,
                ),
            )
            distAlong += spacing
        }
        carry = seg - (distAlong - spacing)
        prev = next
    }
    if (distance(out.last().x, out.last().y, points.last().x, points.last().y) > 0.5f) {
        out.add(points.last())
    }
    return out
}

data class EraseResult(
    val strokes: List<Stroke>,
    val changed: Boolean,
)

fun eraseStroke(stroke: Stroke, cx: Float, cy: Float, radius: Float, newId: (Int) -> String): EraseResult {
    if (stroke.points.size < 2) {
        val p = stroke.points.firstOrNull()
        if (p != null && distance(p.x, p.y, cx, cy) <= radius + stroke.width) {
            return EraseResult(emptyList(), true)
        }
        return EraseResult(listOf(stroke), false)
    }
    val hitRadius = radius + stroke.width * 0.5f
    val dense = resample(stroke.points, spacing = 2.2f)
    val keep = dense.map { p ->
        var near = distance(p.x, p.y, cx, cy) <= hitRadius
        if (!near) {
            // segment test is covered by dense resampling
        }
        !near
    }
    if (keep.all { it }) return EraseResult(listOf(stroke), false)
    if (keep.none { it }) return EraseResult(emptyList(), true)
    val runs = ArrayList<MutableList<InkPoint>>()
    var current: MutableList<InkPoint>? = null
    for (i in dense.indices) {
        if (keep[i]) {
            if (current == null) {
                current = mutableListOf()
                runs.add(current)
            }
            current.add(dense[i])
        } else {
            current = null
        }
    }
    val pieces = runs.filter { it.size >= 2 }.mapIndexed { index, pts ->
        stroke.copy(id = newId(index), points = pts)
    }
    return EraseResult(pieces, true)
}

fun eraseStrokes(
    strokes: List<Stroke>,
    cx: Float,
    cy: Float,
    radius: Float,
    ids: app.inkproof.core.model.Ids,
): Pair<List<Stroke>, Boolean> {
    var changed = false
    val out = ArrayList<Stroke>(strokes.size)
    for (stroke in strokes) {
        val bounds = app.inkproof.core.model.strokeBounds(stroke).inflate(radius)
        if (!bounds.contains(cx, cy) && !bounds.inflate(0f).let {
                cx in it.left..it.right && cy in it.top..it.bottom
            }
        ) {
            // still check contains via inflate already
        }
        if (cx < bounds.left || cx > bounds.right || cy < bounds.top || cy > bounds.bottom) {
            out.add(stroke)
            continue
        }
        var n = 0
        val result = eraseStroke(stroke, cx, cy, radius) { ids.newId().also { n++ } }
        if (result.changed) changed = true
        out.addAll(result.strokes)
    }
    return out to changed
}

private fun pathLength(points: List<InkPoint>): Float {
    var length = 0f
    for (i in 1 until points.size) {
        length += distance(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y)
    }
    return length
}

private fun maxChordDeviation(points: List<InkPoint>): Float {
    if (points.size < 2) return 0f
    val a = points.first()
    val b = points.last()
    var maxDev = 0f
    for (p in points) {
        maxDev = max(maxDev, distancePointToSegment(p.x, p.y, a.x, a.y, b.x, b.y))
    }
    return maxDev
}

fun meanDeviationFromLine(points: List<InkPoint>): Float {
    if (points.size < 2) return 0f
    val a = points.first()
    val b = points.last()
    var acc = 0.0
    for (p in points) {
        acc += distancePointToSegment(p.x, p.y, a.x, a.y, b.x, b.y)
    }
    return (acc / points.size).toFloat()
}
