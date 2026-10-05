package com.inkproof.app.data.repo

import com.inkproof.app.data.db.FolderEntity
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.data.db.NotebookEntity
import com.inkproof.app.data.db.PageEntity
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.newId
import kotlinx.coroutines.flow.Flow

/**
 * Library management: workspace -> folders -> notebooks -> pages.
 * Everything is persisted immediately; nothing lives only in UI state.
 */
class LibraryRepository(private val db: InkProofDatabase) {

    fun observeNotebooks(): Flow<List<NotebookEntity>> = db.notebookDao().observeAll()
    fun observeFolders(): Flow<List<FolderEntity>> = db.folderDao().observeAll()
    fun observePages(notebookId: String): Flow<List<PageEntity>> =
        db.pageDao().observePages(notebookId)

    suspend fun notebook(id: String): NotebookEntity? = db.notebookDao().byId(id)
    suspend fun page(id: String): PageEntity? = db.pageDao().byId(id)
    suspend fun pagesFor(notebookId: String): List<PageEntity> = db.pageDao().pagesFor(notebookId)

    suspend fun createFolder(name: String): FolderEntity {
        val now = System.currentTimeMillis()
        val folder = FolderEntity(id = newId(), name = name, createdAt = now, updatedAt = now)
        db.folderDao().upsert(folder)
        return folder
    }

    /** Deleting a folder moves its notebooks back to the workspace root. */
    suspend fun deleteFolder(id: String) {
        db.notebookDao().clearFolder(id)
        db.folderDao().delete(id)
    }

    suspend fun renameFolder(id: String, name: String) {
        val folder = db.folderDao().byId(id) ?: return
        db.folderDao().upsert(folder.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    suspend fun moveNotebookToFolder(notebookId: String, folderId: String?) {
        db.notebookDao().moveToFolder(notebookId, folderId, System.currentTimeMillis())
    }

    suspend fun createNotebook(
        title: String,
        folderId: String? = null,
        coverColor: Int = 0xFF1A2238.toInt(),
        firstPageKind: PageKind = PageKind.NOTE,
        firstPageTemplate: PageTemplate = PageTemplate.RULED
    ): NotebookEntity {
        val now = System.currentTimeMillis()
        val notebook = NotebookEntity(
            id = newId(), folderId = folderId, title = title,
            coverColor = coverColor, createdAt = now, updatedAt = now
        )
        db.notebookDao().upsert(notebook)
        createPage(notebook.id, firstPageKind, firstPageTemplate)
        return notebook
    }

    suspend fun renameNotebook(id: String, title: String) {
        db.notebookDao().rename(id, title, System.currentTimeMillis())
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        db.notebookDao().setFavorite(id, favorite)
    }

    suspend fun touchNotebook(id: String) {
        db.notebookDao().touch(id, System.currentTimeMillis())
    }

    suspend fun deleteNotebook(id: String) {
        // Cascade delete: strokes/questions/objects -> pages -> notebook.
        db.maintenanceDao().deleteStrokesForNotebook(id)
        db.maintenanceDao().deleteQuestionsForNotebook(id)
        db.maintenanceDao().deleteTextForNotebook(id)
        db.maintenanceDao().deleteImagesForNotebook(id)
        db.maintenanceDao().deletePagesForNotebook(id)
        db.notebookDao().delete(id)
    }

    /** Duplicate a notebook: fresh IDs for notebook, every page and every stroke. */
    suspend fun duplicateNotebook(id: String): NotebookEntity? {
        val source = db.notebookDao().byId(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = newId(),
            title = source.title + " (copy)",
            createdAt = now,
            updatedAt = now
        )
        db.notebookDao().upsert(copy)
        for (page in db.pageDao().pagesFor(id)) {
            val newPageId = newId()
            db.pageDao().upsert(page.copy(id = newPageId, notebookId = copy.id, createdAt = now, updatedAt = now))
            val questionIdMap = HashMap<String, String>()
            for (q in db.questionDao().forPage(page.id)) {
                val newQid = newId()
                questionIdMap[q.id] = newQid
                db.questionDao().upsert(q.copy(id = newQid, pageId = newPageId, createdAt = now))
            }
            for (s in db.strokeDao().forPage(page.id)) {
                db.strokeDao().upsert(
                    s.copy(
                        id = newId(),
                        pageId = newPageId,
                        questionId = s.questionId?.let { questionIdMap[it] }
                    )
                )
            }
        }
        return copy
    }

    /** Creating a page always creates a brand new, empty, independent page. */
    suspend fun createPage(
        notebookId: String,
        kind: PageKind = PageKind.NOTE,
        template: PageTemplate = PageTemplate.RULED,
        pdfPath: String? = null,
        pdfPageIndex: Int = 0
    ): PageEntity {
        val now = System.currentTimeMillis()
        val index = db.pageDao().countFor(notebookId)
        val page = PageEntity(
            id = newId(),
            notebookId = notebookId,
            orderIndex = index,
            kind = kind.name,
            template = template.name,
            pdfPath = pdfPath,
            pdfPageIndex = pdfPageIndex,
            createdAt = now,
            updatedAt = now
        )
        db.pageDao().upsert(page)
        db.notebookDao().touch(notebookId, now)
        return page
    }

    /**
     * Move a page up (-1) or down (+1) in its notebook.
     * Order indices are re-normalized so they stay dense and consistent.
     */
    suspend fun movePage(pageId: String, delta: Int) {
        val page = db.pageDao().byId(pageId) ?: return
        val pages = db.pageDao().pagesFor(page.notebookId).toMutableList()
        val from = pages.indexOfFirst { it.id == pageId }
        if (from == -1) return
        val to = (from + delta).coerceIn(0, pages.lastIndex)
        if (to == from) return
        val moved = pages.removeAt(from)
        pages.add(to, moved)
        pages.forEachIndexed { index, p ->
            if (p.orderIndex != index) db.pageDao().setOrder(p.id, index)
        }
        db.notebookDao().touch(page.notebookId, System.currentTimeMillis())
    }

    /** Deleting a page removes the page AND all of its content. */
    suspend fun deletePage(pageId: String) {
        db.maintenanceDao().deleteStrokesForPage(pageId)
        db.maintenanceDao().deleteQuestionsForPage(pageId)
        db.maintenanceDao().deleteTextForPage(pageId)
        db.maintenanceDao().deleteImagesForPage(pageId)
        db.pageDao().delete(pageId)
    }

    suspend fun reorderPages(notebookId: String, orderedPageIds: List<String>) {
        orderedPageIds.forEachIndexed { index, pageId ->
            db.pageDao().setOrder(pageId, index)
        }
        db.notebookDao().touch(notebookId, System.currentTimeMillis())
    }

    /** Direct page update (used by importers to adjust page size). */
    suspend fun upsertPage(page: PageEntity) {
        db.pageDao().upsert(page)
    }

    /** Changing templates never destroys content. */
    suspend fun setTemplate(pageId: String, template: PageTemplate) {
        db.pageDao().setTemplate(pageId, template.name, System.currentTimeMillis())
    }

    suspend fun search(query: String): List<NotebookEntity> {
        if (query.isBlank()) return emptyList()
        val byTitle = db.notebookDao().search(query.trim())
        val byQuestion = db.questionDao().search(query.trim())
            .mapNotNull { q -> db.pageDao().byId(q.pageId)?.notebookId }
            .mapNotNull { db.notebookDao().byId(it) }
        return (byTitle + byQuestion).distinctBy { it.id }
    }
}
