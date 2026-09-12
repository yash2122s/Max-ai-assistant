package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.automation.activity.ActionReasonFormatter
import com.example.automation.activity.AgentActivityState
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.activity.AgentStage
import com.example.automation.activity.AgentStepState
import com.example.automation.activity.AgentTraceEntry
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentActivityCard(
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val activityState by AgentActivityTracker.state.collectAsState()
    var isExpanded by remember { mutableStateOf(false) }
    var showDevTrace by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Auto-expand when a new goal/task begins
    LaunchedEffect(activityState.goal, activityState.stage) {
        if (activityState.stage != AgentStage.IDLE && !activityState.goal.isNullOrBlank()) {
            isExpanded = true
        }
    }

    val stageDisplayName = when (activityState.stage) {
        AgentStage.IDLE -> if (activityState.goal.isNullOrBlank()) "READY" else "IDLE"
        else -> activityState.stage.name
    }

    val stageColor = when {
        activityState.isAmbiguous -> Color(0xFFF59E0B) // Amber
        activityState.stage == AgentStage.COMPLETED -> Emerald400
        activityState.stage == AgentStage.FAILED -> NeonPink
        activityState.stage == AgentStage.PLANNING -> AccentCyan
        activityState.stage in listOf(AgentStage.EXECUTING, AgentStage.VERIFYING, AgentStage.OBSERVING) -> NeonGold
        activityState.stage in listOf(AgentStage.LISTENING, AgentStage.UNDERSTANDING) -> NeonBlue
        activityState.stage == AgentStage.RESPONDING -> Color(0xFFC084FC)
        else -> TextMuted
    }

    val pulseTransition = rememberInfiniteTransition(label = "badge_pulse")
    val badgeAlpha by pulseTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "badge_alpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(animationSpec = tween(200))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(stageColor.copy(alpha = 0.7f), BorderDark)),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark.copy(alpha = 0.95f)
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
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(stageColor.copy(alpha = if (activityState.active) badgeAlpha else 1.0f))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (activityState.isAmbiguous) "UNDERSTANDING" else stageDisplayName,
                        color = stageColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    if (activityState.totalSteps > 0 && !activityState.isAmbiguous) {
                        Text(
                            text = " · Step ${activityState.currentStepIndex}/${activityState.totalSteps}",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = TextMuted
                        )
                    }
                    if (!activityState.active && activityState.stage != AgentStage.IDLE && onDismiss != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                AgentActivityTracker.reset()
                                onDismiss()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = TextMuted
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
                    !activityState.goal.isNullOrBlank() -> activityState.goal ?: ""
                    activityState.stage == AgentStage.IDLE -> "Ask MAX to automate tasks, open apps, or query your screen"
                    else -> "Processing agent request..."
                }
                Text(
                    text = summaryText,
                    color = if (activityState.isAmbiguous) Color(0xFFFCD34D) else TextLight,
                    fontSize = 13.sp,
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
                        .heightIn(max = 350.dp)
                        .verticalScroll(scrollState)
                        .padding(top = 8.dp)
                ) {
                    HorizontalDivider(color = BorderDark, modifier = Modifier.padding(vertical = 6.dp))

                    // Goal Section
                    if (!activityState.goal.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(text = "🎙", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(text = "Goal", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(text = activityState.goal ?: "", color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Ambiguity Warning Section
                    if (activityState.isAmbiguous) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF78350F).copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Ambiguous",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Ambiguous Request",
                                        color = Color(0xFFFCD34D),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = activityState.clarificationPrompt ?: "What would you like me to do with \"${activityState.goal}\"?",
                                        color = TextLight,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Steps Checklist with Action Reasons
                    if (activityState.steps.isNotEmpty() && !activityState.isAmbiguous) {
                        Text(
                            text = "⚙ Execution Plan (${activityState.steps.size} steps)",
                            color = TextMuted,
                            fontSize = 11.sp,
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
                            colors = CardDefaults.cardColors(containerColor = SurfaceDarker),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "👁", fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Observing: Capturing current screen",
                                        color = AccentCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Why: \"Checking the current screen so MAX can determine what is currently visible.\"",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Result: ✓ Current screen captured (App: ${activityState.currentApp ?: "Active Screen"}, UI elements: ${activityState.observedNodeCount})",
                                    color = Emerald400,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Error Message
                    activityState.error?.let { err ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = NeonPink.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "Error: $err",
                                color = NeonPink,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = BorderDark, modifier = Modifier.padding(vertical = 6.dp))

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
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (showDevTrace) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle Developer Trace",
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Developer Trace Log List
                    AnimatedVisibility(visible = showDevTrace) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .background(SurfaceDarker, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            if (activityState.traceLogs.isEmpty()) {
                                Text(
                                    text = "No trace events recorded yet.",
                                    color = TextMuted,
                                    fontSize = 10.sp,
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
        "COMPLETED" -> Icons.Default.Check to Emerald400
        "RUNNING" -> Icons.Default.Refresh to NeonBlue
        "VERIFYING" -> Icons.Default.PlayArrow to NeonGold
        "FAILED" -> Icons.Default.Close to NeonPink
        else -> Icons.Default.PlayArrow to TextMuted.copy(alpha = 0.4f)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarker.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(8.dp),
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
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = ActionReasonFormatter.formatActionTitle(step.actionType, step.targetText),
                    color = if (step.status in listOf("COMPLETED", "RUNNING", "VERIFYING")) TextLight else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (step.status == "RUNNING") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "⟳", color = NeonBlue, fontSize = 12.sp)
                }
            }

            // Why: Action Reason
            step.reason?.let { reasonText ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Why: \"$reasonText\"",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }

            // Result
            step.result?.let { resultText ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Result: $resultText",
                    color = if (step.status == "COMPLETED") Emerald400 else if (step.status == "FAILED") NeonPink else AccentCyan,
                    fontSize = 11.sp,
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
        true -> Emerald400
        false -> NeonPink
        else -> AccentCyan
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[$timeStr] ",
            color = TextMuted.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "${entry.tag}: ",
            color = tagColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = entry.message,
            color = TextLight,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
