package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.FolderEntity
import com.example.model.EditablePage
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.FilterAdjustments
import com.example.model.FilterType
import com.example.model.NormalizedQuad
import com.example.model.OcrLanguage
import com.example.model.PdfOrientation
import com.example.model.PdfPageSize
import com.example.ui.EditorSessionState
import com.example.ui.EditorTab
import com.example.ui.ExportUiState
import com.example.ui.OcrUiState
import com.example.ui.components.ConfirmDestructiveDialog
import com.example.ui.components.CropPerspectiveEditor
import com.example.ui.components.ExportPanel
import com.example.ui.components.OcrPanel
import java.io.File

@Composable
fun EditorScreen(
    editorState: EditorSessionState,
    exportState: ExportUiState,
    ocrState: OcrUiState,
    folders: List<FolderEntity>,
    onSelectTab: (EditorTab) -> Unit,
    onSelectPage: (Int) -> Unit,
    onToggleBeforeAfter: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onFolderChange: (Long?) -> Unit,
    onAddPageFromCamera: () -> Unit,
    onAddPagesFromGallery: (List<Uri>) -> Unit,
    onApplyCropAndRotation: (NormalizedQuad, Float) -> Unit,
    onAutoDetectCrop: ((NormalizedQuad, Boolean) -> Unit) -> Unit,
    onRotate90: (Boolean) -> Unit,
    onSelectFilter: (FilterType) -> Unit,
    onUpdateAdjustments: (FilterAdjustments) -> Unit,
    onResetAdjustments: () -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onDuplicatePage: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveDocument: () -> Unit,
    onUpdateExportConfig: (String, ExportFormat, Set<Int>, PdfPageSize, PdfOrientation, ExportQualityPreset, Int) -> Unit,
    onDownloadExport: () -> Unit,
    onShareExport: () -> Unit,
    onSelectOcrLanguage: (OcrLanguage) -> Unit,
    onDownloadOcrPack: () -> Unit,
    onExtractOcrPage: () -> Unit,
    onExtractOcrAll: () -> Unit,
    onEditOcrText: (String) -> Unit,
    onCopyOcrText: (String) -> Unit,
    onSaveOcrTxt: (String) -> Unit,
    onShareOcrTxt: (String) -> Unit,
    onDiscardAndExit: () -> Unit
) {
    var showUnsavedExitDialog by remember { mutableStateOf(false) }
    var pageIndexPendingDelete by remember { mutableIntStateOf(-1) }

    val galleryAppendLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddPagesFromGallery(uris)
        }
    }

    val requestExit = {
        if (editorState.hasUnsavedChanges && editorState.pages.isNotEmpty()) {
            showUnsavedExitDialog = true
        } else {
            onDiscardAndExit()
        }
    }

    BackHandler(onBack = requestExit)

    val currentPage = editorState.currentPage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("editor_screen_root"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Action Bar
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
                    onClick = requestExit,
                    modifier = Modifier.testTag("editor_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = editorState.title.ifBlank { "Untitled Scan" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${editorState.pages.size} ${if (editorState.pages.size == 1) "page" else "pages"} • ${editorState.scanMode.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onUndo,
                    enabled = editorState.canUndo,
                    modifier = Modifier.testTag("editor_undo_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }

                IconButton(
                    onClick = onRedo,
                    enabled = editorState.canRedo,
                    modifier = Modifier.testTag("editor_redo_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }

                IconButton(
                    onClick = { onToggleBeforeAfter(!editorState.showBeforeOriginal) },
                    modifier = Modifier.testTag("editor_compare_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Compare,
                        contentDescription = stringResource(R.string.compare_before_after),
                        tint = if (editorState.showBeforeOriginal) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                Button(
                    onClick = onSaveDocument,
                    enabled = editorState.pages.isNotEmpty() && !editorState.isProcessing,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("editor_save_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Save")
                }
            }
        }

        // Scrollable Workspace Body
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 860.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (currentPage != null && editorState.activeTab != EditorTab.CROP) {
                // Live Main Page Stage
                item {
                    MainPagePreviewCard(
                        page = currentPage,
                        pageNumber = editorState.selectedPageIndex + 1,
                        totalPages = editorState.pages.size,
                        showOriginal = editorState.showBeforeOriginal,
                        isProcessing = editorState.isProcessing,
                        processingMessage = editorState.processingMessage,
                        onRotateRight = { onRotate90(true) },
                        onDuplicate = { onDuplicatePage(editorState.selectedPageIndex) },
                        onDelete = { pageIndexPendingDelete = editorState.selectedPageIndex }
                    )
                }
            }

            // Multi-Page Thumbnail Strip & Add Pages Bar
            if (editorState.pages.isNotEmpty()) {
                item {
                    MultiPageThumbnailBar(
                        pages = editorState.pages,
                        selectedIndex = editorState.selectedPageIndex,
                        onSelectPage = onSelectPage,
                        onMoveLeft = { idx -> if (idx > 0) onMovePage(idx, idx - 1) },
                        onMoveRight = { idx -> if (idx < editorState.pages.lastIndex) onMovePage(idx, idx + 1) },
                        onAddCamera = onAddPageFromCamera,
                        onAddGallery = {
                            galleryAppendLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }

            // Editor Tool Tabs
            item {
                ScrollableTabRow(
                    selectedTabIndex = EditorTab.entries.indexOf(editorState.activeTab),
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    EditorTab.entries.forEach { tab ->
                        Tab(
                            selected = editorState.activeTab == tab,
                            onClick = { onSelectTab(tab) },
                            text = { Text(tab.label) },
                            modifier = Modifier.testTag("editor_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }

            // Active Tab Tool Content
            item {
                if (currentPage == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (editorState.isProcessing) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(editorState.processingMessage)
                            }
                        } else {
                            Text("No pages in session.")
                        }
                    }
                } else {
                    when (editorState.activeTab) {
                        EditorTab.FILTERS -> {
                            FiltersAndAdjustmentsDeck(
                                page = currentPage,
                                onSelectFilter = onSelectFilter,
                                onUpdateAdjustments = onUpdateAdjustments,
                                onResetAdjustments = onResetAdjustments
                            )
                        }
                        EditorTab.CROP -> {
                            CropPerspectiveEditor(
                                page = currentPage,
                                onAutoDetectRequest = onAutoDetectCrop,
                                onConfirmCrop = onApplyCropAndRotation,
                                onCancelCrop = { onSelectTab(EditorTab.FILTERS) }
                            )
                        }
                        EditorTab.PAGES -> {
                            PagesAndMetadataPanel(
                                title = editorState.title,
                                notes = editorState.notes,
                                folderId = editorState.folderId,
                                folders = folders,
                                pages = editorState.pages,
                                onTitleChange = onTitleChange,
                                onNotesChange = onNotesChange,
                                onFolderChange = onFolderChange,
                                onMovePage = onMovePage,
                                onDuplicatePage = onDuplicatePage,
                                onDeletePage = { idx -> pageIndexPendingDelete = idx }
                            )
                        }
                        EditorTab.OCR -> {
                            OcrPanel(
                                ocrState = ocrState,
                                totalPageCount = editorState.pages.size,
                                onSelectLanguage = onSelectOcrLanguage,
                                onDownloadLanguageData = onDownloadOcrPack,
                                onExtractCurrentPage = onExtractOcrPage,
                                onExtractAllPages = onExtractOcrAll,
                                onEditRecognizedText = onEditOcrText,
                                onCopyText = onCopyOcrText,
                                onSaveTxtFile = onSaveOcrTxt,
                                onShareTxtFile = onShareOcrTxt
                            )
                        }
                        EditorTab.EXPORT -> {
                            ExportPanel(
                                exportState = exportState,
                                totalPageCount = editorState.pages.size,
                                showSaveToLibraryButton = true,
                                onConfigChange = onUpdateExportConfig,
                                onDownloadClick = onDownloadExport,
                                onShareClick = onShareExport,
                                onSaveToLibraryClick = onSaveDocument
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUnsavedExitDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedExitDialog = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedExitDialog = false
                        onDiscardAndExit()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("confirm_discard_button")
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedExitDialog = false }) {
                    Text(stringResource(R.string.action_keep_editing))
                }
            }
        )
    }

    if (pageIndexPendingDelete >= 0) {
        val idx = pageIndexPendingDelete
        ConfirmDestructiveDialog(
            title = "Delete Page ${idx + 1}?",
            message = "This removes page ${idx + 1} from the current document.",
            onConfirm = { onDeletePage(idx) },
            onDismiss = { pageIndexPendingDelete = -1 }
        )
    }
}

@Composable
private fun MainPagePreviewCard(
    page: EditablePage,
    pageNumber: Int,
    totalPages: Int,
    showOriginal: Boolean,
    isProcessing: Boolean,
    processingMessage: String,
    onRotateRight: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp),
            contentAlignment = Alignment.Center
        ) {
            val pathToShow = if (showOriginal) page.originalImagePath else page.processedImagePath
            AsyncImage(
                model = File(pathToShow),
                contentDescription = "Preview of page $pageNumber",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentScale = ContentScale.Fit
            )

            // Top-left Before/After & Filter indicator pill
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = if (showOriginal) "BEFORE (Original Capture)" else "Page $pageNumber/$totalPages • ${page.filterType.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            // Quick Page Actions floating bar
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onRotateRight,
                    modifier = Modifier.testTag("quick_rotate_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90°", tint = Color.White)
                }
                IconButton(
                    onClick = onDuplicate,
                    modifier = Modifier.testTag("quick_duplicate_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate page", tint = Color.White)
                }
                if (totalPages > 1) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("quick_delete_page_button")
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete page", tint = Color(0xFFFCA5A5))
                    }
                }
            }

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.52f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        if (processingMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(processingMessage, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiPageThumbnailBar(
    pages: List<EditablePage>,
    selectedIndex: Int,
    onSelectPage: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
    onAddCamera: () -> Unit,
    onAddGallery: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { idx, p ->
                val isSelected = idx == selectedIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.testTag("page_thumb_$idx")
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 62.dp, height = 78.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectPage(idx) }
                    ) {
                        AsyncImage(
                            model = File(p.thumbnailPath),
                            contentDescription = "Page ${idx + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.65f),
                            shape = CircleShape,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(20.dp)
                                .align(Alignment.TopStart)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (isSelected && pages.size > 1) {
                        Row {
                            IconButton(
                                onClick = { onMoveLeft(idx) },
                                enabled = idx > 0,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move page left", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { onMoveRight(idx) },
                                enabled = idx < pages.lastIndex,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move page right", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Add More Pages Buttons
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAddCamera,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("editor_add_camera_page_button")
                    ) {
                        Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+ Scan")
                    }
                    OutlinedButton(
                        onClick = onAddGallery,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("editor_add_gallery_page_button")
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+ Import")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersAndAdjustmentsDeck(
    page: EditablePage,
    onSelectFilter: (FilterType) -> Unit,
    onUpdateAdjustments: (FilterAdjustments) -> Unit,
    onResetAdjustments: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Document & Color Filters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onResetAdjustments,
                    modifier = Modifier.testTag("reset_adjustments_button")
                ) {
                    Text("Reset Sliders")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterType.entries.forEach { filter ->
                    FilterChip(
                        selected = page.filterType == filter,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
                    )
                }
            }

            val adj = page.adjustments

            if (page.filterType == FilterType.BLACK_AND_WHITE) {
                AdjustmentSliderRow(
                    label = "B&W Threshold",
                    valueText = "${adj.threshold.toInt()}%",
                    value = adj.threshold,
                    valueRange = 0f..100f,
                    testTag = "slider_threshold",
                    onValueChange = { onUpdateAdjustments(adj.copy(threshold = it)) }
                )
            }

            AdjustmentSliderRow(
                label = "Brightness",
                valueText = String.format(java.util.Locale.US, "%+d", adj.brightness.toInt()),
                value = adj.brightness,
                valueRange = -100f..100f,
                testTag = "slider_brightness",
                onValueChange = { onUpdateAdjustments(adj.copy(brightness = it)) }
            )

            AdjustmentSliderRow(
                label = "Contrast",
                valueText = String.format(java.util.Locale.US, "%+d", adj.contrast.toInt()),
                value = adj.contrast,
                valueRange = -100f..100f,
                testTag = "slider_contrast",
                onValueChange = { onUpdateAdjustments(adj.copy(contrast = it)) }
            )

            AdjustmentSliderRow(
                label = "Sharpness",
                valueText = "${adj.sharpness.toInt()}%",
                value = adj.sharpness,
                valueRange = 0f..100f,
                testTag = "slider_sharpness",
                onValueChange = { onUpdateAdjustments(adj.copy(sharpness = it)) }
            )
        }
    }
}

@Composable
private fun AdjustmentSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagesAndMetadataPanel(
    title: String,
    notes: String,
    folderId: Long?,
    folders: List<FolderEntity>,
    pages: List<EditablePage>,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onFolderChange: (Long?) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onDuplicatePage: (Int) -> Unit,
    onDeletePage: (Int) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Document Details & Page Order",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Document Title") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_title_input")
            )

            OutlinedTextField(
                value = notes,
                onValueChange = onNotesChange,
                label = { Text("Document Notes (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_notes_input")
            )

            if (folders.isNotEmpty()) {
                Text("Folder / Category", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = folderId == null,
                        onClick = { onFolderChange(null) },
                        label = { Text("None") }
                    )
                    folders.forEach { folder ->
                        FilterChip(
                            selected = folderId == folder.id,
                            onClick = { onFolderChange(folder.id) },
                            label = { Text(folder.name) }
                        )
                    }
                }
            }

            Text("Manage Pages (${pages.size})", style = MaterialTheme.typography.labelLarge)
            pages.forEachIndexed { idx, page ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = File(page.thumbnailPath),
                            contentDescription = "Page ${idx + 1}",
                            modifier = Modifier
                                .size(width = 40.dp, height = 52.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Page ${idx + 1}", style = MaterialTheme.typography.titleSmall)
                            Text(page.filterType.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onMovePage(idx, idx - 1) }, enabled = idx > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move earlier")
                        }
                        IconButton(onClick = { onMovePage(idx, idx + 1) }, enabled = idx < pages.lastIndex) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move later")
                        }
                        IconButton(onClick = { onDuplicatePage(idx) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                        }
                        if (pages.size > 1) {
                            IconButton(onClick = { onDeletePage(idx) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
