package com.inkproof.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkproof.app.BuildConfig
import com.inkproof.app.InkProofApp
import com.inkproof.app.data.settings.Settings
import com.inkproof.app.model.PenPalette
import com.inkproof.app.ui.theme.Divider
import com.inkproof.app.ui.theme.InkNavy
import com.inkproof.app.ui.theme.MutedText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InkProofApp
    val store = app.settingsStore
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = Settings())

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 16.dp)
                .widthIn(max = 720.dp)
        ) {
            SectionHeader("Canvas")
            SettingsCard {
                ToggleRow("Continue from last page", "Reopen notebooks where you left off",
                    settings.continueFromLastPage) {
                    scope.launch { store.setContinueFromLastPage(it) }
                }
                ToggleRow("Keep screen awake", "While the editor is open",
                    settings.keepScreenAwake) {
                    scope.launch { store.setKeepScreenAwake(it) }
                }
                ToggleRow(
                    "Finger writing",
                    "Off: stylus writes, fingers navigate (recommended with Stylo 2)",
                    settings.fingerWriting
                ) {
                    scope.launch { store.setFingerWriting(it) }
                }
            }

            SectionHeader("Writing")
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text("Default pen color", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        PenPalette.penColors.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        if (settings.defaultPenColor == c) 3.dp else 1.dp,
                                        if (settings.defaultPenColor == c) InkNavy else Divider,
                                        CircleShape
                                    )
                                    .clickable { scope.launch { store.setDefaultPenColor(c) } }
                            )
                        }
                    }
                }
                SliderRow(
                    "Default pen width",
                    value = settings.defaultPenWidth,
                    range = 1f..10f,
                    format = { "%.1f".format(it) }
                ) { scope.launch { store.setDefaultPenWidth(it) } }
                ToggleRow("Pressure sensitivity", "Vary ink width with stylus pressure",
                    settings.pressureEnabled) {
                    scope.launch { store.setPressureEnabled(it) }
                }
                SliderRow(
                    "Hold-to-shape delay",
                    value = settings.holdToShapeMs.toFloat(),
                    range = 200f..1000f,
                    format = { "${it.toInt()} ms" }
                ) { scope.launch { store.setHoldToShapeMs(it.toLong()) } }
                SliderRow(
                    "Eraser size",
                    value = settings.eraserRadius,
                    range = 6f..60f,
                    format = { "${it.toInt()}" }
                ) { scope.launch { store.setEraserRadius(it) } }
            }

            SectionHeader("Math")
            SettingsCard {
                SliderRow(
                    "Recognition confidence threshold",
                    value = settings.recognitionConfidenceThreshold,
                    range = 0.1f..0.9f,
                    format = { "%.0f%%".format(it * 100) }
                ) { scope.launch { store.setRecognitionConfidence(it) } }
                ToggleRow(
                    "Show first hint automatically",
                    "After a mistake is found",
                    settings.autoShowHints
                ) { scope.launch { store.setAutoShowHints(it) } }
            }

            SectionHeader("AI")
            SettingsCard {
                ToggleRow(
                    "Mock mode",
                    if (settings.mockMode)
                        "ON — checks are simulated locally; no backend or credentials used"
                    else "OFF — checks call the InkProof backend",
                    settings.mockMode
                ) { scope.launch { store.setMockMode(it) } }
                InfoRow("Backend", BuildConfig.BACKEND_BASE_URL)
                ActionRow("Clear cached check results") {
                    scope.launch {
                        app.checkRepository.clearAll()
                        Toast.makeText(context, "Check cache cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            SectionHeader("Storage")
            SettingsCard {
                ActionRow("Storage info") {
                    scope.launch {
                        val db = context.getDatabasePath("inkproof.db")
                        val size = if (db.exists()) db.length() / 1024 else 0
                        Toast.makeText(
                            context, "Database: ${size} KB", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            SectionHeader("About")
            SettingsCard {
                InfoRow("Version", BuildConfig.VERSION_NAME)
                InfoRow("InkProof", "Prove your work.")
                InfoRow("Privacy", "Notebooks stay on this device. Checking sends only the active question and your solution to the backend.")
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MutedText,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MutedText)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MutedText)
    }
}

@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = InkNavy,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    )
}
