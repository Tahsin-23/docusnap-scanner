package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.DocumentEntity
import com.example.data.FolderEntity
import com.example.model.SortOrder
import com.example.ui.LibraryUiState
import com.example.ui.components.ConfirmDestructiveDialog
import com.example.ui.components.DocumentListCard
import com.example.ui.components.MoveToFolderDialog
import com.example.ui.components.TextInputDialog
import com.example.ui.theme.ScannerCyan

@Composable
fun LibraryScreen(
    libraryState: LibraryUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectFolder: (Long?) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (FolderEntity, String) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onRenameDocument: (Long, String) -> Unit,
    onMoveDocument: (Long, Long?) -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onScanDocumentClick: () -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackToHome)

    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }

    var docToRename by remember { mutableStateOf<DocumentEntity?>(null) }
    var docToMove by remember { mutableStateOf<DocumentEntity?>(null) }
    var docToDelete by remember { mutableStateOf<DocumentEntity?>(null) }

    val folderMap = remember(libraryState.folders) {
        libraryState.folders.associateBy { it.id }
    }
    val activeFolder = libraryState.selectedFolderId?.let { folderMap[it] }
    val ambientPrimary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ambientPrimary.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.18f, 36f),
                        radius = size.width * 0.70f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ScannerCyan.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.88f, 140f),
                        radius = size.width * 0.55f
                    )
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .testTag("library_screen_list"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Document Library",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${libraryState.filteredDocuments.size} of ${libraryState.documents.size} saved locally",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box {
                        OutlinedButton(
                            onClick = { sortMenuExpanded = true },
                            shape = CircleShape,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("library_sort_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(libraryState.sortOrder.label)
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label) },
                                    onClick = {
                                        sortMenuExpanded = false
                                        onSortOrderChange(order)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Capsule Search Field
            item {
                OutlinedTextField(
                    value = libraryState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search filename, notes, or recognized OCR text…") },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    trailingIcon = {
                        if (libraryState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_search_input")
                )
            }

            // Folder Filter + Create/Rename/Delete Folder Controls
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = libraryState.selectedFolderId == null,
                                onClick = { onSelectFolder(null) },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                label = { Text("All Documents") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                        items(libraryState.folders, key = { it.id }) { folder ->
                            FilterChip(
                                selected = libraryState.selectedFolderId == folder.id,
                                onClick = { onSelectFolder(folder.id) },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                leadingIcon = {
                                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(folder.name) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = { showCreateFolderDialog = true },
                                shape = CircleShape,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("library_new_folder_button")
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Folder")
                            }
                        }
                    }

                    if (activeFolder != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Folder: ${activeFolder.name}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { folderToRename = activeFolder }) {
                                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Rename")
                                    }
                                    TextButton(onClick = { folderToDelete = activeFolder }) {
                                        Icon(
                                            Icons.Filled.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (libraryState.filteredDocuments.isEmpty()) {
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (libraryState.documents.isEmpty()) {
                                    "Your local library is empty"
                                } else {
                                    "No documents match your current filter"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Capture sharp scans with automatic perspective correction and store them privately on your phone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onScanDocumentClick,
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan a Document")
                            }
                        }
                    }
                }
            } else {
                items(libraryState.filteredDocuments, key = { it.id }) { doc ->
                    DocumentListCard(
                        document = doc,
                        folderName = doc.folderId?.let { folderMap[it]?.name },
                        onOpen = { onOpenDocument(doc.id) },
                        onRenameRequest = { docToRename = doc },
                        onMoveFolderRequest = { docToMove = doc },
                        onDeleteRequest = { docToDelete = doc }
                    )
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        TextInputDialog(
            title = "New Folder",
            initialValue = "",
            label = "Folder name",
            confirmText = "Create",
            onConfirm = onCreateFolder,
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    folderToRename?.let { folder ->
        TextInputDialog(
            title = "Rename Folder",
            initialValue = folder.name,
            label = "Folder name",
            confirmText = "Save",
            onConfirm = { newName -> onRenameFolder(folder, newName) },
            onDismiss = { folderToRename = null }
        )
    }

    folderToDelete?.let { folder ->
        ConfirmDestructiveDialog(
            title = "Delete Folder \"${folder.name}\"?",
            message = "Documents inside this folder will NOT be deleted — they will remain accessible in All Documents.",
            confirmLabel = "Delete Folder",
            onConfirm = { onDeleteFolder(folder.id) },
            onDismiss = { folderToDelete = null }
        )
    }

    docToRename?.let { doc ->
        TextInputDialog(
            title = "Rename Document",
            initialValue = doc.title,
            label = "Document title",
            confirmText = "Save",
            onConfirm = { newTitle -> onRenameDocument(doc.id, newTitle) },
            onDismiss = { docToRename = null }
        )
    }

    docToMove?.let { doc ->
        MoveToFolderDialog(
            folders = libraryState.folders,
            currentFolderId = doc.folderId,
            onSelectFolder = { folderId -> onMoveDocument(doc.id, folderId) },
            onCreateNewFolder = onCreateFolder,
            onDismiss = { docToMove = null }
        )
    }

    docToDelete?.let { doc ->
        ConfirmDestructiveDialog(
            title = "Delete \"${doc.title}\"?",
            message = "This permanently deletes all ${doc.pageCount} page(s) from your device.",
            onConfirm = { onDeleteDocument(doc.id) },
            onDismiss = { docToDelete = null }
        )
    }
}
