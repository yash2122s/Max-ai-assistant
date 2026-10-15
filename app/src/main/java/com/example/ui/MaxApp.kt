package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.home.HomeViewModel
import com.example.ui.navigation.MainDrawerItems
import com.example.ui.navigation.MaxDestinations
import com.example.ui.navigation.MaxNavGraph
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MaxApp(
    homeViewModel: HomeViewModel = viewModel()
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: MaxDestinations.HOME

    val isDarkMode by homeViewModel.isDarkMode.collectAsState()

    MaxAppTheme(darkTheme = isDarkMode) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = (currentRoute != MaxDestinations.ONBOARDING),
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(310.dp)
                ) {
                    // Frosted Luxury Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                            )
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(MaxRadius.md))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(NeonPink, MagentaRose)
                                        )
                                    )
                                    .border(MaxStroke.hairline, MaxColors.extra.borderSubtle, RoundedCornerShape(MaxRadius.md)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(MaxIconTokens.Large)
                                )
                            }
                            Column {
                                Text(
                                    text = "MAX",
                                    style = MaxType.Headline,
                                    fontWeight = FontWeight.Bold,
                                    color = MaxSemanticColors.textPrimary
                                )
                                Text(
                                    text = "AUTONOMOUS AGENTIC OS",
                                    style = MaxType.Eyebrow,
                                    color = MaxSemanticColors.accentCyan
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = MaxStroke.hairline)

                    Spacer(modifier = Modifier.height(10.dp))

                    // Drawer Items
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp)
                    ) {
                        MainDrawerItems.forEach { item ->
                            val selected = currentRoute == item.route
                            val onDrawerItemClick = {
                                coroutineScope.launch { drawerState.close() }
                                if (currentRoute != item.route) {
                                    val startDestId = navController.graph.findStartDestination().id
                                    navController.navigate(item.route) {
                                        popUpTo(startDestId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                            val itemTint = when {
                                selected -> MaterialTheme.colorScheme.primary
                                item.group == com.example.ui.navigation.DrawerIconGroup.TRUST -> MaterialTheme.colorScheme.primary
                                item.group == com.example.ui.navigation.DrawerIconGroup.ALERT -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            NavigationDrawerItem(
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(MaxRadius.sm))
                                            .background(
                                                if (selected) itemTint.copy(alpha = 0.22f)
                                                else itemTint.copy(alpha = 0.10f)
                                            )
                                            .border(
                                                MaxStroke.hairline,
                                                if (selected) itemTint.copy(alpha = 0.45f) else Color.Transparent,
                                                RoundedCornerShape(MaxRadius.sm)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.title,
                                            tint = itemTint,
                                            modifier = Modifier.size(MaxIconTokens.Medium)
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = item.title,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) MaxSemanticColors.textPrimary else MaxSemanticColors.textSecondary,
                                        style = MaxType.Body
                                    )
                                },
                                selected = selected,
                                onClick = onDrawerItemClick,
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .heightIn(min = 48.dp)
                                    .iosPressEffect(scalePressed = 0.98f, alphaPressed = 0.85f, onClick = onDrawerItemClick),
                                shape = RoundedCornerShape(MaxDimensions.radiusMd),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    unselectedContainerColor = Color.Transparent
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = MaxStroke.hairline)

                    // Footer with Developer Credit
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = "MAX AI • Luminous Edition",
                            style = MaxType.Body,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.accentCyan
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Created by Yaswanth • Autonomous AI Companion",
                            style = MaxType.Caption,
                            fontWeight = FontWeight.Normal,
                            color = MaxSemanticColors.textSecondary
                        )
                    }
                }
            }
        ) {
            MaxNavGraph(
                navController = navController,
                homeViewModel = homeViewModel,
                onOpenDrawer = {
                    coroutineScope.launch { drawerState.open() }
                }
            )
        }
    }
}

