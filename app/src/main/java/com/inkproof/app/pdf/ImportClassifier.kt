package com.inkproof.app.pdf

/**
 * Decides what InkProof can do with a picked file — BEFORE trying to import,
 * so unsupported formats are rejected gracefully with a clear reason instead
 * of crashing or silently failing.
 */
object ImportClassifier {

    enum class Kind { PDF, IMAGE, TEXT, HTML, CSV, UNSUPPORTED }

    data class Result(
        val kind: Kind,
        /** Human-readable reason shown when kind == UNSUPPORTED. */
        val reason: String = ""
    )

    private val imageExts = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
    private val textExts = setOf("txt", "md", "markdown", "log")
    private val htmlExts = setOf("html", "htm", "xhtml")
    private val officeExts = mapOf(
        "doc" to "Word", "docx" to "Word", "rtf" to "RTF",
        "odt" to "OpenDocument",
        "xls" to "Excel", "xlsx" to "Excel", "ods" to "OpenDocument",
        "ppt" to "PowerPoint", "pptx" to "PowerPoint", "odp" to "OpenDocument"
    )

    /**
     * Classify by MIME type first (what the document provider reports),
     * falling back to the file extension.
     */
    fun classify(mimeType: String?, fileName: String?): Result {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

        // --- direct, fully supported formats ---
        if (mime == "application/pdf" || ext == "pdf") return Result(Kind.PDF)
        if (mime.startsWith("image/svg") || ext == "svg") {
            return Result(
                Kind.UNSUPPORTED,
                "SVG vector images aren't supported yet. Convert to PNG or JPG first."
            )
        }
        if (mime.startsWith("image/") || ext in imageExts) return Result(Kind.IMAGE)
        if (mime == "text/csv" || mime == "text/comma-separated-values" || ext == "csv" ||
            ext == "tsv" || mime == "text/tab-separated-values"
        ) return Result(Kind.CSV)
        if (mime == "text/html" || mime == "application/xhtml+xml" || ext in htmlExts) {
            return Result(Kind.HTML)
        }
        if (mime.startsWith("text/") || ext in textExts || mime == "application/json" ||
            ext == "json"
        ) return Result(Kind.TEXT)

        // --- known office formats: honest rejection with a workaround ---
        val office = officeExts[ext] ?: when (mime) {
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "Word"
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "Excel"
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PowerPoint"
            "application/rtf", "text/rtf" -> "RTF"
            else -> null
        }
        if (office != null) {
            return Result(
                Kind.UNSUPPORTED,
                "$office files can't be opened directly. " +
                    "Export the document as a PDF and import that instead."
            )
        }

        return Result(
            Kind.UNSUPPORTED,
            "This file type isn't supported. InkProof can import PDF, images " +
                "(PNG, JPG, WEBP, BMP), and text files (TXT, MD, HTML, CSV)."
        )
    }
}
