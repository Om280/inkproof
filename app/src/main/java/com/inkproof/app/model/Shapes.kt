package com.inkproof.app.model

/** Shape types supported by hold-to-snap shape recognition. */
enum class ShapeType {
    LINE,
    ARROW,
    CIRCLE,
    ELLIPSE,
    RECTANGLE,
    SQUARE,
    TRIANGLE,
    POLYGON
}

/**
 * Result of geometric shape detection on a raw ink stroke.
 * [points] are the cleaned vector points of the snapped shape, in page space.
 */
data class DetectedShape(
    val type: ShapeType,
    val points: List<StrokePoint>,
    val confidence: Float
)
