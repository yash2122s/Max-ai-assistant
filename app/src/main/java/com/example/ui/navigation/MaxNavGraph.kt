package com.example.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.ui.components.MaxButton
import com.example.ui.components.MaxButtonVariant
import com.example.ui.components.MaxTopBar
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import com.example.ui.theme.MaxBgDark
import com.example.ui.theme.MaxDimensions
import com.example.ui.theme.MaxPrimary
import com.example.ui.theme.MaxTextMuted
import com.example.ui.theme.MaxTextPrimary

object MaxDestinations {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val MEMORY = "memory"
    const val IMPORT_MEMORY = "import_memory"
    const val PERIOD = "period"
    const val REMINDERS = "reminders"
    const val ROUTINES = "routines"
    const val SKILLS = "skills"
    const val NOTIFICATIONS = "notifications"
    const val CAPABILITIES = "capabilities"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val LICENSE = "license"
    const val WEB_DASHBOARD = "web_dashboard"
}

enum class DrawerIconGroup {
    DEFAULT,
    TRUST, // Security / Lock / Verified -> primary
    ALERT  // Alarm / Notifications -> tertiary
}

data class NavigationDrawerItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val group: DrawerIconGroup = DrawerIconGroup.DEFAULT,
    val badge: String? = null
)

val MainDrawerItems = listOf(
    NavigationDrawerItem(MaxDestinations.HOME, "Home & Voice", Icons.Rounded.Home),
    NavigationDrawerItem(MaxDestinations.LICENSE, "License & Premium", Icons.Rounded.Verified, DrawerIconGroup.TRUST),
    NavigationDrawerItem(MaxDestinations.MEMORY, "Memory Wallet", Icons.Rounded.FolderShared),
    NavigationDrawerItem(MaxDestinations.PERIOD, "Period Tracker", Icons.Rounded.Favorite),
    NavigationDrawerItem(MaxDestinations.REMINDERS, "Reminders", Icons.Rounded.Alarm, DrawerIconGroup.ALERT),
    NavigationDrawerItem(MaxDestinations.SKILLS, "Automation Skills", Icons.Rounded.Extension),
    NavigationDrawerItem(MaxDestinations.ROUTINES, "Routines & Automation", Icons.Rounded.AutoAwesome),
    NavigationDrawerItem(MaxDestinations.NOTIFICATIONS, "Notifications & Auto-Reply", Icons.Rounded.Notifications, DrawerIconGroup.ALERT),
    NavigationDrawerItem(MaxDestinations.PERMISSIONS, "Permissions & Shizuku", Icons.Rounded.Security, DrawerIconGroup.TRUST),
    NavigationDrawerItem(MaxDestinations.CAPABILITIES, "Capabilities Index", Icons.Rounded.CheckCircle),
    NavigationDrawerItem(MaxDestinations.WEB_DASHBOARD, "Web Dashboard", Icons.Rounded.Language),
    NavigationDrawerItem(MaxDestinations.SETTINGS, "Settings", Icons.Rounded.Settings),
    NavigationDrawerItem(MaxDestinations.ABOUT, "About MAX", Icons.Rounded.Info),
    NavigationDrawerItem(MaxDestinations.PRIVACY, "Privacy Policy", Icons.Rounded.Lock, DrawerIconGroup.TRUST)
)

@Composable
fun MaxNavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.example.MyApplication
    val settingsManager = androidx.compose.runtime.remember { com.example.data.preferences.SettingsManager(context) }
    val currentUser by app.maxLicenseManager.currentUser.collectAsState()
    val isAuthCompleted = currentUser != null

    val initialDestination = if (settingsManager.onboardingStage == com.example.data.preferences.SettingsManager.OnboardingStage.COMPLETED && isAuthCompleted) {
        MaxDestinations.HOME
    } else {
        MaxDestinations.ONBOARDING
    }

    // Gatekeeper: if user signs out or has no active Google Account, redirect immediately to ONBOARDING
    androidx.compose.runtime.LaunchedEffect(currentUser) {
        if (currentUser == null) {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute != null && currentRoute != MaxDestinations.ONBOARDING) {
                navController.navigate(MaxDestinations.ONBOARDING) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = initialDestination,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(220))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeOut(animationSpec = tween(180))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(220))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeOut(animationSpec = tween(180))
        }
    ) {
        composable(MaxDestinations.HOME) {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenDrawer = onOpenDrawer,
                onNavigateTo = { route -> navController.navigate(route) }
            )
        }

        composable(MaxDestinations.ONBOARDING) {
            com.example.ui.onboarding.OnboardingScreen(
                onOnboardingFinished = {
                    navController.navigate(MaxDestinations.HOME) {
                        popUpTo(MaxDestinations.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        val navigateBackToPreviousOrDrawer: () -> Unit = {
            if (!navController.popBackStack()) {
                onOpenDrawer()
            }
        }

        // Controlled Screens in Drawer Navigation
        composable(MaxDestinations.LICENSE) {
            com.example.ui.license.LicenseActivationScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.SETTINGS) {
            com.example.ui.settings.SettingsScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer,
                onNavigateToSkills = { navController.navigate(MaxDestinations.SKILLS) },
                onNavigateToLicense = { navController.navigate(MaxDestinations.LICENSE) },
                onNavigateToWebDashboard = { navController.navigate(MaxDestinations.WEB_DASHBOARD) }
            )
        }

        composable(MaxDestinations.SKILLS) {
            com.example.ui.skills.SkillsScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.PERMISSIONS) {
            com.example.ui.permissions.PermissionsScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.MEMORY) {
            com.example.ui.memory.MemoryScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer,
                onNavigateToImport = { navController.navigate(MaxDestinations.IMPORT_MEMORY) }
            )
        }

        composable(MaxDestinations.IMPORT_MEMORY) {
            com.example.ui.memory.import_mem.MemoryImportScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.PERIOD) {
            com.example.ui.period.PeriodScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.REMINDERS) {
            com.example.ui.reminders.RemindersScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.ROUTINES) {
            com.example.ui.routines.RoutinesScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.NOTIFICATIONS) {
            com.example.ui.notifications.NotificationsScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.CAPABILITIES) {
            com.example.ui.capabilities.CapabilitiesScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.ABOUT) {
            com.example.ui.about.AboutScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer,
                onNavigateToPrivacy = { navController.navigate(MaxDestinations.PRIVACY) },
                onNavigateToWebDashboard = { navController.navigate(MaxDestinations.WEB_DASHBOARD) }
            )
        }

        composable(MaxDestinations.WEB_DASHBOARD) {
            com.example.ui.web.WebDashboardScreen(
                onNavigateBack = navigateBackToPreviousOrDrawer
            )
        }

        composable(MaxDestinations.PRIVACY) {
            com.example.ui.about.PrivacyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
