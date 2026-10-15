package com.example.ui.skills

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SkillsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importSkillFromUri(it, context) }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SkillsUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is SkillsUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    MaxScreen(
        title = "Automation Skills",
        eyebrow = "INTELLIGENCE ENGINE",
        auroraMode = AuroraMode.SUBTLE,
        scrollable = false,
        onBack = onNavigateBack,
        topBarActions = {
            MaxButton(
                text = "+ Import",
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/plain",
                            "text/markdown",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                variant = MaxButtonVariant.PRIMARY,
                height = 36.dp,
                modifier = Modifier.padding(end = MaxDimensions.spaceSm)
            )
        }
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (uiState.isLoading && uiState.installedSkills.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaxSemanticColors.accentCyan
                )
            } else if (uiState.installedSkills.isEmpty()) {
                MaxEmptyState(
                    title = "No skills installed",
                    message = "Import an automation skill to give MAX reusable workflows.",
                    icon = Icons.Default.AutoAwesome,
                    actionButton = {
                        MaxButton(
                            text = "Import Skill (.json / .md)",
                            onClick = {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/plain",
                                        "text/markdown",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            variant = MaxButtonVariant.PRIMARY
                        )
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                ) {
                    item {
                        Text(
                            text = "INSTALLED SKILLS (${uiState.installedSkills.size})",
                            style = MaxType.Eyebrow,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.textMuted,
                            modifier = Modifier.padding(vertical = MaxDimensions.spaceXs)
                        )
                    }

                    items(uiState.installedSkills, key = { it.skillId }) { item ->
                        SkillCard(
                            item = item,
                            onToggle = { isChecked -> viewModel.toggleSkillEnabled(item.skillId, isChecked) },
                            onClick = { viewModel.selectSkillForDetails(item) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
                    }
                }
            }

            // --- Detail Modal Dialog ---
            uiState.selectedSkillForDetails?.let { detailItem ->
                SkillDetailsDialog(
                    item = detailItem,
                    onDismiss = { viewModel.dismissDetails() },
                    onToggleEnabled = { isChecked -> viewModel.toggleSkillEnabled(detailItem.skillId, isChecked) },
                    onDelete = { viewModel.requestDelete(detailItem) }
                )
            }

            // --- Duplicate Conflict Dialog ---
            uiState.pendingImportConflict?.let { conflict ->
                AlertDialog(
                    onDismissRequest = { viewModel.cancelOverwrite() },
                    containerColor = MaxSemanticColors.surface,
                    title = {
                        Text(
                            text = "Skill Already Exists",
                            style = MaxType.Headline,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.textPrimary
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "A skill with ID '${conflict.existingSkill.skillId}' is already installed.",
                                color = MaxSemanticColors.textPrimary,
                                style = MaxType.Body
                            )
                            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                            Text(
                                text = "Installed: v${conflict.existingSkill.version} by ${conflict.existingSkill.author}",
                                color = MaxSemanticColors.textMuted,
                                style = MaxType.Caption
                            )
                            Text(
                                text = "New: v${conflict.newSkill.version} by ${conflict.newSkill.author}",
                                color = MaxSemanticColors.textSecondary,
                                style = MaxType.Caption
                            )
                            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                            Text(
                                text = "Do you want to replace the existing skill with the imported version?",
                                color = MaxSemanticColors.textPrimary,
                                style = MaxType.Body,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    confirmButton = {
                        MaxButton(
                            text = "Replace",
                            onClick = { viewModel.confirmOverwrite() },
                            variant = MaxButtonVariant.PRIMARY
                        )
                    },
                    dismissButton = {
                        MaxButton(
                            text = "Cancel",
                            onClick = { viewModel.cancelOverwrite() },
                            variant = MaxButtonVariant.SECONDARY
                        )
                    }
                )
            }

            // --- Delete Confirmation Dialog ---
            uiState.deleteConfirmationSkill?.let { toDelete ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDeleteConfirmation() },
                    containerColor = MaxSemanticColors.surface,
                    title = {
                        Text(
                            text = "Delete Skill?",
                            style = MaxType.Headline,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.destructive
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to permanently delete '${toDelete.name}'? This cannot be undone.",
                            color = MaxSemanticColors.textPrimary,
                            style = MaxType.Body
                        )
                    },
                    confirmButton = {
                        MaxButton(
                            text = "Delete",
                            onClick = { viewModel.confirmDelete() },
                            variant = MaxButtonVariant.DANGER
                        )
                    },
                    dismissButton = {
                        MaxButton(
                            text = "Cancel",
                            onClick = { viewModel.dismissDeleteConfirmation() },
                            variant = MaxButtonVariant.SECONDARY
                        )
                    }
                )
            }

            // --- Error Alert Dialog ---
            uiState.errorDialogMessage?.let { errMsg ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissErrorDialog() },
                    containerColor = MaxSemanticColors.surface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaxSemanticColors.destructive)
                            Spacer(modifier = Modifier.width(MaxDimensions.spaceSm))
                            Text(
                                text = "Skill Import Error",
                                style = MaxType.Headline,
                                fontWeight = FontWeight.Bold,
                                color = MaxSemanticColors.destructive
                            )
                        }
                    },
                    text = {
                        Text(
                            text = errMsg,
                            color = MaxSemanticColors.textPrimary,
                            style = MaxType.Body
                        )
                    },
                    confirmButton = {
                        MaxButton(
                            text = "OK",
                            onClick = { viewModel.dismissErrorDialog() },
                            variant = MaxButtonVariant.PRIMARY
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    item: SkillItemUiModel,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MaxCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(MaxDimensions.radiusLg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaxType.Body,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isEnabled) MaxSemanticColors.textPrimary else MaxSemanticColors.textMuted
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    Text(
                        text = item.description,
                        style = MaxType.Caption,
                        color = MaxSemanticColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(MaxDimensions.spaceSm))

                Switch(
                    checked = item.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaxSemanticColors.primaryRoyal,
                        uncheckedThumbColor = MaxSemanticColors.textMuted,
                        uncheckedTrackColor = MaxSemanticColors.surfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                // Platform Badge
                val badgeColor = when (item.platformSupport) {
                    PlatformSupport.ANDROID_ONLY -> MaxSemanticColors.success
                    PlatformSupport.WINDOWS_ONLY -> MaxSemanticColors.accentCyan
                    PlatformSupport.BOTH -> MaxSemanticColors.primaryRoyal
                }
                MaxStatusBadge(label = item.platformSupport.label, color = badgeColor)

                // Version Chip
                Surface(
                    color = MaxSemanticColors.surfaceVariant,
                    shape = RoundedCornerShape(MaxDimensions.radiusPill)
                ) {
                    Text(
                        text = "v${item.version}",
                        style = MaxType.Eyebrow,
                        color = MaxSemanticColors.textMuted,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                // Steps Count
                Surface(
                    color = MaxSemanticColors.surfaceVariant,
                    shape = RoundedCornerShape(MaxDimensions.radiusPill)
                ) {
                    Text(
                        text = "${item.stepCount} steps",
                        style = MaxType.Eyebrow,
                        color = MaxSemanticColors.textMuted,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillDetailsDialog(
    item: SkillItemUiModel,
    onDismiss: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val skill = item.skill

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaxSemanticColors.surface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaxType.Headline,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textPrimary
                    )
                    Text(
                        text = "ID: ${item.skillId}",
                        style = MaxType.Eyebrow,
                        color = MaxSemanticColors.textMuted
                    )
                }
                Switch(
                    checked = item.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaxSemanticColors.primaryRoyal,
                        uncheckedThumbColor = MaxSemanticColors.textMuted,
                        uncheckedTrackColor = MaxSemanticColors.surfaceVariant
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaxDimensions.spaceXs)
            ) {
                // Description
                Text(
                    text = "Description",
                    style = MaxType.Caption,
                    fontWeight = FontWeight.Bold,
                    color = MaxSemanticColors.textMuted
                )
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                Text(
                    text = item.description,
                    style = MaxType.Caption,
                    color = MaxSemanticColors.textSecondary
                )

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                // Metadata Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Version", style = MaxType.Eyebrow, color = MaxSemanticColors.textMuted, fontWeight = FontWeight.Bold)
                        Text(text = "v${item.version}", style = MaxType.Caption, color = MaxSemanticColors.textPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Author", style = MaxType.Eyebrow, color = MaxSemanticColors.textMuted, fontWeight = FontWeight.Bold)
                        Text(text = item.author, style = MaxType.Caption, color = MaxSemanticColors.textPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Platforms", style = MaxType.Eyebrow, color = MaxSemanticColors.textMuted, fontWeight = FontWeight.Bold)
                        Text(text = item.platformSupport.label, style = MaxType.Caption, color = MaxSemanticColors.accentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                // Required Capabilities
                Text(
                    text = "Allowed Capabilities (${item.requiredCapabilities.size})",
                    style = MaxType.Caption,
                    fontWeight = FontWeight.Bold,
                    color = MaxSemanticColors.textMuted
                )
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.space2xs)
                ) {
                    item.requiredCapabilities.take(4).forEach { cap ->
                        Surface(
                            color = MaxSemanticColors.surfaceVariant,
                            shape = RoundedCornerShape(MaxDimensions.radiusSm)
                        ) {
                            Text(
                                text = cap,
                                style = MaxType.Eyebrow,
                                color = MaxSemanticColors.textSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                // Inputs / Slots
                if (skill.inputs.isNotEmpty()) {
                    Text(
                        text = "Input Parameters (${skill.inputs.size})",
                        style = MaxType.Caption,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textMuted
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    skill.inputs.forEach { input ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "• ${input.name} (${input.type})",
                                style = MaxType.Caption,
                                color = MaxSemanticColors.textSecondary
                            )
                            if (input.required) {
                                Text(text = "Required", style = MaxType.Eyebrow, color = MaxSemanticColors.destructive, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text(text = "Optional", style = MaxType.Eyebrow, color = MaxSemanticColors.textMuted)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                // Steps summary
                Text(
                    text = "Workflow Execution: ${item.stepCount} Steps",
                    style = MaxType.Caption,
                    fontWeight = FontWeight.Bold,
                    color = MaxSemanticColors.textMuted
                )
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                Text(
                    text = "Failure Policy: ${skill.failurePolicy.name}",
                    style = MaxType.Caption,
                    color = MaxSemanticColors.textSecondary
                )
            }
        },
        confirmButton = {
            MaxButton(
                text = "Close",
                onClick = onDismiss,
                variant = MaxButtonVariant.SECONDARY
            )
        },
        dismissButton = {
            MaxButton(
                text = "Delete",
                onClick = onDelete,
                variant = MaxButtonVariant.DANGER
            )
        }
    )
}
