package app.inkproof.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.inkproof.core.ink.pressureWidth
import app.inkproof.core.model.ImageItem
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.InkTool
import app.inkproof.core.model.Page
import app.inkproof.core.model.PageLayout
import app.inkproof.core.model.PageObject
import app.inkproof.core.model.PageTemplate
import app.inkproof.core.model.PaperColor
import app.inkproof.core.model.Question
import app.inkproof.core.model.QLayout
import app.inkproof.core.model.ShapeItem
import app.inkproof.core.model.Stroke
import app.inkproof.core.model.TextItem
import app.inkproof.core.model.templateLineColor
import app.inkproof.core.shape.arrowHead
import app.inkproof.core.shape.axisRect
import app.inkproof.core.shape.lineEnds
import app.inkproof.core.shape.trianglePoints

object InkPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
    }

    fun drawPage(
        canvas: Canvas,
        page: Page,
        layout: PageLayout,
        images: Map<String, Bitmap>,
        width: Float,
        height: Float,
    ) {
        canvas.save()
        canvas.scale(width / layout.pageWidth, height / layout.pageHeight)
        drawPaper(canvas, page.paper, layout.pageWidth, layout.pageHeight)
        drawTemplate(canvas, page.template, page.paper, layout.pageWidth, layout.pageHeight)
        page.backgroundAssetId?.let { id ->
            images[id]?.let { bmp ->
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, layout.pageWidth, layout.pageHeight), null)
            }
        }
        for (q in page.questions) drawQuestionChrome(canvas, q, layout.pageWidth, page.paper.isDark())
        for (obj in page.objects) drawObject(canvas, obj, images)
        canvas.restore()
    }

    fun drawPaper(canvas: Canvas, paper: PaperColor, w: Float, h: Float) {
        fill.color = paper.argb.toInt()
        canvas.drawRect(0f, 0f, w, h, fill)
    }

    fun drawTemplate(canvas: Canvas, template: PageTemplate, paper: PaperColor, w: Float, h: Float) {
        val color = templateLineColor(paper).toInt()
        stroke.color = color
        stroke.strokeWidth = 1.1f
        fill.color = color
        when (template) {
            PageTemplate.BLANK -> Unit
            PageTemplate.RULED -> {
                var y = 72f
                while (y < h) {
                    canvas.drawLine(64f, y, w - 28f, y, stroke)
                    y += 36f
                }
                stroke.strokeWidth = 1.6f
                stroke.color = if (paper.isDark()) 0x55E07A72.toInt() else 0x55B42318.toInt()
                canvas.drawLine(72f, 24f, 72f, h - 24f, stroke)
            }
            PageTemplate.GRID -> grid(canvas, w, h, 40f, 40f, 1.1f)
            PageTemplate.DOT -> {
                var y = 32f
                while (y < h - 16f) {
                    var x = 32f
                    while (x < w - 16f) {
                        canvas.drawCircle(x, y, 1.4f, fill)
                        x += 32f
                    }
                    y += 32f
                }
            }
            PageTemplate.ENGINEERING -> {
                grid(canvas, w, h, 20f, 20f, 0.7f)
                stroke.strokeWidth = 1.5f
                grid(canvas, w, h, 100f, 100f, 1.5f)
            }
            PageTemplate.MATH -> {
                grid(canvas, w, h, 48f, 48f, 0.8f)
                var y = 64f
                stroke.strokeWidth = 1.2f
                while (y < h) {
                    canvas.drawLine(28f, y, w - 28f, y, stroke)
                    y += 48f
                }
                stroke.strokeWidth = 1.8f
                stroke.color = if (paper.isDark()) 0x66E07A72.toInt() else 0x66B42318.toInt()
                canvas.drawLine(80f, 20f, 80f, h - 20f, stroke)
            }
        }
    }

    private fun grid(canvas: Canvas, w: Float, h: Float, stepX: Float, stepY: Float, width: Float) {
        stroke.strokeWidth = width
        var x = stepX
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, stroke)
            x += stepX
        }
        var y = stepY
        while (y < h) {
            canvas.drawLine(0f, y, w, y, stroke)
            y += stepY
        }
    }

    fun drawObject(canvas: Canvas, obj: PageObject, images: Map<String, Bitmap> = emptyMap()) {
        when (obj) {
            is Stroke -> drawStroke(canvas, obj.points, obj.color.toInt(), obj.width, obj.alpha, obj.tool)
            is ShapeItem -> drawShape(canvas, obj)
            is TextItem -> {
                text.color = obj.color.toInt()
                text.textSize = obj.size
                canvas.drawText(obj.text, obj.x, obj.y + obj.size, text)
            }
            is ImageItem -> {
                val bmp = images[obj.assetId]
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, RectF(obj.x, obj.y, obj.x + obj.width, obj.y + obj.height), null)
                } else {
                    stroke.color = 0xFF8A8175.toInt()
                    stroke.strokeWidth = 1.5f
                    canvas.drawRect(obj.x, obj.y, obj.x + obj.width, obj.y + obj.height, stroke)
                }
            }
        }
    }

    fun drawStroke(
        canvas: Canvas,
        points: List<InkPoint>,
        color: Int,
        width: Float,
        alpha: Float,
        tool: InkTool,
    ) {
        if (points.size < 2) return
        stroke.color = color
        stroke.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        stroke.style = Paint.Style.STROKE
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            stroke.strokeWidth = (pressureWidth(width, a.pressure, tool) + pressureWidth(width, b.pressure, tool)) / 2f
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
        }
        stroke.alpha = 255
    }

    fun drawShape(canvas: Canvas, shape: ShapeItem) {
        stroke.color = shape.color.toInt()
        stroke.alpha = 255
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = shape.strokeWidth
        fill.color = shape.color.toInt()
        when (shape.kind) {
            app.inkproof.core.model.ShapeKind.LINE -> {
                val (a, b) = lineEnds(shape)
                canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
            }
            app.inkproof.core.model.ShapeKind.ARROW -> {
                val (a, b) = lineEnds(shape)
                canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
                val head = arrowHead(shape, 14f + shape.strokeWidth)
                val path = Path()
                path.moveTo(head[0].x, head[0].y)
                path.lineTo(head[1].x, head[1].y)
                path.lineTo(head[2].x, head[2].y)
                path.close()
                fill.style = Paint.Style.FILL
                canvas.drawPath(path, fill)
            }
            app.inkproof.core.model.ShapeKind.RECTANGLE,
            app.inkproof.core.model.ShapeKind.SQUARE,
            -> {
                val r = axisRect(shape)
                canvas.drawRect(r[0].x, r[0].y, r[2].x, r[2].y, stroke)
            }
            app.inkproof.core.model.ShapeKind.CIRCLE,
            app.inkproof.core.model.ShapeKind.ELLIPSE,
            -> {
                val r = axisRect(shape)
                canvas.drawOval(r[0].x, r[0].y, r[2].x, r[2].y, stroke)
            }
            app.inkproof.core.model.ShapeKind.TRIANGLE -> {
                val pts = trianglePoints(shape)
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                path.lineTo(pts[1].x, pts[1].y)
                path.lineTo(pts[2].x, pts[2].y)
                path.close()
                canvas.drawPath(path, stroke)
            }
        }
    }

    fun drawQuestionChrome(canvas: Canvas, q: Question, pageWidth: Float, dark: Boolean) {
        fill.style = Paint.Style.FILL
        fill.color = if (dark) 0x14FFFFFF else 0x10000000
        canvas.drawRect(8f, q.y, pageWidth - 8f, q.y + q.height, fill)
        fill.color = 0xFFB42318.toInt()
        canvas.drawRect(8f, q.y, 13f, q.y + q.height, fill)
        text.color = if (dark) 0xFFD9D3C8.toInt() else 0xFF5C564E.toInt()
        text.textSize = 15f
        text.letterSpacing = 0.08f
        canvas.drawText("QUESTION", 24f, q.y + 24f, text)
        val prompt = QLayout.promptRect(q, pageWidth)
        stroke.color = if (dark) 0x33FFFFFF else 0x22000000
        stroke.strokeWidth = 1.2f
        canvas.drawLine(24f, prompt.bottom + 4f, pageWidth - 24f, prompt.bottom + 4f, stroke)
        canvas.drawText("YOUR SOLUTION", 24f, QLayout.solutionTop(q) - 6f, text)
        val labels = arrayOf("Check", "Solve", "Read")
        text.letterSpacing = 0f
        text.textSize = 16f
        for (i in labels.indices) {
            val r = QLayout.buttonRect(q, pageWidth, i)
            stroke.color = if (dark) 0x88E07A72.toInt() else 0xFFB42318.toInt()
            canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, 10f, 10f, stroke)
            text.color = stroke.color
            canvas.drawText(labels[i], r.left + 14f, r.centerY + 6f, text)
        }
        text.letterSpacing = 0f
    }
}
