package com.inkproof.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inkproof.app.check.CheckProvider
import com.inkproof.app.check.CheckWorkEngine
import com.inkproof.app.check.HandwritingRecognizer
import com.inkproof.app.check.LineSegmenter
import com.inkproof.app.check.RecognitionResult
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.data.repo.CheckRepository
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.model.CheckAction
import com.inkproof.app.model.CheckRequest
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.CheckStatus
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.Question
import com.inkproof.app.model.QuestionContentType
import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import com.inkproof.app.model.StrokeRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tests that encode InkProof's hard isolation guarantees:
 *  - Page A's content never leaks into Page B's check.
 *  - Question 1's strokes never appear in Question 2's request.
 *  - The checked question text is the actual Question object's text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataIsolationTest {

    private lateinit var db: InkProofDatabase
    private lateinit var library: LibraryRepository
    private lateinit var pages: PageRepository
    private lateinit var checks: CheckRepository

    /** Captures every request the engine sends to the provider. */
    private class RecordingProvider : CheckProvider {
        override val name = "recording"
        val requests = mutableListOf<CheckRequest>()
        var respondWith: CheckResponse = CheckResponse(status = CheckStatus.CORRECT, confidence = 1f)

        override suspend fun check(request: CheckRequest): CheckResponse {
            requests.add(request)
            return respondWith
        }

        override suspend fun solve(request: CheckRequest): CheckResponse {
            requests.add(request)
            return respondWith
        }
    }

    /** Deterministic recognizer: each visual line becomes "line@<top y>". */
    private class FakeRecognizer : HandwritingRecognizer {
        override val name = "fake"
        override suspend fun isAvailable() = true
        override suspend fun recognize(strokes: List<Stroke>): RecognitionResult {
            if (strokes.isEmpty()) return RecognitionResult(emptyList(), 0f, uncertain = true)
            val lines = LineSegmenter.segment(strokes).mapIndexed { i, line ->
                RecognizedLine(i, "line@${line.first().bounds().top.toInt()}", 0.95f)
            }
            return RecognitionResult(lines, 0.95f)
        }
    }

    private lateinit var provider: RecordingProvider
    private lateinit var engine: CheckWorkEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = InkProofDatabase.inMemory(context)
        library = LibraryRepository(db)
        pages = PageRepository(db)
        checks = CheckRepository(db)
        provider = RecordingProvider()
        engine = CheckWorkEngine(pages, checks, FakeRecognizer(), provider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun questionWithSolution(
        pageId: String,
        text: String,
        top: Float,
        solutionLineYs: List<Float>
    ): Question {
        val q = pages.createQuestion(
            pageId = pageId,
            contentType = QuestionContentType.TYPED,
            typedText = text,
            questionTop = top
        )
        for (y in solutionLineYs) {
            pages.addStroke(
                Stroke(
                    pageId = pageId,
                    questionId = q.id,
                    role = StrokeRole.SOLUTION,
                    color = 1,
                    baseWidth = 3f,
                    points = listOf(
                        StrokePoint(40f, y, 1f, 0),
                        StrokePoint(300f, y + 20f, 1f, 50)
                    )
                )
            )
        }
        return q
    }

    @Test
    fun `checking page B never returns page A's question`() = runBlocking {
        val nb = library.createNotebook("Isolation", firstPageKind = PageKind.MATH_QUESTION)
        val pageA = library.pagesFor(nb.id).first()
        val pageB = library.createPage(nb.id, PageKind.MATH_QUESTION)

        questionWithSolution(pageA.id, "Solve 2x + 6 = 14", 60f, listOf(340f))
        val qB = questionWithSolution(pageB.id, "Solve x^2 + 4x + 1 = 0", 60f, listOf(340f))

        engine.checkQuestion(qB.id, CheckAction.CHECK)

        assertEquals(1, provider.requests.size)
        val sent = provider.requests[0]
        assertEquals("Solve x^2 + 4x + 1 = 0", sent.questionText)
        assertFalse(sent.questionText.contains("2x + 6"))
        assertEquals(qB.id, sent.questionId)
    }

    @Test
    fun `checking question 2 never includes question 1 or 3 strokes`() = runBlocking {
        val nb = library.createNotebook("Multi", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()

        // Q1 at top, Q2 in middle, Q3 at bottom — each with its own ink.
        questionWithSolution(page.id, "Q1: derivative of x^2", 0f, listOf(300f, 380f))
        val q2 = questionWithSolution(page.id, "Q2: integral of 2x", 1100f, listOf(1420f))
        questionWithSolution(page.id, "Q3: limit of 1/x", 2200f, listOf(2520f, 2600f))

        engine.checkQuestion(q2.id, CheckAction.CHECK)

        val sent = provider.requests.single()
        assertEquals("Q2: integral of 2x", sent.questionText)
        // Exactly one recognized line, from Q2's ink only (y=1420).
        assertEquals(1, sent.solutionLines.size)
        assertTrue(sent.solutionLines[0].text.contains("1420"))
    }

    @Test
    fun `questions have separate storage and ids`() = runBlocking {
        val nb = library.createNotebook("Sep", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val q1 = questionWithSolution(page.id, "first", 0f, listOf(300f))
        val q2 = questionWithSolution(page.id, "second", 1100f, listOf(1400f))

        assertTrue(q1.id != q2.id)
        assertEquals(1, pages.solutionStrokes(q1.id).size)
        assertEquals(1, pages.solutionStrokes(q2.id).size)
        assertTrue(
            pages.solutionStrokes(q1.id).map { it.id }
                .intersect(pages.solutionStrokes(q2.id).map { it.id }.toSet())
                .isEmpty()
        )
    }

    @Test
    fun `deleting a question removes its strokes but not its neighbor's`() = runBlocking {
        val nb = library.createNotebook("Del", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val q1 = questionWithSolution(page.id, "first", 0f, listOf(300f))
        val q2 = questionWithSolution(page.id, "second", 1100f, listOf(1400f))

        pages.deleteQuestion(q1.id)
        assertTrue(pages.solutionStrokes(q1.id).isEmpty())
        assertEquals(1, pages.solutionStrokes(q2.id).size)
    }

    @Test
    fun `empty solution yields INCOMPLETE and no provider call`() = runBlocking {
        val nb = library.createNotebook("Empty", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val q = pages.createQuestion(
            pageId = page.id,
            contentType = QuestionContentType.TYPED,
            typedText = "Solve x + 1 = 2",
            questionTop = 60f
        )

        val result = engine.checkQuestion(q.id, CheckAction.CHECK)
        assertEquals(CheckStatus.INCOMPLETE, result.status)
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `empty question yields ERROR and no provider call`() = runBlocking {
        val nb = library.createNotebook("EmptyQ", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val q = pages.createQuestion(
            pageId = page.id,
            contentType = QuestionContentType.TYPED,
            typedText = "",
            questionTop = 60f
        )
        val result = engine.checkQuestion(q.id, CheckAction.CHECK)
        assertEquals(CheckStatus.ERROR, result.status)
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `typed question text is passed verbatim, never re-interpreted`() = runBlocking {
        val nb = library.createNotebook("Verbatim", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val exact = "Find all x such that 3x^2 - 5x + 2 = 0 (give exact roots)"
        val q = questionWithSolution(page.id, exact, 60f, listOf(400f))

        engine.checkQuestion(q.id, CheckAction.CHECK)
        assertEquals(exact, provider.requests.single().questionText)
        assertEquals("typed", provider.requests.single().questionSource)
    }

    @Test
    fun `results are cached and invalidated by solution edits`() = runBlocking {
        val nb = library.createNotebook("Cache", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val q = questionWithSolution(page.id, "cache me", 60f, listOf(400f))

        engine.checkQuestion(q.id, CheckAction.CHECK)
        engine.checkQuestion(q.id, CheckAction.CHECK)
        // Second call served from cache -> only one provider call.
        assertEquals(1, provider.requests.size)

        // Editing the solution bumps the version -> cache miss -> new call.
        pages.addStroke(
            Stroke(
                pageId = page.id, questionId = q.id, role = StrokeRole.SOLUTION,
                color = 1, baseWidth = 3f,
                points = listOf(StrokePoint(40f, 480f, 1f, 0), StrokePoint(200f, 500f, 1f, 30))
            )
        )
        engine.checkQuestion(q.id, CheckAction.CHECK)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `check and solve are cached separately`() = runBlocking {
        val nb = library.createNotebook("Actions", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val q = questionWithSolution(page.id, "two actions", 60f, listOf(400f))

        engine.checkQuestion(q.id, CheckAction.CHECK)
        engine.checkQuestion(q.id, CheckAction.SOLVE)
        assertEquals(2, provider.requests.size)
        assertEquals("check", provider.requests[0].action)
        assertEquals("solve", provider.requests[1].action)
    }

    @Test
    fun `lasso selection inside one question routes to that question`() = runBlocking {
        val nb = library.createNotebook("Lasso", firstPageKind = PageKind.MATH_QUESTION)
        val page = library.pagesFor(nb.id).first()
        val q = questionWithSolution(page.id, "lasso question", 60f, listOf(400f))
        val selected = pages.solutionStrokes(q.id)

        engine.checkSelection(page.id, selected, CheckAction.CHECK)
        val sent = provider.requests.single()
        // Routed through the Question object — its typed text is the truth.
        assertEquals("lasso question", sent.questionText)
        assertEquals(q.id, sent.questionId)
    }

    @Test
    fun `empty lasso selection is a clean error`() = runBlocking {
        val result = engine.checkSelection("page-x", emptyList(), CheckAction.CHECK)
        assertEquals(CheckStatus.ERROR, result.status)
        assertTrue(provider.requests.isEmpty())
    }
}
