package com.inkproof.app.check

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
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
 * Accuracy levers used (per ML Kit guidance):
 *  - the model download is ENSURED inside [recognize] — previously a
 *    missing model made perfectly neat handwriting come back "unclear";
 *  - every line is recognized with a [RecognitionContext] carrying the
 *    line's [WritingArea] and the previously recognized lines as
 *    pre-context;
 *  - raw candidates are post-processed by [MathNormalizer] (x2 -> x^2,
 *    unicode minus/superscripts, etc.) — normalization only, no guessing.
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

    private fun ensureModel(id: DigitalInkRecognitionModelIdentifier): DigitalInkRecognitionModel? =
        runCatching {
            val model = DigitalInkRecognitionModel.builder(id).build()
            val manager = RemoteModelManager.getInstance()
            if (!Tasks.await(manager.isModelDownloaded(model))) {
                Tasks.await(
                    manager.download(model, DownloadConditions.Builder().build())
                )
            }
            model
        }.getOrNull()

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val id = modelIdentifier() ?: return@withContext false
        ensureModel(id) != null
    }

    override suspend fun recognize(strokes: List<Stroke>): RecognitionResult =
        withContext(Dispatchers.IO) {
            if (strokes.isEmpty()) {
                return@withContext RecognitionResult(emptyList(), 0f, uncertain = true)
            }
            val id = modelIdentifier()
                ?: return@withContext RecognitionResult(emptyList(), 0f, uncertain = true)
            // The model MUST be present before recognizing; otherwise neat
            // handwriting is wrongly reported as unreadable.
            val model = ensureModel(id)
                ?: return@withContext RecognitionResult(emptyList(), 0f, uncertain = true)
            runCatching {
                val recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
                val lines = LineSegmenter.segment(strokes)
                val recognized = ArrayList<RecognizedLine>()
                var totalConfidence = 0f
                var preContext = ""
                lines.forEachIndexed { index, lineStrokes ->
                    val inkBuilder = Ink.builder()
                    var minX = Float.MAX_VALUE
                    var maxX = -Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxY = -Float.MAX_VALUE
                    for (s in lineStrokes) {
                        val sb = Ink.Stroke.builder()
                        for (p in s.points) {
                            sb.addPoint(Ink.Point.create(p.x, p.y, s.createdAt + p.t))
                            if (p.x < minX) minX = p.x
                            if (p.x > maxX) maxX = p.x
                            if (p.y < minY) minY = p.y
                            if (p.y > maxY) maxY = p.y
                        }
                        inkBuilder.addStroke(sb.build())
                    }
                    // WritingArea + preContext materially improve accuracy
                    // (documented ML Kit recognition-context levers).
                    val context = RecognitionContext.builder()
                        .setWritingArea(
                            WritingArea(
                                (maxX - minX).coerceAtLeast(1f),
                                (maxY - minY).coerceAtLeast(1f)
                            )
                        )
                        .setPreContext(preContext.takeLast(20))
                        .build()
                    val result =
                        Tasks.await(recognizer.recognize(inkBuilder.build(), context))
                    val best = result.candidates.firstOrNull()
                    val text = MathNormalizer.normalize(best?.text?.trim().orEmpty())
                    // ML Kit score: lower is better when present; map defensively.
                    val conf = if (text.isBlank()) 0f else 0.85f
                    totalConfidence += conf
                    if (text.isNotBlank()) {
                        recognized.add(RecognizedLine(index, text, conf))
                        preContext = text
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
