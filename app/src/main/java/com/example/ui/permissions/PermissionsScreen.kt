package com.example.ui.permissions

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun PermissionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: PermissionsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PermissionsUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is PermissionsUiEvent.OpenSettings -> {}
            }
        }
    }

    val runtimePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }

    val onPermissionItemClick: (PermissionItemState) -> Unit = { item ->
        if (viewModel.isRuntimePermission(item.id)) {
            val permissions = viewModel.getRuntimePermissions(item.id)
            if (permissions.isNotEmpty()) {
                runtimePermissionLauncher.launch(permissions)
            } else {
                viewModel.handlePermissionAction(item.id)
            }
        } else {
            viewModel.handlePermissionAction(item.id)
        }
    }

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Permissions & System",
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.refreshPermissions() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
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
                .verticalScroll(scrollState)
                .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceSm),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
        ) {
            // Overview card
            val totalGranted = uiState.items.count { it.isGranted }
            val totalItems = uiState.items.size
            MaxCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaxDimensions.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "System Readiness",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxTextPrimary
                        )
                        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                        Text(
                            text = "$totalGranted of $totalItems permissions active",
                            fontSize = 13.sp,
                            color = MaxTextMuted
                        )
                    }
                    MaxStatusBadge(
                        label = if (totalGranted == totalItems) "OPTIMAL" else "PARTIAL",
                        color = if (totalGranted == totalItems) MaxSuccess else AccentAmber
                    )
                }
            }

            // Categories
            PermissionCategorySection(
                categoryTitle = "Core System & Automation",
                icon = Icons.Default.SettingsSuggest,
                items = uiState.items.filter { it.category == PermissionCategory.CORE_SYSTEM },
                onItemClick = onPermissionItemClick
            )

            PermissionCategorySection(
                categoryTitle = "Voice & Vision",
                icon = Icons.Default.Mic,
                items = uiState.items.filter { it.category == PermissionCategory.VOICE_AND_VISION },
                onItemClick = onPermissionItemClick
            )

            PermissionCategorySection(
                categoryTitle = "Device Communications",
                icon = Icons.Default.Phone,
                items = uiState.items.filter { it.category == PermissionCategory.COMMUNICATIONS },
                onItemClick = onPermissionItemClick
            )

            PermissionCategorySection(
                categoryTitle = "Privileged Access (Optional)",
                icon = Icons.Default.Terminal,
                items = uiState.items.filter { it.category == PermissionCategory.ADVANCED_ROOT },
                onItemClick = onPermissionItemClick
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
        }
    }
}

@Composable
private fun PermissionCategorySection(
    categoryTitle: String,
    icon: ImageVector,
    items: List<PermissionItemState>,
    onItemClick: (PermissionItemState) -> Unit,
    modifier: Modifier = Modifier
) {
    MaxCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaxPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = categoryTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextPrimary
                )
            }
            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaxBorder,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = MaxDimensions.spaceSm)
                    )
                }
                PermissionRow(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
private fun PermissionRow(
    item: PermissionItemState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MaxDimensions.space2xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
            ) {
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxTextPrimary
                )
                if (item.isRequired) {
                    Text(
                        text = "REQUIRED",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
            Text(
                text = item.description,
                fontSize = 12.sp,
                color = MaxTextMuted,
                lineHeight = 16.sp
            )
        }
        Spacer(modifier = Modifier.width(MaxDimensions.spaceSm))
        if (item.isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = MaxSuccess,
                modifier = Modifier.size(24.dp)
            )
        } else {
            MaxButton(
                text = "Grant",
                onClick = onClick,
                variant = if (item.isRequired) MaxButtonVariant.PRIMARY else MaxButtonVariant.OUTLINE,
                modifier = Modifier.height(34.dp)
            )
        }
    }
}
