package com.inkproof.app.model

/** How the question content was provided. */
enum class QuestionContentType {
    TYPED,
    HANDWRITTEN,
    IMAGE,
    PDF,
    PASTED
}

/**
 * A math Question is a first-class document object, not a visual line.
 *
 * It owns:
 *  - the question content (typed text, ink region, or imported media)
 *  - a dedicated solution region where the student writes
 *  - version counters used to invalidate cached check results
 */
data class Question(
    val id: String = newId(),
    val pageId: String,
    val orderIndex: Int,
    val contentType: QuestionContentType = QuestionContentType.TYPED,
    /** Typed/pasted question text. Never OCR'd — it is already text. */
    val typedText: String? = null,
    /** Path to an imported image/PDF-crop, when applicable. */
    val mediaPath: String? = null,
    /** Vertical band (page space) that holds the question content. */
    val questionTop: Float,
    val questionBottom: Float,
    /** Vertical band (page space) reserved for the student's solution. */
    val solutionTop: Float,
    val solutionBottom: Float,
    /** Incremented whenever question content changes. */
    val contentVersion: Long = 1,
    /** Incremented whenever solution ink changes. */
    val solutionVersion: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
