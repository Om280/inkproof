package com.inkproof.app.data.repo

import com.inkproof.app.data.db.QuestionEntity
import com.inkproof.app.data.db.StrokeCodec
import com.inkproof.app.data.db.StrokeEntity
import com.inkproof.app.data.db.TextObjectEntity
import com.inkproof.app.model.Question
import com.inkproof.app.model.QuestionContentType
import com.inkproof.app.model.ShapeType
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.StrokeRole
import com.inkproof.app.model.TextObject
import com.inkproof.app.model.ToolType

fun Stroke.toEntity(): StrokeEntity = StrokeEntity(
    id = id,
    pageId = pageId,
    questionId = questionId,
    role = role.name,
    tool = tool.name,
    color = color,
    baseWidth = baseWidth,
    shapeType = shapeType?.name,
    points = StrokeCodec.encode(points),
    createdAt = createdAt
)

fun StrokeEntity.toModel(): Stroke = Stroke(
    id = id,
    pageId = pageId,
    questionId = questionId,
    role = runCatching { StrokeRole.valueOf(role) }.getOrDefault(StrokeRole.FREEFORM),
    tool = runCatching { ToolType.valueOf(tool) }.getOrDefault(ToolType.PEN),
    color = color,
    baseWidth = baseWidth,
    points = StrokeCodec.decode(points),
    createdAt = createdAt,
    shapeType = shapeType?.let { runCatching { ShapeType.valueOf(it) }.getOrNull() }
)

fun Question.toEntity(): QuestionEntity = QuestionEntity(
    id = id,
    pageId = pageId,
    orderIndex = orderIndex,
    contentType = contentType.name,
    typedText = typedText,
    mediaPath = mediaPath,
    questionTop = questionTop,
    questionBottom = questionBottom,
    solutionTop = solutionTop,
    solutionBottom = solutionBottom,
    contentVersion = contentVersion,
    solutionVersion = solutionVersion,
    createdAt = createdAt
)

fun TextObject.toEntity(): TextObjectEntity = TextObjectEntity(
    id = id,
    pageId = pageId,
    questionId = questionId,
    text = text,
    x = x,
    y = y,
    widthPts = widthPts,
    fontSize = fontSize,
    color = color,
    createdAt = createdAt
)

fun TextObjectEntity.toModel(): TextObject = TextObject(
    id = id,
    pageId = pageId,
    questionId = questionId,
    text = text,
    x = x,
    y = y,
    widthPts = widthPts,
    fontSize = fontSize,
    color = color,
    createdAt = createdAt
)

fun QuestionEntity.toModel(): Question = Question(
    id = id,
    pageId = pageId,
    orderIndex = orderIndex,
    contentType = runCatching { QuestionContentType.valueOf(contentType) }
        .getOrDefault(QuestionContentType.TYPED),
    typedText = typedText,
    mediaPath = mediaPath,
    questionTop = questionTop,
    questionBottom = questionBottom,
    solutionTop = solutionTop,
    solutionBottom = solutionBottom,
    contentVersion = contentVersion,
    solutionVersion = solutionVersion,
    createdAt = createdAt
)
