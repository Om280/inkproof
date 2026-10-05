package com.inkproof.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryPersistenceTest {

    private lateinit var db: InkProofDatabase
    private lateinit var library: LibraryRepository
    private lateinit var pages: PageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = InkProofDatabase.inMemory(context)
        library = LibraryRepository(db)
        pages = PageRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun stroke(pageId: String, y: Float = 10f) = Stroke(
        pageId = pageId, color = 1, baseWidth = 3f,
        points = listOf(StrokePoint(5f, y, 1f, 0), StrokePoint(50f, y + 5f, 1f, 16))
    )

    @Test
    fun `create rename delete notebook persists`() = runBlocking {
        val nb = library.createNotebook("Algebra")
        assertNotNull(library.notebook(nb.id))

        library.renameNotebook(nb.id, "Algebra II")
        assertEquals("Algebra II", library.notebook(nb.id)!!.title)

        library.deleteNotebook(nb.id)
        assertNull(library.notebook(nb.id))
    }

    @Test
    fun `new notebook starts with exactly one page`() = runBlocking {
        val nb = library.createNotebook("Calc")
        assertEquals(1, library.pagesFor(nb.id).size)
    }

    @Test
    fun `creating a page creates a NEW empty independent page`() = runBlocking {
        val nb = library.createNotebook("Notes")
        val first = library.pagesFor(nb.id).first()
        pages.addStroke(stroke(first.id))

        val second = library.createPage(nb.id)
        assertNotEquals(first.id, second.id)
        // The brand-new page must be empty — no demo/old content leaks.
        assertTrue(pages.strokesForPage(second.id).isEmpty())
        assertEquals(1, pages.strokesForPage(first.id).size)
    }

    @Test
    fun `deleting a page removes it and its content`() = runBlocking {
        val nb = library.createNotebook("Notes")
        val page = library.createPage(nb.id)
        pages.addStroke(stroke(page.id))

        library.deletePage(page.id)
        assertNull(library.page(page.id))
        assertTrue(pages.strokesForPage(page.id).isEmpty())
    }

    @Test
    fun `page reorder persists`() = runBlocking {
        val nb = library.createNotebook("Notes")
        val p1 = library.pagesFor(nb.id).first()
        val p2 = library.createPage(nb.id)
        val p3 = library.createPage(nb.id)

        library.reorderPages(nb.id, listOf(p3.id, p1.id, p2.id))
        val ordered = library.pagesFor(nb.id).map { it.id }
        assertEquals(listOf(p3.id, p1.id, p2.id), ordered)
    }

    @Test
    fun `duplicate notebook deep-copies content with fresh ids`() = runBlocking {
        val nb = library.createNotebook("Original")
        val page = library.pagesFor(nb.id).first()
        pages.addStroke(stroke(page.id))

        val copy = library.duplicateNotebook(nb.id)!!
        assertNotEquals(nb.id, copy.id)
        val copyPages = library.pagesFor(copy.id)
        assertEquals(1, copyPages.size)
        assertNotEquals(page.id, copyPages[0].id)
        assertEquals(1, pages.strokesForPage(copyPages[0].id).size)
        // Deleting the copy leaves the original intact.
        library.deleteNotebook(copy.id)
        assertEquals(1, pages.strokesForPage(page.id).size)
    }

    @Test
    fun `changing template keeps strokes`() = runBlocking {
        val nb = library.createNotebook("Notes")
        val page = library.pagesFor(nb.id).first()
        pages.addStroke(stroke(page.id))

        library.setTemplate(page.id, PageTemplate.DOT_GRID)
        assertEquals(PageTemplate.DOT_GRID.name, library.page(page.id)!!.template)
        assertEquals(1, pages.strokesForPage(page.id).size)
    }

    @Test
    fun `stroke undo-style delete and re-add preserves identity`() = runBlocking {
        val nb = library.createNotebook("Notes")
        val page = library.pagesFor(nb.id).first()
        val s = stroke(page.id)
        pages.addStroke(s)
        pages.deleteStrokes(listOf(s))
        assertTrue(pages.strokesForPage(page.id).isEmpty())
        pages.addStrokes(listOf(s))
        assertEquals(s.id, pages.strokesForPage(page.id).single().id)
    }

    @Test
    fun `search finds notebooks by title and question text`() = runBlocking {
        val nb = library.createNotebook(
            "Quadratics", firstPageKind = PageKind.MATH_QUESTION
        )
        val page = library.pagesFor(nb.id).first()
        pages.createQuestion(
            pageId = page.id,
            contentType = com.inkproof.app.model.QuestionContentType.TYPED,
            typedText = "Solve x^2 + 4x + 1 = 0",
            questionTop = 60f
        )
        assertEquals(1, library.search("Quadra").size)
        assertEquals(1, library.search("4x + 1").size)
        assertTrue(library.search("nonexistent-xyz").isEmpty())
    }

    @Test
    fun `move notebook into folder and delete folder keeps notebook`() = runBlocking {
        val folder = library.createFolder("Physics")
        val nb = library.createNotebook("Mechanics")

        library.moveNotebookToFolder(nb.id, folder.id)
        assertEquals(folder.id, library.notebook(nb.id)!!.folderId)

        library.moveNotebookToFolder(nb.id, null)
        assertNull(library.notebook(nb.id)!!.folderId)

        library.moveNotebookToFolder(nb.id, folder.id)
        library.deleteFolder(folder.id)
        // Notebook survives folder deletion and is back in the root library.
        val after = library.notebook(nb.id)
        assertNotNull(after)
        assertNull(after!!.folderId)
    }

    @Test
    fun `rename folder persists`() = runBlocking {
        val folder = library.createFolder("Chem")
        library.renameFolder(folder.id, "Chemistry")
        assertEquals("Chemistry", db.folderDao().byId(folder.id)!!.name)
    }

    @Test
    fun `text objects persist per page and are deleted with the notebook`() = runBlocking {
        val nb = library.createNotebook("Notes")
        val pageA = library.pagesFor(nb.id).first()
        val pageB = library.createPage(nb.id)

        val obj = com.inkproof.app.model.TextObject(
            pageId = pageA.id, text = "Remember the chain rule", x = 100f, y = 200f
        )
        pages.upsertTextObject(obj)

        // Isolation: text belongs ONLY to page A.
        assertEquals(1, pages.textObjectsForPage(pageA.id).size)
        assertTrue(pages.textObjectsForPage(pageB.id).isEmpty())

        // Edit round-trip.
        pages.upsertTextObject(obj.copy(text = "Updated"))
        assertEquals("Updated", pages.textObjectsForPage(pageA.id).single().text)

        // Explicit delete.
        pages.deleteTextObject(obj.id)
        assertTrue(pages.textObjectsForPage(pageA.id).isEmpty())

        // Cascade: deleting the notebook purges any remaining text objects.
        pages.upsertTextObject(obj)
        library.deleteNotebook(nb.id)
        assertTrue(pages.textObjectsForPage(pageA.id).isEmpty())
    }
}
