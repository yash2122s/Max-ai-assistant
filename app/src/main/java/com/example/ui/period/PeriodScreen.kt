package com.example.ui.period

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.PeriodLog
import com.example.ui.components.*
import com.example.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodScreen(
    onNavigateBack: () -> Unit,
    viewModel: PeriodViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showEditorSheet by remember { mutableStateOf(false) }
    var notesInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isCreatingNew, uiState.selectedLogForEdit) {
        if (uiState.isCreatingNew) {
            notesInput = ""
            showEditorSheet = true
        } else if (uiState.selectedLogForEdit != null) {
            notesInput = uiState.selectedLogForEdit?.notes ?: ""
            showEditorSheet = true
        } else {
            showEditorSheet = false
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PeriodUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Period & Cycle Tracker",
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.startCreate() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Log",
                            tint = MaxPrimary
                        )
                    }
                }
            )
        },
        containerColor = MaxBgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = MaxDimensions.spaceMd)
        ) {
            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            // Prediction & Phase Hero Card
            val prediction = uiState.prediction
            MaxCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = prediction.currentPhase,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (prediction.isBleeding) Color(0xFFFF5252) else MaxPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = prediction.phaseDescription,
                                fontSize = 12.sp,
                                color = MaxTextMuted
                            )
                        }
                        if (prediction.daysUntilNext != null && prediction.daysUntilNext >= 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                                    .background(MaxPrimary.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "in ${prediction.daysUntilNext} days",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaxPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                    // Cycle Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        StatTile(label = "Avg Cycle", value = "${prediction.avgCycleDays} days", modifier = Modifier.weight(1f))
                        StatTile(label = "Avg Duration", value = "${prediction.avgDurationDays} days", modifier = Modifier.weight(1f))
                        if (prediction.nextStartDateStr != null) {
                            StatTile(label = "Next Period", value = prediction.nextStartDateStr, modifier = Modifier.weight(1f))
                        }
                    }

                    if (prediction.fertileWindowStr != null) {
                        Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color(0xFFFF4081),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Fertile Window: ${prediction.fertileWindowStr}",
                                fontSize = 11.sp,
                                color = MaxTextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            // Quick Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                MaxButton(
                    text = "Log Period Start",
                    onClick = { viewModel.startNewPeriod() },
                    variant = MaxButtonVariant.PRIMARY,
                    modifier = Modifier.weight(1f)
                )
                MaxButton(
                    text = "Log Period End",
                    onClick = { viewModel.endCurrentPeriod() },
                    variant = MaxButtonVariant.OUTLINE,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

            Text(
                text = "Cycle History",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaxTextPrimary
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

            if (uiState.logs.isEmpty()) {
                MaxEmptyState(
                    title = "No Cycles Logged",
                    message = "Tap 'Log Period Start' or 'Add Log' to start tracking your cycle.",
                    icon = Icons.Default.CalendarMonth,
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
                    items(uiState.logs, key = { it.id }) { log ->
                        PeriodLogItemCard(
                            log = log,
                            onEdit = { viewModel.startEdit(log) },
                            onDelete = { viewModel.deletePeriod(log.id) }
                        )
                    }
                }
            }
        }

        if (showEditorSheet) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissEditor() },
                containerColor = MaxSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaxDimensions.spaceLg, vertical = MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                ) {
                    Text(
                        text = if (uiState.selectedLogForEdit != null) "Edit Cycle Log" else "Add Cycle Log",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextPrimary
                    )

                    MaxTextField(
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        label = "Symptoms & Notes",
                        placeholder = "e.g. Cramps, mood changes, light flow",
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
                            text = "Save",
                            onClick = {
                                val current = uiState.selectedLogForEdit
                                val start = current?.startDate ?: System.currentTimeMillis()
                                val end = current?.endDate
                                viewModel.saveCustomPeriod(start, end, notesInput)
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
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
            .background(MaxSurfaceVariant)
            .padding(MaxDimensions.spaceSm),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaxTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaxTextMuted
            )
        }
    }
}

@Composable
private fun PeriodLogItemCard(
    log: PeriodLog,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)
    val startStr = Instant.ofEpochMilli(log.startDate).atZone(ZoneId.systemDefault()).format(formatter)
    val endStr = log.endDate?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(formatter) } ?: "Ongoing"

    MaxCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    Text(
                        text = "$startStr - $endStr",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextPrimary
                    )
                    if (log.durationDays > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                                .background(MaxPrimary.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${log.durationDays} days",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaxPrimary
                            )
                        }
                    }
                }

                if (!log.notes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    Text(
                        text = log.notes,
                        fontSize = 12.sp,
                        color = MaxTextSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = MaxTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaxError.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
