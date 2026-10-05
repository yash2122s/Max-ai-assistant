package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    MaxTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = (currentRoute != MaxDestinations.ONBOARDING),
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaxSurfaceCard,
                    drawerContentColor = MaxTextPrimary,
                    modifier = Modifier.width(310.dp)
                ) {
                    // Frosted Luxury Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(MaxSurfaceElevated, MaxSurfaceCard)
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
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(MaxPrimaryRoyal, MaxAccentCyan)
                                        )
                                    )
                                    .border(MaxDimensions.borderHairline, Color(0x6600D2FF), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "MAX",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = MaxTextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "AUTONOMOUS AGENTIC OS",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = MaxAccentCyan,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaxBorderHairline, thickness = MaxDimensions.borderHairline)

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
                            NavigationDrawerItem(
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (selected) item.accentColor.copy(alpha = 0.25f)
                                                else item.accentColor.copy(alpha = 0.12f)
                                            )
                                            .border(
                                                MaxDimensions.borderHairline,
                                                if (selected) item.accentColor.copy(alpha = 0.5f) else Color.Transparent,
                                                RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.title,
                                            tint = item.accentColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = item.title,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) MaxTextPrimary else MaxTextSecondary,
                                        fontSize = 14.sp,
                                        letterSpacing = (-0.2).sp
                                    )
                                },
                                selected = selected,
                                onClick = {
                                    coroutineScope.launch { drawerState.close() }
                                    if (currentRoute != item.route) {
                                        navController.navigate(item.route) {
                                            popUpTo(MaxDestinations.HOME) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .iosPressEffect(scalePressed = 0.98f, alphaPressed = 0.85f, onClick = {
                                        coroutineScope.launch { drawerState.close() }
                                        if (currentRoute != item.route) {
                                            navController.navigate(item.route) {
                                                popUpTo(MaxDestinations.HOME) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }),
                                shape = RoundedCornerShape(MaxDimensions.radiusMd),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = MaxPrimaryRoyal.copy(alpha = 0.14f),
                                    unselectedContainerColor = Color.Transparent
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = MaxBorderHairline, thickness = MaxDimensions.borderHairline)

                    // Footer with Developer Credit
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = "MAX AI • Royal Edition",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxPrimaryRoyal
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Created by Yaswanth • Autonomous AI Companion",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaxTextTertiary
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

