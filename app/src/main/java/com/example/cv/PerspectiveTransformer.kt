package com.example.cv

import android.graphics.Bitmap
import android.graphics.Matrix
import com.example.model.NormalizedQuad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Performs exact 8-DOF projective homography (perspective correction) and rotation on Bitmaps.
 *
 * Given four arbitrary source quadrilateral corners (topLeft, topRight, bottomRight, bottomLeft),
 * solves the 3x3 projective homography H mapping destination rectangle coordinates (u, v) to
 * source coordinates (x, y) and resamples pixels via bilinear interpolation.
 */
object PerspectiveTransformer {

    /**
     * Rotates [source] by [degrees] (supports 90° increments as well as arbitrary fine angles).
     */
    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (abs(normalized) < 0.05f) return source.copy(Bitmap.Config.ARGB_8888, false)
        val matrix = Matrix().apply { postRotate(normalized) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Applies rotation followed by 4-corner perspective rectification onto a straight rectangular page.
     * Preserves true physical aspect ratio computed from the Euclidean side lengths of [quad].
     */
    fun cropAndWarp(
        source: Bitmap,
        quad: NormalizedQuad,
        rotationDegrees: Float = 0f,
        maxOutputDimension: Int = BitmapUtils.DEFAULT_MAX_DIMENSION
    ): Bitmap {
        val rotated = rotateBitmap(source, rotationDegrees)
        if (quad.isFullFrame(epsilon = 0.008f)) {
            return BitmapUtils.scaleDownIfNeeded(rotated, maxOutputDimension)
        }

        val srcW = rotated.width.toFloat()
        val srcH = rotated.height.toFloat()

        val tlX = quad.topLeft.x * (srcW - 1f)
        val tlY = quad.topLeft.y * (srcH - 1f)
        val trX = quad.topRight.x * (srcW - 1f)
        val trY = quad.topRight.y * (srcH - 1f)
        val brX = quad.bottomRight.x * (srcW - 1f)
        val brY = quad.bottomRight.y * (srcH - 1f)
        val blX = quad.bottomLeft.x * (srcW - 1f)
        val blY = quad.bottomLeft.y * (srcH - 1f)

        val widthTop = hypot(trX - tlX, trY - tlY)
        val widthBottom = hypot(brX - blX, brY - blY)
        val heightLeft = hypot(blX - tlX, blY - tlY)
        val heightRight = hypot(brX - trX, brY - trY)

        var dstW = max(widthTop, widthBottom).roundToInt().coerceAtLeast(32)
        var dstH = max(heightLeft, heightRight).roundToInt().coerceAtLeast(32)

        val longest = max(dstW, dstH)
        if (longest > maxOutputDimension) {
            val scale = maxOutputDimension.toFloat() / longest.toFloat()
            dstW = (dstW * scale).roundToInt().coerceAtLeast(32)
            dstH = (dstH * scale).roundToInt().coerceAtLeast(32)
        }

        // Solve inverse homography H mapping destination (u, v) -> source (x, y)
        val dstPts = doubleArrayOf(
            0.0, 0.0,
            (dstW - 1).toDouble(), 0.0,
            (dstW - 1).toDouble(), (dstH - 1).toDouble(),
            0.0, (dstH - 1).toDouble()
        )
        val srcPts = doubleArrayOf(
            tlX.toDouble(), tlY.toDouble(),
            trX.toDouble(), trY.toDouble(),
            brX.toDouble(), brY.toDouble(),
            blX.toDouble(), blY.toDouble()
        )

        val h = solveHomography(dstPts, srcPts)
            ?: return BitmapUtils.scaleDownIfNeeded(rotated, maxOutputDimension)

        val sw = rotated.width
        val sh = rotated.height
        val srcPixels = IntArray(sw * sh)
        rotated.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)
        if (rotated !== source) rotated.recycle()

        val dstPixels = IntArray(dstW * dstH)
        val h00 = h[0]
        val h01 = h[1]
        val h02 = h[2]
        val h10 = h[3]
        val h11 = h[4]
        val h12 = h[5]
        val h20 = h[6]
        val h21 = h[7]

        val maxSrcX = (sw - 1).toDouble()
        val maxSrcY = (sh - 1).toDouble()

        for (v in 0 until dstH) {
            val vd = v.toDouble()
            var numX = h01 * vd + h02
            var numY = h11 * vd + h12
            var den = h21 * vd + 1.0
            val rowOffset = v * dstW

            for (u in 0 until dstW) {
                val invDen = if (abs(den) > 1e-9) 1.0 / den else 1.0
                val sx = (numX * invDen).coerceIn(0.0, maxSrcX)
                val sy = (numY * invDen).coerceIn(0.0, maxSrcY)

                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = (x0 + 1).coerceAtMost(sw - 1)
                val y1 = (y0 + 1).coerceAtMost(sh - 1)

                val fx = (sx - x0).toFloat()
                val fy = (sy - y0).toFloat()
                val oneMinusFx = 1f - fx
                val oneMinusFy = 1f - fy

                val w00 = oneMinusFx * oneMinusFy
                val w10 = fx * oneMinusFy
                val w01 = oneMinusFx * fy
                val w11 = fx * fy

                val c00 = srcPixels[y0 * sw + x0]
                val c10 = srcPixels[y0 * sw + x1]
                val c01 = srcPixels[y1 * sw + x0]
                val c11 = srcPixels[y1 * sw + x1]

                val r = (
                    ((c00 shr 16) and 0xFF) * w00 +
                        ((c10 shr 16) and 0xFF) * w10 +
                        ((c01 shr 16) and 0xFF) * w01 +
                        ((c11 shr 16) and 0xFF) * w11
                    ).roundToInt().coerceIn(0, 255)

                val g = (
                    ((c00 shr 8) and 0xFF) * w00 +
                        ((c10 shr 8) and 0xFF) * w10 +
                        ((c01 shr 8) and 0xFF) * w01 +
                        ((c11 shr 8) and 0xFF) * w11
                    ).roundToInt().coerceIn(0, 255)

                val b = (
                    (c00 and 0xFF) * w00 +
                        (c10 and 0xFF) * w10 +
                        (c01 and 0xFF) * w01 +
                        (c11 and 0xFF) * w11
                    ).roundToInt().coerceIn(0, 255)

                dstPixels[rowOffset + u] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                numX += h00
                numY += h10
                den += h20
            }
        }

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        out.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        return out
    }

    /**
     * Solves 8x8 linear system A * h = b for 2D projective homography parameters
     * [h00, h01, h02, h10, h11, h12, h20, h21] with h22 = 1.0 using Gaussian elimination.
     */
    private fun solveHomography(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val u = src[2 * i]
            val v = src[2 * i + 1]
            val x = dst[2 * i]
            val y = dst[2 * i + 1]

            val r1 = 2 * i
            a[r1][0] = u
            a[r1][1] = v
            a[r1][2] = 1.0
            a[r1][3] = 0.0
            a[r1][4] = 0.0
            a[r1][5] = 0.0
            a[r1][6] = -u * x
            a[r1][7] = -v * x
            a[r1][8] = x

            val r2 = 2 * i + 1
            a[r2][0] = 0.0
            a[r2][1] = 0.0
            a[r2][2] = 0.0
            a[r2][3] = u
            a[r2][4] = v
            a[r2][5] = 1.0
            a[r2][6] = -u * y
            a[r2][7] = -v * y
            a[r2][8] = y
        }

        // Gaussian elimination with partial pivoting
        for (col in 0 until 8) {
            var pivot = col
            var maxAbs = abs(a[col][col])
            for (row in col + 1 until 8) {
                val v = abs(a[row][col])
                if (v > maxAbs) {
                    maxAbs = v
                    pivot = row
                }
            }
            if (maxAbs < 1e-11) return null
            if (pivot != col) {
                val tmp = a[col]
                a[col] = a[pivot]
                a[pivot] = tmp
            }

            val diag = a[col][col]
            for (j in col..8) a[col][j] /= diag

            for (row in 0 until 8) {
                if (row == col) continue
                val factor = a[row][col]
                if (abs(factor) < 1e-13) continue
                for (j in col..8) {
                    a[row][j] -= factor * a[col][j]
                }
            }
        }

        return DoubleArray(8) { idx -> a[idx][8] }
    }
}
