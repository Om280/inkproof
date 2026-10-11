package app.inkproof.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.inkproof.core.ink.ActiveStrokeController
import app.inkproof.core.model.Camera
import app.inkproof.core.model.EraserSize
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.InkTool
import app.inkproof.core.model.Page
import app.inkproof.core.model.PageLayout
import app.inkproof.core.model.QuestionHit
import app.inkproof.core.model.ShapeItem
import app.inkproof.core.model.ShapeKind
import app.inkproof.core.model.ToolMode
import app.inkproof.core.model.clampCamera
import app.inkproof.core.model.effectiveTool
import app.inkproof.core.model.hitQuestion
import app.inkproof.core.shape.shapeFromDrag
import kotlin.math.hypot

/**
 * Live ink surface. Stylus points are appended and invalidated immediately.
 * Persistence, recognition, and network work are never called from the move path.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Host {
        fun session(): app.inkproof.core.edit.NotebookSession?
        fun tool(): ToolMode
        fun penColor(): Long
        fun markerColor(): Long
        fun penWidth(): Float
        fun markerWidth(): Float
        fun markerAlpha(): Float
        fun eraserSize(): EraserSize
        fun stabilizer(): app.inkproof.core.model.StabilizerLevel
        fun shapeKind(): ShapeKind
        fun shapeWidth(): Float
        fun holdEnabled(): Boolean
        fun holdMs(): Long
        fun holdConfidence(): Float
        fun selection(): Set<String>
        fun selectionPage(): String?
        fun contentGeneration(): Int
        fun deskColor(): Int
        fun bitmapFor(assetId: String): Bitmap?
        fun onCommitStroke(pageId: String, points: List<InkPoint>, tool: InkTool, color: Long, width: Float, alpha: Float)
        fun onCommitShape(pageId: String, shape: ShapeItem)
        fun onErase(pageId: String, x: Float, y: Float, radius: Float)
        fun onQuestionHit(pageId: String, questionId: String, hit: QuestionHit)
        fun onLasso(pageId: String, points: List<InkPoint>)
        fun onMoveSelection(pageId: String, dx: Float, dy: Float)
        fun onTapSelection(pageId: String, x: Float, y: Float)
        fun onCamera(camera: Camera)
        fun clearSelection()
        fun undo()
        fun redo()
    }

    lateinit var host: Host

    private val active = ActiveStrokeController()
    private val lasso = ArrayList<InkPoint>()
    private var draftShape: ShapeItem? = null
    private var gesture = Gesture.NONE
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downPage: String? = null
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var holdFired = false
    private var holdArmed = false
    private var pinchStart = 0f
    private var pinchScale = 1f
    private var hoverX = 0f
    private var hoverY = 0f
    private var hover = false
    private val bitmaps = HashMap<String, Bitmap>()
    private var builtGeneration = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val predictor = if (Build.VERSION.SDK_INT >= 34) android.view.MotionPredictor(this) else null
    private var predicted: InkPoint? = null

    private val holdRunnable = Runnable {
        holdFired = true
        holdArmed = false
        invalidate()
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun finishActive() {
        if (gesture == Gesture.DRAW) commitDraw(host.holdMs())
        gesture = Gesture.NONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!::host.isInitialized) return
        val session = host.session() ?: return
        canvas.drawColor(host.deskColor())
        if (builtGeneration != host.contentGeneration()) {
            bitmaps.values.forEach { it.recycle() }
            bitmaps.clear()
            builtGeneration = host.contentGeneration()
        }
        val layout = session.layout
        val camera = session.camera
        val pages = session.orderedPages()
        for ((index, page) in pages.withIndex()) {
            val top = layout.pageTop(index)
            val (sx, sy) = camera.docToScreen(0f, top, width, layout)
            val bottom = sy + layout.pageHeight * camera.scale
            if (bottom < 0 || sy > height) continue
            canvas.save()
            canvas.translate(sx, sy)
            canvas.scale(camera.scale, camera.scale)
            canvas.clipRect(0f, 0f, layout.pageWidth, layout.pageHeight)
            drawPageContent(canvas, page, layout)
            if (active.pageId == page.id && active.active) {
                val preview = if (holdFired) active.finishStabilized() else active.preview()
                InkPainter.drawStroke(canvas, preview, active.color.toInt(), active.width, active.alpha, active.tool)
                predicted?.let { tip ->
                    val last = preview.lastOrNull()
                    if (last != null) {
                        paint.color = active.color.toInt()
                        paint.alpha = 90
                        paint.strokeWidth = active.width
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(last.x, last.y, tip.x, tip.y, paint)
                        paint.alpha = 255
                    }
                }
            }
            draftShape?.let { if (gesture == Gesture.SHAPE) InkPainter.drawShape(canvas, it) }
            if (gesture == Gesture.LASSO && downPage == page.id && lasso.size > 1) {
                val path = Path()
                path.moveTo(lasso[0].x, lasso[0].y)
                for (p in lasso.drop(1)) path.lineTo(p.x, p.y)
                paint.style = Paint.Style.STROKE
                paint.color = 0xFF1F4B57.toInt()
                paint.strokeWidth = 1.6f / camera.scale
                canvas.drawPath(path, paint)
            }
            val selected = if (host.selectionPage() == page.id) host.selection() else emptySet()
            if (selected.isNotEmpty()) {
                paint.style = Paint.Style.STROKE
                paint.color = 0xFFB42318.toInt()
                paint.strokeWidth = 1.4f / camera.scale
                for (obj in page.objects) {
                    if (obj.id !in selected) continue
                    val b = app.inkproof.core.model.objectBounds(obj)
                    canvas.drawRect(b.left, b.top, b.right, b.bottom, paint)
                }
            }
            canvas.restore()
        }
        if (hover && host.tool() != ToolMode.LASSO) {
            paint.style = Paint.Style.STROKE
            paint.color = 0xAA1C1917.toInt()
            paint.strokeWidth = 1.5f
            val radius = when (host.tool()) {
                ToolMode.MARKER -> host.markerWidth()
                ToolMode.ERASER -> host.eraserSize().radius
                else -> host.penWidth()
            } * session.camera.scale * 0.5f
            canvas.drawCircle(hoverX, hoverY, radius.coerceAtLeast(4f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xCC1C1917.toInt()
        paint.textSize = 28f
        canvas.drawText("${(session.camera.scale * 100).toInt()}%", width - 140f, height - 28f, paint)
    }

    private fun drawPageContent(canvas: Canvas, page: Page, layout: PageLayout) {
        InkPainter.drawPaper(canvas, page.paper, layout.pageWidth, layout.pageHeight)
        InkPainter.drawTemplate(canvas, page.template, page.paper, layout.pageWidth, layout.pageHeight)
        page.backgroundAssetId?.let { id ->
            host.bitmapFor(id)?.let { bmp ->
                canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, layout.pageWidth, layout.pageHeight), null)
            }
        }
        for (q in page.questions) InkPainter.drawQuestionChrome(canvas, q, layout.pageWidth, page.paper.isDark())
        val bmp = bitmapFor(page, layout)
        canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, layout.pageWidth, layout.pageHeight), null)
    }

    private fun bitmapFor(page: Page, layout: PageLayout): Bitmap {
        bitmaps[page.id]?.let { return it }
        val w = 900
        val h = (900f * layout.pageHeight / layout.pageWidth).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w / layout.pageWidth, h / layout.pageHeight)
        val images = HashMap<String, Bitmap>()
        for (obj in page.objects) {
            if (obj is app.inkproof.core.model.ImageItem) {
                host.bitmapFor(obj.assetId)?.let { images[obj.assetId] = it }
            }
        }
        for (obj in page.objects) InkPainter.drawObject(c, obj, images)
        bitmaps[page.id] = bmp
        return bmp
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::host.isInitialized) return false
        requestUnbufferedDispatch(event)
        val stylus = stylusIndex(event)
        if (stylus >= 0) return handleStylus(event, stylus)
        if (event.pointerCount >= 2) return handlePinch(event)
        if (isPalm(event, 0) && event.pointerCount == 1) return true
        return handleFinger(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            hover = true
            hoverX = event.x
            hoverY = event.y
            invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hover = false
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleStylus(event: MotionEvent, index: Int): Boolean {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (gesture != Gesture.NONE) return true
                pointerId = id
                beginStylus(event, index, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (id != pointerId && event.findPointerIndex(pointerId) >= 0) {
                    val idx = event.findPointerIndex(pointerId)
                    moveStylus(event, idx, event.getX(idx), event.getY(idx))
                } else if (id == pointerId) {
                    moveStylus(event, index, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) endStylus()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun beginStylus(event: MotionEvent, index: Int, x: Float, y: Float) {
        val session = host.session() ?: return
        val mapped = map(x, y) ?: return
        downX = x
        downY = y
        lastX = x
        lastY = y
        downPage = mapped.pageId
        downLocalX = mapped.x
        downLocalY = mapped.y
        holdFired = false
        predicted = null
        val eraserEnd = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val button = event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
        val tool = effectiveTool(host.tool(), eraserEnd, button)
        val page = session.page(mapped.pageId)
        if (tool == ToolMode.PEN || tool == ToolMode.MARKER) {
            val hit = questionButton(page, mapped.x, mapped.y, session.layout.pageWidth)
            if (hit != null) {
                gesture = Gesture.TAP
                return
            }
        }
        if (host.selection().isNotEmpty() && host.selectionPage() == mapped.pageId && tool != ToolMode.ERASER) {
            val inside = page.objects.any { obj ->
                obj.id in host.selection() && app.inkproof.core.model.objectBounds(obj).inflate(12f).contains(mapped.x, mapped.y)
            }
            if (inside) {
                gesture = Gesture.MOVE
                return
            } else {
                host.clearSelection()
            }
        }
        when (tool) {
            ToolMode.ERASER -> {
                gesture = Gesture.ERASE
                host.onErase(mapped.pageId, mapped.x, mapped.y, host.eraserSize().radius / session.camera.scale)
            }
            ToolMode.LASSO -> {
                gesture = Gesture.LASSO
                lasso.clear()
                lasso.add(InkPoint(mapped.x, mapped.y, 1f, event.eventTime))
            }
            ToolMode.SHAPE -> {
                gesture = Gesture.SHAPE
                draftShape = shapeFromDrag("draft", host.shapeKind(), mapped.x, mapped.y, mapped.x, mapped.y, host.penColor(), host.shapeWidth())
            }
            ToolMode.TEXT -> gesture = Gesture.TAP
            else -> {
                gesture = Gesture.DRAW
                val inkTool = if (tool == ToolMode.MARKER) InkTool.MARKER else InkTool.PEN
                val color = if (inkTool == InkTool.MARKER) host.markerColor() else host.penColor()
                val width = if (inkTool == InkTool.MARKER) host.markerWidth() else host.penWidth()
                val alpha = if (inkTool == InkTool.MARKER) host.markerAlpha() else 1f
                active.begin(mapped.pageId, point(event, index, mapped.x, mapped.y), inkTool, color, width, alpha, host.stabilizer())
                armHold()
                if (Build.VERSION.SDK_INT >= 34) predictor?.record(event)
            }
        }
        invalidate()
    }

    private fun moveStylus(event: MotionEvent, index: Int, x: Float, y: Float) {
        val session = host.session() ?: return
        if (gesture == Gesture.DRAW) {
            val hist = event.historySize
            for (h in 0 until hist) {
                val hx = event.getHistoricalX(index, h)
                val hy = event.getHistoricalY(index, h)
                val mapped = map(hx, hy) ?: continue
                if (mapped.pageId != active.pageId) continue
                active.add(
                    InkPoint(
                        mapped.x,
                        mapped.y,
                        event.getHistoricalPressure(index, h),
                        event.getHistoricalEventTime(h),
                    ),
                )
            }
            val mapped = map(x, y)
            if (mapped != null && mapped.pageId == active.pageId) {
                active.add(point(event, index, mapped.x, mapped.y))
            }
            val moved = hypot(x - lastX, y - lastY)
            if (moved > 4f) {
                removeCallbacks(holdRunnable)
                holdArmed = false
                holdFired = false
            } else if (!holdArmed && !holdFired && host.holdEnabled()) {
                armHold()
            }
            if (Build.VERSION.SDK_INT >= 34) {
                predictor?.record(event)
                val future = predictor?.predict(event.eventTime + 12)
                if (future != null) {
                    val pm = map(future.x, future.y)
                    predicted = pm?.let { InkPoint(it.x, it.y) }
                    future.recycle()
                }
            }
            lastX = x
            lastY = y
            invalidate()
            return
        }
        val mapped = map(x, y) ?: return
        when (gesture) {
            Gesture.ERASE -> host.onErase(mapped.pageId, mapped.x, mapped.y, host.eraserSize().radius / session.camera.scale)
            Gesture.LASSO -> if (mapped.pageId == downPage) lasso.add(InkPoint(mapped.x, mapped.y))
            Gesture.SHAPE -> {
                draftShape = shapeFromDrag(
                    "draft",
                    host.shapeKind(),
                    downLocalX,
                    downLocalY,
                    mapped.x,
                    mapped.y,
                    host.penColor(),
                    host.shapeWidth(),
                )
            }
            Gesture.MOVE -> {
                val dx = (x - lastX) / session.camera.scale
                val dy = (y - lastY) / session.camera.scale
                downPage?.let { host.onMoveSelection(it, dx, dy) }
            }
            else -> Unit
        }
        lastX = x
        lastY = y
        invalidate()
    }

    private fun endStylus() {
        when (gesture) {
            Gesture.DRAW -> commitDraw(if (holdFired) host.holdMs() else 0L)
            Gesture.LASSO -> downPage?.let { host.onLasso(it, lasso.toList()) }
            Gesture.SHAPE -> {
                val shape = draftShape
                val page = downPage
                if (shape != null && page != null && (kotlin.math.abs(shape.w) > 4f || kotlin.math.abs(shape.h) > 4f)) {
                    host.onCommitShape(page, shape.copy(id = java.util.UUID.randomUUID().toString()))
                }
            }
            Gesture.TAP -> {
                val session = host.session()
                val pageId = downPage
                if (session != null && pageId != null) {
                    val page = session.page(pageId)
                    val hit = questionButton(page, downLocalX, downLocalY, session.layout.pageWidth)
                    if (hit != null) host.onQuestionHit(pageId, hit.first, hit.second)
                    else host.onTapSelection(pageId, downLocalX, downLocalY)
                }
            }
            else -> Unit
        }
        cancelGesture()
    }

    private fun commitDraw(holdMillis: Long) {
        val pageId = active.pageId ?: return
        val points = active.finishStabilized()
        val tool = active.tool
        val color = active.color
        val width = active.width
        val alpha = active.alpha
        active.cancel()
        if (points.size < 1) return
        if (holdMillis >= host.holdMs() && host.holdEnabled() && tool == InkTool.PEN) {
            val snap = app.inkproof.core.shape.commitAfterHold(
                points,
                holdMillis,
                true,
                host.holdMs(),
                host.holdConfidence(),
                java.util.UUID.randomUUID().toString(),
                color,
                width,
            )
            if (snap is app.inkproof.core.shape.HoldCommit.Snap && snap.snap.shape != null) {
                host.onCommitShape(pageId, snap.snap.shape!!)
                return
            }
        }
        host.onCommitStroke(pageId, points, tool, color, width, alpha)
    }

    private fun cancelGesture() {
        removeCallbacks(holdRunnable)
        active.cancel()
        lasso.clear()
        draftShape = null
        gesture = Gesture.NONE
        pointerId = -1
        predicted = null
        holdFired = false
        invalidate()
    }

    private fun handleFinger(event: MotionEvent): Boolean {
        val session = host.session() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.FINGER
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture != Gesture.FINGER && gesture != Gesture.SCROLL) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (gesture == Gesture.FINGER && hypot(event.x - downX, event.y - downY) > 12f) {
                    gesture = Gesture.SCROLL
                }
                if (gesture == Gesture.SCROLL) {
                    session.camera = clampCamera(session.camera.panByScreen(dx, dy), session.meta.pageOrder.size, width, height, session.layout)
                    host.onCamera(session.camera)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.FINGER) {
                    val mapped = map(event.x, event.y)
                    if (mapped != null) {
                        val page = session.page(mapped.pageId)
                        val hit = questionButton(page, mapped.x, mapped.y, session.layout.pageWidth)
                        if (hit != null) host.onQuestionHit(mapped.pageId, hit.first, hit.second)
                    }
                }
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> gesture = Gesture.NONE
        }
        return true
    }

    private fun handlePinch(event: MotionEvent): Boolean {
        val session = host.session() ?: return false
        if (event.pointerCount < 2) return true
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val cx = (event.getX(0) + event.getX(1)) / 2f
        val cy = (event.getY(0) + event.getY(1)) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture = Gesture.PINCH
                pinchStart = dist
                pinchScale = session.camera.scale
                cancelDrawOnly()
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture != Gesture.PINCH) return true
                val next = pinchScale * (dist / pinchStart)
                session.camera = clampCamera(
                    session.camera.zoomAt(cx, cy, next, width, session.layout),
                    session.meta.pageOrder.size,
                    width,
                    height,
                    session.layout,
                )
                val panX = cx - lastX
                val panY = cy - lastY
                if (lastX != 0f) {
                    session.camera = clampCamera(
                        session.camera.panByScreen(panX, panY),
                        session.meta.pageOrder.size,
                        width,
                        height,
                        session.layout,
                    )
                }
                lastX = cx
                lastY = cy
                host.onCamera(session.camera)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> gesture = Gesture.NONE
        }
        return true
    }

    private fun cancelDrawOnly() {
        removeCallbacks(holdRunnable)
        active.cancel()
        draftShape = null
    }

    private fun armHold() {
        if (!host.holdEnabled()) return
        holdArmed = true
        removeCallbacks(holdRunnable)
        postDelayed(holdRunnable, host.holdMs())
    }

    private fun questionButton(page: Page, x: Float, y: Float, pageWidth: Float): Pair<String, QuestionHit>? {
        for (q in page.questions.asReversed()) {
            when (val hit = hitQuestion(q, x, y, pageWidth)) {
                QuestionHit.CHECK, QuestionHit.SOLVE, QuestionHit.RECOGNIZE -> return q.id to hit
                else -> Unit
            }
        }
        return null
    }

    private data class Mapped(val pageId: String, val x: Float, val y: Float)

    private fun map(sx: Float, sy: Float): Mapped? {
        val session = host.session() ?: return null
        val (docX, docY) = session.camera.screenToDoc(sx, sy, width, session.layout)
        val index = session.layout.pageIndexAt(docY, session.meta.pageOrder.size) ?: return null
        val pageId = session.meta.pageOrder.getOrNull(index) ?: return null
        val localY = session.layout.localY(docY, index)
        if (docX < 0f || docX > session.layout.pageWidth) return null
        return Mapped(pageId, docX, localY)
    }

    private fun point(event: MotionEvent, index: Int, x: Float, y: Float): InkPoint =
        InkPoint(x, y, event.getPressure(index), event.eventTime)

    private fun stylusIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            val type = event.getToolType(i)
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) return i
        }
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return i
        }
        return -1
    }

    private fun isPalm(event: MotionEvent, index: Int): Boolean {
        val type = event.getToolType(index)
        if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER || type == MotionEvent.TOOL_TYPE_MOUSE) {
            return false
        }
        return event.getSize(index) > 0.09f || event.getTouchMajor(index) > 110f
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (!::host.isInitialized) return super.onKeyDown(keyCode, event)
        if (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_Z) {
            if (event.isShiftPressed) host.redo() else host.undo()
            return true
        }
        if (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_Y) {
            host.redo()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private enum class Gesture { NONE, DRAW, ERASE, LASSO, SHAPE, MOVE, TAP, FINGER, SCROLL, PINCH }
}
