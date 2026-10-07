package com.inkproof.app.check

import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.Stroke

/**
 * Development-only recognizer. It does NOT invent mathematics: it reports
 * each detected handwriting line as unrecognized placeholder text with the
 * stroke counts, so mock flows exercise the UI without pretending to read ink.
 *
 * Mock data is isolated: this class is only wired up when mock mode is on.
 */
class MockRecognizer : HandwritingRecognizer {

    override val name: String = "mock-recognizer"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun recognize(strokes: List<Stroke>): RecognitionResult {
        if (strokes.isEmpty()) return RecognitionResult(emptyList(), 0f, uncertain = true)
        val lines = LineSegmenter.segment(strokes)
        val recognized = lines.mapIndexed { index, line ->
            RecognizedLine(index, "[handwritten line ${index + 1}: ${line.size} strokes]", 0.99f)
        }
        return RecognitionResult(recognized, 0.99f, uncertain = false)
    }
}
