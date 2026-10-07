package com.inkproof.app.ink

import android.graphics.Matrix

/**
 * Maps between page space (document units) and screen space.
 * Strokes are stored in page space so ink stays sharp at any zoom.
 */
class CanvasCamera {
    var scale: Float = 1f
        private set
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    var minScale: Float = 0.25f
    var maxScale: Float = 6f

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val tmp = FloatArray(2)

    fun matrix(): Matrix {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        return matrix
    }

    fun screenToPageX(sx: Float): Float = (sx - offsetX) / scale
    fun screenToPageY(sy: Float): Float = (sy - offsetY) / scale
    fun pageToScreenX(px: Float): Float = px * scale + offsetX
    fun pageToScreenY(py: Float): Float = py * scale + offsetY

    fun panBy(dxScreen: Float, dyScreen: Float) {
        offsetX += dxScreen
        offsetY += dyScreen
    }

    /** Zoom around a screen-space focal point. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val actual = newScale / scale
        // Keep the focal point fixed on screen.
        offsetX = focusX - (focusX - offsetX) * actual
        offsetY = focusY - (focusY - offsetY) * actual
        scale = newScale
    }

    fun set(scale: Float, offsetX: Float, offsetY: Float) {
        this.scale = scale.coerceIn(minScale, maxScale)
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    /** Fit a page of the given size into the given viewport with padding. */
    fun fitPage(pageWidth: Float, pageHeight: Float, viewWidth: Float, viewHeight: Float) {
        if (pageWidth <= 0 || viewWidth <= 0) return
        val padding = 24f
        val s = minOf(
            (viewWidth - padding * 2) / pageWidth,
            (viewHeight - padding * 2) / pageHeight
        ).coerceIn(minScale, maxScale)
        scale = s
        offsetX = (viewWidth - pageWidth * s) / 2f
        offsetY = padding
    }
}
