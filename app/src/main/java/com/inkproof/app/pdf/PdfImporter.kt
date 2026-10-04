package com.inkproof.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Imports a PDF: each source page becomes an InkProof PDF page whose
 * background is the rendered page image; ink annotation goes on top as
 * normal vector strokes.
 */
class PdfImporter(
    private val context: Context,
    private val library: LibraryRepository
) {
    suspend fun import(uri: Uri, notebookTitle: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "pdf/${newId()}")
            dir.mkdirs()
            val local = File(dir, "source.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(local).use { output -> input.copyTo(output) }
            } ?: return@withContext null

            val notebook = library.createNotebook(
                title = notebookTitle,
                firstPageKind = PageKind.PDF,
                firstPageTemplate = PageTemplate.BLANK
            )
            // createNotebook creates one page; remove it and add real PDF pages.
            library.pagesFor(notebook.id).forEach { library.deletePage(it.id) }

            ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        try {
                            val scale = 2f
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt(),
                                (page.height * scale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(
                                bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            val imageFile = File(dir, "page_$i.png")
                            FileOutputStream(imageFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                            }
                            bitmap.recycle()
                            library.createPage(
                                notebookId = notebook.id,
                                kind = PageKind.PDF,
                                template = PageTemplate.BLANK,
                                pdfPath = imageFile.absolutePath,
                                pdfPageIndex = i
                            )
                        } finally {
                            page.close()
                        }
                    }
                }
            }
            notebook.id
        } catch (e: Exception) {
            null
        }
    }
}
