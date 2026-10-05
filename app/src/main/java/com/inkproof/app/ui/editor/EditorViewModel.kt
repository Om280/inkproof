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

    // ================= tools =================

    fun setTool(tool: ToolType) {
        _penStyle.value = _penStyle.value.copy(tool = tool)
    }

    fun setColor(color: Int) {
        _penStyle.value = _penStyle.value.copy(color = color)
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

    // ================= copy / paste =================

    fun copySelection() {
        val (strokes, _) = _selection.value
        clipboard = strokes
        viewModelScope.launch { _toast.emit("${strokes.size} strokes copied") }
    }

    fun paste() {
        val pageId = _pageContent.value?.page?.id ?: return
        if (clipboard.isEmpty()) return
        val pasted = clipboard.map { s ->
            s.translated(48f, 48f).copy(id = newId(), pageId = pageId)
        }
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
