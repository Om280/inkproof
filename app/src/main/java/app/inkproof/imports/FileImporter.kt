package app.inkproof.imports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import app.inkproof.core.imports.ImportKind
import app.inkproof.core.imports.classifyImport
import app.inkproof.core.imports.extractByKind
import app.inkproof.core.imports.parseSimpleSvg
import app.inkproof.core.model.ShapeKind
import app.inkproof.core.model.TextItem
import app.inkproof.core.model.UuidIds
import app.inkproof.core.persist.DocumentRepository
import app.inkproof.core.shape.shapeFromDrag
import java.io.ByteArrayOutputStream

sealed class ImportOutcome {
    data class OpenNotebook(val id: String, val note: String) : ImportOutcome()
    data class InsertText(val text: String, val note: String) : ImportOutcome()
    data class InsertImage(val assetId: String, val width: Float, val height: Float, val note: String) : ImportOutcome()
    data class Message(val text: String) : ImportOutcome()
}

class FileImporter(
    private val context: Context,
    private val repo: DocumentRepository,
) {
    fun import(uri: Uri, intoOpenPage: Boolean): ImportOutcome {
        val name = queryName(uri)
        val mime = context.contentResolver.getType(uri)
        val kind = classifyImport(name, mime)
        return try {
            when (kind) {
                ImportKind.PDF -> importPdf(uri, name)
                ImportKind.IMAGE -> importImage(uri, name, intoOpenPage)
                ImportKind.SVG -> importSvg(uri, name)
                ImportKind.LEGACY_OFFICE -> ImportOutcome.Message(
                    "Binary ${name.substringAfterLast('.', name)} is not edited in place. Save it as DOCX, XLSX, PPTX, PDF, or TXT.",
                )
                ImportKind.UNSUPPORTED -> ImportOutcome.Message(
                    "Unsupported file. InkProof reads PDF, PNG, JPG, WEBP, BMP, TXT, Markdown, HTML, RTF, CSV, DOCX, XLSX, PPTX, and simple SVG.",
                )
                else -> importTextLike(uri, name, mime, intoOpenPage)
            }
        } catch (e: Exception) {
            ImportOutcome.Message("Could not import $name (${e.javaClass.simpleName}). Nothing was changed.")
        }
    }

    private fun importPdf(uri: Uri, name: String): ImportOutcome {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return ImportOutcome.Message("Could not open the PDF.")
        try {
            val pdf = PdfRenderer(pfd)
            try {
                if (pdf.pageCount == 0) return ImportOutcome.Message("The PDF has no pages.")
                val count = minOf(pdf.pageCount, 40)
                val notebook = repo.createNotebook(name.substringBeforeLast('.').ifBlank { "Imported PDF" })
                val ids = mutableListOf(notebook.pageOrder.single())
                for (i in 1 until count) ids.add(repo.createPage(notebook.id, ids.last()).id)
                for (i in 0 until count) {
                    val pdfPage = pdf.openPage(i)
                    try {
                        val width = 1400
                        val height = (width.toFloat() * pdfPage.height / pdfPage.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val assetId = UuidIds.newId()
                        repo.saveAsset(assetId, bitmap.pngBytes(), "image/png", "page-$i.png")
                        bitmap.recycle()
                        val page = repo.loadPage(notebook.id, ids[i])
                        repo.savePage(page.copy(backgroundAssetId = assetId, backgroundKind = "pdf"))
                    } finally {
                        pdfPage.close()
                    }
                }
                val note = if (pdf.pageCount > count) {
                    "Imported the first $count of ${pdf.pageCount} pages as a new notebook. Annotate, then export."
                } else {
                    "Imported $count PDF page(s) as a new notebook. Ink stays separate from the background."
                }
                ImportOutcome.OpenNotebook(notebook.id, note)
            } finally {
                pdf.close()
            }
        } finally {
            pfd.close()
        }
    }

    private fun importImage(uri: Uri, name: String, intoOpenPage: Boolean): ImportOutcome {
        val bytes = readBytes(uri)
        if (bytes.isEmpty()) return ImportOutcome.Message("The image was empty.")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return ImportOutcome.Message("Could not decode $name.")
        val scaled = scaleDown(bitmap, 1800)
        val assetId = UuidIds.newId()
        repo.saveAsset(assetId, scaled.pngBytes(), "image/png", name)
        val width = scaled.width.toFloat().coerceAtMost(1000f)
        val height = scaled.height * (width / scaled.width)
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        if (intoOpenPage) {
            return ImportOutcome.InsertImage(assetId, width, height, "Image placed on the current page.")
        }
        val notebook = repo.createNotebook(name.substringBeforeLast('.').ifBlank { "Imported image" })
        val page = repo.loadPage(notebook.id, notebook.pageOrder.single())
        repo.savePage(page.copy(backgroundAssetId = assetId, backgroundKind = "image"))
        return ImportOutcome.OpenNotebook(notebook.id, "Image imported as a page background.")
    }

    private fun importTextLike(uri: Uri, name: String, mime: String?, intoOpenPage: Boolean): ImportOutcome {
        val bytes = readBytes(uri)
        val extracted = try {
            extractByKind(name, mime, bytes)
        } catch (_: Exception) {
            return ImportOutcome.Message("Could not read $name. The file may be corrupt.")
        }
        if (extracted.text.isBlank()) {
            return ImportOutcome.Message(extracted.note.ifBlank { "No text could be read from $name." })
        }
        if (intoOpenPage) {
            return ImportOutcome.InsertText(extracted.text.take(4000), "Imported text onto the current page.")
        }
        val notebook = repo.createNotebook(name.substringBeforeLast('.').ifBlank { "Imported notes" })
        val page = repo.loadPage(notebook.id, notebook.pageOrder.single())
        repo.savePage(
            page.copy(objects = listOf(TextItem(UuidIds.newId(), extracted.text.take(4000), 48f, 64f, width = 1080f))),
        )
        return ImportOutcome.OpenNotebook(notebook.id, "Imported ${extracted.kind.name} as text. It is not a live office document.")
    }

    private fun importSvg(uri: Uri, name: String): ImportOutcome {
        val svg = readBytes(uri).toString(Charsets.UTF_8)
        val primitives = parseSimpleSvg(svg)
        val notebook = repo.createNotebook(name.substringBeforeLast('.').ifBlank { "Imported SVG" })
        val page = repo.loadPage(notebook.id, notebook.pageOrder.single())
        val shapes = primitives.map { prim ->
            val kind = when (prim.kind) {
                "circle" -> ShapeKind.CIRCLE
                "ellipse" -> ShapeKind.ELLIPSE
                "line" -> ShapeKind.LINE
                else -> ShapeKind.RECTANGLE
            }
            shapeFromDrag(UuidIds.newId(), kind, prim.x, prim.y, prim.x + prim.w, prim.y + prim.h, 0xFF1A1A1A, 2.4f)
        }
        val text = extractByKind(name, "image/svg+xml", svg.toByteArray()).text
        val objects = shapes + if (text.isNotBlank()) listOf(TextItem(UuidIds.newId(), text.take(2000), 40f, 40f)) else emptyList()
        repo.savePage(page.copy(objects = objects))
        return ImportOutcome.OpenNotebook(
            notebook.id,
            if (shapes.isEmpty()) "SVG had no simple shapes. Text was kept if present." else "Imported ${shapes.size} SVG shape(s).",
        )
    }

    private fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()

    private fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "import"
        }
        return uri.lastPathSegment ?: "import"
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val scale = maxSide.toFloat() / max
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }
}

private fun Bitmap.pngBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 90, out)
    return out.toByteArray()
}
