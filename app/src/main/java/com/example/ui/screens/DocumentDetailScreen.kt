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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.DocumentWithPages
import com.example.data.FolderEntity
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.PdfOrientation
import com.example.model.PdfPageSize
import com.example.ui.ExportUiState
import com.example.ui.components.BadgePill
import com.example.ui.components.ConfirmDestructiveDialog
import com.example.ui.components.ExportPanel
import com.example.ui.components.MoveToFolderDialog
import com.example.ui.components.TextInputDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentDetailScreen(
    docWithPages: DocumentWithPages?,
    folders: List<FolderEntity>,
    exportState: ExportUiState,
    onPrepareExport: (DocumentWithPages) -> Unit,
    onUpdateExportConfig: (String, ExportFormat, Set<Int>, PdfPageSize, PdfOrientation, ExportQualityPreset, Int, List<String>) -> Unit,
    onDownloadExport: (List<String>) -> Unit,
    onShareExport: (List<String>) -> Unit,
    onContinueEditing: (Long) -> Unit,
    onRenameDocument: (Long, String) -> Unit,
    onUpdateNotes: (Long, String) -> Unit,
    onMoveToFolder: (Long, Long?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCopyOcrText: (String) -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var showRenameDialog by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(docWithPages?.document?.id, docWithPages?.document?.updatedAt) {
        if (docWithPages != null) {
            onPrepareExport(docWithPages)
        }
    }

    if (docWithPages == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val document = docWithPages.document
    val pages = docWithPages.orderedPages
    val allPaths = remember(pages) { pages.map { it.processedImagePath } }
    val folderName = remember(document.folderId, folders) {
        folders.firstOrNull { it.id == document.folderId }?.name
    }
    val dateFormatted = remember(document.updatedAt) {
        SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.US).format(Date(document.updatedAt))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("document_detail_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("detail_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Library")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename document")
                }
                IconButton(onClick = { showMoveDialog = true }) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move to folder")
                }
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.testTag("detail_delete_button")
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Delete document",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Summary + Continue Editing CTA
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BadgePill(
                                text = "${pages.size} ${if (pages.size == 1) "Page" else "Pages"}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (folderName != null) {
                                BadgePill(
                                    text = folderName,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        if (document.notes.isNotBlank()) {
                            Text(
                                text = document.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onContinueEditing(document.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("detail_continue_editing_button")
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Continue Editing")
                            }
                            OutlinedButton(
                                onClick = { showNotesDialog = true }
                            ) {
                                Text(if (document.notes.isBlank()) "Add Notes" else "Edit Notes")
                            }
                        }
                    }
                }
            }

            // Scanned Pages Preview List
            itemsIndexed(pages, key = { _, p -> p.id }) { idx, page ->
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Page ${idx + 1} of ${pages.size}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = page.filterType,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(290.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0F172A)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = File(page.processedImagePath),
                                contentDescription = "Saved page ${idx + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }

                        if (page.ocrText.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = page.ocrText,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(onClick = { onCopyOcrText(page.ocrText) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy page OCR text")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Export & Share Panel for Saved Document
            item {
                ExportPanel(
                    exportState = exportState,
                    totalPageCount = pages.size,
                    showSaveToLibraryButton = false,
                    onConfigChange = { name, fmt, sel, ps, orient, preset, q ->
                        onUpdateExportConfig(name, fmt, sel, ps, orient, preset, q, allPaths)
                    },
                    onDownloadClick = { onDownloadExport(allPaths) },
                    onShareClick = { onShareExport(allPaths) }
                )
            }
        }
    }

    if (showRenameDialog) {
        TextInputDialog(
            title = "Rename Document",
            initialValue = document.title,
            label = "Document title",
            confirmText = "Save",
            onConfirm = { onRenameDocument(document.id, it) },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showNotesDialog) {
        TextInputDialog(
            title = "Document Notes",
            initialValue = document.notes,
            label = "Notes",
            confirmText = "Save",
            onConfirm = { onUpdateNotes(document.id, it) },
            onDismiss = { showNotesDialog = false }
        )
    }

    if (showMoveDialog) {
        MoveToFolderDialog(
            folders = folders,
            currentFolderId = document.folderId,
            onSelectFolder = { onMoveToFolder(document.id, it) },
            onCreateNewFolder = onCreateFolder,
            onDismiss = { showMoveDialog = false }
        )
    }

    if (showDeleteDialog) {
        ConfirmDestructiveDialog(
            title = "Delete \"${document.title}\"?",
            message = "This permanently removes this document and all its pages from your device.",
            onConfirm = { onDeleteDocument(document.id) },
            onDismiss = { showDeleteDialog = false }
        )
    }
}
