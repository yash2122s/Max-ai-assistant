package com.example.ui.reminders

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
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

        val editing = uiState.selectedReminderForEdit
        if (uiState.isCreatingNew || editing != null) {
            var messageInput by remember(editing?.id) { mutableStateOf(editing?.message.orEmpty()) }
            var selectedMinutesOffset by remember(editing?.id) { mutableStateOf<Int?>(if (editing == null) 15 else null) }
            var customPickedMillis by remember(editing?.id) { mutableStateOf<Long?>(null) }

            var isNotifGranted by remember {
                mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
            }

            val notifPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                isNotifGranted = granted || NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (!isNotifGranted) {
                    Toast.makeText(context, "Notification permission is required for reminders to pop up", Toast.LENGTH_LONG).show()
                }
            }

            val alarmManager = remember { context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager }
            val isExactAlarmGranted = remember {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager?.canScheduleExactAlarms() ?: true
                } else true
            }

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

                    // Permission Warning: Notifications
                    if (!isNotifGranted) {
                        Surface(
                            shape = RoundedCornerShape(MaxDimensions.radiusSm),
                            color = MaxSemanticColors.destructive.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaxSemanticColors.destructive.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(MaxDimensions.spaceSm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Notifications disabled. Reminders will not alert you.",
                                    style = MaxType.Caption,
                                    color = MaxSemanticColors.destructive,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                        }
                                    }
                                ) {
                                    Text("Enable", color = MaxSemanticColors.accentCyan, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Permission Warning: Exact Alarms
                    if (!isExactAlarmGranted) {
                        Surface(
                            shape = RoundedCornerShape(MaxDimensions.radiusSm),
                            color = MaxSemanticColors.accentCyan.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaxSemanticColors.accentCyan.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(MaxDimensions.spaceSm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Exact alarm access is off. Alarms may be delayed.",
                                    style = MaxType.Caption,
                                    color = MaxSemanticColors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                        }
                                    }
                                ) {
                                    Text("Allow", color = MaxSemanticColors.accentCyan, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    MaxTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        label = "Reminder Note",
                        placeholder = "e.g. Call Mom, Buy groceries, Check server",
                        modifier = Modifier.fillMaxWidth()
                    )

                    val previewDateText = when {
                        customPickedMillis != null -> {
                            "Scheduled for " + SimpleDateFormat("EEE, MMM d 'at' hh:mm a", Locale.getDefault()).format(Date(customPickedMillis!!))
                        }
                        selectedMinutesOffset != null -> {
                            val targetTime = System.currentTimeMillis() + selectedMinutesOffset!! * 60_000L
                            "Scheduled for " + SimpleDateFormat("EEE, MMM d 'at' hh:mm a", Locale.getDefault()).format(Date(targetTime))
                        }
                        editing != null -> {
                            "Scheduled for " + SimpleDateFormat("EEE, MMM d 'at' hh:mm a", Locale.getDefault()).format(Date(editing.triggerAt))
                        }
                        else -> "Remind me in:"
                    }

                    Text(
                        text = previewDateText,
                        style = MaxType.Caption,
                        fontWeight = FontWeight.SemiBold,
                        color = if (customPickedMillis != null || selectedMinutesOffset != null) MaxSemanticColors.accentCyan else MaxSemanticColors.textMuted
                    )

                    // Quick offset presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        listOf(15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 1440 to "1 day").forEach { (mins, label) ->
                            val isSelected = selectedMinutesOffset == mins && customPickedMillis == null
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                                    .background(if (isSelected) MaxSemanticColors.accentCyan else MaxSemanticColors.surfaceVariant)
                                    .clickable {
                                        selectedMinutesOffset = mins
                                        customPickedMillis = null
                                    }
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

                    // Custom Time Picker Trigger
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val now = Calendar.getInstance()
                                TimePickerDialog(
                                    context,
                                    { _, hourOfDay, minute ->
                                        val cal = Calendar.getInstance().apply {
                                            set(Calendar.HOUR_OF_DAY, hourOfDay)
                                            set(Calendar.MINUTE, minute)
                                            set(Calendar.SECOND, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }
                                        if (cal.timeInMillis <= System.currentTimeMillis()) {
                                            cal.add(Calendar.DAY_OF_YEAR, 1)
                                        }
                                        customPickedMillis = cal.timeInMillis
                                        selectedMinutesOffset = null
                                    },
                                    now.get(Calendar.HOUR_OF_DAY),
                                    now.get(Calendar.MINUTE),
                                    false
                                ).show()
                            },
                            shape = RoundedCornerShape(MaxDimensions.radiusSm),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (customPickedMillis != null) MaxSemanticColors.accentCyan else MaxSemanticColors.border)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = "Pick Clock Time",
                                tint = if (customPickedMillis != null) MaxSemanticColors.accentCyan else MaxSemanticColors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (customPickedMillis != null) "Time Selected" else "Pick Clock Time...",
                                color = if (customPickedMillis != null) MaxSemanticColors.accentCyan else MaxSemanticColors.textSecondary,
                                style = MaxType.Caption
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MaxButton(
                                text = "Cancel",
                                onClick = { viewModel.dismissEditor() },
                                variant = MaxButtonVariant.OUTLINE,
                                modifier = Modifier.padding(end = MaxDimensions.spaceSm)
                            )
                            MaxButton(
                                text = "Save",
                                onClick = {
                                    val trigger = customPickedMillis
                                        ?: selectedMinutesOffset?.let { System.currentTimeMillis() + it * 60_000L }
                                        ?: if (editing == null) (System.currentTimeMillis() + 15 * 60_000L) else null
                                    viewModel.saveReminder(
                                        message = messageInput,
                                        triggerAtMillis = trigger
                                    )
                                },
                                variant = MaxButtonVariant.PRIMARY
                            )
                        }
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
