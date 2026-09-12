package com.example.ui.memory.import_mem

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.memory.universal.ImportAction
import com.example.memory.universal.ImportPreviewItem
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun MemoryImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryImportViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MemoryImportUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is MemoryImportUiEvent.NavigateBackOnSuccess -> {
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Import Memories",
                onBackClick = onNavigateBack,
                actions = {
                    if (uiState.previewResult != null) {
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset",
                                tint = MaxPrimary
                            )
                        }
                    }
                }
            )
        },
        containerColor = MaxBgDark
    ) { padding ->
        if (uiState.previewResult == null) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
            ) {
                MaxCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaxPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Universal Memory Importer",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaxTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                        Text(
                            text = "Import memories directly from ChatGPT export, Claude markdown, Obsidian notes, Gemini takeout, or standard Markdown files.",
                            fontSize = 13.sp,
                            color = MaxTextMuted,
                            lineHeight = 18.sp
                        )
                        if (uiState.detectedProvider != null) {
                            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
                            ) {
                                Text(
                                    text = "Detected Format:",
                                    fontSize = 12.sp,
                                    color = MaxTextSecondary
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                                        .background(MaxPrimary.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = uiState.detectedProvider!!.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaxPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Text input area
                OutlinedTextField(
                    value = uiState.rawText,
                    onValueChange = { viewModel.updateRawText(it) },
                    placeholder = {
                        Text(
                            text = "Paste ChatGPT custom instructions, Claude memory markdown, Obsidian frontmatter notes, or list of facts...",
                            color = MaxTextMuted,
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaxSurface,
                        unfocusedContainerColor = MaxSurface,
                        focusedBorderColor = MaxPrimary,
                        unfocusedBorderColor = MaxBorder,
                        focusedTextColor = MaxTextPrimary,
                        unfocusedTextColor = MaxTextPrimary
                    ),
                    shape = RoundedCornerShape(MaxDimensions.radiusLg)
                )

                if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage!!,
                        color = MaxError,
                        fontSize = 13.sp
                    )
                }

                MaxButton(
                    text = if (uiState.isAnalyzing) "Analyzing & Deduplicating..." else "Analyze & Preview",
                    onClick = { viewModel.analyzeAndPreview() },
                    variant = MaxButtonVariant.PRIMARY,
                    enabled = !uiState.isAnalyzing && uiState.rawText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
            }
        } else {
            // Preview mode
            val preview = uiState.previewResult!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = MaxDimensions.spaceMd)
            ) {
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                // Stats Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    ImportStatCard(label = "New Insert", count = preview.insertCount, color = MaxSuccess, modifier = Modifier.weight(1f))
                    ImportStatCard(label = "Update/Supersede", count = preview.supersedeCount, color = MaxPrimary, modifier = Modifier.weight(1f))
                    ImportStatCard(label = "Duplicate Skip", count = preview.duplicateCount, color = MaxTextMuted, modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                Text(
                    text = "Items to Import (${preview.items.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextPrimary
                )

                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm),
                    contentPadding = PaddingValues(bottom = MaxDimensions.spaceSm)
                ) {
                    items(preview.items) { item ->
                        ImportPreviewItemCard(item = item)
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                MaxButton(
                    text = if (uiState.isCommitting) "Committing..." else "Confirm & Commit to Memory",
                    onClick = { viewModel.commitImport() },
                    variant = MaxButtonVariant.PRIMARY,
                    enabled = !uiState.isCommitting && (preview.insertCount > 0 || preview.supersedeCount > 0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = MaxDimensions.spaceMd)
                )
            }
        }
    }
}

@Composable
private fun ImportStatCard(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    MaxCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceSm),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaxTextMuted
            )
        }
    }
}

@Composable
private fun ImportPreviewItemCard(
    item: ImportPreviewItem,
    modifier: Modifier = Modifier
) {
    val (actionLabel, actionColor) = when (item.action) {
        ImportAction.INSERT -> "INSERT" to MaxSuccess
        ImportAction.UPDATE_SUPERSEDE -> "UPDATE" to MaxPrimary
        ImportAction.SKIP_DUPLICATE -> "SKIP" to MaxTextMuted
    }

    MaxCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.candidate.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                        .background(actionColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = actionLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = actionColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

            Text(
                text = item.candidate.content,
                fontSize = 12.sp,
                color = MaxTextSecondary,
                lineHeight = 16.sp
            )

            if (item.reason != null) {
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                Text(
                    text = item.reason,
                    fontSize = 10.sp,
                    color = MaxTextMuted
                )
            }
        }
    }
}
