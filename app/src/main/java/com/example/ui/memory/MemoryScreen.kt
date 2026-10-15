package com.example.ui.memory

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.memory.sync.MemoryConflictChoice
import com.example.memory.sync.MemoryConflictResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToImport: () -> Unit,
    viewModel: MemoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showEditorSheet by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf("Personal") }
    var editTags by remember { mutableStateOf("") }
    var editType by remember { mutableStateOf(MemoryType.FACT) }
    var showLegacyReview by remember { mutableStateOf(false) }
    var mergeConflict by remember { mutableStateOf<com.example.memory.sync.MemoryConflict?>(null) }
    var mergeTitle by remember { mutableStateOf("") }
    var mergeContent by remember { mutableStateOf("") }
    var mergeCategory by remember { mutableStateOf("") }
    var mergeTags by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isCreatingNew, uiState.selectedMemoryForEdit) {
        if (uiState.isCreatingNew) {
            editTitle = ""
            editContent = ""
            editCategory = "Personal"
            editTags = ""
            editType = MemoryType.FACT
            showEditorSheet = true
        } else if (uiState.selectedMemoryForEdit != null) {
            val mem = uiState.selectedMemoryForEdit!!
            editTitle = mem.title
            editContent = mem.content
            editCategory = mem.category
            editTags = mem.tags
            editType = mem.type
            showEditorSheet = true
        } else {
            showEditorSheet = false
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MemoryUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    MaxScreen(
        title = "Memory Wallet",
        eyebrow = "KNOWLEDGE VAULT",
        auroraMode = AuroraMode.SUBTLE,
        scrollable = false,
        onBack = onNavigateBack,
        topBarActions = {
            IconButton(onClick = onNavigateToImport) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "Import Memories",
                    tint = MaxSemanticColors.accentCyan
                )
            }
            IconButton(onClick = { viewModel.startCreateMemory() }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Memory",
                    tint = MaxSemanticColors.accentCyan
                )
            }
            IconButton(onClick = { showLegacyReview = true }) {
                Icon(Icons.Default.Policy, "Review unassigned legacy memories", tint = MaxSemanticColors.accentCyan)
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Search Bar
            MaxTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                label = "Search memories",
                placeholder = "Search topics, facts, preferences...",
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaxSemanticColors.textMuted) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceSm)
            )

            // Category Chips Row
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
            ) {
                uiState.availableCategories.forEach { category ->
                    val isSelected = uiState.selectedCategory.equals(category, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(if (isSelected) MaxSemanticColors.primaryRoyal else MaxSemanticColors.surfaceVariant)
                            .clickable { viewModel.onCategorySelected(category) }
                            .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceXs)
                    ) {
                        Text(
                            text = category,
                            style = MaxType.Caption,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else MaxSemanticColors.textSecondary
                        )
                    }
                }
            }
            uiState.conflicts.forEach { conflict ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = MaxDimensions.spaceMd), colors = CardDefaults.cardColors(containerColor = MaxSemanticColors.surfaceVariant)) {
                    Column(Modifier.padding(MaxDimensions.spaceSm)) {
                        Text("Sync conflict: ${conflict.memoryId}", color = MaxSemanticColors.textPrimary, fontWeight = FontWeight.Bold)
                        Text("${conflict.state.name.lowercase().replace('_', ' ')} • Local v${conflict.localRevision} • Remote v${conflict.remoteRevision}", color = MaxSemanticColors.textSecondary)
                        Text("LOCAL\n${conflict.localJson ?: "(tombstone)"}", color = MaxSemanticColors.textSecondary, style = MaxType.Caption)
                        Text("REMOTE\n${conflict.remoteJson ?: "(tombstone)"}", color = MaxSemanticColors.textSecondary, style = MaxType.Caption)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ viewModel.resolveConflict(conflict, MemoryConflictChoice.LOCAL) }) { Text("Local") }
                            TextButton({ viewModel.resolveConflict(conflict, MemoryConflictChoice.REMOTE) }) { Text("Remote") }
                            TextButton({
                                val base = conflict.localJson?.let { runCatching { MemoryConflictResolver.choose(conflict, MemoryConflictChoice.LOCAL).getOrNull() }.getOrNull() }
                                mergeConflict = conflict; mergeTitle = base?.title ?: ""; mergeContent = base?.content ?: ""; mergeCategory = base?.category ?: ""; mergeTags = base?.tags ?: ""
                            }) { Text("Merge") }
                            TextButton({ viewModel.resolveConflict(conflict, MemoryConflictChoice.CANCEL) }) { Text("Cancel") }
                        }
                        if (!conflict.error.isNullOrBlank()) Text("Failed: ${conflict.error}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            // Memory List
            if (uiState.filteredMemories.isEmpty()) {
                MaxEmptyState(
                    title = "No Memories Found",
                    message = if (uiState.searchQuery.isNotEmpty()) "No memories match '${uiState.searchQuery}'." else "Your long-term memory wallet is empty. Add a fact or import from ChatGPT/Claude/Obsidian.",
                    icon = Icons.Default.Psychology,
                    actionButton = {
                        MaxButton(
                            text = "Add Memory",
                            onClick = { viewModel.startCreateMemory() },
                            variant = MaxButtonVariant.PRIMARY
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm),
                    contentPadding = PaddingValues(bottom = MaxDimensions.space2xl)
                ) {
                    items(
                        items = uiState.filteredMemories,
                        key = { it.memoryId }
                    ) { memory ->
                        MemoryItemCard(
                            memory = memory,
                            onEdit = { viewModel.startEditMemory(memory) },
                            onDelete = { viewModel.deleteMemory(memory) },
                            onTogglePinned = { viewModel.togglePinned(memory) },
                            onToggleEnabled = { viewModel.toggleEnabled(memory) }
                        )
                    }
                }
            }
        }

        mergeConflict?.let { conflict ->
            AlertDialog(onDismissRequest = { mergeConflict = null }, title = { Text("Merge memory") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MaxTextField(mergeTitle, { mergeTitle = it }, label = "Title", modifier = Modifier.fillMaxWidth())
                    MaxTextField(mergeContent, { mergeContent = it }, label = "Content", singleLine = false, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    MaxTextField(mergeCategory, { mergeCategory = it }, label = "Category", modifier = Modifier.fillMaxWidth())
                    MaxTextField(mergeTags, { mergeTags = it }, label = "Tags", modifier = Modifier.fillMaxWidth())
                } }, confirmButton = { TextButton(onClick = {
                    val source = MemoryConflictResolver.choose(conflict, MemoryConflictChoice.LOCAL).getOrNull()
                    if (source != null) viewModel.resolveConflict(conflict, MemoryConflictChoice.MERGE, source.copy(title = mergeTitle, content = mergeContent, category = mergeCategory, tags = mergeTags))
                    mergeConflict = null
                }) { Text("Resolve") } }, dismissButton = { TextButton({ mergeConflict = null }) { Text("Cancel") } })
        }

        if (showLegacyReview) {
            AlertDialog(
                onDismissRequest = { showLegacyReview = false },
                title = { Text("Review unassigned memories") },
                text = { Text("Legacy records remain unassigned. Select records and explicitly confirm a destination account; MAX never assigns them automatically.") },
                confirmButton = { TextButton(onClick = { showLegacyReview = false }) { Text("Close") } }
            )
        }

        // Edit / Add BottomSheet
        if (showEditorSheet) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissEditor() },
                containerColor = MaxSemanticColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaxDimensions.spaceLg, vertical = MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                ) {
                    Text(
                        text = if (uiState.selectedMemoryForEdit != null) "Edit Memory" else "New Memory",
                        style = MaxType.Headline,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textPrimary
                    )

                    MaxTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = "Topic / Title",
                        placeholder = "e.g. Favorite Food",
                        modifier = Modifier.fillMaxWidth()
                    )

                    MaxTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = "Memory Content",
                        placeholder = "e.g. Prefers spicy biryani on weekends",
                        singleLine = false,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )

                    MaxTextField(
                        value = editCategory,
                        onValueChange = { editCategory = it },
                        label = "Category",
                        placeholder = "Personal, Preferences, Work...",
                        modifier = Modifier.fillMaxWidth()
                    )

                    MaxTextField(
                        value = editTags,
                        onValueChange = { editTags = it },
                        label = "Tags (comma separated)",
                        placeholder = "food, dinner, spice",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaxButton(
                            text = "Cancel",
                            onClick = { viewModel.dismissEditor() },
                            variant = MaxButtonVariant.OUTLINE,
                            modifier = Modifier.padding(end = MaxDimensions.spaceSm)
                        )
                        MaxButton(
                            text = if (uiState.selectedMemoryForEdit != null) "Update" else "Save",
                            onClick = {
                                viewModel.saveMemory(
                                    title = editTitle,
                                    content = editContent,
                                    category = editCategory,
                                    tags = editTags,
                                    type = editType
                                )
                            },
                            variant = MaxButtonVariant.PRIMARY
                        )
                    }

                    Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryItemCard(
    memory: PermanentMemory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleEnabled: () -> Unit,
    modifier: Modifier = Modifier
) {
    MaxCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    Text(
                        text = memory.title,
                        style = MaxType.Body,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(MaxSemanticColors.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = memory.category.uppercase(),
                            style = MaxType.Eyebrow,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.accentCyan
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTogglePinned, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (memory.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (memory.pinned) "Unpin memory" else "Pin memory",
                            tint = if (memory.pinned) MaxSemanticColors.accentCyan else MaxSemanticColors.textMuted,
                            modifier = Modifier.size(MaxIconTokens.Small)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit memory",
                            tint = MaxSemanticColors.textMuted,
                            modifier = Modifier.size(MaxIconTokens.Small)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete memory",
                            tint = MaxSemanticColors.destructive.copy(alpha = 0.7f),
                            modifier = Modifier.size(MaxIconTokens.Small)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

            Text(
                text = memory.content,
                style = MaxType.Caption,
                color = MaxSemanticColors.textSecondary
            )

            if (memory.tags.isNotBlank()) {
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    memory.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                                .background(MaxSemanticColors.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaxType.Eyebrow,
                                color = MaxSemanticColors.accentCyan
                            )
                        }
                    }
                }
            }
        }
    }
}
