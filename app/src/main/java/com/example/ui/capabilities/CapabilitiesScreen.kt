package com.example.ui.capabilities

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun CapabilitiesScreen(
    onNavigateBack: () -> Unit,
    viewModel: CapabilitiesViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MaxScreen(
        title = "Capabilities Index",
        eyebrow = "REGISTRY",
        auroraMode = AuroraMode.SUBTLE,
        scrollable = false,
        onBack = onNavigateBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MaxDimensions.spaceMd)
        ) {
            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            // Search Box
            MaxTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                label = "Search Tools & Actions",
                placeholder = "e.g. bluetooth, FLASHLIGHT, youtube",
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaxSemanticColors.textMuted) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            // Summary Card
            MaxCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaxDimensions.spaceMd),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Tool & Action Registry",
                            style = MaxType.Headline,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${uiState.totalToolsCount} Tools • ${uiState.totalActionsCount} Execution Actions",
                            style = MaxType.Caption,
                            color = MaxSemanticColors.textSecondary
                        )
                    }
                    MaxStatusBadge(
                        label = "FROZEN",
                        color = MaxSemanticColors.accentCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm),
                contentPadding = PaddingValues(bottom = MaxDimensions.spaceXl)
            ) {
                items(uiState.tools, key = { it.name }) { tool ->
                    CapabilityToolCard(tool = tool)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityToolCard(
    tool: CapabilityToolItem,
    modifier: Modifier = Modifier
) {
    MaxCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tool.name,
                    style = MaxType.Headline,
                    fontWeight = FontWeight.Bold,
                    color = MaxSemanticColors.accentCyan
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (tool.supportsPlanner) {
                        BadgeTag(text = "PLANNER", color = MaxSemanticColors.primaryRoyal)
                    }
                    if (tool.requiresAccessibility) {
                        BadgeTag(text = "ACCESSIBILITY", color = MaxSemanticColors.accentCyan)
                    }
                    if (tool.requiresNetwork) {
                        BadgeTag(text = "NETWORK", color = MaxSemanticColors.textMuted)
                    }
                }
            }

            if (tool.supportedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                Text(
                    text = "Supported Actions:",
                    style = MaxType.Eyebrow,
                    color = MaxSemanticColors.textMuted
                )
                Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tool.supportedActions.forEach { action ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(MaxRadius.xs))
                                .background(MaxSemanticColors.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = action,
                                style = MaxType.Caption,
                                fontWeight = FontWeight.Bold,
                                color = MaxSemanticColors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(MaxRadius.xs))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaxType.Eyebrow,
            color = color
        )
    }
}
