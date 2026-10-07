package com.inkproof.app.check

/**
 * Conservative post-processing for recognized math text.
 *
 * The on-device model is a general TEXT model, so common math notation
 * comes back slightly mangled ("x2" for x², unicode minus signs, stray
 * spacing). These rules only normalize UNAMBIGUOUS cases — they never
 * guess at what the student "probably meant". Anything ambiguous is left
 * exactly as recognized so the user can correct it in the preview.
 */
object MathNormalizer {

    // A single letter followed by digits, where the letter is NOT part of a
    // word (so "x2" -> "x^2" and "3x2" -> "3x^2", but "sin2x" is untouched).
    private val IMPLICIT_POWER = Regex("(?<![A-Za-z])([A-Za-z])(\\d+)")

    // Letter O that is clearly a digit zero: adjacent to digits.
    private val O_AFTER_DIGIT = Regex("(?<=\\d)[Oo]")
    private val O_BEFORE_DIGIT = Regex("[Oo](?=\\d)")

    fun normalize(raw: String): String {
        if (raw.isBlank()) return raw.trim()
        var s = raw.trim()

        // Unicode dashes/minus signs -> ASCII minus.
        s = s.replace('\u2212', '-') // −
            .replace('\u2013', '-') // –
            .replace('\u2014', '-') // —

        // Unicode superscripts -> caret notation.
        s = s.replace("\u00B2", "^2")
            .replace("\u00B3", "^3")
            .replace("\u00B9", "^1")
            .replace("\u2070", "^0")
            .replace("\u2074", "^4")
            .replace("\u2075", "^5")
            .replace("\u2076", "^6")
            .replace("\u2077", "^7")
            .replace("\u2078", "^8")
            .replace("\u2079", "^9")

        // Arrow variants -> ASCII (keeps "lim x->0" machine-friendly).
        s = s.replace("\u2192", "->")

        // Multiplication look-alikes.
        s = s.replace('\u00D7', '*') // ×
            .replace('\u22C5', '*') // ⋅
            .replace('\u2219', '*') // ∙

        // O/o used as zero between digits (e.g. "1O" -> "10").
        s = O_AFTER_DIGIT.replace(s, "0")
        s = O_BEFORE_DIGIT.replace(s, "0")

        // Implicit exponent: standalone letter followed by digits is a
        // power in handwritten math ("x2-5x+6" -> "x^2-5x+6"). Skipped
        // when the letter ends a word (sin2x, log2 stay untouched).
        s = IMPLICIT_POWER.replace(s) { m ->
            "${m.groupValues[1]}^${m.groupValues[2]}"
        }

        // Tidy spacing: collapse runs, remove spaces around ^.
        s = s.replace(Regex("\\s*\\^\\s*"), "^")
        s = s.replace(Regex("\\s+"), " ").trim()

        return s
    }
}
