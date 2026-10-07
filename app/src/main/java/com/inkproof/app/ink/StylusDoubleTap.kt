package com.inkproof.app.ink

import com.inkproof.app.model.ToolType

/**
 * Detects a stylus-button DOUBLE press from real hardware button events.
 *
 * InkProof only reacts to events Android actually delivers
 * (MotionEvent.BUTTON_STYLUS_PRIMARY / BUTTON_STYLUS_SECONDARY state
 * transitions, including while hovering). It never fakes the gesture from
 * screen taps — tapping the page is writing, not a gesture.
 *
 * Note for OnePlus Stylo owners: the pencil's "double-tap the barrel"
 * gesture is handled by OnePlus firmware and is not currently exposed to
 * third-party apps as a public event. If the OEM maps it to a stylus
 * button event, this detector picks it up automatically.
 */
class StylusDoubleTapDetector(private val windowMs: Long = 600L) {

    private var lastPressAt = Long.MIN_VALUE / 2

    /**
     * Call on each button press transition (up -> down).
     * @return true when this press completes a double press.
     */
    fun onButtonPress(nowMs: Long): Boolean {
        val isDouble = nowMs - lastPressAt in 0..windowMs
        lastPressAt = if (isDouble) Long.MIN_VALUE / 2 else nowMs
        return isDouble
    }

    fun reset() {
        lastPressAt = Long.MIN_VALUE / 2
    }
}

/**
 * Eraser <-> previous-tool toggle. Pure state machine so the exact
 * restoration rules are unit-tested:
 *  - any tool -> ERASER remembers that tool
 *  - ERASER -> restores the remembered tool
 *  - a remembered ERASER (edge case) falls back to PEN
 */
object StylusEraserToggle {

    data class Result(val tool: ToolType, val remembered: ToolType)

    fun toggle(current: ToolType, remembered: ToolType): Result =
        if (current == ToolType.ERASER) {
            val restore = if (remembered == ToolType.ERASER) ToolType.PEN else remembered
            Result(tool = restore, remembered = restore)
        } else {
            Result(tool = ToolType.ERASER, remembered = current)
        }
}
