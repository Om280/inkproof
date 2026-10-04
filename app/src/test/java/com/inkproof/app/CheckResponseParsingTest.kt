package com.inkproof.app

import com.inkproof.app.check.BackendCheckProvider
import com.inkproof.app.model.CheckStatus
import com.inkproof.app.model.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckResponseParsingTest {

    private val provider = BackendCheckProvider("https://example.invalid")

    @Test
    fun `valid structured response parses`() {
        val json = """
            {
              "status": "incorrect",
              "confidence": 0.96,
              "first_error_step": "step_2",
              "steps": [
                {"step_id":"step_1","status":"correct","expression":"2x + 6 = 14","explanation":"ok"},
                {"step_id":"step_2","status":"incorrect","expression":"2x = 20","hint":"check both sides"},
                {"step_id":"step_3","status":"dependent_on_previous_error","expression":"x = 10"}
              ],
              "final_answer": "x = 4",
              "full_solution": "subtract 6, divide by 2",
              "hints": ["look at step 2"]
            }
        """.trimIndent()
        val result = provider.parseStrict(json)
        assertEquals(CheckStatus.INCORRECT, result.status)
        assertEquals("step_2", result.firstErrorStep)
        assertEquals(3, result.steps.size)
        assertEquals(StepStatus.CORRECT, result.steps[0].status)
        assertEquals(StepStatus.INCORRECT, result.steps[1].status)
        assertEquals(StepStatus.DEPENDENT_ON_PREVIOUS_ERROR, result.steps[2].status)
        assertEquals("x = 4", result.finalAnswer)
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """{"status":"correct","confidence":1.0,"extra_field":"ignored","nested":{"a":1}}"""
        val result = provider.parseStrict(json)
        assertEquals(CheckStatus.CORRECT, result.status)
    }

    @Test
    fun `malformed json becomes a clean error state`() {
        val result = provider.parseStrict("this is not json {{{")
        assertEquals(CheckStatus.ERROR, result.status)
        assertEquals(true, result.message?.isNotBlank())
    }

    @Test
    fun `AI prose instead of json becomes error, never controls the UI`() {
        val result = provider.parseStrict("Sure! The answer is x = 4 because...")
        assertEquals(CheckStatus.ERROR, result.status)
    }

    @Test
    fun `empty body becomes error`() {
        val result = provider.parseStrict("")
        assertEquals(CheckStatus.ERROR, result.status)
    }
}
