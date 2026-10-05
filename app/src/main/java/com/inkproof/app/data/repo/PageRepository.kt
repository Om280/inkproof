package com.inkproof.app.data.repo

import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.model.Question
import com.inkproof.app.model.QuestionContentType
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokeRole
import com.inkproof.app.model.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Page-scoped content: strokes and questions.
 *
 * Isolation guarantees:
 *  - loading a page loads ONLY that page's strokes/questions
 *  - strokes are always written with their owning pageId
 *  - solution strokes are tagged with their questionId at write time,
 *    so checking a question never needs page-wide guessing.
 */
class PageRepository(private val db: InkProofDatabase) {

    // ----- Strokes -----

    suspend fun strokesForPage(pageId: String): List<Stroke> =
        db.strokeDao().forPage(pageId).map { it.toModel() }

    suspend fun addStroke(stroke: Stroke) {
        db.strokeDao().upsert(stroke.toEntity())
        if (stroke.questionId != null && stroke.role == StrokeRole.SOLUTION) {
            db.questionDao().bumpSolutionVersion(stroke.questionId)
        }
        if (stroke.questionId != null && stroke.role == StrokeRole.QUESTION) {
            db.questionDao().bumpContentVersion(stroke.questionId)
        }
    }

    suspend fun addStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        db.strokeDao().upsertAll(strokes.map { it.toEntity() })
        strokes.mapNotNull { s -> s.questionId?.takeIf { s.role == StrokeRole.SOLUTION } }
            .distinct()
            .forEach { db.questionDao().bumpSolutionVersion(it) }
    }

    suspend fun updateStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        db.strokeDao().upsertAll(strokes.map { it.toEntity() })
        // Geometry changes alter what recognition would read -> invalidate caches.
        bumpVersionsFor(strokes)
    }

    suspend fun bumpVersionsFor(strokes: List<Stroke>) {
        strokes.mapNotNull { s -> s.questionId?.takeIf { s.role == StrokeRole.SOLUTION } }
            .distinct()
            .forEach { db.questionDao().bumpSolutionVersion(it) }
    }

    suspend fun deleteStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        db.strokeDao().deleteAll(strokes.map { it.id })
        strokes.mapNotNull { s -> s.questionId?.takeIf { s.role == StrokeRole.SOLUTION } }
            .distinct()
            .forEach { db.questionDao().bumpSolutionVersion(it) }
    }

    suspend fun solutionStrokes(questionId: String): List<Stroke> =
        db.strokeDao().forQuestionRole(questionId, StrokeRole.SOLUTION.name).map { it.toModel() }

    suspend fun questionStrokes(questionId: String): List<Stroke> =
        db.strokeDao().forQuestionRole(questionId, StrokeRole.QUESTION.name).map { it.toModel() }

    // ----- Text objects -----

    suspend fun textObjectsForPage(pageId: String): List<com.inkproof.app.model.TextObject> =
        db.textObjectDao().forPage(pageId).map { it.toModel() }

    suspend fun upsertTextObject(obj: com.inkproof.app.model.TextObject) {
        db.textObjectDao().upsert(obj.toEntity())
    }

    suspend fun deleteTextObject(id: String) {
        db.textObjectDao().delete(id)
    }

    // ----- Questions -----

    fun observeQuestions(pageId: String): Flow<List<Question>> =
        db.questionDao().observeForPage(pageId).map { list -> list.map { it.toModel() } }

    suspend fun questionsForPage(pageId: String): List<Question> =
        db.questionDao().forPage(pageId).map { it.toModel() }

    suspend fun question(id: String): Question? = db.questionDao().byId(id)?.toModel()

    suspend fun createQuestion(
        pageId: String,
        contentType: QuestionContentType,
        typedText: String? = null,
        mediaPath: String? = null,
        questionTop: Float,
        questionHeight: Float = 260f,
        solutionHeight: Float = 700f
    ): Question {
        val index = db.questionDao().forPage(pageId).size
        val question = Question(
            id = newId(),
            pageId = pageId,
            orderIndex = index,
            contentType = contentType,
            typedText = typedText,
            mediaPath = mediaPath,
            questionTop = questionTop,
            questionBottom = questionTop + questionHeight,
            solutionTop = questionTop + questionHeight,
            solutionBottom = questionTop + questionHeight + solutionHeight
        )
        db.questionDao().upsert(question.toEntity())
        return question
    }

    suspend fun updateQuestionText(questionId: String, text: String) {
        db.questionDao().updateTypedText(questionId, text)
    }

    suspend fun deleteQuestion(questionId: String) {
        val strokes = db.strokeDao().forQuestionRole(questionId, StrokeRole.SOLUTION.name) +
            db.strokeDao().forQuestionRole(questionId, StrokeRole.QUESTION.name)
        db.strokeDao().deleteAll(strokes.map { it.id })
        db.checkResultDao().clearFor(questionId)
        db.questionDao().delete(questionId)
    }

    /**
     * Associate a stroke with the question whose solution region contains it.
     * Priority: explicit lasso association > containing question region > freeform.
     */
    suspend fun classifyStroke(pageId: String, stroke: Stroke): Stroke {
        val questions = questionsForPage(pageId)
        val b = stroke.bounds()
        val solutionOwner = questions.firstOrNull { q ->
            b.centerY >= q.solutionTop && b.centerY <= q.solutionBottom
        }
        if (solutionOwner != null) {
            return stroke.copy(questionId = solutionOwner.id, role = StrokeRole.SOLUTION)
        }
        val questionOwner = questions.firstOrNull { q ->
            b.centerY >= q.questionTop && b.centerY < q.questionBottom
        }
        if (questionOwner != null) {
            return stroke.copy(questionId = questionOwner.id, role = StrokeRole.QUESTION)
        }
        return stroke.copy(questionId = null, role = StrokeRole.FREEFORM)
    }
}
