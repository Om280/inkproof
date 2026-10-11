package app.inkproof.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkproof.core.model.EraserSize
import app.inkproof.core.model.PageTemplate
import app.inkproof.core.model.Palette
import app.inkproof.core.model.PaperColor
import app.inkproof.core.model.ShapeKind
import app.inkproof.core.model.StabilizerLevel
import app.inkproof.core.model.ToolMode
import app.inkproof.editor.EditorController
import app.inkproof.editor.EditorDialog

@Composable
fun EditorTopBar(controller: EditorController, onBack: () -> Unit, onChrome: () -> Unit) {
    val state by controller.chrome.collectAsState()
    androidx.compose.runtime.SideEffect { onChrome() }
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Library") }
            Text(
                state.banner ?: state.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (state.banner != null) {
                TextButton(onClick = { controller.dismissBanner() }) { Text("OK") }
            }
            TextButton(onClick = { controller.undo() }, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = { controller.redo() }, enabled = state.canRedo) { Text("Redo") }
            TextButton(onClick = { controller.addQuestion() }) { Text("+ Question") }
            TextButton(onClick = { controller.requestCheck() }) { Text("Check") }
            TextButton(onClick = { controller.toggleDrawer() }) { Text(state.pageLabel) }
        }
    }
}

@Composable
fun ToolRail(controller: EditorController) {
    val state by controller.chrome.collectAsState()
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxHeight()) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolMode.entries.forEach { mode ->
                val selected = state.tool == mode
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { controller.setTool(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    ToolGlyph(mode, if (mode == ToolMode.MARKER) state.markerColor else state.penColor)
                }
            }
            if (state.flyout) {
                Spacer(Modifier.height(4.dp))
                when (state.tool) {
                    ToolMode.PEN, ToolMode.MARKER -> ColorRow(
                        controller,
                        state.tool == ToolMode.MARKER,
                        if (state.tool == ToolMode.MARKER) state.markerColor else state.penColor,
                        state.customColors,
                    )
                    ToolMode.ERASER -> EraserRow(controller, state.eraser)
                    ToolMode.SHAPE -> ShapeRow(controller, state.shapeKind)
                    else -> Unit
                }
                if (state.tool == ToolMode.PEN) {
                    TextButton(onClick = { controller.setPenWidth(1.6f) }) { Text("Thin", fontSize = 11.sp) }
                    TextButton(onClick = { controller.setPenWidth(2.6f) }) { Text("Med", fontSize = 11.sp) }
                    TextButton(onClick = { controller.setPenWidth(4.2f) }) { Text("Thick", fontSize = 11.sp) }
                }
                if (state.tool == ToolMode.MARKER) {
                    TextButton(onClick = { controller.setMarkerAlpha(0.25f) }) { Text("25%", fontSize = 11.sp) }
                    TextButton(onClick = { controller.setMarkerAlpha(0.38f) }) { Text("38%", fontSize = 11.sp) }
                    TextButton(onClick = { controller.setMarkerAlpha(0.6f) }) { Text("60%", fontSize = 11.sp) }
                }
                TextButton(onClick = { controller.setStabilizer(nextLevel(state.stabilizer)) }) {
                    Text(state.stabilizer.name.lowercase(), fontSize = 11.sp)
                }
            }
            if (state.selectionCount > 0) {
                TextButton(onClick = { controller.copySelection() }) { Text("Copy") }
                TextButton(onClick = { controller.cutSelection() }) { Text("Cut") }
                TextButton(onClick = { controller.duplicateSelection() }) { Text("Dup") }
                TextButton(onClick = { controller.deleteSelection() }) { Text("Del") }
                TextButton(onClick = { controller.requestCheck() }) { Text("Check") }
                TextButton(onClick = { controller.requestSolve() }) { Text("Solve") }
            }
            TextButton(onClick = { controller.pasteSelection() }) { Text("Paste") }
        }
    }
}

@Composable
private fun ToolGlyph(mode: ToolMode, color: Long) {
    val label = when (mode) {
        ToolMode.PEN -> "Pen"
        ToolMode.MARKER -> "Mark"
        ToolMode.ERASER -> "Erase"
        ToolMode.LASSO -> "Lasso"
        ToolMode.SHAPE -> "Shape"
        ToolMode.TEXT -> "Text"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(14.dp).clip(CircleShape).background(Color(color.toInt()))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Text(label, fontSize = 9.sp)
    }
}

@Composable
private fun ColorRow(controller: EditorController, marker: Boolean, selected: Long, custom: List<Long>) {
    var askCustom by remember { mutableStateOf(false) }
    var hex by remember { mutableStateOf("7A5AF0") }
    Column {
        (Palette.all.map { it.second } + custom).chunked(4).forEach { row ->
            Row {
                row.forEach { color ->
                    Box(
                        Modifier.padding(2.dp).size(18.dp).clip(CircleShape).background(Color(color.toInt()))
                            .border(if (color == selected) 2.dp else 1.dp, if (color == selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                            .clickable { if (marker) controller.setMarkerColor(color) else controller.setPenColor(color) },
                    )
                }
            }
        }
        TextButton(onClick = { askCustom = true }) { Text("Custom", fontSize = 11.sp) }
    }
    if (askCustom) {
        AlertDialog(
            onDismissRequest = { askCustom = false },
            title = { Text("Custom color") },
            text = {
                OutlinedTextField(hex, { hex = it.take(8) }, label = { Text("RRGGBB") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    val parsed = hex.trim().removePrefix("#").toLongOrNull(16)
                    if (parsed != null) {
                        val argb = if (hex.trim().removePrefix("#").length <= 6) 0xFF000000L or parsed else parsed
                        if (marker) controller.setMarkerColor(argb) else controller.rememberCustomColor(argb)
                    }
                    askCustom = false
                }) { Text("Use") }
            },
            dismissButton = { TextButton(onClick = { askCustom = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EraserRow(controller: EditorController, current: EraserSize) {
    Row {
        EraserSize.entries.forEach { size ->
            TextButton(onClick = { controller.setEraser(size) }) {
                Text(if (size == current) size.name.take(1) else size.name.take(1), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ShapeRow(controller: EditorController, current: ShapeKind) {
    Column {
        ShapeKind.entries.forEach { kind ->
            Text(
                kind.name.lowercase(),
                fontSize = 11.sp,
                color = if (kind == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { controller.setShapeKind(kind) }.padding(vertical = 2.dp),
            )
        }
    }
}

private fun nextLevel(level: StabilizerLevel): StabilizerLevel = when (level) {
    StabilizerLevel.OFF -> StabilizerLevel.LOW
    StabilizerLevel.LOW -> StabilizerLevel.MEDIUM
    StabilizerLevel.MEDIUM -> StabilizerLevel.HIGH
    StabilizerLevel.HIGH -> StabilizerLevel.OFF
}

@Composable
fun ReviewPanelView(controller: EditorController) {
    val state by controller.chrome.collectAsState()
    val review = state.review ?: return
    val response = review.response
    Surface(Modifier.fillMaxHeight().width(320.dp), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.dismissReview() }) { Text("Close") }
            }
            val statusColor = when (response.status) {
                "correct" -> Correct
                "incorrect" -> Proof
                "incomplete", "unclear" -> Caution
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(response.status.replaceFirstChar { it.uppercase() }, color = statusColor, fontFamily = FontFamily.Serif, fontSize = 22.sp)
            Text(
                when {
                    response.mock -> "MOCK — not a real check"
                    response.provider.contains("gemini") -> "Provider ${response.provider}${response.model?.let { " · $it" } ?: ""}"
                    response.provider == "on_device" -> "On-device check. Not a Gemini response."
                    else -> response.provider
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (response.status == "error") {
                Text(response.errorCode ?: "error", color = Proof, modifier = Modifier.padding(top = 8.dp))
                Text(response.message ?: "The request failed. Nothing was marked correct.", style = MaterialTheme.typography.bodyMedium)
            }
            if (response.recognizedQuestion.isNotBlank()) {
                Text("READ AS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                Text(response.recognizedQuestion, style = MaterialTheme.typography.bodyMedium)
            }
            response.firstError?.let { err ->
                Text("FIRST MISTAKE", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                Text("Step ${err.stepIndex + 1}", color = Proof)
                Text(err.explanation, style = MaterialTheme.typography.bodyMedium)
            }
            if (response.explanation.isNotBlank() && response.status != "error") {
                Text(response.explanation, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            response.stepAssessments.forEach { step ->
                if (step.verdict == "commentary") return@forEach
                Text("${step.stepIndex + 1}. ${step.verdict} — ${step.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (review.visibleHints().isNotEmpty()) {
                Text("HINTS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                review.visibleHints().forEachIndexed { i, hint -> Text("${i + 1}. $hint", style = MaterialTheme.typography.bodyMedium) }
                if (review.hintsShown < response.hints.size) {
                    TextButton(onClick = { controller.nextHint() }) { Text("Another hint") }
                }
            }
            Row(Modifier.padding(top = 12.dp)) {
                TextButton(onClick = { controller.tryAgain() }) { Text("Try again") }
                TextButton(onClick = { controller.revealSolution() }) { Text("Show solution") }
            }
            if (review.solutionRevealed) {
                Text("SOLUTION", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                review.visibleAnswer()?.let { Text(it, fontFamily = FontFamily.Serif, fontSize = 18.sp) }
                review.visibleSolution().forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            } else {
                Text("The full answer stays hidden until you ask.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (state.busy) Text("Checking…", modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun PageDrawer(controller: EditorController, onImport: () -> Unit = {}, onExport: () -> Unit = {}) {
    val state by controller.chrome.collectAsState()
    Surface(Modifier.fillMaxHeight().width(280.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("Pages", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { controller.addPage() }) { Text("New blank page") }
            TextButton(onClick = { controller.typeIntoActive("prompt") }) { Text("Type question") }
            TextButton(onClick = { controller.typeIntoActive("solution") }) { Text("Type solution") }
            TextButton(onClick = onImport) { Text("Import file") }
            TextButton(onClick = onExport) { Text("Export annotated PDF") }
            TextButton(onClick = { controller.deleteCurrentPage() }) { Text("Delete current page") }
            state.pages.forEach { chip ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(Color(chip.paper.argb.toInt())))
                    Text("  ${chip.index + 1}  ${chip.template.name.lowercase()}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { controller.movePage(chip.id, -1) }) { Text("Up") }
                    TextButton(onClick = { controller.movePage(chip.id, 1) }) { Text("Dn") }
                }
                Text("Paper", style = MaterialTheme.typography.labelSmall)
                Row {
                    PaperColor.entries.take(4).forEach { paper ->
                        Box(
                            Modifier.padding(2.dp).size(18.dp).background(Color(paper.argb.toInt())).border(1.dp, Color.Gray)
                                .clickable { controller.setPaper(chip.id, paper) },
                        )
                    }
                }
                Row {
                    PaperColor.entries.drop(4).forEach { paper ->
                        Box(
                            Modifier.padding(2.dp).size(18.dp).background(Color(paper.argb.toInt())).border(1.dp, Color.Gray)
                                .clickable { controller.setPaper(chip.id, paper) },
                        )
                    }
                }
                PageTemplate.entries.forEach { template ->
                    Text(
                        template.name.lowercase(),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { controller.setTemplate(chip.id, template) }.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EditorDialogs(controller: EditorController) {
    val state by controller.chrome.collectAsState()
    when (val dialog = state.dialog) {
        is EditorDialog.ConfirmRead -> ConfirmDialog(controller, dialog)
        is EditorDialog.ChooseQuestion -> {
            AlertDialog(
                onDismissRequest = { controller.dismissDialog() },
                title = { Text("Which question?") },
                text = {
                    Column {
                        Text("InkProof will not guess between questions.")
                        dialog.ids.forEachIndexed { index, id ->
                            TextButton(onClick = {
                                controller.dismissDialog()
                                if (dialog.action == "solve") controller.requestSolve(id) else controller.requestCheck(id)
                            }) { Text(dialog.labels.getOrElse(index) { "Question ${index + 1}" }) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { controller.dismissDialog() }) { Text("Cancel") } },
            )
        }
        is EditorDialog.TypeText -> TypeDialog(controller, dialog)
        is EditorDialog.Notice -> AlertDialog(
            onDismissRequest = { controller.dismissDialog() },
            title = { Text("InkProof") },
            text = { Text(dialog.text) },
            confirmButton = { TextButton(onClick = { controller.dismissDialog() }) { Text("OK") } },
        )
        null -> Unit
    }
}

@Composable
private fun ConfirmDialog(controller: EditorController, dialog: EditorDialog.ConfirmRead) {
    var prompt by remember(dialog.questionId, dialog.prompt) { mutableStateOf(dialog.prompt) }
    var solution by remember(dialog.questionId, dialog.solution) { mutableStateOf(dialog.solution) }
    AlertDialog(
        onDismissRequest = { controller.dismissDialog() },
        title = { Text(if (dialog.action == "recognize") "Reading" else "Confirm before checking") },
        text = {
            Column {
                Text(dialog.reason, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Question") }, modifier = Modifier.padding(top = 8.dp))
                if (dialog.action != "solve") {
                    OutlinedTextField(solution, { solution = it }, label = { Text("Your solution") }, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (dialog.action == "recognize") {
                    controller.setPrompt(dialog.pageId, dialog.questionId, prompt)
                    controller.setSolutionText(dialog.pageId, dialog.questionId, solution)
                } else {
                    controller.confirmAndRun(dialog, prompt, solution)
                }
            }) { Text(if (dialog.action == "recognize") "Keep ink, save reading" else "Check this") }
        },
        dismissButton = { TextButton(onClick = { controller.dismissDialog() }) { Text("Cancel") } },
    )
}

@Composable
private fun TypeDialog(controller: EditorController, dialog: EditorDialog.TypeText) {
    var text by remember { mutableStateOf(dialog.initial) }
    AlertDialog(
        onDismissRequest = { controller.dismissDialog() },
        title = { Text(if (dialog.field == "prompt") "Type question" else "Type solution") },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            Button(onClick = {
                if (dialog.field == "prompt") controller.setPrompt(dialog.pageId, dialog.questionId, text)
                else controller.setSolutionText(dialog.pageId, dialog.questionId, text)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { controller.dismissDialog() }) { Text("Cancel") } },
    )
}

