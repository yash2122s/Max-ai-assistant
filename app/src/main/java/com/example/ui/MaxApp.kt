package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            gesturesEnabled = (currentRoute == MaxDestinations.HOME),
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaxSurfaceDark,
                    drawerContentColor = MaxTextPrimary,
                    modifier = Modifier.width(300.dp)
                ) {
                    // Drawer Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaxSurfaceVariant)
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaxPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = OnPrimaryDark,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "MAX",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 20.sp,
                                    color = MaxTextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "NATIVE KOTLIN ENGINE",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp,
                                    color = MaxPrimary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = BorderDark, thickness = 1.dp)

                    Spacer(modifier = Modifier.height(12.dp))

                    // Drawer Items
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MainDrawerItems.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationDrawerItem(
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                if (selected) item.accentColor.copy(alpha = 0.2f)
                                                else item.accentColor.copy(alpha = 0.12f)
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
                                        color = if (selected) MaxPrimary else MaxTextPrimary,
                                        fontSize = 13.5.sp
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
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = item.accentColor.copy(alpha = 0.08f),
                                    unselectedContainerColor = Color.Transparent
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = BorderDark, thickness = 0.5.dp)

                    // Bottom info with Developer Credit
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = "Created by Yaswanth",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "v1.0 • Autonomous AI Companion",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaxTextMuted
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
