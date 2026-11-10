package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.PdfOrientation
import com.example.model.PdfPageSize
import com.example.ui.ExportUiState

/**
 * Reusable PDF & Image Export configuration panel supporting:
 * - Filename input & live generated file-size readout
 * - Export Format: PDF, JPG, PNG
 * - Multi-page selection (All Pages vs selected/individual pages)
 * - PDF Page Size (A4, US Letter, Original Aspect Ratio) & Orientation (Portrait, Landscape, Match Page)
 * - Quality presets (High Quality, Balanced, Smaller File Size, Custom) + JPEG Quality slider
 * - Save to Device (Downloads), Share via Android Share Sheet, and Save to Local Library
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportPanel(
    exportState: ExportUiState,
    totalPageCount: Int,
    showSaveToLibraryButton: Boolean,
    onConfigChange: (
        filename: String,
        format: ExportFormat,
        selectedIndices: Set<Int>,
        pageSize: PdfPageSize,
        orientation: PdfOrientation,
        qualityPreset: ExportQualityPreset,
        customQuality: Int
    ) -> Unit,
    onDownloadClick: () -> Unit,
    onShareClick: () -> Unit,
    onSaveToLibraryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("export_panel"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Export PDF or Image",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (exportState.isGenerating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Calculating…", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    exportState.lastGeneratedResult?.let { gen ->
                        BadgePill(
                            text = "Est. Size: ${gen.formattedSize}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Filename Input
            OutlinedTextField(
                value = exportState.filename,
                onValueChange = { newName ->
                    onConfigChange(
                        newName,
                        exportState.format,
                        exportState.selectedPageIndices,
                        exportState.pdfPageSize,
                        exportState.pdfOrientation,
                        exportState.qualityPreset,
                        exportState.customJpegQuality
                    )
                },
                label = { Text("Filename") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_filename_input")
            )

            // Format Selector (PDF, JPG, PNG)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("File Format", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = exportState.format == fmt,
                            onClick = {
                                onConfigChange(
                                    exportState.filename,
                                    fmt,
                                    exportState.selectedPageIndices,
                                    exportState.pdfPageSize,
                                    exportState.pdfOrientation,
                                    exportState.qualityPreset,
                                    exportState.customJpegQuality
                                )
                            },
                            label = { Text(fmt.label) },
                            modifier = Modifier.testTag("export_format_${fmt.name.lowercase()}")
                        )
                    }
                }
            }

            // Page Selection (All Pages or Specific Pages)
            if (totalPageCount > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pages to Export (${exportState.selectedPageIndices.size} of $totalPageCount)",
                            style = MaterialTheme.typography.labelLarge
                        )
                        TextButton(
                            onClick = {
                                val all = (0 until totalPageCount).toSet()
                                onConfigChange(
                                    exportState.filename,
                                    exportState.format,
                                    all,
                                    exportState.pdfPageSize,
                                    exportState.pdfOrientation,
                                    exportState.qualityPreset,
                                    exportState.customJpegQuality
                                )
                            }
                        ) {
                            Text("Select All")
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (idx in 0 until totalPageCount) {
                            val isSelected = idx in exportState.selectedPageIndices
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    val next = exportState.selectedPageIndices.toMutableSet()
                                    if (isSelected && next.size > 1) {
                                        next.remove(idx)
                                    } else {
                                        next.add(idx)
                                    }
                                    onConfigChange(
                                        exportState.filename,
                                        exportState.format,
                                        next,
                                        exportState.pdfPageSize,
                                        exportState.pdfOrientation,
                                        exportState.qualityPreset,
                                        exportState.customJpegQuality
                                    )
                                },
                                label = { Text("Page ${idx + 1}") },
                                modifier = Modifier.testTag("export_page_chip_$idx")
                            )
                        }
                    }
                }
            }

            // PDF-Specific Sizing & Orientation
            if (exportState.format == ExportFormat.PDF) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PDF Page Size", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfPageSize.entries.forEach { ps ->
                            FilterChip(
                                selected = exportState.pdfPageSize == ps,
                                onClick = {
                                    onConfigChange(
                                        exportState.filename,
                                        exportState.format,
                                        exportState.selectedPageIndices,
                                        ps,
                                        exportState.pdfOrientation,
                                        exportState.qualityPreset,
                                        exportState.customJpegQuality
                                    )
                                },
                                label = { Text(ps.label) },
                                modifier = Modifier.testTag("pdf_size_${ps.name.lowercase()}")
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PDF Orientation", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfOrientation.entries.forEach { orient ->
                            FilterChip(
                                selected = exportState.pdfOrientation == orient,
                                onClick = {
                                    onConfigChange(
                                        exportState.filename,
                                        exportState.format,
                                        exportState.selectedPageIndices,
                                        exportState.pdfPageSize,
                                        orient,
                                        exportState.qualityPreset,
                                        exportState.customJpegQuality
                                    )
                                },
                                label = { Text(orient.label) },
                                modifier = Modifier.testTag("pdf_orient_${orient.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            // Quality Presets & Custom JPEG Quality Slider
            if (exportState.format != ExportFormat.PNG) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quality & File Size", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportQualityPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = exportState.qualityPreset == preset,
                                onClick = {
                                    onConfigChange(
                                        exportState.filename,
                                        exportState.format,
                                        exportState.selectedPageIndices,
                                        exportState.pdfPageSize,
                                        exportState.pdfOrientation,
                                        preset,
                                        if (preset == ExportQualityPreset.CUSTOM) exportState.customJpegQuality else preset.jpegQuality
                                    )
                                },
                                label = { Text(preset.label) },
                                modifier = Modifier.testTag("export_quality_${preset.name.lowercase()}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Image Quality: ${exportState.customJpegQuality}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(128.dp)
                        )
                        Slider(
                            value = exportState.customJpegQuality.toFloat(),
                            onValueChange = { q ->
                                onConfigChange(
                                    exportState.filename,
                                    exportState.format,
                                    exportState.selectedPageIndices,
                                    exportState.pdfPageSize,
                                    exportState.pdfOrientation,
                                    ExportQualityPreset.CUSTOM,
                                    q.toInt()
                                )
                            },
                            valueRange = 35f..100f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_quality_slider")
                        )
                    }
                }
            }

            // Status / Feedback Banner
            exportState.statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (exportState.isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exportState.isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        },
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Action Buttons: Save to Downloads, Share, Save to Local Library
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDownloadClick,
                    enabled = !exportState.isGenerating,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("export_download_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save File")
                }

                OutlinedButton(
                    onClick = onShareClick,
                    enabled = !exportState.isGenerating,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("export_share_button")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            }

            if (showSaveToLibraryButton && onSaveToLibraryClick != null) {
                Button(
                    onClick = onSaveToLibraryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("export_save_library_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Document to Local Library")
                }
            }
        }
    }
}
