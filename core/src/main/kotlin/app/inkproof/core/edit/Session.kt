package app.inkproof.core.edit

import app.inkproof.core.ink.eraseStrokes
import app.inkproof.core.model.Camera
import app.inkproof.core.model.Clock
import app.inkproof.core.model.Ids
import app.inkproof.core.model.ImageItem
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.InkRegion
import app.inkproof.core.model.NotebookMeta
import app.inkproof.core.model.Page
import app.inkproof.core.model.PageLayout
import app.inkproof.core.model.PageObject
import app.inkproof.core.model.PageTemplate
import app.inkproof.core.model.PaperColor
import app.inkproof.core.model.Question
import app.inkproof.core.model.ShapeItem
import app.inkproof.core.model.Stroke
import app.inkproof.core.model.TextItem
import app.inkproof.core.model.duplicate
import app.inkproof.core.model.nextQuestionY
import app.inkproof.core.model.offsetBy
import app.inkproof.core.model.recolored
import app.inkproof.core.model.regionForPoint
import app.inkproof.core.model.withId
import app.inkproof.core.model.withWidth
import app.inkproof.core.persist.DocumentRepository
import app.inkproof.core.shape.selectByLasso

class InkClipboard {
    var items: List<PageObject> = emptyList()
        private set
    private var generation = 0

    fun copy(objects: List<PageObject>) {
        items = objects
        generation = 0
    }

    fun hasItems(): Boolean = items.isNotEmpty()

    fun paste(ids: Ids): List<PageObject> {
        generation++
        val d = 28f * generation
        return items.map { it.duplicate(ids.newId(), d, d) }
    }
}

private class PageUndo(val before: Page) {
    fun restore(pages: MutableMap<String, Page>): PageUndo {
        val current = pages.getValue(before.id)
        pages[before.id] = before
        return PageUndo(current)
    }
}

class NotebookSession(
    var meta: NotebookMeta,
    val pages: MutableMap<String, Page>,
    private val repo: DocumentRepository,
    private val ids: Ids,
    private val clock: Clock,
    val layout: PageLayout = PageLayout(),
) {
    val clipboard = InkClipboard()
    var camera = Camera()
    var activeQuestionId: String? = null
    val dirty = LinkedHashSet<String>()
    var onChanged: (String?) -> Unit = {}

    private val undoStack = ArrayDeque<PageUndo>()
    private val redoStack = ArrayDeque<PageUndo>()
    private var recordHistory = true

    fun orderedPages(): List<Page> = meta.pageOrder.mapNotNull { pages[it] }

    fun page(id: String): Page = pages.getValue(id)

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun addStroke(pageId: String, stroke: Stroke): Stroke {
        val page = pages.getValue(pageId)
        val start = stroke.points.firstOrNull()
        val tagged = if (start != null && stroke.questionId == null) {
            val hit = regionForPoint(page, start.x, start.y, layout.pageWidth)
            if (hit != null) stroke.copy(questionId = hit.first, region = hit.second) else stroke
        } else {
            stroke
        }
        editPage(pageId) { current ->
            val questions = bumpQuestions(current, tagged.questionId, tagged.region)
            current.copy(objects = current.objects + tagged, questions = questions)
        }
        if (tagged.questionId != null) activeQuestionId = tagged.questionId
        return tagged
    }

    fun addShape(pageId: String, shape: ShapeItem) {
        editPage(pageId) { it.copy(objects = it.objects + shape) }
    }

    fun addText(pageId: String, text: TextItem) {
        editPage(pageId) { it.copy(objects = it.objects + text) }
    }

    fun addImage(pageId: String, image: ImageItem) {
        editPage(pageId) { it.copy(objects = it.objects + image) }
    }

    fun eraseAt(pageId: String, x: Float, y: Float, radius: Float): Boolean {
        val page = pages.getValue(pageId)
        val strokes = page.strokes
        val (erased, changed) = eraseStrokes(strokes, x, y, radius, ids)
        if (!changed) return false
        val removedIds = strokes.map { it.id }.toSet() - erased.map { it.id }.toSet()
        val affectedQuestions = strokes.filter { it.id in removedIds }.mapNotNull { it.questionId }.toSet()
        editPage(pageId) { current ->
            val others = current.objects.filter { it !is Stroke }
            var questions = current.questions
            for (qid in affectedQuestions) {
                val region = strokes.firstOrNull { it.questionId == qid }?.region
                questions = bumpQuestions(current.copy(questions = questions), qid, region ?: InkRegion.SOLUTION)
            }
            current.copy(objects = others + erased, questions = questions)
        }
        return true
    }

    fun replaceStrokeWithShape(pageId: String, strokeId: String, shape: ShapeItem) {
        editPage(pageId) { page ->
            page.copy(objects = page.objects.filter { it.id != strokeId } + shape)
        }
    }

    fun moveObjects(pageId: String, selected: Set<String>, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        editPage(pageId) { page ->
            page.copy(objects = page.objects.map { if (it.id in selected) it.offsetBy(dx, dy) else it })
        }
    }

    fun moveQuestion(pageId: String, questionId: String, dy: Float) {
        if (dy == 0f) return
        editPage(pageId) { page ->
            val q = page.questions.first { it.id == questionId }
            page.copy(
                questions = page.questions.map { if (it.id == questionId) it.copy(y = it.y + dy) else it },
                objects = page.objects.map { obj ->
                    val belongs = when (obj) {
                        is Stroke -> obj.questionId == questionId
                        is ShapeItem -> obj.questionId == questionId
                        is TextItem -> obj.questionId == questionId
                        is ImageItem -> obj.questionId == questionId
                    }
                    if (belongs) obj.offsetBy(0f, dy) else obj
                },
            ).let { updated ->
                updated.copy(questions = updated.questions.map {
                    if (it.id == questionId) it.copy(y = (q.y + dy).coerceIn(0f, layout.pageHeight - 80f)) else it
                })
            }
        }
    }

    fun resizeQuestion(pageId: String, questionId: String, height: Float) {
        editPage(pageId) { page ->
            page.copy(
                questions = page.questions.map {
                    if (it.id == questionId) it.copy(height = height.coerceIn(280f, 1400f)) else it
                },
            )
        }
    }

    fun recolor(pageId: String, selected: Set<String>, color: Long) {
        editPage(pageId) { page ->
            page.copy(objects = page.objects.map { if (it.id in selected) it.recolored(color) else it })
        }
    }

    fun resizeStrokeWidth(pageId: String, selected: Set<String>, width: Float) {
        editPage(pageId) { page ->
            page.copy(objects = page.objects.map { if (it.id in selected) it.withWidth(width) else it })
        }
    }

    fun deleteObjects(pageId: String, selected: Set<String>) {
        if (selected.isEmpty()) return
        editPage(pageId) { page ->
            val removed = page.objects.filter { it.id in selected }
            var questions = page.questions
            for (obj in removed) {
                val qid = when (obj) {
                    is Stroke -> obj.questionId
                    else -> null
                }
                val region = (obj as? Stroke)?.region
                if (qid != null) questions = bumpList(questions, qid, region ?: InkRegion.SOLUTION)
            }
            page.copy(objects = page.objects.filter { it.id !in selected }, questions = questions)
        }
    }

    fun copyObjects(pageId: String, selected: Set<String>) {
        val page = pages.getValue(pageId)
        clipboard.copy(page.objects.filter { it.id in selected })
    }

    fun cutObjects(pageId: String, selected: Set<String>) {
        copyObjects(pageId, selected)
        deleteObjects(pageId, selected)
    }

    fun paste(pageId: String): List<PageObject> {
        if (!clipboard.hasItems()) return emptyList()
        val pasted = clipboard.paste(ids)
        editPage(pageId) { it.copy(objects = it.objects + pasted) }
        return pasted
    }

    fun duplicateObjects(pageId: String, selected: Set<String>): List<PageObject> {
        val page = pages.getValue(pageId)
        val copies = page.objects.filter { it.id in selected }.map { it.duplicate(ids.newId(), 28f, 28f) }
        if (copies.isEmpty()) return emptyList()
        editPage(pageId) { it.copy(objects = it.objects + copies) }
        return copies
    }

    fun selectLasso(pageId: String, polygon: List<InkPoint>): Set<String> =
        selectByLasso(pages.getValue(pageId).objects, polygon)

    fun addQuestion(pageId: String, viewportLocalY: Float): Question {
        val page = pages.getValue(pageId)
        val q = Question(
            id = ids.newId(),
            y = nextQuestionY(page, viewportLocalY, layout.pageHeight),
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        editPage(pageId) { it.copy(questions = it.questions + q) }
        activeQuestionId = q.id
        return q
    }

    fun setPromptText(pageId: String, questionId: String, text: String) {
        editPage(pageId) { page ->
            page.copy(
                questions = page.questions.map {
                    if (it.id == questionId) {
                        it.copy(
                            promptText = text,
                            promptVersion = it.promptVersion + 1,
                            updatedAt = clock.now(),
                        )
                    } else {
                        it
                    }
                },
            )
        }
        activeQuestionId = questionId
    }

    fun setRecognized(
        pageId: String,
        questionId: String,
        prompt: String?,
        solution: String?,
        promptConfidence: Float?,
        solutionConfidence: Float?,
    ) {
        editPage(pageId) { page ->
            page.copy(
                questions = page.questions.map { q ->
                    if (q.id != questionId) q
                    else q.copy(
                        recognizedPrompt = prompt ?: q.recognizedPrompt,
                        recognizedSolution = solution ?: q.recognizedSolution,
                        promptConfidence = promptConfidence ?: q.promptConfidence,
                        solutionConfidence = solutionConfidence ?: q.solutionConfidence,
                        promptVersion = if (prompt != null) q.promptVersion + 1 else q.promptVersion,
                        solutionVersion = if (solution != null) q.solutionVersion + 1 else q.solutionVersion,
                        updatedAt = clock.now(),
                    )
                },
            )
        }
    }

    fun setPaper(pageId: String, paper: PaperColor) {
        editPage(pageId) { it.copy(paper = paper) }
    }

    fun setTemplate(pageId: String, template: PageTemplate) {
        editPage(pageId) { it.copy(template = template) }
    }

    fun setBackground(pageId: String, assetId: String?, kind: String?) {
        editPage(pageId) { it.copy(backgroundAssetId = assetId, backgroundKind = kind) }
    }

    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        recordHistory = false
        val inverse = entry.restore(pages)
        redoStack.addLast(inverse)
        dirty.add(entry.before.id)
        recordHistory = true
        onChanged(entry.before.id)
        return true
    }

    fun redo(): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        recordHistory = false
        val inverse = entry.restore(pages)
        undoStack.addLast(inverse)
        dirty.add(inverse.before.id)
        recordHistory = true
        onChanged(inverse.before.id)
        return true
    }

    fun addPage(afterPageId: String?): Page {
        val page = repo.createPage(meta.id, afterPageId)
        pages[page.id] = page
        meta = repo.requireMeta(meta.id)
        onChanged(page.id)
        return page
    }

    fun deletePage(pageId: String): Boolean {
        val removed = pages[pageId]
        val ok = repo.deletePage(meta.id, pageId)
        if (!ok) return false
        pages.remove(pageId)
        meta = repo.requireMeta(meta.id)
        dirty.remove(pageId)
        onChanged(null)
        return removed != null || ok
    }

    fun reorderPage(pageId: String, direction: Int) {
        repo.reorder(meta.id, pageId, direction)
        meta = repo.requireMeta(meta.id)
        onChanged(null)
    }

    data class SaveSnap(
        val meta: NotebookMeta,
        val pages: List<Page>,
        val camera: Camera,
    )

    fun takeSaveSnap(): SaveSnap {
        val snapPages = dirty.mapNotNull { pages[it] }.filter { it.id in meta.pageOrder }
        dirty.clear()
        return SaveSnap(meta.copy(), snapPages, camera.copy())
    }

    fun noteSaved(updated: NotebookMeta) {
        meta = updated
    }

    fun flush() {
        val snap = takeSaveSnap()
        if (snap.pages.isNotEmpty()) repo.saveSnapshot(snap.meta, snap.pages)
        meta = repo.requireMeta(meta.id)
        repo.saveUi(
            meta.id,
            app.inkproof.core.model.NotebookUiState(snap.camera.scrollX, snap.camera.scrollY, snap.camera.scale, activeQuestionId),
        )
    }

    private fun editPage(pageId: String, block: (Page) -> Page): Page {
        val before = pages.getValue(pageId)
        val after = block(before).copy(updatedAt = clock.now())
        pages[pageId] = after
        if (recordHistory) {
            undoStack.addLast(PageUndo(before))
            redoStack.clear()
            while (undoStack.size > 60) undoStack.removeFirst()
        }
        dirty.add(pageId)
        onChanged(pageId)
        return after
    }

    private fun bumpQuestions(page: Page, questionId: String?, region: InkRegion): List<Question> {
        if (questionId == null) return page.questions
        return bumpList(page.questions, questionId, region)
    }

    private fun bumpList(questions: List<Question>, questionId: String, region: InkRegion): List<Question> =
        questions.map { q ->
            if (q.id != questionId) q
            else if (region == InkRegion.PROMPT) {
                q.copy(promptVersion = q.promptVersion + 1, updatedAt = clock.now())
            } else {
                q.copy(solutionVersion = q.solutionVersion + 1, updatedAt = clock.now())
            }
        }
}

fun questionIdsTouched(page: Page, objectIds: Set<String>): Set<String> {
    val ids = LinkedHashSet<String>()
    for (obj in page.objects) {
        if (obj.id !in objectIds) continue
        when (obj) {
            is Stroke -> obj.questionId?.let { ids.add(it) }
            is ShapeItem -> obj.questionId?.let { ids.add(it) }
            is TextItem -> obj.questionId?.let { ids.add(it) }
            is ImageItem -> obj.questionId?.let { ids.add(it) }
        }
    }
    return ids
}

sealed class QuestionResolve {
    data class Found(val questionId: String) : QuestionResolve()
    data object None : QuestionResolve()
    data class Ambiguous(val ids: List<String>) : QuestionResolve()
}

fun resolveQuestion(
    page: Page,
    explicitId: String?,
    activeId: String?,
    selectedObjectIds: Set<String>,
): QuestionResolve {
    if (explicitId != null && page.questions.any { it.id == explicitId }) return QuestionResolve.Found(explicitId)
    val fromSelection = questionIdsTouched(page, selectedObjectIds)
    if (fromSelection.size == 1) return QuestionResolve.Found(fromSelection.first())
    if (fromSelection.size > 1) return QuestionResolve.Ambiguous(fromSelection.toList())
    if (activeId != null && page.questions.any { it.id == activeId }) return QuestionResolve.Found(activeId)
    if (page.questions.size == 1) return QuestionResolve.Found(page.questions.first().id)
    if (page.questions.size > 1) return QuestionResolve.Ambiguous(page.questions.map { it.id })
    return QuestionResolve.None
}
