package app.inkproof.ui

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.inkproof.core.model.Folder
import app.inkproof.core.model.NotebookMeta
import app.inkproof.core.model.SortOrder
import app.inkproof.core.model.filterNotebooks
import app.inkproof.core.model.sortNotebooks
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(
    notebooks: List<NotebookMeta>,
    folders: List<Folder>,
    folderId: String?,
    section: String,
    query: String,
    sort: SortOrder,
    message: String?,
    thumb: (String) -> File,
    onQuery: (String) -> Unit,
    onSort: (SortOrder) -> Unit,
    onSection: (String) -> Unit,
    onOpenFolder: (String?) -> Unit,
    onOpen: (NotebookMeta) -> Unit,
    onCreate: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (NotebookMeta, String) -> Unit,
    onDelete: (NotebookMeta) -> Unit,
    onDuplicate: (NotebookMeta) -> Unit,
    onFavorite: (NotebookMeta) -> Unit,
    onMove: (NotebookMeta, String?) -> Unit,
    onImport: () -> Unit,
    onExport: (NotebookMeta) -> Unit,
    onSettings: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var folderName by remember { mutableStateOf("") }
    var askFolder by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.width(280.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(24.dp),
        ) {
            Text("InkProof", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp)
            Text("Prove your work.", color = Mist, modifier = Modifier.padding(bottom = 28.dp))
            NavItem("All notebooks", section == "all") { onSection("all"); onOpenFolder(null) }
            NavItem("Recent", section == "recent") { onSection("recent") }
            NavItem("Favorites", section == "favorites") { onSection("favorites") }
            Text("FOLDERS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            folders.filter { it.parentId == null }.forEach { folder ->
                NavItem(folder.name, folderId == folder.id) { onOpenFolder(folder.id); onSection("folder") }
            }
            TextButton(onClick = { askFolder = true }) { Text("New folder") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onImport) { Text("Import") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Column(Modifier.weight(1f).padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = when (section) {
                    "recent" -> "Recent"
                    "favorites" -> "Favorites"
                    "folder" -> folders.firstOrNull { it.id == folderId }?.name ?: "Folder"
                    else -> "Notebooks"
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = onCreate) { Text("New notebook") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                placeholder = { Text("Search notebooks") },
                singleLine = true,
            )
            Row(Modifier.padding(vertical = 8.dp)) {
                SortOrder.entries.forEach { order ->
                    TextButton(onClick = { onSort(order) }) {
                        Text(order.name.lowercase().replace('_', ' '), color = if (order == sort) Proof else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            message?.let {
                Text(it, color = Proof, modifier = Modifier.clickable { onDismissMessage() }.padding(bottom = 8.dp))
            }
            val visible = visibleNotebooks(notebooks, section, folderId, query, sort)
            if (visible.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text("Nothing here yet.", fontFamily = FontFamily.Serif, fontSize = 28.sp)
                    Text("A new notebook is blank. No sample problems are added.", color = Mist, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(190.dp),
                    contentPadding = PaddingValues(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(visible, key = { it.id }) { notebook ->
                        NotebookCard(
                            notebook = notebook,
                            thumb = thumb(notebook.coverPageId ?: notebook.pageOrder.firstOrNull().orEmpty()),
                            folders = folders,
                            onOpen = { onOpen(notebook) },
                            onRename = { onRename(notebook, it) },
                            onDelete = { onDelete(notebook) },
                            onDuplicate = { onDuplicate(notebook) },
                            onFavorite = { onFavorite(notebook) },
                            onMove = { onMove(notebook, it) },
                            onExport = { onExport(notebook) },
                        )
                    }
                }
            }
        }
    }
    if (askFolder) {
        AlertDialog(
            onDismissRequest = { askFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("Name") }) },
            confirmButton = {
                Button(onClick = {
                    onCreateFolder(folderName.ifBlank { "Folder" })
                    folderName = ""
                    askFolder = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { askFolder = false }) { Text("Cancel") } },
        )
    }
}

private fun visibleNotebooks(
    notebooks: List<NotebookMeta>,
    section: String,
    folderId: String?,
    query: String,
    sort: SortOrder,
): List<NotebookMeta> {
    val base = when (section) {
        "recent" -> notebooks.sortedByDescending { it.updatedAt }.take(12)
        "favorites" -> notebooks.filter { it.favorite }
        "folder" -> notebooks.filter { it.folderId == folderId }
        else -> if (folderId == null) notebooks else notebooks.filter { it.folderId == folderId }
    }
    return sortNotebooks(filterNotebooks(base, query), sort)
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun NotebookCard(
    notebook: NotebookMeta,
    thumb: File,
    folders: List<Folder>,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onFavorite: () -> Unit,
    onMove: (String?) -> Unit,
    onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember(notebook.name) { mutableStateOf(notebook.name) }
    var bitmap by remember(thumb.path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(thumb.path, thumb.lastModified()) {
        bitmap = if (thumb.exists()) BitmapFactory.decodeFile(thumb.path)?.asImageBitmap() else null
    }
    Surface(
        modifier = Modifier.height(230.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(140.dp).background(Color(0xFFFBF6EE)).border(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("Blank", color = Mist, modifier = Modifier.align(Alignment.Center), fontFamily = FontFamily.Serif)
                }
            }
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(notebook.name, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(
                        "${notebook.pageOrder.size} pages · ${DateFormat.getDateInstance().format(Date(notebook.updatedAt))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("···") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
                        DropdownMenuItem(text = { Text(if (notebook.favorite) "Unfavorite" else "Favorite") }, onClick = { menu = false; onFavorite() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Export PDF") }, onClick = { menu = false; onExport() })
                        folders.forEach { folder ->
                            DropdownMenuItem(text = { Text("Move to ${folder.name}") }, onClick = { menu = false; onMove(folder.id) })
                        }
                        DropdownMenuItem(text = { Text("Move to root") }, onClick = { menu = false; onMove(null) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
    }
    if (rename) {
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }) },
            confirmButton = { Button(onClick = { onRename(name); rename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${notebook.name}?") },
            text = { Text("Pages in this notebook are removed. This does not affect other notebooks.") },
            confirmButton = { Button(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
