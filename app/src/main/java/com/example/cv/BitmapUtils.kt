package com.example.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * High-precision, EXIF-aware bitmap loading and file persistence utilities.
 * Preserves high resolution for legible text while safeguarding against OutOfMemoryError.
 */
object BitmapUtils {

    const val DEFAULT_MAX_DIMENSION = 2400
    const val PREVIEW_MAX_DIMENSION = 1100
    const val THUMBNAIL_MAX_DIMENSION = 360

    /**
     * Decodes a [Bitmap] from a content [Uri] respecting EXIF orientation without unnecessary recompression.
     */
    fun decodeBitmapFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                maxDimension
            )
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            val exifRotation = context.contentResolver.openInputStream(uri)?.use { stream ->
                readExifMatrix(ExifInterface(stream))
            }

            val oriented = if (exifRotation != null && !exifRotation.isIdentity) {
                val rotated = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, exifRotation, true
                )
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }

            scaleDownIfNeeded(oriented, maxDimension)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes a [Bitmap] from a local [File] respecting EXIF orientation.
     */
    fun decodeBitmapFromFile(
        path: String,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                maxDimension
            )
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

            val exifMatrix = try {
                readExifMatrix(ExifInterface(file.absolutePath))
            } catch (_: Exception) {
                null
            }

            val oriented = if (exifMatrix != null && !exifMatrix.isIdentity) {
                val rotated = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, exifMatrix, true
                )
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }

            scaleDownIfNeeded(oriented, maxDimension)
        } catch (_: Exception) {
            null
        }
    }

    fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val newW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun createThumbnail(source: Bitmap, maxDimension: Int = THUMBNAIL_MAX_DIMENSION): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxDimension) return source.copy(Bitmap.Config.ARGB_8888, false)
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val newW = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val newH = (source.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }

    /**
     * Saves a [Bitmap] losslessly as PNG (ideal for preserving original captures without generation loss)
     * or JPEG with configurable quality.
     */
    fun saveBitmapToFile(
        bitmap: Bitmap,
        targetFile: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 95
    ): Boolean {
        return try {
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { out ->
                bitmap.compress(format, quality.coerceIn(1, 100), out)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val longest = max(width, height)
        if (longest > maxDimension) {
            while ((longest / (inSampleSize * 2)) >= maxDimension) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun readExifMatrix(exif: ExifInterface): Matrix {
        val matrix = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
        }
        return matrix
    }
}
