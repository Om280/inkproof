package com.inkproof.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "notebooks",
    indices = [Index("folderId")]
)
data class NotebookEntity(
    @PrimaryKey val id: String,
    val folderId: String? = null,
    val title: String,
    val coverColor: Int,
    val favorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "pages",
    indices = [Index("notebookId")]
)
data class PageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val orderIndex: Int,
    /** PageKind name: NOTE, MATH_QUESTION, PDF. */
    val kind: String,
    /** PageTemplate name. */
    val template: String,
    val widthPts: Float = 1600f,
    val heightPts: Float = 2200f,
    /** For PDF pages: path of the rendered source page. */
    val pdfPath: String? = null,
    val pdfPageIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "questions",
    indices = [Index("pageId")]
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderIndex: Int,
    val contentType: String,
    val typedText: String? = null,
    val mediaPath: String? = null,
    val questionTop: Float,
    val questionBottom: Float,
    val solutionTop: Float,
    val solutionBottom: Float,
    val contentVersion: Long,
    val solutionVersion: Long,
    val createdAt: Long
)

@Entity(
    tableName = "strokes",
    indices = [Index("pageId"), Index("questionId")]
)
data class StrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val questionId: String? = null,
    /** StrokeRole name. */
    val role: String,
    /** ToolType name. */
    val tool: String,
    val color: Int,
    val baseWidth: Float,
    /** ShapeType name when the stroke is a snapped shape. */
    val shapeType: String? = null,
    /** Packed point data; see [StrokeCodec]. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val points: ByteArray,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean =
        other is StrokeEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

@Entity(
    tableName = "text_objects",
    indices = [Index("pageId")]
)
data class TextObjectEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val questionId: String? = null,
    val text: String,
    val x: Float,
    val y: Float,
    val widthPts: Float,
    val fontSize: Float,
    val color: Int,
    val createdAt: Long
)

@Entity(
    tableName = "image_objects",
    indices = [Index("pageId")]
)
data class ImageObjectEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val questionId: String? = null,
    val path: String,
    val x: Float,
    val y: Float,
    val widthPts: Float,
    val heightPts: Float,
    val createdAt: Long
)

/**
 * Cached CHECK/SOLVE results, keyed by question + versions + action so that
 * editing the solution invalidates older results automatically.
 */
@Entity(
    tableName = "check_results",
    indices = [Index(value = ["questionId", "contentVersion", "solutionVersion", "action"], unique = true)]
)
data class CheckResultEntity(
    @PrimaryKey val id: String,
    val questionId: String,
    val contentVersion: Long,
    val solutionVersion: Long,
    /** CheckAction name. */
    val action: String,
    /** Raw structured JSON of the CheckResponse. */
    val resultJson: String,
    val createdAt: Long
)
