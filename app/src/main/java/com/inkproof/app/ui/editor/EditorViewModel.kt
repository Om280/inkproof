package com.inkproof.app.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkproof.app.InkProofApp
import com.inkproof.app.data.db.PageEntity
import com.inkproof.app.data.settings.Settings
import com.inkproof.app.ink.CanvasOp
import com.inkproof.app.ink.UndoRedoStack
import com.inkproof.app.model.CheckAction
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.PenStyle
import com.inkproof.app.model.Question
import com.inkproof.app.model.QuestionContentType
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.TextObject
import com.inkproof.app.model.ToolType
import com.inkproof.app.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** One fully-loaded page, ready for the canvas. */
data class PageContent(
    val page: PageEntity,
    val strokes: List<Stroke>,
    val questions: List<Question>,
    val pdfBackground: Bitmap?,
    val textObjects: List<TextObject> = emptyList(),
    /** Monotonic token so re-loading the same page still refreshes the view. */
    val loadToken: Long
)

/** Mutations the ViewModel pushes INTO the canvas view (undo/redo/paste). */
sealed class CanvasMutation {
    data class Add(val strokes: List<Stroke>) : CanvasMutation()
    data class Remove(val strokes: List<Stroke>) : CanvasMutation()
    data class Replace(val strokes: List<Stroke>) : CanvasMutation()
}

sealed class CheckUiState {
    data object Hidden : CheckUiState()
    data class Loading(val questionId: String?, val action: CheckAction) : CheckUiState()
    data class Result(
        val questionId: String?,
        val action: CheckAction,
        val response: CheckResponse
    ) : CheckUiState()
}

class EditorViewModel(
    application: Application,
    private val notebookId: String
) : AndroidViewModel(application) {

    private val app = application as InkProofApp
    private val library = app.libraryRepository
    private val pageRepo = app.pageRepository

    val settings: StateFlow<Settings> = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val pages: StateFlow<List<PageEntity>> = library.observePages(notebookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _notebookTitle = MutableStateFlow("")
    val notebookTitle: StateFlow<String> = _notebookTitle

    private val _pageContent = MutableStateFlow<PageContent?>(null)
    val pageContent: StateFlow<PageContent?> = _pageContent

    private val _penStyle = MutableStateFlow(PenStyle())
    val penStyle: StateFlow<PenStyle> = _penStyle

    private val _canvasMutations = MutableSharedFlow<CanvasMutation>(extraBufferCapacity = 16)
    val canvasMutations: SharedFlow<CanvasMutation> = _canvasMutations

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private val _selection = MutableStateFlow<Pair<List<Stroke>, RectF?>>(emptyList<Stroke>() to null)
    val selection: StateFlow<Pair<List<Stroke>, RectF?>> = _selection

    private val _checkState = MutableStateFlow<CheckUiState>(CheckUiState.Hidden)
    val checkState: StateFlow<CheckUiState> = _checkState

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    private val undoRedo = UndoRedoStack()
    private var clipboard: List<Stroke> = emptyList()
    private var loadCounter = 0L

    init {
        viewModelScope.launch {
            _notebookTitle.value = library.notebook(notebookId)?.title.orEmpty()
            // Apply writing defaults.
            val s = app.settingsStore.settings.first()
            _penStyle.value = PenStyle(
                tool = ToolType.PEN,
                color = s.defaultPenColor,
                baseWidth = s.defaultPenWidth,
                pressureEnabled = s.pressureEnabled
            )
            val pageList = library.pagesFor(notebookId)
            // Continue from last page when enabled and the page still exists.
            val remembered = if (s.continueFromLastPage) {
                com.inkproof.app.data.settings.SettingsStore
                    .decodeLastPages(s.lastPageByNotebook)[notebookId]
            } else null
            val target = pageList.firstOrNull { it.id == remembered } ?: pageList.firstOrNull()
            target?.let { selectPage(it.id) }
        }
    }

    // ================= pages =================

    fun selectPage(pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val page = library.page(pageId) ?: return@launch
            val strokes = pageRepo.strokesForPage(pageId)
            val questions = pageRepo.questionsForPage(pageId)
            val textObjects = pageRepo.textObjectsForPage(pageId)
            val pdf = page.pdfPath?.let { path ->
                if (File(path).exists()) BitmapFactory.decodeFile(path) else null
            }
            undoRedo.clear()
            updateUndoState()
            clearSelectionState()
            _checkState.value = CheckUiState.Hidden
            _pageContent.value =
                PageContent(page, strokes, questions, pdf, textObjects, ++loadCounter)
            library.touchNotebook(notebookId)
            app.settingsStore.setLastPage(notebookId, pageId)
        }
    }

    fun movePage(pageId: String, delta: Int) {
        viewModelScope.launch(Dispatchers.IO) { library.movePage(pageId, delta) }
    }

    fun addPage(kind: PageKind = PageKind.NOTE, template: PageTemplate = PageTemplate.RULED) {
        viewModelScope.launch {
            val page = library.createPage(notebookId, kind, template)
            selectPage(page.id)
        }
    }

    fun deleteCurrentPage() {
        val current = _pageContent.value?.page ?: return
        viewModelScope.launch {
            val all = library.pagesFor(notebookId)
            if (all.size <= 1) {
                _toast.emit("A notebook needs at least one page.")
                return@launch
            }
            val index = all.indexOfFirst { it.id == current.id }
            library.deletePage(current.id)
            val remaining = library.pagesFor(notebookId)
            remaining.getOrNull((index - 1).coerceAtLeast(0))?.let { selectPage(it.id) }
        }
    }

    fun setTemplate(template: PageTemplate) {
        val current = _pageContent.value?.page ?: return
        viewModelScope.launch {
            library.setTemplate(current.id, template)
            selectPage(current.id)
        }
    }

    fun setPaperColor(color: Int) {
        val current = _pageContent.value?.page ?: return
        viewModelScope.launch {
            library.setPaperColor(current.id, color)
            selectPage(current.id)
        }
    }

    // ================= tools =================

    // Last non-eraser tool, restored by the stylus double-press toggle.
    private var rememberedTool: ToolType = ToolType.PEN

    fun setTool(tool: ToolType) {
        if (tool != ToolType.ERASER) rememberedTool = tool
        _penStyle.value = _penStyle.value.copy(tool = tool)
    }

    /** Stylus button double-press: eraser <-> previous tool. */
    fun stylusToggleEraser() {
        val result = com.inkproof.app.ink.StylusEraserToggle.toggle(
            current = _penStyle.value.tool,
            remembered = rememberedTool
        )
        rememberedTool = result.remembered
        _penStyle.value = _penStyle.value.copy(tool = result.tool)
    }

    fun setColor(color: Int) {
        _penStyle.value = _penStyle.value.copy(color = color)
    }

    fun setEraserRadius(radius: Float) {
        viewModelScope.launch { app.settingsStore.setEraserRadius(radius) }
    }

    fun setWidth(width: Float) {
        _penStyle.value = _penStyle.value.copy(baseWidth = width)
    }

    // ================= stroke persistence (async, never blocks drawing) =====

    fun onStrokeCommitted(stroke: Stroke) {
        undoRedo.push(CanvasOp.Add(listOf(stroke)))
        updateUndoState()
        viewModelScope.launch(Dispatchers.IO) { pageRepo.addStroke(stroke) }
    }

    fun onStrokesErased(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        undoRedo.push(CanvasOp.Remove(strokes))
        updateUndoState()
        viewModelScope.launch(Dispatchers.IO) { pageRepo.deleteStrokes(strokes) }
    }

    fun onStrokesMoved(before: List<Stroke>, after: List<Stroke>) {
        undoRedo.push(CanvasOp.Replace(before, after))
        updateUndoState()
        viewModelScope.launch(Dispatchers.IO) { pageRepo.updateStrokes(after) }
    }

    fun onSelectionChanged(strokes: List<Stroke>, bounds: RectF?) {
        _selection.value = strokes to bounds
    }

    private fun clearSelectionState() {
        _selection.value = emptyList<Stroke>() to null
    }

    // ================= undo / redo =================

    fun undo() {
        val op = undoRedo.undo() ?: return
        updateUndoState()
        viewModelScope.launch(Dispatchers.IO) {
            when (op) {
                is CanvasOp.Add -> {
                    pageRepo.deleteStrokes(op.strokes)
                    _canvasMutations.emit(CanvasMutation.Remove(op.strokes))
                }
                is CanvasOp.Remove -> {
                    pageRepo.addStrokes(op.strokes)
                    _canvasMutations.emit(CanvasMutation.Add(op.strokes))
                }
                is CanvasOp.Replace -> {
                    pageRepo.updateStrokes(op.before)
                    _canvasMutations.emit(CanvasMutation.Replace(op.before))
                }
            }
        }
    }

    fun redo() {
        val op = undoRedo.redo() ?: return
        updateUndoState()
        viewModelScope.launch(Dispatchers.IO) {
            when (op) {
                is CanvasOp.Add -> {
                    pageRepo.addStrokes(op.strokes)
                    _canvasMutations.emit(CanvasMutation.Add(op.strokes))
                }
                is CanvasOp.Remove -> {
                    pageRepo.deleteStrokes(op.strokes)
                    _canvasMutations.emit(CanvasMutation.Remove(op.strokes))
                }
                is CanvasOp.Replace -> {
                    pageRepo.updateStrokes(op.after)
                    _canvasMutations.emit(CanvasMutation.Replace(op.after))
                }
            }
        }
    }

    private fun updateUndoState() {
        _canUndo.value = undoRedo.canUndo
        _canRedo.value = undoRedo.canRedo
    }

    // ================= on-demand recognition =================

    /** Result of SELECT → RECOGNIZE MATH. Null = no dialog. */
    data class RecognitionUi(
        val loading: Boolean,
        val text: String = "",
        val confidence: Float = 0f,
        val uncertain: Boolean = false
    )

    private val _recognition = MutableStateFlow<RecognitionUi?>(null)
    val recognition: StateFlow<RecognitionUi?> = _recognition

    /**
     * Recognize ONLY the lasso-selected strokes, locally. Never called
     * automatically — never while writing — and never replaces the ink.
     */
    fun recognizeSelection() {
        val (strokes, _) = _selection.value
        if (strokes.isEmpty()) {
            viewModelScope.launch { _toast.emit("Select some handwriting first") }
            return
        }
        _recognition.value = RecognitionUi(loading = true)
        viewModelScope.launch {
            val result = runCatching {
                app.recognizer(settings.value.mockMode, settings.value.backendUrl)
                    .recognize(strokes)
            }.getOrNull()
            if (result == null || result.lines.isEmpty()) {
                _recognition.value = RecognitionUi(
                    loading = false, text = "", confidence = 0f, uncertain = true
                )
                return@launch
            }
            val threshold = settings.value.recognitionConfidenceThreshold
            _recognition.value = RecognitionUi(
                loading = false,
                text = result.lines.joinToString("\n") { it.text },
                confidence = result.confidence,
                uncertain = result.uncertain || result.confidence < threshold
            )
        }
    }

    fun dismissRecognition() {
        _recognition.value = null
    }

    /** ACCEPT: place the recognized text next to the selection as a
     *  text object. The original handwriting is never touched. */
    fun acceptRecognition(text: String) {
        val content = _pageContent.value ?: return
        val (strokes, _) = _selection.value
        val bounds = strokes.firstOrNull()?.let {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = 0f
            for (s in strokes) {
                val b = s.bounds()
                if (b.left < minX) minX = b.left
                if (b.top < minY) minY = b.top
                if (b.right > maxX) maxX = b.right
            }
            Triple(minX, minY, maxX)
        }
        addOrUpdateText(
            TextObject(
                pageId = content.page.id,
                text = text,
                x = bounds?.third?.plus(24f) ?: 80f,
                y = bounds?.second ?: 80f,
                fontSize = 30f
            )
        )
        _recognition.value = null
    }

    // ================= copy / paste =================

    fun copySelection() {
        val (strokes, _) = _selection.value
        clipboard = strokes
        viewModelScope.launch { _toast.emit("${strokes.size} strokes copied") }
    }

    fun paste() {
        val pageId = _pageContent.value?.page?.id ?: return
        if (clipboard.isEmpty()) return
        val pasted = com.inkproof.app.model.ClipboardOps.cloneForPaste(clipboard, pageId)
        undoRedo.push(CanvasOp.Add(pasted))
        updateUndoState()
        viewModelScope.launch(Dispatchers.IO) {
            pageRepo.addStrokes(pasted)
            _canvasMutations.emit(CanvasMutation.Add(pasted))
        }
    }

    // ================= questions =================

    fun createQuestion(contentType: QuestionContentType, typedText: String?) {
        val content = _pageContent.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = pageRepo.questionsForPage(content.page.id)
            val top = (existing.maxOfOrNull { it.solutionBottom } ?: 60f) + 40f
            pageRepo.createQuestion(
                pageId = content.page.id,
                contentType = contentType,
                typedText = typedText,
                questionTop = top
            )
            refreshQuestions()
        }
    }

    /**
     * Create a Question whose statement is an imported image or the first
     * page of an imported PDF (§ imported regions become Question objects).
     * The media is copied into app storage and rendered inside the
     * question band; the solution area below stays normal handwriting.
     */
    fun createQuestionFromMedia(uri: android.net.Uri, isPdf: Boolean) {
        val content = _pageContent.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                if (isPdf) renderPdfFirstPage(uri)
                else app.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (bitmap == null) {
                _toast.emit(
                    if (isPdf) "Couldn't read that PDF." else "Couldn't read that image."
                )
                return@launch
            }
            val dir = java.io.File(app.filesDir, "question_media").apply { mkdirs() }
            val file = java.io.File(dir, "${newId()}.png")
            runCatching {
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
                }
            }.onFailure {
                _toast.emit("Couldn't save the imported file.")
                return@launch
            }

            // Scale the question band to the media's aspect ratio.
            val pageWidth = 1600f
            val availW = pageWidth - 72f
            val mediaHeight = (availW / bitmap.width * bitmap.height)
                .coerceIn(120f, 620f)
            val existing = pageRepo.questionsForPage(content.page.id)
            val top = (existing.maxOfOrNull { it.solutionBottom } ?: 60f) + 40f
            pageRepo.createQuestion(
                pageId = content.page.id,
                contentType = if (isPdf) QuestionContentType.PDF else QuestionContentType.IMAGE,
                mediaPath = file.absolutePath,
                questionTop = top,
                questionHeight = mediaHeight + 110f
            )
            refreshQuestions()
            _toast.emit("Question added from ${if (isPdf) "PDF" else "image"}")
        }
    }

    private fun renderPdfFirstPage(uri: android.net.Uri): Bitmap? =
        app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    val scale = (1400f / page.width).coerceIn(1f, 4f)
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt(),
                        (page.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(
                        bmp, null, null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    bmp
                }
            }
        }

    fun updateQuestionText(questionId: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            pageRepo.updateQuestionText(questionId, text)
            refreshQuestions()
        }
    }

    fun deleteQuestion(questionId: String) {
        val content = _pageContent.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            pageRepo.deleteQuestion(questionId)
            // Strokes of the question were deleted too: full reload.
            selectPage(content.page.id)
        }
    }

    private suspend fun refreshQuestions() {
        val content = _pageContent.value ?: return
        // Re-read strokes too so recently drawn ink is never wiped from the view.
        _pageContent.value = content.copy(
            strokes = pageRepo.strokesForPage(content.page.id),
            questions = pageRepo.questionsForPage(content.page.id),
            loadToken = ++loadCounter
        )
    }

    // ================= text objects =================

    fun addOrUpdateText(obj: TextObject) {
        val content = _pageContent.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            pageRepo.upsertTextObject(obj)
            refreshTextObjects(content.page.id)
        }
    }

    fun deleteText(id: String) {
        val content = _pageContent.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            pageRepo.deleteTextObject(id)
            refreshTextObjects(content.page.id)
        }
    }

    private suspend fun refreshTextObjects(pageId: String) {
        val content = _pageContent.value ?: return
        if (content.page.id != pageId) return
        // Re-read strokes too: content.strokes may be stale (ink drawn since
        // the page load is persisted asynchronously) and must not be wiped.
        _pageContent.value = content.copy(
            strokes = pageRepo.strokesForPage(pageId),
            textObjects = pageRepo.textObjectsForPage(pageId),
            loadToken = ++loadCounter
        )
    }

    // ================= CHECK MY WORK / SOLVE =================

    private fun engine() = app.checkEngine(
        mockMode = settings.value.mockMode,
        confidenceThreshold = settings.value.recognitionConfidenceThreshold,
        backendUrl = settings.value.backendUrl
    )

    fun checkQuestion(questionId: String, action: CheckAction) {
        _checkState.value = CheckUiState.Loading(questionId, action)
        viewModelScope.launch(Dispatchers.IO) {
            val response = engine().checkQuestion(questionId, action)
            _checkState.value = CheckUiState.Result(questionId, action, response)
        }
    }

    fun checkSelection(action: CheckAction) {
        val pageId = _pageContent.value?.page?.id ?: return
        val (strokes, _) = _selection.value
        _checkState.value = CheckUiState.Loading(null, action)
        viewModelScope.launch(Dispatchers.IO) {
            val response = engine().checkSelection(pageId, strokes, action)
            _checkState.value = CheckUiState.Result(null, action, response)
        }
    }

    fun dismissCheck() {
        _checkState.value = CheckUiState.Hidden
    }

    // ================= export =================

    fun exportPdf() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = app.pdfExporter.export(notebookId)
            _toast.emit(
                if (file != null) "Exported to ${file.name}" else "Export failed."
            )
        }
    }
}
