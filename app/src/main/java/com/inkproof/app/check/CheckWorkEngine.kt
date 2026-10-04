package com.inkproof.app.check

import com.inkproof.app.data.repo.CheckRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.model.CheckAction
import com.inkproof.app.model.CheckRequest
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.CheckStatus
import com.inkproof.app.model.Question
import com.inkproof.app.model.QuestionContentType
import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.newId

/**
 * Orchestrates CHECK MY WORK / SOLVE.
 *
 * Pipeline:
 *   Question object + solution strokes (ONLY that question's strokes)
 *   -> handwriting recognition (strokes, never screenshots)
 *   -> structured CheckRequest
 *   -> provider (backend or mock)
 *   -> validated structured CheckResponse
 *   -> cached by (question, contentVersion, solutionVersion, action)
 *
 * The Question object is the single source of truth for what the problem is.
 * If the question is typed, it is passed through verbatim — never OCR'd.
 * If handwriting can't be read confidently, the result is UNCLEAR; the
 * engine never guesses.
 */
class CheckWorkEngine(
    private val pageRepository: PageRepository,
    private val checkRepository: CheckRepository,
    private val recognizer: HandwritingRecognizer,
    private val provider: CheckProvider
) {

    /** Check one specific question. Other questions/pages are never touched. */
    suspend fun checkQuestion(questionId: String, action: CheckAction): CheckResponse {
        val question = pageRepository.question(questionId)
            ?: return CheckResponse(
                status = CheckStatus.ERROR,
                message = "This question no longer exists."
            )

        // Cache hit: same question content + same solution ink + same action.
        checkRepository.cached(
            question.id, question.contentVersion, question.solutionVersion, action
        )?.let { return it }

        val questionPart = resolveQuestionText(question)
        if (questionPart == null) {
            return CheckResponse(
                status = CheckStatus.UNCLEAR,
                summary = "I couldn't confidently read the question.",
                message = "I couldn't confidently read the handwritten question. Type it or rewrite it, then try again."
            )
        }
        val (questionText, questionSource, questionConfidence) = questionPart
        if (questionText.isBlank()) {
            return CheckResponse(
                status = CheckStatus.ERROR,
                message = "This question is empty. Add the problem before checking."
            )
        }

        // ONLY this question's solution strokes — isolation by construction.
        val solutionStrokes = pageRepository.solutionStrokes(question.id)
        val solutionLines: List<RecognizedLine>
        if (action == CheckAction.CHECK) {
            if (solutionStrokes.isEmpty()) {
                return CheckResponse(
                    status = CheckStatus.INCOMPLETE,
                    questionEcho = questionText,
                    summary = "No solution yet.",
                    message = "Write your solution in the solution area first — InkProof checks YOUR work."
                )
            }
            val recognition = recognizer.recognize(solutionStrokes)
            if (recognition.uncertain || recognition.lines.isEmpty()) {
                return CheckResponse(
                    status = CheckStatus.UNCLEAR,
                    questionEcho = questionText,
                    summary = "I couldn't confidently read this work.",
                    message = "I couldn't confidently read the handwriting in your solution. InkProof won't guess — try writing a little larger, or check a lasso selection."
                )
            }
            solutionLines = recognition.lines
        } else {
            solutionLines = emptyList()
        }

        val request = CheckRequest(
            requestId = newId(),
            action = if (action == CheckAction.CHECK) "check" else "solve",
            questionId = question.id,
            questionText = questionText,
            questionSource = questionSource,
            questionConfidence = questionConfidence,
            solutionLines = solutionLines,
            contentVersion = question.contentVersion,
            solutionVersion = question.solutionVersion
        )

        val response = when (action) {
            CheckAction.CHECK -> provider.check(request)
            CheckAction.SOLVE -> provider.solve(request)
        }

        // Only cache meaningful outcomes; transient errors should retry.
        if (response.status != CheckStatus.ERROR) {
            checkRepository.store(
                question.id, question.contentVersion, question.solutionVersion, action, response
            )
        }
        return response
    }

    /**
     * Check an explicit lasso selection (freeform pages).
     * Priority: explicit selection > current question > never page-wide guessing.
     */
    suspend fun checkSelection(
        pageId: String,
        selectedStrokes: List<Stroke>,
        action: CheckAction
    ): CheckResponse {
        if (selectedStrokes.isEmpty()) {
            return CheckResponse(
                status = CheckStatus.ERROR,
                message = "Nothing is selected. Lasso the work you want to check."
            )
        }
        // If the whole selection belongs to one question, use the question flow
        // so its typed statement (source of truth) is used.
        val questionIds = selectedStrokes.mapNotNull { it.questionId }.distinct()
        if (questionIds.size == 1 && selectedStrokes.all { it.questionId == questionIds[0] }) {
            return checkQuestion(questionIds[0], action)
        }

        val recognition = recognizer.recognize(selectedStrokes)
        if (recognition.uncertain || recognition.lines.isEmpty()) {
            return CheckResponse(
                status = CheckStatus.UNCLEAR,
                summary = "I couldn't confidently read this selection.",
                message = "I couldn't confidently read the selected handwriting. Select again or write more clearly — InkProof never guesses."
            )
        }
        // Freeform: the first recognized line is treated as the problem
        // statement and the rest as work, and this is shown transparently.
        val questionText = recognition.lines.first().text
        val work = recognition.lines.drop(1)
        val request = CheckRequest(
            requestId = newId(),
            action = if (action == CheckAction.CHECK) "check" else "solve",
            questionId = "selection:$pageId",
            questionText = questionText,
            questionSource = "handwritten-selection",
            questionConfidence = recognition.confidence,
            solutionLines = work,
            contentVersion = 0,
            solutionVersion = 0
        )
        return when (action) {
            CheckAction.CHECK -> provider.check(request)
            CheckAction.SOLVE -> provider.solve(request)
        }
    }

    /**
     * Resolve the question statement.
     *  - typed/pasted text is used verbatim (never OCR'd)
     *  - handwritten questions are recognized from ONLY the question strokes
     *  - unreadable handwriting -> null (caller reports UNCLEAR; no guessing)
     */
    private suspend fun resolveQuestionText(question: Question): Triple<String, String, Float>? {
        return when (question.contentType) {
            QuestionContentType.TYPED, QuestionContentType.PASTED ->
                Triple(question.typedText.orEmpty(), "typed", 1f)

            QuestionContentType.IMAGE, QuestionContentType.PDF ->
                // The media itself is the source; typed caption may accompany it.
                Triple(
                    question.typedText ?: "(imported ${question.contentType.name.lowercase()} question)",
                    question.contentType.name.lowercase(),
                    1f
                )

            QuestionContentType.HANDWRITTEN -> {
                val strokes = pageRepository.questionStrokes(question.id)
                if (strokes.isEmpty()) return Triple("", "handwritten", 0f)
                val recognition = recognizer.recognize(strokes)
                if (recognition.uncertain || recognition.lines.isEmpty()) return null
                Triple(
                    recognition.lines.joinToString(" ") { it.text },
                    "handwritten",
                    recognition.confidence
                )
            }
        }
    }
}
