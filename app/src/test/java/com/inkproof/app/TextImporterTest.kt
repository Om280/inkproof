package com.inkproof.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.pdf.TextImporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextImporterTest {

    private lateinit var db: InkProofDatabase
    private lateinit var importer: TextImporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = InkProofDatabase.inMemory(context)
        importer = TextImporter(context, LibraryRepository(db), PageRepository(db))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `stripHtml removes tags scripts and entities`() {
        val html = """
            <html><head><style>body{color:red}</style>
            <script>alert('x')</script></head>
            <body><h1>Quadratics</h1><p>Solve x&sup2; &amp; show work &lt;fast&gt;.</p></body></html>
        """.trimIndent()
        val text = importer.stripHtml(html)
        // All markup gone…
        assertFalse(text.contains("<h1>"))
        assertFalse(text.contains("</p>"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color:red"))
        // …but the readable content, including decoded entities, survives.
        assertTrue(text.contains("Quadratics"))
        assertTrue(text.contains("& show work"))
        assertTrue(text.contains("<fast>"))
    }

    @Test
    fun `formatCsv joins columns readably`() {
        val out = importer.formatCsv("name,score\n\"Asha\",95\nRavi,88")
        val lines = out.lines()
        assertEquals("name  |  score", lines[0])
        assertEquals("Asha  |  95", lines[1])
        assertEquals("Ravi  |  88", lines[2])
    }

    @Test
    fun `wrapLines keeps short lines and wraps long ones at spaces`() {
        val short = "a short line"
        val long = (1..40).joinToString(" ") { "word$it" } // > 110 chars
        val wrapped = importer.wrapLines("$short\n$long")
        assertEquals(short, wrapped.first())
        assertTrue(wrapped.size > 2)
        wrapped.forEach { assertTrue(it.length <= TextImporter.MAX_LINE_CHARS + 10) }
        // No content lost.
        assertEquals(
            (short + " " + long).replace(" ", ""),
            wrapped.joinToString("").replace(" ", "")
        )
    }
}
