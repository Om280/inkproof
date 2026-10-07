package com.inkproof.app

import com.inkproof.app.ink.StylusDoubleTapDetector
import com.inkproof.app.ink.StylusEraserToggle
import com.inkproof.app.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusDoubleTapTest {

    // ----- double-press detection (real button events only) -----

    @Test
    fun `two quick presses are a double press`() {
        val d = StylusDoubleTapDetector(windowMs = 600)
        assertFalse(d.onButtonPress(1000))
        assertTrue(d.onButtonPress(1400))
    }

    @Test
    fun `slow presses never trigger`() {
        val d = StylusDoubleTapDetector(windowMs = 600)
        assertFalse(d.onButtonPress(1000))
        assertFalse(d.onButtonPress(1700))
        assertFalse(d.onButtonPress(2400))
    }

    @Test
    fun `third press starts a fresh sequence`() {
        val d = StylusDoubleTapDetector(windowMs = 600)
        assertFalse(d.onButtonPress(1000))
        assertTrue(d.onButtonPress(1300)) // double consumed
        // The next press must NOT pair with the consumed one.
        assertFalse(d.onButtonPress(1500))
        assertTrue(d.onButtonPress(1800))
    }

    @Test
    fun `reset clears pending press`() {
        val d = StylusDoubleTapDetector(windowMs = 600)
        assertFalse(d.onButtonPress(1000))
        d.reset()
        assertFalse(d.onButtonPress(1200))
    }

    // ----- eraser <-> previous tool state machine -----

    @Test
    fun `pen toggles to eraser and back`() {
        val toEraser = StylusEraserToggle.toggle(ToolType.PEN, ToolType.PEN)
        assertEquals(ToolType.ERASER, toEraser.tool)
        assertEquals(ToolType.PEN, toEraser.remembered)

        val back = StylusEraserToggle.toggle(ToolType.ERASER, toEraser.remembered)
        assertEquals(ToolType.PEN, back.tool)
    }

    @Test
    fun `previous tool is restored, not reset to pen`() {
        val toEraser = StylusEraserToggle.toggle(ToolType.HIGHLIGHTER, ToolType.PEN)
        assertEquals(ToolType.ERASER, toEraser.tool)
        assertEquals(ToolType.HIGHLIGHTER, toEraser.remembered)

        val back = StylusEraserToggle.toggle(ToolType.ERASER, toEraser.remembered)
        assertEquals(ToolType.HIGHLIGHTER, back.tool)
    }

    @Test
    fun `lasso and shape tools round-trip too`() {
        for (tool in listOf(ToolType.LASSO, ToolType.SHAPE, ToolType.TEXT)) {
            val r1 = StylusEraserToggle.toggle(tool, ToolType.PEN)
            assertEquals(ToolType.ERASER, r1.tool)
            val r2 = StylusEraserToggle.toggle(ToolType.ERASER, r1.remembered)
            assertEquals(tool, r2.tool)
        }
    }

    @Test
    fun `remembered eraser edge case falls back to pen`() {
        val r = StylusEraserToggle.toggle(ToolType.ERASER, ToolType.ERASER)
        assertEquals(ToolType.PEN, r.tool)
        assertEquals(ToolType.PEN, r.remembered)
    }
}
