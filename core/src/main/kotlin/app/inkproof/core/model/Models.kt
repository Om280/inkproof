package app.inkproof.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val t: Long = 0L,
)

@Serializable
sealed class PageObject {
    abstract val id: String
}

@Serializable
@SerialName("stroke")
data class Stroke(
    override val id: String,
    val points: List<InkPoint>,
    val color: Long,
    val width: Float,
    val tool: InkTool,
    val alpha: Float = 1f,
    val questionId: String? = null,
    val region: InkRegion = InkRegion.FREE,
) : PageObject()

@Serializable
@SerialName("shape")
data class ShapeItem(
    override val id: String,
    val kind: ShapeKind,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: Long,
    val strokeWidth: Float,
    val questionId: String? = null,
) : PageObject()

@Serializable
@SerialName("text")
data class TextItem(
    override val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float = 480f,
    val color: Long = Palette.BLACK,
    val size: Float = 22f,
    val questionId: String? = null,
) : PageObject()

@Serializable
@SerialName("image")
data class ImageItem(
    override val id: String,
    val assetId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val questionId: String? = null,
) : PageObject()

@Serializable
data class Question(
    val id: String,
    val y: Float,
    val height: Float = 640f,
    val promptText: String = "",
    val promptAssetId: String? = null,
    val recognizedPrompt: String? = null,
    val recognizedSolution: String? = null,
    val promptConfidence: Float = 1f,
    val solutionConfidence: Float = 0f,
    val promptVersion: Int = 1,
    val solutionVersion: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class Page(
    val id: String,
    val notebookId: String,
    val paper: PaperColor = PaperColor.WARM_WHITE,
    val template: PageTemplate = PageTemplate.BLANK,
    val objects: List<PageObject> = emptyList(),
    val questions: List<Question> = emptyList(),
    val backgroundAssetId: String? = null,
    val backgroundKind: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val strokes: List<Stroke> get() = objects.filterIsInstance<Stroke>()
    val shapes: List<ShapeItem> get() = objects.filterIsInstance<ShapeItem>()
}

@Serializable
data class NotebookMeta(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pageOrder: List<String> = emptyList(),
    val defaultPaper: PaperColor = PaperColor.WARM_WHITE,
    val defaultTemplate: PageTemplate = PageTemplate.BLANK,
    val searchBlob: String = "",
    val coverPageId: String? = null,
)

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class LibraryIndex(
    val formatVersion: Int = DOCUMENT_FORMAT_VERSION,
    val folders: List<Folder> = emptyList(),
    val notebooks: List<NotebookMeta> = emptyList(),
)

@Serializable
data class NotebookUiState(
    val cameraX: Float = 0f,
    val cameraY: Float = 0f,
    val scale: Float = 1f,
    val activePageId: String? = null,
)

enum class SortOrder { NAME_ASC, NAME_DESC, UPDATED, CREATED }

fun sortNotebooks(list: List<NotebookMeta>, order: SortOrder): List<NotebookMeta> = when (order) {
    SortOrder.NAME_ASC -> list.sortedBy { it.name.lowercase() }
    SortOrder.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
    SortOrder.UPDATED -> list.sortedByDescending { it.updatedAt }
    SortOrder.CREATED -> list.sortedByDescending { it.createdAt }
}

fun filterNotebooks(list: List<NotebookMeta>, query: String): List<NotebookMeta> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return list
    return list.filter {
        it.name.lowercase().contains(q) || it.searchBlob.lowercase().contains(q)
    }
}

fun objectBounds(obj: PageObject): DocRect = when (obj) {
    is Stroke -> strokeBounds(obj)
    is ShapeItem -> rectOf(obj.x, obj.y, obj.w, obj.h).inflate(obj.strokeWidth)
    is TextItem -> DocRect(obj.x, obj.y, obj.x + obj.width, obj.y + obj.size * 1.4f)
    is ImageItem -> DocRect(obj.x, obj.y, obj.x + obj.width, obj.y + obj.height)
}

fun strokeBounds(stroke: Stroke): DocRect {
    if (stroke.points.isEmpty()) return DocRect(0f, 0f, 0f, 0f)
    var l = Float.POSITIVE_INFINITY
    var t = Float.POSITIVE_INFINITY
    var r = Float.NEGATIVE_INFINITY
    var b = Float.NEGATIVE_INFINITY
    for (p in stroke.points) {
        l = min(l, p.x)
        t = min(t, p.y)
        r = max(r, p.x)
        b = max(b, p.y)
    }
    val pad = stroke.width
    return DocRect(l - pad, t - pad, r + pad, b + pad)
}

fun PageObject.offsetBy(dx: Float, dy: Float): PageObject = when (this) {
    is Stroke -> copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
    is ShapeItem -> copy(x = x + dx, y = y + dy)
    is TextItem -> copy(x = x + dx, y = y + dy)
    is ImageItem -> copy(x = x + dx, y = y + dy)
}

fun PageObject.withId(newId: String): PageObject = when (this) {
    is Stroke -> copy(id = newId)
    is ShapeItem -> copy(id = newId)
    is TextItem -> copy(id = newId)
    is ImageItem -> copy(id = newId)
}

fun PageObject.recolored(color: Long): PageObject = when (this) {
    is Stroke -> copy(color = color)
    is ShapeItem -> copy(color = color)
    is TextItem -> copy(color = color)
    is ImageItem -> this
}

fun PageObject.withWidth(width: Float): PageObject = when (this) {
    is Stroke -> copy(width = width)
    is ShapeItem -> copy(strokeWidth = width)
    else -> this
}

fun PageObject.duplicate(newId: String, dx: Float, dy: Float): PageObject =
    offsetBy(dx, dy).withId(newId)

object QLayout {
    const val HEADER = 36f
    const val PROMPT_LABEL = 22f
    const val SOLUTION_LABEL = 22f
    const val DIVIDER = 10f
    const val FOOTER = 52f
    const val PAD = 18f

    fun innerHeight(q: Question): Float =
        (q.height - HEADER - PROMPT_LABEL - SOLUTION_LABEL - DIVIDER - FOOTER).coerceAtLeast(160f)

    fun promptHeight(q: Question): Float = (innerHeight(q) * 0.34f).coerceAtLeast(72f)

    fun promptTop(q: Question): Float = q.y + HEADER + PROMPT_LABEL

    fun promptRect(q: Question, pageWidth: Float): DocRect {
        val top = promptTop(q)
        return DocRect(PAD, top, pageWidth - PAD, top + promptHeight(q))
    }

    fun solutionTop(q: Question): Float = promptTop(q) + promptHeight(q) + DIVIDER + SOLUTION_LABEL

    fun solutionRect(q: Question, pageWidth: Float): DocRect {
        val top = solutionTop(q)
        val bottom = q.y + q.height - FOOTER
        return DocRect(PAD, top, pageWidth - PAD, bottom.coerceAtLeast(top + 48f))
    }

    fun footerRect(q: Question, pageWidth: Float): DocRect =
        DocRect(PAD, q.y + q.height - FOOTER + 6f, pageWidth - PAD, q.y + q.height - 8f)

    fun buttonRect(q: Question, pageWidth: Float, index: Int): DocRect {
        val footer = footerRect(q, pageWidth)
        val gap = 8f
        val w = (footer.width - gap * 2f) / 3f
        val left = footer.left + index * (w + gap)
        return DocRect(left, footer.top, left + w, footer.bottom)
    }

    fun headerRect(q: Question, pageWidth: Float): DocRect =
        DocRect(0f, q.y, pageWidth, q.y + HEADER)

    fun resizeRect(q: Question, pageWidth: Float): DocRect =
        DocRect(pageWidth / 2f - 40f, q.y + q.height - 10f, pageWidth / 2f + 40f, q.y + q.height + 8f)
}

enum class QuestionHit { HEADER, PROMPT, SOLUTION, CHECK, SOLVE, RECOGNIZE, RESIZE, BODY, OUTSIDE }

fun hitQuestion(q: Question, x: Float, y: Float, pageWidth: Float): QuestionHit {
    if (y < q.y - 4f || y > q.y + q.height + 10f || x < -4f || x > pageWidth + 4f) {
        return QuestionHit.OUTSIDE
    }
    if (QLayout.resizeRect(q, pageWidth).inflate(6f).contains(x, y)) return QuestionHit.RESIZE
    if (QLayout.buttonRect(q, pageWidth, 0).contains(x, y)) return QuestionHit.CHECK
    if (QLayout.buttonRect(q, pageWidth, 1).contains(x, y)) return QuestionHit.SOLVE
    if (QLayout.buttonRect(q, pageWidth, 2).contains(x, y)) return QuestionHit.RECOGNIZE
    if (QLayout.headerRect(q, pageWidth).contains(x, y)) return QuestionHit.HEADER
    if (QLayout.promptRect(q, pageWidth).contains(x, y)) return QuestionHit.PROMPT
    if (QLayout.solutionRect(q, pageWidth).contains(x, y)) return QuestionHit.SOLUTION
    if (y in q.y..(q.y + q.height)) return QuestionHit.BODY
    return QuestionHit.OUTSIDE
}

fun regionForPoint(page: Page, x: Float, y: Float, pageWidth: Float): Pair<String, InkRegion>? {
    for (q in page.questions.asReversed()) {
        when (hitQuestion(q, x, y, pageWidth)) {
            QuestionHit.PROMPT -> return q.id to InkRegion.PROMPT
            QuestionHit.SOLUTION -> return q.id to InkRegion.SOLUTION
            else -> Unit
        }
    }
    return null
}

fun questionText(q: Question): String =
    q.promptText.trim().ifBlank { q.recognizedPrompt?.trim().orEmpty() }

fun solutionText(q: Question): String = q.recognizedSolution?.trim().orEmpty()

fun nextQuestionY(page: Page, viewportLocalY: Float, pageHeight: Float): Float {
    val lowest = page.questions.maxOfOrNull { it.y + it.height } ?: 0f
    val desired = viewportLocalY.coerceIn(24f, pageHeight - 680f)
    return max(desired, lowest + 24f).coerceAtMost(pageHeight - 200f)
}
