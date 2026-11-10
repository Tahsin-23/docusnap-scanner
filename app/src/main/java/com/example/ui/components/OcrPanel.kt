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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.OcrLanguage
import com.example.ui.OcrUiState

/**
 * Optional local-only OCR Text Recognition panel supporting English and Bangla (বাংলা).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrPanel(
    ocrState: OcrUiState,
    totalPageCount: Int,
    onSelectLanguage: (OcrLanguage) -> Unit,
    onDownloadLanguageData: () -> Unit,
    onExtractCurrentPage: () -> Unit,
    onExtractAllPages: () -> Unit,
    onEditRecognizedText: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onSaveTxtFile: (String) -> Unit,
    onShareTxtFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ocr_panel"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Optional OCR Text Recognition",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.ocr_optional_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Language Selector (English, Bangla, English + Bangla)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recognition Language", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcrLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = ocrState.selectedLanguage == lang,
                            onClick = { onSelectLanguage(lang) },
                            label = { Text(lang.label) },
                            modifier = Modifier.testTag("ocr_lang_${lang.name.lowercase()}")
                        )
                    }
                }
            }

            // Language Pack Download Prompt when Bangla is selected & not yet cached
            if (!ocrState.isLanguageReady) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Offline language pack needed for ${ocrState.selectedLanguage.label}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (ocrState.isDownloadingLanguage) {
                            LinearProgressIndicator(
                                progress = { ocrState.downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Downloading… ${(ocrState.downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        } else {
                            Button(
                                onClick = onDownloadLanguageData,
                                modifier = Modifier.testTag("ocr_download_lang_button")
                            ) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download Language Data")
                            }
                        }
                    }
                }
            }

            // Extract Text Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onExtractCurrentPage,
                    enabled = ocrState.isLanguageReady && !ocrState.isRecognizing && !ocrState.isDownloadingLanguage,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("ocr_extract_page_button")
                ) {
                    if (ocrState.isRecognizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Extract Page")
                }

                if (totalPageCount > 1) {
                    OutlinedButton(
                        onClick = onExtractAllPages,
                        enabled = ocrState.isLanguageReady && !ocrState.isRecognizing && !ocrState.isDownloadingLanguage,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ocr_extract_all_button")
                    ) {
                        Text("All $totalPageCount Pages")
                    }
                }
            }

            ocrState.statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ocrState.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            // Recognized Text Viewer / Editor
            OutlinedTextField(
                value = ocrState.recognizedText,
                onValueChange = onEditRecognizedText,
                label = { Text("Recognized Text (editable — does not alter scan image)") },
                placeholder = { Text("Tap 'Extract Page' above to recognize printed or clear text…") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ocr_result_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onCopyText(ocrState.recognizedText) },
                    enabled = ocrState.recognizedText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ocr_copy_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }
                OutlinedButton(
                    onClick = { onSaveTxtFile(ocrState.recognizedText) },
                    enabled = ocrState.recognizedText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ocr_save_txt_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save TXT")
                }
                OutlinedButton(
                    onClick = { onShareTxtFile(ocrState.recognizedText) },
                    enabled = ocrState.recognizedText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
            }
        }
    }
}
