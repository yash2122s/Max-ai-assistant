package com.example.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
}

data class NavigationDrawerItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val accentColor: Color = Color(0xFF0066FF),
    val badge: String? = null
)

val MainDrawerItems = listOf(
    NavigationDrawerItem(MaxDestinations.HOME, "Home & Voice", Icons.Default.Home, Color(0xFF0066FF)),
    NavigationDrawerItem(MaxDestinations.LICENSE, "License & Premium", Icons.Default.Verified, Color(0xFF10B981)),
    NavigationDrawerItem(MaxDestinations.MEMORY, "Memory Wallet", Icons.Default.FolderShared, Color(0xFF8B5CF6)),
    NavigationDrawerItem(MaxDestinations.PERIOD, "Period Tracker", Icons.Default.Favorite, Color(0xFFEC4899)),
    NavigationDrawerItem(MaxDestinations.REMINDERS, "Reminders", Icons.Default.Alarm, Color(0xFFF59E0B)),
    NavigationDrawerItem(MaxDestinations.SKILLS, "Automation Skills", Icons.Default.Extension, Color(0xFF6366F1)),
    NavigationDrawerItem(MaxDestinations.ROUTINES, "Routines & Automation", Icons.Default.AutoAwesome, Color(0xFF8B5CF6)),
    NavigationDrawerItem(MaxDestinations.NOTIFICATIONS, "Notifications & Auto-Reply", Icons.Default.Notifications, Color(0xFF06B6D4)),
    NavigationDrawerItem(MaxDestinations.PERMISSIONS, "Permissions & Shizuku", Icons.Default.Security, Color(0xFF10B981)),
    NavigationDrawerItem(MaxDestinations.CAPABILITIES, "Capabilities Index", Icons.Default.CheckCircle, Color(0xFF3B82F6)),
    NavigationDrawerItem(MaxDestinations.SETTINGS, "Settings", Icons.Default.Settings, Color(0xFF64748B)),
    NavigationDrawerItem(MaxDestinations.ABOUT, "About MAX", Icons.Default.Info, Color(0xFF0284C7)),
    NavigationDrawerItem(MaxDestinations.PRIVACY, "Privacy Policy", Icons.Default.Lock, Color(0xFF14B8A6))
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
        modifier = modifier
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

        val navigateBackToDrawer: () -> Unit = {
            if (navController.popBackStack()) {
                onOpenDrawer()
            }
        }

        // Controlled Screens in Drawer Navigation
        composable(MaxDestinations.LICENSE) {
            com.example.ui.license.LicenseActivationScreen(
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.SETTINGS) {
            com.example.ui.settings.SettingsScreen(
                onNavigateBack = navigateBackToDrawer,
                onNavigateToSkills = { navController.navigate(MaxDestinations.SKILLS) },
                onNavigateToLicense = { navController.navigate(MaxDestinations.LICENSE) }
            )
        }

        composable(MaxDestinations.SKILLS) {
            com.example.ui.skills.SkillsScreen(
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.PERMISSIONS) {
            com.example.ui.permissions.PermissionsScreen(
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.MEMORY) {
            com.example.ui.memory.MemoryScreen(
                onNavigateBack = navigateBackToDrawer,
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
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.REMINDERS) {
            com.example.ui.reminders.RemindersScreen(
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.ROUTINES) {
            com.example.ui.routines.RoutinesScreen(
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.NOTIFICATIONS) {
            com.example.ui.notifications.NotificationsScreen(
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.CAPABILITIES) {
            com.example.ui.capabilities.CapabilitiesScreen(
                onNavigateBack = navigateBackToDrawer
            )
        }

        composable(MaxDestinations.ABOUT) {
            com.example.ui.about.AboutScreen(
                onNavigateBack = navigateBackToDrawer,
                onNavigateToPrivacy = { navController.navigate(MaxDestinations.PRIVACY) }
            )
        }

        composable(MaxDestinations.PRIVACY) {
            com.example.ui.about.PrivacyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
