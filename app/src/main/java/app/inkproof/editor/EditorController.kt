package app.inkproof.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkproof.ai.HttpAnalysisTransport
import app.inkproof.core.ai.AnalysisPipeline
import app.inkproof.core.ai.CheckDecision
import app.inkproof.core.ai.ReviewPanel
import app.inkproof.core.ai.buildCheckRequest
import app.inkproof.core.ai.buildSolveRequest
import app.inkproof.core.edit.NotebookSession
import app.inkproof.core.edit.QuestionResolve
import app.inkproof.core.edit.resolveQuestion
import app.inkproof.core.model.AiMode
import app.inkproof.core.model.AppSettings
import app.inkproof.core.model.Camera
import app.inkproof.core.model.EraserSize
import app.inkproof.core.model.Ids
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.InkRegion
import app.inkproof.core.model.InkTool
import app.inkproof.core.model.PageLayout
import app.inkproof.core.model.PageTemplate
import app.inkproof.core.model.PaperColor
import app.inkproof.core.model.QuestionHit
import app.inkproof.core.model.ShapeItem
import app.inkproof.core.model.ShapeKind
import app.inkproof.core.model.StabilizerLevel
import app.inkproof.core.model.Stroke
import app.inkproof.core.model.SystemClock
import app.inkproof.core.model.TextItem
import app.inkproof.core.model.ToolMode
import app.inkproof.core.model.UuidIds
import app.inkproof.core.persist.DocumentRepository
import app.inkproof.core.recognize.RecognitionDraft
import app.inkproof.core.recognize.UnistrokeRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

data class PageChip(val id: String, val index: Int, val paper: PaperColor, val template: PageTemplate)

sealed class EditorDialog {
    data class ConfirmRead(
        val questionId: String,
        val pageId: String,
        val prompt: String,
        val solution: String,
        val reason: String,
        val action: String,
    ) : EditorDialog()

    data class ChooseQuestion(val ids: List<String>, val labels: List<String>, val action: String) : EditorDialog()
    data class TypeText(val pageId: String, val questionId: String, val field: String, val initial: String) : EditorDialog()
    data class Notice(val text: String) : EditorDialog()
}

data class ChromeState(
    val title: String = "",
    val pageLabel: String = "1 / 1",
    val tool: ToolMode = ToolMode.PEN,
    val penColor: Long = app.inkproof.core.model.Palette.BLACK,
    val markerColor: Long = app.inkproof.core.model.Palette.YELLOW,
    val penWidth: Float = 2.6f,
    val markerWidth: Float = 22f,
    val eraser: EraserSize = EraserSize.MEDIUM,
    val stabilizer: StabilizerLevel = StabilizerLevel.MEDIUM,
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val review: ReviewPanel? = null,
    val banner: String? = null,
    val busy: Boolean = false,
    val dialog: EditorDialog? = null,
    val drawer: Boolean = false,
    val flyout: Boolean = false,
    val pages: List<PageChip> = emptyList(),
    val selectionCount: Int = 0,
    val darkChrome: Boolean = false,
    val customColors: List<Long> = emptyList(),
)

class EditorController(
    private val repo: DocumentRepository,
    private val transport: HttpAnalysisTransport,
    private val ids: Ids = UuidIds,
    private val recognizer: suspend (List<Stroke>) -> RecognitionDraft? = { null },
) : ViewModel() {
    var session: NotebookSession? = null
        private set
    var tool: ToolMode = ToolMode.PEN
    var selection: Set<String> = emptySet()
    var selectionPageId: String? = null
    var contentGeneration: Int = 0
        private set
    var onInvalidate: (() -> Unit)? = null
    var allowFit: Boolean = true
    private val _chrome = MutableStateFlow(ChromeState())
    val chrome: StateFlow<ChromeState> = _chrome
    private var saveJob: Job? = null
    private var saveQueued = false
    private val pipeline = AnalysisPipeline(transport, mode = { settings().aiMode })
    private val localReader = UnistrokeRecognizer()
    private val imageCache = HashMap<String, Bitmap>()

    fun settings(): AppSettings = repo.loadSettings()

    fun open(notebookId: String) {
        val meta = repo.requireMeta(notebookId)
        val loaded = meta.pageOrder.associateWith { repo.loadPage(notebookId, it) }.toMutableMap()
        val opened = NotebookSession(meta, loaded, repo, ids, SystemClock)
        val ui = repo.loadUi(notebookId)
        allowFit = ui.scale <= 1.01f && ui.cameraX == 0f && ui.cameraY == 0f
        if (!allowFit) {
            opened.camera = Camera(ui.cameraX, ui.cameraY, ui.scale.coerceIn(0.45f, 3.5f))
        }
        opened.onChanged = {
            contentGeneration++
            publish(invalidate = true)
            scheduleSave()
        }
        session = opened
        selection = emptySet()
        val prefs = settings()
        tool = ToolMode.PEN
        contentGeneration++
        publish(invalidate = true)
        _chrome.value = _chrome.value.copy(
            penColor = prefs.penColor,
            markerColor = prefs.markerColor,
            penWidth = prefs.penWidth,
            markerWidth = prefs.markerWidth,
            eraser = prefs.eraserSize,
            stabilizer = prefs.stabilizer,
            shapeKind = prefs.shapeKind,
        )
    }

    fun applyFit(viewWidth: Int) {
        val current = session ?: return
        if (!allowFit || viewWidth <= 0) return
        val fit = (viewWidth * 0.96f) / current.layout.pageWidth
        current.camera = current.camera.copy(scale = fit.coerceIn(0.45f, 3.5f))
        allowFit = false
        onInvalidate?.invoke()
    }

    fun close() {
        flushNow()
        session = null
    }

    fun undo() {
        session?.undo()
        publish(invalidate = true)
    }

    fun redo() {
        session?.redo()
        publish(invalidate = true)
    }

    fun setTool(mode: ToolMode) {
        tool = mode
        _chrome.value = _chrome.value.copy(tool = mode, flyout = true)
        onInvalidate?.invoke()
    }

    fun toggleFlyout() {
        _chrome.value = _chrome.value.copy(flyout = !_chrome.value.flyout)
    }

    fun setPenColor(color: Long) {
        saveSettings(settings().copy(penColor = color))
        if (selection.isNotEmpty()) selectionPageId?.let { session?.recolor(it, selection, color) }
        publish(invalidate = true)
    }

    fun setMarkerColor(color: Long) {
        saveSettings(settings().copy(markerColor = color))
        publish(invalidate = false)
    }

    fun rememberCustomColor(color: Long) {
        val current = settings()
        saveSettings(current.copy(customColors = (current.customColors + color).distinct().takeLast(8), penColor = color))
        if (selection.isNotEmpty()) selectionPageId?.let { session?.recolor(it, selection, color) }
        publish(invalidate = true)
    }

    fun setMarkerAlpha(alpha: Float) {
        saveSettings(settings().copy(markerAlpha = alpha.coerceIn(0.15f, 0.85f)))
        publish(invalidate = false)
    }

    fun setPenWidth(width: Float) {
        saveSettings(settings().copy(penWidth = width))
        publish(invalidate = false)
    }

    fun setMarkerWidth(width: Float) {
        saveSettings(settings().copy(markerWidth = width))
        publish(invalidate = false)
    }

    fun setEraser(size: EraserSize) {
        saveSettings(settings().copy(eraserSize = size))
        publish(invalidate = false)
    }

    fun setStabilizer(level: StabilizerLevel) {
        saveSettings(settings().copy(stabilizer = level))
        publish(invalidate = false)
    }

    fun setShapeKind(kind: ShapeKind) {
        saveSettings(settings().copy(shapeKind = kind))
        publish(invalidate = false)
    }

    fun toggleDrawer() {
        _chrome.value = _chrome.value.copy(drawer = !_chrome.value.drawer)
    }

    fun dismissBanner() {
        _chrome.value = _chrome.value.copy(banner = null)
    }

    fun dismissDialog() {
        _chrome.value = _chrome.value.copy(dialog = null)
    }

    fun commitStroke(pageId: String, points: List<InkPoint>, tool: InkTool, color: Long, width: Float, alpha: Float) {
        val stroke = Stroke(ids.newId(), points, color, width, tool, alpha)
        session?.addStroke(pageId, stroke)
        selection = emptySet()
    }

    fun commitShape(pageId: String, shape: ShapeItem) {
        session?.addShape(pageId, shape.copy(id = ids.newId()))
    }

    fun erase(pageId: String, x: Float, y: Float, radius: Float) {
        session?.eraseAt(pageId, x, y, radius)
    }

    fun onQuestionHit(pageId: String, questionId: String, hit: QuestionHit) {
        session?.activeQuestionId = questionId
        when (hit) {
            QuestionHit.CHECK -> requestCheck(questionId)
            QuestionHit.SOLVE -> requestSolve(questionId)
            QuestionHit.RECOGNIZE -> recognize(questionId)
            else -> Unit
        }
    }

    fun onLasso(pageId: String, points: List<InkPoint>) {
        val selected = session?.selectLasso(pageId, points).orEmpty()
        selection = selected
        selectionPageId = pageId
        publish(invalidate = true)
    }

    fun moveSelection(pageId: String, dx: Float, dy: Float) {
        if (selection.isEmpty()) return
        session?.moveObjects(pageId, selection, dx, dy)
    }

    fun clearSelection() {
        selection = emptySet()
        publish(invalidate = true)
    }

    fun copySelection() {
        val page = selectionPageId ?: return
        session?.copyObjects(page, selection)
        banner("Copied ${selection.size}")
    }

    fun cutSelection() {
        val page = selectionPageId ?: return
        session?.cutObjects(page, selection)
        selection = emptySet()
        banner("Cut")
    }

    fun pasteSelection() {
        val page = currentPageId() ?: return
        val pasted = session?.paste(page).orEmpty()
        selection = pasted.map { it.id }.toSet()
        selectionPageId = page
        publish(invalidate = true)
    }

    fun placeImage(assetId: String, width: Float, height: Float) {
        val pageId = currentPageId() ?: return
        session?.addImage(pageId, app.inkproof.core.model.ImageItem(ids.newId(), assetId, 72f, 96f, width, height))
        publish(invalidate = true)
    }

    fun duplicateSelection() {
        val page = selectionPageId ?: return
        val copies = session?.duplicateObjects(page, selection).orEmpty()
        selection = copies.map { it.id }.toSet()
        publish(invalidate = true)
    }

    fun deleteSelection() {
        val page = selectionPageId ?: return
        session?.deleteObjects(page, selection)
        selection = emptySet()
    }

    fun typeIntoActive(field: String) {
        val pageId = currentPageId() ?: return
        val q = session?.page(pageId)?.questions?.lastOrNull()
        if (q == null) {
            banner("Add a question first.")
            return
        }
        _chrome.value = _chrome.value.copy(
            dialog = EditorDialog.TypeText(
                pageId,
                q.id,
                field,
                if (field == "prompt") q.promptText else q.recognizedSolution.orEmpty(),
            ),
        )
    }

    fun addQuestion() {
        val pageId = currentPageId() ?: return
        val session = session ?: return
        val y = session.camera.scrollY.coerceAtLeast(0f)
        val index = session.layout.pageIndexAt(y + 80f, session.meta.pageOrder.size) ?: 0
        val local = session.layout.localY(y + 120f, index).coerceAtLeast(24f)
        val target = session.meta.pageOrder.getOrNull(index) ?: pageId
        session.addQuestion(target, local)
        banner("Question added on this page")
    }

    fun setPrompt(pageId: String, questionId: String, text: String) {
        session?.setPromptText(pageId, questionId, text)
        dismissDialog()
    }

    fun setSolutionText(pageId: String, questionId: String, text: String) {
        session?.setRecognized(pageId, questionId, null, text, null, 1f)
        dismissDialog()
    }

    fun setPaper(pageId: String, paper: PaperColor) {
        session?.setPaper(pageId, paper)
    }

    fun setTemplate(pageId: String, template: PageTemplate) {
        session?.setTemplate(pageId, template)
    }

    fun addPage() {
        val after = currentPageId()
        session?.addPage(after)
        publish(invalidate = true)
    }

    fun deleteCurrentPage() {
        val id = currentPageId() ?: return
        val ok = session?.deletePage(id) ?: false
        if (!ok) banner("A notebook keeps at least one page.")
        publish(invalidate = true)
    }

    fun movePage(pageId: String, direction: Int) {
        session?.reorderPage(pageId, direction)
        publish(invalidate = true)
    }

    fun requestCheck(explicitId: String? = null) = beginAction("check", explicitId)
    fun requestSolve(explicitId: String? = null) = beginAction("solve", explicitId)

    fun confirmAndRun(dialog: EditorDialog.ConfirmRead, prompt: String, solution: String) {
        val session = session ?: return
        session.setRecognized(
            dialog.pageId,
            dialog.questionId,
            prompt,
            solution,
            1f,
            1f,
        )
        dismissDialog()
        if (dialog.action == "solve") launchSolve(dialog.questionId) else launchCheck(dialog.questionId)
    }

    fun revealSolution() {
        val panel = _chrome.value.review ?: return
        _chrome.value = _chrome.value.copy(review = panel.reveal())
    }

    fun nextHint() {
        val panel = _chrome.value.review ?: return
        _chrome.value = _chrome.value.copy(review = panel.withAnotherHint())
    }

    fun dismissReview() {
        _chrome.value = _chrome.value.copy(review = null)
    }

    fun tryAgain() {
        dismissReview()
        banner("Edit the working, then check again.")
    }

    fun recognize(questionId: String) {
        val session = session ?: return
        val page = pageOf(questionId) ?: return
        val q = page.questions.first { it.id == questionId }
        val strokes = page.strokes.filter { it.questionId == questionId }
        val local = localReader.recognizeExpression(strokes)
        viewModelScope.launch {
            val better = withContext(Dispatchers.IO) { recognizer(strokes) }
            val draft = better?.takeIf { it.confidence > local.confidence && it.text.isNotBlank() } ?: local
            _chrome.value = _chrome.value.copy(
                dialog = EditorDialog.ConfirmRead(
                    questionId = q.id,
                    pageId = page.id,
                    prompt = q.promptText.ifBlank { draft.text },
                    solution = draft.text,
                    reason = "Interpreted with ${draft.source} at ${(draft.confidence * 100).toInt()}% confidence. Ink is unchanged.",
                    action = "recognize",
                ),
            )
        }
    }

    fun bitmapFor(assetId: String): Bitmap? {
        imageCache[assetId]?.let { return it }
        val file = repo.assetFile(assetId)
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        imageCache[assetId] = bmp
        return bmp
    }

    fun insertImportedText(pageId: String, text: String) {
        session?.addText(pageId, TextItem(ids.newId(), text.take(4000), 48f, 72f, width = 1000f))
    }

    fun currentPageId(): String? {
        val session = session ?: return null
        val index = session.layout.pageIndexAt(session.camera.scrollY + 80f, session.meta.pageOrder.size) ?: 0
        return session.meta.pageOrder.getOrNull(index)
    }

    fun flushNow() {
        val snap = session?.takeSaveSnap() ?: return
        if (snap.pages.isNotEmpty()) repo.saveSnapshot(snap.meta, snap.pages)
        repo.saveUi(
            snap.meta.id,
            app.inkproof.core.model.NotebookUiState(snap.camera.scrollX, snap.camera.scrollY, snap.camera.scale, session?.activeQuestionId),
        )
        session?.noteSaved(repo.requireMeta(snap.meta.id))
        snap.pages.forEach { writeThumbnail(it) }
    }

    private fun beginAction(action: String, explicitId: String?) {
        val session = session ?: return
        val page = session.orderedPages().firstOrNull { page ->
            explicitId != null && page.questions.any { it.id == explicitId }
        } ?: session.page(currentPageId() ?: return)
        when (val resolved = resolveQuestion(page, explicitId, session.activeQuestionId, if (selectionPageId == page.id) selection else emptySet())) {
            is QuestionResolve.None -> banner("Add a question on this page before checking.")
            is QuestionResolve.Ambiguous -> {
                _chrome.value = _chrome.value.copy(
                    dialog = EditorDialog.ChooseQuestion(
                        resolved.ids,
                        resolved.ids.map { id -> page.questions.first { it.id == id }.promptText.ifBlank { "Question" }.take(48) },
                        action,
                    ),
                )
            }
            is QuestionResolve.Found -> {
                if (action == "solve") prepareSolve(page.id, resolved.questionId) else prepareCheck(page.id, resolved.questionId)
            }
        }
    }

    private fun prepareCheck(pageId: String, questionId: String) {
        val session = session ?: return
        val page = session.page(pageId)
        val q = page.questions.first { it.id == questionId }
        val promptStrokes = page.strokes.filter { it.questionId == questionId && it.region == InkRegion.PROMPT }
        val selectedHere = if (selectionPageId == pageId) selection else emptySet()
        val solutionStrokes = page.strokes.filter {
            (it.questionId == questionId && it.region == InkRegion.SOLUTION) ||
                (it.id in selectedHere && it.questionId == null)
        }
        val promptRead = if (q.promptText.isBlank()) localReader.recognizeExpression(promptStrokes) else null
        val solutionRead = if (q.recognizedSolution.isNullOrBlank()) localReader.recognizeExpression(solutionStrokes) else null
        val decision = app.inkproof.core.ai.decideCheck(
            q,
            promptRead?.text,
            solutionRead?.text,
            solutionRead?.confidence ?: q.solutionConfidence,
        )
        when (decision) {
            is CheckDecision.Blocked -> banner(decision.reason)
            is CheckDecision.Confirm -> {
                _chrome.value = _chrome.value.copy(
                    dialog = EditorDialog.ConfirmRead(questionId, pageId, decision.prompt, decision.solution, decision.reason, "check"),
                )
            }
            is CheckDecision.Run -> {
                if (q.recognizedSolution.isNullOrBlank()) {
                    session.setRecognized(pageId, questionId, null, decision.solution, null, 1f)
                }
                launchCheck(questionId)
            }
        }
    }

    private fun prepareSolve(pageId: String, questionId: String) {
        val session = session ?: return
        val page = session.page(pageId)
        val q = page.questions.first { it.id == questionId }
        if (q.promptText.isBlank() && q.recognizedPrompt.isNullOrBlank()) {
            val read = localReader.recognizeExpression(page.strokes.filter { it.questionId == questionId && it.region == InkRegion.PROMPT })
            if (read.confidence < 0.55f || read.text.isBlank()) {
                _chrome.value = _chrome.value.copy(
                    dialog = EditorDialog.ConfirmRead(questionId, pageId, read.text, "", "Confirm the question before solving. Solution ink is not sent.", "solve"),
                )
                return
            }
            session.setRecognized(pageId, questionId, read.text, null, read.confidence, null)
        }
        launchSolve(questionId)
    }

    private fun launchCheck(questionId: String) {
        val session = session ?: return
        val page = pageOf(questionId) ?: return
        val include = if (selectionPageId == page.id) selection else emptySet()
        val request = buildCheckRequest(session.meta.id, session.page(page.id), questionId, include)
        viewModelScope.launch {
            _chrome.value = _chrome.value.copy(busy = true, banner = null)
            val response = withContext(Dispatchers.IO) { pipeline.check(request) }
            _chrome.value = _chrome.value.copy(busy = false, review = ReviewPanel(response), drawer = false)
            onInvalidate?.invoke()
        }
    }

    private fun launchSolve(questionId: String) {
        val session = session ?: return
        val page = pageOf(questionId) ?: return
        val request = buildSolveRequest(session.meta.id, session.page(page.id), questionId)
        viewModelScope.launch {
            _chrome.value = _chrome.value.copy(busy = true)
            val response = withContext(Dispatchers.IO) { pipeline.solve(request) }
            _chrome.value = _chrome.value.copy(busy = false, review = ReviewPanel(response))
        }
    }

    private fun pageOf(questionId: String) = session?.orderedPages()?.firstOrNull { page -> page.questions.any { it.id == questionId } }

    private fun publish(invalidate: Boolean) {
        val session = session
        val pages = session?.orderedPages().orEmpty()
        val index = session?.let {
            it.layout.pageIndexAt(it.camera.scrollY + 40f, it.meta.pageOrder.size) ?: 0
        } ?: 0
        val prefs = settings()
        _chrome.value = _chrome.value.copy(
            title = session?.meta?.name ?: "",
            pageLabel = "${index + 1} / ${pages.size.coerceAtLeast(1)}",
            tool = tool,
            penColor = prefs.penColor,
            markerColor = prefs.markerColor,
            penWidth = prefs.penWidth,
            markerWidth = prefs.markerWidth,
            eraser = prefs.eraserSize,
            stabilizer = prefs.stabilizer,
            shapeKind = prefs.shapeKind,
            canUndo = session?.canUndo() == true,
            canRedo = session?.canRedo() == true,
            pages = pages.mapIndexed { i, page -> PageChip(page.id, i, page.paper, page.template) },
            selectionCount = selection.size,
            darkChrome = prefs.theme == app.inkproof.core.model.ThemeMode.DARK,
            customColors = prefs.customColors,
        )
        if (invalidate) onInvalidate?.invoke()
    }

    fun showBanner(text: String) = banner(text)

    private fun banner(text: String) {
        _chrome.value = _chrome.value.copy(banner = text)
    }

    private fun saveSettings(settings: AppSettings) {
        repo.saveSettings(settings)
    }

    private fun scheduleSave() {
        saveQueued = true
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            while (saveQueued) {
                saveQueued = false
                delay(350)
                if (saveQueued) continue
                val snap = session?.takeSaveSnap() ?: continue
                withContext(Dispatchers.IO) {
                    if (snap.pages.isNotEmpty()) repo.saveSnapshot(snap.meta, snap.pages)
                    repo.saveUi(
                        snap.meta.id,
                        app.inkproof.core.model.NotebookUiState(snap.camera.scrollX, snap.camera.scrollY, snap.camera.scale, session?.activeQuestionId),
                    )
                    snap.pages.forEach { writeThumbnail(it) }
                }
                session?.noteSaved(repo.requireMeta(snap.meta.id))
            }
        }
    }

    private fun writeThumbnail(page: app.inkproof.core.model.Page) {
        try {
            val bmp = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val images = HashMap<String, Bitmap>()
            fun load(id: String) {
                val file = repo.assetFile(id)
                if (!file.exists()) return
                BitmapFactory.decodeFile(file.absolutePath)?.let { images[id] = it }
            }
            page.backgroundAssetId?.let { load(it) }
            page.objects.filterIsInstance<app.inkproof.core.model.ImageItem>().forEach { load(it.assetId) }
            InkPainter.drawPage(canvas, page, PageLayout(), images, 240f, 320f)
            FileOutputStream(repo.thumbnailFile(page.id)).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
            images.values.forEach { it.recycle() }
        } catch (_: Exception) {
            // Thumbnail failure must not block saving.
        }
    }
}
