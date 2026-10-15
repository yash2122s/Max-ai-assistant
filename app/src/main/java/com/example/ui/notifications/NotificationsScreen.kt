package com.example.ui.notifications

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.AutoReplyRule
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    MaxScreen(
        title = "Notifications & Auto-Reply",
        eyebrow = "COMMUNICATION ENGINE",
        auroraMode = AuroraMode.SUBTLE,
        scrollable = false,
        onBack = onNavigateBack,
        topBarActions = {
            IconButton(onClick = { viewModel.startCreateRule() }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Rule",
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

            // Settings & Toggles Card
            MaxCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
                    Text(
                        text = "Auto-Reply Engine",
                        style = MaxType.Headline,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                    SwitchRow(
                        title = "Global Notification Auto-Reply",
                        subtitle = "Enable automatic message replies via Notification Listener",
                        isChecked = uiState.isAutoReplyEnabled,
                        onCheckedChange = { viewModel.toggleAutoReply(it) }
                    )

                    HorizontalDivider(color = MaxSemanticColors.border, thickness = MaxStroke.hairline, modifier = Modifier.padding(vertical = MaxDimensions.spaceSm))

                    SwitchRow(
                        title = "WhatsApp Auto-Reply",
                        subtitle = "Respond to incoming WhatsApp conversations",
                        isChecked = uiState.isWhatsAppAutoReplyEnabled,
                        onCheckedChange = { viewModel.toggleWhatsAppAutoReply(it) }
                    )

                    if (uiState.isWhatsAppAutoReplyEnabled) {
                        Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))
                        WhatsAppAudienceSection(
                            scope = uiState.whatsappAutoReplyScope,
                            selectedContacts = uiState.selectedContacts,
                            onScopeChange = { viewModel.setWhatsAppAutoReplyScope(it) },
                            onOpenPicker = { viewModel.openContactPicker() },
                            onRemoveContact = { viewModel.removeSelectedContact(it) }
                        )
                    }

                    HorizontalDivider(color = MaxSemanticColors.border, thickness = MaxStroke.hairline, modifier = Modifier.padding(vertical = MaxDimensions.spaceSm))

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
                style = MaxType.Headline,
                fontWeight = FontWeight.Bold,
                color = MaxSemanticColors.textPrimary
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
                containerColor = MaxSemanticColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaxDimensions.spaceLg, vertical = MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                ) {
                    Text(
                        text = if (uiState.selectedRuleForEdit != null) "Edit Auto-Reply Rule" else "New Auto-Reply Rule",
                        style = MaxType.Headline,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textPrimary
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

        if (uiState.isContactPickerOpen) {
            ContactPickerBottomSheet(
                searchQuery = uiState.contactSearchQuery,
                onSearchQueryChange = { viewModel.updateContactSearchQuery(it) },
                availableContacts = uiState.availableDeviceContacts,
                selectedContacts = uiState.selectedContacts,
                onAddContact = { viewModel.addSelectedContact(it) },
                onRemoveContact = { viewModel.removeSelectedContact(it) },
                onDismiss = { viewModel.closeContactPicker() }
            )
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
                style = MaxType.Body,
                fontWeight = FontWeight.SemiBold,
                color = MaxSemanticColors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaxType.Caption,
                color = MaxSemanticColors.textMuted
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaxSemanticColors.accentCyan,
                uncheckedThumbColor = MaxSemanticColors.textMuted,
                uncheckedTrackColor = MaxSemanticColors.surfaceVariant
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
                            .background(MaxSemanticColors.accentCyan.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "IF: \"${rule.triggerKeyword}\"",
                            style = MaxType.Caption,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.accentCyan
                        )
                    }
                    if (!rule.targetContact.isNullOrBlank()) {
                        Text(
                            text = "@ ${rule.targetContact}",
                            style = MaxType.Caption,
                            color = MaxSemanticColors.primaryRoyal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

                Text(
                    text = "Reply: \"${rule.replyMessage}\"",
                    style = MaxType.Body,
                    color = MaxSemanticColors.textSecondary
                )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatsAppAudienceSection(
    scope: String,
    selectedContacts: Set<String>,
    onScopeChange: (String) -> Unit,
    onOpenPicker: () -> Unit,
    onRemoveContact: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
            .background(MaxSemanticColors.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp)
    ) {
        Text(
            text = "Auto-Reply Audience",
            style = MaxType.Body,
            fontWeight = FontWeight.Bold,
            color = MaxSemanticColors.textPrimary
        )
        Text(
            text = "Choose who receives automatic WhatsApp replies",
            style = MaxType.Caption,
            color = MaxSemanticColors.textMuted
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudienceOptionChip(
                label = "All Contacts",
                selected = scope == "ALL",
                onClick = { onScopeChange("ALL") },
                modifier = Modifier.weight(1f)
            )
            AudienceOptionChip(
                label = "Selected Only (${selectedContacts.size})",
                selected = scope == "SELECTED",
                onClick = { onScopeChange("SELECTED") },
                modifier = Modifier.weight(1f)
            )
        }

        if (scope == "SELECTED") {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Whitelisted Contacts",
                    style = MaxType.Caption,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxSemanticColors.textPrimary
                )
                TextButton(
                    onClick = onOpenPicker,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaxSemanticColors.accentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Add Contacts",
                        style = MaxType.Caption,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxSemanticColors.accentCyan
                    )
                }
            }

            if (selectedContacts.isEmpty()) {
                Text(
                    text = "No contacts selected yet. Auto-replies are paused until contacts are added to this whitelist.",
                    style = MaxType.Caption,
                    color = MaxSemanticColors.destructive.copy(alpha = 0.9f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    selectedContacts.forEach { contact ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                                .background(MaxSemanticColors.accentCyan.copy(alpha = 0.15f))
                                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
                        ) {
                            Text(
                                text = contact,
                                style = MaxType.Caption,
                                fontWeight = FontWeight.Medium,
                                color = MaxSemanticColors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { onRemoveContact(contact) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove $contact",
                                    tint = MaxSemanticColors.textSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudienceOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
            .background(if (selected) MaxSemanticColors.accentCyan else MaxSemanticColors.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaxType.Caption,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaxSemanticColors.textPrimary else MaxSemanticColors.textSecondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPickerBottomSheet(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    availableContacts: List<com.example.automation.contact.Contact>,
    selectedContacts: Set<String>,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaxSemanticColors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = MaxDimensions.spaceLg, vertical = MaxDimensions.spaceMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select WhatsApp Contacts",
                    style = MaxType.Headline,
                    fontWeight = FontWeight.Bold,
                    color = MaxSemanticColors.textPrimary
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", style = MaxType.Body, fontWeight = FontWeight.Bold, color = MaxSemanticColors.accentCyan)
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            MaxTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = "Search Contacts",
                placeholder = "Type name or phone number...",
                modifier = Modifier.fillMaxWidth()
            )

            if (searchQuery.isNotBlank() && availableContacts.none { it.name.equals(searchQuery, true) || it.phoneNumber == searchQuery }) {
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                        .background(MaxSemanticColors.surfaceVariant)
                        .clickable { onAddContact(searchQuery.trim()) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Add custom: \"${searchQuery.trim()}\"",
                            style = MaxType.Body,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxSemanticColors.accentCyan
                        )
                        Text(
                            text = "Allow auto-reply to this exact name or number",
                            style = MaxType.Caption,
                            color = MaxSemanticColors.textMuted
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.AddCircle,
                        contentDescription = "Add custom",
                        tint = MaxSemanticColors.accentCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            val filtered = remember(searchQuery, availableContacts) {
                if (searchQuery.isBlank()) availableContacts
                else availableContacts.filter {
                    it.name.contains(searchQuery, ignoreCase = true) || it.phoneNumber.contains(searchQuery)
                }
            }

            if (filtered.isEmpty() && searchQuery.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No device contacts found",
                        style = MaxType.Body,
                        color = MaxSemanticColors.textMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(filtered, key = { "${it.name}_${it.phoneNumber}" }) { contact ->
                        val isSelected = selectedContacts.any {
                            com.example.automation.contact.ContactResolver.matchesSelectedContact(it, contact.name) ||
                            com.example.automation.contact.ContactResolver.matchesSelectedContact(it, contact.phoneNumber)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                                .background(if (isSelected) MaxSemanticColors.accentCyan.copy(alpha = 0.08f) else MaxSemanticColors.surfaceVariant.copy(alpha = 0.35f))
                            .clickable {
                                if (isSelected) {
                                    onRemoveContact(contact.name)
                                    onRemoveContact(contact.phoneNumber)
                                } else {
                                    onAddContact(contact.name)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.name,
                                style = MaxType.Body,
                                fontWeight = FontWeight.SemiBold,
                                color = MaxSemanticColors.textPrimary
                            )
                            Text(
                                text = "${contact.type} • ${contact.phoneNumber}",
                                style = MaxType.Caption,
                                color = MaxSemanticColors.textMuted
                            )
                        }
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onAddContact(contact.name)
                                } else {
                                    onRemoveContact(contact.name)
                                    onRemoveContact(contact.phoneNumber)
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaxSemanticColors.accentCyan,
                                checkmarkColor = Color.White
                            )
                        )
                    }
                }
            }
        }
    }
}
}

