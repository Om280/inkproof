package com.inkproof.app

import com.inkproof.app.pdf.ImportClassifier
import com.inkproof.app.pdf.ImportClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportClassifierTest {

    @Test
    fun `pdf by mime and extension`() {
        assertEquals(Kind.PDF, ImportClassifier.classify("application/pdf", null).kind)
        assertEquals(Kind.PDF, ImportClassifier.classify(null, "homework.PDF").kind)
    }

    @Test
    fun `images are supported, svg is rejected with reason`() {
        assertEquals(Kind.IMAGE, ImportClassifier.classify("image/png", "a.png").kind)
        assertEquals(Kind.IMAGE, ImportClassifier.classify(null, "scan.webp").kind)
        assertEquals(Kind.IMAGE, ImportClassifier.classify("image/bmp", null).kind)
        val svg = ImportClassifier.classify("image/svg+xml", "fig.svg")
        assertEquals(Kind.UNSUPPORTED, svg.kind)
        assertTrue(svg.reason.contains("SVG"))
    }

    @Test
    fun `text family routes correctly`() {
        assertEquals(Kind.TEXT, ImportClassifier.classify("text/plain", "notes.txt").kind)
        assertEquals(Kind.TEXT, ImportClassifier.classify(null, "README.md").kind)
        assertEquals(Kind.HTML, ImportClassifier.classify("text/html", "page.html").kind)
        assertEquals(Kind.CSV, ImportClassifier.classify("text/csv", "marks.csv").kind)
        assertEquals(Kind.CSV, ImportClassifier.classify(null, "data.tsv").kind)
    }

    @Test
    fun `office formats rejected with export-as-pdf guidance`() {
        listOf(
            "report.docx" to "Word",
            "sheet.xlsx" to "Excel",
            "slides.pptx" to "PowerPoint",
            "old.doc" to "Word",
            "letter.rtf" to "RTF"
        ).forEach { (name, label) ->
            val r = ImportClassifier.classify(null, name)
            assertEquals("for $name", Kind.UNSUPPORTED, r.kind)
            assertTrue("for $name", r.reason.contains(label))
            assertTrue("for $name", r.reason.contains("PDF"))
        }
        val byMime = ImportClassifier.classify(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null
        )
        assertEquals(Kind.UNSUPPORTED, byMime.kind)
    }

    @Test
    fun `unknown binary rejected with supported-formats hint`() {
        val r = ImportClassifier.classify("application/octet-stream", "data.bin")
        assertEquals(Kind.UNSUPPORTED, r.kind)
        assertTrue(r.reason.contains("PDF"))
    }

    @Test
    fun `mime parameters and case are tolerated`() {
        assertEquals(
            Kind.TEXT,
            ImportClassifier.classify("Text/Plain; charset=UTF-8", null).kind
        )
    }
}
