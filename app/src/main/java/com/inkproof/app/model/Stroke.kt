package com.inkproof.app.model

/**
 * A single sampled point on a stroke.
 *
 * Coordinates are in page space (document units), independent of zoom/pan,
 * so ink stays sharp at any zoom level.
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    /** Milliseconds since the start of the stroke; useful for recognition. */
    val t: Long
)

/** Which logical region of a page a stroke belongs to. */
enum class StrokeRole {
    /** Freeform content not bound to a question. */
    FREEFORM,
    /** Ink that is part of a question statement. */
    QUESTION,
    /** Ink that is part of a student solution. */
    SOLUTION
}

/**
 * The persistent, editable vector stroke model.
 * Strokes are never flattened into bitmaps in the document model.
 */
data class Stroke(
    val id: String = newId(),
    val pageId: String,
    /** Non-null when the stroke belongs to a Question object. */
    val questionId: String? = null,
    val role: StrokeRole = StrokeRole.FREEFORM,
    val tool: ToolType = ToolType.PEN,
    val color: Int,
    val baseWidth: Float,
    val points: List<StrokePoint>,
    val createdAt: Long = System.currentTimeMillis(),
    /** Set when this stroke is a snapped vector shape. */
    val shapeType: ShapeType? = null
) {
    val isHighlighter: Boolean get() = tool == ToolType.HIGHLIGHTER

    fun bounds(): Bounds {
        if (points.isEmpty()) return Bounds(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    fun translated(dx: Float, dy: Float): Stroke =
        copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
}

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: Bounds): Bounds = Bounds(
        minOf(left, other.left), minOf(top, other.top),
        maxOf(right, other.right), maxOf(bottom, other.bottom)
    )

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun intersects(other: Bounds): Boolean =
        left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom
}
