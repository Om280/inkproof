package app.inkproof

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.inkproof.ai.HttpAnalysisTransport
import app.inkproof.core.model.AppSettings
import app.inkproof.core.model.SortOrder
import app.inkproof.core.model.ThemeMode
import app.inkproof.core.persist.DocumentRepository
import app.inkproof.editor.EditorController
import app.inkproof.editor.EditorHostView
import app.inkproof.imports.AnnotatedPdfExporter
import app.inkproof.imports.FileImporter
import app.inkproof.imports.ImportOutcome
import app.inkproof.recognize.MlKitInk
import app.inkproof.ui.InkProofTheme
import app.inkproof.ui.LibraryScreen
import app.inkproof.ui.SettingsScreen
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var repo: DocumentRepository
    private lateinit var editor: EditorController
    private lateinit var importer: FileImporter
    private lateinit var host: EditorHostView
    private lateinit var libraryView: ComposeView
    private var editing = false
    private var settingsOpen by mutableStateOf(false)
    private var section by mutableStateOf("all")
    private var folderId by mutableStateOf<String?>(null)
    private var query by mutableStateOf("")
    private var sort by mutableStateOf(SortOrder.UPDATED)
    private var message by mutableStateOf<String?>(null)
    private var backendStatus by mutableStateOf("")
    private var tick by mutableIntStateOf(0)
    private var importIntoEditor = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // One-shot read permission is enough for this import.
        }
        val outcome = importer.import(uri, importIntoEditor && editing)
        importIntoEditor = false
        when (outcome) {
            is ImportOutcome.OpenNotebook -> {
                if (editing) editor.flushNow()
                message = outcome.note
                showEditor(outcome.id)
                editor.showBanner(outcome.note)
            }
            is ImportOutcome.InsertText -> {
                val pageId = editor.currentPageId()
                if (pageId == null) {
                    message = "Open a notebook before placing imported text."
                } else {
                    editor.insertImportedText(pageId, outcome.text)
                    editor.showBanner(outcome.note)
                }
            }
            is ImportOutcome.InsertImage -> {
                editor.placeImage(outcome.assetId, outcome.width, outcome.height)
                editor.showBanner(outcome.note)
            }
            is ImportOutcome.Message -> {
                message = outcome.text
                if (editing) editor.showBanner(outcome.text)
            }
        }
        tick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DocumentRepository(File(filesDir, "inkproof"))
        importer = FileImporter(this, repo)
        editor = ViewModelProvider(this, editorFactory())[EditorController::class.java]
        val root = FrameLayout(this)
        host = EditorHostView(this)
        libraryView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        root.addView(host, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(libraryView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        host.controller = editor
        host.onBack = { showLibrary() }
        host.onImport = {
            importIntoEditor = true
            openPicker()
        }
        host.onExport = { exportNotebook(editor.session?.meta?.id) }
        libraryView.setContent { libraryContent() }
        val restore = savedInstanceState?.getString(KEY_NOTEBOOK)
        if (restore != null && repo.loadLibrary().notebooks.any { it.id == restore }) {
            showEditor(restore)
        } else {
            showLibrary()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        editor.session?.meta?.id?.let { outState.putString(KEY_NOTEBOOK, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (editing) editor.flushNow()
        super.onStop()
    }

    private fun editorFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val ml = MlKitInk(this@MainActivity)
            return EditorController(
                repo,
                HttpAnalysisTransport { repo.loadSettings() },
                recognizer = { strokes -> ml.recognize(strokes) },
            ) as T
        }
    }

    private fun showLibrary() {
        if (editing) {
            host.finishActive()
            editor.flushNow()
        }
        editing = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host.visibility = android.view.View.GONE
        libraryView.visibility = android.view.View.VISIBLE
        tick++
    }

    private fun showEditor(id: String) {
        editor.open(id)
        host.theme = repo.loadSettings().theme
        host.bind()
        editing = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        libraryView.visibility = android.view.View.GONE
        host.visibility = android.view.View.VISIBLE
    }

    @androidx.compose.runtime.Composable
    private fun libraryContent() {
        val revision = tick
        val library = repo.loadLibrary()
        val settings = repo.loadSettings()
        InkProofTheme(settings.theme) {
            if (settingsOpen) {
                SettingsScreen(
                    settings = settings,
                    status = backendStatus,
                    onChange = { next ->
                        repo.saveSettings(next)
                        host.theme = next.theme
                        tick++
                    },
                    onTest = { testBackend() },
                    onBack = { settingsOpen = false },
                )
            } else {
                LibraryScreen(
                    notebooks = library.notebooks,
                    folders = library.folders,
                    folderId = folderId,
                    section = section,
                    query = query,
                    sort = sort,
                    message = message,
                    thumb = { pageId -> repo.thumbnailFile(pageId) },
                    onQuery = { query = it },
                    onSort = { sort = it },
                    onSection = { section = it },
                    onOpenFolder = { folderId = it },
                    onOpen = { showEditor(it.id) },
                    onCreate = {
                        val created = repo.createNotebook("Untitled notebook", folderId)
                        message = null
                        showEditor(created.id)
                    },
                    onCreateFolder = { name ->
                        repo.createFolder(name)
                        tick++
                    },
                    onRename = { notebook, name -> repo.renameNotebook(notebook.id, name); tick++ },
                    onDelete = { notebook -> repo.deleteNotebook(notebook.id); tick++ },
                    onDuplicate = { notebook ->
                        repo.duplicateNotebook(notebook.id)
                        message = "Duplicated ${notebook.name}."
                        tick++
                    },
                    onFavorite = { notebook -> repo.setFavorite(notebook.id, !notebook.favorite); tick++ },
                    onMove = { notebook, target -> repo.moveNotebook(notebook.id, target); tick++ },
                    onImport = {
                        importIntoEditor = false
                        openPicker()
                    },
                    onExport = { exportNotebook(it.id) },
                    onSettings = { settingsOpen = true },
                    onDismissMessage = { message = null },
                )
            }
        }
        if (revision < 0) return
    }

    private fun openPicker() {
        picker.launch(
            arrayOf(
                "application/pdf",
                "image/*",
                "text/*",
                "application/rtf",
                "text/csv",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "image/svg+xml",
                "application/msword",
                "application/vnd.ms-excel",
                "application/vnd.ms-powerpoint",
                "*/*",
            ),
        )
    }

    private fun exportNotebook(id: String?) {
        if (id == null) {
            message = "Open a notebook before exporting."
            return
        }
        if (editing && editor.session?.meta?.id == id) editor.flushNow()
        try {
            val file = File(cacheDir, "export/$id.pdf")
            AnnotatedPdfExporter(repo).export(id, file)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Export annotated PDF"))
        } catch (e: Exception) {
            val text = "Export failed (${e.javaClass.simpleName})."
            message = text
            if (editing) editor.showBanner(text)
        }
    }

    private fun testBackend() {
        val settings: AppSettings = repo.loadSettings()
        backendStatus = "Checking ${settings.backendUrl}…"
        Thread {
            val health = HttpAnalysisTransport { settings }.health()
            val gemini = HttpAnalysisTransport { settings }.geminiHealth()
            val text = when {
                health.failure != null -> "Backend unreachable (${health.failure}). Start it with node server.js from the backend folder."
                health.httpStatus !in 200..299 -> "Backend returned HTTP ${health.httpStatus}."
                else -> {
                    val configured = health.body.contains("\"configured\":true")
                    val model = Regex("\"model\":\"([^\"]+)\"").find(health.body)?.groupValues?.get(1) ?: "unknown"
                    val live = if (gemini.httpStatus in 200..299) "Gemini health check succeeded." else "Gemini health check did not succeed (${gemini.failure ?: gemini.httpStatus})."
                    "Backend is up. Model $model. Key configured: $configured. $live Mock responses are labeled and are not Gemini."
                }
            }
            runOnUiThread { backendStatus = text }
        }.start()
    }

    companion object {
        private const val KEY_NOTEBOOK = "notebook"
    }
}
