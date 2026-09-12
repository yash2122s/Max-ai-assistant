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
import androidx.compose.ui.unit.sp
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

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Capabilities Index",
                onBackClick = onNavigateBack
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

            // Search Box
            MaxTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                label = "Search Tools & Actions",
                placeholder = "e.g. bluetooth, FLASHLIGHT, youtube",
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaxTextMuted) },
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
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${uiState.totalToolsCount} Tools • ${uiState.totalActionsCount} Execution Actions",
                            fontSize = 12.sp,
                            color = MaxTextMuted
                        )
                    }
                    MaxStatusBadge(
                        label = "FROZEN",
                        color = MaxPrimary
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxPrimary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (tool.supportsPlanner) {
                        BadgeTag(text = "PLANNER", color = MaxSecondary)
                    }
                    if (tool.requiresAccessibility) {
                        BadgeTag(text = "ACCESSIBILITY", color = MaxPrimary)
                    }
                    if (tool.requiresNetwork) {
                        BadgeTag(text = "NETWORK", color = MaxTextMuted)
                    }
                }
            }

            if (tool.supportedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                Text(
                    text = "Supported Actions:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxTextMuted
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
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaxSurfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = action,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaxTextSecondary
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
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
