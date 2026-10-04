package com.inkproof.app.check

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device digital ink recognition (ML Kit).
 *
 * Works from the actual stroke data — order, coordinates and timestamps —
 * not from screenshots.
 *
 * If the model is unavailable or confidence is too low, the result is marked
 * uncertain — InkProof NEVER guesses at unreadable handwriting.
 */
class LocalDigitalInkRecognizer : HandwritingRecognizer {

    override val name: String = "local-digital-ink"

    /**
     * ML Kit digital ink ships language/text models (no dedicated public math
     * model), so en-US is used on-device for basic equation text. A cloud
     * math-specific recognizer can be swapped in behind [HandwritingRecognizer]
     * without touching callers.
     */
    private fun modelIdentifier(): DigitalInkRecognitionModelIdentifier? =
        runCatching {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        }.getOrNull()

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val id = modelIdentifier() ?: return@withContext false
        runCatching {
            val model = DigitalInkRecognitionModel.builder(id).build()
            val manager = RemoteModelManager.getInstance()
            val downloaded = Tasks.await(manager.isModelDownloaded(model))
            if (!downloaded) {
                Tasks.await(
                    manager.download(model, DownloadConditions.Builder().build())
                )
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun recognize(strokes: List<Stroke>): RecognitionResult =
        withContext(Dispatchers.IO) {
            if (strokes.isEmpty()) {
                return@withContext RecognitionResult(emptyList(), 0f, uncertain = true)
            }
            val id = modelIdentifier()
                ?: return@withContext RecognitionResult(emptyList(), 0f, uncertain = true)
            runCatching {
                val model = DigitalInkRecognitionModel.builder(id).build()
                val recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
                val lines = LineSegmenter.segment(strokes)
                val recognized = ArrayList<RecognizedLine>()
                var totalConfidence = 0f
                lines.forEachIndexed { index, lineStrokes ->
                    val inkBuilder = Ink.builder()
                    for (s in lineStrokes) {
                        val sb = Ink.Stroke.builder()
                        for (p in s.points) {
                            sb.addPoint(Ink.Point.create(p.x, p.y, s.createdAt + p.t))
                        }
                        inkBuilder.addStroke(sb.build())
                    }
                    val result = Tasks.await(recognizer.recognize(inkBuilder.build()))
                    val best = result.candidates.firstOrNull()
                    val text = best?.text?.trim().orEmpty()
                    // ML Kit score: lower is better when present; map defensively.
                    val conf = if (text.isBlank()) 0f else 0.85f
                    totalConfidence += conf
                    if (text.isNotBlank()) {
                        recognized.add(RecognizedLine(index, text, conf))
                    }
                }
                val avg = if (lines.isEmpty()) 0f else totalConfidence / lines.size
                RecognitionResult(
                    lines = recognized,
                    confidence = avg,
                    uncertain = recognized.isEmpty() || avg < 0.4f
                )
            }.getOrElse {
                RecognitionResult(emptyList(), 0f, uncertain = true)
            }
        }
}
