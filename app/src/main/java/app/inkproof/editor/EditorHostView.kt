package app.inkproof.editor

import android.content.Context
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.inkproof.core.model.Camera
import app.inkproof.core.model.EraserSize
import app.inkproof.core.model.InkPoint
import app.inkproof.core.model.InkTool
import app.inkproof.core.model.QuestionHit
import app.inkproof.core.model.ShapeItem
import app.inkproof.core.model.ShapeKind
import app.inkproof.core.model.StabilizerLevel
import app.inkproof.core.model.ThemeMode
import app.inkproof.core.model.ToolMode
import app.inkproof.ui.EditorDialogs
import app.inkproof.ui.EditorTopBar
import app.inkproof.ui.InkProofTheme
import app.inkproof.ui.PageDrawer
import app.inkproof.ui.ReviewPanelView
import app.inkproof.ui.ToolRail
import app.inkproof.ui.deskColor

class EditorHostView(context: Context) : FrameLayout(context) {
    lateinit var controller: EditorController
    var onBack: () -> Unit = {}
    var onImport: () -> Unit = {}
    var onExport: () -> Unit = {}
    var theme: ThemeMode = ThemeMode.SYSTEM
    private val canvas = InkCanvasView(context)
    private val top = compose()
    private val rail = compose()
    private val panel = compose()
    private val drawer = compose()
    private val dialogs = compose()
    private var fitted = false

    init {
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply { gravity = android.view.Gravity.TOP })
        addView(rail, LayoutParams(dp(72), LayoutParams.MATCH_PARENT).apply {
            gravity = android.view.Gravity.START
            marginStart = dp(6)
            topMargin = dp(64)
            bottomMargin = dp(8)
        })
        addView(drawer, LayoutParams(dp(280), LayoutParams.MATCH_PARENT).apply {
            gravity = android.view.Gravity.START
            topMargin = dp(56)
        })
        addView(panel, LayoutParams(dp(320), LayoutParams.MATCH_PARENT).apply { gravity = android.view.Gravity.END })
        addView(dialogs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        drawer.visibility = GONE
        panel.visibility = GONE
        dialogs.visibility = GONE
        canvas.host = object : InkCanvasView.Host {
            override fun session() = controller.session
            override fun tool() = controller.tool
            override fun penColor() = controller.settings().penColor
            override fun markerColor() = controller.settings().markerColor
            override fun penWidth() = controller.settings().penWidth
            override fun markerWidth() = controller.settings().markerWidth
            override fun markerAlpha() = controller.settings().markerAlpha
            override fun eraserSize() = controller.settings().eraserSize
            override fun stabilizer() = controller.settings().stabilizer
            override fun shapeKind() = controller.settings().shapeKind
            override fun shapeWidth() = controller.settings().shapeWidth
            override fun holdEnabled() = controller.settings().holdToShapeEnabled
            override fun holdMs() = controller.settings().holdToShapeMs
            override fun holdConfidence() = controller.settings().holdMinConfidence
            override fun selection() = controller.selection
            override fun selectionPage() = controller.selectionPageId
            override fun contentGeneration() = controller.contentGeneration
            override fun deskColor(): Int {
                val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val dark = theme == ThemeMode.DARK || (theme == ThemeMode.SYSTEM && night)
                return deskColor(dark)
            }
            override fun bitmapFor(assetId: String): Bitmap? = controller.bitmapFor(assetId)
            override fun onCommitStroke(pageId: String, points: List<InkPoint>, tool: InkTool, color: Long, width: Float, alpha: Float) {
                controller.commitStroke(pageId, points, tool, color, width, alpha)
            }
            override fun onCommitShape(pageId: String, shape: ShapeItem) = controller.commitShape(pageId, shape)
            override fun onErase(pageId: String, x: Float, y: Float, radius: Float) = controller.erase(pageId, x, y, radius)
            override fun onQuestionHit(pageId: String, questionId: String, hit: QuestionHit) = controller.onQuestionHit(pageId, questionId, hit)
            override fun onLasso(pageId: String, points: List<InkPoint>) = controller.onLasso(pageId, points)
            override fun onMoveSelection(pageId: String, dx: Float, dy: Float) = controller.moveSelection(pageId, dx, dy)
            override fun onTapSelection(pageId: String, x: Float, y: Float) = Unit
            override fun onCamera(camera: Camera) = Unit
            override fun clearSelection() = controller.clearSelection()
            override fun undo() = controller.undo()
            override fun redo() = controller.redo()
        }
    }

    fun bind() {
        controller.onInvalidate = { canvas.invalidate() }
        val themeMode = { theme }
        top.setContent { InkProofTheme(themeMode()) { EditorTopBar(controller, onBack) { applyChrome() } } }
        rail.setContent { InkProofTheme(themeMode()) { ToolRail(controller) } }
        panel.setContent { InkProofTheme(themeMode()) { ReviewPanelView(controller) } }
        drawer.setContent { InkProofTheme(themeMode()) { PageDrawer(controller, onImport, onExport) } }
        dialogs.setContent { InkProofTheme(themeMode()) { EditorDialogs(controller) } }
        canvas.requestFocus()
    }

    fun applyChrome() {
        val state = controller.chrome.value
        panel.visibility = if (state.review != null) VISIBLE else GONE
        drawer.visibility = if (state.drawer) VISIBLE else GONE
        dialogs.visibility = if (state.dialog != null) VISIBLE else GONE
        val railWidth = if (state.flyout) dp(168) else dp(72)
        val railParams = rail.layoutParams as LayoutParams
        if (railParams.width != railWidth) {
            railParams.width = railWidth
            rail.layoutParams = railParams
        }
        val params = canvas.layoutParams as LayoutParams
        val top = dp(56)
        val start = railWidth + dp(8)
        val end = if (state.review != null) dp(320) else 0
        if (params.topMargin != top || params.marginStart != start || params.marginEnd != end) {
            params.topMargin = top
            params.marginStart = start
            params.marginEnd = end
            canvas.layoutParams = params
        }
    }

    fun finishActive() = canvas.finishActive()

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (super.dispatchGenericMotionEvent(event)) return true
        if (event.actionMasked == android.view.MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER ||
            event.actionMasked == android.view.MotionEvent.ACTION_HOVER_EXIT
        ) {
            val copy = android.view.MotionEvent.obtain(event)
            copy.offsetLocation(-canvas.left.toFloat(), -canvas.top.toFloat())
            val handled = canvas.dispatchGenericMotionEvent(copy)
            copy.recycle()
            return handled
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted && w > 0 && ::controller.isInitialized) {
            controller.applyFit(w - dp(8))
            fitted = true
            canvas.invalidate()
        }
    }

    private fun compose(): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
