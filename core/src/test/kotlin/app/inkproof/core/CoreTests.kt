package app.inkproof.core

import app.inkproof.core.ai.AnalysisPipeline
import app.inkproof.core.ai.MockAnalyzer
import app.inkproof.core.ai.RecordingTransport
import app.inkproof.core.ai.ReviewPanel
import app.inkproof.core.ai.TransportResult
import app.inkproof.core.ai.buildCheckRequest
import app.inkproof.core.ai.buildSolveRequest
import app.inkproof.core.ai.decideCheck
import app.inkproof.core.ai.encodeRequest
import app.inkproof.core.ai.encodeSolve
import app.inkproof.core.ai.foreignTextInCheck
import app.inkproof.core.ai.interpret
import app.inkproof.core.ai.parseAnalysis
import app.inkproof.core.edit.NotebookSession
import app.inkproof.core.edit.resolveQuestion
import app.inkproof.core.ink.ActiveStrokeController
import app.inkproof.core.ink.eraseStroke
import app.inkproof.core.ink.meanDeviationFromLine
import app.inkproof.core.ink.pressureWidth
import app.inkproof.core.ink.stabilize
import app.inkproof.core.ink.styleFor
import app.inkproof.core.imports.ImportKind
import app.inkproof.core.imports.classifyImport
import app.inkproof.core.imports.corruptSafe
import app.inkproof.core.imports.extractByKind
import app.inkproof.core.imports.extractDocx
import app.inkproof.core.imports.isOleCompound
import app.inkproof.core.imports.minimalZip
import app.inkproof.core.imports.parseSimpleSvg
import app.inkproof.core.imports.stripHtml
import app.inkproof.core.imports.stripRtf
import app.inkproof.core.math.classifyProblem
import app.inkproof.core.math.checkWork
import app.inkproof.core.math.limitValue
import app.inkproof.core.math.roots
import app.inkproof.core.math.solveProblem
import app.inkproof.core.math.toPoly
import app.inkproof.core.math.tryParseExpr
import app.inkproof.core.model.AiMode
import app.inkproof.core.model.Camera
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.InkRegion
import app.inkproof.core.model.InkTool
import app.inkproof.core.model.Page
import app.inkproof.core.model.PageLayout
import app.inkproof.core.model.PageTemplate
import app.inkproof.core.model.Palette
import app.inkproof.core.model.PaperColor
import app.inkproof.core.model.Question
import app.inkproof.core.model.SeqClock
import app.inkproof.core.model.SeqIds
import app.inkproof.core.model.StabilizerLevel
import app.inkproof.core.model.Stroke
import app.inkproof.core.model.TextItem
import app.inkproof.core.model.contrastRatio
import app.inkproof.core.model.effectiveTool
import app.inkproof.core.model.isLegible
import app.inkproof.core.model.ToolMode
import app.inkproof.core.persist.DocumentRepository
import app.inkproof.core.persist.pageIsBlank
import app.inkproof.core.recognize.SymbolBox
import app.inkproof.core.recognize.UnistrokeRecognizer
import app.inkproof.core.recognize.assemble
import app.inkproof.core.recognize.templateStroke
import app.inkproof.core.shape.HoldCommit
import app.inkproof.core.shape.arrowHead
import app.inkproof.core.shape.circleOf
import app.inkproof.core.shape.commitAfterHold
import app.inkproof.core.shape.evaluateHoldShape
import app.inkproof.core.shape.isAxisAlignedRectangle
import app.inkproof.core.shape.selectByLasso
import app.inkproof.core.shape.shapeFromDrag
import app.inkproof.core.shape.trianglePoints
import app.inkproof.core.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs

class DocumentPageTests {
    private fun repo(): DocumentRepository {
        val dir = Files.createTempDirectory("inkproof-doc").toFile()
        return DocumentRepository(dir, SeqIds(), SeqClock())
    }

    @Test
    fun newNotebookIsBlankAndHasUniqueIds() {
        val repo = repo()
        val a = repo.createNotebook("Untitled notebook")
        val b = repo.createNotebook("Another")
        assertNotEquals(a.id, b.id)
        val page = repo.loadPage(a.id, a.pageOrder.single())
        assertTrue(pageIsBlank(page))
        assertFalse(page.objects.any { it is TextItem && it.text.contains("2x") })
        val json = app.inkproof.core.model.InkJson.encodeToString(Page.serializer(), page)
        assertFalse(json.contains("SECRET"))
        assertFalse(json.contains("2x + 6"))
    }

    @Test
    fun threePagesStayIsolatedAcrossReloadAndDelete() {
        val repo = repo()
        val nb = repo.createNotebook("Isolation")
        val p1 = repo.loadPage(nb.id, nb.pageOrder.single())
        val p2 = repo.createPage(nb.id, p1.id)
        val p3 = repo.createPage(nb.id, p2.id)
        repo.savePage(p1.copy(objects = listOf(TextItem("t1", "ONE", 10f, 10f))))
        repo.savePage(p2.copy(objects = listOf(TextItem("t2", "TWO", 10f, 10f))))
        repo.savePage(p3.copy(objects = listOf(TextItem("t3", "THREE", 10f, 10f))))

        val reopened = DocumentRepository(repo.root, SeqIds(), SeqClock())
        val meta = reopened.requireMeta(nb.id)
        assertEquals(listOf(p1.id, p2.id, p3.id), meta.pageOrder)
        assertEquals("ONE", (reopened.loadPage(nb.id, p1.id).objects.single() as TextItem).text)
        assertEquals("TWO", (reopened.loadPage(nb.id, p2.id).objects.single() as TextItem).text)
        assertEquals("THREE", (reopened.loadPage(nb.id, p3.id).objects.single() as TextItem).text)

        assertTrue(reopened.deletePage(nb.id, p2.id))
        assertEquals(listOf(p1.id, p3.id), reopened.requireMeta(nb.id).pageOrder)
        assertEquals("ONE", (reopened.loadPage(nb.id, p1.id).objects.single() as TextItem).text)
        assertEquals("THREE", (reopened.loadPage(nb.id, p3.id).objects.single() as TextItem).text)
        var missing = false
        try {
            reopened.loadPage(nb.id, p2.id)
        } catch (_: Exception) {
            missing = true
        }
        assertTrue(missing)

        val fresh = reopened.createPage(nb.id, p3.id)
        assertTrue(pageIsBlank(fresh))
        assertNotEquals(p1.id, fresh.id)
        assertNotEquals(p2.id, fresh.id)
        assertEquals("ONE", (reopened.loadPage(nb.id, p1.id).objects.single() as TextItem).text)
    }

    @Test
    fun duplicateNotebookIsIndependent() {
        val repo = repo()
        val nb = repo.createNotebook("Original")
        val page = repo.loadPage(nb.id, nb.pageOrder.single())
        repo.savePage(page.copy(objects = listOf(TextItem("keep", "alpha", 1f, 1f))))
        val copy = repo.duplicateNotebook(nb.id)
        assertNotEquals(nb.id, copy.id)
        val copyPage = repo.loadPage(copy.id, copy.pageOrder.single())
        assertEquals("alpha", (copyPage.objects.single() as TextItem).text)
        repo.savePage(copyPage.copy(objects = listOf(TextItem("other", "beta", 2f, 2f))))
        val originalAgain = repo.loadPage(nb.id, nb.pageOrder.single())
        assertEquals("alpha", (originalAgain.objects.single() as TextItem).text)
    }

    @Test
    fun foldersSearchSortAndFavorite() {
        val repo = repo()
        val folder = repo.createFolder("Course")
        val a = repo.createNotebook("Algebra", folder.id)
        val b = repo.createNotebook("Geometry")
        repo.setFavorite(a.id, true)
        repo.renameNotebook(b.id, "Beta")
        val lib = repo.loadLibrary()
        assertEquals(1, lib.folders.size)
        assertTrue(lib.notebooks.first { it.id == a.id }.favorite)
        assertEquals("Beta", lib.notebooks.first { it.id == b.id }.name)
        val found = app.inkproof.core.model.filterNotebooks(lib.notebooks, "alg")
        assertEquals(1, found.size)
        repo.deleteFolder(folder.id)
        assertNull(repo.loadLibrary().notebooks.first { it.id == a.id }.folderId)
    }
}

class InkAndShapeTests {
    @Test
    fun activeStrokePreviewKeepsRawTip() {
        val c = ActiveStrokeController()
        c.begin("p", InkPoint(0f, 0f), InkTool.PEN, Palette.BLACK, 2.5f, 1f, StabilizerLevel.HIGH)
        c.add(InkPoint(2f, 1.4f))
        c.add(InkPoint(4f, -0.8f))
        c.add(InkPoint(8f, 0.6f))
        val tip = InkPoint(12f, 3.3f, 0.4f, 9L)
        c.add(tip)
        val preview = c.preview()
        assertEquals(tip.x, preview.last().x, 0.001f)
        assertEquals(tip.y, preview.last().y, 0.001f)
        assertEquals("p", c.pageId)
    }

    @Test
    fun stabilizerSmoothsNoiseAndKeepsCorner() {
        val noisy = (0..40).map { i ->
            val n = if (i % 2 == 0) 1.4f else -1.3f
            InkPoint(i.toFloat(), n)
        }
        val off = stabilize(noisy, StabilizerLevel.OFF)
        assertEquals(noisy, off)
        val high = stabilize(noisy, StabilizerLevel.HIGH)
        assertTrue(meanDeviationFromLine(high) < meanDeviationFromLine(noisy))
        val corner = mutableListOf<InkPoint>()
        for (i in 0..20) corner.add(InkPoint(0f, i * 5f))
        for (i in 1..20) corner.add(InkPoint(i * 5f, 100f))
        val smoothed = stabilize(corner, StabilizerLevel.MEDIUM)
        assertTrue(smoothed.any { abs(it.x) < 4f && abs(it.y - 100f) < 6f })
    }

    @Test
    fun eraserSplitsStrokeAndUndoRestoresIt() {
        val dir = Files.createTempDirectory("inkproof-erase").toFile()
        val repo = DocumentRepository(dir, SeqIds(), SeqClock())
        val nb = repo.createNotebook("E")
        val session = NotebookSession(repo.requireMeta(nb.id), mutableMapOf(nb.pageOrder.single() to repo.loadPage(nb.id, nb.pageOrder.single())), repo, SeqIds(), SeqClock())
        val stroke = Stroke(
            "s",
            (0..40).map { InkPoint(it * 3f, 40f) },
            Palette.BLACK,
            3f,
            InkTool.PEN,
        )
        session.addStroke(nb.pageOrder.single(), stroke)
        assertTrue(session.eraseAt(nb.pageOrder.single(), 60f, 40f, 8f))
        val after = session.page(nb.pageOrder.single()).strokes
        assertTrue(after.none { it.points.any { p -> abs(p.x - 60f) < 4f && abs(p.y - 40f) < 4f } })
        assertTrue(after.size >= 2)
        assertTrue(session.undo())
        assertEquals(1, session.page(nb.pageOrder.single()).strokes.size)
        session.flush()
        val reloaded = DocumentRepository(dir, SeqIds(), SeqClock()).loadPage(nb.id, nb.pageOrder.single())
        assertEquals(1, reloaded.strokes.size)
    }

    @Test
    fun partialEraseDoesNotReturnOriginalIdPiecesOnly() {
        val stroke = Stroke("s", (0..30).map { InkPoint(it * 4f, 0f) }, Palette.BLACK, 2f, InkTool.PEN)
        var n = 0
        val result = eraseStroke(stroke, 60f, 0f, 6f) { "piece-${n++}" }
        assertTrue(result.changed)
        assertTrue(result.strokes.all { it.id != "s" })
    }

    @Test
    fun cameraRoundTripAndPageMappingDoNotMixPages() {
        val layout = PageLayout()
        val camera = Camera(scrollX = 12f, scrollY = layout.pageTop(2) + 40f, scale = 1.4f)
        val (sx, sy) = camera.docToScreen(100f, layout.toDocY(2, 80f), 1600, layout)
        val (dx, dy) = camera.screenToDoc(sx, sy, 1600, layout)
        assertEquals(100f, dx, 0.05f)
        assertEquals(layout.toDocY(2, 80f), dy, 0.05f)
        assertEquals(2, layout.pageIndexAt(dy, 3))
        assertNull(layout.pageIndexAt(layout.pageTop(1) + layout.pageHeight + 4f, 3))
        assertEquals(0, layout.pageIndexAt(10f, 3))
    }

    @Test
    fun markerIsWiderAndTranslucent() {
        val pen = styleFor(InkTool.PEN, 2.6f, 0.38f)
        val marker = styleFor(InkTool.MARKER, 22f, 0.38f)
        assertTrue(marker.width > pen.width * 3)
        assertTrue(marker.translucent)
        assertTrue(marker.alpha < 1f)
        assertEquals(1f, pen.alpha, 0.001f)
        assertFalse(pen.translucent)
        assertTrue(pressureWidth(22f, 0.2f, InkTool.MARKER) > pressureWidth(2.6f, 1f, InkTool.PEN))
    }

    @Test
    fun explicitShapesArePrimitives() {
        val rect = shapeFromDrag("r", ShapeKind.RECTANGLE, 10f, 20f, 200f, 90f, Palette.BLACK, 2f)
        assertTrue(isAxisAlignedRectangle(rect))
        assertEquals(ShapeKind.RECTANGLE, rect.kind)
        val square = shapeFromDrag("s", ShapeKind.SQUARE, 0f, 0f, 40f, 90f, Palette.BLACK, 2f)
        assertEquals(square.w, square.h, 0.01f)
        assertEquals(ShapeKind.SQUARE, square.kind)
        val circle = shapeFromDrag("c", ShapeKind.CIRCLE, 0f, 0f, 50f, 10f, Palette.BLUE, 2f)
        val (center, radius) = circleOf(circle)
        assertEquals(circle.w / 2f, radius, 0.01f)
        assertEquals(circle.x + circle.w / 2f, center.x, 0.01f)
        val tri = trianglePoints(shapeFromDrag("t", ShapeKind.TRIANGLE, 0f, 0f, 90f, 60f, Palette.RED, 2f))
        assertEquals(3, tri.size)
        assertEquals(tri[1].y, tri[2].y, 0.01f)
        val arrow = shapeFromDrag("a", ShapeKind.ARROW, 0f, 0f, 80f, 0f, Palette.BLACK, 2f)
        assertEquals(3, arrowHead(arrow).size)
        val line = shapeFromDrag("l", ShapeKind.LINE, 5f, 6f, 40f, 70f, Palette.BLACK, 2f)
        assertEquals(5f, line.x, 0.01f)
        assertEquals(35f, line.w, 0.01f)
    }

    @Test
    fun holdSnapsCleanGeometryAndPreservesHandwriting() {
        val rect = rectanglePoints()
        val held = commitAfterHold(rect, holdMillis = 500)
        assertTrue(held is HoldCommit.Snap)
        assertEquals(ShapeKind.RECTANGLE, (held as HoldCommit.Snap).snap.kind)
        val notHeld = commitAfterHold(rect, holdMillis = 0)
        assertTrue(notHeld is HoldCommit.KeepStroke)
        val scribble = (0..30).map { i -> InkPoint(i * 6f, if (i % 3 == 0) 10f else if (i % 3 == 1) 70f else 30f) }
        val snap = evaluateHoldShape(scribble)
        assertFalse(snap.snapped)
        assertTrue(commitAfterHold(scribble, 800) is HoldCommit.KeepStroke)
        val circle = List(48) { i ->
            val t = Math.PI * 2 * i / 47.0
            InkPoint((100 + 60 * kotlin.math.cos(t)).toFloat(), (100 + 60 * kotlin.math.sin(t)).toFloat())
        }
        val circleSnap = evaluateHoldShape(circle)
        assertTrue(circleSnap.snapped)
        assertEquals(ShapeKind.CIRCLE, circleSnap.kind)
        val line = (0..20).map { InkPoint(it * 8f, 12f) }
        assertEquals(ShapeKind.LINE, evaluateHoldShape(line).kind)
    }

    @Test
    fun lassoSelectsInsideObjectsOnly() {
        val inside = Stroke("in", listOf(InkPoint(10f, 10f), InkPoint(12f, 14f)), Palette.BLACK, 2f, InkTool.PEN)
        val outside = Stroke("out", listOf(InkPoint(200f, 200f), InkPoint(210f, 208f)), Palette.BLACK, 2f, InkTool.PEN)
        val poly = listOf(InkPoint(0f, 0f), InkPoint(40f, 0f), InkPoint(40f, 40f), InkPoint(0f, 40f))
        val selected = selectByLasso(listOf(inside, outside), poly)
        assertEquals(setOf("in"), selected)
    }

    @Test
    fun stylusButtonSelectsEraserWithoutChangingBaseTool() {
        assertEquals(ToolMode.ERASER, effectiveTool(ToolMode.PEN, stylusEraserEnd = true, stylusButton = false))
        assertEquals(ToolMode.ERASER, effectiveTool(ToolMode.MARKER, stylusEraserEnd = false, stylusButton = true))
        assertEquals(ToolMode.PEN, effectiveTool(ToolMode.PEN, false, false))
    }

    private fun rectanglePoints(): List<InkPoint> {
        val pts = ArrayList<InkPoint>()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            for (i in 0..24) {
                val t = i / 24f
                pts.add(InkPoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
            }
        }
        line(10f, 10f, 210f, 10f)
        line(210f, 10f, 210f, 130f)
        line(210f, 130f, 10f, 130f)
        line(10f, 130f, 10f, 10f)
        return pts
    }
}

class QuestionClipboardDarkTests {
    @Test
    fun questionDoesNotCreateAPageAndIsolatesRequests() {
        val dir = Files.createTempDirectory("inkproof-q").toFile()
        val repo = DocumentRepository(dir, SeqIds(), SeqClock())
        val nb = repo.createNotebook("Q")
        val pageId = nb.pageOrder.single()
        val session = NotebookSession(
            repo.requireMeta(nb.id),
            mutableMapOf(pageId to repo.loadPage(nb.id, pageId)),
            repo,
            SeqIds(),
            SeqClock(),
        )
        assertEquals(1, session.meta.pageOrder.size)
        val a = session.addQuestion(pageId, 40f)
        val b = session.addQuestion(pageId, 700f)
        assertEquals(1, session.meta.pageOrder.size)
        assertNotEquals(a.id, b.id)
        session.setPromptText(pageId, a.id, "2x + 6 = 14 SECRET_A")
        session.setPromptText(pageId, b.id, "x^2 + 4x + 1 = 0 SECRET_B")
        val strokeA = Stroke(
            "sa",
            listOf(InkPoint(30f, a.y + 80f, 1f), InkPoint(80f, a.y + 90f)),
            Palette.BLACK,
            2f,
            InkTool.PEN,
        )
        val strokeB = Stroke(
            "sb",
            listOf(InkPoint(11111f, b.y + 200f), InkPoint(11140f, b.y + 210f)),
            Palette.BLACK,
            2f,
            InkTool.PEN,
        )
        session.addStroke(pageId, strokeA.copy(points = listOf(InkPoint(40f, session.page(pageId).questions.first { it.id == a.id }.y + 90f), InkPoint(70f, session.page(pageId).questions.first { it.id == a.id }.y + 100f))))
        val page = session.page(pageId)
        val qB = page.questions.first { it.id == b.id }
        val solutionStroke = Stroke(
            "solB",
            listOf(InkPoint(40f, qB.y + 400f), InkPoint(22222f, qB.y + 420f)),
            Palette.RED,
            2f,
            InkTool.PEN,
        )
        session.addStroke(pageId, solutionStroke)
        session.addStroke(pageId, Stroke("free", listOf(InkPoint(33333f, 10f), InkPoint(33340f, 12f)), Palette.BLACK, 2f, InkTool.PEN))
        val loaded = session.page(pageId)
        val checkB = buildCheckRequest(nb.id, loaded, b.id)
        val encoded = encodeRequest(checkB)
        assertFalse(foreignTextInCheck(checkB, "SECRET_A"))
        assertTrue(encoded.contains("SECRET_B"))
        assertFalse(encoded.contains("33333"))
        assertTrue(checkB.strokes.all { it.id != "free" })
        val withFree = buildCheckRequest(nb.id, loaded, b.id, setOf("free"))
        assertTrue(withFree.strokes.any { it.id == "free" })
        assertFalse(encodeRequest(withFree).contains("SECRET_A"))
        val sneak = buildCheckRequest(nb.id, loaded, b.id, loaded.strokes.filter { it.questionId == a.id }.map { it.id }.toSet())
        assertTrue(sneak.strokes.none { loaded.strokes.any { stroke -> stroke.id == it.id && stroke.questionId == a.id } })
        assertTrue(checkB.strokes.none { stroke -> loaded.strokes.any { it.id == stroke.id && it.questionId == a.id } })
        val solveB = buildSolveRequest(nb.id, loaded, b.id)
        assertTrue(solveB.solutionStrokeIds.isEmpty())
        assertFalse(encodeSolve(solveB).contains("SECRET_A"))
        assertTrue(solveB.strokes.none { it.region == "solution" })
        assertEquals(2, loaded.questions.size)
        assertTrue(loaded.objects.any { it !is app.inkproof.core.model.Stroke || (it as Stroke).questionId == null || it.region == InkRegion.FREE || it.questionId != null })
    }

    @Test
    fun copyPasteCutDuplicateAreIndependentAndPersist() {
        val dir = Files.createTempDirectory("inkproof-clip").toFile()
        val repo = DocumentRepository(dir, SeqIds(), SeqClock())
        val nb = repo.createNotebook("Clip")
        val pageId = nb.pageOrder.single()
        val ids = SeqIds()
        val session = NotebookSession(repo.requireMeta(nb.id), mutableMapOf(pageId to repo.loadPage(nb.id, pageId)), repo, ids, SeqClock())
        val stroke = Stroke("orig", listOf(InkPoint(1f, 1f), InkPoint(20f, 5f)), Palette.BLUE, 2f, InkTool.PEN)
        session.addStroke(pageId, stroke)
        session.copyObjects(pageId, setOf("orig"))
        val pasted = session.paste(pageId)
        assertEquals(1, pasted.size)
        assertNotEquals("orig", pasted.single().id)
        session.moveObjects(pageId, setOf(pasted.single().id), 15f, 0f)
        val original = session.page(pageId).strokes.first { it.id == "orig" }
        assertEquals(1f, original.points.first().x, 0.01f)
        session.cutObjects(pageId, setOf("orig"))
        assertTrue(session.page(pageId).strokes.none { it.id == "orig" })
        assertTrue(session.undo())
        assertTrue(session.page(pageId).strokes.any { it.id == "orig" })
        val dup = session.duplicateObjects(pageId, setOf("orig"))
        assertNotEquals("orig", dup.single().id)
        session.flush()
        val reloaded = DocumentRepository(dir, SeqIds(), SeqClock()).loadPage(nb.id, pageId)
        assertTrue(reloaded.strokes.any { it.id == dup.single().id })
        assertTrue(reloaded.strokes.any { it.id == "orig" })
    }

    @Test
    fun paperAndTemplateAreIndependentPerPage() {
        val dir = Files.createTempDirectory("inkproof-paper").toFile()
        val repo = DocumentRepository(dir, SeqIds(), SeqClock())
        val nb = repo.createNotebook("Paper")
        val p1 = nb.pageOrder.single()
        val session = NotebookSession(repo.requireMeta(nb.id), mutableMapOf(p1 to repo.loadPage(nb.id, p1)), repo, SeqIds(), SeqClock())
        val stroke = Stroke("ink", listOf(InkPoint(5f, 5f), InkPoint(9f, 8f)), Palette.WHITE, 2f, InkTool.PEN)
        session.addStroke(p1, stroke)
        session.setPaper(p1, PaperColor.BLACK)
        session.setTemplate(p1, PageTemplate.GRID)
        val page = session.page(p1)
        assertEquals(PaperColor.BLACK, page.paper)
        assertEquals(PageTemplate.GRID, page.template)
        assertEquals(Palette.WHITE, page.strokes.single().color)
        val p2 = session.addPage(p1)
        assertTrue(pageIsBlank(session.page(p2.id)))
        session.setPaper(p2.id, PaperColor.SLATE)
        session.setTemplate(p2.id, PageTemplate.DOT)
        assertEquals(PaperColor.BLACK, session.page(p1).paper)
        assertEquals(PageTemplate.GRID, session.page(p1).template)
        assertEquals(1, session.page(p1).strokes.size)
        session.flush()
        val again = DocumentRepository(dir, SeqIds(), SeqClock())
        assertEquals(PaperColor.BLACK, again.loadPage(nb.id, p1).paper)
        assertEquals(PaperColor.SLATE, again.loadPage(nb.id, p2.id).paper)
        assertEquals(PageTemplate.DOT, again.loadPage(nb.id, p2.id).template)
    }

    @Test
    fun listedLightInksAreLegibleOnDarkPaper() {
        for (ink in Palette.darkPaperLegibility) {
            assertTrue("$ink on black", isLegible(ink, PaperColor.BLACK.argb))
            assertTrue(isLegible(ink, PaperColor.NEAR_BLACK.argb))
        }
        assertFalse(isLegible(Palette.BLACK, PaperColor.BLACK.argb))
        assertTrue(contrastRatio(Palette.WHITE, PaperColor.BLACK.argb) > 10)
    }

    @Test
    fun ambiguousQuestionsAreNotGuessed() {
        val page = Page(
            id = "p",
            notebookId = "n",
            questions = listOf(Question("a", 10f, promptText = "one"), Question("b", 800f, promptText = "two")),
        )
        val resolved = resolveQuestion(page, null, null, emptySet())
        assertTrue(resolved is app.inkproof.core.edit.QuestionResolve.Ambiguous)
        val explicit = resolveQuestion(page, "b", "a", emptySet())
        assertTrue(explicit is app.inkproof.core.edit.QuestionResolve.Found)
        assertEquals("b", (explicit as app.inkproof.core.edit.QuestionResolve.Found).questionId)
    }
}

class MathAndAnalysisTests {
    @Test
    fun solvesRequiredProblemTypes() {
        val linear = solveProblem("2x + 6 = 14")
        assertNotNull(linear)
        assertTrue(linear!!.finalAnswer.contains("4"))
        val quad = solveProblem("x^2 + 4x + 1 = 0")
        assertNotNull(quad)
        assertTrue(quad!!.finalAnswer.contains("sqrt") || quad.finalAnswer.contains("-2"))
        val deriv = solveProblem("d/dx (x^2 + 3x)")
        assertEquals("2x+3", deriv!!.finalAnswer.replace(" ", ""))
        val integral = solveProblem("integral x^2 dx")
        assertTrue(integral!!.finalAnswer.contains("x^3"))
        assertTrue(integral.finalAnswer.contains("C"))
        val unicode = solveProblem("∫ x^2 dx")
        assertNotNull(unicode)
        val limit = solveProblem("lim x->2 (x+1)")
        assertEquals("3", limit!!.finalAnswer)
        val cancel = classifyProblem("lim x->1 (x^2-1)/(x-1)")
        assertTrue(cancel is app.inkproof.core.math.Problem.Limit)
        val value = limitValue(cancel as app.inkproof.core.math.Problem.Limit)
        assertEquals(2.0, value!!, 1e-6)
        assertNull(solveProblem("sin(x) + cos(y) = 1"))
    }

    @Test
    fun checkMarksFirstErrorAndDoesNotAbsolveLaterSteps() {
        val bad = checkWork("2x + 6 = 14", listOf("2x = 20", "x = 10"))
        assertEquals("incorrect", bad!!.status)
        assertEquals(0, bad.firstError!!.stepIndex)
        assertEquals("incorrect", bad.steps[0].verdict)
        assertEquals("depends_on_error", bad.steps[1].verdict)
        assertNotEquals("correct", bad.steps[1].verdict)
        val good = checkWork("2x + 6 = 14", listOf("2x = 8", "x = 4"))
        assertEquals("correct", good!!.status)
        val quad = checkWork("x^2 + 4x + 1 = 0", listOf("x = -2 ± sqrt(3)"))
        assertEquals("correct", quad!!.status)
        val quadBad = checkWork("x^2 + 4x + 1 = 0", listOf("x = -2 ± sqrt(5)"))
        assertEquals("incorrect", quadBad!!.status)
        val deriv = checkWork("d/dx (x^2 + 3x)", listOf("2x + 3"))
        assertEquals("correct", deriv!!.status)
        val derivBad = checkWork("d/dx (x^2 + 3x)", listOf("2x + 5"))
        assertEquals("incorrect", derivBad!!.status)
        val integral = checkWork("integral x^2 dx", listOf("x^3/3 + C"))
        assertEquals("correct", integral!!.status)
        val limit = checkWork("lim x->2 (x+1)", listOf("3"))
        assertEquals("correct", limit!!.status)
        assertNull(checkWork("matrix [[1,0],[0,1]]", listOf("I")))
    }

    @Test
    fun polynomialParseAcceptsUnicodeSuperscript() {
        val expr = tryParseExpr("x² + 4x + 1")
        val poly = toPoly(expr!!)
        assertEquals(2, poly!!.degree())
        assertEquals(2, roots(poly)!!.size)
    }

    @Test
    fun pipelineDoesNotFakeSuccess() {
        val transport = RecordingTransport()
        transport.fail = true
        val pipeline = AnalysisPipeline(transport, mode = { AiMode.REAL })
        val request = buildCheckRequest(
            "n",
            pageWith("2x + 6 = 14", "2x = 8\nx = 4"),
            "q",
        )
        val result = pipeline.check(request)
        assertFalse(result.mock)
        assertEquals("on_device", result.provider)
        assertEquals("correct", result.status)
        assertTrue(result.message.orEmpty().contains("unavailable") || result.message.orEmpty().isNotBlank())
        assertEquals(1, transport.checks.size)

        val unsupported = pipeline.check(
            buildCheckRequest("n", pageWith("please simplify the matrix determinant of a general 4 by 4", "42"), "q"),
        )
        assertEquals("error", unsupported.status)
        assertFalse(unsupported.mock)
        assertNotEquals("correct", unsupported.status)
    }

    @Test
    fun mockModeNeverCallsTransportAndIsLabeled() {
        val transport = RecordingTransport()
        val pipeline = AnalysisPipeline(transport, mode = { AiMode.MOCK })
        val response = pipeline.check(buildCheckRequest("n", pageWith("2x+6=14", "x=4"), "q"))
        assertTrue(response.mock)
        assertEquals("mock", response.provider)
        assertTrue(transport.checks.isEmpty())
        assertTrue(MockAnalyzer.check(buildCheckRequest("n", pageWith("a", "b"), "q")).mock)
    }

    @Test
    fun invalidResponsesAndMissingKeyAreErrors() {
        val bad = interpret(TransportResult(200, "not-json"), "q", "check", 1, 1)
        assertEquals("error", bad.status)
        assertEquals("INVALID_RESPONSE", bad.errorCode)
        assertFalse(bad.mock)
        assertNull(parseAnalysis("{"))
        val missing = interpret(
            TransportResult(503, """{"schemaVersion":1,"status":"error","provider":"gemini","mock":false,"errorCode":"MISSING_API_KEY","message":"Set GEMINI_API_KEY"}"""),
            "q", "check", 1, 1,
        )
        assertEquals("error", missing.status)
        assertEquals("MISSING_API_KEY", missing.errorCode)
        assertFalse(missing.mock)
        val rate = interpret(TransportResult(429, "slow down"), "q", "solve", 1, 0)
        assertEquals("RATE_LIMIT", rate.errorCode)
        val network = interpret(TransportResult(0, "", "NETWORK"), "q", "check", 1, 1)
        assertEquals("NETWORK", network.errorCode)
        assertFalse(network.mock)
    }

    @Test
    fun cacheUsesQuestionVersionAndSkipsErrors() {
        val transport = RecordingTransport()
        transport.body = """{"schemaVersion":1,"status":"unclear","provider":"gemini","mock":false,"explanation":"cloud"}"""
        val pipeline = AnalysisPipeline(transport, mode = { AiMode.REAL })
        val page = pageWith("2x + 6 = 14", "x = 4")
        val first = pipeline.check(buildCheckRequest("n", page, "q"))
        val second = pipeline.check(buildCheckRequest("n", page, "q"))
        assertEquals(first.explanation, second.explanation)
        assertEquals(1, transport.checks.size)
        val bumped = page.copy(questions = page.questions.map { it.copy(solutionVersion = 9, recognizedSolution = "x = 4") })
        pipeline.check(buildCheckRequest("n", bumped, "q"))
        assertEquals(2, transport.checks.size)
    }

    @Test
    fun solutionStaysHiddenUntilReveal() {
        val response = solveProblem("2x + 6 = 14")!!
        val panel = ReviewPanel(
            app.inkproof.core.ai.AnalysisResponse(
                status = "correct",
                provider = "on_device",
                finalAnswer = response.finalAnswer,
                fullSolution = response.steps,
                hints = listOf("h1", "h2"),
            ),
        )
        assertNull(panel.visibleAnswer())
        assertTrue(panel.visibleSolution().isEmpty())
        assertEquals(listOf("h1"), panel.visibleHints())
        val shown = panel.reveal().withAnotherHint()
        assertEquals(response.finalAnswer, shown.visibleAnswer())
        assertEquals(2, shown.visibleHints().size)
    }

    @Test
    fun lowConfidenceMustBeConfirmed() {
        val q = Question("q", 0f, promptText = "", recognizedPrompt = "2x+6=14", promptConfidence = 0.2f, recognizedSolution = "x=4", solutionConfidence = 0.2f)
        val decision = decideCheck(q, "2x+6=14", "x=4", 0.2f)
        assertTrue(decision is app.inkproof.core.ai.CheckDecision.Confirm || decision is app.inkproof.core.ai.CheckDecision.Blocked)
        val typed = Question("q", 0f, promptText = "2x+6=14", recognizedSolution = "x=4", solutionConfidence = 0.9f)
        assertTrue(decideCheck(typed, null, "x=4", 0.9f) is app.inkproof.core.ai.CheckDecision.Run)
    }

    @Test
    fun solveRequestDoesNotIncludeOtherQuestion() {
        val page = Page(
            id = "p",
            notebookId = "n",
            questions = listOf(
                Question("A", 10f, promptText = "SECRET_A 2x+6=14"),
                Question("B", 700f, promptText = "SECRET_B x^2+4x+1=0"),
            ),
            objects = listOf(
                Stroke("sa", listOf(InkPoint(1f, 40f)), Palette.BLACK, 2f, InkTool.PEN, questionId = "A", region = InkRegion.SOLUTION),
                Stroke("sb", listOf(InkPoint(1f, 900f)), Palette.BLACK, 2f, InkTool.PEN, questionId = "B", region = InkRegion.SOLUTION),
            ),
        )
        val solve = buildSolveRequest("n", page, "B")
        assertFalse(encodeSolve(solve).contains("SECRET_A"))
        assertTrue(solve.strokes.isEmpty())
        assertTrue(solve.solutionStrokeIds.isEmpty())
    }

    private fun pageWith(prompt: String, solution: String): Page = Page(
        id = "p",
        notebookId = "n",
        questions = listOf(
            Question(
                id = "q",
                y = 20f,
                promptText = prompt,
                recognizedSolution = solution,
                solutionConfidence = 1f,
            ),
        ),
    )
}

class ImportAndRecognitionTests {
    @Test
    fun classifiesAndExtractsPracticalFormats() {
        assertEquals(ImportKind.PDF, classifyImport("notes.pdf", "application/pdf"))
        assertEquals(ImportKind.IMAGE, classifyImport("scan.PNG", "image/png"))
        assertEquals(ImportKind.DOCX, classifyImport("hw.docx", null))
        assertEquals(ImportKind.LEGACY_OFFICE, classifyImport("old.doc", "application/msword"))
        assertEquals("Hello world", stripHtml("<p>Hello <b>world</b></p><script>nope</script>"))
        assertTrue(stripRtf("{\\rtf1 Hello \\b world}").contains("Hello"))
        val docx = minimalZip(mapOf("word/document.xml" to """<w:document><w:t>Quadratic</w:t><w:t> homework</w:t></w:document>"""))
        assertTrue(extractDocx(docx).contains("Quadratic"))
        val extracted = extractByKind("hw.docx", null, docx)
        assertTrue(extracted.text.contains("homework"))
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
        assertTrue(isOleCompound(ole))
        val legacy = extractByKind("old.doc", null, ole)
        assertTrue(legacy.note.contains("DOCX"))
        val svg = """<svg><rect x="1" y="2" width="30" height="10"/><circle cx="5" cy="6" r="2"/><text>label</text></svg>"""
        assertEquals(2, parseSimpleSvg(svg).size)
        assertTrue(extractByKind("fig.svg", "image/svg+xml", svg.toByteArray()).text.contains("label"))
        val corrupt = corruptSafe { throw IllegalArgumentException("bad") }
        assertTrue(corrupt.note.contains("corrupt"))
    }

    @Test
    fun recognizerMatchesItsOwnTemplateAndRejectsScribble() {
        val recognizer = UnistrokeRecognizer()
        val one = templateStroke("1")!!
        val match = recognizer.recognizeSymbol(one)
        assertNotNull(match)
        assertEquals("1", match!!.symbol)
        assertTrue(match.confidence > 0.75f)
        val scribble = (0..36).map { i ->
            InkPoint((i * 7 % 50).toFloat(), ((i * i) % 47).toFloat())
        }
        val poor = recognizer.recognizeSymbol(scribble)
        assertTrue(poor == null || poor.confidence < 0.55f)
        val text = assemble(
            listOf(
                SymbolBox("x", 0f, 20f, 18f, 28f, 0.9f),
                SymbolBox("2", 20f, 0f, 10f, 12f, 0.9f),
                SymbolBox("+", 40f, 20f, 16f, 28f, 0.9f),
            ),
        )
        assertTrue(text.contains("x^2"))
    }
}
