package com.inkproof.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkproof.app.data.db.NotebookEntity
import com.inkproof.app.ui.theme.InkNavy
import com.inkproof.app.ui.theme.MutedText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenNotebook: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val notebooks by viewModel.notebooks.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val navigateTo by viewModel.navigateTo.collectAsState()

    var showCreateMenu by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf<CreateKind?>(null) }
    var pendingPdfTitle by remember { mutableStateOf("") }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importPdf(uri, pendingPdfTitle)
    }

    LaunchedEffect(navigateTo) {
        navigateTo?.let {
            viewModel.consumeNavigation()
            onOpenNotebook(it)
        }
    }
    LaunchedEffect(notebooks) { viewModel.refreshThumbnails(notebooks) }

    val shown = searchResults ?: notebooks
    val favorites = shown.filter { it.favorite }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showCreateMenu = true },
                    containerColor = InkNavy,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create")
                }
                DropdownMenu(
                    expanded = showCreateMenu,
                    onDismissRequest = { showCreateMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("New note") },
                        leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                        onClick = { showCreateMenu = false; createDialog = CreateKind.NOTE }
                    )
                    DropdownMenuItem(
                        text = { Text("New math question") },
                        leadingIcon = { Icon(Icons.Outlined.Calculate, null) },
                        onClick = { showCreateMenu = false; createDialog = CreateKind.MATH }
                    )
                    DropdownMenuItem(
                        text = { Text("Import PDF") },
                        leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                        onClick = {
                            showCreateMenu = false
                            pendingPdfTitle = "Imported PDF"
                            pdfLauncher.launch(arrayOf("application/pdf"))
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("InkProof", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Prove your work.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = MutedText)
                }
            }

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.search(it) },
                placeholder = { Text("Search notebooks and questions") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MutedText) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )

            if (shown.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(190.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 96.dp, top = 6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (favorites.isNotEmpty() && searchResults == null) {
                        items(favorites, key = { "fav-${it.id}" }) { nb ->
                            NotebookCard(
                                notebook = nb,
                                thumbnail = thumbnails[nb.id],
                                onOpen = { onOpenNotebook(nb.id) },
                                onRename = { viewModel.rename(nb.id, it) },
                                onDelete = { viewModel.delete(nb.id) },
                                onDuplicate = { viewModel.duplicate(nb.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(nb) }
                            )
                        }
                    }
                    items(
                        shown.filter { !it.favorite || searchResults != null },
                        key = { it.id }
                    ) { nb ->
                        NotebookCard(
                            notebook = nb,
                            thumbnail = thumbnails[nb.id],
                            onOpen = { onOpenNotebook(nb.id) },
                            onRename = { viewModel.rename(nb.id, it) },
                            onDelete = { viewModel.delete(nb.id) },
                            onDuplicate = { viewModel.duplicate(nb.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(nb) }
                        )
                    }
                }
            }
        }
    }

    createDialog?.let { kind ->
        CreateNotebookDialog(
            kind = kind,
            onDismiss = { createDialog = null },
            onCreate = { title ->
                when (kind) {
                    CreateKind.NOTE -> viewModel.createNote(title)
                    CreateKind.MATH -> viewModel.createMathQuestion(title)
                }
                createDialog = null
            }
        )
    }
}

enum class CreateKind { NOTE, MATH }

@Composable
private fun CreateNotebookDialog(
    kind: CreateKind,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var title by remember {
        mutableStateOf(if (kind == CreateKind.MATH) "Math questions" else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (kind == CreateKind.MATH) "New math question" else "New note")
        },
        text = {
            Column {
                if (kind == CreateKind.MATH) {
                    Text(
                        "You solve it. InkProof checks it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun NotebookCard(
    notebook: NotebookEntity,
    thumbnail: android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MutedText,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(42.dp)
                    )
                }
                if (notebook.favorite) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(18.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        notebook.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                            .format(Date(notebook.updatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MutedText)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (notebook.favorite) "Unfavorite" else "Favorite") },
                            leadingIcon = {
                                Icon(
                                    if (notebook.favorite) Icons.Filled.Favorite
                                    else Icons.Outlined.FavoriteBorder,
                                    null
                                )
                            },
                            onClick = { menuOpen = false; onToggleFavorite() }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; renameOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { menuOpen = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; deleteConfirm = true }
                        )
                    }
                }
            }
        }
    }

    if (renameOpen) {
        var title by remember { mutableStateOf(notebook.title) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename notebook") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { onRename(title); renameOpen = false }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Delete notebook?") },
            text = { Text("\"${notebook.title}\" and all of its pages will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); deleteConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Calculate,
            contentDescription = null,
            tint = MutedText,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("No notebooks yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "You solve it. InkProof checks it.\nCreate a math question to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText,
            modifier = Modifier.width(320.dp)
        )
    }
}
