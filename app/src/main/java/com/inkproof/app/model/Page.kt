package com.inkproof.app.model

/** Background templates for pages. Changing a template never destroys content. */
enum class PageTemplate {
    BLANK,
    RULED,
    GRID,
    DOT_GRID,
    ENGINEERING,
    MATH_WORKSHEET
}

/** The two page concepts in InkProof. */
enum class PageKind {
    /** Freeform mathematics / notes. */
    NOTE,
    /** Structured Question -> Solution page. */
    MATH_QUESTION,
    /** An imported PDF page with ink annotation on top. */
    PDF
}
