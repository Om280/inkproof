package com.inkproof.app.model

/**
 * Clipboard semantics: a paste is a NEW independent copy — new stroke IDs,
 * same appearance, offset position. Never shared references.
 */
object ClipboardOps {
    fun cloneForPaste(
        clipboard: List<Stroke>,
        targetPageId: String,
        dx: Float = 48f,
        dy: Float = 48f
    ): List<Stroke> = clipboard.map { s ->
        s.translated(dx, dy).copy(
            id = newId(),
            pageId = targetPageId,
            // Pasted ink is freeform until it lands in a question region.
            questionId = null,
            role = StrokeRole.FREEFORM
        )
    }
}
