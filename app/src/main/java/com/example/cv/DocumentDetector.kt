package com.example.cv

import android.graphics.Bitmap
import com.example.model.DetectionResult
import com.example.model.NormalizedQuad
import com.example.model.Point2D
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Genuine computer-vision document boundary detector.
 *
 * Pipeline overview:
 * 1. Downsamples input to a fast analysis resolution (~300px longest side) while preserving aspect ratio.
 * 2. Computes luminance and applies a separable 5x5 Gaussian smoothing kernel ([1, 4, 6, 4, 1] / 16)
 *    to suppress paper grain and text characters while keeping outer document edges sharp.
 * 3. Computes 3x3 Sobel gradients (Gx, Gy) and gradient magnitude map.
 * 4. Evaluates two complementary quadrilateral hypothesis generators:
 *    - Strategy A (Otsu Sheet Segmentation + Connected Component + Convex Hull + 4-Corner Fit):
 *      Segments high-contrast foreground sheets against darker or brighter backgrounds, extracts the
 *      largest connected component, builds its Monotone Chain Convex Hull, and extracts the optimal
 *      4-corner bounding quadrilateral via RDP simplification and hull line intersection.
 *    - Strategy B (Outer Edge Ray-Cast + Deterministic Hough/RANSAC 4-Line Intersection):
 *      Casts scan rays inward from all 4 image edges to locate strong outer gradient peaks, fits
 *      robust lines to Top, Right, Bottom, and Left borders, and intersects adjacent lines.
 * 5. Validates every candidate strictly for convexity, area (6%..95%), interior angles (40°..140°),
 *    and directional Sobel edge alignment along all 4 sides.
 * 6. Never fabricates a result: returns [DetectionResult.NONE] if no genuine document boundary exists.
 */
object DocumentDetector {

    private const val WORKING_MAX_DIM = 300
    private const val MIN_CONFIDENCE = 0.50f

    /**
     * Detects 4 document corners on a full [Bitmap].
     */
    fun detect(bitmap: Bitmap): DetectionResult {
        if (bitmap.width < 32 || bitmap.height < 32) return DetectionResult.NONE
        val longest = max(bitmap.width, bitmap.height)
        val scale = if (longest > WORKING_MAX_DIM) WORKING_MAX_DIM.toFloat() / longest else 1f
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(32)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(32)

        val workBmp = if (w != bitmap.width || h != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }

        val pixels = IntArray(w * h)
        workBmp.getPixels(pixels, 0, w, 0, 0, w, h)
        if (workBmp !== bitmap) workBmp.recycle()

        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = (77 * r + 150 * g + 29 * b) shr 8
        }

        return detectOnLuminanceGrid(luma, w, h)
    }

    /**
     * Detects 4 document corners directly from a CameraX YUV_420_888 Y-plane buffer,
     * applying [rotationDegrees] (0, 90, 180, 270) so coordinates match upright display orientation.
     */
    fun detectFromYPlane(
        yBytes: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int
    ): DetectionResult {
        if (srcWidth < 32 || srcHeight < 32) return DetectionResult.NONE
        val step = max(1, max(srcWidth, srcHeight) / WORKING_MAX_DIM)
        val sampledW = srcWidth / step
        val sampledH = srcHeight / step
        if (sampledW < 24 || sampledH < 24) return DetectionResult.NONE

        val normalizedRot = ((rotationDegrees % 360) + 360) % 360
        val outW = if (normalizedRot == 90 || normalizedRot == 270) sampledH else sampledW
        val outH = if (normalizedRot == 90 || normalizedRot == 270) sampledW else sampledH
        val luma = IntArray(outW * outH)

        for (sy in 0 until sampledH) {
            val srcY = sy * step
            val rowOffset = srcY * rowStride
            for (sx in 0 until sampledW) {
                val idx = rowOffset + (sx * step) * pixelStride
                val yVal = if (idx in yBytes.indices) (yBytes[idx].toInt() and 0xFF) else 0
                val (dx, dy) = when (normalizedRot) {
                    90 -> (sampledH - 1 - sy) to sx
                    180 -> (sampledW - 1 - sx) to (sampledH - 1 - sy)
                    270 -> sy to (sampledW - 1 - sx)
                    else -> sx to sy
                }
                luma[dy * outW + dx] = yVal
            }
        }

        return detectOnLuminanceGrid(luma, outW, outH)
    }

    private fun detectOnLuminanceGrid(luma: IntArray, w: Int, h: Int): DetectionResult {
        val blurred = gaussianBlur5x5(luma, w, h)

        // Compute Sobel gradients Gx, Gy, and Magnitude
        val gxArr = FloatArray(w * h)
        val gyArr = FloatArray(w * h)
        val magArr = FloatArray(w * h)
        var maxMag = 1f

        for (y in 1 until h - 1) {
            val r0 = (y - 1) * w
            val r1 = y * w
            val r2 = (y + 1) * w
            for (x in 1 until w - 1) {
                val tl = blurred[r0 + x - 1]
                val tc = blurred[r0 + x]
                val tr = blurred[r0 + x + 1]
                val ml = blurred[r1 + x - 1]
                val mr = blurred[r1 + x + 1]
                val bl = blurred[r2 + x - 1]
                val bc = blurred[r2 + x]
                val br = blurred[r2 + x + 1]

                val gx = ((tr + 2 * mr + br) - (tl + 2 * ml + bl)).toFloat()
                val gy = ((bl + 2 * bc + br) - (tl + 2 * tc + tr)).toFloat()
                val mag = sqrt(gx * gx + gy * gy)
                val idx = r1 + x
                gxArr[idx] = gx
                gyArr[idx] = gy
                magArr[idx] = mag
                if (mag > maxMag) maxMag = mag
            }
        }

        // Reject near-uniform / blank scenes immediately
        if (maxMag < 38f) return DetectionResult.NONE

        val edgeThreshold = computeAdaptiveEdgeThreshold(magArr, w, h)
        val candidates = mutableListOf<NormalizedQuad>()

        // Strategy A1: Bright paper on darker background via Otsu threshold
        val otsuT = computeOtsuThreshold(blurred)
        extractForegroundQuad(blurred, w, h, threshold = otsuT, brightForeground = true)?.let {
            candidates.add(it)
        }
        // Strategy A2: Darker document/card on bright background via Otsu threshold
        extractForegroundQuad(blurred, w, h, threshold = otsuT, brightForeground = false)?.let {
            candidates.add(it)
        }

        // Strategy B: Outer ray-cast + RANSAC 4-side line intersection
        extractRayCastRansacQuad(magArr, gxArr, gyArr, w, h, edgeThreshold)?.let {
            candidates.add(it)
        }

        var bestQuad: NormalizedQuad? = null
        var bestScore = 0f

        for (rawCandidate in candidates) {
            val quad = rawCandidate.clamp()
            val score = scoreCandidateQuad(quad, magArr, gxArr, gyArr, w, h, edgeThreshold)
            if (score > bestScore) {
                bestScore = score
                bestQuad = quad
            }
        }

        return if (bestQuad != null && bestScore >= MIN_CONFIDENCE) {
            DetectionResult(
                quad = bestQuad,
                confidence = bestScore.coerceIn(0f, 1f),
                isDetected = true
            )
        } else {
            DetectionResult.NONE
        }
    }

    /**
     * Separable 5x5 Gaussian kernel [1, 4, 6, 4, 1] / 16.
     */
    private fun gaussianBlur5x5(src: IntArray, w: Int, h: Int): IntArray {
        val temp = IntArray(w * h)
        val dst = IntArray(w * h)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = (x - 2).coerceIn(0, w - 1)
                val x1 = (x - 1).coerceIn(0, w - 1)
                val x2 = x
                val x3 = (x + 1).coerceIn(0, w - 1)
                val x4 = (x + 2).coerceIn(0, w - 1)
                temp[row + x] = (src[row + x0] + 4 * src[row + x1] + 6 * src[row + x2] + 4 * src[row + x3] + src[row + x4]) shr 4
            }
        }

        for (y in 0 until h) {
            val y0 = (y - 2).coerceIn(0, h - 1) * w
            val y1 = (y - 1).coerceIn(0, h - 1) * w
            val y2 = y * w
            val y3 = (y + 1).coerceIn(0, h - 1) * w
            val y4 = (y + 2).coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                dst[y2 + x] = (temp[y0 + x] + 4 * temp[y1 + x] + 6 * temp[y2 + x] + 4 * temp[y3 + x] + temp[y4 + x]) shr 4
            }
        }
        return dst
    }

    /**
     * Standard Otsu's method maximizing between-class variance across 256 luminance bins.
     */
    private fun computeOtsuThreshold(luma: IntArray): Int {
        val hist = IntArray(256)
        for (v in luma) hist[v.coerceIn(0, 255)]++
        val total = luma.size
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]

        var sumB = 0L
        var wB = 0
        var maxVar = -1.0
        var bestT = 128

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val diff = mB - mF
            val between = wB.toDouble() * wF.toDouble() * diff * diff
            if (between > maxVar) {
                maxVar = between
                bestT = t
            }
        }
        return bestT
    }

    private fun computeAdaptiveEdgeThreshold(mag: FloatArray, w: Int, h: Int): Float {
        // Compute 86th percentile of interior gradient magnitudes
        val sampleCount = ((w - 4) * (h - 4)).coerceAtLeast(1)
        val sorted = FloatArray(sampleCount)
        var k = 0
        for (y in 2 until h - 2) {
            val row = y * w
            for (x in 2 until w - 2) {
                if (k < sorted.size) sorted[k++] = mag[row + x]
            }
        }
        sorted.sort(0, k)
        val p86 = sorted[(k * 0.86f).toInt().coerceIn(0, k - 1)]
        return max(32f, p86 * 0.72f)
    }

    /**
     * Segments foreground mask, extracts the largest connected component, builds its Convex Hull,
     * and fits a 4-vertex quadrilateral.
     */
    private fun extractForegroundQuad(
        blurred: IntArray,
        w: Int,
        h: Int,
        threshold: Int,
        brightForeground: Boolean
    ): NormalizedQuad? {
        val mask = BooleanArray(w * h)
        for (i in blurred.indices) {
            mask[i] = if (brightForeground) blurred[i] >= threshold else blurred[i] <= threshold
        }

        // BFS to find largest connected component in interior
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var bestComponentBoundary = emptyList<Point2D>()
        var bestArea = 0

        for (startY in 2 until h - 2) {
            for (startX in 2 until w - 2) {
                val startIdx = startY * w + startX
                if (!mask[startIdx] || visited[startIdx]) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                visited[startIdx] = true
                var area = 0
                val boundaryPts = ArrayList<Point2D>(256)

                while (head < tail) {
                    val curr = queue[head++]
                    area++
                    val cy = curr / w
                    val cx = curr - cy * w

                    var isBoundary = (cx == 0 || cx == w - 1 || cy == 0 || cy == h - 1)
                    // 4-connected neighbors
                    val neighbors = intArrayOf(
                        if (cx > 0) curr - 1 else -1,
                        if (cx < w - 1) curr + 1 else -1,
                        if (cy > 0) curr - w else -1,
                        if (cy < h - 1) curr + w else -1
                    )
                    for (nb in neighbors) {
                        if (nb == -1 || !mask[nb]) {
                            isBoundary = true
                        } else if (!visited[nb]) {
                            visited[nb] = true
                            queue[tail++] = nb
                        }
                    }
                    if (isBoundary) {
                        boundaryPts.add(Point2D(cx.toFloat() / (w - 1), cy.toFloat() / (h - 1)))
                    }
                }

                val areaFrac = area.toFloat() / (w * h)
                if (area > bestArea && areaFrac in 0.06f..0.95f && boundaryPts.size >= 16) {
                    bestArea = area
                    bestComponentBoundary = boundaryPts
                }
            }
        }

        if (bestComponentBoundary.size < 16) return null
        val hull = convexHull(bestComponentBoundary)
        if (hull.size < 4) return null

        return fitQuadFromConvexHull(hull)
    }

    /**
     * Monotone chain O(N log N) convex hull algorithm.
     */
    private fun convexHull(points: List<Point2D>): List<Point2D> {
        val sorted = points.sortedWith(compareBy<Point2D> { it.x }.thenBy { it.y })
        if (sorted.size <= 3) return sorted

        val lower = ArrayList<Point2D>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }

        val upper = ArrayList<Point2D>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }

        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: Point2D, a: Point2D, b: Point2D): Float {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    /**
     * Extracts 4 corners from a convex hull using Ramer–Douglas–Peucker polygon approximation,
     * falling back to maximum-area 4-vertex selection on the simplified hull.
     */
    private fun fitQuadFromConvexHull(hull: List<Point2D>): NormalizedQuad? {
        var perimeter = 0f
        for (i in hull.indices) {
            perimeter += hull[i].distanceTo(hull[(i + 1) % hull.size])
        }

        // Binary search / sweep epsilon for RDP to hit 4 vertices cleanly
        val closed = hull + hull.first()
        var bestSimplified = hull
        for (factor in listOf(0.015f, 0.022f, 0.03f, 0.04f, 0.055f, 0.075f)) {
            val simplified = rdpSimplify(closed, factor * perimeter).dropLast(1)
            if (simplified.size == 4) {
                return NormalizedQuad.fromUnorderedPoints(simplified)
            }
            if (simplified.size in 5..16) {
                bestSimplified = simplified
            }
        }

        // Select the 4 vertices on bestSimplified (capped at 20 points) that maximize quadrilateral area
        val pts = if (bestSimplified.size > 20) {
            val step = bestSimplified.size.toFloat() / 20f
            List(20) { idx -> bestSimplified[(idx * step).toInt()] }
        } else {
            bestSimplified
        }
        if (pts.size < 4) return null

        var maxArea = 0f
        var bestFour: List<Point2D>? = null
        val n = pts.size
        for (i in 0 until n - 3) {
            for (j in i + 1 until n - 2) {
                for (k in j + 1 until n - 1) {
                    for (m in k + 1 until n) {
                        val candidate = listOf(pts[i], pts[j], pts[k], pts[m])
                        val area = quadArea(candidate[0], candidate[1], candidate[2], candidate[3])
                        if (area > maxArea) {
                            maxArea = area
                            bestFour = candidate
                        }
                    }
                }
            }
        }

        return bestFour?.let { NormalizedQuad.fromUnorderedPoints(it) }
    }

    private fun rdpSimplify(points: List<Point2D>, epsilon: Float): List<Point2D> {
        if (points.size <= 2) return points
        var maxDist = 0f
        var index = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until points.size - 1) {
            val d = pointToSegmentDistance(points[i], start, end)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }

        return if (maxDist > epsilon) {
            val left = rdpSimplify(points.subList(0, index + 1), epsilon)
            val right = rdpSimplify(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun pointToSegmentDistance(p: Point2D, a: Point2D, b: Point2D): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-8f) return p.distanceTo(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }

    private fun quadArea(a: Point2D, b: Point2D, c: Point2D, d: Point2D): Float {
        return 0.5f * abs(
            (a.x * b.y - b.x * a.y) +
                (b.x * c.y - c.x * b.y) +
                (c.x * d.y - d.x * c.y) +
                (d.x * a.y - a.x * d.y)
        )
    }

    /**
     * Strategy B: Casts rays inward from Top, Bottom, Left, and Right borders to detect the
     * first strong gradient edge points, fits 4 lines via deterministic RANSAC, and intersects them.
     */
    private fun extractRayCastRansacQuad(
        mag: FloatArray,
        gx: FloatArray,
        gy: FloatArray,
        w: Int,
        h: Int,
        edgeThresh: Float
    ): NormalizedQuad? {
        val topPts = ArrayList<Point2D>()
        val bottomPts = ArrayList<Point2D>()
        val leftPts = ArrayList<Point2D>()
        val rightPts = ArrayList<Point2D>()

        val marginX = max(3, w / 28)
        val marginY = max(3, h / 28)
        val rayStepX = max(2, (w - 2 * marginX) / 36)
        val rayStepY = max(2, (h - 2 * marginY) / 36)

        // Top & Bottom vertical scan rays
        var x = marginX
        while (x < w - marginX) {
            for (y in marginY until (h * 0.48f).toInt()) {
                val idx = y * w + x
                if (mag[idx] >= edgeThresh && abs(gy[idx]) >= abs(gx[idx]) * 0.55f) {
                    topPts.add(Point2D(x.toFloat() / (w - 1), y.toFloat() / (h - 1)))
                    break
                }
            }
            for (y in (h - 1 - marginY) downTo (h * 0.52f).toInt()) {
                val idx = y * w + x
                if (mag[idx] >= edgeThresh && abs(gy[idx]) >= abs(gx[idx]) * 0.55f) {
                    bottomPts.add(Point2D(x.toFloat() / (w - 1), y.toFloat() / (h - 1)))
                    break
                }
            }
            x += rayStepX
        }

        // Left & Right horizontal scan rays
        var y = marginY
        while (y < h - marginY) {
            for (rx in marginX until (w * 0.48f).toInt()) {
                val idx = y * w + rx
                if (mag[idx] >= edgeThresh && abs(gx[idx]) >= abs(gy[idx]) * 0.55f) {
                    leftPts.add(Point2D(rx.toFloat() / (w - 1), y.toFloat() / (h - 1)))
                    break
                }
            }
            for (rx in (w - 1 - marginX) downTo (w * 0.52f).toInt()) {
                val idx = y * w + rx
                if (mag[idx] >= edgeThresh && abs(gx[idx]) >= abs(gy[idx]) * 0.55f) {
                    rightPts.add(Point2D(rx.toFloat() / (w - 1), y.toFloat() / (h - 1)))
                    break
                }
            }
            y += rayStepY
        }

        val topLine = fitLineRansac(topPts) ?: return null
        val bottomLine = fitLineRansac(bottomPts) ?: return null
        val leftLine = fitLineRansac(leftPts) ?: return null
        val rightLine = fitLineRansac(rightPts) ?: return null

        val tl = intersectLines(topLine, leftLine) ?: return null
        val tr = intersectLines(topLine, rightLine) ?: return null
        val br = intersectLines(bottomLine, rightLine) ?: return null
        val bl = intersectLines(bottomLine, leftLine) ?: return null

        val pts = listOf(tl, tr, br, bl)
        if (pts.any { it.x !in -0.05f..1.05f || it.y !in -0.05f..1.05f }) return null
        return NormalizedQuad.fromUnorderedPoints(pts.map { it.clamp() })
    }

    private data class Line2D(val a: Float, val b: Float, val c: Float) // ax + by + c = 0, sqrt(a^2+b^2)=1

    private fun fitLineRansac(pts: List<Point2D>): Line2D? {
        if (pts.size < 6) return null
        var bestInliers = 0
        var bestLine: Line2D? = null
        val inlierTol = 0.018f
        val n = pts.size

        // Deterministic stride pairs across sorted ray points
        for (i in 0 until n - 2) {
            for (j in i + 2 until n step max(1, n / 8)) {
                val p1 = pts[i]
                val p2 = pts[j]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val len = hypot(dx, dy)
                if (len < 0.12f) continue
                val a = -dy / len
                val b = dx / len
                val c = -(a * p1.x + b * p1.y)

                var inliers = 0
                for (p in pts) {
                    if (abs(a * p.x + b * p.y + c) <= inlierTol) inliers++
                }
                if (inliers > bestInliers) {
                    bestInliers = inliers
                    bestLine = Line2D(a, b, c)
                }
            }
        }
        return if (bestInliers >= max(5, (pts.size * 0.42f).toInt())) bestLine else null
    }

    private fun intersectLines(l1: Line2D, l2: Line2D): Point2D? {
        val det = l1.a * l2.b - l2.a * l1.b
        if (abs(det) < 1e-4f) return null
        val x = (l1.b * l2.c - l2.b * l1.c) / det
        val y = (l2.a * l1.c - l1.a * l2.c) / det
        return Point2D(x, y)
    }

    /**
     * Validates quadrilateral geometry and measures how strongly its 4 edges align with actual
     * Sobel edge magnitudes and perpendicular gradient normals in the image.
     */
    private fun scoreCandidateQuad(
        quad: NormalizedQuad,
        mag: FloatArray,
        gx: FloatArray,
        gy: FloatArray,
        w: Int,
        h: Int,
        edgeThresh: Float
    ): Float {
        if (!quad.isConvex()) return 0f
        val area = quad.areaFraction()
        if (area !in 0.06f..0.95f) return 0f

        // Reject if practically identical to the raw image border itself (border artifact)
        if (quad.isFullFrame(epsilon = 0.015f)) return 0f

        val (minAngle, maxAngle) = quad.interiorAngleRangeDegrees()
        if (minAngle < 40f || maxAngle > 142f) return 0f

        val corners = quad.toList()
        var totalSupport = 0f
        var supportedSides = 0
        val samplesPerSide = 36

        for (side in 0 until 4) {
            val p1 = corners[side]
            val p2 = corners[(side + 1) % 4]
            val edgeDx = (p2.x - p1.x) * w
            val edgeDy = (p2.y - p1.y) * h
            val edgeLen = hypot(edgeDx, edgeDy)
            if (edgeLen < min(w, h) * 0.14f) return 0f

            // Expected unit normal perpendicular to edge
            val nx = -edgeDy / edgeLen
            val ny = edgeDx / edgeLen

            var hits = 0
            for (s in 2 until samplesPerSide - 2) {
                val t = s.toFloat() / samplesPerSide
                val cx = (p1.x + t * (p2.x - p1.x)) * (w - 1)
                val cy = (p1.y + t * (p2.y - p1.y)) * (h - 1)

                var matched = false
                for (offset in -2..2) {
                    val px = (cx + offset * nx).roundToInt().coerceIn(1, w - 2)
                    val py = (cy + offset * ny).roundToInt().coerceIn(1, h - 2)
                    val idx = py * w + px
                    val m = mag[idx]
                    if (m >= edgeThresh * 0.75f) {
                        val align = abs(gx[idx] * nx + gy[idx] * ny) / (m + 1e-4f)
                        if (align >= 0.62f) {
                            matched = true
                            break
                        }
                    }
                }
                if (matched) hits++
            }

            val sideRatio = hits.toFloat() / (samplesPerSide - 4)
            totalSupport += sideRatio
            if (sideRatio >= 0.44f) supportedSides++
        }

        if (supportedSides < 3) return 0f
        val avgEdgeSupport = totalSupport / 4f
        val angleRegularity = 1f - ((maxAngle - 90f).coerceAtLeast(0f) / 90f) * 0.25f
        return (avgEdgeSupport * angleRegularity).coerceIn(0f, 1f)
    }
}
