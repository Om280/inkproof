package com.inkproof.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inkproof.app.check.CheckProvider
import com.inkproof.app.check.CheckWorkEngine
import com.inkproof.app.check.HandwritingRecognizer
import com.inkproof.app.check.RecognitionResult
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.data.repo.CheckRepository
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.model.CheckAction
import com.inkproof.app.model.CheckRequest
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.CheckStatus
import com.inkproof.app.model.QuestionContentType
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import com.inkproof.app.model.StrokeRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SOLVE must use ONLY the question statement:
 * - the student's ink is never recognized (a throwing recognizer proves it),
 * - the provider receives empty solution lines,
 * - the typed question text travels unmodified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolveFlowTest {

    private lateinit var db: InkProofDatabase
    private lateinit var library: LibraryRepository
    private lateinit var pages: PageRepository
    private lateinit var checks: CheckRepository

    /** Blows up if SOLVE ever tries to read the student's handwriting. */
    private class ThrowingRecognizer : HandwritingRecognizer {
        override val name = "throwing"
        override suspend fun isAvailable() = true
        override suspend fun recognize(strokes: List<Stroke>): RecognitionResult =
            throw AssertionError("SOLVE must not recognize student ink")
    }

    private class RecordingProvider : CheckProvider {
        var lastRequest: CheckRequest? = null
        var solveCalls = 0
        override val name = "recording"
        override suspend fun check(request: CheckRequest): CheckResponse =
            throw AssertionError("SOLVE must route to solve(), not check()")

        override suspend fun solve(request: CheckRequest): CheckResponse {
            solveCalls++
            lastRequest = request
            return CheckResponse(
                status = CheckStatus.CORRECT,
                finalAnswer = "x = 4",
                fullSolution = "2x = 8 so x = 4",
                summary = "solved"
            )
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = InkProofDatabase.inMemory(context)
        library = LibraryRepository(db)
        pages = PageRepository(db)
        checks = CheckRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun typedQuestionWithMessyInk(text: String): String {
        val nb = library.createNotebook("Calculus")
        val page = library.pagesFor(nb.id).first()
        val q = pages.createQuestion(
            pageId = page.id,
            contentType = QuestionContentType.TYPED,
            typedText = text,
            questionTop = 60f
        )
        // Student scribbles that SOLVE must completely ignore.
        pages.addStroke(
            Stroke(
                pageId = page.id, questionId = q.id, role = StrokeRole.SOLUTION,
                color = 1, baseWidth = 3f,
                points = listOf(StrokePoint(10f, 400f, 1f, 0), StrokePoint(90f, 420f, 1f, 16))
            )
        )
        return q.id
    }

    @Test
    fun `solve ignores student ink and sends only the question`() = runBlocking {
        val questions = listOf(
            "Solve 2x + 6 = 14",
            "Solve x^2 - 5x + 6 = 0",
            "Differentiate f(x) = 3x^2 + 2x",
            "Evaluate the integral of 2x dx from 0 to 3",
            "Find the limit of (sin x)/x as x approaches 0"
        )
        for (text in questions) {
            val provider = RecordingProvider()
            val engine = CheckWorkEngine(
                pageRepository = pages,
                checkRepository = checks,
                recognizer = ThrowingRecognizer(),
                provider = provider,
                confidenceThreshold = 0.4f
            )
            val qid = typedQuestionWithMessyInk(text)
            val response = engine.checkQuestion(qid, CheckAction.SOLVE)

            assertEquals("for \"$text\"", 1, provider.solveCalls)
            val request = provider.lastRequest!!
            assertEquals("for \"$text\"", text, request.questionText)
            assertTrue(
                "solution lines must be empty for \"$text\"",
                request.solutionLines.isEmpty()
            )
            assertEquals(CheckStatus.CORRECT, response.status)
            assertEquals("x = 4", response.finalAnswer)
        }
    }
}
