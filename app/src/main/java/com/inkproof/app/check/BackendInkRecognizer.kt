package com.inkproof.app.check

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Cloud fallback recognizer: renders the SELECTED strokes (never a whole
 * page, never a screenshot of the screen) to a small black-on-white PNG
 * and asks the InkProof backend to transcribe it (Gemini multimodal).
 *
 * Only ever invoked by [HybridRecognizer] after on-device recognition came
 * back uncertain, which itself only happens during an explicit user action
 * (RECOGNIZE / CHECK MY WORK). No background calls, no secrets on-device.
 */
class BackendInkRecognizer(
    private val baseUrl: String,
    client: OkHttpClient? = null
) : HandwritingRecognizer {

    override val name: String = "backend-ink"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun isAvailable(): Boolean = baseUrl.isNotBlank()

    override suspend fun recognize(strokes: List<Stroke>): RecognitionResult =
        withContext(Dispatchers.IO) {
            if (strokes.isEmpty() || baseUrl.isBlank()) {
                return@withContext RecognitionResult(emptyList(), 0f, uncertain = true)
            }
            runCatching {
                val png = renderStrokes(strokes)
                    ?: return@withContext RecognitionResult(emptyList(), 0f, uncertain = true)
                val payload = json.encodeToString(
                    RecognizeRequest.serializer(),
                    RecognizeRequest(
                        image_base64 = Base64.encodeToString(png, Base64.NO_WRAP),
                        mime = "image/png"
                    )
                )
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/api/recognize")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use RecognitionResult(emptyList(), 0f, uncertain = true)
                    }
                    val parsed = json.decodeFromString(RecognizeResponse.serializer(), text)
                    if (parsed.status != "ok" || parsed.lines.isEmpty()) {
                        return@use RecognitionResult(emptyList(), 0f, uncertain = true)
                    }
                    RecognitionResult(
                        lines = parsed.lines.mapIndexed { i, l ->
                            RecognizedLine(
                                lineIndex = if (l.index >= 0) l.index else i,
                                text = MathNormalizer.normalize(l.text),
                                confidence = l.confidence.coerceIn(0f, 1f)
                            )
                        },
                        confidence = parsed.confidence.coerceIn(0f, 1f),
                        uncertain = parsed.uncertain
                    )
                }
            }.getOrElse { RecognitionResult(emptyList(), 0f, uncertain = true) }
        }

    /** Black ink on white, padded and scaled so the longest side is ~1024px. */
    private fun renderStrokes(strokes: List<Stroke>): ByteArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) for (p in s.points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        if (maxX <= minX || maxY <= minY) return null
        val w = maxX - minX
        val h = maxY - minY
        val scale = (1024f / maxOf(w, h)).coerceIn(0.25f, 6f)
        val pad = 24
        val bw = (w * scale).toInt() + pad * 2
        val bh = (h * scale).toInt() + pad * 2
        if (bw <= 0 || bh <= 0 || bw > 4096 || bh > 4096) return null

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            paint.strokeWidth = (s.baseWidth * scale).coerceIn(2f, 24f)
            val path = Path()
            s.points.forEachIndexed { i, p ->
                val x = (p.x - minX) * scale + pad
                val y = (p.y - minY) * scale + pad
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Serializable
    private data class RecognizeRequest(val image_base64: String, val mime: String)

    @Serializable
    private data class RecognizeResponse(
        val status: String = "error",
        val lines: List<RecognizeLine> = emptyList(),
        val confidence: Float = 0f,
        val uncertain: Boolean = true
    )

    @Serializable
    private data class RecognizeLine(
        val index: Int = -1,
        val text: String = "",
        val confidence: Float = 0.8f
    )
}

/**
 * On-device first; cloud only as an explicit-action fallback.
 *
 * - If the local result is confident, the cloud is NEVER contacted.
 * - If the local result is uncertain and a backend is configured, one
 *   cloud attempt is made; a confident cloud reading wins, otherwise the
 *   honest uncertain local result is returned (InkProof never guesses).
 */
class HybridRecognizer(
    private val primary: HandwritingRecognizer,
    private val fallback: HandwritingRecognizer?
) : HandwritingRecognizer {

    override val name: String =
        "hybrid(${primary.name}+${fallback?.name ?: "none"})"

    override suspend fun isAvailable(): Boolean =
        primary.isAvailable() || fallback?.isAvailable() == true

    override suspend fun recognize(strokes: List<Stroke>): RecognitionResult {
        val local = runCatching { primary.recognize(strokes) }
            .getOrElse { RecognitionResult(emptyList(), 0f, uncertain = true) }
        if (!local.uncertain) return local
        val cloud = fallback ?: return local
        if (!cloud.isAvailable()) return local
        val remote = runCatching { cloud.recognize(strokes) }.getOrNull() ?: return local
        return if (!remote.uncertain && remote.lines.isNotEmpty()) remote else local
    }
}
