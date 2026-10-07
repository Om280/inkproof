package com.inkproof.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.ink.TemplateRenderer
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.PaperColors
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaperColorTest {

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
    fun tearDown() = db.close()

    @Test
    fun `new pages default to white paper`() = runBlocking {
        val nb = library.createNotebook("Notes")
        val page = library.pagesFor(nb.id).first()
        assertEquals(PaperColors.WHITE, page.paperColor)
    }

    @Test
    fun `paper color persists per page and pages stay independent`() = runBlocking {
        val nb = library.createNotebook("Mixed papers")
        val pageA = library.pagesFor(nb.id).first()
        val pageB = library.createPage(nb.id, PageKind.NOTE, PageTemplate.GRID)
        val pageC = library.createPage(nb.id, PageKind.NOTE, PageTemplate.RULED)

        library.setPaperColor(pageB.id, PaperColors.BLACK)
        library.setPaperColor(pageC.id, PaperColors.SLATE)

        assertEquals(PaperColors.WHITE, library.page(pageA.id)!!.paperColor)
        assertEquals(PaperColors.BLACK, library.page(pageB.id)!!.paperColor)
        assertEquals(PaperColors.SLATE, library.page(pageC.id)!!.paperColor)

        // Template survives a color change and vice versa.
        assertEquals(PageTemplate.GRID.name, library.page(pageB.id)!!.template)
        library.setTemplate(pageB.id, PageTemplate.DOT_GRID)
        assertEquals(PaperColors.BLACK, library.page(pageB.id)!!.paperColor)
    }

    @Test
    fun `changing paper color never touches ink`() = runBlocking {
        val nb = library.createNotebook("Inked")
        val page = library.pagesFor(nb.id).first()
        pages.addStroke(
            Stroke(
                pageId = page.id, color = 0xFF112233.toInt(), baseWidth = 3f,
                points = listOf(StrokePoint(1f, 2f, 1f, 0), StrokePoint(5f, 6f, 1f, 16))
            )
        )
        library.setPaperColor(page.id, PaperColors.NEAR_BLACK)
        val strokes = pages.strokesForPage(page.id)
        assertEquals(1, strokes.size)
        // Ink keeps its original color — no automatic inversion.
        assertEquals(0xFF112233.toInt(), strokes.first().color)
    }

    @Test
    fun `dark detection splits the palette correctly`() {
        assertFalse(PaperColors.isDark(PaperColors.WHITE))
        assertFalse(PaperColors.isDark(PaperColors.WARM_WHITE))
        assertFalse(PaperColors.isDark(PaperColors.GREY))
        assertTrue(PaperColors.isDark(PaperColors.SLATE))
        assertTrue(PaperColors.isDark(PaperColors.DARK_GREY))
        assertTrue(PaperColors.isDark(PaperColors.NEAR_BLACK))
        assertTrue(PaperColors.isDark(PaperColors.BLACK))
    }

    @Test
    fun `template lines adapt to dark paper`() {
        val onLight = TemplateRenderer.subtleLine(PaperColors.WHITE)
        val onDark = TemplateRenderer.subtleLine(PaperColors.BLACK)
        assertNotEquals(onLight, onDark)
        // Dark-paper lines are translucent white so rules stay subtle.
        assertEquals(0xFFFFFF, onDark and 0xFFFFFF)
    }
}
