package com.inkproof.app.ui.editor

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.CheckStatus
import com.inkproof.app.model.StepStatus
import com.inkproof.app.ui.theme.ErrorRed
import com.inkproof.app.ui.theme.ErrorRedSoft
import com.inkproof.app.ui.theme.InkNavy
import com.inkproof.app.ui.theme.MutedText
import com.inkproof.app.ui.theme.ProofGreen
import com.inkproof.app.ui.theme.ProofGreenSoft
import com.inkproof.app.ui.theme.WarnAmber
import com.inkproof.app.ui.theme.WarnAmberSoft
import com.inkproof.app.model.CheckAction

/**
 * Compact side panel for CHECK MY WORK results.
 * The student's handwriting stays visible — this never covers the canvas.
 */
@Composable
fun CheckPanel(
    state: CheckUiState,
    onDismiss: () -> Unit,
    onTryAgain: () -> Unit,
    onRetryCheck: (String, CheckAction) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(360.dp)
            .fillMaxHeight()
    ) {
        when (state) {
            is CheckUiState.Hidden -> Unit

            is CheckUiState.Loading -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ProofGreen)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (state.action == CheckAction.CHECK) "Checking your work…"
                        else "Working out a solution…",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Reading only this question and your solution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
            }

            is CheckUiState.Result -> ResultContent(
                response = state.response,
                action = state.action,
                questionId = state.questionId,
                onDismiss = onDismiss,
                onTryAgain = onTryAgain,
                onRetryCheck = onRetryCheck
            )
        }
    }
}

@Composable
private fun ResultContent(
    response: CheckResponse,
    action: CheckAction,
    questionId: String?,
    onDismiss: () -> Unit,
    onTryAgain: () -> Unit,
    onRetryCheck: (String, CheckAction) -> Unit
) {
    var hintsRevealed by remember(response) { mutableIntStateOf(0) }
    var solutionRevealed by remember(response) { mutableStateOf(false) }

    val (headerColor, headerBg, headerText) = when (response.status) {
        CheckStatus.CORRECT -> Triple(ProofGreen, ProofGreenSoft, "Correct")
        CheckStatus.INCORRECT -> Triple(ErrorRed, ErrorRedSoft, "First mistake found")
        CheckStatus.INCOMPLETE -> Triple(WarnAmber, WarnAmberSoft, "Incomplete")
        CheckStatus.UNCLEAR -> Triple(WarnAmber, WarnAmberSoft, "Hard to read")
        CheckStatus.UNSUPPORTED -> Triple(MutedText, Color(0xFFEDEFF5), "Not supported yet")
        CheckStatus.ERROR -> Triple(ErrorRed, ErrorRedSoft, "Something went wrong")
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Surface(color = headerBg) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (action == CheckAction.SOLVE) "Solution" else headerText,
                        style = MaterialTheme.typography.titleMedium,
                        color = headerColor
                    )
                    response.summary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "Close", tint = MutedText)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            // Question echo — full transparency about what was analyzed.
            response.questionEcho?.takeIf { it.isNotBlank() }?.let {
                Text("QUESTION", style = MaterialTheme.typography.labelMedium, color = MutedText)
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
            }

            // Error / info message
            response.message?.let {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = headerBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // Steps
            if (response.steps.isNotEmpty()) {
                Text("STEPS", style = MaterialTheme.typography.labelMedium, color = MutedText)
                Spacer(Modifier.height(8.dp))
                response.steps.forEach { step ->
                    StepRow(
                        step = step,
                        isFirstError = step.stepId == response.firstErrorStep
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            // Progressive hints
            if (response.hints.isNotEmpty() && response.status != CheckStatus.CORRECT) {
                Text("HINTS", style = MaterialTheme.typography.labelMedium, color = MutedText)
                Spacer(Modifier.height(8.dp))
                response.hints.take(hintsRevealed).forEachIndexed { i, hint ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = InkNavy,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Text(hint, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (hintsRevealed < response.hints.size) {
                    OutlinedButton(onClick = { hintsRevealed++ }) {
                        Text(if (hintsRevealed == 0) "Get a hint" else "Next hint")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Full solution (hidden by default)
            response.fullSolution?.let { solution ->
                Spacer(Modifier.height(8.dp))
                if (solutionRevealed || action == CheckAction.SOLVE) {
                    Text("SOLUTION", style = MaterialTheme.typography.labelMedium, color = MutedText)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ProofGreenSoft,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(solution, style = MaterialTheme.typography.bodyMedium)
                            response.finalAnswer?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Answer: $it",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ProofGreen
                                )
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { solutionRevealed = true }) {
                        Text("Show solution", color = MutedText)
                    }
                }
            }
        }

        // Footer actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            OutlinedButton(
                onClick = onTryAgain,
                modifier = Modifier.weight(1f)
            ) { Text("Try again") }
            Spacer(Modifier.width(10.dp))
            if (questionId != null &&
                (response.status == CheckStatus.UNCLEAR || response.status == CheckStatus.ERROR)
            ) {
                Button(
                    onClick = { onRetryCheck(questionId, action) },
                    colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                    modifier = Modifier.weight(1f)
                ) { Text("Check again") }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = ProofGreen),
                    modifier = Modifier.weight(1f)
                ) { Text("Done") }
            }
        }
    }
}

@Composable
private fun StepRow(step: com.inkproof.app.model.CheckStep, isFirstError: Boolean) {
    val (icon, tint, bg) = when (step.status) {
        StepStatus.CORRECT -> Triple(Icons.Filled.Check, ProofGreen, Color.Transparent)
        StepStatus.INCORRECT -> Triple(Icons.Filled.Close, ErrorRed, ErrorRedSoft)
        StepStatus.DEPENDENT_ON_PREVIOUS_ERROR ->
            Triple(Icons.Outlined.SubdirectoryArrowRight, MutedText, Color.Transparent)
        StepStatus.UNCLEAR -> Triple(Icons.Filled.QuestionMark, WarnAmber, WarnAmberSoft)
        StepStatus.SKIPPED -> Triple(Icons.Filled.QuestionMark, MutedText, Color.Transparent)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f))
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        step.expression.ifBlank { step.stepId },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFirstError) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "FIRST MISTAKE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ErrorRed
                        )
                    }
                }
                step.explanation?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
                step.hint?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Hint: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnAmber
                    )
                }
            }
        }
    }
}
