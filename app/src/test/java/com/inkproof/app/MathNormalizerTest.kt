package com.inkproof.app

import com.inkproof.app.check.MathNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class MathNormalizerTest {

    @Test
    fun `plain linear equation is untouched`() {
        assertEquals("2x+6=14", MathNormalizer.normalize("2x+6=14"))
        assertEquals("3x-5=10", MathNormalizer.normalize("3x-5=10"))
    }

    @Test
    fun `implicit exponent becomes caret`() {
        assertEquals("x^2-5x+6=0", MathNormalizer.normalize("x2-5x+6=0"))
        assertEquals("dy/dx=3x^2+2x", MathNormalizer.normalize("dy/dx=3x2+2x"))
        assertEquals("y=2x^3", MathNormalizer.normalize("y=2x3"))
    }

    @Test
    fun `function names are never mangled into powers`() {
        assertEquals("sin2x", MathNormalizer.normalize("sin2x"))
        assertEquals("log2", MathNormalizer.normalize("log2"))
        assertEquals("cos3x+1", MathNormalizer.normalize("cos3x+1"))
    }

    @Test
    fun `unicode superscripts and minus are normalized`() {
        assertEquals("x^2-4=0", MathNormalizer.normalize("x\u00B2\u22124=0"))
        assertEquals("a^3+b^3", MathNormalizer.normalize("a\u00B3+b\u00B3"))
    }

    @Test
    fun `arrow and multiplication variants become ascii`() {
        assertEquals(
            "lim x->0 sin(x)/x",
            MathNormalizer.normalize("lim x\u21920 sin(x)/x")
        )
        assertEquals("3*4=12", MathNormalizer.normalize("3\u00D74=12"))
    }

    @Test
    fun `letter O between digits becomes zero`() {
        assertEquals("10+5=15", MathNormalizer.normalize("1O+5=15"))
        assertEquals("102", MathNormalizer.normalize("1O2"))
    }

    @Test
    fun `spacing is tidied without changing content`() {
        assertEquals("x^2 + 1", MathNormalizer.normalize("x ^ 2  +  1"))
        assertEquals("", MathNormalizer.normalize("   "))
    }

    @Test
    fun `integral and root symbols pass through unchanged`() {
        assertEquals("\u222B2x dx = x^2 + C", MathNormalizer.normalize("\u222B2x dx = x2 + C"))
        assertEquals("\u221A(x+1)", MathNormalizer.normalize("\u221A(x+1)"))
    }
}
