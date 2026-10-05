package com.inkproof.app.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Imports an image (gallery/photo picker): the image becomes the background
 * of a new annotatable page, exactly like an imported PDF page — ink goes on
 * top as normal vector strokes, so lasso/eraser/CHECK all work.
 */
class ImageImporter(
    private val context: Context,
    private val library: LibraryRepository
) {
    suspend fun import(uri: Uri, notebookTitle: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "images")
            dir.mkdirs()
            val local = File(dir, "${newId()}.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(local).use { output -> input.copyTo(output) }
            } ?: return@withContext null

            // Read dimensions only; the canvas decodes lazily when needed.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(local.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                local.delete()
                return@withContext null
            }

            val pageWidth = 1600f
            val pageHeight = (pageWidth * opts.outHeight / opts.outWidth)
                .coerceIn(400f, 6000f)

            val notebook = library.createNotebook(
                title = notebookTitle,
                firstPageKind = PageKind.PDF,
                firstPageTemplate = PageTemplate.BLANK
            )
            library.pagesFor(notebook.id).forEach { library.deletePage(it.id) }
            val page = library.createPage(
                notebookId = notebook.id,
                kind = PageKind.PDF,
                template = PageTemplate.BLANK,
                pdfPath = local.absolutePath
            )
            // Match the page aspect to the image.
            updatePageSize(page.id, pageWidth, pageHeight)
            notebook.id
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun updatePageSize(pageId: String, width: Float, height: Float) {
        val page = library.page(pageId) ?: return
        library.upsertPage(page.copy(widthPts = width, heightPts = height))
    }
}
