package com.inkproof.app.model

/** A typed text box placed on a page (editable, persistent). */
data class TextObject(
    val id: String = newId(),
    val pageId: String,
    val questionId: String? = null,
    val text: String,
    val x: Float,
    val y: Float,
    val widthPts: Float = 600f,
    val fontSize: Float = 34f,
    val color: Int = 0xFF222838.toInt(),
    val createdAt: Long = System.currentTimeMillis()
)
