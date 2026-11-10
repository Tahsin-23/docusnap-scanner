package com.example.ui

import com.example.data.AppSettings
import com.example.data.DocumentEntity
import com.example.data.FolderEntity
import com.example.export.GeneratedExportResult
import com.example.model.EditablePage
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.OcrLanguage
import com.example.model.PdfOrientation
import com.example.model.PdfPageSize
import com.example.model.ScanMode
import com.example.model.SortOrder

sealed interface AppDestination {
    data object Home : AppDestination
    data object Library : AppDestination
    data object Settings : AppDestination
    data class Camera(
        val scanMode: ScanMode,
        val appendToCurrentSession: Boolean = false
    ) : AppDestination
    data object Editor : AppDestination
    data class DocumentDetail(val documentId: Long) : AppDestination
}

enum class EditorTab(val label: String) {
    FILTERS("Filters"),
    CROP("Crop & Rotate"),
    PAGES("Pages & Info"),
    OCR("OCR Text"),
    EXPORT("Save & Export")
}

data class EditorSessionState(
    val existingDocumentId: Long? = null,
    val title: String = "",
    val notes: String = "",
    val folderId: Long? = null,
    val scanMode: ScanMode = ScanMode.DOCUMENT,
    val pages: List<EditablePage> = emptyList(),
    val selectedPageIndex: Int = 0,
    val activeTab: EditorTab = EditorTab.FILTERS,
    val showBeforeOriginal: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val isProcessing: Boolean = false,
    val processingMessage: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
) {
    val currentPage: EditablePage?
        get() = pages.getOrNull(selectedPageIndex)
}

data class ExportUiState(
    val filename: String = "",
    val format: ExportFormat = ExportFormat.PDF,
    val selectedPageIndices: Set<Int> = emptySet(),
    val pdfPageSize: PdfPageSize = PdfPageSize.A4,
    val pdfOrientation: PdfOrientation = PdfOrientation.PORTRAIT,
    val qualityPreset: ExportQualityPreset = ExportQualityPreset.HIGH,
    val customJpegQuality: Int = 88,
    val isGenerating: Boolean = false,
    val lastGeneratedResult: GeneratedExportResult? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

data class OcrUiState(
    val selectedLanguage: OcrLanguage = OcrLanguage.ENGLISH,
    val isLanguageReady: Boolean = true,
    val isDownloadingLanguage: Boolean = false,
    val downloadProgress: Float = 0f,
    val isRecognizing: Boolean = false,
    val recognizedText: String = "",
    val statusMessage: String? = null,
    val isError: Boolean = false
)

data class LibraryUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val filteredDocuments: List<DocumentEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val selectedFolderId: Long? = null, // null = All Documents
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.DATE_DESC
)

data class DeviceCapabilities(
    val hasAnyCamera: Boolean = true,
    val hasRearCamera: Boolean = true,
    val hasFlashHardware: Boolean = false,
    val availableStorageMb: Long = 0L
)

data class MainUiState(
    val destination: AppDestination = AppDestination.Home,
    val settings: AppSettings = AppSettings(),
    val library: LibraryUiState = LibraryUiState(),
    val editor: EditorSessionState = EditorSessionState(),
    val exportState: ExportUiState = ExportUiState(),
    val ocrState: OcrUiState = OcrUiState(),
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
    val snackbarMessage: String? = null
)
