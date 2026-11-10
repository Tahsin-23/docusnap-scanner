package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.ScanMode
import com.example.model.ThemePreference
import com.example.ui.screens.CameraScreen
import com.example.ui.screens.DocumentDetailScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.DocuSnapTheme
import com.example.ui.theme.HeroRoyalBlue
import com.example.ui.theme.ScannerCyan

@Composable
fun DocuSnapApp(
    viewModel: DocuSnapViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val isDark = when (state.settings.themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val homeGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onGalleryUrisSelected(
                uris = uris,
                scanMode = ScanMode.DOCUMENT,
                appendToSession = false
            )
        }
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    DocuSnapTheme(darkTheme = isDark) {
        when (val dest = state.destination) {
            is AppDestination.Camera -> {
                CameraScreen(
                    scanMode = dest.scanMode,
                    existingPageCount = if (dest.appendToCurrentSession) state.editor.pages.size else 0,
                    initialAutoCapture = state.settings.autoCaptureEnabled,
                    onCapturedFileConfirmed = { file, stayForMore ->
                        viewModel.onRawFileCaptured(
                            capturedFile = file,
                            scanMode = dest.scanMode,
                            appendToSession = dest.appendToCurrentSession || stayForMore,
                            stayInCameraForMorePages = stayForMore
                        )
                    },
                    onImportUris = { uris ->
                        viewModel.onGalleryUrisSelected(
                            uris = uris,
                            scanMode = dest.scanMode,
                            appendToSession = dest.appendToCurrentSession
                        )
                    },
                    onDoneToEditor = {
                        viewModel.navigateTo(AppDestination.Editor)
                    },
                    onCancel = {
                        if (dest.appendToCurrentSession && state.editor.pages.isNotEmpty()) {
                            viewModel.navigateTo(AppDestination.Editor)
                        } else {
                            viewModel.navigateTo(AppDestination.Home)
                        }
                    }
                )
            }

            AppDestination.Editor -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        EditorScreen(
                            editorState = state.editor,
                            exportState = state.exportState,
                            ocrState = state.ocrState,
                            folders = state.library.folders,
                            onSelectTab = viewModel::selectEditorTab,
                            onSelectPage = viewModel::selectPage,
                            onToggleBeforeAfter = viewModel::toggleBeforeAfter,
                            onTitleChange = viewModel::updateEditorTitle,
                            onNotesChange = viewModel::updateEditorNotes,
                            onFolderChange = viewModel::updateEditorFolder,
                            onAddPageFromCamera = {
                                viewModel.startCameraFlow(
                                    scanMode = state.editor.scanMode,
                                    appendToSession = true
                                )
                            },
                            onAddPagesFromGallery = { uris ->
                                viewModel.onGalleryUrisSelected(
                                    uris = uris,
                                    scanMode = state.editor.scanMode,
                                    appendToSession = true
                                )
                            },
                            onApplyCropAndRotation = { quad, rot ->
                                viewModel.applyCropAndRotationToCurrentPage(
                                    newQuad = quad,
                                    newRotationDegrees = rot,
                                    switchToFiltersAfter = true
                                )
                            },
                            onAutoDetectCrop = viewModel::autoDetectCropForCurrentPage,
                            onRotate90 = viewModel::rotateCurrentPage90,
                            onSelectFilter = viewModel::updateFilterForCurrentPage,
                            onUpdateAdjustments = viewModel::updateAdjustmentsForCurrentPage,
                            onResetAdjustments = viewModel::resetAdjustmentsForCurrentPage,
                            onMovePage = viewModel::movePage,
                            onDuplicatePage = viewModel::duplicatePage,
                            onDeletePage = viewModel::deletePage,
                            onUndo = viewModel::undoEdit,
                            onRedo = viewModel::redoEdit,
                            onSaveDocument = { viewModel.saveCurrentSessionToLibrary(openDetailAfterSave = true) },
                            onUpdateExportConfig = { name, fmt, sel, ps, orient, preset, q ->
                                viewModel.updateExportConfig(name, fmt, sel, ps, orient, preset, q)
                            },
                            onDownloadExport = { viewModel.downloadCurrentExportToDevice() },
                            onShareExport = { viewModel.shareCurrentExport() },
                            onSelectOcrLanguage = viewModel::selectOcrLanguage,
                            onDownloadOcrPack = { viewModel.downloadOcrLanguagePack() },
                            onExtractOcrPage = { viewModel.runOcrOnCurrentPage(extractAllPages = false) },
                            onExtractOcrAll = { viewModel.runOcrOnCurrentPage(extractAllPages = true) },
                            onEditOcrText = viewModel::updateRecognizedOcrText,
                            onCopyOcrText = viewModel::copyTextToClipboard,
                            onSaveOcrTxt = { txt -> viewModel.saveOcrTextAsTxtFile(state.editor.title, txt, false) },
                            onShareOcrTxt = { txt -> viewModel.saveOcrTextAsTxtFile(state.editor.title, txt, true) },
                            onDiscardAndExit = viewModel::discardSessionAndExit
                        )
                    }
                }
            }

            is AppDestination.DocumentDetail -> {
                val docWithPages by remember(dest.documentId) {
                    viewModel.observeDocumentDetail(dest.documentId)
                }.collectAsStateWithLifecycle(initialValue = null)

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        DocumentDetailScreen(
                            docWithPages = docWithPages,
                            folders = state.library.folders,
                            exportState = state.exportState,
                            onPrepareExport = { viewModel.prepareExportForSavedDocument(it) },
                            onUpdateExportConfig = { name, fmt, sel, ps, orient, preset, q, paths ->
                                viewModel.updateExportConfig(name, fmt, sel, ps, orient, preset, q, paths)
                            },
                            onDownloadExport = { paths -> viewModel.downloadCurrentExportToDevice(paths) },
                            onShareExport = { paths -> viewModel.shareCurrentExport(paths) },
                            onContinueEditing = viewModel::openSavedDocumentForEditing,
                            onRenameDocument = viewModel::renameSavedDocument,
                            onUpdateNotes = viewModel::updateSavedDocumentNotes,
                            onMoveToFolder = viewModel::moveSavedDocumentToFolder,
                            onCreateFolder = viewModel::createFolder,
                            onCopyOcrText = viewModel::copyTextToClipboard,
                            onDeleteDocument = viewModel::deleteSavedDocument,
                            onBack = { viewModel.navigateTo(AppDestination.Library) }
                        )
                    }
                }
            }

            // Primary Top-Level Destinations (Home, Library, Settings) with Adaptive Navigation
            else -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useNavigationRail = maxWidth >= 600.dp

                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        contentWindowInsets = WindowInsets.safeDrawing,
                        floatingActionButton = {
                            if (!useNavigationRail && dest == AppDestination.Library) {
                                ExtendedFloatingActionButton(
                                    onClick = { viewModel.startCameraFlow(ScanMode.DOCUMENT) },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    shape = RoundedCornerShape(18.dp),
                                    icon = {
                                        Icon(Icons.Filled.DocumentScanner, contentDescription = "Scan Document")
                                    },
                                    text = {
                                        Text("Scan", fontWeight = FontWeight.SemiBold)
                                    },
                                    modifier = Modifier.testTag("fab_scan_document")
                                )
                            }
                        },
                        bottomBar = {
                            if (!useNavigationRail) {
                                DocuSnapBottomBar(
                                    currentDestination = dest,
                                    onNavigate = viewModel::navigateTo,
                                    onQuickScan = { viewModel.startCameraFlow(ScanMode.DOCUMENT) }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (useNavigationRail) {
                                DocuSnapSideRail(
                                    currentDestination = dest,
                                    onNavigate = viewModel::navigateTo,
                                    onQuickScan = { viewModel.startCameraFlow(ScanMode.DOCUMENT) }
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                Crossfade(
                                    targetState = dest,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "top_level_tab_crossfade"
                                ) { targetDest ->
                                    when (targetDest) {
                                        AppDestination.Home -> {
                                            HomeScreen(
                                                libraryState = state.library,
                                                onScanDocumentClick = { viewModel.startCameraFlow(ScanMode.DOCUMENT) },
                                                onTakePhotoClick = { viewModel.startCameraFlow(ScanMode.PHOTO) },
                                                onImportGalleryClick = {
                                                    homeGalleryLauncher.launch(
                                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                    )
                                                },
                                                onSearchQueryChange = viewModel::setSearchQuery,
                                                onSelectFolder = viewModel::selectFolder,
                                                onCreateFolder = viewModel::createFolder,
                                                onOpenDocument = { id -> viewModel.navigateTo(AppDestination.DocumentDetail(id)) },
                                                onRenameDocument = viewModel::renameSavedDocument,
                                                onMoveDocument = viewModel::moveSavedDocumentToFolder,
                                                onDeleteDocument = viewModel::deleteSavedDocument,
                                                onViewAllLibraryClick = { viewModel.navigateTo(AppDestination.Library) }
                                            )
                                        }

                                        AppDestination.Library -> {
                                            LibraryScreen(
                                                libraryState = state.library,
                                                onSearchQueryChange = viewModel::setSearchQuery,
                                                onSelectFolder = viewModel::selectFolder,
                                                onSortOrderChange = viewModel::setSortOrder,
                                                onCreateFolder = viewModel::createFolder,
                                                onRenameFolder = viewModel::renameFolder,
                                                onDeleteFolder = viewModel::deleteFolder,
                                                onOpenDocument = { id -> viewModel.navigateTo(AppDestination.DocumentDetail(id)) },
                                                onRenameDocument = viewModel::renameSavedDocument,
                                                onMoveDocument = viewModel::moveSavedDocumentToFolder,
                                                onDeleteDocument = viewModel::deleteSavedDocument,
                                                onScanDocumentClick = { viewModel.startCameraFlow(ScanMode.DOCUMENT) },
                                                onBackToHome = { viewModel.navigateTo(AppDestination.Home) }
                                            )
                                        }

                                        AppDestination.Settings -> {
                                            SettingsScreen(
                                                settings = state.settings,
                                                capabilities = state.capabilities,
                                                onThemeChange = viewModel::setThemePreference,
                                                onDefaultFilterChange = viewModel::setDefaultDocumentFilter,
                                                onDefaultQualityChange = viewModel::setDefaultExportQuality,
                                                onAutoCaptureChange = viewModel::setAutoCaptureEnabled,
                                                onClearOcrCache = viewModel::clearOcrLanguageCache,
                                                onBackToHome = { viewModel.navigateTo(AppDestination.Home) }
                                            )
                                        }

                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocuSnapBottomBar(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    onQuickScan: () -> Unit
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.testTag("bottom_navigation_bar")
        ) {
            NavigationBarItem(
                selected = currentDestination == AppDestination.Home,
                onClick = { onNavigate(AppDestination.Home) },
                icon = {
                    Icon(
                        imageVector = if (currentDestination == AppDestination.Home) Icons.Filled.Home else Icons.Outlined.Home,
                        contentDescription = stringResource(R.string.nav_home)
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.nav_home),
                        fontWeight = if (currentDestination == AppDestination.Home) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = itemColors,
                modifier = Modifier.testTag("nav_home_tab")
            )

            NavigationBarItem(
                selected = currentDestination == AppDestination.Library,
                onClick = { onNavigate(AppDestination.Library) },
                icon = {
                    Icon(
                        imageVector = if (currentDestination == AppDestination.Library) Icons.Filled.FolderOpen else Icons.Outlined.FolderOpen,
                        contentDescription = stringResource(R.string.nav_library)
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.nav_library),
                        fontWeight = if (currentDestination == AppDestination.Library) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = itemColors,
                modifier = Modifier.testTag("nav_library_tab")
            )

            // Instant Scan Action Item in Mobile Dock
            NavigationBarItem(
                selected = false,
                onClick = onQuickScan,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(HeroRoyalBlue, ScannerCyan)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DocumentScanner,
                            contentDescription = "Quick Scan",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                },
                label = {
                    Text(
                        text = "Scan",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = itemColors,
                modifier = Modifier.testTag("nav_quick_scan_button")
            )

            NavigationBarItem(
                selected = currentDestination == AppDestination.Settings,
                onClick = { onNavigate(AppDestination.Settings) },
                icon = {
                    Icon(
                        imageVector = if (currentDestination == AppDestination.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.nav_settings)
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.nav_settings),
                        fontWeight = if (currentDestination == AppDestination.Settings) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = itemColors,
                modifier = Modifier.testTag("nav_settings_tab")
            )
        }
    }
}

@Composable
private fun DocuSnapSideRail(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    onQuickScan: () -> Unit
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxHeight()
            .testTag("side_navigation_rail"),
        header = {
            FloatingActionButton(
                onClick = onQuickScan,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Filled.DocumentScanner, contentDescription = "Scan Document")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    ) {
        NavigationRailItem(
            selected = currentDestination == AppDestination.Home,
            onClick = { onNavigate(AppDestination.Home) },
            icon = {
                Icon(
                    imageVector = if (currentDestination == AppDestination.Home) Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = stringResource(R.string.nav_home)
                )
            },
            label = { Text(stringResource(R.string.nav_home)) },
            modifier = Modifier.testTag("rail_home_tab")
        )
        NavigationRailItem(
            selected = currentDestination == AppDestination.Library,
            onClick = { onNavigate(AppDestination.Library) },
            icon = {
                Icon(
                    imageVector = if (currentDestination == AppDestination.Library) Icons.Filled.FolderOpen else Icons.Outlined.FolderOpen,
                    contentDescription = stringResource(R.string.nav_library)
                )
            },
            label = { Text(stringResource(R.string.nav_library)) },
            modifier = Modifier.testTag("rail_library_tab")
        )
        NavigationRailItem(
            selected = currentDestination == AppDestination.Settings,
            onClick = { onNavigate(AppDestination.Settings) },
            icon = {
                Icon(
                    imageVector = if (currentDestination == AppDestination.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.nav_settings)
                )
            },
            label = { Text(stringResource(R.string.nav_settings)) },
            modifier = Modifier.testTag("rail_settings_tab")
        )
    }
}
