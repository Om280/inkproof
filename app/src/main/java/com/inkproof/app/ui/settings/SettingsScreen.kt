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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.inkproof.app.ui.theme.ThemeMode
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
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
            SectionHeader("Appearance")
            SettingsCard {
                ChoiceRow(
                    title = "Theme",
                    subtitle = "Pages keep their own paper color",
                    options = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark"
                    ),
                    selected = settings.appTheme
                ) { scope.launch { store.setAppTheme(it) } }
            }

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
                ToggleRow("Full-screen canvas", "Hide system bars while writing",
                    settings.fullScreenCanvas) {
                    scope.launch { store.setFullScreenCanvas(it) }
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
                BackendUrlRow(
                    current = settings.backendUrl,
                    effective = settings.backendUrl.ifBlank { BuildConfig.BACKEND_BASE_URL },
                    onSave = { scope.launch { store.setBackendUrl(it) } }
                )
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
private fun ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(value) }
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
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
private fun BackendUrlRow(
    current: String,
    effective: String,
    onSave: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text("Backend URL", style = MaterialTheme.typography.bodyLarge)
        Text(
            effective + if (current.isBlank()) "  (default — tap to change)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText
        )
    }
    if (editing) {
        var url by remember { mutableStateOf(current.ifBlank { effective }) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Backend URL") },
            text = {
                Column {
                    Text(
                        "Where CHECK MY WORK sends requests when mock mode is off. " +
                            "Example: http://192.168.1.50:8787 (your computer running " +
                            "the InkProof backend on the same Wi-Fi).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        placeholder = { Text("http://192.168.1.50:8787") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(url); editing = false }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onSave(""); editing = false }) {
                        Text("Reset to default")
                    }
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            }
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
