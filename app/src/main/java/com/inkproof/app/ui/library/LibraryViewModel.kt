package com.inkproof.app.ui.library

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkproof.app.InkProofApp
import com.inkproof.app.data.db.NotebookEntity
import com.inkproof.app.ink.ThumbnailRenderer
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as InkProofApp
    private val library = app.libraryRepository
    private val pages = app.pageRepository

    val notebooks: StateFlow<List<NotebookEntity>> = library.observeNotebooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<NotebookEntity>?>(null)
    val searchResults: StateFlow<List<NotebookEntity>?> = _searchResults

    private val _navigateTo = MutableStateFlow<String?>(null)
    val navigateTo: StateFlow<String?> = _navigateTo

    fun consumeNavigation() { _navigateTo.value = null }

    fun refreshThumbnails(notebookList: List<NotebookEntity>) {
        viewModelScope.launch(Dispatchers.Default) {
            val map = HashMap<String, Bitmap>()
            for (nb in notebookList.take(40)) {
                val firstPage = library.pagesFor(nb.id).firstOrNull() ?: continue
                val strokes = pages.strokesForPage(firstPage.id)
                val template = runCatching { PageTemplate.valueOf(firstPage.template) }
                    .getOrDefault(PageTemplate.BLANK)
                map[nb.id] = ThumbnailRenderer.render(
                    strokes, template, firstPage.widthPts, firstPage.heightPts, 220
                )
            }
            _thumbnails.value = map
        }
    }

    fun createNote(title: String) {
        viewModelScope.launch {
            val nb = library.createNotebook(
                title = title.ifBlank { "Untitled note" },
                firstPageKind = PageKind.NOTE,
                firstPageTemplate = PageTemplate.RULED
            )
            _navigateTo.value = nb.id
        }
    }

    fun createMathQuestion(title: String) {
        viewModelScope.launch {
            val nb = library.createNotebook(
                title = title.ifBlank { "Math questions" },
                firstPageKind = PageKind.MATH_QUESTION,
                firstPageTemplate = PageTemplate.MATH_WORKSHEET
            )
            _navigateTo.value = nb.id
        }
    }

    fun importPdf(uri: Uri, title: String) {
        viewModelScope.launch {
            val id = app.pdfImporter.import(uri, title.ifBlank { "Imported PDF" })
            if (id != null) _navigateTo.value = id
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { library.renameNotebook(id, title) }
    }

    fun delete(id: String) {
        viewModelScope.launch { library.deleteNotebook(id) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { library.duplicateNotebook(id) }
    }

    fun toggleFavorite(notebook: NotebookEntity) {
        viewModelScope.launch { library.setFavorite(notebook.id, !notebook.favorite) }
    }

    fun search(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = null
            return
        }
        viewModelScope.launch {
            _searchResults.value = library.search(query)
        }
    }
}
