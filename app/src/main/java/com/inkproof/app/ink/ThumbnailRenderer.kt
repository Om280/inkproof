package com.inkproof.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.Stroke

/**
 * Renders real page content into small bitmaps for library/page cards.
 * Every thumbnail reflects the page's actual strokes — no fake previews.
 */
object ThumbnailRenderer {

    fun render(
        strokes: List<Stroke>,
        template: PageTemplate,
        pageWidth: Float,
        pageHeight: Float,
        targetWidth: Int = 280
    ): Bitmap {
        val scale = targetWidth / pageWidth
        val targetHeight = (pageHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        TemplateRenderer.draw(canvas, template, pageWidth, pageHeight)
        for (s in strokes) {
            StrokeRenderer.draw(canvas, s)
        }
        return bitmap
    }
}
