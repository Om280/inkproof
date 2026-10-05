package com.inkproof.app.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.inkproof.app.model.DetectedShape
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.PenStyle
import com.inkproof.app.model.Question
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokePoint
import com.inkproof.app.model.ShapeType
import com.inkproof.app.model.StrokeRole
import com.inkproof.app.model.TextObject
import com.inkproof.app.model.ToolType
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The InkProof handwriting canvas.
 *
 * Latency architecture:
 *   Stylus MotionEvent -> input layer (this view) -> active stroke points
 *   -> immediate render in onDraw -> stroke committed to the persistent
 *   model only on pen-up (via [Listener.onStrokeCommitted]).
 *
 *  - The active stroke is drawn directly every frame; nothing waits for
 *    state frameworks, databases, JSON, OCR or the network.
 *  - Committed strokes are recorded into a [Picture] display list that is
 *    rebuilt only when the stroke set changes — never per stylus point.
 *  - All stroke geometry lives in page space, so ink is sharp at any zoom.
 *
 * Input policy (OnePlus Stylo 2 first):
 *  - stylus (TOOL_TYPE_STYLUS) writes; TOOL_TYPE_ERASER erases
 *  - the primary stylus button temporarily switches to the eraser
 *  - fingers navigate: one-finger pan, two-finger pan, pinch zoom
 *  - palm: finger touches are ignored for navigation while the stylus is
 *    in contact or hovered recently (real tool-type data, not timing hacks)
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** A finished stroke should be persisted (async, off the UI thread). */
        fun onStrokeCommitted(stroke: Stroke)

        /** Strokes removed by the eraser in one gesture. */
        fun onStrokesErased(strokes: List<Stroke>)

        /** Lasso selection changed; bounds are in screen coordinates. */
        fun onSelectionChanged(strokes: List<Stroke>, screenBounds: RectF?)

        /** A lasso selection was moved: same IDs, new coordinates. */
        fun onStrokesMoved(before: List<Stroke>, after: List<Stroke>)

        /** Camera (zoom) changed — for zoom indicators. */
        fun onCameraChanged(scale: Float) {}

        /**
         * TEXT tool tap: [existing] is the tapped text object (edit) or null
         * (create a new one at the tapped page position).
         */
        fun onTextTap(pageX: Float, pageY: Float, existing: TextObject?) {}
    }

    var listener: Listener? = null

    // ----- page state -----
    var pageId: String = ""
        private set
    var pageWidth: Float = 1600f
        private set
    var pageHeight: Float = 2200f
        private set
    var template: PageTemplate = PageTemplate.RULED
        set(value) { field = value; invalidate() }

    private var pdfBackground: Bitmap? = null
    private var questions: List<Question> = emptyList()
    private var textObjects: List<TextObject> = emptyList()

    // ----- text tool tap -----
    private var textTapPending = false
    private var textTapX = 0f
    private var textTapY = 0f

    // ----- tool state -----
    var penStyle: PenStyle = PenStyle()
    var holdToShapeMs: Long = 400L
    var fingerWritingEnabled: Boolean = false

    /** Shape drawn by the explicit SHAPE tool (picked in the toolbar). */
    var activeShapeKind: ShapeType = ShapeType.RECTANGLE

    private val camera = CanvasCamera()

    // ----- committed strokes (page order) -----
    private val strokes = ArrayList<Stroke>()
    private var committedPicture: Picture? = null
    private var pictureDirty = true

    // ----- active stroke -----
    private var activePointerId = -1
    private var activeToolOverride: ToolType? = null
    private val activePoints = ArrayList<StrokePoint>(512)
    private var activeStartUptime = 0L
    private var drawing = false

    // ----- hold to shape -----
    private var snappedShape: DetectedShape? = null
    private var lastSignificantMove = 0L
    private val holdCheck = Runnable { maybeSnapShape() }

    // ----- eraser -----
    private val erasedThisGesture = ArrayList<Stroke>()
    var eraserRadiusPage: Float = 18f

    // ----- lasso -----
    private val lassoPoints = ArrayList<StrokePoint>(256)
    private var lassoActive = false
    private val selection = ArrayList<Stroke>()
    private var selectionBoundsPage: RectF? = null
    private var draggingSelection = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragDX = 0f
    private var dragDY = 0f
    private var selectionBefore: List<Stroke> = emptyList()

    // ----- selection resize (corner handle) -----
    private var resizingSelection = false
    private var resizeOrigBounds = RectF()
    private val resizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF4B6BD6.toInt()
    }
    private val resizeHandleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2.5f
    }

    // ----- navigation -----
    private var navPointerId1 = -1
    private var navPointerId2 = -1
    private var lastNavX = 0f
    private var lastNavY = 0f
    private var navigating = false
    private var lastStylusContact = 0L

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                listener?.onCameraChanged(camera.scale)
                invalidate()
                return true
            }
        }
    ).apply { isQuickScaleEnabled = false }

    // ----- hover cursor -----
    private var hoverX = -1f
    private var hoverY = -1f
    private var hovering = false

    // ----- paints -----
    private val backgroundPaint = Paint().apply { color = 0xFFECEEF3.toInt() }

    /**
     * Surrounding chrome color (outside the page). Follows the app theme;
     * the PAGE itself keeps its chosen template/background.
     */
    fun setChromeColor(color: Int) {
        backgroundPaint.color = color
        invalidate()
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4B6BD6.toInt()
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x144B6BD6
    }
    private val selectionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4B6BD6.toInt()
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x664B6BD6
        strokeWidth = 1.5f
    }
    private val snapHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x554B6BD6
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val regionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA3B5.toInt()
        textSize = 22f
        isFakeBoldText = true
        letterSpacing = 0.12f
    }
    private val regionLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFD8DDE8.toInt()
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(2f, 8f), 0f)
    }
    private val solutionTint = Paint().apply { color = 0x06003C8F }
    private val questionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF222838.toInt()
        textSize = 34f
    }
    private val textObjPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ================= public API =================

    /** Load a page. ONLY this page's strokes are ever held by the view. */
    fun setPage(
        pageId: String,
        width: Float,
        height: Float,
        template: PageTemplate,
        strokes: List<Stroke>,
        questions: List<Question>,
        pdfBackground: Bitmap?,
        textObjects: List<TextObject> = emptyList()
    ) {
        this.pageId = pageId
        this.pageWidth = width
        this.pageHeight = height
        this.template = template
        this.questions = questions
        this.pdfBackground = pdfBackground
        this.textObjects = textObjects
        this.strokes.clear()
        this.strokes.addAll(strokes)
        clearSelectionInternal()
        activePoints.clear()
        snappedShape = null
        drawing = false
        pictureDirty = true
        if (width > 0 && this.width > 0) {
            camera.fitPage(width, height, this.width.toFloat(), this.height.toFloat())
        }
        invalidate()
    }

    fun setQuestions(questions: List<Question>) {
        this.questions = questions
        invalidate()
    }

    fun setTextObjects(objects: List<TextObject>) {
        this.textObjects = objects
        pictureDirty = true
        invalidate()
    }

    fun allStrokes(): List<Stroke> = strokes.toList()

    /** Apply external mutations (undo/redo, paste) without a full reload. */
    fun applyAdd(added: List<Stroke>) {
        strokes.addAll(added)
        pictureDirty = true
        invalidate()
    }

    fun applyRemove(removed: List<Stroke>) {
        val ids = removed.mapTo(HashSet()) { it.id }
        strokes.removeAll { it.id in ids }
        selection.removeAll { it.id in ids }
        if (selection.isEmpty()) clearSelectionInternal()
        pictureDirty = true
        invalidate()
    }

    fun applyReplace(after: List<Stroke>) {
        val byId = after.associateBy { it.id }
        for (i in strokes.indices) {
            byId[strokes[i].id]?.let { strokes[i] = it }
        }
        pictureDirty = true
        invalidate()
    }

    fun currentSelection(): List<Stroke> = selection.toList()

    fun clearSelection() {
        clearSelectionInternal()
        listener?.onSelectionChanged(emptyList(), null)
        invalidate()
    }

    fun selectionScreenBounds(): RectF? = selectionBoundsPage?.let { pageRectToScreen(it) }

    fun zoomToFit() {
        camera.fitPage(pageWidth, pageHeight, width.toFloat(), height.toFloat())
        listener?.onCameraChanged(camera.scale)
        invalidate()
    }

    fun cameraScale(): Float = camera.scale

    // ================= layout =================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) {
            camera.fitPage(pageWidth, pageHeight, w.toFloat(), h.toFloat())
        }
    }

    // ================= input =================

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true
                hoverX = event.x
                hoverY = event.y
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                ) {
                    lastStylusContact = SystemClock.uptimeMillis()
                }
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hovering = false
                invalidate()
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        val toolType = event.getToolType(index)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER

        if (isStylus) lastStylusContact = SystemClock.uptimeMillis()

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (isStylus || (fingerWritingEnabled && !stylusRecentlyActive())) {
                    // Low-latency: deliver every input sample immediately.
                    requestUnbufferedDispatch(event)
                    beginStylusGesture(event, index, toolType)
                } else {
                    beginFingerGesture(event, index)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (drawing || lassoActive || draggingSelection || resizingSelection) {
                    // Palm or extra finger while the pen is working: ignore it.
                    return true
                }
                if (isStylus) {
                    // Stylus lands while fingers were navigating: stylus wins.
                    cancelNavigation()
                    beginStylusGesture(event, index, toolType)
                } else if (navigating) {
                    navPointerId2 = event.getPointerId(index)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    drawing -> extendActiveStroke(event)
                    lassoActive -> extendLasso(event)
                    resizingSelection -> resizeSelection(event)
                    draggingSelection -> moveSelection(event)
                    navigating -> {
                        scaleDetector.onTouchEvent(event)
                        panWithFingers(event)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(index)
                if (pid == navPointerId2) navPointerId2 = -1
                if (pid == navPointerId1) {
                    navPointerId1 = navPointerId2
                    navPointerId2 = -1
                    if (navPointerId1 != -1) {
                        val i = event.findPointerIndex(navPointerId1)
                        if (i != -1) { lastNavX = event.getX(i); lastNavY = event.getY(i) }
                    }
                }
                if (pid == activePointerId) finishActiveGesture(event, cancelled = false)
            }

            MotionEvent.ACTION_UP -> {
                if (navigating) scaleDetector.onTouchEvent(event)
                finishActiveGesture(event, cancelled = false)
                cancelNavigation()
            }

            MotionEvent.ACTION_CANCEL -> {
                finishActiveGesture(event, cancelled = true)
                cancelNavigation()
            }
        }
        return true
    }

    private fun stylusRecentlyActive(): Boolean =
        SystemClock.uptimeMillis() - lastStylusContact < 400

    // ----- stylus gestures -----

    private fun beginStylusGesture(event: MotionEvent, index: Int, toolType: Int) {
        val px = camera.screenToPageX(event.getX(index))
        val py = camera.screenToPageY(event.getY(index))
        activePointerId = event.getPointerId(index)

        // Hardware eraser end or primary stylus button -> eraser.
        val buttonEraser = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val tool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER || buttonEraser -> ToolType.ERASER
            else -> penStyle.tool
        }
        activeToolOverride = tool

        // Grabbing the corner handle starts a resize; tapping inside an
        // existing selection starts a move.
        val sel = selectionBoundsPage
        if (selection.isNotEmpty() && sel != null && tool != ToolType.ERASER) {
            if (nearResizeHandle(px, py, sel)) {
                resizingSelection = true
                resizeOrigBounds.set(sel)
                selectionBefore = selection.toList()
                return
            }
            if (sel.contains(px, py)) {
                draggingSelection = true
                dragStartX = px
                dragStartY = py
                dragDX = 0f
                dragDY = 0f
                selectionBefore = selection.toList()
                return
            }
        }
        if (selection.isNotEmpty()) {
            clearSelection()
        }

        when (tool) {
            ToolType.ERASER -> {
                drawing = true
                erasedThisGesture.clear()
                eraseAt(px, py)
            }
            ToolType.LASSO -> {
                lassoActive = true
                lassoPoints.clear()
                lassoPoints.add(StrokePoint(px, py, 1f, 0))
            }
            ToolType.PAN -> {
                navigating = true
                navPointerId1 = activePointerId
                lastNavX = event.getX(index)
                lastNavY = event.getY(index)
            }
            ToolType.TEXT -> {
                textTapPending = true
                textTapX = px
                textTapY = py
            }
            else -> {
                drawing = true
                activePoints.clear()
                activeStartUptime = event.eventTime
                lastSignificantMove = SystemClock.uptimeMillis()
                snappedShape = null
                activePoints.add(
                    StrokePoint(px, py, event.getPressure(index).coerceIn(0.05f, 1.5f), 0)
                )
                scheduleHoldCheck()
            }
        }
        invalidate()
    }

    private fun extendActiveStroke(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex == -1) return

        if (activeToolOverride == ToolType.ERASER) {
            for (h in 0 until event.historySize) {
                eraseAt(
                    camera.screenToPageX(event.getHistoricalX(pointerIndex, h)),
                    camera.screenToPageY(event.getHistoricalY(pointerIndex, h))
                )
            }
            eraseAt(
                camera.screenToPageX(event.getX(pointerIndex)),
                camera.screenToPageY(event.getY(pointerIndex))
            )
            invalidate()
            return
        }

        // Append every batched historical sample — no point skipping.
        val t0 = activeStartUptime
        var moved = false
        for (h in 0 until event.historySize) {
            moved = appendPoint(
                camera.screenToPageX(event.getHistoricalX(pointerIndex, h)),
                camera.screenToPageY(event.getHistoricalY(pointerIndex, h)),
                event.getHistoricalPressure(pointerIndex, h),
                event.getHistoricalEventTime(h) - t0
            ) || moved
        }
        moved = appendPoint(
            camera.screenToPageX(event.getX(pointerIndex)),
            camera.screenToPageY(event.getY(pointerIndex)),
            event.getPressure(pointerIndex),
            event.eventTime - t0
        ) || moved

        if (moved) {
            lastSignificantMove = SystemClock.uptimeMillis()
            if (snappedShape != null) {
                // User kept drawing after a snap preview: revert to raw ink.
                snappedShape = null
            }
            scheduleHoldCheck()
        }
        invalidate()
    }

    /** Returns true when the sample represents significant movement. */
    private fun appendPoint(px: Float, py: Float, pressure: Float, t: Long): Boolean {
        val last = activePoints.lastOrNull()
        activePoints.add(StrokePoint(px, py, pressure.coerceIn(0.05f, 1.5f), t))
        if (last == null) return true
        return hypot(px - last.x, py - last.y) > 2.5f / camera.scale
    }

    private fun finishActiveGesture(event: MotionEvent, cancelled: Boolean) {
        removeCallbacks(holdCheck)
        when {
            textTapPending -> {
                textTapPending = false
                if (!cancelled) {
                    listener?.onTextTap(textTapX, textTapY, hitTextObject(textTapX, textTapY))
                }
            }

            resizingSelection -> {
                resizingSelection = false
                if (!cancelled && selectionBefore.isNotEmpty()) {
                    val after = selection.toList()
                    listener?.onStrokesMoved(selectionBefore, after)
                    listener?.onSelectionChanged(after, selectionScreenBounds())
                }
            }

            draggingSelection -> {
                draggingSelection = false
                if (!cancelled && (abs(dragDX) > 0.5f || abs(dragDY) > 0.5f)) {
                    val after = selection.toList()
                    listener?.onStrokesMoved(selectionBefore, after)
                    listener?.onSelectionChanged(after, selectionScreenBounds())
                }
            }

            drawing && activeToolOverride == ToolType.ERASER -> {
                drawing = false
                if (erasedThisGesture.isNotEmpty()) {
                    listener?.onStrokesErased(erasedThisGesture.toList())
                    erasedThisGesture.clear()
                }
            }

            drawing -> {
                drawing = false
                if (!cancelled) commitActiveStroke()
                activePoints.clear()
                snappedShape = null
            }

            lassoActive -> {
                lassoActive = false
                if (!cancelled) completeLasso()
                lassoPoints.clear()
            }
        }
        activePointerId = -1
        activeToolOverride = null
        invalidate()
    }

    private fun commitActiveStroke() {
        if (activePoints.isEmpty() || pageId.isEmpty()) return
        // Explicit SHAPE tool: the drag start/end define the chosen shape.
        if (activeToolOverride == ToolType.SHAPE && activePoints.size >= 2) {
            val first = activePoints.first()
            val last = activePoints.last()
            if (hypot(last.x - first.x, last.y - first.y) >= 8f) {
                val shapeStroke = Stroke(
                    pageId = pageId,
                    tool = ToolType.PEN,
                    color = penStyle.color,
                    baseWidth = penStyle.baseWidth,
                    points = ShapeFactory.create(activeShapeKind, first.x, first.y, last.x, last.y),
                    shapeType = activeShapeKind
                )
                val classified = classifyByRegion(shapeStroke)
                strokes.add(classified)
                pictureDirty = true
                listener?.onStrokeCommitted(classified)
            }
            return
        }
        val snap = snappedShape
        val stroke = if (snap != null) {
            Stroke(
                pageId = pageId,
                tool = ToolType.PEN,
                color = penStyle.color,
                baseWidth = penStyle.baseWidth,
                points = snap.points,
                shapeType = snap.type
            )
        } else {
            Stroke(
                pageId = pageId,
                tool = penStyle.tool,
                color = penStyle.color,
                baseWidth = penStyle.baseWidth,
                points = activePoints.toList()
            )
        }
        val classified = classifyByRegion(stroke)
        strokes.add(classified)
        pictureDirty = true
        listener?.onStrokeCommitted(classified)
    }

    /** Tag strokes with the question whose region contains them. */
    private fun classifyByRegion(stroke: Stroke): Stroke {
        if (questions.isEmpty()) return stroke
        val b = stroke.bounds()
        for (q in questions) {
            if (b.centerY >= q.solutionTop && b.centerY <= q.solutionBottom) {
                return stroke.copy(questionId = q.id, role = StrokeRole.SOLUTION)
            }
            if (b.centerY >= q.questionTop && b.centerY < q.questionBottom) {
                return stroke.copy(questionId = q.id, role = StrokeRole.QUESTION)
            }
        }
        return stroke
    }

    // ----- hold-to-shape -----

    private fun scheduleHoldCheck() {
        removeCallbacks(holdCheck)
        postDelayed(holdCheck, holdToShapeMs)
    }

    private fun maybeSnapShape() {
        if (!drawing || activeToolOverride == ToolType.ERASER) return
        // Hold-to-shape applies to freehand PEN ink only; the SHAPE tool
        // draws the chosen shape explicitly.
        if (penStyle.tool != ToolType.PEN || activeToolOverride == ToolType.SHAPE) return
        val still = SystemClock.uptimeMillis() - lastSignificantMove >= holdToShapeMs - 30
        if (!still || activePoints.size < 8) return
        val detected = ShapeDetector.detect(activePoints)
        if (detected != null) {
            snappedShape = detected
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }

    // ----- eraser -----

    private fun eraseAt(px: Float, py: Float) {
        val r = eraserRadiusPage
        var removedAny = false
        val iterator = strokes.listIterator(strokes.size)
        // Iterate from top-most stroke down.
        while (iterator.hasPrevious()) {
            val s = iterator.previous()
            val b = s.bounds()
            val reach = r + s.baseWidth / 2f
            if (px < b.left - reach || px > b.right + reach ||
                py < b.top - reach || py > b.bottom + reach
            ) continue
            // Segment-accurate: snapped shapes (2-point lines, sparse corner
            // polygons) are erasable anywhere along their edges.
            if (StrokeHitTester.hits(s, px, py, r)) {
                iterator.remove()
                erasedThisGesture.add(s)
                removedAny = true
            }
        }
        if (removedAny) {
            pictureDirty = true
            invalidate()
        }
    }

    // ----- lasso -----

    private fun extendLasso(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex == -1) return
        val px = camera.screenToPageX(event.getX(pointerIndex))
        val py = camera.screenToPageY(event.getY(pointerIndex))
        val last = lassoPoints.lastOrNull()
        if (last == null || hypot(px - last.x, py - last.y) > 3f / camera.scale) {
            lassoPoints.add(StrokePoint(px, py, 1f, 0))
            invalidate()
        }
    }

    private fun completeLasso() {
        if (lassoPoints.size < 3) return
        val polyX = FloatArray(lassoPoints.size) { lassoPoints[it].x }
        val polyY = FloatArray(lassoPoints.size) { lassoPoints[it].y }
        selection.clear()
        for (s in strokes) {
            var inside = 0
            val step = (s.points.size / 16).coerceAtLeast(1)
            var sampled = 0
            var i = 0
            while (i < s.points.size) {
                val p = s.points[i]
                if (pointInPolygon(p.x, p.y, polyX, polyY)) inside++
                sampled++
                i += step
            }
            if (sampled > 0 && inside >= (sampled + 1) / 2) selection.add(s)
        }
        if (selection.isEmpty()) {
            clearSelectionInternal()
            listener?.onSelectionChanged(emptyList(), null)
        } else {
            var bounds: RectF? = null
            for (s in selection) {
                val b = s.bounds()
                val r = RectF(b.left, b.top, b.right, b.bottom)
                if (bounds == null) bounds = r else bounds.union(r)
            }
            bounds?.inset(-16f, -16f)
            selectionBoundsPage = bounds
            listener?.onSelectionChanged(selection.toList(), selectionScreenBounds())
        }
    }

    private fun moveSelection(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex == -1) return
        val px = camera.screenToPageX(event.getX(pointerIndex))
        val py = camera.screenToPageY(event.getY(pointerIndex))
        val dx = px - dragStartX - dragDX
        val dy = py - dragStartY - dragDY
        dragDX += dx
        dragDY += dy
        if (dx == 0f && dy == 0f) return

        val ids = selection.mapTo(HashSet()) { it.id }
        for (i in strokes.indices) {
            if (strokes[i].id in ids) {
                strokes[i] = strokes[i].translated(dx, dy)
            }
        }
        for (i in selection.indices) {
            selection[i] = selection[i].translated(dx, dy)
        }
        selectionBoundsPage?.offset(dx, dy)
        pictureDirty = true
        invalidate()
    }

    // ----- selection resize -----

    private fun nearResizeHandle(px: Float, py: Float, sel: RectF): Boolean {
        val grab = 26f / camera.scale
        return hypot(px - sel.right, py - sel.bottom) <= grab
    }

    private fun resizeSelection(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex == -1 && event.pointerCount > 0) return
        val i = if (pointerIndex == -1) 0 else pointerIndex
        val px = camera.screenToPageX(event.getX(i))
        val py = camera.screenToPageY(event.getY(i))
        val orig = resizeOrigBounds
        if (orig.width() < 1f || orig.height() < 1f) return
        val sx = ((px - orig.left) / orig.width()).coerceIn(0.05f, 20f)
        val sy = ((py - orig.top) / orig.height()).coerceIn(0.05f, 20f)

        // Always scale from the ORIGINAL strokes to avoid cumulative drift.
        val scaled = selectionBefore.map { it.scaled(sx, sy, orig.left, orig.top) }
        val byId = scaled.associateBy { it.id }
        for (j in strokes.indices) {
            byId[strokes[j].id]?.let { strokes[j] = it }
        }
        selection.clear()
        selection.addAll(scaled)
        selectionBoundsPage = RectF(
            orig.left, orig.top,
            orig.left + orig.width() * sx,
            orig.top + orig.height() * sy
        )
        pictureDirty = true
        invalidate()
    }

    /** Delete the current selection (invoked from the contextual menu). */
    fun deleteSelection() {
        if (selection.isEmpty()) return
        val removed = selection.toList()
        applyRemove(removed)
        listener?.onStrokesErased(removed)
        clearSelection()
    }

    fun recolorSelection(color: Int) {
        if (selection.isEmpty()) return
        val before = selection.toList()
        val after = before.map { it.copy(color = color) }
        for (i in selection.indices) selection[i] = after[i]
        applyReplace(after)
        listener?.onStrokesMoved(before, after)
    }

    fun setSelectionWidth(width: Float) {
        if (selection.isEmpty()) return
        val before = selection.toList()
        val after = before.map { it.copy(baseWidth = width) }
        for (i in selection.indices) selection[i] = after[i]
        applyReplace(after)
        listener?.onStrokesMoved(before, after)
    }

    private fun clearSelectionInternal() {
        selection.clear()
        selectionBoundsPage = null
        draggingSelection = false
    }

    // ----- finger navigation -----

    private fun beginFingerGesture(event: MotionEvent, index: Int) {
        if (stylusRecentlyActive() && !drawing) {
            // Likely a resting palm: ignore navigation to avoid page jumps.
            return
        }
        if (drawing || lassoActive) return

        val px = camera.screenToPageX(event.getX(index))
        val py = camera.screenToPageY(event.getY(index))
        val sel = selectionBoundsPage
        if (selection.isNotEmpty() && sel != null && sel.contains(px, py)) {
            activePointerId = event.getPointerId(index)
            draggingSelection = true
            dragStartX = px
            dragStartY = py
            dragDX = 0f
            dragDY = 0f
            selectionBefore = selection.toList()
            return
        }

        navigating = true
        navPointerId1 = event.getPointerId(index)
        lastNavX = event.getX(index)
        lastNavY = event.getY(index)
        scaleDetector.onTouchEvent(event)
    }

    private fun panWithFingers(event: MotionEvent) {
        val i = event.findPointerIndex(navPointerId1)
        if (i == -1) return
        if (scaleDetector.isInProgress) {
            // Pinch handles both zoom and its own focal panning.
            val fx = scaleDetector.focusX
            val fy = scaleDetector.focusY
            camera.panBy(fx - lastNavX, fy - lastNavY)
            lastNavX = fx
            lastNavY = fy
        } else {
            val x = event.getX(i)
            val y = event.getY(i)
            camera.panBy(x - lastNavX, y - lastNavY)
            lastNavX = x
            lastNavY = y
        }
        invalidate()
    }

    private fun cancelNavigation() {
        navigating = false
        navPointerId1 = -1
        navPointerId2 = -1
    }

    // ================= rendering =================

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        canvas.save()
        canvas.concat(camera.matrix())

        // 1. Page background + template
        TemplateRenderer.draw(canvas, template, pageWidth, pageHeight)
        pdfBackground?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, pageWidth, pageHeight), null)
        }

        // 2. Question region guides (subtle, page-space)
        drawQuestionRegions(canvas)

        // 3. Committed strokes via display list (rebuilt only on change)
        if (pictureDirty || committedPicture == null) {
            rebuildPicture()
        }
        committedPicture?.let { canvas.drawPicture(it) }

        // 4. Active stroke — immediate, nothing between stylus and pixels
        if (drawing && activeToolOverride != ToolType.ERASER && activePoints.isNotEmpty()) {
            val snap = snappedShape
            if (activeToolOverride == ToolType.SHAPE && activePoints.size >= 2) {
                // Live preview of the explicit shape being dragged out.
                val first = activePoints.first()
                val last = activePoints.last()
                StrokeRenderer.draw(
                    canvas,
                    Stroke(
                        pageId = pageId,
                        tool = ToolType.PEN,
                        color = penStyle.color,
                        baseWidth = penStyle.baseWidth,
                        points = ShapeFactory.create(
                            activeShapeKind, first.x, first.y, last.x, last.y
                        ),
                        shapeType = activeShapeKind
                    )
                )
            } else if (snap != null) {
                val preview = Stroke(
                    pageId = pageId,
                    tool = ToolType.PEN,
                    color = penStyle.color,
                    baseWidth = penStyle.baseWidth,
                    points = snap.points,
                    shapeType = snap.type
                )
                StrokeRenderer.draw(canvas, preview)
                // Subtle hold feedback: dashed hint around the snapped shape.
                val b = preview.bounds()
                canvas.drawRoundRect(
                    RectF(b.left - 12f, b.top - 12f, b.right + 12f, b.bottom + 12f),
                    10f, 10f, snapHintPaint
                )
            } else {
                StrokeRenderer.draw(
                    canvas,
                    Stroke(
                        pageId = pageId,
                        tool = penStyle.tool,
                        color = penStyle.color,
                        baseWidth = penStyle.baseWidth,
                        points = activePoints
                    )
                )
            }
        }

        // 5. Lasso in progress
        if (lassoActive && lassoPoints.size > 1) {
            canvas.drawPath(StrokeRenderer.centerlinePath(lassoPoints), lassoPaint)
        }

        // 6. Selection highlight + corner resize handle
        selectionBoundsPage?.let { b ->
            canvas.drawRoundRect(b, 12f, 12f, selectionFill)
            canvas.drawRoundRect(b, 12f, 12f, selectionStroke)
            val handleR = 9f / camera.scale
            canvas.drawCircle(b.right, b.bottom, handleR, resizeHandlePaint)
            canvas.drawCircle(b.right, b.bottom, handleR, resizeHandleRing)
        }

        canvas.restore()

        // 7. Hover cursor (screen space)
        if (hovering && !drawing) {
            val radius = when (penStyle.tool) {
                ToolType.ERASER -> eraserRadiusPage * camera.scale
                else -> (penStyle.baseWidth * camera.scale / 2f).coerceAtLeast(3f)
            }
            canvas.drawCircle(hoverX, hoverY, radius, hoverPaint)
        }
    }

    private fun rebuildPicture() {
        val picture = Picture()
        val c = picture.beginRecording(pageWidth.toInt() + 1, pageHeight.toInt() + 1)
        for (t in textObjects) {
            textObjPaint.textSize = t.fontSize
            textObjPaint.color = t.color
            drawWrappedText(c, t.text, t.x, t.y, t.widthPts, textObjPaint)
        }
        for (s in strokes) {
            StrokeRenderer.draw(c, s)
        }
        picture.endRecording()
        committedPicture = picture
        pictureDirty = false
    }

    // ----- text objects -----

    private fun hitTextObject(px: Float, py: Float): TextObject? =
        textObjects.lastOrNull { t ->
            val height = estimateTextHeight(t)
            px >= t.x - 8f && px <= t.x + t.widthPts + 8f &&
                py >= t.y - t.fontSize - 8f && py <= t.y - t.fontSize + height + 8f
        }

    private fun estimateTextHeight(t: TextObject): Float {
        textObjPaint.textSize = t.fontSize
        var lines = 0
        for (raw in t.text.split('\n')) {
            val w = textObjPaint.measureText(raw.ifEmpty { " " })
            lines += ceil((w / t.widthPts).toDouble()).toInt().coerceAtLeast(1)
        }
        return lines * t.fontSize * 1.3f + t.fontSize * 0.4f
    }

    private fun drawQuestionRegions(canvas: Canvas) {
        if (questions.isEmpty()) return
        for ((index, q) in questions.withIndex()) {
            // Divider above the question block.
            canvas.drawLine(24f, q.questionTop, pageWidth - 24f, q.questionTop, regionLinePaint)
            canvas.drawText("QUESTION ${index + 1}", 32f, q.questionTop + 30f, regionLabelPaint)
            // Typed/pasted question statements are rendered on the page.
            val text = q.typedText
            if (!text.isNullOrBlank()) {
                drawWrappedText(canvas, text, 36f, q.questionTop + 78f, pageWidth - 72f, questionTextPaint)
            }
            // Solution band tint + label.
            canvas.drawRect(0f, q.solutionTop, pageWidth, q.solutionBottom, solutionTint)
            canvas.drawLine(24f, q.solutionTop, pageWidth - 24f, q.solutionTop, regionLinePaint)
            canvas.drawText("YOUR SOLUTION", 32f, q.solutionTop + 30f, regionLabelPaint)
            canvas.drawLine(
                24f, q.solutionBottom, pageWidth - 24f, q.solutionBottom, regionLinePaint
            )
        }
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint
    ) {
        var cursorY = y
        for (rawLine in text.split('\n')) {
            var line = rawLine
            while (line.isNotEmpty()) {
                val count = paint.breakText(line, true, maxWidth, null)
                if (count <= 0) break
                // Prefer breaking at a space.
                var cut = count
                if (count < line.length) {
                    val lastSpace = line.substring(0, count).lastIndexOf(' ')
                    if (lastSpace > count / 2) cut = lastSpace + 1
                }
                canvas.drawText(line.substring(0, cut).trimEnd(), x, cursorY, paint)
                line = line.substring(cut)
                cursorY += paint.textSize * 1.3f
            }
            if (rawLine.isEmpty()) cursorY += paint.textSize * 1.3f
        }
    }

    // ----- helpers -----

    private fun pageRectToScreen(r: RectF): RectF = RectF(
        camera.pageToScreenX(r.left),
        camera.pageToScreenY(r.top),
        camera.pageToScreenX(r.right),
        camera.pageToScreenY(r.bottom)
    )

    companion object {
        fun pointInPolygon(x: Float, y: Float, polyX: FloatArray, polyY: FloatArray): Boolean {
            var inside = false
            var j = polyX.size - 1
            for (i in polyX.indices) {
                if ((polyY[i] > y) != (polyY[j] > y) &&
                    x < (polyX[j] - polyX[i]) * (y - polyY[i]) / (polyY[j] - polyY[i]) + polyX[i]
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }
    }
}
