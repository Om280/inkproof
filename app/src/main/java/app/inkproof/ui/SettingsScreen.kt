package app.inkproof.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.inkproof.BuildConfig
import app.inkproof.core.model.AiMode
import app.inkproof.core.model.AppSettings
import app.inkproof.core.model.StabilizerLevel
import app.inkproof.core.model.ThemeMode

@Composable
fun SettingsScreen(
    settings: AppSettings,
    status: String,
    onChange: (AppSettings) -> Unit,
    onTest: () -> Unit,
    onBack: () -> Unit,
) {
    var url by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    var hold by remember(settings.holdToShapeMs) { mutableFloatStateOf(settings.holdToShapeMs.toFloat()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Application theme is separate from paper color.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.theme == mode, onClick = { onChange(settings.copy(theme = mode)) })
                Text(mode.name.lowercase())
            }
        }
        Text("Stabilization", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Default is medium. The pen tip is drawn immediately; smoothing catches up behind it.", style = MaterialTheme.typography.bodySmall)
        StabilizerLevel.entries.forEach { level ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.stabilizer == level, onClick = { onChange(settings.copy(stabilizer = level)) })
                Text(level.name.lowercase())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Hold to shape", modifier = Modifier.weight(1f))
            Switch(checked = settings.holdToShapeEnabled, onCheckedChange = { onChange(settings.copy(holdToShapeEnabled = it)) })
        }
        Text("Hold ${hold.toInt()} ms")
        Slider(
            value = hold,
            onValueChange = { hold = it },
            onValueChangeFinished = { onChange(settings.copy(holdToShapeMs = hold.toLong())) },
            valueRange = 200f..1000f,
        )
        Text("AI mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Real calls the backend. Mock is labeled and is never presented as Gemini. Off uses only the on-device checker.", style = MaterialTheme.typography.bodySmall)
        AiMode.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.aiMode == mode, onClick = { onChange(settings.copy(aiMode = mode)) })
                Text(mode.name.lowercase())
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Backend URL") },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text("Emulator: http://10.0.2.2:8787. Tablet: http://YOUR-PC-LAN-IP:8787", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onChange(settings.copy(backendUrl = url.trim())); onTest() }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Check backend")
        }
        if (status.isNotBlank()) {
            Text(status, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Text("InkProof ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 24.dp))
        Text("Handwriting stays on the device until you tap Check, Solve, or a cloud read.", style = MaterialTheme.typography.bodySmall)
    }
}
