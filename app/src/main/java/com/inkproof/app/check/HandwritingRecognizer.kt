package com.inkproof.app.check

import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.Stroke

/** Result of recognizing a set of ink strokes. */
data class RecognitionResult(
    val lines: List<RecognizedLine>,
    /** Overall confidence 0..1. */
    val confidence: Float,
    /** True when the recognizer could not produce a confident reading. */
    val uncertain: Boolean = false
)

/**
 * Replaceable handwriting recognition abstraction.
 *
 * Implementations receive ONLY the strokes of the region being recognized
 * (one question's solution, one question's handwritten statement, or one
 * lasso selection) — never a whole page and never a screenshot.
 */
interface HandwritingRecognizer {
    /** Human-readable implementation name, e.g. "local-digital-ink". */
    val name: String

    suspend fun isAvailable(): Boolean

    /**
     * Recognize math handwriting from raw strokes. Strokes keep original
     * order, coordinates, pressure and timestamps.
     */
    suspend fun recognize(strokes: List<Stroke>): RecognitionResult
}

/**
 * Groups strokes into visual lines by vertical position, so recognition and
 * step-checking can work line by line (step by step).
 */
object LineSegmenter {
    fun segment(strokes: List<Stroke>): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()
        val sorted = strokes.sortedBy { it.bounds().centerY }
        val heights = sorted.map { it.bounds().height }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(8f)
        val gapThreshold = medianHeight * 0.9f

        val lines = ArrayList<MutableList<Stroke>>()
        var current = mutableListOf(sorted.first())
        var currentBottom = sorted.first().bounds().bottom
        for (s in sorted.drop(1)) {
            val b = s.bounds()
            if (b.top > currentBottom + gapThreshold) {
                lines.add(current)
                current = mutableListOf(s)
            } else {
                current.add(s)
            }
            currentBottom = maxOf(currentBottom, b.bottom)
        }
        lines.add(current)
        // Within a line, order strokes left-to-right by writing position.
        return lines.map { line -> line.sortedBy { it.bounds().left } }
    }
}
