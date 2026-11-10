package com.example.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Distinguishes document scanning (auto boundary detection, perspective crop, document filter)
 * from ordinary photography (preserves original colors and uncropped frame by default).
 */
enum class ScanMode(val label: String) {
    DOCUMENT("Document Scan"),
    PHOTO("Photo")
}

/**
 * Real pixel-level document and image enhancement filters.
 */
enum class FilterType(val label: String, val subtitle: String) {
    ORIGINAL("Original Color", "True camera colors"),
    ENHANCED("Enhanced Doc", "Whiten paper & crisp ink"),
    BLACK_AND_WHITE("Black & White", "Adaptive monochrome text"),
    GRAYSCALE("Grayscale", "Balanced neutral tones"),
    HIGH_CONTRAST("High Contrast", "Bold ink & vivid stamps"),
    LIGHTEN("Lighten", "Brighten dim captures"),
    DARKEN("Darken", "Deepen faint pencil/ink")
}

/**
 * Continuous non-destructive image adjustment parameters applied on top of [FilterType].
 */
data class FilterAdjustments(
    val brightness: Float = 0f,   // -100f .. +100f
    val contrast: Float = 0f,     // -100f .. +100f
    val threshold: Float = 50f,   // 0f .. 100f (for Black & White / monochrome thresholding)
    val sharpness: Float = 15f    // 0f .. 100f (unsharp convolution strength)
) {
    companion object {
        val DEFAULT_DOCUMENT = FilterAdjustments(brightness = 0f, contrast = 8f, threshold = 52f, sharpness = 20f)
        val DEFAULT_PHOTO = FilterAdjustments(brightness = 0f, contrast = 0f, threshold = 50f, sharpness = 0f)
    }
}

/**
 * 2D point in normalized [0f..1f] image space.
 */
data class Point2D(
    val x: Float,
    val y: Float
) {
    fun clamp(): Point2D = Point2D(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f)
    )

    fun distanceTo(other: Point2D): Float = hypot(x - other.x, y - other.y)
}

/**
 * Represents a 4-corner quadrilateral boundary in normalized [0f..1f] image coordinates,
 * ordered clockwise: topLeft -> topRight -> bottomRight -> bottomLeft.
 */
data class NormalizedQuad(
    val topLeft: Point2D,
    val topRight: Point2D,
    val bottomRight: Point2D,
    val bottomLeft: Point2D
) {
    fun toList(): List<Point2D> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun clamp(): NormalizedQuad = NormalizedQuad(
        topLeft = topLeft.clamp(),
        topRight = topRight.clamp(),
        bottomRight = bottomRight.clamp(),
        bottomLeft = bottomLeft.clamp()
    )

    /**
     * Checks whether this quadrilateral is strictly convex (no self-intersections or concave corners).
     */
    fun isConvex(): Boolean {
        val pts = toList()
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-5f) return false
            val currentSign = if (cross > 0f) 1 else -1
            if (sign == 0) {
                sign = currentSign
            } else if (sign != currentSign) {
                return false
            }
        }
        return areaFraction() > 0.01f
    }

    /**
     * Shoelace formula for polygon area in normalized [0..1] space.
     */
    fun areaFraction(): Float {
        val pts = toList()
        var sum = 0f
        for (i in 0 until 4) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 4]
            sum += (p1.x * p2.y - p2.x * p1.y)
        }
        return abs(sum) * 0.5f
    }

    /**
     * Computes minimum and maximum interior angles (in degrees) to reject severely degenerate shapes.
     */
    fun interiorAngleRangeDegrees(): Pair<Float, Float> {
        val pts = toList()
        var minAngle = 180f
        var maxAngle = 0f
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val curr = pts[i]
            val next = pts[(i + 1) % 4]
            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val len1 = hypot(v1x, v1y)
            val len2 = hypot(v2x, v2y)
            if (len1 < 1e-5f || len2 < 1e-5f) return 0f to 180f
            val cosTheta = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
            val deg = (acos(cosTheta) * 180.0 / PI).toFloat()
            minAngle = min(minAngle, deg)
            maxAngle = max(maxAngle, deg)
        }
        return minAngle to maxAngle
    }

    /**
     * Maximum corner movement relative to [other], used to measure camera stability across frames.
     */
    fun maxCornerDelta(other: NormalizedQuad): Float {
        return maxOf(
            topLeft.distanceTo(other.topLeft),
            topRight.distanceTo(other.topRight),
            bottomRight.distanceTo(other.bottomRight),
            bottomLeft.distanceTo(other.bottomLeft)
        )
    }

    fun isFullFrame(epsilon: Float = 0.015f): Boolean {
        return topLeft.distanceTo(Point2D(0f, 0f)) < epsilon &&
            topRight.distanceTo(Point2D(1f, 0f)) < epsilon &&
            bottomRight.distanceTo(Point2D(1f, 1f)) < epsilon &&
            bottomLeft.distanceTo(Point2D(0f, 1f)) < epsilon
    }

    fun serialize(): String {
        return "${topLeft.x},${topLeft.y};${topRight.x},${topRight.y};${bottomRight.x},${bottomRight.y};${bottomLeft.x},${bottomLeft.y}"
    }

    companion object {
        fun fullFrame(): NormalizedQuad = NormalizedQuad(
            topLeft = Point2D(0f, 0f),
            topRight = Point2D(1f, 0f),
            bottomRight = Point2D(1f, 1f),
            bottomLeft = Point2D(0f, 1f)
        )

        fun insetFrame(margin: Float = 0.06f): NormalizedQuad = NormalizedQuad(
            topLeft = Point2D(margin, margin),
            topRight = Point2D(1f - margin, margin),
            bottomRight = Point2D(1f - margin, 1f - margin),
            bottomLeft = Point2D(margin, 1f - margin)
        )

        /**
         * Orders any 4 points deterministically into clockwise [topLeft, topRight, bottomRight, bottomLeft].
         */
        fun fromUnorderedPoints(points: List<Point2D>): NormalizedQuad {
            require(points.size == 4) { "Expected 4 corner points" }
            // Sort by y ascending to split top pair and bottom pair, or use sum/difference heuristic
            val cx = points.map { it.x }.average().toFloat()
            val cy = points.map { it.y }.average().toFloat()
            val sortedClockwise = points.sortedBy { kotlin.math.atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
            // Find the top-left-most vertex (minimum x + y) as index 0
            val startIdx = sortedClockwise.indices.minByOrNull { idx ->
                sortedClockwise[idx].x + sortedClockwise[idx].y
            } ?: 0
            val ordered = List(4) { i -> sortedClockwise[(startIdx + i) % 4] }
            return NormalizedQuad(
                topLeft = ordered[0].clamp(),
                topRight = ordered[1].clamp(),
                bottomRight = ordered[2].clamp(),
                bottomLeft = ordered[3].clamp()
            )
        }

        fun deserialize(raw: String?): NormalizedQuad {
            if (raw.isNullOrBlank()) return fullFrame()
            return try {
                val parts = raw.split(";")
                if (parts.size != 4) return fullFrame()
                val pts = parts.map { pair ->
                    val xy = pair.split(",")
                    Point2D(xy[0].toFloat(), xy[1].toFloat()).clamp()
                }
                NormalizedQuad(pts[0], pts[1], pts[2], pts[3])
            } catch (_: Exception) {
                fullFrame()
            }
        }
    }
}

/**
 * Result of automatic document boundary detection.
 * Never claims [isDetected] = true unless a genuine 4-corner quadrilateral was detected.
 */
data class DetectionResult(
    val quad: NormalizedQuad?,
    val confidence: Float,
    val isDetected: Boolean
) {
    companion object {
        val NONE = DetectionResult(quad = null, confidence = 0f, isDetected = false)
    }
}

enum class PdfPageSize(val label: String, val widthPt: Int, val heightPt: Int) {
    A4("A4 (210 × 297 mm)", 595, 842),
    LETTER("US Letter (8.5 × 11 in)", 612, 792),
    ORIGINAL("Original Aspect Ratio", 0, 0)
}

enum class PdfOrientation(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    AUTO("Match Page")
}

enum class ExportQualityPreset(val label: String, val jpegQuality: Int, val maxDimensionPx: Int) {
    HIGH("High Quality (Print)", 94, 2600),
    BALANCED("Balanced (Standard)", 82, 2000),
    SMALL("Smaller File Size", 62, 1400),
    CUSTOM("Custom Quality", 85, 2200)
}

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    PDF("PDF Document (.pdf)", "pdf", "application/pdf"),
    JPG("JPEG Image (.jpg)", "jpg", "image/jpeg"),
    PNG("PNG Lossless (.png)", "png", "image/png")
}

enum class OcrLanguage(val label: String, val tessCode: String, val requiresDownload: Boolean) {
    ENGLISH("English (Offline Ready)", "eng", false),
    BANGLA("Bangla / বাংলা", "ben", true),
    ENGLISH_BANGLA("English + Bangla (বাংলা)", "eng+ben", true)
}

enum class SortOrder(val label: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)")
}

enum class ThemePreference(val label: String) {
    SYSTEM("System Default"),
    LIGHT("Light Theme"),
    DARK("Dark Theme")
}

/**
 * Represents one editable page inside an active scanning/editing session.
 * Preserves [originalImagePath] untouched so re-cropping and switching filters never degrade quality.
 */
data class EditablePage(
    val id: String,
    val originalImagePath: String,
    val processedImagePath: String,
    val thumbnailPath: String,
    val quad: NormalizedQuad,
    val wasBoundaryAutoDetected: Boolean,
    val rotationDegrees: Float = 0f,
    val filterType: FilterType = FilterType.ORIGINAL,
    val adjustments: FilterAdjustments = FilterAdjustments(),
    val ocrText: String = "",
    val ocrLanguage: OcrLanguage = OcrLanguage.ENGLISH
)
