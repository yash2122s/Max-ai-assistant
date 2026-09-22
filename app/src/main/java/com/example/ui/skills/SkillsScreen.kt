package com.example.ui.skills

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Automation Skills",
                onBackClick = onNavigateBack,
                actions = {
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
                        modifier = Modifier.padding(end = MaxDimensions.spaceSm)
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaxBgDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading && uiState.installedSkills.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaxPrimary
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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxTextMuted,
                            letterSpacing = 0.5.sp,
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
                    containerColor = MaxSurface,
                    title = {
                        Text(
                            text = "Skill Already Exists",
                            fontWeight = FontWeight.Bold,
                            color = MaxTextPrimary
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "A skill with ID '${conflict.existingSkill.skillId}' is already installed.",
                                color = MaxTextPrimary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                            Text(
                                text = "Installed: v${conflict.existingSkill.version} by ${conflict.existingSkill.author}",
                                color = MaxTextMuted,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "New: v${conflict.newSkill.version} by ${conflict.newSkill.author}",
                                color = MaxTextSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                            Text(
                                text = "Do you want to replace the existing skill with the imported version?",
                                color = MaxTextPrimary,
                                fontSize = 14.sp,
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
                    containerColor = MaxSurface,
                    title = {
                        Text(
                            text = "Delete Skill?",
                            fontWeight = FontWeight.Bold,
                            color = MaxError
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to permanently delete '${toDelete.name}'? This cannot be undone.",
                            color = MaxTextPrimary,
                            fontSize = 14.sp
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
                    containerColor = MaxSurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaxError)
                            Spacer(modifier = Modifier.width(MaxDimensions.spaceSm))
                            Text(
                                text = "Skill Import Error",
                                fontWeight = FontWeight.Bold,
                                color = MaxError
                            )
                        }
                    },
                    text = {
                        Text(
                            text = errMsg,
                            color = MaxTextPrimary,
                            fontSize = 14.sp
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isEnabled) MaxTextPrimary else MaxTextMuted
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    Text(
                        text = item.description,
                        fontSize = 13.sp,
                        color = MaxTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(MaxDimensions.spaceSm))

                Switch(
                    checked = item.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaxBgDark,
                        checkedTrackColor = MaxPrimary,
                        uncheckedThumbColor = MaxTextMuted,
                        uncheckedTrackColor = MaxSurfaceVariant
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
                    PlatformSupport.ANDROID_ONLY -> MaxSuccess
                    PlatformSupport.WINDOWS_ONLY -> MaxPrimary
                    PlatformSupport.BOTH -> AccentPurple
                }
                MaxStatusBadge(label = item.platformSupport.label, color = badgeColor)

                // Version Chip
                Surface(
                    color = MaxSurfaceVariant,
                    shape = RoundedCornerShape(MaxDimensions.radiusPill)
                ) {
                    Text(
                        text = "v${item.version}",
                        fontSize = 11.sp,
                        color = MaxTextMuted,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                // Steps Count
                Surface(
                    color = MaxSurfaceVariant,
                    shape = RoundedCornerShape(MaxDimensions.radiusPill)
                ) {
                    Text(
                        text = "${item.stepCount} steps",
                        fontSize = 11.sp,
                        color = MaxTextMuted,
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
        containerColor = MaxSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextPrimary
                    )
                    Text(
                        text = "ID: ${item.skillId}",
                        fontSize = 11.sp,
                        color = MaxTextMuted
                    )
                }
                Switch(
                    checked = item.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaxBgDark,
                        checkedTrackColor = MaxPrimary,
                        uncheckedThumbColor = MaxTextMuted,
                        uncheckedTrackColor = MaxSurfaceVariant
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
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextMuted
                )
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                Text(
                    text = item.description,
                    fontSize = 13.sp,
                    color = MaxTextSecondary
                )

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                // Metadata Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Version", fontSize = 11.sp, color = MaxTextMuted, fontWeight = FontWeight.Bold)
                        Text(text = "v${item.version}", fontSize = 13.sp, color = MaxTextPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Author", fontSize = 11.sp, color = MaxTextMuted, fontWeight = FontWeight.Bold)
                        Text(text = item.author, fontSize = 13.sp, color = MaxTextPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Platforms", fontSize = 11.sp, color = MaxTextMuted, fontWeight = FontWeight.Bold)
                        Text(text = item.platformSupport.label, fontSize = 13.sp, color = MaxPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                // Required Capabilities
                Text(
                    text = "Allowed Capabilities (${item.requiredCapabilities.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextMuted
                )
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.space2xs)
                ) {
                    item.requiredCapabilities.take(4).forEach { cap ->
                        Surface(
                            color = MaxSurfaceVariant,
                            shape = RoundedCornerShape(MaxDimensions.radiusSm)
                        ) {
                            Text(
                                text = cap,
                                fontSize = 11.sp,
                                color = MaxTextSecondary,
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextMuted
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    skill.inputs.forEach { input ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "• ${input.name} (${input.type})",
                                fontSize = 12.sp,
                                color = MaxTextSecondary
                            )
                            if (input.required) {
                                Text(text = "Required", fontSize = 11.sp, color = MaxError, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text(text = "Optional", fontSize = 11.sp, color = MaxTextMuted)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                // Steps summary
                Text(
                    text = "Workflow Execution: ${item.stepCount} Steps",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextMuted
                )
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                Text(
                    text = "Failure Policy: ${skill.failurePolicy.name}",
                    fontSize = 12.sp,
                    color = MaxTextSecondary
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
