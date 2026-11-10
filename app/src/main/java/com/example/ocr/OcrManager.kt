package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.cv.BitmapUtils
import com.example.model.OcrLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional local-only OCR Text Recognition manager supporting English and Bangla (বাংলা).
 *
 * - English (`OcrLanguage.ENGLISH`): Uses bundled offline Google ML Kit Text Recognition immediately
 *   with zero download required.
 * - Bangla (`OcrLanguage.BANGLA`) & Combined (`OcrLanguage.ENGLISH_BANGLA`): Uses native offline
 *   Tesseract 4 LSTM (`TessBaseAPI` via `tesseract4android`). Downloads `ben.traineddata` (and
 *   `eng.traineddata` for combined mode) once from official `tesseract-ocr/tessdata_fast` into
 *   `filesDir/tessdata/` when requested by the user, then runs 100% locally on-device.
 * - Never uploads user scans anywhere and never mutates the original scan image.
 */
class OcrManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val tessRootDir: File
        get() = File(context.filesDir, "tesseract").apply { mkdirs() }

    private val tessDataDir: File
        get() = File(tessRootDir, "tessdata").apply { mkdirs() }

    fun isLanguageDataReady(language: OcrLanguage): Boolean {
        return when (language) {
            OcrLanguage.ENGLISH -> true // Bundled offline via ML Kit
            OcrLanguage.BANGLA -> hasTrainedData("ben")
            OcrLanguage.ENGLISH_BANGLA -> hasTrainedData("ben") && hasTrainedData("eng")
        }
    }

    fun getDownloadedLanguageSummary(): Map<String, Boolean> {
        return mapOf(
            "English (Bundled ML Kit)" to true,
            "Bangla / বাংলা (ben.traineddata)" to hasTrainedData("ben"),
            "English Tesseract (eng.traineddata)" to hasTrainedData("eng")
        )
    }

    private fun hasTrainedData(code: String): Boolean {
        val file = File(tessDataDir, "$code.traineddata")
        return file.exists() && file.length() > 100_000L
    }

    /**
     * Downloads required `.traineddata` language pack(s) from official Tesseract `tessdata_fast`
     * with live progress reporting [0f..1f].
     */
    suspend fun downloadLanguageData(
        language: OcrLanguage,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val codesToFetch = when (language) {
                OcrLanguage.ENGLISH -> emptyList()
                OcrLanguage.BANGLA -> listOf("ben").filter { !hasTrainedData(it) }
                OcrLanguage.ENGLISH_BANGLA -> listOf("ben", "eng").filter { !hasTrainedData(it) }
            }
            if (codesToFetch.isEmpty()) {
                onProgress(1f)
                return@runCatching
            }

            codesToFetch.forEachIndexed { index, code ->
                val url = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/$code.traineddata"
                val tmpFile = File(tessDataDir, "$code.traineddata.tmp")
                val destFile = File(tessDataDir, "$code.traineddata")

                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${response.code}). Check internet connection and retry.")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty language pack response.")
                    val totalBytes = body.contentLength().coerceAtLeast(1L)
                    var readSoFar = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                readSoFar += read
                                val fileFrac = (readSoFar.toFloat() / totalBytes).coerceIn(0f, 1f)
                                val overall = (index + fileFrac) / codesToFetch.size
                                onProgress(overall)
                            }
                            output.flush()
                        }
                    }
                }
                if (destFile.exists()) destFile.delete()
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
            }
            onProgress(1f)
        }
    }

    /**
     * Extracts text from a local image path using the chosen [OcrLanguage] without modifying the scan.
     */
    suspend fun recognizeTextFromFile(
        imagePath: String,
        language: OcrLanguage
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = BitmapUtils.decodeBitmapFromFile(imagePath, maxDimension = 2000)
                ?: throw IllegalStateException("Unable to load page image for OCR.")
            try {
                when (language) {
                    OcrLanguage.ENGLISH -> recognizeWithMlKit(bitmap)
                    OcrLanguage.BANGLA -> recognizeWithTesseract(bitmap, "ben")
                    OcrLanguage.ENGLISH_BANGLA -> recognizeWithTesseract(bitmap, "eng+ben")
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun recognizeWithMlKit(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    recognizer.close()
                    if (cont.isActive) cont.resume(visionText.text.trim())
                }
                .addOnFailureListener { e ->
                    recognizer.close()
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private fun recognizeWithTesseract(bitmap: Bitmap, langSpec: String): String {
        val codes = langSpec.split("+")
        for (c in codes) {
            if (!hasTrainedData(c)) {
                throw IllegalStateException("Language data '$c.traineddata' is not downloaded yet. Tap 'Download Language Data' first.")
            }
        }
        val tess = TessBaseAPI()
        return try {
            val initialized = tess.init(tessRootDir.absolutePath, langSpec)
            if (!initialized) {
                throw IllegalStateException("Could not initialize offline OCR engine for '$langSpec'.")
            }
            val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            tess.setImage(argbBitmap)
            val text = tess.utF8Text?.trim().orEmpty()
            if (argbBitmap !== bitmap) argbBitmap.recycle()
            text
        } finally {
            runCatching { tess.recycle() }
        }
    }

    suspend fun clearDownloadedLanguagePacks() = withContext(Dispatchers.IO) {
        tessDataDir.listFiles()?.forEach { it.delete() }
    }
}
