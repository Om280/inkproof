package com.inkproof.app

import com.inkproof.app.check.MockCheckProvider
import com.inkproof.app.model.CheckRequest
import com.inkproof.app.model.CheckStatus
import com.inkproof.app.model.RecognizedLine
import com.inkproof.app.model.StepStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockCheckProviderTest {

    private val provider = MockCheckProvider(simulatedLatencyMs = 0)

    private fun request(question: String, lines: List<String>) = CheckRequest(
        requestId = "r1",
        action = "check",
        questionId = "q1",
        questionText = question,
        questionSource = "typed",
        questionConfidence = 1f,
        solutionLines = lines.mapIndexed { i, t -> RecognizedLine(i, t, 0.95f) },
        contentVersion = 1,
        solutionVersion = 1
    )

    @Test
    fun `correct scenario`() = runTest {
        val r = provider.check(request("mock:correct solve 2x+6=14", listOf("2x = 8", "x = 4")))
        assertEquals(CheckStatus.CORRECT, r.status)
        assertEquals(2, r.steps.size)
        assertTrue(r.steps.all { it.status == StepStatus.CORRECT })
    }

    @Test
    fun `incorrect scenario marks first error and dependent steps`() = runTest {
        val r = provider.check(
            request("mock:incorrect solve 2x+6=14", listOf("2x = 20", "x = 10", "check: 26"))
        )
        assertEquals(CheckStatus.INCORRECT, r.status)
        assertNotNull(r.firstErrorStep)
        val errorIndex = r.steps.indexOfFirst { it.status == StepStatus.INCORRECT }
        assertTrue(errorIndex >= 0)
        // Steps after the error must be dependent, never "correct".
        r.steps.drop(errorIndex + 1).forEach {
            assertEquals(StepStatus.DEPENDENT_ON_PREVIOUS_ERROR, it.status)
        }
        // Progressive hints exist.
        assertTrue(r.hints.size >= 2)
    }

    @Test
    fun `incomplete scenario`() = runTest {
        val r = provider.check(request("mock:incomplete integrate x^2", listOf("x^3")))
        assertEquals(CheckStatus.INCOMPLETE, r.status)
    }

    @Test
    fun `unclear scenario refuses to guess`() = runTest {
        val r = provider.check(request("mock:unclear messy writing", listOf("???")))
        assertEquals(CheckStatus.UNCLEAR, r.status)
        assertTrue(r.message!!.contains("never guesses"))
    }

    @Test
    fun `empty solution is incomplete, not invented`() = runTest {
        val r = provider.check(request("mock:correct solve x+1=2", emptyList()))
        assertEquals(CheckStatus.INCOMPLETE, r.status)
        assertTrue(r.steps.isEmpty())
    }

    @Test
    fun `empty question is an error`() = runTest {
        val r = provider.check(request("", listOf("x = 1")))
        assertEquals(CheckStatus.ERROR, r.status)
    }

    @Test
    fun `mock echoes the actual submitted question, never a demo problem`() = runTest {
        val question = "mock:correct factor x^2 + 4x + 1 = 0"
        val r = provider.check(request(question, listOf("step")))
        assertEquals(question, r.questionEcho)
        // The infamous prototype bug: result must not reference a different equation.
        assertTrue(r.questionEcho!!.contains("x^2 + 4x + 1"))
    }

    @Test
    fun `solve returns a worked solution for the submitted question`() = runTest {
        val r = provider.solve(request("integrate sin(x)", emptyList()))
        assertTrue(r.fullSolution!!.contains("integrate sin(x)"))
    }
}
