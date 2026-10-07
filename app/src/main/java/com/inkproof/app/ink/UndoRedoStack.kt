package com.inkproof.app.ink

import com.inkproof.app.model.Stroke

/** An undoable canvas operation. */
sealed class CanvasOp {
    /** Strokes that were added (draw, paste, snapped shape). */
    data class Add(val strokes: List<Stroke>) : CanvasOp()

    /** Strokes that were removed (eraser, lasso delete). */
    data class Remove(val strokes: List<Stroke>) : CanvasOp()

    /** Strokes replaced (move, recolor, resize): before -> after by ID. */
    data class Replace(val before: List<Stroke>, val after: List<Stroke>) : CanvasOp()
}

/**
 * Classic bounded undo/redo stack. Pure Kotlin, unit-tested.
 * Applying is done by the caller via [UndoRedoListener]; the stack only
 * tracks operation history.
 */
class UndoRedoStack(private val capacity: Int = 100) {

    private val undoStack = ArrayDeque<CanvasOp>()
    private val redoStack = ArrayDeque<CanvasOp>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(op: CanvasOp) {
        undoStack.addLast(op)
        if (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Returns the op to revert, or null. */
    fun undo(): CanvasOp? {
        val op = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(op)
        return op
    }

    /** Returns the op to re-apply, or null. */
    fun redo(): CanvasOp? {
        val op = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(op)
        return op
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
