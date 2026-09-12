package com.example.ui.notifications

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
import com.example.data.local.AutoReplyRule
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    viewModel: NotificationsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var triggerKeywordInput by remember { mutableStateOf("") }
    var replyMessageInput by remember { mutableStateOf("") }
    var targetContactInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isCreatingRule, uiState.selectedRuleForEdit) {
        if (uiState.isCreatingRule) {
            triggerKeywordInput = ""
            replyMessageInput = ""
            targetContactInput = ""
        } else if (uiState.selectedRuleForEdit != null) {
            val r = uiState.selectedRuleForEdit!!
            triggerKeywordInput = r.triggerKeyword
            replyMessageInput = r.replyMessage
            targetContactInput = r.targetContact ?: ""
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is NotificationsUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Notifications & Auto-Reply",
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.startCreateRule() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Rule",
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

            // Settings & Toggles Card
            MaxCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
                    Text(
                        text = "Auto-Reply Engine",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextPrimary
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                    SwitchRow(
                        title = "Global Notification Auto-Reply",
                        subtitle = "Enable automatic message replies via Notification Listener",
                        isChecked = uiState.isAutoReplyEnabled,
                        onCheckedChange = { viewModel.toggleAutoReply(it) }
                    )

                    Divider(color = MaxBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = MaxDimensions.spaceSm))

                    SwitchRow(
                        title = "WhatsApp Auto-Reply",
                        subtitle = "Respond to incoming WhatsApp conversations",
                        isChecked = uiState.isWhatsAppAutoReplyEnabled,
                        onCheckedChange = { viewModel.toggleWhatsAppAutoReply(it) }
                    )

                    Divider(color = MaxBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = MaxDimensions.spaceSm))

                    SwitchRow(
                        title = "AI Fallback Reply",
                        subtitle = "Generate smart AI reply when no rule keyword matches",
                        isChecked = uiState.isAiFallbackEnabled,
                        onCheckedChange = { viewModel.toggleAiFallback(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

            Text(
                text = "Keyword Reply Rules (${uiState.rules.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaxTextPrimary
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

            if (uiState.rules.isEmpty()) {
                MaxEmptyState(
                    title = "No Custom Rules",
                    message = "Add keyword rules like 'urgent -> I will call in 5 mins' or 'where are you -> At work'.",
                    icon = Icons.Default.Rule,
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
                    items(uiState.rules, key = { it.id }) { rule ->
                        AutoReplyRuleCard(
                            rule = rule,
                            onToggle = { viewModel.toggleRuleEnabled(rule) },
                            onEdit = { viewModel.startEditRule(rule) },
                            onDelete = { viewModel.deleteRule(rule.id) }
                        )
                    }
                }
            }
        }

        if (uiState.isCreatingRule || uiState.selectedRuleForEdit != null) {
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
                        text = if (uiState.selectedRuleForEdit != null) "Edit Auto-Reply Rule" else "New Auto-Reply Rule",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxTextPrimary
                    )

                    MaxTextField(
                        value = triggerKeywordInput,
                        onValueChange = { triggerKeywordInput = it },
                        label = "Trigger Keyword",
                        placeholder = "e.g. urgent, where, busy",
                        modifier = Modifier.fillMaxWidth()
                    )

                    MaxTextField(
                        value = replyMessageInput,
                        onValueChange = { replyMessageInput = it },
                        label = "Reply Message",
                        placeholder = "e.g. I am in a meeting, will reach out shortly.",
                        modifier = Modifier.fillMaxWidth()
                    )

                    MaxTextField(
                        value = targetContactInput,
                        onValueChange = { targetContactInput = it },
                        label = "Target Contact Filter (Optional)",
                        placeholder = "Leave blank for any contact",
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
                                viewModel.saveRule(triggerKeywordInput, replyMessageInput, targetContactInput)
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
private fun SwitchRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaxTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaxTextMuted
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaxBgDark,
                checkedTrackColor = MaxPrimary,
                uncheckedThumbColor = MaxTextMuted,
                uncheckedTrackColor = MaxSurfaceVariant
            )
        )
    }
}

@Composable
private fun AutoReplyRuleCard(
    rule: AutoReplyRule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    MaxCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(MaxPrimary.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "IF: \"${rule.triggerKeyword}\"",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxPrimary
                        )
                    }
                    if (!rule.targetContact.isNullOrBlank()) {
                        Text(
                            text = "@ ${rule.targetContact}",
                            fontSize = 11.sp,
                            color = MaxSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

                Text(
                    text = "Reply: \"${rule.replyMessage}\"",
                    fontSize = 13.sp,
                    color = MaxTextSecondary
                )
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
