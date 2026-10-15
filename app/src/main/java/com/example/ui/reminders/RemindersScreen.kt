package com.example.ui.reminders

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.Reminder
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    onNavigateBack: () -> Unit,
    viewModel: RemindersViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showEditorSheet by remember { mutableStateOf(false) }
    var messageInput by remember { mutableStateOf("") }
    var selectedMinutesOffset by remember { mutableStateOf(15) }

    LaunchedEffect(uiState.isCreatingNew, uiState.selectedReminderForEdit) {
        if (uiState.isCreatingNew) {
            messageInput = ""
            selectedMinutesOffset = 15
            showEditorSheet = true
        } else if (uiState.selectedReminderForEdit != null) {
            messageInput = uiState.selectedReminderForEdit?.message ?: ""
            showEditorSheet = true
        } else {
            showEditorSheet = false
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RemindersUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    MaxScreen(
        title = "Reminders",
        eyebrow = "TASK MANAGEMENT",
        auroraMode = AuroraMode.SUBTLE,
        scrollable = false,
        onBack = onNavigateBack,
        topBarActions = {
            IconButton(onClick = { viewModel.startCreate() }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Reminder",
                    tint = MaxSemanticColors.accentCyan
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MaxDimensions.spaceMd)
        ) {
            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            // Filter Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                listOf("ALL", "PENDING", "COMPLETED").forEach { filter ->
                    val isSelected = uiState.selectedFilter.equals(filter, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(if (isSelected) MaxSemanticColors.accentCyan else MaxSemanticColors.surfaceVariant)
                            .clickable { viewModel.onFilterSelected(filter) }
                            .padding(vertical = MaxDimensions.spaceSm),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter,
                            style = MaxType.Caption,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else MaxSemanticColors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

            val displayList = when (uiState.selectedFilter) {
                "PENDING" -> uiState.pendingReminders
                "COMPLETED" -> uiState.completedReminders
                else -> uiState.pendingReminders + uiState.completedReminders
            }

            if (displayList.isEmpty()) {
                MaxEmptyState(
                    title = "No Reminders Found",
                    message = if (uiState.selectedFilter == "PENDING") "You're all caught up! No pending reminders." else "No reminders in this category.",
                    icon = Icons.Default.NotificationsNone,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm),
                    contentPadding = PaddingValues(bottom = MaxDimensions.spaceXl)
                ) {
                    items(displayList, key = { it.id }) { reminder ->
                        ReminderItemCard(
                            reminder = reminder,
                            onToggleComplete = { viewModel.toggleComplete(reminder) },
                            onEdit = { viewModel.startEdit(reminder) },
                            onDelete = { viewModel.deleteReminder(reminder.id) }
                        )
                    }
                }
            }
        }

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
                        text = if (uiState.selectedReminderForEdit != null) "Edit Reminder" else "New Reminder",
                        style = MaxType.Headline,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textPrimary
                    )

                    MaxTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        label = "Reminder Note",
                        placeholder = "e.g. Call Mom, Buy groceries, Check server",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Remind me in:",
                        style = MaxType.Caption,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxSemanticColors.textMuted
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        listOf(15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 1440 to "1 day").forEach { (mins, label) ->
                            val isSelected = selectedMinutesOffset == mins
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                                    .background(if (isSelected) MaxSemanticColors.accentCyan else MaxSemanticColors.surfaceVariant)
                                    .clickable { selectedMinutesOffset = mins }
                                    .padding(vertical = MaxDimensions.spaceSm),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaxType.Caption,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else MaxSemanticColors.textSecondary
                                )
                            }
                        }
                    }

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
                            text = "Save",
                            onClick = {
                                val trigger = System.currentTimeMillis() + (selectedMinutesOffset * 60 * 1000L)
                                viewModel.saveReminder(
                                    message = messageInput,
                                    triggerAtMillis = trigger
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

@Composable
private fun ReminderItemCard(
    reminder: Reminder,
    onToggleComplete: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCompleted = reminder.status.equals("completed", ignoreCase = true)
    val sdf = SimpleDateFormat("EEE, MMM d 'at' hh:mm a", Locale.getDefault())
    val timeStr = sdf.format(Date(reminder.triggerAt))

    MaxCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm),
                modifier = Modifier.weight(1f)
            ) {
                IconButton(onClick = onToggleComplete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = "Complete",
                        tint = if (isCompleted) MaxSemanticColors.success else MaxSemanticColors.textMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = reminder.message,
                        style = MaxType.Body,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCompleted) MaxSemanticColors.textMuted else MaxSemanticColors.textPrimary,
                        textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = timeStr,
                        style = MaxType.Caption,
                        color = if (isCompleted) MaxSemanticColors.textMuted else MaxSemanticColors.accentCyan
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = MaxSemanticColors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaxSemanticColors.destructive.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
