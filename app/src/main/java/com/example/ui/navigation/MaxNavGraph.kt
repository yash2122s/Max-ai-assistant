package com.example.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    const val NOTIFICATIONS = "notifications"
    const val CAPABILITIES = "capabilities"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
}

data class NavigationDrawerItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val badge: String? = null
)

val MainDrawerItems = listOf(
    NavigationDrawerItem(MaxDestinations.HOME, "Home & Voice", Icons.Default.Home),
    NavigationDrawerItem(MaxDestinations.MEMORY, "Memory Wallet", Icons.Default.FolderShared),
    NavigationDrawerItem(MaxDestinations.PERIOD, "Period Tracker", Icons.Default.Favorite),
    NavigationDrawerItem(MaxDestinations.REMINDERS, "Reminders", Icons.Default.Alarm),
    NavigationDrawerItem(MaxDestinations.ROUTINES, "Routines & Skills", Icons.Default.AutoAwesome),
    NavigationDrawerItem(MaxDestinations.NOTIFICATIONS, "Notifications & Auto-Reply", Icons.Default.Notifications),
    NavigationDrawerItem(MaxDestinations.PERMISSIONS, "Permissions & Shizuku", Icons.Default.Security),
    NavigationDrawerItem(MaxDestinations.CAPABILITIES, "Capabilities Index", Icons.Default.CheckCircle),
    NavigationDrawerItem(MaxDestinations.SETTINGS, "Settings", Icons.Default.Settings),
    NavigationDrawerItem(MaxDestinations.ABOUT, "About MAX", Icons.Default.Info),
    NavigationDrawerItem(MaxDestinations.PRIVACY, "Privacy Policy", Icons.Default.Lock)
)

@Composable
fun MaxNavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsManager = androidx.compose.runtime.remember { com.example.data.preferences.SettingsManager(context) }
    val initialDestination = if (settingsManager.onboardingStage == com.example.data.preferences.SettingsManager.OnboardingStage.COMPLETED) {
        MaxDestinations.HOME
    } else {
        MaxDestinations.ONBOARDING
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

        // Controlled Placeholder Shells for sequential migration (Phases 15.2 - 15.12)
        composable(MaxDestinations.SETTINGS) {
            com.example.ui.settings.SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.PERMISSIONS) {
            com.example.ui.permissions.PermissionsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.MEMORY) {
            com.example.ui.memory.MemoryScreen(
                onNavigateBack = { navController.popBackStack() },
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
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.REMINDERS) {
            com.example.ui.reminders.RemindersScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.ROUTINES) {
            com.example.ui.routines.RoutinesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.NOTIFICATIONS) {
            com.example.ui.notifications.NotificationsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.CAPABILITIES) {
            com.example.ui.capabilities.CapabilitiesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(MaxDestinations.ABOUT) {
            com.example.ui.about.AboutScreen(
                onNavigateBack = { navController.popBackStack() },
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
