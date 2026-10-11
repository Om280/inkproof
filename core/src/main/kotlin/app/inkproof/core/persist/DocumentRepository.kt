package app.inkproof.core.persist

import app.inkproof.core.model.AppSettings
import app.inkproof.core.model.Clock
import app.inkproof.core.model.DOCUMENT_FORMAT_VERSION
import app.inkproof.core.model.Folder
import app.inkproof.core.model.Ids
import app.inkproof.core.model.InkJson
import app.inkproof.core.model.LibraryIndex
import app.inkproof.core.model.NotebookMeta
import app.inkproof.core.model.NotebookUiState
import app.inkproof.core.model.Page
import app.inkproof.core.model.PageTemplate
import app.inkproof.core.model.PaperColor
import app.inkproof.core.model.Question
import app.inkproof.core.model.SystemClock
import app.inkproof.core.model.TextItem
import app.inkproof.core.model.UuidIds
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DocumentRepository(
    val root: File,
    private val ids: Ids = UuidIds,
    private val clock: Clock = SystemClock,
) {
    private val indexFile = File(root, "library.json")
    private val settingsFile = File(root, "settings.json")

    init {
        root.mkdirs()
        File(root, "notebooks").mkdirs()
        File(root, "assets").mkdirs()
        File(root, "thumbs").mkdirs()
    }

    fun loadLibrary(): LibraryIndex {
        if (!indexFile.exists()) return LibraryIndex()
        return try {
            InkJson.decodeFromString(LibraryIndex.serializer(), indexFile.readText())
        } catch (e: Exception) {
            throw StorageException("Library index is unreadable.", e)
        }
    }

    fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return try {
            InkJson.decodeFromString(AppSettings.serializer(), settingsFile.readText())
        } catch (_: Exception) {
            AppSettings()
        }
    }

    fun saveSettings(settings: AppSettings) {
        atomicWrite(settingsFile, InkJson.encodeToString(settings))
    }

    fun createFolder(name: String, parentId: String? = null): Folder {
        val now = clock.now()
        val folder = Folder(ids.newId(), name.trim().ifBlank { "Folder" }, parentId, now, now)
        val lib = loadLibrary()
        saveLibrary(lib.copy(folders = lib.folders + folder))
        return folder
    }

    fun renameFolder(id: String, name: String) {
        val lib = loadLibrary()
        saveLibrary(
            lib.copy(
                folders = lib.folders.map {
                    if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, updatedAt = clock.now()) else it
                },
            ),
        )
    }

    fun deleteFolder(id: String) {
        val lib = loadLibrary()
        val folder = lib.folders.firstOrNull { it.id == id } ?: return
        val folders = lib.folders
            .filter { it.id != id }
            .map { if (it.parentId == id) it.copy(parentId = folder.parentId) else it }
        val notebooks = lib.notebooks.map {
            if (it.folderId == id) it.copy(folderId = folder.parentId) else it
        }
        saveLibrary(lib.copy(folders = folders, notebooks = notebooks))
    }

    fun createNotebook(name: String, folderId: String? = null): NotebookMeta {
        val now = clock.now()
        val id = ids.newId()
        val page = blankPage(ids.newId(), id, PaperColor.WARM_WHITE, PageTemplate.BLANK, now)
        val meta = NotebookMeta(
            id = id,
            name = name.trim().ifBlank { "Untitled notebook" },
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            pageOrder = listOf(page.id),
            coverPageId = page.id,
        )
        writePage(page)
        val lib = loadLibrary()
        saveLibrary(lib.copy(notebooks = lib.notebooks + meta))
        return meta
    }

    fun blankPage(
        id: String,
        notebookId: String,
        paper: PaperColor,
        template: PageTemplate,
        now: Long,
    ): Page = Page(
        id = id,
        notebookId = notebookId,
        paper = paper,
        template = template,
        objects = emptyList(),
        questions = emptyList(),
        createdAt = now,
        updatedAt = now,
    )

    fun renameNotebook(id: String, name: String) {
        updateMeta(id) { it.copy(name = name.trim().ifBlank { it.name }, updatedAt = clock.now()) }
    }

    fun setFavorite(id: String, favorite: Boolean) {
        updateMeta(id) { it.copy(favorite = favorite, updatedAt = clock.now()) }
    }

    fun moveNotebook(id: String, folderId: String?) {
        updateMeta(id) { it.copy(folderId = folderId, updatedAt = clock.now()) }
    }

    fun deleteNotebook(id: String) {
        val lib = loadLibrary()
        val meta = lib.notebooks.firstOrNull { it.id == id }
        saveLibrary(lib.copy(notebooks = lib.notebooks.filter { it.id != id }))
        File(root, "notebooks/$id").deleteRecursively()
        meta?.pageOrder?.forEach { File(root, "thumbs/$it.png").delete() }
    }

    fun duplicateNotebook(id: String): NotebookMeta {
        val source = requireMeta(id)
        val pages = source.pageOrder.map { loadPage(id, it) }
        val now = clock.now()
        val newId = ids.newId()
        val idMap = HashMap<String, String>()
        val newPages = pages.map { page ->
            val newPageId = ids.newId()
            idMap[page.id] = newPageId
            val objectIds = page.objects.associate { it.id to ids.newId() }
            val questionIds = page.questions.associate { it.id to ids.newId() }
            page.copy(
                id = newPageId,
                notebookId = newId,
                objects = page.objects.map { obj ->
                    val nid = objectIds.getValue(obj.id)
                    when (obj) {
                        is app.inkproof.core.model.Stroke -> obj.copy(
                            id = nid,
                            questionId = obj.questionId?.let { questionIds[it] },
                        )
                        is app.inkproof.core.model.ShapeItem -> obj.copy(
                            id = nid,
                            questionId = obj.questionId?.let { questionIds[it] },
                        )
                        is TextItem -> obj.copy(id = nid, questionId = obj.questionId?.let { questionIds[it] })
                        is app.inkproof.core.model.ImageItem -> {
                            val asset = obj.assetId.let { old ->
                                val copied = ids.newId()
                                copyAsset(old, copied)
                                copied
                            }
                            obj.copy(id = nid, assetId = asset, questionId = obj.questionId?.let { questionIds[it] })
                        }
                    }
                },
                questions = page.questions.map { q ->
                    q.copy(id = questionIds.getValue(q.id))
                },
                backgroundAssetId = page.backgroundAssetId?.let { old ->
                    val copied = ids.newId()
                    copyAsset(old, copied)
                    copied
                },
                createdAt = now,
                updatedAt = now,
            )
        }
        newPages.forEach { writePage(it) }
        val meta = source.copy(
            id = newId,
            name = source.name + " copy",
            favorite = false,
            createdAt = now,
            updatedAt = now,
            pageOrder = newPages.map { it.id },
            coverPageId = newPages.firstOrNull()?.id,
        )
        val lib = loadLibrary()
        saveLibrary(lib.copy(notebooks = lib.notebooks + meta))
        return meta
    }

    fun requireMeta(id: String): NotebookMeta =
        loadLibrary().notebooks.firstOrNull { it.id == id }
            ?: throw StorageException("Notebook $id was not found.")

    fun loadPage(notebookId: String, pageId: String): Page {
        val file = pageFile(notebookId, pageId)
        if (!file.exists()) throw StorageException("Page $pageId was not found.")
        return try {
            InkJson.decodeFromString(Page.serializer(), file.readText())
        } catch (e: Exception) {
            throw StorageException("Page $pageId is unreadable.", e)
        }
    }

    fun savePage(page: Page) {
        writePage(page)
        updateMeta(page.notebookId) { meta ->
            val blob = rebuildBlob(meta, page)
            meta.copy(updatedAt = clock.now(), searchBlob = blob, coverPageId = meta.coverPageId ?: page.id)
        }
    }

    fun saveSnapshot(meta: NotebookMeta, pages: List<Page>) {
        pages.forEach { writePage(it) }
        prunePages(meta.id, meta.pageOrder.toSet())
        val blob = pages.flatMap { page ->
            page.questions.map { it.promptText } + page.objects.filterIsInstance<TextItem>().map { it.text }
        }.joinToString(" ").take(4000)
        val stored = meta.copy(updatedAt = clock.now(), searchBlob = blob.ifBlank { meta.searchBlob })
        updateMeta(stored.id) { stored }
    }

    fun createPage(notebookId: String, afterPageId: String? = null): Page {
        val meta = requireMeta(notebookId)
        val now = clock.now()
        val page = blankPage(ids.newId(), notebookId, meta.defaultPaper, meta.defaultTemplate, now)
        writePage(page)
        val order = meta.pageOrder.toMutableList()
        val index = afterPageId?.let { order.indexOf(it) } ?: -1
        if (index >= 0) order.add(index + 1, page.id) else order.add(page.id)
        updateMeta(notebookId) { it.copy(pageOrder = order, updatedAt = now) }
        return page
    }

    fun deletePage(notebookId: String, pageId: String): Boolean {
        val meta = requireMeta(notebookId)
        if (pageId !in meta.pageOrder) return false
        if (meta.pageOrder.size <= 1) return false
        val order = meta.pageOrder.filter { it != pageId }
        updateMeta(notebookId) {
            it.copy(
                pageOrder = order,
                coverPageId = if (it.coverPageId == pageId) order.firstOrNull() else it.coverPageId,
                updatedAt = clock.now(),
            )
        }
        pageFile(notebookId, pageId).delete()
        File(root, "thumbs/$pageId.png").delete()
        return true
    }

    fun reorder(notebookId: String, pageId: String, direction: Int) {
        val meta = requireMeta(notebookId)
        val order = meta.pageOrder.toMutableList()
        val index = order.indexOf(pageId)
        if (index < 0) return
        val target = (index + direction).coerceIn(0, order.lastIndex)
        if (target == index) return
        val id = order.removeAt(index)
        order.add(target, id)
        updateMeta(notebookId) { it.copy(pageOrder = order, updatedAt = clock.now()) }
    }

    fun loadUi(notebookId: String): NotebookUiState {
        val file = File(root, "notebooks/$notebookId/ui.json")
        if (!file.exists()) return NotebookUiState()
        return try {
            InkJson.decodeFromString(NotebookUiState.serializer(), file.readText())
        } catch (_: Exception) {
            NotebookUiState()
        }
    }

    fun saveUi(notebookId: String, state: NotebookUiState) {
        atomicWrite(File(root, "notebooks/$notebookId/ui.json"), InkJson.encodeToString(state))
    }

    fun thumbnailFile(pageId: String): File = File(root, "thumbs/$pageId.png")

    fun assetFile(assetId: String): File = File(root, "assets/$assetId.bin")

    fun saveAsset(assetId: String, bytes: ByteArray, mime: String, name: String) {
        val file = assetFile(assetId)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        atomicWrite(
            File(root, "assets/$assetId.meta.json"),
            InkJson.encodeToString(AssetMeta(mime, name)),
        )
    }

    fun loadAsset(assetId: String): ByteArray? {
        val file = assetFile(assetId)
        return if (file.exists()) file.readBytes() else null
    }

    private fun copyAsset(from: String, to: String) {
        val src = assetFile(from)
        if (!src.exists()) return
        src.copyTo(assetFile(to), overwrite = true)
        val meta = File(root, "assets/$from.meta.json")
        if (meta.exists()) meta.copyTo(File(root, "assets/$to.meta.json"), overwrite = true)
    }

    private fun rebuildBlob(meta: NotebookMeta, page: Page): String {
        val extra = (page.questions.map { it.promptText } + page.objects.filterIsInstance<TextItem>().map { it.text })
            .joinToString(" ")
        return (meta.searchBlob + " " + extra).trim().take(4000)
    }

    private fun updateMeta(id: String, block: (NotebookMeta) -> NotebookMeta) {
        val lib = loadLibrary()
        saveLibrary(lib.copy(notebooks = lib.notebooks.map { if (it.id == id) block(it) else it }))
    }

    private fun writePage(page: Page) {
        atomicWrite(pageFile(page.notebookId, page.id), InkJson.encodeToString(page))
    }

    private fun prunePages(notebookId: String, keep: Set<String>) {
        val dir = File(root, "notebooks/$notebookId/pages")
        dir.listFiles()?.forEach { file ->
            if (file.extension == "json" && file.nameWithoutExtension !in keep) file.delete()
        }
    }

    private fun pageFile(notebookId: String, pageId: String): File =
        File(root, "notebooks/$notebookId/pages/$pageId.json")

    private fun saveLibrary(index: LibraryIndex) {
        atomicWrite(indexFile, InkJson.encodeToString(index.copy(formatVersion = DOCUMENT_FORMAT_VERSION)))
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

@Serializable
data class AssetMeta(val mime: String, val name: String)

fun pageIsBlank(page: Page): Boolean =
    page.objects.isEmpty() && page.questions.isEmpty() && page.backgroundAssetId == null

fun questionSecrets(page: Page): Map<String, String> =
    page.questions.associate { it.id to it.promptText }
