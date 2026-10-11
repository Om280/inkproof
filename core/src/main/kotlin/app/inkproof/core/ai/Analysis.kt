package app.inkproof.core.ai

import app.inkproof.core.math.FirstError
import app.inkproof.core.math.LocalCheck
import app.inkproof.core.math.StepAssessment
import app.inkproof.core.math.checkWork
import app.inkproof.core.math.solveProblem
import app.inkproof.core.model.ANALYSIS_SCHEMA_VERSION
import app.inkproof.core.model.AiMode
import app.inkproof.core.model.ImageItem
import app.inkproof.core.model.InkRegion
import app.inkproof.core.model.Page
import app.inkproof.core.model.Question
import app.inkproof.core.model.ShapeItem
import app.inkproof.core.model.Stroke
import app.inkproof.core.model.TextItem
import app.inkproof.core.model.questionText
import app.inkproof.core.model.solutionText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val PayloadJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class StrokePayload(
    val id: String,
    val region: String,
    val color: Long,
    val tool: String,
    val points: List<List<Float>>,
)

@Serializable
data class CheckRequest(
    val schemaVersion: Int = ANALYSIS_SCHEMA_VERSION,
    val action: String = "check",
    val notebookId: String,
    val pageId: String,
    val questionId: String,
    val promptText: String,
    val solutionText: String,
    val solutionSteps: List<String>,
    val promptVersion: Int,
    val solutionVersion: Int,
    val strokes: List<StrokePayload>,
)

@Serializable
data class SolveRequest(
    val schemaVersion: Int = ANALYSIS_SCHEMA_VERSION,
    val action: String = "solve",
    val notebookId: String,
    val pageId: String,
    val questionId: String,
    val promptText: String,
    val promptVersion: Int,
    val solutionStrokeIds: List<String> = emptyList(),
    val strokes: List<StrokePayload> = emptyList(),
)

@Serializable
data class StepDto(
    val stepIndex: Int,
    val text: String = "",
    val verdict: String = "unclear",
    val note: String = "",
)

@Serializable
data class ErrorDto(
    val stepIndex: Int,
    val explanation: String,
)

@Serializable
data class AnalysisResponse(
    val schemaVersion: Int = ANALYSIS_SCHEMA_VERSION,
    val status: String,
    val confidence: Double = 0.0,
    val provider: String,
    val mock: Boolean = false,
    val model: String? = null,
    val recognizedQuestion: String = "",
    val recognizedSteps: List<String> = emptyList(),
    val stepAssessments: List<StepDto> = emptyList(),
    val firstError: ErrorDto? = null,
    val explanation: String = "",
    val hints: List<String> = emptyList(),
    val finalAnswer: String? = null,
    val fullSolution: List<String> = emptyList(),
    val errorCode: String? = null,
    val message: String? = null,
    val questionId: String = "",
    val action: String = "",
    val promptVersion: Int = 0,
    val solutionVersion: Int = 0,
)

val ALLOWED_STATUS = setOf("correct", "incorrect", "incomplete", "unclear", "unsupported", "error")

fun buildCheckRequest(
    notebookId: String,
    page: Page,
    questionId: String,
    includeStrokeIds: Set<String> = emptySet(),
): CheckRequest {
    val q = page.questions.firstOrNull { it.id == questionId }
        ?: throw IllegalArgumentException("Question $questionId is not on page ${page.id}.")
    val strokes = page.objects.filterIsInstance<Stroke>().filter { stroke ->
        stroke.questionId == q.id || (stroke.id in includeStrokeIds && stroke.questionId == null)
    }
    return CheckRequest(
        notebookId = notebookId,
        pageId = page.id,
        questionId = q.id,
        promptText = questionText(q),
        solutionText = solutionText(q),
        solutionSteps = stepsFrom(solutionText(q)),
        promptVersion = q.promptVersion,
        solutionVersion = q.solutionVersion,
        strokes = strokes.map { it.toPayload() },
    )
}

fun buildSolveRequest(notebookId: String, page: Page, questionId: String): SolveRequest {
    val q = page.questions.firstOrNull { it.id == questionId }
        ?: throw IllegalArgumentException("Question $questionId is not on page ${page.id}.")
    val promptStrokes = page.objects.filterIsInstance<Stroke>()
        .filter { it.questionId == q.id && it.region == InkRegion.PROMPT }
    return SolveRequest(
        notebookId = notebookId,
        pageId = page.id,
        questionId = q.id,
        promptText = questionText(q),
        promptVersion = q.promptVersion,
        solutionStrokeIds = emptyList(),
        strokes = promptStrokes.map { it.toPayload() },
    )
}

fun stepsFrom(text: String): List<String> =
    text.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }

private fun Stroke.toPayload(): StrokePayload {
    val simplified = if (points.size <= 64) points else {
        val step = points.size / 64
        points.filterIndexed { index, _ -> index % step == 0 || index == points.lastIndex }
    }
    return StrokePayload(
        id = id,
        region = region.name.lowercase(),
        color = color,
        tool = tool.name.lowercase(),
        points = simplified.map { listOf(it.x, it.y) },
    )
}

fun encodeRequest(request: CheckRequest): String = PayloadJson.encodeToString(request)

fun encodeSolve(request: SolveRequest): String = PayloadJson.encodeToString(request)

data class ReviewPanel(
    val response: AnalysisResponse,
    val hintsShown: Int = 1,
    val solutionRevealed: Boolean = false,
) {
    fun visibleAnswer(): String? = if (solutionRevealed) response.finalAnswer else null
    fun visibleSolution(): List<String> = if (solutionRevealed) response.fullSolution else emptyList()
    fun visibleHints(): List<String> = response.hints.take(hintsShown.coerceAtLeast(0))
    fun withAnotherHint(): ReviewPanel =
        copy(hintsShown = (hintsShown + 1).coerceAtMost(response.hints.size.coerceAtLeast(hintsShown)))
    fun reveal(): ReviewPanel = copy(solutionRevealed = true)
}

class AnalysisCache {
    private val map = HashMap<String, AnalysisResponse>()

    fun key(mode: AiMode, action: String, questionId: String, promptVersion: Int, solutionVersion: Int): String =
        "$mode|$action|$questionId|$promptVersion|$solutionVersion"

    fun get(key: String): AnalysisResponse? = map[key]
    fun put(key: String, response: AnalysisResponse) {
        if (response.status != "error") map[key] = response
    }
    fun clear() = map.clear()
}

data class TransportResult(
    val httpStatus: Int,
    val body: String,
    val failure: String? = null,
)

interface AnalysisTransport {
    fun check(request: CheckRequest): TransportResult
    fun solve(request: SolveRequest): TransportResult
    fun health(): TransportResult
}

class RecordingTransport : AnalysisTransport {
    val checks = ArrayList<CheckRequest>()
    val solves = ArrayList<SolveRequest>()
    var fail = false
    var body = """{"schemaVersion":1,"status":"unclear","provider":"gemini","mock":false,"explanation":"remote"}"""
    var httpStatus = 200

    override fun check(request: CheckRequest): TransportResult {
        checks.add(request)
        if (fail) return TransportResult(0, "", "NETWORK")
        return TransportResult(httpStatus, body)
    }

    override fun solve(request: SolveRequest): TransportResult {
        solves.add(request)
        if (fail) return TransportResult(0, "", "NETWORK")
        return TransportResult(httpStatus, body)
    }

    override fun health(): TransportResult = TransportResult(200, """{"ok":true,"configured":false}""")
}

object MockAnalyzer {
    fun check(request: CheckRequest): AnalysisResponse = AnalysisResponse(
        schemaVersion = ANALYSIS_SCHEMA_VERSION,
        status = "unclear",
        confidence = 0.0,
        provider = "mock",
        mock = true,
        recognizedQuestion = request.promptText,
        recognizedSteps = request.solutionSteps,
        explanation = "Mock response. This is not a real check of ${request.questionId}.",
        hints = listOf("Mock hint only."),
        finalAnswer = null,
        fullSolution = emptyList(),
        questionId = request.questionId,
        action = "check",
        promptVersion = request.promptVersion,
        solutionVersion = request.solutionVersion,
        message = "MOCK",
    )

    fun solve(request: SolveRequest): AnalysisResponse = AnalysisResponse(
        schemaVersion = ANALYSIS_SCHEMA_VERSION,
        status = "unsupported",
        confidence = 0.0,
        provider = "mock",
        mock = true,
        recognizedQuestion = request.promptText,
        explanation = "Mock solve. This is not a real solution of ${request.questionId}.",
        questionId = request.questionId,
        action = "solve",
        promptVersion = request.promptVersion,
        message = "MOCK",
    )
}

class AnalysisPipeline(
    private val transport: AnalysisTransport,
    private val cache: AnalysisCache = AnalysisCache(),
    private val mode: () -> AiMode,
) {
    fun check(request: CheckRequest): AnalysisResponse {
        val key = cache.key(mode(), "check", request.questionId, request.promptVersion, request.solutionVersion)
        cache.get(key)?.let { return it }
        val local = runLocalCheck(request)
        val response = when (mode()) {
            AiMode.MOCK -> MockAnalyzer.check(request)
            AiMode.OFF -> local ?: notConfigured(request, "check")
            AiMode.REAL -> merge(local, callRemote(request), request, "check")
        }
        cache.put(key, response)
        return response
    }

    fun solve(request: SolveRequest): AnalysisResponse {
        val key = cache.key(mode(), "solve", request.questionId, request.promptVersion, 0)
        cache.get(key)?.let { return it }
        val local = solveProblem(request.promptText)?.let { solved ->
            AnalysisResponse(
                status = "correct",
                confidence = 0.95,
                provider = "on_device",
                mock = false,
                recognizedQuestion = request.promptText,
                recognizedSteps = solved.steps,
                explanation = "Solved on-device (${solved.problemType}).",
                finalAnswer = solved.finalAnswer,
                fullSolution = solved.steps,
                questionId = request.questionId,
                action = "solve",
                promptVersion = request.promptVersion,
            )
        }
        val response = when (mode()) {
            AiMode.MOCK -> MockAnalyzer.solve(request)
            AiMode.OFF -> local ?: notConfiguredSolve(request)
            AiMode.REAL -> {
                if (local != null) {
                    val remote = callRemoteSolve(request)
                    if (remote != null && remote.status != "error" && !remote.mock) {
                        local.copy(
                            explanation = remote.explanation.ifBlank { local.explanation },
                            hints = remote.hints.ifEmpty { local.hints },
                            provider = "on_device+gemini",
                            model = remote.model,
                        )
                    } else {
                        local.copy(
                            message = remote?.message ?: remote?.errorCode,
                        )
                    }
                } else {
                    callRemoteSolve(request) ?: notConfiguredSolve(request).copy(
                        errorCode = "UPSTREAM",
                        message = "The solver could not handle this question and the AI request failed.",
                        status = "error",
                    )
                }
            }
        }
        cache.put(key, response)
        return response
    }

    private fun runLocalCheck(request: CheckRequest): AnalysisResponse? {
        val local = checkWork(request.promptText, request.solutionSteps.ifEmpty { stepsFrom(request.solutionText) })
            ?: return null
        return local.toResponse(request)
    }

    private fun callRemote(request: CheckRequest): AnalysisResponse? {
        val result = try {
            transport.check(request)
        } catch (e: Exception) {
            return errorResponse(request, "check", "NETWORK", e.message ?: "Network failure")
        }
        return interpret(result, request.questionId, "check", request.promptVersion, request.solutionVersion)
    }

    private fun callRemoteSolve(request: SolveRequest): AnalysisResponse? {
        val result = try {
            transport.solve(request)
        } catch (e: Exception) {
            return errorResponseSolve(request, "NETWORK", e.message ?: "Network failure")
        }
        return interpret(result, request.questionId, "solve", request.promptVersion, 0)
    }

    private fun merge(
        local: AnalysisResponse?,
        remote: AnalysisResponse?,
        request: CheckRequest,
        action: String,
    ): AnalysisResponse {
        if (remote?.mock == true) {
            return errorResponse(request, action, "INVALID_RESPONSE", "Real mode received a mock payload.")
        }
        if (local == null && (remote == null || remote.status == "error")) {
            return remote ?: errorResponse(request, action, "UPSTREAM", "No checker result.")
        }
        if (local == null) return remote!!
        if (remote == null || remote.status == "error") {
            return local.copy(
                message = listOfNotNull(local.message, remote?.message, remote?.errorCode, "AI explanation unavailable.").joinToString(" "),
            )
        }
        if (remote.status != local.status && local.status in setOf("correct", "incorrect", "incomplete")) {
            return local.copy(
                explanation = local.explanation + " Cloud status was ${remote.status} and was not used as the verdict.",
                provider = "on_device",
                model = remote.model,
            )
        }
        return local.copy(
            explanation = remote.explanation.ifBlank { local.explanation },
            hints = remote.hints.ifEmpty { local.hints },
            provider = "on_device+gemini",
            model = remote.model,
            finalAnswer = local.finalAnswer ?: remote.finalAnswer,
            fullSolution = local.fullSolution.ifEmpty { remote.fullSolution },
        )
    }
}

fun interpret(
    result: TransportResult,
    questionId: String,
    action: String,
    promptVersion: Int,
    solutionVersion: Int,
): AnalysisResponse {
    if (result.failure != null) {
        return errorBare(questionId, action, result.failure, result.failureMessage(), promptVersion, solutionVersion)
    }
    if (result.httpStatus !in 200..299) {
        val parsed = parseAnalysis(result.body)
        if (parsed != null && parsed.status == "error") {
            return parsed.copy(mock = false, questionId = parsed.questionId.ifBlank { questionId }, action = action)
        }
        val code = when (result.httpStatus) {
            401, 403 -> "INVALID_API_KEY"
            429 -> "RATE_LIMIT"
            404 -> "MODEL_ERROR"
            503 -> "MISSING_API_KEY"
            else -> "UPSTREAM"
        }
        return errorBare(questionId, action, code, result.body.take(300).ifBlank { "HTTP ${result.httpStatus}" }, promptVersion, solutionVersion)
    }
    val parsed = parseAnalysis(result.body)
        ?: return errorBare(questionId, action, "INVALID_RESPONSE", "The provider returned invalid JSON.", promptVersion, solutionVersion)
    if (parsed.status !in ALLOWED_STATUS) {
        return errorBare(questionId, action, "INVALID_RESPONSE", "Unknown status.", promptVersion, solutionVersion)
    }
    if (parsed.mock || parsed.provider == "mock") {
        return errorBare(questionId, action, "INVALID_RESPONSE", "Provider marked the payload as mock.", promptVersion, solutionVersion)
    }
    return parsed.copy(
        provider = if (parsed.provider.isBlank()) "gemini" else parsed.provider,
        mock = false,
        questionId = parsed.questionId.ifBlank { questionId },
        action = action,
        promptVersion = promptVersion,
        solutionVersion = solutionVersion,
    )
}

private fun TransportResult.failureMessage(): String = when (failure) {
    "TIMEOUT" -> "The request timed out."
    "NETWORK" -> "The backend could not be reached."
    else -> failure ?: "Request failed."
}

fun parseAnalysis(body: String): AnalysisResponse? {
    if (body.isBlank()) return null
    return try {
        val parsed = PayloadJson.decodeFromString(AnalysisResponse.serializer(), body)
        if (parsed.schemaVersion != ANALYSIS_SCHEMA_VERSION) return null
        if (parsed.status !in ALLOWED_STATUS) return null
        parsed
    } catch (_: Exception) {
        null
    }
}

private fun LocalCheck.toResponse(request: CheckRequest): AnalysisResponse = AnalysisResponse(
    schemaVersion = ANALYSIS_SCHEMA_VERSION,
    status = status,
    confidence = confidence,
    provider = "on_device",
    mock = false,
    recognizedQuestion = recognizedQuestion,
    recognizedSteps = steps.map { it.text },
    stepAssessments = steps.map { StepDto(it.stepIndex, it.text, it.verdict, it.note) },
    firstError = firstError?.let { ErrorDto(it.stepIndex, it.explanation) },
    explanation = explanation,
    hints = hints,
    finalAnswer = finalAnswer,
    fullSolution = fullSolution,
    questionId = request.questionId,
    action = "check",
    promptVersion = request.promptVersion,
    solutionVersion = request.solutionVersion,
)

private fun notConfigured(request: CheckRequest, action: String): AnalysisResponse = errorResponse(
    request,
    action,
    "UNSUPPORTED",
    "This question is not covered by the on-device checker, and AI mode is off.",
).copy(status = "unsupported")

private fun notConfiguredSolve(request: SolveRequest): AnalysisResponse = AnalysisResponse(
    status = "unsupported",
    provider = "on_device",
    mock = false,
    explanation = "On-device solve does not cover this question, and AI mode is off.",
    errorCode = "UNSUPPORTED",
    questionId = request.questionId,
    action = "solve",
    promptVersion = request.promptVersion,
    recognizedQuestion = request.promptText,
)

private fun errorResponse(request: CheckRequest, action: String, code: String, message: String): AnalysisResponse =
    errorBare(request.questionId, action, code, message, request.promptVersion, request.solutionVersion)

private fun errorResponseSolve(request: SolveRequest, code: String, message: String): AnalysisResponse =
    errorBare(request.questionId, "solve", code, message, request.promptVersion, 0)

private fun errorBare(
    questionId: String,
    action: String,
    code: String,
    message: String,
    promptVersion: Int,
    solutionVersion: Int,
): AnalysisResponse = AnalysisResponse(
    schemaVersion = ANALYSIS_SCHEMA_VERSION,
    status = "error",
    confidence = 0.0,
    provider = "backend",
    mock = false,
    explanation = "",
    errorCode = code,
    message = message.take(500),
    questionId = questionId,
    action = action,
    promptVersion = promptVersion,
    solutionVersion = solutionVersion,
)

sealed class CheckDecision {
    data class Run(val prompt: String, val solution: String) : CheckDecision()
    data class Confirm(val prompt: String, val solution: String, val confidence: Float, val reason: String) : CheckDecision()
    data class Blocked(val reason: String) : CheckDecision()
}

fun decideCheck(
    question: Question,
    recognizedPrompt: String?,
    recognizedSolution: String?,
    recognitionConfidence: Float,
): CheckDecision {
    val prompt = question.promptText.trim().ifBlank { recognizedPrompt?.trim().orEmpty() }
    val solution = question.recognizedSolution?.trim().orEmpty().ifBlank { recognizedSolution?.trim().orEmpty() }
    if (prompt.isBlank()) {
        return CheckDecision.Blocked("Type or confirm the question before checking. InkProof will not invent it.")
    }
    if (solution.isBlank()) {
        return CheckDecision.Blocked("The solution could not be read. Type it or rewrite the step, then check again.")
    }
    val promptWasTyped = question.promptText.isNotBlank()
    val confidence = if (promptWasTyped && question.recognizedSolution != null) {
        question.solutionConfidence
    } else {
        recognitionConfidence
    }
    if (!promptWasTyped && question.promptConfidence < 0.55f && recognizedPrompt != null) {
        return CheckDecision.Confirm(prompt, solution, question.promptConfidence, "Confirm the question reading before checking.")
    }
    if (confidence < 0.55f) {
        return CheckDecision.Confirm(prompt, solution, confidence, "The solution reading is uncertain. Confirm or edit it before checking.")
    }
    return CheckDecision.Run(prompt, solution)
}

fun foreignTextInCheck(request: CheckRequest, secret: String): Boolean {
    if (secret.isBlank()) return false
    val blob = encodeRequest(request)
    return blob.contains(secret)
}
