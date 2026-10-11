package app.inkproof.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val ANALYSIS_SCHEMA_VERSION = 1
const val DOCUMENT_FORMAT_VERSION = 1

fun interface Ids {
    fun newId(): String
}

object UuidIds : Ids {
    override fun newId(): String = UUID.randomUUID().toString()
}

class SeqIds(private var n: Int = 0) : Ids {
    override fun newId(): String = "id-${++n}"
}

fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class SeqClock(var time: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = time++
}

val InkJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    classDiscriminator = "type"
    explicitNulls = false
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class StabilizerLevel { OFF, LOW, MEDIUM, HIGH }

@Serializable
enum class EraserSize(val radius: Float) {
    SMALL(8f),
    MEDIUM(18f),
    LARGE(36f),
}

@Serializable
enum class AiMode { REAL, MOCK, OFF }

@Serializable
enum class InkTool { PEN, MARKER }

@Serializable
enum class InkRegion { FREE, PROMPT, SOLUTION }

@Serializable
enum class ShapeKind { LINE, ARROW, RECTANGLE, SQUARE, CIRCLE, ELLIPSE, TRIANGLE }

@Serializable
enum class ToolMode { PEN, MARKER, ERASER, LASSO, SHAPE, TEXT }

@Serializable
enum class PageTemplate { BLANK, RULED, GRID, DOT, ENGINEERING, MATH }

@Serializable
enum class PaperColor(val argb: Long) {
    WHITE(0xFFFFFFFFL),
    WARM_WHITE(0xFFFBF6EEL),
    GREY(0xFFE6E6E6L),
    BLACK(0xFF000000L),
    NEAR_BLACK(0xFF141414L),
    DARK_GREY(0xFF2C2C2EL),
    SLATE(0xFF1E2A36L),
    ;

    fun isDark(): Boolean = relativeLuminance(argb) < 0.45
}

object Palette {
    const val BLACK = 0xFF1A1A1AL
    const val WHITE = 0xFFFFFFFFL
    const val GREY = 0xFFB7BDC4L
    const val DARK_GREY = 0xFF4A4F55L
    const val RED = 0xFFD64545L
    const val ORANGE = 0xFFE07A2FL
    const val YELLOW = 0xFFFFD15CL
    const val GREEN = 0xFF4CCB7AL
    const val CYAN = 0xFF4FD2D2L
    const val LIGHT_BLUE = 0xFF8EC5FFL
    const val BLUE = 0xFF3A6FE0L
    const val PURPLE = 0xFF7A5AF0L
    const val PINK = 0xFFFF8DC7L

    val all: List<Pair<String, Long>> = listOf(
        "Black" to BLACK,
        "White" to WHITE,
        "Grey" to GREY,
        "Dark grey" to DARK_GREY,
        "Red" to RED,
        "Orange" to ORANGE,
        "Yellow" to YELLOW,
        "Green" to GREEN,
        "Cyan" to CYAN,
        "Light blue" to LIGHT_BLUE,
        "Blue" to BLUE,
        "Purple" to PURPLE,
        "Pink" to PINK,
    )

    /** Colors the spec requires to stay readable on dark paper. */
    val darkPaperLegibility: List<Long> = listOf(
        WHITE, GREY, YELLOW, CYAN, LIGHT_BLUE, PINK, GREEN,
    )
}

fun templateLineColor(paper: PaperColor): Long =
    if (paper.isDark()) 0x38FFFFFFL else 0x24000000L

fun relativeLuminance(argb: Long): Double {
    fun channel(c: Double): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    val r = channel(((argb shr 16) and 0xFF).toDouble())
    val g = channel(((argb shr 8) and 0xFF).toDouble())
    val b = channel((argb and 0xFF).toDouble())
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

fun contrastRatio(ink: Long, paper: Long): Double {
    val a = relativeLuminance(ink)
    val b = relativeLuminance(paper)
    val lighter = max(a, b)
    val darker = min(a, b)
    return (lighter + 0.05) / (darker + 0.05)
}

fun isLegible(ink: Long, paper: Long, minimum: Double = 3.0): Boolean =
    contrastRatio(ink, paper) >= minimum

fun effectiveTool(selected: ToolMode, stylusEraserEnd: Boolean, stylusButton: Boolean): ToolMode {
    if (stylusEraserEnd || stylusButton) return ToolMode.ERASER
    return selected
}

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val stabilizer: StabilizerLevel = StabilizerLevel.MEDIUM,
    val holdToShapeMs: Long = 450L,
    val holdToShapeEnabled: Boolean = true,
    val holdMinConfidence: Float = 0.82f,
    val backendUrl: String = "http://10.0.2.2:8787",
    val aiMode: AiMode = AiMode.REAL,
    val penWidth: Float = 2.6f,
    val markerWidth: Float = 22f,
    val markerAlpha: Float = 0.38f,
    val eraserSize: EraserSize = EraserSize.MEDIUM,
    val penColor: Long = Palette.BLACK,
    val markerColor: Long = Palette.YELLOW,
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    val shapeWidth: Float = 2.4f,
    val customColors: List<Long> = emptyList(),
    val showHoverCursor: Boolean = true,
)

@Serializable
data class DocRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun inflate(amount: Float): DocRect =
        DocRect(left - amount, top - amount, right + amount, bottom + amount)

    fun intersects(other: DocRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

fun rectOf(x: Float, y: Float, w: Float, h: Float): DocRect {
    val l = min(x, x + w)
    val r = max(x, x + w)
    val t = min(y, y + h)
    val b = max(y, y + h)
    return DocRect(l, t, r, b)
}

data class PageLayout(
    val pageWidth: Float = 1200f,
    val pageHeight: Float = 1600f,
    val gap: Float = 56f,
) {
    val span: Float get() = pageHeight + gap

    fun pageTop(index: Int): Float = index * span

    fun pageIndexAt(docY: Float, pageCount: Int): Int? {
        if (pageCount <= 0 || docY.isNaN()) return null
        val index = kotlin.math.floor(docY / span).toInt()
        if (index !in 0 until pageCount) return null
        val local = docY - pageTop(index)
        if (local < 0f || local > pageHeight) return null
        return index
    }

    fun localY(docY: Float, index: Int): Float = docY - pageTop(index)

    fun toDocY(index: Int, localY: Float): Float = pageTop(index) + localY
}

@Serializable
data class Camera(
    val scrollX: Float = 0f,
    val scrollY: Float = 0f,
    val scale: Float = 1f,
) {
    fun docToScreen(docX: Float, docY: Float, viewW: Int, layout: PageLayout): Pair<Float, Float> {
        val originX = pageOriginX(viewW, layout)
        val sx = originX + (docX - scrollX) * scale
        val sy = (docY - scrollY) * scale
        return sx to sy
    }

    fun screenToDoc(sx: Float, sy: Float, viewW: Int, layout: PageLayout): Pair<Float, Float> {
        val originX = pageOriginX(viewW, layout)
        val docX = (sx - originX) / scale + scrollX
        val docY = sy / scale + scrollY
        return docX to docY
    }

    fun pageOriginX(viewW: Int, layout: PageLayout): Float {
        val pagePx = layout.pageWidth * scale
        return if (pagePx < viewW) (viewW - pagePx) / 2f else 0f
    }

    fun zoomAt(sx: Float, sy: Float, newScale: Float, viewW: Int, layout: PageLayout): Camera {
        val clamped = newScale.coerceIn(0.45f, 3.5f)
        val (docX, docY) = screenToDoc(sx, sy, viewW, layout)
        val next = copy(scale = clamped)
        val originX = next.pageOriginX(viewW, layout)
        val scrollX2 = docX - (sx - originX) / clamped
        val scrollY2 = docY - sy / clamped
        return next.copy(scrollX = scrollX2, scrollY = scrollY2)
    }

    fun panByScreen(dxPx: Float, dyPx: Float): Camera =
        copy(scrollX = scrollX - dxPx / scale, scrollY = scrollY - dyPx / scale)
}

fun clampCamera(camera: Camera, pageCount: Int, viewW: Int, viewH: Int, layout: PageLayout): Camera {
    if (pageCount <= 0) return camera
    val maxY = layout.pageTop(pageCount - 1) + layout.pageHeight - viewH / camera.scale
    val y = camera.scrollY.coerceIn(-48f, max(0f, maxY) + 48f)
    val maxX = max(0f, layout.pageWidth - viewW / camera.scale)
    val x = camera.scrollX.coerceIn(-24f, maxX + 24f)
    return camera.copy(scrollX = x, scrollY = y)
}
