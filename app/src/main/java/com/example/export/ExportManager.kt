package com.example.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.cv.BitmapUtils
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.PdfOrientation
import com.example.model.PdfPageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

data class GeneratedExportResult(
    val files: List<File>,
    val mimeType: String,
    val totalSizeBytes: Long
) {
    val formattedSize: String
        get() = ExportManager.formatFileSize(totalSizeBytes)
}

/**
 * Generates standards-compliant ISO 32000 PDF 1.4 documents (embedding `/DCTDecode` JPEG image
 * streams directly for crisp text and true quality-controlled file sizes), JPG/PNG images,
 * and plain-text OCR exports. Supports saving to device Downloads and sharing via [FileProvider].
 */
object ExportManager {

    private fun getExportsDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * Generates a multi-page (or selected-pages) PDF 1.4 document embedding the actual processed
     * page images in exact order, honoring page size (A4, Letter, Original), orientation
     * (Portrait, Landscape, Auto), and JPEG compression quality.
     */
    suspend fun generatePdf(
        context: Context,
        filenameBase: String,
        processedImagePaths: List<String>,
        pageSize: PdfPageSize,
        orientation: PdfOrientation,
        qualityPreset: ExportQualityPreset,
        customJpegQuality: Int
    ): Result<GeneratedExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(processedImagePaths.isNotEmpty()) { "Select at least one page to export." }
            val safeName = sanitizeFilename(filenameBase).ifBlank { "DocuSnap_Export" }
            val outFile = File(getExportsDir(context), "$safeName.pdf")
            if (outFile.exists()) outFile.delete()

            val effectiveQuality = if (qualityPreset == ExportQualityPreset.CUSTOM) {
                customJpegQuality.coerceIn(30, 100)
            } else {
                qualityPreset.jpegQuality
            }
            val maxDim = qualityPreset.maxDimensionPx

            val pageCount = processedImagePaths.size
            val totalObjects = 2 + pageCount * 3 // 1 = Catalog, 2 = Pages, then 3 objects per page
            val offsets = LongArray(totalObjects + 1)

            FileOutputStream(outFile).use { fos ->
                var bytesWritten = 0L

                fun writeAscii(str: String) {
                    val b = str.toByteArray(Charsets.US_ASCII)
                    fos.write(b)
                    bytesWritten += b.size
                }

                fun writeRaw(bytes: ByteArray) {
                    fos.write(bytes)
                    bytesWritten += bytes.size
                }

                // PDF 1.4 Header + binary marker comment
                writeAscii("%PDF-1.4\n")
                writeRaw(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

                // 1 0 obj: Catalog
                offsets[1] = bytesWritten
                writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

                // 2 0 obj: Pages root
                val kidsRefs = (0 until pageCount).joinToString(" ") { idx -> "${3 + idx * 3} 0 R" }
                offsets[2] = bytesWritten
                writeAscii("2 0 obj\n<< /Type /Pages /Kids [$kidsRefs] /Count $pageCount >>\nendobj\n")

                // Each page: Page object, Content stream object, and DCTDecode Image XObject
                processedImagePaths.forEachIndexed { idx, imgPath ->
                    val bmp = BitmapUtils.decodeBitmapFromFile(imgPath, maxDimension = maxDim)
                        ?: throw IllegalStateException("Could not read processed image for page ${idx + 1}.")

                    val imgW = bmp.width
                    val imgH = bmp.height
                    val jpegStream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, effectiveQuality, jpegStream)
                    bmp.recycle()
                    val jpegBytes = jpegStream.toByteArray()

                    val (pageW, pageH) = computePdfPageDimensions(
                        imgW = imgW,
                        imgH = imgH,
                        pageSize = pageSize,
                        orientation = orientation
                    )

                    val margin = if (pageSize == PdfPageSize.ORIGINAL) 0f else 18f
                    val availW = (pageW - margin * 2f).coerceAtLeast(1f)
                    val availH = (pageH - margin * 2f).coerceAtLeast(1f)
                    val scale = min(availW / imgW.toFloat(), availH / imgH.toFloat())
                    val drawW = imgW * scale
                    val drawH = imgH * scale
                    val left = (pageW - drawW) * 0.5f
                    val bottom = (pageH - drawH) * 0.5f // PDF origin is bottom-left

                    val pageObjId = 3 + idx * 3
                    val contentObjId = 4 + idx * 3
                    val imageObjId = 5 + idx * 3

                    // Page dictionary
                    offsets[pageObjId] = bytesWritten
                    writeAscii(
                        "$pageObjId 0 obj\n" +
                            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] " +
                            "/Resources << /XObject << /Im0 $imageObjId 0 R >> >> " +
                            "/Contents $contentObjId 0 R >>\n" +
                            "endobj\n"
                    )

                    // Page content stream placing /Im0
                    val contentCommands = String.format(
                        Locale.US,
                        "q\n%.2f 0 0 %.2f %.2f %.2f cm\n/Im0 Do\nQ\n",
                        drawW, drawH, left, bottom
                    )
                    val contentBytes = contentCommands.toByteArray(Charsets.US_ASCII)
                    offsets[contentObjId] = bytesWritten
                    writeAscii("$contentObjId 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
                    writeRaw(contentBytes)
                    writeAscii("\nendstream\nendobj\n")

                    // Image XObject embedding compressed JPEG stream
                    offsets[imageObjId] = bytesWritten
                    writeAscii(
                        "$imageObjId 0 obj\n" +
                            "<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH " +
                            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                            "/Length ${jpegBytes.size} >>\n" +
                            "stream\n"
                    )
                    writeRaw(jpegBytes)
                    writeAscii("\nendstream\nendobj\n")
                }

                // Cross-reference table (20-byte standard entries)
                val xrefStart = bytesWritten
                writeAscii("xref\n0 ${totalObjects + 1}\n")
                writeAscii("0000000000 65535 f \n")
                for (objId in 1..totalObjects) {
                    writeAscii(String.format(Locale.US, "%010d 00000 n \n", offsets[objId]))
                }

                // Trailer
                writeAscii(
                    "trailer\n" +
                        "<< /Size ${totalObjects + 1} /Root 1 0 R >>\n" +
                        "startxref\n" +
                        "$xrefStart\n" +
                        "%%EOF\n"
                )
                fos.flush()
            }

            GeneratedExportResult(
                files = listOf(outFile),
                mimeType = ExportFormat.PDF.mimeType,
                totalSizeBytes = outFile.length()
            )
        }
    }

    /**
     * Generates exported JPG or PNG files for each selected page.
     */
    suspend fun generateImages(
        context: Context,
        filenameBase: String,
        processedImagePaths: List<String>,
        format: ExportFormat,
        qualityPreset: ExportQualityPreset,
        customJpegQuality: Int
    ): Result<GeneratedExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(processedImagePaths.isNotEmpty()) { "Select at least one page to export." }
            val safeName = sanitizeFilename(filenameBase).ifBlank { "DocuSnap_Scan" }
            val effectiveQuality = if (qualityPreset == ExportQualityPreset.CUSTOM) {
                customJpegQuality.coerceIn(30, 100)
            } else {
                qualityPreset.jpegQuality
            }
            val maxDim = qualityPreset.maxDimensionPx
            val exportsDir = getExportsDir(context)

            val outputFiles = processedImagePaths.mapIndexed { idx, imgPath ->
                val bmp = BitmapUtils.decodeBitmapFromFile(imgPath, maxDimension = maxDim)
                    ?: throw IllegalStateException("Could not decode page ${idx + 1} for export.")
                val suffix = if (processedImagePaths.size > 1) "_page_${idx + 1}" else ""
                val outFile = File(exportsDir, "$safeName$suffix.${format.extension}")
                val compressFormat = if (format == ExportFormat.PNG) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                val saved = BitmapUtils.saveBitmapToFile(
                    bitmap = bmp,
                    targetFile = outFile,
                    format = compressFormat,
                    quality = if (format == ExportFormat.PNG) 100 else effectiveQuality
                )
                bmp.recycle()
                if (!saved) throw IllegalStateException("Failed writing exported image file.")
                outFile
            }

            GeneratedExportResult(
                files = outputFiles,
                mimeType = format.mimeType,
                totalSizeBytes = outputFiles.sumOf { it.length() }
            )
        }
    }

    /**
     * Generates a downloadable/shareable UTF-8 `.txt` file containing recognized OCR text.
     */
    suspend fun generateTxtFile(
        context: Context,
        filenameBase: String,
        text: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = sanitizeFilename(filenameBase).ifBlank { "DocuSnap_OCR" }
            val outFile = File(getExportsDir(context), "${safeName}_ocr.txt")
            outFile.writeText(text, Charsets.UTF_8)
            outFile
        }
    }

    /**
     * Saves generated export file(s) directly to the user's public Downloads folder via MediaStore.
     */
    suspend fun saveFilesToDeviceDownloads(
        context: Context,
        files: List<File>,
        mimeType: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val savedNames = mutableListOf<String>()
            for (file in files) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocuSnap")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri: Uri = resolver.insert(collection, contentValues)
                        ?: throw IllegalStateException("MediaStore could not create ${file.name} in Downloads.")

                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(file).use { input ->
                            input.copyTo(out)
                        }
                    } ?: throw IllegalStateException("Failed to open output stream for ${file.name}.")

                    val completeValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, completeValues, null, null)
                    savedNames.add("Downloads/DocuSnap/${file.name}")
                } else {
                    val extDir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "DocuSnap"
                    ).apply { mkdirs() }
                    val target = File(extDir, file.name)
                    file.copyTo(target, overwrite = true)
                    savedNames.add(target.absolutePath)
                }
            }
            savedNames
        }
    }

    /**
     * Launches the Android system Share Sheet using [FileProvider].
     */
    fun shareExportedFiles(context: Context, files: List<File>, mimeType: String, subject: String) {
        if (files.isEmpty()) return
        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<Uri>(files.map { file ->
            FileProvider.getUriForFile(context, authority, file)
        })

        val shareIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val chooser = Intent.createChooser(shareIntent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun computePdfPageDimensions(
        imgW: Int,
        imgH: Int,
        pageSize: PdfPageSize,
        orientation: PdfOrientation
    ): Pair<Int, Int> {
        if (pageSize == PdfPageSize.ORIGINAL) {
            val maxPts = 1000f
            val longest = maxOf(imgW, imgH).coerceAtLeast(1)
            val ratio = if (longest > maxPts) maxPts / longest else 1f
            val w = (imgW * ratio).roundToInt().coerceAtLeast(72)
            val h = (imgH * ratio).roundToInt().coerceAtLeast(72)
            return when (orientation) {
                PdfOrientation.PORTRAIT -> if (w > h) h to w else w to h
                PdfOrientation.LANDSCAPE -> if (h > w) h to w else w to h
                PdfOrientation.AUTO -> w to h
            }
        }

        val baseW = pageSize.widthPt
        val baseH = pageSize.heightPt
        return when (orientation) {
            PdfOrientation.PORTRAIT -> baseW to baseH
            PdfOrientation.LANDSCAPE -> baseH to baseW
            PdfOrientation.AUTO -> if (imgW > imgH) baseH to baseW else baseW to baseH
        }
    }

    fun sanitizeFilename(raw: String): String {
        return raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(80)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
