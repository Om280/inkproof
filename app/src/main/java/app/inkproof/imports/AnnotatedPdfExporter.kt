package app.inkproof.imports

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import app.inkproof.core.model.ImageItem
import app.inkproof.core.model.PageLayout
import app.inkproof.core.persist.DocumentRepository
import app.inkproof.editor.InkPainter
import java.io.File
import java.io.FileOutputStream

class AnnotatedPdfExporter(private val repo: DocumentRepository) {
    fun export(notebookId: String, dest: File): File {
        val meta = repo.requireMeta(notebookId)
        if (meta.pageOrder.isEmpty()) error("This notebook has no pages to export.")
        val doc = PdfDocument()
        val layout = PageLayout()
        val pageWidth = 1400
        val pageHeight = (pageWidth * layout.pageHeight / layout.pageWidth).toInt()
        try {
            meta.pageOrder.forEachIndexed { index, pageId ->
                val page = repo.loadPage(notebookId, pageId)
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = doc.startPage(info)
                val images = HashMap<String, Bitmap>()
                fun load(id: String) {
                    val file = repo.assetFile(id)
                    if (!file.exists()) return
                    BitmapFactory.decodeFile(file.absolutePath)?.let { images[id] = it }
                }
                page.backgroundAssetId?.let { load(it) }
                page.objects.filterIsInstance<ImageItem>().forEach { load(it.assetId) }
                InkPainter.drawPage(pdfPage.canvas, page, layout, images, pageWidth.toFloat(), pageHeight.toFloat())
                images.values.forEach { it.recycle() }
                doc.finishPage(pdfPage)
            }
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return dest
    }
}
