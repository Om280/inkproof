package com.inkproof.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Action requested by the user. CHECK and SOLVE are deliberately distinct. */
enum class CheckAction { CHECK, SOLVE }

/** Overall outcome of a check. */
@Serializable
enum class CheckStatus {
    @SerialName("correct") CORRECT,
    @SerialName("incorrect") INCORRECT,
    @SerialName("incomplete") INCOMPLETE,
    @SerialName("unclear") UNCLEAR,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("error") ERROR
}

/** Status of one step inside a solution. */
@Serializable
enum class StepStatus {
    @SerialName("correct") CORRECT,
    @SerialName("incorrect") INCORRECT,
    @SerialName("dependent_on_previous_error") DEPENDENT_ON_PREVIOUS_ERROR,
    @SerialName("unclear") UNCLEAR,
    @SerialName("skipped") SKIPPED
}

/** One recognized line of the student's handwriting, sent to the backend. */
@Serializable
data class RecognizedLine(
    @SerialName("line_index") val lineIndex: Int,
    val text: String,
    val confidence: Float
)

/**
 * The payload for a CHECK / SOLVE call.
 *
 * Isolation guarantee: a request only ever carries ONE question and the
 * solution lines recognized from that question's solution region. It never
 * contains other questions, other pages, or page screenshots.
 */
@Serializable
data class CheckRequest(
    @SerialName("request_id") val requestId: String,
    val action: String,
    @SerialName("question_id") val questionId: String,
    @SerialName("question_text") val questionText: String,
    @SerialName("question_source") val questionSource: String,
    @SerialName("question_confidence") val questionConfidence: Float,
    @SerialName("solution_lines") val solutionLines: List<RecognizedLine>,
    @SerialName("content_version") val contentVersion: Long,
    @SerialName("solution_version") val solutionVersion: Long
)

@Serializable
data class CheckStep(
    @SerialName("step_id") val stepId: String,
    val status: StepStatus,
    val expression: String = "",
    val explanation: String? = null,
    val hint: String? = null
)

/**
 * Structured result of CHECK MY WORK / SOLVE.
 * The app owns the UI; the AI only supplies this structured data.
 */
@Serializable
data class CheckResponse(
    val status: CheckStatus,
    val confidence: Float = 0f,
    @SerialName("question_echo") val questionEcho: String? = null,
    @SerialName("first_error_step") val firstErrorStep: String? = null,
    val steps: List<CheckStep> = emptyList(),
    /** Progressive hints: general -> specific -> very specific. */
    val hints: List<String> = emptyList(),
    @SerialName("final_answer") val finalAnswer: String? = null,
    @SerialName("full_solution") val fullSolution: String? = null,
    /** Human-readable summary shown in the result header. */
    val summary: String? = null,
    /** Set when status is ERROR / UNCLEAR / UNSUPPORTED. */
    val message: String? = null,
    /** How the math was verified: "symbolic", "ai", "mock". */
    @SerialName("verified_by") val verifiedBy: String? = null
)
