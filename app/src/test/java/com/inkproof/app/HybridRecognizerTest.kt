package com.inkproof.app

import com.inkproof.app.check.HandwritingRecognizer
import com.inkproof.app.check.HybridRecognizer
import com.inkproof.app.check.RecognitionResult
import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRecognizerTest {

    private class Fake(
        override val name: String,
        private val result: RecognitionResult,
        private val available: Boolean = true
    ) : HandwritingRecognizer {
        var called = 0
        override suspend fun isAvailable(): Boolean = available
        override suspend fun recognize(strokes: List<Stroke>): RecognitionResult {
            called++
            return result
        }
    }

    private fun strokes() = listOf(
        Stroke(
            pageId = "p1", color = 0, baseWidth = 3f,
            points = listOf(StrokePoint(0f, 0f, 0.5f, 0), StrokePoint(10f, 2f, 0.5f, 10))
        )
    )

    private fun confident(text: String) = RecognitionResult(
        lines = listOf(RecognizedLine(0, text, 0.9f)),
        confidence = 0.9f,
        uncertain = false
    )

    private val uncertain = RecognitionResult(emptyList(), 0f, uncertain = true)

    @Test
    fun `cloud is never contacted when local is confident`() = runBlocking {
        val local = Fake("local", confident("2x+6=14"))
        val cloud = Fake("cloud", confident("WRONG"))
        val result = HybridRecognizer(local, cloud).recognize(strokes())
        assertEquals("2x+6=14", result.lines.first().text)
        assertEquals(0, cloud.called)
    }

    @Test
    fun `uncertain local falls back to a confident cloud reading`() = runBlocking {
        val local = Fake("local", uncertain)
        val cloud = Fake("cloud", confident("x^2-5x+6=0"))
        val result = HybridRecognizer(local, cloud).recognize(strokes())
        assertFalse(result.uncertain)
        assertEquals("x^2-5x+6=0", result.lines.first().text)
        assertEquals(1, cloud.called)
    }

    @Test
    fun `uncertain cloud keeps the honest uncertain result`() = runBlocking {
        val local = Fake("local", uncertain)
        val cloud = Fake("cloud", uncertain)
        val result = HybridRecognizer(local, cloud).recognize(strokes())
        assertTrue(result.uncertain)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `no fallback configured returns the local result`() = runBlocking {
        val local = Fake("local", uncertain)
        val result = HybridRecognizer(local, null).recognize(strokes())
        assertTrue(result.uncertain)
    }

    @Test
    fun `unavailable cloud is skipped`() = runBlocking {
        val local = Fake("local", uncertain)
        val cloud = Fake("cloud", confident("x=1"), available = false)
        val result = HybridRecognizer(local, cloud).recognize(strokes())
        assertTrue(result.uncertain)
        assertEquals(0, cloud.called)
    }
}
