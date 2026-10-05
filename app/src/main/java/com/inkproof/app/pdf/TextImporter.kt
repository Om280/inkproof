package com.inkproof.app.pdf

import android.content.Context
import android.net.Uri
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.TextObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports plain-text-ish formats (TXT, MD, HTML, CSV, JSON) into a new
 * notebook as editable [TextObject]s — one block per page, paginated by
 * line count. HTML is stripped to text; CSV columns are joined readably.
 */
class TextImporter(
    private val context: Context,
    private val library: LibraryRepository,
    private val pages: PageRepository
) {
    companion object {
        /** Cap raw input so a giant log file can't lock the UI or DB. */
        const val MAX_BYTES = 500_000
        const val LINES_PER_PAGE = 46
        const val MAX_LINE_CHARS = 110
    }

    suspend fun import(
        uri: Uri,
        notebookTitle: String,
        kind: ImportClassifier.Kind
    ): String? = withContext(Dispatchers.IO) {
        try {
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().let { bytes ->
                    if (bytes.size > MAX_BYTES) bytes.copyOf(MAX_BYTES) else bytes
                }
            }?.toString(Charsets.UTF_8) ?: return@withContext null

            val text = when (kind) {
                ImportClassifier.Kind.HTML -> stripHtml(raw)
                ImportClassifier.Kind.CSV -> formatCsv(raw)
                else -> raw
            }.trim()
            if (text.isEmpty()) return@withContext null

            val lines = wrapLines(text)
            val pageChunks = lines.chunked(LINES_PER_PAGE).ifEmpty { listOf(listOf("")) }

            val notebook = library.createNotebook(
                title = notebookTitle,
                firstPageKind = PageKind.NOTE,
                firstPageTemplate = PageTemplate.RULED
            )
            val existing = library.pagesFor(notebook.id)
            pageChunks.forEachIndexed { index, chunk ->
                val page = if (index == 0 && existing.isNotEmpty()) existing.first()
                else library.createPage(
                    notebookId = notebook.id,
                    kind = PageKind.NOTE,
                    template = PageTemplate.RULED
                )
                pages.upsertTextObject(
                    TextObject(
                        pageId = page.id,
                        text = chunk.joinToString("\n"),
                        x = 60f,
                        y = 80f,
                        widthPts = 1480f,
                        fontSize = 30f
                    )
                )
            }
            notebook.id
        } catch (e: Exception) {
            null
        }
    }

    /** Remove tags/scripts/styles and decode the most common entities. */
    fun stripHtml(html: String): String =
        html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /** Join CSV columns with a readable separator, row per line. */
    fun formatCsv(csv: String): String =
        csv.lineSequence()
            .map { line -> line.split(',').joinToString("  |  ") { it.trim().trim('"') } }
            .joinToString("\n")

    /** Soft-wrap very long lines so they fit the page width. */
    fun wrapLines(text: String): List<String> {
        val out = ArrayList<String>()
        text.lines().forEach { line ->
            if (line.length <= MAX_LINE_CHARS) {
                out.add(line)
            } else {
                var rest = line
                while (rest.length > MAX_LINE_CHARS) {
                    val cut = rest.lastIndexOf(' ', MAX_LINE_CHARS)
                        .let { if (it <= 0) MAX_LINE_CHARS else it }
                    out.add(rest.substring(0, cut))
                    rest = rest.substring(cut).trimStart()
                }
                if (rest.isNotEmpty()) out.add(rest)
            }
        }
        return out
    }
}
