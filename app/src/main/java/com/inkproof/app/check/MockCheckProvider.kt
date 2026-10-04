package com.inkproof.app.check

import com.inkproof.app.model.CheckRequest
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.CheckStatus
import com.inkproof.app.model.CheckStep
import com.inkproof.app.model.StepStatus
import kotlinx.coroutines.delay

/**
 * Deterministic development provider so the full CHECK MY WORK UX can be
 * exercised with no API credentials.
 *
 * Isolation rules:
 *  - It only ever uses the question/solution INSIDE the request it receives.
 *    No hardcoded demo question is ever substituted for the user's problem.
 *  - The scenario is selected from the request content, so the same input
 *    always produces the same output (good for tests).
 */
class MockCheckProvider(
    private val simulatedLatencyMs: Long = 600
) : CheckProvider {

    override val name: String = "mock"

    override suspend fun check(request: CheckRequest): CheckResponse {
        delay(simulatedLatencyMs)

        if (request.questionText.isBlank()) {
            return CheckResponse(
                status = CheckStatus.ERROR,
                message = "This question has no content yet. Add the problem before checking."
            )
        }
        if (request.solutionLines.isEmpty()) {
            return CheckResponse(
                status = CheckStatus.INCOMPLETE,
                confidence = 1f,
                questionEcho = request.questionText,
                summary = "No solution steps found.",
                message = "Write your solution in the solution area, then check again.",
                verifiedBy = "mock"
            )
        }

        // Deterministic scenario from the actual submitted content.
        val scenario = scenarioFor(request)
        val steps = request.solutionLines.mapIndexed { i, line ->
            CheckStep(
                stepId = "step_${i + 1}",
                status = StepStatus.CORRECT,
                expression = line.text,
                explanation = "This step follows from the previous line."
            )
        }

        return when (scenario) {
            Scenario.CORRECT -> CheckResponse(
                status = CheckStatus.CORRECT,
                confidence = 0.97f,
                questionEcho = request.questionText,
                steps = steps,
                summary = "Every step checks out. Nicely done.",
                finalAnswer = steps.lastOrNull()?.expression,
                verifiedBy = "mock"
            )

            Scenario.INCORRECT -> {
                val errorIndex = (steps.size / 2).coerceAtLeast(if (steps.size > 1) 1 else 0)
                val marked = steps.mapIndexed { i, s ->
                    when {
                        i < errorIndex -> s
                        i == errorIndex -> s.copy(
                            status = StepStatus.INCORRECT,
                            explanation = "The operation applied here does not preserve equality. Re-check the arithmetic on both sides.",
                            hint = "Compare this line carefully with the one above it."
                        )
                        else -> s.copy(
                            status = StepStatus.DEPENDENT_ON_PREVIOUS_ERROR,
                            explanation = "This step builds on the earlier mistake, so it can't be marked correct on its own."
                        )
                    }
                }
                CheckResponse(
                    status = CheckStatus.INCORRECT,
                    confidence = 0.95f,
                    questionEcho = request.questionText,
                    firstErrorStep = "step_${errorIndex + 1}",
                    steps = marked,
                    hints = listOf(
                        "Look at what changed between step $errorIndex and step ${errorIndex + 1}.",
                        "One side of the equation was changed without applying the same change to the other side.",
                        "Undo the operation in step ${errorIndex + 1} and redo it on both sides."
                    ),
                    summary = "First mistake found at step ${errorIndex + 1}.",
                    fullSolution = "Mock full solution: rework from step ${errorIndex + 1} applying each operation to both sides.",
                    verifiedBy = "mock"
                )
            }

            Scenario.INCOMPLETE -> CheckResponse(
                status = CheckStatus.INCOMPLETE,
                confidence = 0.9f,
                questionEcho = request.questionText,
                steps = steps,
                summary = "The work so far is fine, but the problem isn't finished.",
                hints = listOf("You've set things up correctly — keep going to isolate the unknown."),
                verifiedBy = "mock"
            )

            Scenario.UNCLEAR -> CheckResponse(
                status = CheckStatus.UNCLEAR,
                confidence = 0.3f,
                questionEcho = request.questionText,
                summary = "I couldn't confidently read this step.",
                message = "I couldn't confidently read part of the handwriting. You can edit the transcription or select the work again — InkProof never guesses.",
                verifiedBy = "mock"
            )
        }
    }

    override suspend fun solve(request: CheckRequest): CheckResponse {
        delay(simulatedLatencyMs)
        if (request.questionText.isBlank()) {
            return CheckResponse(
                status = CheckStatus.ERROR,
                message = "This question has no content yet. Add the problem before solving."
            )
        }
        return CheckResponse(
            status = CheckStatus.CORRECT,
            confidence = 0.9f,
            questionEcho = request.questionText,
            summary = "Mock worked solution for: ${request.questionText}",
            fullSolution = "Mock mode: a fully worked solution for \"${request.questionText}\" would appear here, step by step.",
            finalAnswer = "mock answer",
            verifiedBy = "mock"
        )
    }

    private enum class Scenario { CORRECT, INCORRECT, INCOMPLETE, UNCLEAR }

    private fun scenarioFor(request: CheckRequest): Scenario {
        val q = request.questionText.lowercase()
        // Explicit test keywords first (used by automated tests + demos).
        return when {
            "mock:correct" in q -> Scenario.CORRECT
            "mock:incorrect" in q -> Scenario.INCORRECT
            "mock:incomplete" in q -> Scenario.INCOMPLETE
            "mock:unclear" in q -> Scenario.UNCLEAR
            else -> {
                // Stable pseudo-random pick based on content hash.
                val h = (request.questionText.hashCode() xor
                    request.solutionLines.size.hashCode()) and 0x7fffffff
                when (h % 4) {
                    0 -> Scenario.CORRECT
                    1 -> Scenario.INCORRECT
                    2 -> Scenario.INCOMPLETE
                    else -> Scenario.UNCLEAR
                }
            }
        }
    }
}
