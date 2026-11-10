package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.DocumentEntity
import com.example.data.FolderEntity
import com.example.ui.LibraryUiState
import com.example.ui.components.ConfirmDestructiveDialog
import com.example.ui.components.CreatorAttributionBanner
import com.example.ui.components.DocuSnapLogoHeader
import com.example.ui.components.DocumentListCard
import com.example.ui.components.MoveToFolderDialog
import com.example.ui.components.TextInputDialog
import com.example.ui.theme.HeroRoyalBlue
import com.example.ui.theme.HeroSapphireDeep
import com.example.ui.theme.ScannerCyan

@Composable
fun HomeScreen(
    libraryState: LibraryUiState,
    onScanDocumentClick: () -> Unit,
    onTakePhotoClick: () -> Unit,
    onImportGalleryClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectFolder: (Long?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onRenameDocument: (Long, String) -> Unit,
    onMoveDocument: (Long, Long?) -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onViewAllLibraryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var docToRename by remember { mutableStateOf<DocumentEntity?>(null) }
    var docToMove by remember { mutableStateOf<DocumentEntity?>(null) }
    var docToDelete by remember { mutableStateOf<DocumentEntity?>(null) }

    val folderMap = remember(libraryState.folders) {
        libraryState.folders.associateBy { it.id }
    }

    val totalScannedPages = remember(libraryState.documents) {
        libraryState.documents.sumOf { it.pageCount }
    }

    val displayedDocs = remember(libraryState.filteredDocuments, libraryState.searchQuery) {
        if (libraryState.searchQuery.isNotBlank()) {
            libraryState.filteredDocuments
        } else {
            libraryState.filteredDocuments.take(8)
        }
    }

    val ambientPrimary = MaterialTheme.colorScheme.primary
    val ambientCyan = ScannerCyan

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Atmospheric top studio glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ambientPrimary.copy(alpha = 0.11f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.12f, 40f),
                        radius = size.width * 0.72f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ambientCyan.copy(alpha = 0.09f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.90f, 160f),
                        radius = size.width * 0.60f
                    )
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .testTag("home_screen_list"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Brand & Creator Header
            item {
                DocuSnapLogoHeader()
            }

            // 2. Capsule Search Bar
            item {
                OutlinedTextField(
                    value = libraryState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
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
                                contentDescription = "Search documents",
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
                        .testTag("home_search_input")
                )
            }

            // 3. Flagship Bento Scanner Deck (Scan Document Hero + Take Photo + Import Gallery)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroScanDocumentCard(onClick = onScanDocumentClick)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SecondaryActionTile(
                            title = stringResource(R.string.action_take_photo),
                            subtitle = "True natural color",
                            icon = Icons.Filled.CameraAlt,
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            testTag = "take_photo_button",
                            onClick = onTakePhotoClick,
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryActionTile(
                            title = stringResource(R.string.action_import_gallery),
                            subtitle = "Multi-page batch",
                            icon = Icons.Filled.PhotoLibrary,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            testTag = "import_gallery_button",
                            onClick = onImportGalleryClick,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 4. Compact Workspace Metrics Strip
            item {
                WorkspaceStatsStrip(
                    documentCount = libraryState.documents.size,
                    pageCount = totalScannedPages,
                    folderCount = libraryState.folders.size
                )
            }

            // 5. Document Folders / Categories Strip
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.section_folders),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        TextButton(
                            onClick = { showNewFolderDialog = true },
                            modifier = Modifier.testTag("create_folder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_new_folder))
                        }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = libraryState.selectedFolderId == null,
                                onClick = { onSelectFolder(null) },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                label = { Text(stringResource(R.string.folder_all)) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("folder_chip_all")
                            )
                        }
                        items(libraryState.folders, key = { it.id }) { folder ->
                            FolderChipItem(
                                folder = folder,
                                selected = libraryState.selectedFolderId == folder.id,
                                onClick = { onSelectFolder(folder.id) }
                            )
                        }
                    }
                }
            }

            // 6. Recent Documents Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (libraryState.searchQuery.isNotBlank()) {
                            "Search Results (${displayedDocs.size})"
                        } else {
                            stringResource(R.string.section_recent_documents)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (libraryState.documents.isNotEmpty()) {
                        TextButton(
                            onClick = onViewAllLibraryClick,
                            modifier = Modifier.testTag("view_all_library_button")
                        ) {
                            Text(stringResource(R.string.view_all))
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // 7. Documents List or Polished Empty State
            if (displayedDocs.isEmpty()) {
                item {
                    EmptyDocumentsCard(
                        isSearching = libraryState.searchQuery.isNotBlank(),
                        onScanClick = onScanDocumentClick,
                        onImportClick = onImportGalleryClick
                    )
                }
            } else {
                items(displayedDocs, key = { it.id }) { doc ->
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

            // 8. Signature Creator Attribution Card
            item {
                Spacer(modifier = Modifier.height(4.dp))
                CreatorAttributionBanner()
            }
        }
    }

    if (showNewFolderDialog) {
        TextInputDialog(
            title = "Create Folder",
            initialValue = "",
            label = "Folder name (e.g. Receipts, Contracts)",
            confirmText = "Create",
            onConfirm = onCreateFolder,
            onDismiss = { showNewFolderDialog = false }
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
            message = "This permanently deletes all ${doc.pageCount} scanned page(s) from your local library.",
            onConfirm = { onDeleteDocument(doc.id) },
            onDismiss = { docToDelete = null }
        )
    }
}

@Composable
private fun HeroScanDocumentCard(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_document_button")
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            HeroSapphireDeep,
                            HeroRoyalBlue,
                            ScannerCyan
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(900f, 520f)
                    )
                )
                .drawBehind {
                    // Ambient luminous orb & decorative scanner corner marks
                    drawCircle(
                        color = Color.White.copy(alpha = 0.08f),
                        radius = size.height * 0.85f,
                        center = Offset(size.width * 0.88f, size.height * 0.25f)
                    )
                    val markColor = Color.White.copy(alpha = 0.18f)
                    val pad = 16.dp.toPx()
                    val arm = 18.dp.toPx()
                    val stroke = 2.dp.toPx()
                    // Decorative reticle corners inside hero card
                    drawLine(markColor, Offset(size.width - pad, pad), Offset(size.width - pad - arm, pad), stroke, StrokeCap.Round)
                    drawLine(markColor, Offset(size.width - pad, pad), Offset(size.width - pad, pad + arm), stroke, StrokeCap.Round)
                    drawLine(markColor, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - arm, size.height - pad), stroke, StrokeCap.Round)
                    drawLine(markColor, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - arm), stroke, StrokeCap.Round)
                }
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Surface(
                            color = Color.White.copy(alpha = 0.16f),
                            shape = CircleShape
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoFixHigh,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "AUTO-EDGE & PERSPECTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = stringResource(R.string.action_scan_document),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.action_scan_document_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.90f)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    // Tactile Dual-Ring Shutter Emblem
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.DocumentScanner,
                                    contentDescription = "Scan Document",
                                    tint = HeroRoyalBlue,
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom capability pills
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroFeaturePill("Smart Crop")
                    HeroFeaturePill("7 Filters")
                    HeroFeaturePill("HD PDF & OCR")
                }
            }
        }
    }
}

@Composable
private fun HeroFeaturePill(label: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.20f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.95f),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SecondaryActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .testTag(testTag)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.88f, size.height * 0.18f),
                            radius = size.width * 0.65f
                        )
                    )
                }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accentColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WorkspaceStatsStrip(
    documentCount: Int,
    pageCount: Int,
    folderCount: Int
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatMetricItem(
                icon = Icons.Filled.Description,
                value = "$documentCount",
                label = "Scans",
                tint = MaterialTheme.colorScheme.primary
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            StatMetricItem(
                icon = Icons.Filled.Layers,
                value = "$pageCount",
                label = "Pages",
                tint = MaterialTheme.colorScheme.secondary
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            StatMetricItem(
                icon = Icons.Filled.Folder,
                value = "$folderCount",
                label = "Folders",
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun StatMetricItem(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.12f),
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderChipItem(
    folder: FolderEntity,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
        ),
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        label = { Text(folder.name) },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("folder_chip_${folder.id}")
    )
}

@Composable
private fun EmptyDocumentsCard(
    isSearching: Boolean,
    onScanClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("empty_library_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isSearching) {
                Image(
                    painter = painterResource(id = R.drawable.img_empty_scans),
                    contentDescription = "Scan documents illustration",
                    modifier = Modifier
                        .fillMaxWidth(0.76f)
                        .heightIn(max = 165.dp)
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = if (isSearching) "No matching documents found" else stringResource(R.string.empty_library_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isSearching) {
                    "Try a different filename, note keyword, or recognized OCR phrase."
                } else {
                    stringResource(R.string.empty_library_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isSearching) {
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onScanClick,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start Scan")
                    }
                    OutlinedButton(
                        onClick = onImportClick,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import")
                    }
                }
            }
        }
    }
}
