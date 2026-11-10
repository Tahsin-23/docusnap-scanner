package com.example.cv

import android.graphics.Bitmap
import com.example.model.FilterAdjustments
import com.example.model.FilterType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Real pixel-level image processing engine for document scans and ordinary photographs.
 *
 * Filters:
 * - [FilterType.ORIGINAL]: Preserves original RGB colors (used by default for Take Photo).
 * - [FilterType.ENHANCED]: Local illumination normalization via integral-image background estimation
 *   to whiten shadowed paper while preserving colored stamps, blue ink signatures, and fine text.
 * - [FilterType.BLACK_AND_WHITE]: True monochrome (#000000 / #FFFFFF) using grayscale luminance and
 *   Bradley-Roth integral-image local adaptive thresholding controlled by [FilterAdjustments.threshold].
 * - [FilterType.GRAYSCALE]: Perceptual ITU-R BT.601 luminance grayscale.
 * - [FilterType.HIGH_CONTRAST]: Strong tonal contrast stretch with crisp white clipping.
 * - [FilterType.LIGHTEN]: Gamma lift (0.72) + shadow brightening.
 * - [FilterType.DARKEN]: Gamma deepen (1.42) to make faint pencil or receipt ink darker and clearer.
 *
 * Adjustments:
 * - Brightness (-100..+100), Contrast (-100..+100), Threshold (0..100), and Sharpness (0..100).
 */
object ImageFilterEngine {

    fun applyFilterAndAdjustments(
        source: Bitmap,
        filterType: FilterType,
        adjustments: FilterAdjustments
    ): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source.copy(Bitmap.Config.ARGB_8888, false)

        // Fast-path when completely unmodified Original Color
        if (filterType == FilterType.ORIGINAL &&
            adjustments.brightness == 0f &&
            adjustments.contrast == 0f &&
            adjustments.sharpness <= 0.5f
        ) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Step 1: Apply core filter transformation
        when (filterType) {
            FilterType.ORIGINAL -> Unit
            FilterType.GRAYSCALE -> applyGrayscaleInPlace(pixels)
            FilterType.ENHANCED -> applyEnhancedDocumentInPlace(pixels, w, h)
            FilterType.BLACK_AND_WHITE -> {
                // Apply brightness/contrast before adaptive binarization so user sliders refine B&W output
                if (adjustments.brightness != 0f || adjustments.contrast != 0f) {
                    applyBrightnessContrastInPlace(pixels, adjustments.brightness, adjustments.contrast)
                }
                if (adjustments.sharpness > 1f) {
                    applyUnsharpMaskInPlace(pixels, w, h, adjustments.sharpness)
                }
                applyAdaptiveBlackAndWhiteInPlace(pixels, w, h, adjustments.threshold)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                out.setPixels(pixels, 0, w, 0, 0, w, h)
                return out
            }
            FilterType.HIGH_CONTRAST -> applyHighContrastInPlace(pixels)
            FilterType.LIGHTEN -> applyGammaInPlace(pixels, gamma = 0.70, lift = 14)
            FilterType.DARKEN -> applyGammaInPlace(pixels, gamma = 1.45, lift = -10)
        }

        // Step 2: Apply user Brightness & Contrast LUT
        if (adjustments.brightness != 0f || adjustments.contrast != 0f) {
            applyBrightnessContrastInPlace(pixels, adjustments.brightness, adjustments.contrast)
        }

        // Step 3: Apply 3x3 Unsharp Convolution for crisp small text, stamps & handwriting
        if (adjustments.sharpness > 1f) {
            applyUnsharpMaskInPlace(pixels, w, h, adjustments.sharpness)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyGrayscaleInPlace(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (77 * r + 150 * g + 29 * b) shr 8
            pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
    }

    /**
     * Normalizes uneven room illumination across a paper document using an O(1) integral-image
     * local background estimator, turning gray/yellowish paper backgrounds crisp white while
     * preserving blue/red signatures, colored stamps, and fine text.
     */
    private fun applyEnhancedDocumentInPlace(pixels: IntArray, w: Int, h: Int) {
        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = max(r, max(g, b))
        }

        val integral = buildIntegralImage(luma, w, h)
        val radius = max(14, min(w, h) / 14)

        for (y in 0 until h) {
            val y1 = (y - radius).coerceAtLeast(0)
            val y2 = (y + radius).coerceAtMost(h - 1)
            val row = y * w
            for (x in 0 until w) {
                val x1 = (x - radius).coerceAtLeast(0)
                val x2 = (x + radius).coerceAtMost(w - 1)
                val localMean = rectMean(integral, w, x1, y1, x2, y2).coerceAtLeast(48)

                val idx = row + x
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                // Normalize each RGB channel by local paper illumination & apply crisp S-curve
                val nr = stretchDocumentTone(((r * 250) / localMean).coerceIn(0, 255))
                val ng = stretchDocumentTone(((g * 250) / localMean).coerceIn(0, 255))
                val nb = stretchDocumentTone(((b * 250) / localMean).coerceIn(0, 255))

                pixels[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    private fun stretchDocumentTone(v: Int): Int {
        // Snap near-white background (>222) cleanly to pure white 255, deepen dark ink (<85)
        return when {
            v >= 226 -> 255
            v <= 38 -> (v * 0.55f).roundToInt()
            else -> (((v - 38f) / (226f - 38f)) * 234f + 21f).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Produces a true monochrome (#000000 and #FFFFFF) image using Bradley-Roth local adaptive
     * integral-image thresholding combined with a global floor controlled by [thresholdSlider] (0..100).
     */
    private fun applyAdaptiveBlackAndWhiteInPlace(
        pixels: IntArray,
        w: Int,
        h: Int,
        thresholdSlider: Float
    ) {
        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = (77 * r + 150 * g + 29 * b) shr 8
        }

        val integral = buildIntegralImage(luma, w, h)
        val radius = max(10, min(w, h) / 18)

        // Map slider 0..100 -> local sensitivity ratio (0 = very light/few blacks, 50 = balanced 0.89, 100 = very dark/more ink)
        val norm = thresholdSlider.coerceIn(0f, 100f) / 100f
        val localRatio = 0.72f + norm * 0.30f // at 50 -> 0.87f; higher norm -> higher cutoff -> more black pixels
        val globalBias = ((norm - 0.5f) * 56f).roundToInt()

        for (y in 0 until h) {
            val y1 = (y - radius).coerceAtLeast(0)
            val y2 = (y + radius).coerceAtMost(h - 1)
            val row = y * w
            for (x in 0 until w) {
                val x1 = (x - radius).coerceAtLeast(0)
                val x2 = (x + radius).coerceAtMost(w - 1)
                val localMean = rectMean(integral, w, x1, y1, x2, y2)
                val idx = row + x
                val cutoff = (localMean * localRatio + globalBias).roundToInt().coerceIn(18, 245)
                val isInk = luma[idx] < cutoff && luma[idx] < (225 + globalBias).coerceIn(60, 250)
                pixels[idx] = if (isInk) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
    }

    private fun applyHighContrastInPlace(pixels: IntArray) {
        val lut = IntArray(256) { v ->
            val centered = (v - 128) * 1.62f + 132f
            centered.roundToInt().coerceIn(0, 255)
        }
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = lut[(c shr 16) and 0xFF]
            val g = lut[(c shr 8) and 0xFF]
            val b = lut[c and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun applyGammaInPlace(pixels: IntArray, gamma: Double, lift: Int) {
        val lut = IntArray(256) { v ->
            val mapped = ((v / 255.0).pow(gamma) * 255.0).roundToInt() + lift
            mapped.coerceIn(0, 255)
        }
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = lut[(c shr 16) and 0xFF]
            val g = lut[(c shr 8) and 0xFF]
            val b = lut[c and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun applyBrightnessContrastInPlace(
        pixels: IntArray,
        brightness: Float,
        contrast: Float
    ) {
        val bShift = brightness.coerceIn(-100f, 100f) * 1.4f
        val cNorm = contrast.coerceIn(-100f, 100f)
        val cFactor = (259f * (cNorm + 255f)) / (255f * (259f - cNorm))

        val lut = IntArray(256) { v ->
            val adjusted = cFactor * (v - 128f) + 128f + bShift
            adjusted.roundToInt().coerceIn(0, 255)
        }

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = lut[(c shr 16) and 0xFF]
            val g = lut[(c shr 8) and 0xFF]
            val b = lut[c and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * 3x3 Unsharp convolution filter: [0, -k, 0; -k, 1 + 4k, -k; 0, -k, 0]
     * Enhances legibility of small text, stamps, and handwriting.
     */
    private fun applyUnsharpMaskInPlace(pixels: IntArray, w: Int, h: Int, sharpness: Float) {
        if (w < 3 || h < 3) return
        val src = pixels.copyOf()
        val k = (sharpness.coerceIn(0f, 100f) / 100f) * 0.65f
        val centerWeight = 1f + 4f * k

        for (y in 1 until h - 1) {
            val row = y * w
            val rowAbove = (y - 1) * w
            val rowBelow = (y + 1) * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = src[idx]
                val n = src[rowAbove + x]
                val s = src[rowBelow + x]
                val l = src[idx - 1]
                val rPx = src[idx + 1]

                val r = (
                    ((c shr 16) and 0xFF) * centerWeight -
                        k * (((n shr 16) and 0xFF) + ((s shr 16) and 0xFF) + ((l shr 16) and 0xFF) + ((rPx shr 16) and 0xFF))
                    ).roundToInt().coerceIn(0, 255)

                val g = (
                    ((c shr 8) and 0xFF) * centerWeight -
                        k * (((n shr 8) and 0xFF) + ((s shr 8) and 0xFF) + ((l shr 8) and 0xFF) + ((rPx shr 8) and 0xFF))
                    ).roundToInt().coerceIn(0, 255)

                val b = (
                    (c and 0xFF) * centerWeight -
                        k * ((n and 0xFF) + (s and 0xFF) + (l and 0xFF) + (rPx and 0xFF))
                    ).roundToInt().coerceIn(0, 255)

                pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun buildIntegralImage(src: IntArray, w: Int, h: Int): LongArray {
        val integral = LongArray((w + 1) * (h + 1))
        val stride = w + 1
        for (y in 1..h) {
            var rowSum = 0L
            val srcRow = (y - 1) * w
            val intRow = y * stride
            val prevIntRow = (y - 1) * stride
            for (x in 1..w) {
                rowSum += src[srcRow + x - 1]
                integral[intRow + x] = integral[prevIntRow + x] + rowSum
            }
        }
        return integral
    }

    private fun rectMean(integral: LongArray, w: Int, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val stride = w + 1
        val ax = x1
        val ay = y1
        val bx = x2 + 1
        val by = y2 + 1
        val sum = integral[by * stride + bx] -
            integral[ay * stride + bx] -
            integral[by * stride + ax] +
            integral[ay * stride + ax]
        val count = (bx - ax) * (by - ay)
        return (sum / count.coerceAtLeast(1)).toInt()
    }
}
