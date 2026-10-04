package com.inkproof.app.ui.editor

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkproof.app.ink.InkCanvasView
import com.inkproof.app.ink.ThumbnailRenderer
import com.inkproof.app.model.CheckAction
import com.inkproof.app.model.PageKind
import com.inkproof.app.model.PageTemplate
import com.inkproof.app.model.PenPalette
import com.inkproof.app.model.QuestionContentType
import com.inkproof.app.model.Stroke
import com.inkproof.app.model.ToolType
import com.inkproof.app.ui.theme.Divider
import com.inkproof.app.ui.theme.InkNavy
import com.inkproof.app.ui.theme.MutedText
import com.inkproof.app.ui.theme.ProofGreen

class EditorViewModelFactory(
    private val application: Application,
    private val notebookId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        EditorViewModel(application, notebookId) as T
}

@Composable
fun EditorScreen(
    notebookId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel(
        key = "editor-$notebookId",
        factory = EditorViewModelFactory(
            context.applicationContext as Application, notebookId
        )
    )

    val pageContent by viewModel.pageContent.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val penStyle by viewModel.penStyle.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val checkState by viewModel.checkState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val title by viewModel.notebookTitle.collectAsState()

    var canvasRef by remember { mutableStateOf<InkCanvasView?>(null) }
    var showQuestionComposer by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var showPageRail by remember { mutableStateOf(true) }

    // Keep screen awake while writing (setting).
    LaunchedEffect(settings.keepScreenAwake) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        if (settings.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Toasts
    LaunchedEffect(Unit) {
        viewModel.toast.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Pushes undo/redo/paste mutations into the canvas.
    LaunchedEffect(canvasRef) {
        val view = canvasRef ?: return@LaunchedEffect
        viewModel.canvasMutations.collect { mutation ->
            when (mutation) {
                is CanvasMutation.Add -> view.applyAdd(mutation.strokes)
                is CanvasMutation.Remove -> view.applyRemove(mutation.strokes)
                is CanvasMutation.Replace -> view.applyReplace(mutation.strokes)
            }
        }
    }

    // Load page content into the canvas when it changes.
    LaunchedEffect(pageContent?.loadToken, canvasRef) {
        val content = pageContent ?: return@LaunchedEffect
        val view = canvasRef ?: return@LaunchedEffect
        view.setPage(
            pageId = content.page.id,
            width = content.page.widthPts,
            height = content.page.heightPts,
            template = runCatching { PageTemplate.valueOf(content.page.template) }
                .getOrDefault(PageTemplate.RULED),
            strokes = content.strokes,
            questions = content.questions,
            pdfBackground = content.pdfBackground
        )
    }

    // Keep tool config in sync.
    LaunchedEffect(penStyle, settings.holdToShapeMs, settings.fingerWriting, settings.eraserRadius, canvasRef) {
        canvasRef?.let {
            it.penStyle = penStyle
            it.holdToShapeMs = settings.holdToShapeMs
            it.fingerWritingEnabled = settings.fingerWriting
            it.eraserRadiusPage = settings.eraserRadius
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        EditorToolbar(
            title = title,
            penStyle = penStyle,
            canUndo = canUndo,
            canRedo = canRedo,
            isQuestionPage = pageContent?.page?.kind == PageKind.MATH_QUESTION.name,
            onBack = onBack,
            onTool = viewModel::setTool,
            onColor = viewModel::setColor,
            onWidth = viewModel::setWidth,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onAddQuestion = { showQuestionComposer = true },
            onAddPage = { kind -> viewModel.addPage(kind) },
            onDeletePage = viewModel::deleteCurrentPage,
            onTemplates = { showTemplatePicker = true },
            onExportPdf = viewModel::exportPdf,
            onZoomFit = { canvasRef?.zoomToFit() },
            onTogglePages = { showPageRail = !showPageRail },
            onPaste = viewModel::paste
        )

        Row(Modifier.fillMaxSize()) {
            // ----- page rail -----
            AnimatedVisibility(visible = showPageRail) {
                PageRail(
                    pages = pages,
                    currentPageId = pageContent?.page?.id,
                    onSelect = viewModel::selectPage,
                    onAdd = {
                        viewModel.addPage(
                            if (pageContent?.page?.kind == PageKind.MATH_QUESTION.name)
                                PageKind.MATH_QUESTION else PageKind.NOTE,
                            runCatching {
                                PageTemplate.valueOf(pageContent?.page?.template ?: "RULED")
                            }.getOrDefault(PageTemplate.RULED)
                        )
                    }
                )
            }

            // ----- canvas -----
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    factory = { ctx ->
                        InkCanvasView(ctx).also { view ->
                            view.listener = object : InkCanvasView.Listener {
                                override fun onStrokeCommitted(stroke: Stroke) {
                                    viewModel.onStrokeCommitted(stroke)
                                }

                                override fun onStrokesErased(strokes: List<Stroke>) {
                                    viewModel.onStrokesErased(strokes)
                                }

                                override fun onSelectionChanged(
                                    strokes: List<Stroke>,
                                    screenBounds: RectF?
                                ) {
                                    viewModel.onSelectionChanged(strokes, screenBounds)
                                }

                                override fun onStrokesMoved(
                                    before: List<Stroke>,
                                    after: List<Stroke>
                                ) {
                                    viewModel.onStrokesMoved(before, after)
                                }
                            }
                            canvasRef = view
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ----- lasso contextual actions -----
                val (selectedStrokes, selectionBounds) = selection
                if (selectedStrokes.isNotEmpty() && selectionBounds != null) {
                    SelectionActions(
                        bounds = selectionBounds,
                        onCheck = { viewModel.checkSelection(CheckAction.CHECK) },
                        onSolve = { viewModel.checkSelection(CheckAction.SOLVE) },
                        onCopy = viewModel::copySelection,
                        onDelete = { canvasRef?.deleteSelection() },
                        onRecolor = { color -> canvasRef?.recolorSelection(color) },
                        onDismiss = { canvasRef?.clearSelection() }
                    )
                }

                // ----- per-question CHECK buttons -----
                val content = pageContent
                if (content != null && content.questions.isNotEmpty() &&
                    checkState is CheckUiState.Hidden
                ) {
                    QuestionActionsOverlay(
                        questions = content.questions,
                        onCheck = { qid -> viewModel.checkQuestion(qid, CheckAction.CHECK) },
                        onSolve = { qid -> viewModel.checkQuestion(qid, CheckAction.SOLVE) },
                        onEditText = { qid, text -> viewModel.updateQuestionText(qid, text) },
                        onDeleteQuestion = { qid -> viewModel.deleteQuestion(qid) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                    )
                }
            }

            // ----- check result side panel -----
            AnimatedVisibility(
                visible = checkState !is CheckUiState.Hidden,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                CheckPanel(
                    state = checkState,
                    onDismiss = viewModel::dismissCheck,
                    onTryAgain = viewModel::dismissCheck,
                    onRetryCheck = { qid, action -> viewModel.checkQuestion(qid, action) }
                )
            }
        }
    }

    if (showQuestionComposer) {
        QuestionComposerDialog(
            onDismiss = { showQuestionComposer = false },
            onCreate = { type, text ->
                viewModel.createQuestion(type, text)
                showQuestionComposer = false
            }
        )
    }

    if (showTemplatePicker) {
        TemplatePickerDialog(
            onDismiss = { showTemplatePicker = false },
            onPick = { template ->
                viewModel.setTemplate(template)
                showTemplatePicker = false
            }
        )
    }
}

// ============================= toolbar =============================

@Composable
private fun EditorToolbar(
    title: String,
    penStyle: com.inkproof.app.model.PenStyle,
    canUndo: Boolean,
    canRedo: Boolean,
    isQuestionPage: Boolean,
    onBack: () -> Unit,
    onTool: (ToolType) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddQuestion: () -> Unit,
    onAddPage: (PageKind) -> Unit,
    onDeletePage: () -> Unit,
    onTemplates: () -> Unit,
    onExportPdf: () -> Unit,
    onZoomFit: () -> Unit,
    onTogglePages: () -> Unit,
    onPaste: () -> Unit
) {
    var penOptionsFor by remember { mutableStateOf<ToolType?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(160.dp)
            )
            Spacer(Modifier.width(12.dp))

            // Tools
            ToolButton(
                selected = penStyle.tool == ToolType.PEN,
                icon = { Icon(Icons.Outlined.Edit, "Pen") },
                onClick = {
                    if (penStyle.tool == ToolType.PEN) penOptionsFor = ToolType.PEN
                    else onTool(ToolType.PEN)
                }
            )
            ToolButton(
                selected = penStyle.tool == ToolType.HIGHLIGHTER,
                icon = { Icon(Icons.Outlined.BorderColor, "Highlighter") },
                onClick = {
                    if (penStyle.tool == ToolType.HIGHLIGHTER) penOptionsFor = ToolType.HIGHLIGHTER
                    else onTool(ToolType.HIGHLIGHTER)
                }
            )
            ToolButton(
                selected = penStyle.tool == ToolType.ERASER,
                icon = { Icon(Icons.Outlined.Backspace, "Eraser") },
                onClick = { onTool(ToolType.ERASER) }
            )
            ToolButton(
                selected = penStyle.tool == ToolType.LASSO,
                icon = { Icon(Icons.Outlined.HighlightAlt, "Lasso") },
                onClick = { onTool(ToolType.LASSO) }
            )
            ToolButton(
                selected = penStyle.tool == ToolType.SHAPE,
                icon = { Icon(Icons.Outlined.Category, "Shapes") },
                onClick = { onTool(ToolType.SHAPE) }
            )
            ToolButton(
                selected = penStyle.tool == ToolType.PAN,
                icon = { Icon(Icons.Outlined.Gesture, "Pan") },
                onClick = { onTool(ToolType.PAN) }
            )

            // Current color indicator
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(penStyle.color))
                    .border(1.5.dp, Divider, CircleShape)
                    .clickable { penOptionsFor = penStyle.tool }
            )

            Spacer(Modifier.weight(1f))

            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo",
                    tint = if (canUndo) MaterialTheme.colorScheme.onSurface else Divider)
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Redo",
                    tint = if (canRedo) MaterialTheme.colorScheme.onSurface else Divider)
            }

            if (isQuestionPage) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clickable(onClick = onAddQuestion)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add, null,
                            tint = ProofGreen, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Question",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Filled.MoreVert, "More")
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Add note page") },
                        onClick = { overflowOpen = false; onAddPage(PageKind.NOTE) }
                    )
                    DropdownMenuItem(
                        text = { Text("Add question page") },
                        onClick = { overflowOpen = false; onAddPage(PageKind.MATH_QUESTION) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete this page") },
                        onClick = { overflowOpen = false; onDeletePage() }
                    )
                    DropdownMenuItem(
                        text = { Text("Page template…") },
                        onClick = { overflowOpen = false; onTemplates() }
                    )
                    DropdownMenuItem(
                        text = { Text("Paste") },
                        onClick = { overflowOpen = false; onPaste() }
                    )
                    DropdownMenuItem(
                        text = { Text("Zoom to fit") },
                        onClick = { overflowOpen = false; onZoomFit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Show/hide pages") },
                        onClick = { overflowOpen = false; onTogglePages() }
                    )
                    DropdownMenuItem(
                        text = { Text("Export PDF") },
                        onClick = { overflowOpen = false; onExportPdf() }
                    )
                }
            }
        }
    }

    penOptionsFor?.let { tool ->
        PenOptionsDialog(
            tool = tool,
            currentColor = penStyle.color,
            currentWidth = penStyle.baseWidth,
            onColor = onColor,
            onWidth = onWidth,
            onDismiss = { penOptionsFor = null }
        )
    }
}

@Composable
private fun ToolButton(
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) InkNavy else MutedText,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(40.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            icon()
        }
    }
}

@Composable
private fun PenOptionsDialog(
    tool: ToolType,
    currentColor: Int,
    currentWidth: Float,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = if (tool == ToolType.HIGHLIGHTER) PenPalette.highlighterColors
    else PenPalette.penColors
    val widths = if (tool == ToolType.HIGHLIGHTER) PenPalette.highlighterWidths
    else PenPalette.penWidths

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tool == ToolType.HIGHLIGHTER) "Highlighter" else "Pen") },
        text = {
            Column {
                Text("Color", style = MaterialTheme.typography.labelMedium, color = MutedText)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (c == currentColor) 3.dp else 1.dp,
                                    if (c == currentColor) InkNavy else Divider,
                                    CircleShape
                                )
                                .clickable { onColor(c) }
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Width", style = MaterialTheme.typography.labelMedium, color = MutedText)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    widths.forEach { w ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (w == currentWidth)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { onWidth(w) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size((w * 1.6f).coerceIn(4f, 30f).dp)
                                    .clip(CircleShape)
                                    .background(Color(currentColor))
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

// ============================= page rail =============================

@Composable
private fun PageRail(
    pages: List<com.inkproof.app.data.db.PageEntity>,
    currentPageId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .width(120.dp)
            .fillMaxHeight()
    ) {
        LazyColumn(
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                PageThumb(
                    index = index,
                    pageId = page.id,
                    selected = page.id == currentPageId,
                    onClick = { onSelect(page.id) }
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clickable(onClick = onAdd)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Add, "Add page", tint = MutedText)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageThumb(
    index: Int,
    pageId: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(pageId) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(pageId, selected) {
        val app = context.applicationContext as com.inkproof.app.InkProofApp
        val page = app.libraryRepository.page(pageId) ?: return@LaunchedEffect
        val strokes = app.pageRepository.strokesForPage(pageId)
        val template = runCatching { PageTemplate.valueOf(page.template) }
            .getOrDefault(PageTemplate.BLANK)
        thumb = ThumbnailRenderer.render(strokes, template, page.widthPts, page.heightPts, 160)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(
                if (selected) 2.5.dp else 1.dp,
                if (selected) InkNavy else Divider
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clickable(onClick = onClick)
        ) {
            thumb?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) InkNavy else MutedText,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ============================= selection actions =============================

@Composable
private fun SelectionActions(
    bounds: RectF,
    onCheck: () -> Unit,
    onSolve: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRecolor: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalContext.current.resources.displayMetrics.density
    var recolorOpen by remember { mutableStateOf(false) }
    val yOffset = ((bounds.top / density) - 56f).coerceAtLeast(8f)
    val xOffset = (bounds.left / density).coerceAtLeast(8f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(start = xOffset.dp, top = yOffset.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            TextButton(onClick = onCheck) { Text("Check my work", color = ProofGreen) }
            TextButton(onClick = onSolve) { Text("Solve") }
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = { recolorOpen = true }) { Text("Color") }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete, "Delete selection",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            DropdownMenu(expanded = recolorOpen, onDismissRequest = { recolorOpen = false }) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    PenPalette.penColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .padding(3.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable { onRecolor(c); recolorOpen = false }
                        )
                    }
                }
            }
        }
    }
}

// ============================= question overlay =============================

@Composable
private fun QuestionActionsOverlay(
    questions: List<com.inkproof.app.model.Question>,
    onCheck: (String) -> Unit,
    onSolve: (String) -> Unit,
    onEditText: (String, String) -> Unit,
    onDeleteQuestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(questions.size == 1) }
    var editFor by remember { mutableStateOf<com.inkproof.app.model.Question?>(null) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.width(280.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    "Check my work",
                    style = MaterialTheme.typography.titleSmall,
                    color = ProofGreen,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (expanded) "Hide" else "${questions.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedText
                )
            }
            if (expanded) {
                questions.forEachIndexed { index, q ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Q${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = InkNavy,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { editFor = q }) {
                                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = { onDeleteQuestion(q.id) }) {
                                    Text(
                                        "Delete",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            q.typedText?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ProofGreen,
                                    modifier = Modifier.clickable { onCheck(q.id) }
                                ) {
                                    Text(
                                        "Check my work",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp, vertical = 7.dp
                                        )
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Transparent,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Divider),
                                    modifier = Modifier.clickable { onSolve(q.id) }
                                ) {
                                    Text(
                                        "Solve",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MutedText,
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp, vertical = 7.dp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editFor?.let { q ->
        var text by remember(q.id) { mutableStateOf(q.typedText.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editFor = null },
            title = { Text("Edit question") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Question") },
                    minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = { onEditText(q.id, text); editFor = null }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editFor = null }) { Text("Cancel") }
            }
        )
    }
}

// ============================= dialogs =============================

@Composable
private fun QuestionComposerDialog(
    onDismiss: () -> Unit,
    onCreate: (QuestionContentType, String?) -> Unit
) {
    var mode by remember { mutableStateOf(QuestionContentType.TYPED) }
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New question") },
        text = {
            Column {
                Text(
                    "Add the problem the easiest way for you. Your solution gets its own handwriting area below it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Type", mode == QuestionContentType.TYPED) {
                        mode = QuestionContentType.TYPED
                    }
                    ModeChip("Write by hand", mode == QuestionContentType.HANDWRITTEN) {
                        mode = QuestionContentType.HANDWRITTEN
                    }
                    ModeChip("Paste", mode == QuestionContentType.PASTED) {
                        mode = QuestionContentType.PASTED
                        val pasted = clipboard?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        if (!pasted.isNullOrBlank()) text = pasted
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (mode) {
                    QuestionContentType.HANDWRITTEN -> Text(
                        "A question area will be added to the page — write the problem there with your stylus. InkProof recognizes only that region.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                    else -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Question (e.g. Solve 2x + 6 = 14)") },
                        minLines = 2
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(mode, text.takeIf { it.isNotBlank() })
            }) { Text("Add question") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) InkNavy else MutedText,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onPick: (PageTemplate) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page template") },
        text = {
            Column {
                Text(
                    "Changing the template never touches your ink.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                listOf(
                    PageTemplate.BLANK to "Blank",
                    PageTemplate.RULED to "Ruled",
                    PageTemplate.GRID to "Grid",
                    PageTemplate.DOT_GRID to "Dot grid",
                    PageTemplate.ENGINEERING to "Engineering grid",
                    PageTemplate.MATH_WORKSHEET to "Math worksheet"
                ).forEach { (template, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(template) }
                            .padding(vertical = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
