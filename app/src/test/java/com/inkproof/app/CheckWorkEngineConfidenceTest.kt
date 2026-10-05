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
 * The engine must honor the recognition confidence threshold:
 * below it InkProof says UNCLEAR and NEVER calls the math provider —
 * it never guesses from handwriting it can't read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckWorkEngineConfidenceTest {

    private lateinit var db: InkProofDatabase
    private lateinit var library: LibraryRepository
    private lateinit var pages: PageRepository
    private lateinit var checks: CheckRepository

    private class FakeRecognizer(private val confidence: Float) : HandwritingRecognizer {
        override val name = "fake"
        override suspend fun isAvailable() = true
        override suspend fun recognize(strokes: List<Stroke>) = RecognitionResult(
            lines = listOf(RecognizedLine(0, "2x + 6 = 14", confidence)),
            confidence = confidence,
            uncertain = false
        )
    }

    private class RecordingProvider : CheckProvider {
        var calls = 0
        override val name = "recording"
        override suspend fun check(request: CheckRequest): CheckResponse {
            calls++
            return CheckResponse(status = CheckStatus.CORRECT, summary = "ok")
        }

        override suspend fun solve(request: CheckRequest): CheckResponse {
            calls++
            return CheckResponse(status = CheckStatus.CORRECT, summary = "ok")
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
    fun tearDown() {
        db.close()
    }

    private suspend fun questionWithInk(): String {
        val nb = library.createNotebook("Algebra")
        val page = library.pagesFor(nb.id).first()
        val q = pages.createQuestion(
            pageId = page.id,
            contentType = QuestionContentType.TYPED,
            typedText = "Solve 2x + 6 = 14",
            questionTop = 60f
        )
        pages.addStroke(
            Stroke(
                pageId = page.id, questionId = q.id, role = StrokeRole.SOLUTION,
                color = 1, baseWidth = 3f,
                points = listOf(StrokePoint(10f, 400f, 1f, 0), StrokePoint(80f, 410f, 1f, 16))
            )
        )
        return q.id
    }

    private fun engine(recConfidence: Float, threshold: Float, provider: RecordingProvider) =
        CheckWorkEngine(
            pageRepository = pages,
            checkRepository = checks,
            recognizer = FakeRecognizer(recConfidence),
            provider = provider,
            confidenceThreshold = threshold
        )

    @Test
    fun `low confidence returns UNCLEAR and never calls the provider`() = runBlocking {
        val qid = questionWithInk()
        val provider = RecordingProvider()
        val response = engine(recConfidence = 0.2f, threshold = 0.4f, provider = provider)
            .checkQuestion(qid, CheckAction.CHECK)
        assertEquals(CheckStatus.UNCLEAR, response.status)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `confidence above threshold reaches the provider`() = runBlocking {
        val qid = questionWithInk()
        val provider = RecordingProvider()
        val response = engine(recConfidence = 0.9f, threshold = 0.4f, provider = provider)
            .checkQuestion(qid, CheckAction.CHECK)
        assertEquals(CheckStatus.CORRECT, response.status)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `stricter threshold makes the same reading UNCLEAR`() = runBlocking {
        val qid = questionWithInk()

        // Strict first (UNCLEAR is never cached, so it can't pollute the next check).
        val strict = RecordingProvider()
        val unclear = engine(recConfidence = 0.6f, threshold = 0.8f, provider = strict)
            .checkQuestion(qid, CheckAction.CHECK)
        assertEquals(CheckStatus.UNCLEAR, unclear.status)
        assertEquals(0, strict.calls)

        val lenient = RecordingProvider()
        val ok = engine(recConfidence = 0.6f, threshold = 0.4f, provider = lenient)
            .checkQuestion(qid, CheckAction.CHECK)
        assertFalse(ok.status == CheckStatus.UNCLEAR)
        assertEquals(1, lenient.calls)
    }

    @Test
    fun `UNCLEAR results are not cached as the real verdict`() = runBlocking {
        val qid = questionWithInk()

        // First attempt: too messy to read under a strict threshold.
        val strict = RecordingProvider()
        val unclear = engine(recConfidence = 0.6f, threshold = 0.8f, provider = strict)
            .checkQuestion(qid, CheckAction.CHECK)
        assertEquals(CheckStatus.UNCLEAR, unclear.status)

        // Second attempt with readable ink must do a REAL check, not reuse UNCLEAR.
        val provider = RecordingProvider()
        val retry = engine(recConfidence = 0.9f, threshold = 0.4f, provider = provider)
            .checkQuestion(qid, CheckAction.CHECK)
        assertTrue(retry.status == CheckStatus.CORRECT)
        assertEquals(1, provider.calls)
    }
}
