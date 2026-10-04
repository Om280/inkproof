package com.inkproof.app

import com.inkproof.app.ink.CanvasOp
import com.inkproof.app.ink.UndoRedoStack
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoRedoStackTest {

    private fun stroke(id: String) = Stroke(
        id = id, pageId = "page", color = 0, baseWidth = 3f,
        points = listOf(StrokePoint(0f, 0f, 1f, 0))
    )

    @Test
    fun `undo and redo return operations in order`() {
        val stack = UndoRedoStack()
        val a = CanvasOp.Add(listOf(stroke("a")))
        val b = CanvasOp.Add(listOf(stroke("b")))
        stack.push(a)
        stack.push(b)

        assertEquals(b, stack.undo())
        assertEquals(a, stack.undo())
        assertNull(stack.undo())

        assertEquals(a, stack.redo())
        assertEquals(b, stack.redo())
        assertNull(stack.redo())
    }

    @Test
    fun `new operation clears redo history`() {
        val stack = UndoRedoStack()
        stack.push(CanvasOp.Add(listOf(stroke("a"))))
        stack.undo()
        assertTrue(stack.canRedo)
        stack.push(CanvasOp.Remove(listOf(stroke("b"))))
        assertFalse(stack.canRedo)
    }

    @Test
    fun `capacity is bounded`() {
        val stack = UndoRedoStack(capacity = 5)
        repeat(20) { stack.push(CanvasOp.Add(listOf(stroke("s$it")))) }
        var count = 0
        while (stack.undo() != null) count++
        assertEquals(5, count)
    }

    @Test
    fun `replace op carries before and after`() {
        val stack = UndoRedoStack()
        val before = listOf(stroke("x"))
        val after = listOf(stroke("x").copy(color = 99))
        stack.push(CanvasOp.Replace(before, after))
        val op = stack.undo() as CanvasOp.Replace
        assertEquals(0, op.before[0].color)
        assertEquals(99, op.after[0].color)
    }
}
