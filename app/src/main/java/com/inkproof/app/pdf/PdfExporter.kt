package com.inkproof.app.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.ink.StrokeRenderer
import com.inkproof.app.ink.TemplateRenderer
import com.inkproof.app.model.PageTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Exports a notebook (including annotated PDF pages) to a flat PDF file. */
class PdfExporter(
    private val context: Context,
    private val library: LibraryRepository,
    private val pages: PageRepository
) {
    suspend fun export(notebookId: String): File? = withContext(Dispatchers.IO) {
        try {
            val notebook = library.notebook(notebookId) ?: return@withContext null
            val doc = PdfDocument()
            val notebookPages = library.pagesFor(notebookId)
            notebookPages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(
                    page.widthPts.toInt(), page.heightPts.toInt(), index + 1
                ).create()
                val pdfPage = doc.startPage(info)
                val canvas = pdfPage.canvas
                val template = runCatching { PageTemplate.valueOf(page.template) }
                    .getOrDefault(PageTemplate.BLANK)
                TemplateRenderer.draw(
                    canvas, template, page.widthPts, page.heightPts, page.paperColor
                )
                page.pdfPath?.let { path ->
                    BitmapFactory.decodeFile(path)?.let { bmp ->
                        canvas.drawBitmap(
                            bmp, null, RectF(0f, 0f, page.widthPts, page.heightPts), null
                        )
                        bmp.recycle()
                    }
                }
                for (stroke in pages.strokesForPage(page.id)) {
                    StrokeRenderer.draw(canvas, stroke)
                }
                doc.finishPage(pdfPage)
            }
            val outDir = File(context.filesDir, "exports")
            outDir.mkdirs()
            val safeTitle = notebook.title.replace(Regex("[^A-Za-z0-9 _-]"), "_")
            val outFile = File(outDir, "$safeTitle.pdf")
            FileOutputStream(outFile).use { doc.writeTo(it) }
            doc.close()
            outFile
        } catch (e: Exception) {
            null
        }
    }
}
