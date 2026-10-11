package app.inkproof.core.recognize

import app.inkproof.core.ink.distance
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.Stroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SymbolMatch(val symbol: String, val confidence: Float, val distance: Float)

data class SymbolBox(
    val text: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val confidence: Float,
)

data class RecognitionDraft(
    val text: String,
    val confidence: Float,
    val source: String,
)

class UnistrokeRecognizer {
    private val templates: List<Pair<String, List<InkPoint>>> = buildTemplates()

    fun recognizeSymbol(points: List<InkPoint>): SymbolMatch? {
        if (points.size < 2) return null
        val candidate = normalize(points)
        var best: SymbolMatch? = null
        var second = Float.POSITIVE_INFINITY
        for ((symbol, template) in templates) {
            val d = pathDistance(candidate, template)
            if (best == null || d < best.distance) {
                if (best != null) second = best.distance
                best = SymbolMatch(symbol, confidenceFrom(d, second), d)
            } else if (d < second) {
                second = d
            }
        }
        val winner = best ?: return null
        val confidence = confidenceFrom(winner.distance, second)
        if (confidence < 0.2f) return SymbolMatch(winner.symbol, confidence, winner.distance)
        return winner.copy(confidence = confidence)
    }

    fun recognizeExpression(strokes: List<Stroke>): RecognitionDraft {
        if (strokes.isEmpty()) return RecognitionDraft("", 0f, "unistroke")
        val boxes = strokes.mapNotNull { stroke ->
            val match = recognizeSymbol(stroke.points) ?: return@mapNotNull null
            val b = bounds(stroke.points)
            SymbolBox(match.symbol, b.first, b.second, b.third, b.fourth, match.confidence)
        }
        if (boxes.isEmpty()) return RecognitionDraft("", 0.1f, "unistroke")
        val text = assemble(boxes)
        val confidence = boxes.map { it.confidence }.average().toFloat() * if (text.contains('?')) 0.45f else 1f
        return RecognitionDraft(text, confidence.coerceIn(0f, 0.99f), "unistroke")
    }
}

fun assemble(symbols: List<SymbolBox>): String {
    if (symbols.isEmpty()) return ""
    val sorted = symbols.sortedWith(compareBy({ it.y + it.h / 2f }, { it.x }))
    val lines = ArrayList<MutableList<SymbolBox>>()
    for (symbol in sorted) {
        val cy = symbol.y + symbol.h / 2f
        val line = lines.firstOrNull { existing ->
            val ref = existing.first()
            val ry = ref.y + ref.h / 2f
            abs(cy - ry) < max(ref.h, symbol.h) * 0.65f
        }
        if (line == null) lines.add(mutableListOf(symbol)) else line.add(symbol)
    }
    return lines.joinToString("\n") { line -> assembleLine(line.sortedBy { it.x }) }
}

private fun assembleLine(line: List<SymbolBox>): String {
    if (line.isEmpty()) return ""
    val medianH = line.map { it.h }.sorted()[line.size / 2]
    val baseline = line.map { it.y + it.h }.sorted()[line.size / 2]
    val sb = StringBuilder()
    for (symbol in line) {
        val superScript = symbol.h < medianH * 0.72f && (symbol.y + symbol.h) < baseline - medianH * 0.28f
        val text = if (symbol.confidence < 0.35f) "?" else symbol.text
        if (superScript) sb.append("^")
        sb.append(text)
    }
    return sb.toString()
}

private fun confidenceFrom(best: Float, second: Float): Float {
    val raw = (0.42f - best) / 0.42f
    val margin = if (second.isFinite()) ((second - best) / 0.42f).coerceIn(0f, 0.25f) else 0f
    return (raw + margin).coerceIn(0f, 0.99f)
}

private fun pathDistance(a: List<InkPoint>, b: List<InkPoint>): Float {
    val n = min(a.size, b.size)
    if (n == 0) return 1f
    var acc = 0.0
    for (i in 0 until n) acc += distance(a[i].x, a[i].y, b[i].x, b[i].y)
    return (acc / n).toFloat()
}

private fun normalize(points: List<InkPoint>): List<InkPoint> {
    val sampled = resampleCount(points, 64)
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (p in sampled) {
        minX = min(minX, p.x); minY = min(minY, p.y)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y)
    }
    val w = (maxX - minX).coerceAtLeast(1e-3f)
    val h = (maxY - minY).coerceAtLeast(1e-3f)
    val scale = max(w, h)
    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f
    return sampled.map { InkPoint((it.x - cx) / scale, (it.y - cy) / scale) }
}

private fun resampleCount(points: List<InkPoint>, count: Int): List<InkPoint> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return List(count) { points.first() }
    var length = 0f
    val acc = FloatArray(points.size)
    for (i in 1 until points.size) {
        length += distance(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y)
        acc[i] = length
    }
    if (length < 1e-4f) return List(count) { points.first() }
    val step = length / (count - 1)
    val out = ArrayList<InkPoint>(count)
    var seg = 1
    for (i in 0 until count) {
        val target = step * i
        while (seg < acc.size - 1 && acc[seg] < target) seg++
        val prev = acc[seg - 1]
        val span = (acc[seg] - prev).coerceAtLeast(1e-6f)
        val t = ((target - prev) / span).coerceIn(0f, 1f)
        val a = points[seg - 1]
        val b = points[seg]
        out.add(InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
    }
    return out
}

private fun bounds(points: List<InkPoint>): Quad {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (p in points) {
        minX = min(minX, p.x); minY = min(minY, p.y)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y)
    }
    return Quad(minX, minY, (maxX - minX).coerceAtLeast(1f), (maxY - minY).coerceAtLeast(1f))
}

private data class Quad(val first: Float, val second: Float, val third: Float, val fourth: Float)

private fun buildTemplates(): List<Pair<String, List<InkPoint>>> {
    val raw = listOf(
        "0" to oval(),
        "1" to line(0.5f, 0.05f, 0.5f, 0.95f),
        "2" to poly(0.1f, 0.25f, 0.3f, 0.05f, 0.75f, 0.15f, 0.85f, 0.4f, 0.15f, 0.95f, 0.9f, 0.95f),
        "3" to poly(0.15f, 0.15f, 0.8f, 0.1f, 0.35f, 0.48f, 0.85f, 0.7f, 0.2f, 0.92f),
        "4" to poly(0.7f, 0.05f, 0.15f, 0.62f, 0.9f, 0.62f, 0.7f, 0.05f, 0.7f, 0.95f),
        "5" to poly(0.8f, 0.08f, 0.2f, 0.08f, 0.18f, 0.42f, 0.75f, 0.48f, 0.8f, 0.78f, 0.25f, 0.95f),
        "6" to poly(0.75f, 0.12f, 0.3f, 0.2f, 0.18f, 0.7f, 0.55f, 0.95f, 0.82f, 0.7f, 0.4f, 0.55f),
        "7" to poly(0.12f, 0.1f, 0.88f, 0.1f, 0.35f, 0.95f),
        "8" to listOf(oval(0.5f, 0.28f, 0.28f, 0.22f), oval(0.5f, 0.72f, 0.32f, 0.24f)).flatten(),
        "9" to poly(0.3f, 0.55f, 0.55f, 0.12f, 0.82f, 0.3f, 0.45f, 0.48f, 0.7f, 0.95f),
        "+" to (line(0.5f, 0.15f, 0.5f, 0.85f) + line(0.15f, 0.5f, 0.85f, 0.5f)),
        "-" to line(0.12f, 0.5f, 0.88f, 0.5f),
        "=" to (line(0.12f, 0.38f, 0.88f, 0.38f) + line(0.12f, 0.62f, 0.88f, 0.62f)),
        "x" to (line(0.15f, 0.15f, 0.85f, 0.85f) + line(0.85f, 0.15f, 0.15f, 0.85f)),
        "(" to poly(0.7f, 0.05f, 0.3f, 0.3f, 0.25f, 0.5f, 0.3f, 0.7f, 0.7f, 0.95f),
        ")" to poly(0.3f, 0.05f, 0.7f, 0.3f, 0.75f, 0.5f, 0.7f, 0.7f, 0.3f, 0.95f),
        "/" to line(0.75f, 0.05f, 0.25f, 0.95f),
    )
    return raw.map { (symbol, pts) -> symbol to normalize(pts) }
}

private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 24): List<InkPoint> =
    List(n) { i ->
        val t = i / (n - 1f)
        InkPoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
    }

private fun poly(vararg xy: Float): List<InkPoint> {
    val pts = ArrayList<InkPoint>()
    var i = 0
    while (i + 3 < xy.size) {
        pts += line(xy[i], xy[i + 1], xy[i + 2], xy[i + 3])
        i += 2
    }
    return pts
}

private fun oval(cx: Float = 0.5f, cy: Float = 0.5f, rx: Float = 0.32f, ry: Float = 0.42f): List<InkPoint> =
    List(48) { i ->
        val t = (Math.PI * 2 * i) / 47.0
        InkPoint((cx + rx * kotlin.math.cos(t)).toFloat(), (cy + ry * kotlin.math.sin(t)).toFloat())
    }

fun templateStroke(symbol: String): List<InkPoint>? {
    val recognizerField = buildRaw(symbol)
    return recognizerField
}

private fun buildRaw(symbol: String): List<InkPoint>? = when (symbol) {
    "1" -> line(10f, 10f, 10f, 90f)
    "0" -> oval(50f, 50f, 30f, 40f)
    "-" -> line(10f, 50f, 90f, 50f)
    "+" -> line(50f, 10f, 50f, 90f) + line(10f, 50f, 90f, 50f)
    else -> null
}

