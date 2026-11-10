package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.cv.BitmapUtils
import com.example.cv.PerspectiveTransformer
import com.example.model.EditablePage
import com.example.model.NormalizedQuad
import com.example.model.Point2D
import com.example.ui.theme.ScannerAmber
import com.example.ui.theme.ScannerCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive 4-corner Crop & Perspective editor with:
 * - Four independently draggable corner handles (48dp+ touch hit zone)
 * - Live zoomed magnifier loupe while dragging a corner
 * - Bilinear interior alignment grid & convexity validation
 * - 90-degree left/right rotation + fine tilt slider (-15°..+15°)
 * - Auto-Detect, Full-Page Reset, Cancel, and Apply Crop actions
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CropPerspectiveEditor(
    page: EditablePage,
    onAutoDetectRequest: ((NormalizedQuad, Boolean) -> Unit) -> Unit,
    onConfirmCrop: (NormalizedQuad, Float) -> Unit,
    onCancelCrop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var baseRotation90 by remember(page.id, page.rotationDegrees) {
        val rounded90 = ((page.rotationDegrees / 90f).roundToInt() * 90f)
        mutableFloatStateOf(((rounded90 % 360f) + 360f) % 360f)
    }
    var fineTiltDegrees by remember(page.id, page.rotationDegrees) {
        val rounded90 = ((page.rotationDegrees / 90f).roundToInt() * 90f)
        val diff = page.rotationDegrees - rounded90
        mutableFloatStateOf(diff.coerceIn(-15f, 15f))
    }
    val totalRotation = ((baseRotation90 + fineTiltDegrees) % 360f + 360f) % 360f

    var draftQuad by remember(page.id, page.quad) { mutableStateOf(page.quad) }
    var activeCornerIndex by remember { mutableIntStateOf(-1) }
    var rotatedPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showNoBoundaryBanner by remember(page.id) { mutableStateOf(!page.wasBoundaryAutoDetected && page.quad.isFullFrame()) }

    LaunchedEffect(page.originalImagePath, totalRotation) {
        val loaded = withContext(Dispatchers.Default) {
            val orig = BitmapUtils.decodeBitmapFromFile(
                page.originalImagePath,
                maxDimension = BitmapUtils.PREVIEW_MAX_DIMENSION
            ) ?: return@withContext null
            val rot = PerspectiveTransformer.rotateBitmap(orig, totalRotation)
            if (rot !== orig) orig.recycle()
            rot
        }
        val previous = rotatedPreviewBitmap
        rotatedPreviewBitmap = loaded
        if (previous != null && previous !== loaded) {
            previous.recycle()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            rotatedPreviewBitmap?.recycle()
            rotatedPreviewBitmap = null
        }
    }

    val isQuadValid = remember(draftQuad) { draftQuad.isConvex() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("crop_perspective_editor"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showNoBoundaryBanner) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Tip: Drag the 4 glowing corner handles onto your paper's corners for perspective straightening, or keep Full Page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Interactive Crop Canvas Stage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0F172A)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = rotatedPreviewBitmap
            if (bmp == null) {
                CircularProgressIndicator(color = ScannerCyan)
            } else {
                var imageRect by remember { mutableStateOf(Rect.Zero) }
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .testTag("crop_canvas")
                        .pointerInput(bmp) {
                            detectDragGestures(
                                onDragStart = { downOffset ->
                                    if (imageRect.width <= 0f || imageRect.height <= 0f) return@detectDragGestures
                                    val corners = draftQuad.toList().map { pt ->
                                        Offset(
                                            imageRect.left + pt.x * imageRect.width,
                                            imageRect.top + pt.y * imageRect.height
                                        )
                                    }
                                    val hitRadiusPx = 42.dp.toPx()
                                    var closestIdx = -1
                                    var closestDist = Float.MAX_VALUE
                                    corners.forEachIndexed { idx, c ->
                                        val d = hypot(downOffset.x - c.x, downOffset.y - c.y)
                                        if (d < hitRadiusPx && d < closestDist) {
                                            closestDist = d
                                            closestIdx = idx
                                        }
                                    }
                                    activeCornerIndex = closestIdx
                                },
                                onDragEnd = { activeCornerIndex = -1 },
                                onDragCancel = { activeCornerIndex = -1 },
                                onDrag = { change, dragAmount ->
                                    if (activeCornerIndex in 0..3 && imageRect.width > 0f && imageRect.height > 0f) {
                                        change.consume()
                                        val dxNorm = dragAmount.x / imageRect.width
                                        val dyNorm = dragAmount.y / imageRect.height
                                        val pts = draftQuad.toList().toMutableList()
                                        val old = pts[activeCornerIndex]
                                        pts[activeCornerIndex] = Point2D(
                                            (old.x + dxNorm).coerceIn(0f, 1f),
                                            (old.y + dyNorm).coerceIn(0f, 1f)
                                        )
                                        draftQuad = NormalizedQuad(pts[0], pts[1], pts[2], pts[3])
                                        showNoBoundaryBanner = false
                                    }
                                }
                            )
                        }
                ) {
                    val canvasW = size.width
                    val canvasH = size.height
                    val scale = min(canvasW / bmp.width.toFloat(), canvasH / bmp.height.toFloat())
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    val left = (canvasW - drawW) * 0.5f
                    val top = (canvasH - drawH) * 0.5f
                    imageRect = Rect(left, top, left + drawW, top + drawH)

                    // Draw underlying source bitmap
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
                    )

                    val pTL = Offset(left + draftQuad.topLeft.x * drawW, top + draftQuad.topLeft.y * drawH)
                    val pTR = Offset(left + draftQuad.topRight.x * drawW, top + draftQuad.topRight.y * drawH)
                    val pBR = Offset(left + draftQuad.bottomRight.x * drawW, top + draftQuad.bottomRight.y * drawH)
                    val pBL = Offset(left + draftQuad.bottomLeft.x * drawW, top + draftQuad.bottomLeft.y * drawH)

                    val quadPath = Path().apply {
                        moveTo(pTL.x, pTL.y)
                        lineTo(pTR.x, pTR.y)
                        lineTo(pBR.x, pBR.y)
                        lineTo(pBL.x, pBL.y)
                        close()
                    }

                    // Dim exterior area outside quad
                    clipPath(quadPath, clipOp = ClipOp.Difference) {
                        drawRect(
                            color = Color.Black.copy(alpha = 0.56f),
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(drawW, drawH)
                        )
                    }

                    val borderColor = if (isQuadValid) ScannerCyan else ScannerAmber

                    // Draw bilinear perspective rule-of-thirds grid inside quad
                    for (step in 1..2) {
                        val t = step / 3f
                        val hStart = lerpOffset(pTL, pBL, t)
                        val hEnd = lerpOffset(pTR, pBR, t)
                        drawLine(borderColor.copy(alpha = 0.4f), hStart, hEnd, strokeWidth = 1.dp.toPx())

                        val vStart = lerpOffset(pTL, pTR, t)
                        val vEnd = lerpOffset(pBL, pBR, t)
                        drawLine(borderColor.copy(alpha = 0.4f), vStart, vEnd, strokeWidth = 1.dp.toPx())
                    }

                    // Draw quad boundary
                    drawPath(
                        path = quadPath,
                        color = borderColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Draw 4 draggable corner handles
                    val corners = listOf(pTL, pTR, pBR, pBL)
                    corners.forEachIndexed { idx, pt ->
                        val isDragging = idx == activeCornerIndex
                        val outerR = if (isDragging) 14.dp.toPx() else 11.dp.toPx()
                        drawCircle(color = Color.White, radius = outerR, center = pt)
                        drawCircle(color = borderColor, radius = outerR - 3.dp.toPx(), center = pt)
                    }

                    // Precision Magnifier Loupe when actively dragging a corner
                    if (activeCornerIndex in 0..3) {
                        val draggedNorm = draftQuad.toList()[activeCornerIndex]
                        val srcCx = (draggedNorm.x * bmp.width).roundToInt()
                        val srcCy = (draggedNorm.y * bmp.height).roundToInt()
                        val patchHalf = (min(bmp.width, bmp.height) * 0.08f).roundToInt().coerceAtLeast(16)
                        val srcLeft = (srcCx - patchHalf).coerceIn(0, (bmp.width - patchHalf * 2).coerceAtLeast(0))
                        val srcTop = (srcCy - patchHalf).coerceIn(0, (bmp.height - patchHalf * 2).coerceAtLeast(0))
                        val srcW = (patchHalf * 2).coerceAtMost(bmp.width - srcLeft)
                        val srcH = (patchHalf * 2).coerceAtMost(bmp.height - srcTop)

                        val loupeSizePx = 86.dp.toPx().roundToInt()
                        val loupeX = if (draggedNorm.x < 0.5f) (canvasW - loupeSizePx - 8).roundToInt() else 8
                        val loupeY = 8

                        drawImage(
                            image = imageBitmap,
                            srcOffset = IntOffset(srcLeft, srcTop),
                            srcSize = IntSize(srcW, srcH),
                            dstOffset = IntOffset(loupeX, loupeY),
                            dstSize = IntSize(loupeSizePx, loupeSizePx)
                        )
                        drawRect(
                            color = ScannerCyan,
                            topLeft = Offset(loupeX.toFloat(), loupeY.toFloat()),
                            size = androidx.compose.ui.geometry.Size(loupeSizePx.toFloat(), loupeSizePx.toFloat()),
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                        // Crosshair inside loupe
                        val cx = loupeX + loupeSizePx * 0.5f
                        val cy = loupeY + loupeSizePx * 0.5f
                        drawLine(ScannerCyan, Offset(cx - 10.dp.toPx(), cy), Offset(cx + 10.dp.toPx(), cy), 1.5.dp.toPx())
                        drawLine(ScannerCyan, Offset(cx, cy - 10.dp.toPx()), Offset(cx, cy + 10.dp.toPx()), 1.5.dp.toPx())
                    }
                }
            }
        }

        // Rotation Controls (90° Left/Right + Fine Straighten Slider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { baseRotation90 = ((baseRotation90 - 90f) % 360f + 360f) % 360f },
                modifier = Modifier.testTag("crop_rotate_left_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "Rotate 90° left", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("-90°")
            }

            OutlinedButton(
                onClick = { baseRotation90 = (baseRotation90 + 90f) % 360f },
                modifier = Modifier.testTag("crop_rotate_right_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90° right", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("+90°")
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tilt: ${String.format(java.util.Locale.US, "%+.1f°", fineTiltDegrees)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = fineTiltDegrees,
                    onValueChange = { fineTiltDegrees = it },
                    valueRange = -15f..15f,
                    modifier = Modifier.testTag("crop_fine_tilt_slider")
                )
            }
        }

        // Quick Boundary Helpers + Confirm / Cancel
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onAutoDetectRequest { detected, found ->
                            draftQuad = detected
                            showNoBoundaryBanner = !found
                        }
                    },
                    modifier = Modifier.testTag("crop_auto_detect_button")
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Auto Detect")
                }

                OutlinedButton(
                    onClick = {
                        draftQuad = NormalizedQuad.fullFrame()
                        fineTiltDegrees = 0f
                    },
                    modifier = Modifier.testTag("crop_reset_button")
                ) {
                    Icon(Icons.Filled.CropFree, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Reset")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onCancelCrop,
                    modifier = Modifier.testTag("crop_cancel_button")
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onConfirmCrop(draftQuad, totalRotation) },
                    enabled = isQuadValid,
                    modifier = Modifier.testTag("confirm_crop_button")
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Crop")
                }
            }
        }
    }
}

private fun lerpOffset(a: Offset, b: Offset, t: Float): Offset {
    return Offset(
        x = a.x + (b.x - a.x) * t,
        y = a.y + (b.y - a.y) * t
    )
}
