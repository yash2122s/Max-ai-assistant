package com.example.ui.routines

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
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    onNavigateBack: () -> Unit,
    viewModel: RoutinesViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var routineNameInput by remember { mutableStateOf("") }
    var stepsJsonInput by remember { mutableStateOf("[{\"action\":\"SET_DND\",\"arguments\":{\"enabled\":true}}]") }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RoutinesUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Routines & Automation",
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.startCreate() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Routine",
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

            if (uiState.isExecuting) {
                MaxCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaxDimensions.spaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        CircularProgressIndicator(
                            color = MaxPrimary,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Executing routine: '${uiState.currentlyRunningRoutine}'...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd),
                contentPadding = PaddingValues(bottom = MaxDimensions.spaceXl)
            ) {
                items(uiState.routines, key = { it.name }) { routine ->
                    RoutineCard(
                        routine = routine,
                        isRunning = uiState.isExecuting && uiState.currentlyRunningRoutine == routine.name,
                        onRun = { viewModel.runRoutine(routine.name) },
                        onDelete = { viewModel.deleteRoutine(routine.name) }
                    )
                }
            }
        }

        if (uiState.isCreatingNew) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissCreate() },
                containerColor = MaxSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaxDimensions.spaceLg, vertical = MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                ) {
                    Text(
                        text = "New Custom Routine",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextPrimary
                    )

                    MaxTextField(
                        value = routineNameInput,
                        onValueChange = { routineNameInput = it },
                        label = "Routine Name",
                        placeholder = "e.g. commute, focus, study",
                        modifier = Modifier.fillMaxWidth()
                    )

                    MaxTextField(
                        value = stepsJsonInput,
                        onValueChange = { stepsJsonInput = it },
                        label = "Steps JSON Definition",
                        placeholder = "[{\"action\":\"SET_DND\",\"arguments\":{\"enabled\":true}}]",
                        singleLine = false,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaxButton(
                            text = "Cancel",
                            onClick = { viewModel.dismissCreate() },
                            variant = MaxButtonVariant.OUTLINE,
                            modifier = Modifier.padding(end = MaxDimensions.spaceSm)
                        )
                        MaxButton(
                            text = "Create",
                            onClick = {
                                viewModel.createCustomRoutine(routineNameInput, stepsJsonInput)
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
private fun RoutineCard(
    routine: RoutineItemModel,
    isRunning: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    MaxCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
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
                        text = routine.name.replaceFirstChar { it.uppercase() },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(if (routine.isBuiltIn) MaxPrimary.copy(alpha = 0.2f) else MaxSecondary.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (routine.isBuiltIn) "BUILT-IN" else "CUSTOM",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (routine.isBuiltIn) MaxPrimary else MaxSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    MaxButton(
                        text = if (isRunning) "Running..." else "Run",
                        onClick = onRun,
                        variant = MaxButtonVariant.PRIMARY,
                        enabled = !isRunning,
                        modifier = Modifier.height(34.dp)
                    )
                    if (!routine.isBuiltIn) {
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

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            // Steps preview
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                routine.steps.forEachIndexed { index, step ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
                    ) {
                        Text(
                            text = "${index + 1}.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxTextMuted
                        )
                        Text(
                            text = step.description,
                            fontSize = 12.sp,
                            color = MaxTextSecondary
                        )
                    }
                }
            }
        }
    }
}
