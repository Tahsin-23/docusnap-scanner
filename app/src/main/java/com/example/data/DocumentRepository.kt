package com.example.data

import android.content.Context
import android.os.StatFs
import com.example.model.EditablePage
import com.example.model.FilterAdjustments
import com.example.model.FilterType
import com.example.model.NormalizedQuad
import com.example.model.OcrLanguage
import com.example.model.ScanMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Local-first repository coordinating Room database records and private internal file storage.
 * Preserves original source images and processed scans across app restarts and handles storage
 * constraints and file cleanup gracefully.
 */
class DocumentRepository(
    private val context: Context,
    private val dao: DocuSnapDao
) {
    val allDocuments: Flow<List<DocumentEntity>> = dao.observeAllDocuments()
    val allFolders: Flow<List<FolderEntity>> = dao.observeAllFolders()

    fun observeDocumentWithPages(documentId: Long): Flow<DocumentWithPages?> =
        dao.observeDocumentWithPages(documentId)

    suspend fun getDocumentWithPages(documentId: Long): DocumentWithPages? =
        dao.getDocumentWithPagesOnce(documentId)

    fun getSessionTempDir(): File = File(context.filesDir, "scans/session_tmp").apply { mkdirs() }
    fun getPermanentDocDir(docId: Long): File = File(context.filesDir, "scans/doc_$docId").apply { mkdirs() }

    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            if (stat.totalBytes > 0L) {
                stat.availableBytes
            } else {
                context.filesDir.usableSpace.takeIf { it > 0L } ?: (512L * 1024L * 1024L)
            }
        } catch (_: Exception) {
            512L * 1024L * 1024L
        }
    }

    suspend fun createFolder(name: String, colorHex: String = "#1D4ED8"): Long {
        return dao.insertFolder(
            FolderEntity(
                name = name.trim(),
                colorHex = colorHex,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun renameFolder(folder: FolderEntity, newName: String) {
        dao.updateFolder(folder.copy(name = newName.trim()))
    }

    suspend fun deleteFolder(folderId: Long) {
        dao.deleteFolderById(folderId)
    }

    suspend fun renameDocument(documentId: Long, newTitle: String) {
        dao.renameDocument(documentId, newTitle.trim(), System.currentTimeMillis())
    }

    suspend fun updateNotes(documentId: Long, notes: String) {
        dao.updateDocumentNotes(documentId, notes.trim(), System.currentTimeMillis())
    }

    suspend fun moveDocumentToFolder(documentId: Long, folderId: Long?) {
        dao.moveDocumentToFolder(documentId, folderId, System.currentTimeMillis())
    }

    /**
     * Saves a new or existing scanned document and copies/persists all page image files into
     * permanent private storage (`filesDir/scans/doc_<id>/`).
     */
    suspend fun saveEditorDocument(
        existingDocumentId: Long?,
        title: String,
        notes: String,
        folderId: Long?,
        scanMode: ScanMode,
        pages: List<EditablePage>
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            require(pages.isNotEmpty()) { "Document must contain at least one page." }
            val available = getAvailableStorageBytes()
            if (available < 8L * 1024L * 1024L) {
                throw IllegalStateException("Low device storage (< 8 MB free). Free up space and try again.")
            }

            val now = System.currentTimeMillis()
            val cleanTitle = title.trim().ifBlank { "Scan ${now / 1000}" }
            val combinedOcr = pages.mapNotNull { p ->
                p.ocrText.trim().takeIf { it.isNotEmpty() }
            }.joinToString("\n\n")

            val docId = if (existingDocumentId != null && existingDocumentId > 0L) {
                val existing = dao.getDocumentWithPagesOnce(existingDocumentId)?.document
                val updatedDoc = DocumentEntity(
                    id = existingDocumentId,
                    title = cleanTitle,
                    notes = notes.trim(),
                    folderId = folderId,
                    scanMode = scanMode.name,
                    pageCount = pages.size,
                    coverThumbnailPath = existing?.coverThumbnailPath ?: "",
                    combinedOcrText = combinedOcr,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
                dao.updateDocument(updatedDoc)
                existingDocumentId
            } else {
                dao.insertDocument(
                    DocumentEntity(
                        title = cleanTitle,
                        notes = notes.trim(),
                        folderId = folderId,
                        scanMode = scanMode.name,
                        pageCount = pages.size,
                        coverThumbnailPath = "",
                        combinedOcrText = combinedOcr,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }

            val docDir = getPermanentDocDir(docId)
            val keptFiles = mutableSetOf<String>()
            val persistedPages = pages.mapIndexed { idx, page ->
                val pagePrefix = "p_${idx}_${page.id.take(8)}"
                val origDest = copyIntoDirIfExternal(File(page.originalImagePath), docDir, "${pagePrefix}_orig.png")
                val procDest = copyIntoDirIfExternal(File(page.processedImagePath), docDir, "${pagePrefix}_proc.jpg")
                val thumbDest = copyIntoDirIfExternal(File(page.thumbnailPath), docDir, "${pagePrefix}_thumb.jpg")

                keptFiles.add(origDest.absolutePath)
                keptFiles.add(procDest.absolutePath)
                keptFiles.add(thumbDest.absolutePath)

                PageEntity(
                    documentId = docId,
                    pageIndex = idx,
                    originalImagePath = origDest.absolutePath,
                    processedImagePath = procDest.absolutePath,
                    thumbnailPath = thumbDest.absolutePath,
                    quadSerialized = page.quad.serialize(),
                    wasBoundaryAutoDetected = page.wasBoundaryAutoDetected,
                    rotationDegrees = page.rotationDegrees,
                    filterType = page.filterType.name,
                    brightness = page.adjustments.brightness,
                    contrast = page.adjustments.contrast,
                    threshold = page.adjustments.threshold,
                    sharpness = page.adjustments.sharpness,
                    ocrText = page.ocrText,
                    ocrLanguage = page.ocrLanguage.name
                )
            }

            dao.deletePagesForDocument(docId)
            dao.insertPages(persistedPages)

            // Update cover thumbnail on document
            val coverPath = persistedPages.firstOrNull()?.thumbnailPath ?: ""
            val currentDoc = dao.getDocumentWithPagesOnce(docId)?.document
            if (currentDoc != null) {
                dao.updateDocument(
                    currentDoc.copy(
                        coverThumbnailPath = coverPath,
                        pageCount = persistedPages.size,
                        combinedOcrText = combinedOcr,
                        updatedAt = now
                    )
                )
            }

            // Clean up orphaned files inside docDir from removed pages
            docDir.listFiles()?.forEach { file ->
                if (file.absolutePath !in keptFiles) {
                    file.delete()
                }
            }

            docId
        }
    }

    /**
     * Converts a saved [DocumentWithPages] back into editable session pages by copying working files
     * into the session workspace so edits remain non-destructive until the user clicks Save.
     */
    suspend fun loadPagesForEditing(docWithPages: DocumentWithPages): List<EditablePage> =
        withContext(Dispatchers.IO) {
            val tmpDir = getSessionTempDir()
            docWithPages.orderedPages.map { entity ->
                val uid = UUID.randomUUID().toString()
                val origCopy = File(tmpDir, "${uid}_orig.png")
                val procCopy = File(tmpDir, "${uid}_proc.jpg")
                val thumbCopy = File(tmpDir, "${uid}_thumb.jpg")

                safeCopyFile(File(entity.originalImagePath), origCopy)
                safeCopyFile(File(entity.processedImagePath), procCopy)
                safeCopyFile(File(entity.thumbnailPath), thumbCopy)

                EditablePage(
                    id = uid,
                    originalImagePath = origCopy.absolutePath,
                    processedImagePath = procCopy.absolutePath,
                    thumbnailPath = thumbCopy.absolutePath,
                    quad = NormalizedQuad.deserialize(entity.quadSerialized),
                    wasBoundaryAutoDetected = entity.wasBoundaryAutoDetected,
                    rotationDegrees = entity.rotationDegrees,
                    filterType = runCatching { FilterType.valueOf(entity.filterType) }.getOrDefault(FilterType.ORIGINAL),
                    adjustments = FilterAdjustments(
                        brightness = entity.brightness,
                        contrast = entity.contrast,
                        threshold = entity.threshold,
                        sharpness = entity.sharpness
                    ),
                    ocrText = entity.ocrText,
                    ocrLanguage = runCatching { OcrLanguage.valueOf(entity.ocrLanguage) }.getOrDefault(OcrLanguage.ENGLISH)
                )
            }
        }

    suspend fun deleteDocument(documentId: Long) = withContext(Dispatchers.IO) {
        dao.deleteDocumentById(documentId)
        val docDir = getPermanentDocDir(documentId)
        if (docDir.exists()) {
            docDir.deleteRecursively()
        }
    }

    suspend fun clearSessionTempFiles() = withContext(Dispatchers.IO) {
        val dir = getSessionTempDir()
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun copyIntoDirIfExternal(source: File, targetDir: File, targetName: String): File {
        val dest = File(targetDir, targetName)
        if (source.absolutePath == dest.absolutePath) return dest
        safeCopyFile(source, dest)
        return dest
    }

    private fun safeCopyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (src.exists()) {
            src.copyTo(dst, overwrite = true)
        }
    }
}
