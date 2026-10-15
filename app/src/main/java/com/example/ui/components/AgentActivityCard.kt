package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.automation.activity.ActionReasonFormatter
import com.example.automation.activity.AgentActivityState
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.activity.AgentStage
import com.example.automation.activity.AgentStepState
import com.example.automation.activity.AgentTraceEntry
import com.example.ui.theme.*
import com.example.ui.home.AssistantPresentation
import com.example.ui.home.AssistantPhase
import com.example.ui.home.phaseColor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentActivityCard(
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onStopTask: (() -> Unit)? = null,
    presentation: AssistantPresentation? = null,
    activityState: AgentActivityState = AgentActivityTracker.state.collectAsStateWithLifecycle().value
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showDevTrace by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Stay compact when stages change; details expand only at the user's request.
    LaunchedEffect(activityState.goal) {
        isExpanded = false
    }

    val stageDisplayName = presentation?.label ?: when (activityState.stage) {
        AgentStage.IDLE -> if (activityState.goal.isNullOrBlank()) "READY" else "IDLE"
        else -> activityState.stage.name
    }

    val stageColor = presentation?.let { phaseColor(it.phase) } ?: when {
        activityState.isAmbiguous -> MaxSemanticColors.warning
        activityState.stage == AgentStage.COMPLETED -> MaxSemanticColors.success
        activityState.stage == AgentStage.FAILED -> MaxSemanticColors.accentPink
        activityState.stage == AgentStage.PLANNING -> MaxSemanticColors.accentCyan
        activityState.stage in listOf(AgentStage.EXECUTING, AgentStage.VERIFYING, AgentStage.OBSERVING) -> MaxSemanticColors.royalGold
        activityState.stage in listOf(AgentStage.LISTENING, AgentStage.UNDERSTANDING) -> MaxSemanticColors.info
        activityState.stage == AgentStage.RESPONDING -> MaxSemanticColors.primaryRoyal
        else -> MaxSemanticColors.textMuted
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(animationSpec = tween(200))
            .border(
                width = MaxStroke.hairline,
                brush = Brush.linearGradient(listOf(stageColor.copy(alpha = 0.5f), MaxSemanticColors.border)),
                shape = MaxShapes.card
            ),
        shape = MaxShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaxSemanticColors.surfaceElevated
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top Bar: Stage Badge + Summary + Expand / Collapse Icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(stageColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (presentation == null && activityState.isAmbiguous) "INPUT NEEDED" else stageDisplayName,
                        color = stageColor,
                        style = MaxType.Caption,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (activityState.totalSteps > 0 && !activityState.isAmbiguous) {
                        Text(
                            text = " · Step ${activityState.currentStepIndex}/${activityState.totalSteps}",
                            color = MaxSemanticColors.textMuted,
                            style = MaxType.Caption,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((presentation?.canStop ?: activityState.active) || presentation?.phase == AssistantPhase.CANCELLING) {
                        MaxButton(
                            text = if (presentation?.phase == AssistantPhase.CANCELLING) "STOPPING" else "STOP TASK",
                            onClick = {
                                onStopTask?.invoke() ?: com.example.automation.engine.TaskCancellationCoordinator.cancelActiveTask()
                            },
                            enabled = presentation?.phase != AssistantPhase.CANCELLING,
                            variant = MaxButtonVariant.DANGER,
                            height = 36.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaxSemanticColors.textMuted
                        )
                    }
                    if (!activityState.active && activityState.stage != AgentStage.IDLE && onDismiss != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                AgentActivityTracker.reset()
                                onDismiss()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss",
                                tint = MaxSemanticColors.textMuted
                            )
                        }
                    }
                }
            }

            // Compact View Summary
            if (!isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                val summaryText = when {
                    activityState.isAmbiguous -> activityState.clarificationPrompt ?: "Clarification required"
                    !activityState.currentTarget.isNullOrBlank() -> "${activityState.currentAction ?: "Action"}: ${activityState.currentTarget}"
                    !activityState.goal.isNullOrBlank() -> activityState.goal
                    activityState.stage == AgentStage.IDLE -> "Ask MAX to automate tasks, open apps, or query your screen"
                    else -> "Processing agent request..."
                }
                Text(
                    text = summaryText,
                    color = if (activityState.isAmbiguous) MaxSemanticColors.royalGold else MaxSemanticColors.textPrimary,
                    style = MaxType.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Expanded Full View with Bounded Vertical Scroll to NEVER Push BottomControls Off-Screen
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                        .padding(top = 8.dp)
                ) {
                    HorizontalDivider(color = MaxSemanticColors.border, modifier = Modifier.padding(vertical = 6.dp))

                    // Goal Section
                    if (!activityState.goal.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = MaxSemanticColors.textMuted,
                                modifier = Modifier.size(MaxIconTokens.Small)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(text = "Goal", color = MaxSemanticColors.textMuted, style = MaxType.Caption, fontWeight = FontWeight.Bold)
                                Text(text = activityState.goal, color = MaxSemanticColors.textPrimary, style = MaxType.Body, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Ambiguity Warning Section
                    if (activityState.isAmbiguous) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaxSemanticColors.warningSurface),
                            shape = RoundedCornerShape(MaxRadius.md),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaxSemanticColors.warning.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = "Ambiguous",
                                    tint = MaxSemanticColors.warning,
                                    modifier = Modifier.size(MaxIconTokens.Medium)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Ambiguous Request",
                                        color = MaxSemanticColors.royalGold,
                                        style = MaxType.Caption,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = activityState.clarificationPrompt ?: "What would you like me to do with \"${activityState.goal}\"?",
                                        color = MaxSemanticColors.textPrimary,
                                        style = MaxType.Caption
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Steps Checklist with Action Reasons
                    if (activityState.steps.isNotEmpty() && !activityState.isAmbiguous) {
                        Text(
                            text = "Execution Plan (${activityState.steps.size} steps)",
                            color = MaxSemanticColors.textMuted,
                            style = MaxType.Caption,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        activityState.steps.forEach { step ->
                            StepDetailRow(step = step)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Observation Info with Rationale
                    if (!activityState.currentApp.isNullOrBlank() || activityState.observedNodeCount > 0) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaxSemanticColors.surfaceVariant),
                            shape = RoundedCornerShape(MaxRadius.md),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Visibility,
                                        contentDescription = null,
                                        tint = MaxSemanticColors.accentCyan,
                                        modifier = Modifier.size(MaxIconTokens.Small)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Observing: Capturing current screen",
                                        color = MaxSemanticColors.accentCyan,
                                        style = MaxType.Caption,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Why: \"Checking the current screen so MAX can determine what is currently visible.\"",
                                    color = MaxSemanticColors.textMuted,
                                    style = MaxType.Caption
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Result: Current screen captured (App: ${activityState.currentApp ?: "Active Screen"}, UI elements: ${activityState.observedNodeCount})",
                                    color = MaxSemanticColors.success,
                                    style = MaxType.Caption
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Error Message
                    activityState.error?.let { err ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaxSemanticColors.destructiveSurface
                            ),
                            shape = RoundedCornerShape(MaxRadius.sm),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaxSemanticColors.destructive.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "Error: $err",
                                color = MaxSemanticColors.destructive,
                                style = MaxType.Caption,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (activityState.active) {
                        Spacer(modifier = Modifier.height(6.dp))
                        MaxButton(
                            text = "STOP ACTIVE TASK",
                            onClick = {
                                onStopTask?.invoke() ?: com.example.automation.engine.TaskCancellationCoordinator.cancelActiveTask()
                            },
                            variant = MaxButtonVariant.DANGER,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HorizontalDivider(color = MaxSemanticColors.border, modifier = Modifier.padding(vertical = 6.dp))

                    // Developer Trace Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDevTrace = !showDevTrace },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Developer Trace (${activityState.traceLogs.size} events)",
                            color = MaxSemanticColors.accentCyan,
                            style = MaxType.Caption,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (showDevTrace) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Toggle Developer Trace",
                            tint = MaxSemanticColors.accentCyan,
                            modifier = Modifier.size(MaxIconTokens.Medium)
                        )
                    }

                    // Developer Trace Log List
                    AnimatedVisibility(visible = showDevTrace) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .background(MaxSemanticColors.surfaceVariant, RoundedCornerShape(MaxRadius.sm))
                                .padding(8.dp)
                        ) {
                            if (activityState.traceLogs.isEmpty()) {
                                Text(
                                    text = "No trace events recorded yet.",
                                    color = MaxSemanticColors.textMuted,
                                    style = MaxType.Caption,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                activityState.traceLogs.takeLast(20).forEach { entry ->
                                    TraceLogRow(entry = entry)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDetailRow(step: AgentStepState) {
    val (icon, color) = when (step.status) {
        "COMPLETED" -> Icons.Rounded.Check to MaxSemanticColors.success
        "RUNNING" -> Icons.Rounded.Refresh to MaxSemanticColors.primaryRoyal
        "VERIFYING" -> Icons.Rounded.PlayArrow to MaxSemanticColors.royalGold
        "FAILED" -> Icons.Rounded.Close to MaxSemanticColors.destructive
        else -> Icons.Rounded.PlayArrow to MaxSemanticColors.textMuted.copy(alpha = 0.4f)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaxSemanticColors.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(MaxRadius.sm),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = step.status,
                    tint = color,
                    modifier = Modifier.size(MaxIconTokens.Small)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = ActionReasonFormatter.formatActionTitle(step.actionType, step.targetText),
                    color = if (step.status in listOf("COMPLETED", "RUNNING", "VERIFYING")) MaxSemanticColors.textPrimary else MaxSemanticColors.textMuted,
                    style = MaxType.Caption,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (step.status == "RUNNING") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "⟳", color = MaxSemanticColors.primaryRoyal, style = MaxType.Caption)
                }
            }

            // Why: Action Reason
            step.reason?.let { reasonText ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Why: \"$reasonText\"",
                    color = MaxSemanticColors.textMuted,
                    style = MaxType.Caption,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }

            // Result
            step.result?.let { resultText ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Result: $resultText",
                    color = if (step.status == "COMPLETED") MaxSemanticColors.success else if (step.status == "FAILED") MaxSemanticColors.destructive else MaxSemanticColors.accentCyan,
                    style = MaxType.Caption,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
        }
    }
}

@Composable
private fun TraceLogRow(entry: AgentTraceEntry) {
    val timeStr = remember(entry.timestamp) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(entry.timestamp))
    }

    val tagColor = when (entry.isSuccess) {
        true -> MaxSemanticColors.success
        false -> MaxSemanticColors.accentPink
        else -> MaxSemanticColors.accentCyan
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[$timeStr] ",
            color = MaxSemanticColors.textMuted.copy(alpha = 0.7f),
            style = MaxType.Caption,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "${entry.tag}: ",
            color = tagColor,
            style = MaxType.Caption,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = entry.message,
            color = MaxSemanticColors.textPrimary,
            style = MaxType.Caption,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
