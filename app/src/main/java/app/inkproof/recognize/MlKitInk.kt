package app.inkproof.recognize

import android.content.Context
import app.inkproof.core.model.Stroke
import app.inkproof.core.recognize.RecognitionDraft
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import java.util.concurrent.TimeUnit

/**
 * Optional text recognizer. ML Kit downloads a language model and does not
 * understand arbitrary integrals or matrices. Any failure returns null so the
 * caller stays on the on-device unistroke reader.
 */
class MlKitInk(context: Context) {
    @Suppress("unused")
    private val appContext = context.applicationContext

    fun recognize(strokes: List<Stroke>): RecognitionDraft? {
        if (strokes.isEmpty() || strokes.all { it.points.size < 2 }) return null
        return try {
            val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US") ?: return null
            val model = DigitalInkRecognitionModel.builder(identifier).build()
            val manager = RemoteModelManager.getInstance()
            val downloaded = Tasks.await(manager.isModelDownloaded(model), 3, TimeUnit.SECONDS)
            if (!downloaded) {
                manager.download(model, DownloadConditions.Builder().build())
                return null
            }
            val ink = Ink.builder().apply {
                for (stroke in strokes) {
                    if (stroke.points.isEmpty()) continue
                    val builder = Ink.Stroke.builder()
                    for (point in stroke.points) {
                        builder.addPoint(Ink.Point.create(point.x, point.y, point.t))
                    }
                    addStroke(builder.build())
                }
            }.build()
            val client = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
            try {
                val result = Tasks.await(client.recognize(ink), 8, TimeUnit.SECONDS)
                val text = result.candidates.firstOrNull()?.text?.trim().orEmpty()
                if (text.isBlank()) null else RecognitionDraft(text, 0.5f, "mlkit")
            } finally {
                client.close()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
