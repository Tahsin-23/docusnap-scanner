package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.cv.BitmapUtils
import com.example.cv.DocumentDetector
import com.example.cv.ImageFilterEngine
import com.example.cv.PerspectiveTransformer
import com.example.data.DocuSnapDatabase
import com.example.data.DocumentEntity
import com.example.data.DocumentRepository
import com.example.data.DocumentWithPages
import com.example.data.FolderEntity
import com.example.data.SettingsRepository
import com.example.export.ExportManager
import com.example.model.EditablePage
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.FilterAdjustments
import com.example.model.FilterType
import com.example.model.NormalizedQuad
import com.example.model.OcrLanguage
import com.example.model.PdfOrientation
import com.example.model.PdfPageSize
import com.example.model.ScanMode
import com.example.model.SortOrder
import com.example.model.ThemePreference
import com.example.ocr.OcrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DocuSnapViewModel(
    application: Application,
    private val repository: DocumentRepository,
    private val settingsRepository: SettingsRepository,
    private val ocrManager: OcrManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<List<EditablePage>>()
    private val redoStack = ArrayDeque<List<EditablePage>>()
    private var filterPreviewJob: Job? = null

    init {
        refreshDeviceCapabilities()
        observeDataStreams()
    }

    private fun observeDataStreams() {
        viewModelScope.launch {
            combine(
                repository.allDocuments,
                repository.allFolders,
                settingsRepository.settingsFlow
            ) { docs, folders, settings ->
                Triple(docs, folders, settings)
            }.collect { (docs, folders, settings) ->
                _uiState.update { current ->
                    val filtered = filterAndSortDocuments(
                        docs = docs,
                        folderId = current.library.selectedFolderId,
                        query = current.library.searchQuery,
                        sortOrder = current.library.sortOrder
                    )
                    current.copy(
                        settings = settings,
                        library = current.library.copy(
                            documents = docs,
                            filteredDocuments = filtered,
                            folders = folders
                        )
                    )
                }
            }
        }
    }

    fun observeDocumentDetail(documentId: Long): Flow<DocumentWithPages?> =
        repository.observeDocumentWithPages(documentId)

    private fun refreshDeviceCapabilities() {
        val pm = getApplication<Application>().packageManager
        val hasAnyCam = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasRear = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        val hasFlash = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        val freeMb = repository.getAvailableStorageBytes() / (1024L * 1024L)
        _uiState.update {
            it.copy(
                capabilities = DeviceCapabilities(
                    hasAnyCamera = hasAnyCam,
                    hasRearCamera = hasRear,
                    hasFlashHardware = hasFlash,
                    availableStorageMb = freeMb
                )
            )
        }
    }

    // --- Navigation ---

    fun navigateTo(destination: AppDestination) {
        _uiState.update { it.copy(destination = destination) }
    }

    fun startCameraFlow(scanMode: ScanMode, appendToSession: Boolean = false) {
        if (!appendToSession) {
            resetEditorSession(scanMode)
        }
        _uiState.update {
            it.copy(destination = AppDestination.Camera(scanMode, appendToSession))
        }
    }

    private fun resetEditorSession(scanMode: ScanMode) {
        undoStack.clear()
        redoStack.clear()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val prefix = if (scanMode == ScanMode.PHOTO) "Photo" else "Scan"
        _uiState.update { state ->
            state.copy(
                editor = EditorSessionState(
                    existingDocumentId = null,
                    title = "$prefix $stamp",
                    notes = "",
                    folderId = state.library.selectedFolderId,
                    scanMode = scanMode,
                    pages = emptyList(),
                    selectedPageIndex = 0,
                    activeTab = EditorTab.FILTERS,
                    hasUnsavedChanges = false
                )
            )
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    // --- Capture & Gallery Import ---

    fun onRawFileCaptured(
        capturedFile: File,
        scanMode: ScanMode,
        appendToSession: Boolean,
        stayInCameraForMorePages: Boolean = false
    ) {
        viewModelScope.launch {
            if (!appendToSession && _uiState.value.editor.pages.isEmpty()) {
                resetEditorSession(scanMode)
            }
            setEditorProcessing(true, "Detecting boundaries & enhancing scan…")

            val newPage = withContext(Dispatchers.Default) {
                val bmp = BitmapUtils.decodeBitmapFromFile(capturedFile.absolutePath)
                capturedFile.delete()
                if (bmp == null) null else buildInitialEditablePage(bmp, scanMode)
            }

            if (newPage == null) {
                setEditorProcessing(false, "")
                showMessage("Could not decode captured photo. Please try again.")
                return@launch
            }

            appendPagesInternal(listOf(newPage), navigateToEditor = !stayInCameraForMorePages)
            if (!newPage.wasBoundaryAutoDetected && scanMode == ScanMode.DOCUMENT) {
                showMessage("No clear paper edges detected — adjust corners in Crop & Rotate if needed.")
            }
        }
    }

    fun onGalleryUrisSelected(
        uris: List<Uri>,
        scanMode: ScanMode,
        appendToSession: Boolean
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (!appendToSession) {
                resetEditorSession(scanMode)
            }
            _uiState.update { it.copy(destination = AppDestination.Editor) }
            setEditorProcessing(true, "Importing ${uris.size} image(s)…")

            val context = getApplication<Application>()
            val importedPages = withContext(Dispatchers.Default) {
                uris.mapNotNull { uri ->
                    val bmp = BitmapUtils.decodeBitmapFromUri(context, uri) ?: return@mapNotNull null
                    buildInitialEditablePage(bmp, scanMode)
                }
            }

            if (importedPages.isEmpty()) {
                setEditorProcessing(false, "")
                showMessage("Unable to import selected image(s). Unsupported or corrupted format.")
                if (_uiState.value.editor.pages.isEmpty()) {
                    navigateTo(AppDestination.Home)
                }
                return@launch
            }

            appendPagesInternal(importedPages, navigateToEditor = true)
        }
    }

    private fun buildInitialEditablePage(
        rawBitmap: Bitmap,
        scanMode: ScanMode
    ): EditablePage {
        val uid = UUID.randomUUID().toString()
        val tmpDir = repository.getSessionTempDir()
        val origFile = File(tmpDir, "${uid}_orig.png")
        BitmapUtils.saveBitmapToFile(rawBitmap, origFile, Bitmap.CompressFormat.PNG, 100)

        val (quad, autoDetected) = if (scanMode == ScanMode.DOCUMENT) {
            val detection = DocumentDetector.detect(rawBitmap)
            if (detection.isDetected && detection.quad != null) {
                detection.quad to true
            } else {
                NormalizedQuad.fullFrame() to false
            }
        } else {
            // Ordinary Photo mode: preserve full frame & true original colors
            NormalizedQuad.fullFrame() to false
        }

        val defaultFilter = if (scanMode == ScanMode.DOCUMENT) {
            _uiState.value.settings.defaultDocumentFilter
        } else {
            FilterType.ORIGINAL
        }

        val defaultAdjustments = if (scanMode == ScanMode.DOCUMENT) {
            FilterAdjustments.DEFAULT_DOCUMENT
        } else {
            FilterAdjustments.DEFAULT_PHOTO
        }

        val warped = PerspectiveTransformer.cropAndWarp(rawBitmap, quad, 0f)
        val filtered = ImageFilterEngine.applyFilterAndAdjustments(warped, defaultFilter, defaultAdjustments)
        if (warped !== rawBitmap && warped !== filtered) warped.recycle()
        rawBitmap.recycle()

        val procFile = File(tmpDir, "${uid}_proc_0.jpg")
        val thumbFile = File(tmpDir, "${uid}_thumb_0.jpg")
        BitmapUtils.saveBitmapToFile(filtered, procFile, Bitmap.CompressFormat.JPEG, 95)
        val thumbBmp = BitmapUtils.createThumbnail(filtered)
        BitmapUtils.saveBitmapToFile(thumbBmp, thumbFile, Bitmap.CompressFormat.JPEG, 88)
        thumbBmp.recycle()
        filtered.recycle()

        return EditablePage(
            id = uid,
            originalImagePath = origFile.absolutePath,
            processedImagePath = procFile.absolutePath,
            thumbnailPath = thumbFile.absolutePath,
            quad = quad,
            wasBoundaryAutoDetected = autoDetected,
            rotationDegrees = 0f,
            filterType = defaultFilter,
            adjustments = defaultAdjustments
        )
    }

    private fun appendPagesInternal(newPages: List<EditablePage>, navigateToEditor: Boolean) {
        val currentPages = _uiState.value.editor.pages
        if (currentPages.isNotEmpty()) {
            pushUndoSnapshot(currentPages)
        }
        val combined = currentPages + newPages
        val newIndex = combined.lastIndex.coerceAtLeast(0)

        _uiState.update { state ->
            val defaultTab = if (
                newPages.size == 1 &&
                state.editor.scanMode == ScanMode.DOCUMENT &&
                !newPages.first().wasBoundaryAutoDetected &&
                currentPages.isEmpty()
            ) {
                EditorTab.CROP
            } else {
                state.editor.activeTab
            }

            state.copy(
                destination = if (navigateToEditor) AppDestination.Editor else state.destination,
                editor = state.editor.copy(
                    pages = combined,
                    selectedPageIndex = newIndex,
                    activeTab = defaultTab,
                    hasUnsavedChanges = true,
                    isProcessing = false,
                    processingMessage = "",
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            )
        }
        syncOcrStateForCurrentPage()
        prepareDefaultExportState()
    }

    // --- Editor Operations & Non-Destructive Reprocessing ---

    fun selectEditorTab(tab: EditorTab) {
        _uiState.update { it.copy(editor = it.editor.copy(activeTab = tab)) }
        if (tab == EditorTab.EXPORT) {
            prepareDefaultExportState()
            refreshExportPreview()
        } else if (tab == EditorTab.OCR) {
            syncOcrStateForCurrentPage()
        }
    }

    fun selectPage(index: Int) {
        val pages = _uiState.value.editor.pages
        if (index !in pages.indices) return
        _uiState.update {
            it.copy(
                editor = it.editor.copy(
                    selectedPageIndex = index,
                    showBeforeOriginal = false
                )
            )
        }
        syncOcrStateForCurrentPage()
    }

    fun toggleBeforeAfter(showOriginal: Boolean) {
        _uiState.update { it.copy(editor = it.editor.copy(showBeforeOriginal = showOriginal)) }
    }

    fun updateEditorTitle(title: String) {
        _uiState.update {
            it.copy(editor = it.editor.copy(title = title, hasUnsavedChanges = true))
        }
    }

    fun updateEditorNotes(notes: String) {
        _uiState.update {
            it.copy(editor = it.editor.copy(notes = notes, hasUnsavedChanges = true))
        }
    }

    fun updateEditorFolder(folderId: Long?) {
        _uiState.update {
            it.copy(editor = it.editor.copy(folderId = folderId, hasUnsavedChanges = true))
        }
    }

    fun applyCropAndRotationToCurrentPage(
        newQuad: NormalizedQuad,
        newRotationDegrees: Float,
        switchToFiltersAfter: Boolean = false
    ) {
        val current = _uiState.value.editor.currentPage ?: return
        val clampedQuad = newQuad.clamp()
        if (!clampedQuad.isConvex()) {
            showMessage("Invalid crop corners: please keep corners forming a non-crossing 4-sided shape.")
            return
        }
        pushUndoSnapshot(_uiState.value.editor.pages)
        val updated = current.copy(
            quad = clampedQuad,
            rotationDegrees = ((newRotationDegrees % 360f) + 360f) % 360f
        )
        reprocessPage(updated, switchToFiltersAfter = switchToFiltersAfter)
    }

    fun rotateCurrentPage90(clockwise: Boolean) {
        val current = _uiState.value.editor.currentPage ?: return
        val delta = if (clockwise) 90f else -90f
        val nextRot = (((current.rotationDegrees + delta) % 360f) + 360f) % 360f
        pushUndoSnapshot(_uiState.value.editor.pages)
        reprocessPage(current.copy(rotationDegrees = nextRot))
    }

    fun autoDetectCropForCurrentPage(onDetectedQuad: (NormalizedQuad, Boolean) -> Unit) {
        val current = _uiState.value.editor.currentPage ?: return
        viewModelScope.launch {
            setEditorProcessing(true, "Detecting paper corners…")
            val result = withContext(Dispatchers.Default) {
                val orig = BitmapUtils.decodeBitmapFromFile(current.originalImagePath)
                    ?: return@withContext null
                val rotated = PerspectiveTransformer.rotateBitmap(orig, current.rotationDegrees)
                if (rotated !== orig) orig.recycle()
                val detection = DocumentDetector.detect(rotated)
                rotated.recycle()
                detection
            }
            setEditorProcessing(false, "")
            if (result != null && result.isDetected && result.quad != null) {
                onDetectedQuad(result.quad, true)
                showMessage("Document boundaries detected!")
            } else {
                onDetectedQuad(NormalizedQuad.insetFrame(0.06f), false)
                showMessage("No clear paper boundaries found. Drag the 4 corner handles manually.")
            }
        }
    }

    fun updateFilterForCurrentPage(filterType: FilterType) {
        val current = _uiState.value.editor.currentPage ?: return
        if (current.filterType == filterType) return
        pushUndoSnapshot(_uiState.value.editor.pages)
        reprocessPage(current.copy(filterType = filterType))
    }

    fun updateAdjustmentsForCurrentPage(adjustments: FilterAdjustments) {
        val current = _uiState.value.editor.currentPage ?: return
        filterPreviewJob?.cancel()
        filterPreviewJob = viewModelScope.launch {
            pushUndoSnapshotIfDistinct(_uiState.value.editor.pages)
            reprocessPageSuspend(current.copy(adjustments = adjustments), showFullLoader = false)
        }
    }

    fun resetAdjustmentsForCurrentPage() {
        val current = _uiState.value.editor.currentPage ?: return
        pushUndoSnapshot(_uiState.value.editor.pages)
        val defaults = if (_uiState.value.editor.scanMode == ScanMode.PHOTO) {
            FilterAdjustments.DEFAULT_PHOTO
        } else {
            FilterAdjustments.DEFAULT_DOCUMENT
        }
        reprocessPage(current.copy(adjustments = defaults))
    }

    private fun reprocessPage(
        targetPage: EditablePage,
        switchToFiltersAfter: Boolean = false
    ) {
        viewModelScope.launch {
            reprocessPageSuspend(targetPage, showFullLoader = true, switchToFiltersAfter = switchToFiltersAfter)
        }
    }

    private suspend fun reprocessPageSuspend(
        targetPage: EditablePage,
        showFullLoader: Boolean,
        switchToFiltersAfter: Boolean = false
    ) {
        if (showFullLoader) {
            setEditorProcessing(true, "Applying perspective & filter…")
        }
        val regenerated = withContext(Dispatchers.Default) {
            val orig = BitmapUtils.decodeBitmapFromFile(targetPage.originalImagePath)
                ?: return@withContext null
            val warped = PerspectiveTransformer.cropAndWarp(
                source = orig,
                quad = targetPage.quad,
                rotationDegrees = targetPage.rotationDegrees
            )
            val filtered = ImageFilterEngine.applyFilterAndAdjustments(
                source = warped,
                filterType = targetPage.filterType,
                adjustments = targetPage.adjustments
            )
            if (warped !== orig && warped !== filtered) warped.recycle()
            orig.recycle()

            val rev = System.nanoTime()
            val tmpDir = repository.getSessionTempDir()
            val procFile = File(tmpDir, "${targetPage.id}_proc_$rev.jpg")
            val thumbFile = File(tmpDir, "${targetPage.id}_thumb_$rev.jpg")
            BitmapUtils.saveBitmapToFile(filtered, procFile, Bitmap.CompressFormat.JPEG, 95)
            val thumb = BitmapUtils.createThumbnail(filtered)
            BitmapUtils.saveBitmapToFile(thumb, thumbFile, Bitmap.CompressFormat.JPEG, 88)
            thumb.recycle()
            filtered.recycle()

            targetPage.copy(
                processedImagePath = procFile.absolutePath,
                thumbnailPath = thumbFile.absolutePath
            )
        }

        if (regenerated == null) {
            setEditorProcessing(false, "")
            showMessage("Image processing failed for this page.")
            return
        }

        _uiState.update { state ->
            val updatedList = state.editor.pages.map { p ->
                if (p.id == regenerated.id) regenerated else p
            }
            state.copy(
                editor = state.editor.copy(
                    pages = updatedList,
                    activeTab = if (switchToFiltersAfter) EditorTab.FILTERS else state.editor.activeTab,
                    hasUnsavedChanges = true,
                    isProcessing = false,
                    processingMessage = "",
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            )
        }
    }

    // --- Multi-Page Reordering, Duplication, Deletion, Undo/Redo ---

    fun movePage(fromIndex: Int, toIndex: Int) {
        val pages = _uiState.value.editor.pages
        if (fromIndex !in pages.indices || toIndex !in pages.indices || fromIndex == toIndex) return
        pushUndoSnapshot(pages)
        val mutable = pages.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        _uiState.update {
            it.copy(
                editor = it.editor.copy(
                    pages = mutable,
                    selectedPageIndex = toIndex,
                    hasUnsavedChanges = true,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            )
        }
    }

    fun duplicatePage(index: Int) {
        val pages = _uiState.value.editor.pages
        val target = pages.getOrNull(index) ?: return
        viewModelScope.launch {
            pushUndoSnapshot(pages)
            val cloned = withContext(Dispatchers.IO) {
                val uid = UUID.randomUUID().toString()
                val tmpDir = repository.getSessionTempDir()
                val origCopy = File(tmpDir, "${uid}_orig.png")
                val procCopy = File(tmpDir, "${uid}_proc.jpg")
                val thumbCopy = File(tmpDir, "${uid}_thumb.jpg")
                File(target.originalImagePath).copyTo(origCopy, overwrite = true)
                File(target.processedImagePath).copyTo(procCopy, overwrite = true)
                File(target.thumbnailPath).copyTo(thumbCopy, overwrite = true)
                target.copy(
                    id = uid,
                    originalImagePath = origCopy.absolutePath,
                    processedImagePath = procCopy.absolutePath,
                    thumbnailPath = thumbCopy.absolutePath
                )
            }
            val mutable = pages.toMutableList().apply { add(index + 1, cloned) }
            _uiState.update {
                it.copy(
                    editor = it.editor.copy(
                        pages = mutable,
                        selectedPageIndex = index + 1,
                        hasUnsavedChanges = true,
                        canUndo = undoStack.isNotEmpty(),
                        canRedo = redoStack.isNotEmpty()
                    )
                )
            }
            showMessage("Page ${index + 1} duplicated.")
        }
    }

    fun deletePage(index: Int) {
        val pages = _uiState.value.editor.pages
        if (index !in pages.indices) return
        if (pages.size <= 1) {
            showMessage("A document must keep at least 1 page. Discard the scan to exit.")
            return
        }
        pushUndoSnapshot(pages)
        val mutable = pages.toMutableList().apply { removeAt(index) }
        val nextSel = index.coerceAtMost(mutable.lastIndex)
        _uiState.update {
            it.copy(
                editor = it.editor.copy(
                    pages = mutable,
                    selectedPageIndex = nextSel,
                    hasUnsavedChanges = true,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            )
        }
        syncOcrStateForCurrentPage()
    }

    fun undoEdit() {
        if (undoStack.isEmpty()) return
        val current = _uiState.value.editor.pages
        redoStack.addLast(current)
        val prev = undoStack.removeLast()
        _uiState.update { state ->
            state.copy(
                editor = state.editor.copy(
                    pages = prev,
                    selectedPageIndex = state.editor.selectedPageIndex.coerceIn(0, (prev.size - 1).coerceAtLeast(0)),
                    hasUnsavedChanges = true,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            )
        }
    }

    fun redoEdit() {
        if (redoStack.isEmpty()) return
        val current = _uiState.value.editor.pages
        undoStack.addLast(current)
        val next = redoStack.removeLast()
        _uiState.update { state ->
            state.copy(
                editor = state.editor.copy(
                    pages = next,
                    selectedPageIndex = state.editor.selectedPageIndex.coerceIn(0, (next.size - 1).coerceAtLeast(0)),
                    hasUnsavedChanges = true,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            )
        }
    }

    private fun pushUndoSnapshot(pages: List<EditablePage>) {
        if (undoStack.size >= 15) undoStack.removeFirst()
        undoStack.addLast(pages)
        redoStack.clear()
    }

    private fun pushUndoSnapshotIfDistinct(pages: List<EditablePage>) {
        val last = undoStack.lastOrNull()
        if (last != pages) {
            pushUndoSnapshot(pages)
        }
    }

    private fun setEditorProcessing(loading: Boolean, message: String) {
        _uiState.update {
            it.copy(
                editor = it.editor.copy(
                    isProcessing = loading,
                    processingMessage = message
                )
            )
        }
    }

    // --- Save to Local Library & Re-open Saved Documents ---

    fun saveCurrentSessionToLibrary(openDetailAfterSave: Boolean = true) {
        val editor = _uiState.value.editor
        if (editor.pages.isEmpty()) return
        viewModelScope.launch {
            setEditorProcessing(true, "Saving document locally…")
            val res = repository.saveEditorDocument(
                existingDocumentId = editor.existingDocumentId,
                title = editor.title,
                notes = editor.notes,
                folderId = editor.folderId,
                scanMode = editor.scanMode,
                pages = editor.pages
            )
            setEditorProcessing(false, "")
            res.onSuccess { docId ->
                _uiState.update { state ->
                    state.copy(
                        destination = if (openDetailAfterSave) {
                            AppDestination.DocumentDetail(docId)
                        } else {
                            state.destination
                        },
                        editor = state.editor.copy(
                            existingDocumentId = docId,
                            hasUnsavedChanges = false
                        ),
                        snackbarMessage = "Saved \"${editor.title.ifBlank { "Document" }}\" to local library."
                    )
                }
            }.onFailure { err ->
                showMessage(err.message ?: "Failed to save document locally.")
            }
        }
    }

    fun openSavedDocumentForEditing(documentId: Long) {
        viewModelScope.launch {
            val docWithPages = repository.getDocumentWithPages(documentId)
            if (docWithPages == null || docWithPages.pages.isEmpty()) {
                showMessage("Could not load saved document pages.")
                return@launch
            }
            undoStack.clear()
            redoStack.clear()
            _uiState.update {
                it.copy(
                    destination = AppDestination.Editor,
                    editor = EditorSessionState(
                        existingDocumentId = docWithPages.document.id,
                        title = docWithPages.document.title,
                        notes = docWithPages.document.notes,
                        folderId = docWithPages.document.folderId,
                        scanMode = runCatching { ScanMode.valueOf(docWithPages.document.scanMode) }
                            .getOrDefault(ScanMode.DOCUMENT),
                        isProcessing = true,
                        processingMessage = "Loading pages for editing…"
                    )
                )
            }
            val loadedPages = repository.loadPagesForEditing(docWithPages)
            _uiState.update { state ->
                state.copy(
                    editor = state.editor.copy(
                        pages = loadedPages,
                        selectedPageIndex = 0,
                        hasUnsavedChanges = false,
                        isProcessing = false,
                        processingMessage = ""
                    )
                )
            }
            syncOcrStateForCurrentPage()
            prepareDefaultExportState()
        }
    }

    fun discardSessionAndExit() {
        viewModelScope.launch {
            repository.clearSessionTempFiles()
        }
        undoStack.clear()
        redoStack.clear()
        _uiState.update {
            it.copy(
                destination = AppDestination.Home,
                editor = EditorSessionState()
            )
        }
    }

    // --- Export PDF, JPG, PNG ---

    fun prepareExportForSavedDocument(docWithPages: DocumentWithPages, format: ExportFormat = ExportFormat.PDF) {
        val paths = docWithPages.orderedPages.map { it.processedImagePath }
        val indices = paths.indices.toSet()
        _uiState.update { state ->
            state.copy(
                exportState = ExportUiState(
                    filename = docWithPages.document.title,
                    format = format,
                    selectedPageIndices = indices,
                    qualityPreset = state.settings.defaultExportQuality,
                    customJpegQuality = state.settings.defaultExportQuality.jpegQuality
                )
            )
        }
        refreshExportPreviewForPaths(paths, indices)
    }

    private fun prepareDefaultExportState() {
        val editor = _uiState.value.editor
        val defaultFormat = if (editor.scanMode == ScanMode.PHOTO && editor.pages.size == 1) {
            ExportFormat.JPG
        } else {
            ExportFormat.PDF
        }
        _uiState.update { state ->
            state.copy(
                exportState = state.exportState.copy(
                    filename = editor.title.ifBlank { "DocuSnap_Scan" },
                    format = defaultFormat,
                    selectedPageIndices = editor.pages.indices.toSet(),
                    qualityPreset = state.settings.defaultExportQuality,
                    customJpegQuality = state.settings.defaultExportQuality.jpegQuality,
                    statusMessage = null,
                    isError = false
                )
            )
        }
    }

    fun updateExportConfig(
        filename: String = _uiState.value.exportState.filename,
        format: ExportFormat = _uiState.value.exportState.format,
        selectedPageIndices: Set<Int> = _uiState.value.exportState.selectedPageIndices,
        pdfPageSize: PdfPageSize = _uiState.value.exportState.pdfPageSize,
        pdfOrientation: PdfOrientation = _uiState.value.exportState.pdfOrientation,
        qualityPreset: ExportQualityPreset = _uiState.value.exportState.qualityPreset,
        customJpegQuality: Int = _uiState.value.exportState.customJpegQuality,
        overridePaths: List<String>? = null
    ) {
        _uiState.update { state ->
            state.copy(
                exportState = state.exportState.copy(
                    filename = filename,
                    format = format,
                    selectedPageIndices = selectedPageIndices,
                    pdfPageSize = pdfPageSize,
                    pdfOrientation = pdfOrientation,
                    qualityPreset = qualityPreset,
                    customJpegQuality = customJpegQuality,
                    statusMessage = null,
                    isError = false
                )
            )
        }
        val paths = overridePaths ?: _uiState.value.editor.pages.map { it.processedImagePath }
        refreshExportPreviewForPaths(paths, selectedPageIndices)
    }

    fun refreshExportPreview() {
        val paths = _uiState.value.editor.pages.map { it.processedImagePath }
        refreshExportPreviewForPaths(paths, _uiState.value.exportState.selectedPageIndices)
    }

    private fun refreshExportPreviewForPaths(allPaths: List<String>, selectedIndices: Set<Int>) {
        val chosen = selectedIndices.sorted().mapNotNull { allPaths.getOrNull(it) }
        if (chosen.isEmpty()) {
            _uiState.update {
                it.copy(
                    exportState = it.exportState.copy(
                        lastGeneratedResult = null,
                        statusMessage = "Select at least one page to export.",
                        isError = true
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(exportState = it.exportState.copy(isGenerating = true)) }
            val exp = _uiState.value.exportState
            val ctx = getApplication<Application>()
            val result = if (exp.format == ExportFormat.PDF) {
                ExportManager.generatePdf(
                    context = ctx,
                    filenameBase = exp.filename,
                    processedImagePaths = chosen,
                    pageSize = exp.pdfPageSize,
                    orientation = exp.pdfOrientation,
                    qualityPreset = exp.qualityPreset,
                    customJpegQuality = exp.customJpegQuality
                )
            } else {
                ExportManager.generateImages(
                    context = ctx,
                    filenameBase = exp.filename,
                    processedImagePaths = chosen,
                    format = exp.format,
                    qualityPreset = exp.qualityPreset,
                    customJpegQuality = exp.customJpegQuality
                )
            }

            result.onSuccess { gen ->
                _uiState.update {
                    it.copy(
                        exportState = it.exportState.copy(
                            isGenerating = false,
                            lastGeneratedResult = gen,
                            statusMessage = null,
                            isError = false
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        exportState = it.exportState.copy(
                            isGenerating = false,
                            statusMessage = err.message ?: "Export generation failed.",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    fun downloadCurrentExportToDevice(overridePaths: List<String>? = null) {
        viewModelScope.launch {
            val allPaths = overridePaths ?: _uiState.value.editor.pages.map { it.processedImagePath }
            val exp = _uiState.value.exportState
            val chosen = exp.selectedPageIndices.sorted().mapNotNull { allPaths.getOrNull(it) }
            if (chosen.isEmpty()) {
                showMessage("Select at least one page to save.")
                return@launch
            }
            _uiState.update { it.copy(exportState = it.exportState.copy(isGenerating = true)) }
            val ctx = getApplication<Application>()
            val genRes = if (exp.format == ExportFormat.PDF) {
                ExportManager.generatePdf(
                    ctx, exp.filename, chosen, exp.pdfPageSize, exp.pdfOrientation, exp.qualityPreset, exp.customJpegQuality
                )
            } else {
                ExportManager.generateImages(
                    ctx, exp.filename, chosen, exp.format, exp.qualityPreset, exp.customJpegQuality
                )
            }

            genRes.onSuccess { gen ->
                val saveRes = ExportManager.saveFilesToDeviceDownloads(ctx, gen.files, gen.mimeType)
                saveRes.onSuccess { paths ->
                    _uiState.update {
                        it.copy(
                            exportState = it.exportState.copy(
                                isGenerating = false,
                                lastGeneratedResult = gen,
                                statusMessage = "Saved (${gen.formattedSize}) to ${paths.first()}",
                                isError = false
                            ),
                            snackbarMessage = "Saved to ${paths.first()}"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            exportState = it.exportState.copy(
                                isGenerating = false,
                                statusMessage = err.message ?: "Failed saving to Downloads.",
                                isError = true
                            )
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        exportState = it.exportState.copy(
                            isGenerating = false,
                            statusMessage = err.message ?: "Failed generating export.",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    fun shareCurrentExport(overridePaths: List<String>? = null) {
        viewModelScope.launch {
            val allPaths = overridePaths ?: _uiState.value.editor.pages.map { it.processedImagePath }
            val exp = _uiState.value.exportState
            val chosen = exp.selectedPageIndices.sorted().mapNotNull { allPaths.getOrNull(it) }
            if (chosen.isEmpty()) {
                showMessage("Select at least one page to share.")
                return@launch
            }
            _uiState.update { it.copy(exportState = it.exportState.copy(isGenerating = true)) }
            val ctx = getApplication<Application>()
            val genRes = if (exp.format == ExportFormat.PDF) {
                ExportManager.generatePdf(
                    ctx, exp.filename, chosen, exp.pdfPageSize, exp.pdfOrientation, exp.qualityPreset, exp.customJpegQuality
                )
            } else {
                ExportManager.generateImages(
                    ctx, exp.filename, chosen, exp.format, exp.qualityPreset, exp.customJpegQuality
                )
            }
            genRes.onSuccess { gen ->
                _uiState.update {
                    it.copy(
                        exportState = it.exportState.copy(
                            isGenerating = false,
                            lastGeneratedResult = gen
                        )
                    )
                }
                ExportManager.shareExportedFiles(ctx, gen.files, gen.mimeType, exp.filename)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        exportState = it.exportState.copy(
                            isGenerating = false,
                            statusMessage = err.message ?: "Could not prepare file for sharing.",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    // --- Optional Offline OCR ---

    private fun syncOcrStateForCurrentPage() {
        val current = _uiState.value.editor.currentPage ?: return
        val lang = current.ocrLanguage
        _uiState.update {
            it.copy(
                ocrState = it.ocrState.copy(
                    selectedLanguage = lang,
                    isLanguageReady = ocrManager.isLanguageDataReady(lang),
                    recognizedText = current.ocrText,
                    statusMessage = null,
                    isError = false
                )
            )
        }
    }

    fun selectOcrLanguage(language: OcrLanguage) {
        _uiState.update {
            it.copy(
                ocrState = it.ocrState.copy(
                    selectedLanguage = language,
                    isLanguageReady = ocrManager.isLanguageDataReady(language),
                    statusMessage = null,
                    isError = false
                )
            )
        }
    }

    fun downloadOcrLanguagePack(language: OcrLanguage = _uiState.value.ocrState.selectedLanguage) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    ocrState = it.ocrState.copy(
                        isDownloadingLanguage = true,
                        downloadProgress = 0.02f,
                        statusMessage = "Downloading offline ${language.label} data…",
                        isError = false
                    )
                )
            }
            val res = ocrManager.downloadLanguageData(language) { prog ->
                _uiState.update {
                    it.copy(ocrState = it.ocrState.copy(downloadProgress = prog))
                }
            }
            res.onSuccess {
                _uiState.update {
                    it.copy(
                        ocrState = it.ocrState.copy(
                            isDownloadingLanguage = false,
                            downloadProgress = 1f,
                            isLanguageReady = ocrManager.isLanguageDataReady(language),
                            statusMessage = "${language.label} offline pack ready!",
                            isError = false
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        ocrState = it.ocrState.copy(
                            isDownloadingLanguage = false,
                            isLanguageReady = ocrManager.isLanguageDataReady(language),
                            statusMessage = err.message ?: "Language download failed. Check network and retry.",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    fun runOcrOnCurrentPage(extractAllPages: Boolean = false) {
        val editor = _uiState.value.editor
        val lang = _uiState.value.ocrState.selectedLanguage
        if (!ocrManager.isLanguageDataReady(lang)) {
            _uiState.update {
                it.copy(
                    ocrState = it.ocrState.copy(
                        statusMessage = "Language data for ${lang.label} is required first. Tap 'Download Language Data'.",
                        isError = true
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    ocrState = it.ocrState.copy(
                        isRecognizing = true,
                        statusMessage = if (extractAllPages) "Extracting text from all ${editor.pages.size} pages…" else "Recognizing text on page ${editor.selectedPageIndex + 1}…",
                        isError = false
                    )
                )
            }

            if (extractAllPages) {
                val updatedPages = editor.pages.mapIndexed { idx, p ->
                    val res = ocrManager.recognizeTextFromFile(p.processedImagePath, lang)
                    val txt = res.getOrDefault("")
                    p.copy(ocrText = txt, ocrLanguage = lang)
                }
                val combined = updatedPages.mapIndexedNotNull { idx, p ->
                    p.ocrText.takeIf { it.isNotBlank() }?.let { "--- Page ${idx + 1} ---\n$it" }
                }.joinToString("\n\n")

                _uiState.update { state ->
                    state.copy(
                        editor = state.editor.copy(pages = updatedPages, hasUnsavedChanges = true),
                        ocrState = state.ocrState.copy(
                            isRecognizing = false,
                            recognizedText = combined,
                            statusMessage = if (combined.isBlank()) "No readable text found on scanned pages." else "Recognized text across ${updatedPages.size} page(s). Save document to index for search.",
                            isError = false
                        )
                    )
                }
            } else {
                val page = editor.currentPage ?: return@launch
                val res = ocrManager.recognizeTextFromFile(page.processedImagePath, lang)
                res.onSuccess { text ->
                    val updatedPage = page.copy(ocrText = text, ocrLanguage = lang)
                    _uiState.update { state ->
                        val newPages = state.editor.pages.map { if (it.id == updatedPage.id) updatedPage else it }
                        state.copy(
                            editor = state.editor.copy(pages = newPages, hasUnsavedChanges = true),
                            ocrState = state.ocrState.copy(
                                isRecognizing = false,
                                recognizedText = text,
                                statusMessage = if (text.isBlank()) "No clear text detected on this page." else "Text extracted locally (${text.length} chars).",
                                isError = false
                            )
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            ocrState = it.ocrState.copy(
                                isRecognizing = false,
                                statusMessage = err.message ?: "OCR recognition failed.",
                                isError = true
                            )
                        )
                    }
                }
            }
        }
    }

    fun updateRecognizedOcrText(editedText: String) {
        val current = _uiState.value.editor.currentPage
        _uiState.update { state ->
            val updatedPages = if (current != null) {
                state.editor.pages.map {
                    if (it.id == current.id) it.copy(ocrText = editedText) else it
                }
            } else state.editor.pages
            state.copy(
                editor = state.editor.copy(pages = updatedPages, hasUnsavedChanges = true),
                ocrState = state.ocrState.copy(recognizedText = editedText)
            )
        }
    }

    fun copyTextToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DocuSnap OCR Text", text))
        showMessage("Copied recognized text to clipboard.")
    }

    fun saveOcrTextAsTxtFile(filenameBase: String, text: String, shareInstead: Boolean = false) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            ExportManager.generateTxtFile(ctx, filenameBase, text).onSuccess { file ->
                if (shareInstead) {
                    ExportManager.shareExportedFiles(ctx, listOf(file), "text/plain", filenameBase)
                } else {
                    ExportManager.saveFilesToDeviceDownloads(ctx, listOf(file), "text/plain")
                        .onSuccess { saved -> showMessage("Saved TXT to ${saved.first()}") }
                        .onFailure { showMessage(it.message ?: "Could not save TXT file.") }
                }
            }.onFailure {
                showMessage(it.message ?: "Could not create TXT file.")
            }
        }
    }

    // --- Library Search, Sort, Folders, Document Management ---

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            val filtered = filterAndSortDocuments(
                docs = state.library.documents,
                folderId = state.library.selectedFolderId,
                query = query,
                sortOrder = state.library.sortOrder
            )
            state.copy(
                library = state.library.copy(
                    searchQuery = query,
                    filteredDocuments = filtered
                )
            )
        }
    }

    fun selectFolder(folderId: Long?) {
        _uiState.update { state ->
            val filtered = filterAndSortDocuments(
                docs = state.library.documents,
                folderId = folderId,
                query = state.library.searchQuery,
                sortOrder = state.library.sortOrder
            )
            state.copy(
                library = state.library.copy(
                    selectedFolderId = folderId,
                    filteredDocuments = filtered
                )
            )
        }
    }

    fun setSortOrder(sortOrder: SortOrder) {
        _uiState.update { state ->
            val filtered = filterAndSortDocuments(
                docs = state.library.documents,
                folderId = state.library.selectedFolderId,
                query = state.library.searchQuery,
                sortOrder = sortOrder
            )
            state.copy(
                library = state.library.copy(
                    sortOrder = sortOrder,
                    filteredDocuments = filtered
                )
            )
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createFolder(name)
            showMessage("Folder \"${name.trim()}\" created.")
        }
    }

    fun renameFolder(folder: FolderEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.renameFolder(folder, newName)
            showMessage("Folder renamed to \"${newName.trim()}\".")
        }
    }

    fun deleteFolder(folderId: Long) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            if (_uiState.value.library.selectedFolderId == folderId) {
                selectFolder(null)
            }
            showMessage("Folder removed (documents kept in All Documents).")
        }
    }

    fun renameSavedDocument(documentId: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            repository.renameDocument(documentId, newTitle)
            showMessage("Document renamed.")
        }
    }

    fun updateSavedDocumentNotes(documentId: Long, notes: String) {
        viewModelScope.launch {
            repository.updateNotes(documentId, notes)
            showMessage("Notes saved.")
        }
    }

    fun moveSavedDocumentToFolder(documentId: Long, folderId: Long?) {
        viewModelScope.launch {
            repository.moveDocumentToFolder(documentId, folderId)
            showMessage("Moved document.")
        }
    }

    fun deleteSavedDocument(documentId: Long) {
        viewModelScope.launch {
            repository.deleteDocument(documentId)
            if (_uiState.value.destination == AppDestination.DocumentDetail(documentId)) {
                navigateTo(AppDestination.Library)
            }
            showMessage("Document deleted.")
        }
    }

    private fun filterAndSortDocuments(
        docs: List<DocumentEntity>,
        folderId: Long?,
        query: String,
        sortOrder: SortOrder
    ): List<DocumentEntity> {
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = docs.filter { doc ->
            val matchesFolder = folderId == null || doc.folderId == folderId
            val matchesQuery = q.isEmpty() ||
                doc.title.lowercase(Locale.ROOT).contains(q) ||
                doc.notes.lowercase(Locale.ROOT).contains(q) ||
                doc.combinedOcrText.lowercase(Locale.ROOT).contains(q)
            matchesFolder && matchesQuery
        }
        return when (sortOrder) {
            SortOrder.DATE_DESC -> filtered.sortedByDescending { it.updatedAt }
            SortOrder.DATE_ASC -> filtered.sortedBy { it.updatedAt }
            SortOrder.NAME_ASC -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
            SortOrder.NAME_DESC -> filtered.sortedByDescending { it.title.lowercase(Locale.ROOT) }
        }
    }

    // --- Settings ---

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch { settingsRepository.setThemePreference(theme) }
    }

    fun setDefaultDocumentFilter(filter: FilterType) {
        viewModelScope.launch { settingsRepository.setDefaultDocumentFilter(filter) }
    }

    fun setDefaultExportQuality(quality: ExportQualityPreset) {
        viewModelScope.launch { settingsRepository.setDefaultExportQuality(quality) }
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoCaptureEnabled(enabled) }
    }

    fun clearOcrLanguageCache() {
        viewModelScope.launch {
            ocrManager.clearDownloadedLanguagePacks()
            _uiState.update {
                it.copy(
                    ocrState = it.ocrState.copy(
                        isLanguageReady = ocrManager.isLanguageDataReady(it.ocrState.selectedLanguage)
                    )
                )
            }
            showMessage("Cleared downloaded OCR language files.")
        }
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val db = DocuSnapDatabase.getInstance(application)
                    val repo = DocumentRepository(application, db.dao())
                    val settingsRepo = SettingsRepository(application)
                    val ocrMgr = OcrManager(application)
                    return DocuSnapViewModel(application, repo, settingsRepo, ocrMgr) as T
                }
            }
    }
}
