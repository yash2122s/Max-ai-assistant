package com.example.ui.license

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.DeviceRegistration
import com.example.firebase.model.FirebaseUserAccount
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus
import com.example.firebase.model.LicenseSyncState
import com.example.license.entitlement.Entitlement
import com.example.ui.components.*
import com.example.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LicenseActivationScreen(
    onNavigateBack: () -> Unit,
    viewModel: LicenseViewModel = viewModel()
) {
    val context = LocalContext.current
    val entitlement by viewModel.entitlement.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val cloudLicense by viewModel.cloudLicense.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val currentDevice by viewModel.currentDevice.collectAsState()
    val dailyUsage by viewModel.dailyUsageCount.collectAsState()

    var licenseKeyInput by remember { mutableStateOf("") }

    val isLicenseActive = (cloudLicense.status == LicenseStatus.ACTIVE && (cloudLicense.plan == LicensePlan.LIFETIME || cloudLicense.plan == LicensePlan.PREMIUM || cloudLicense.plan == LicensePlan.PRO)) || entitlement is Entitlement.Premium

    LaunchedEffect(importState) {
        when (val state = importState) {
            is LicenseImportState.Success -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                licenseKeyInput = ""
                viewModel.resetState()
            }
            is LicenseImportState.Error -> {
                // Keep error state in UI so inline error banner remains visible
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Account & Licensing",
                subtitle = "MAX Cloud Licensing",
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.iosPressEffect(scalePressed = 0.90f, alphaPressed = 0.70f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = MaxTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        },
        containerColor = MaxBaseDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Google Account Card
            GoogleAccountSection(
                user = currentUser,
                onSignIn = {
                    val activity = context as? Activity
                    if (activity != null) {
                        viewModel.signInWithGoogle(activity)
                    } else {
                        Toast.makeText(context, "Activity context unavailable", Toast.LENGTH_SHORT).show()
                    }
                },
                onSignOut = { viewModel.signOut() }
            )

            // 2. Cloud License & Entitlement Hero Card
            CloudLicenseHeroCard(
                user = currentUser,
                license = cloudLicense,
                entitlement = entitlement,
                dailyUsage = dailyUsage,
                syncState = syncState,
                onSync = { viewModel.syncLicense() }
            )

            // 2.5 License Key Activation Section (ONLY visible if not already Lifetime / Premium Active)
            if (!isLicenseActive) {
                Text(
                    text = "ACTIVATE PREMIUM LICENSE KEY",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextTertiary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )

                MaxCard(
                    cornerRadius = MaxDimensions.radiusXl,
                    backgroundColor = MaxSurfaceCard,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Enter Lifetime License Key",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxTextPrimary
                        )

                        // If wrong key, display the error banner
                        if (importState is LicenseImportState.Error) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                                    .background(MaxDestructiveApple.copy(alpha = 0.15f))
                                    .border(1.dp, MaxDestructiveApple.copy(alpha = 0.5f), RoundedCornerShape(MaxDimensions.radiusMd))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = MaxDestructiveApple,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "Invalid License Key",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaxDestructiveApple
                                    )
                                    Text(
                                        text = (importState as LicenseImportState.Error).error,
                                        fontSize = 11.5.sp,
                                        color = MaxDestructiveApple.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Enter your commercial license key to permanently bind Lifetime Premium to your Google account.",
                                fontSize = 12.sp,
                                color = MaxTextTertiary
                            )
                        }

                        OutlinedTextField(
                            value = licenseKeyInput,
                            onValueChange = {
                                licenseKeyInput = it.uppercase()
                                if (importState is LicenseImportState.Error) {
                                    viewModel.resetState()
                                }
                            },
                            placeholder = { Text("MAX-LIFE-XXXX-XXXX-XXXX", color = MaxTextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = importState is LicenseImportState.Error,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaxPrimary,
                                unfocusedBorderColor = if (importState is LicenseImportState.Error) MaxDestructiveApple else MaxBorder,
                                focusedTextColor = MaxTextPrimary,
                                unfocusedTextColor = MaxTextPrimary
                            ),
                            shape = RoundedCornerShape(MaxDimensions.radiusMd)
                        )

                        MaxButton(
                            text = if (importState is LicenseImportState.Loading) "Activating..." else "Activate License Key",
                            onClick = {
                                if (licenseKeyInput.isNotBlank()) {
                                    viewModel.activateLicenseKey(licenseKeyInput)
                                }
                            },
                            enabled = licenseKeyInput.isNotBlank() && importState !is LicenseImportState.Loading,
                            variant = MaxButtonVariant.PRIMARY,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. Registered Device Info Card
            DeviceStatusCard(device = currentDevice)
        }
    }
}

@Composable
fun GoogleAccountSection(
    user: FirebaseUserAccount?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    MaxCard(
        cornerRadius = MaxDimensions.radius2xl,
        backgroundColor = MaxSurfaceCard,
        contentPadding = PaddingValues(16.dp),
        specularHighlight = true
    ) {
        if (user != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaxPrimaryRoyal.copy(alpha = 0.2f))
                            .border(1.dp, MaxPrimaryRoyal.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (user.displayName?.firstOrNull() ?: user.email?.firstOrNull() ?: 'U').uppercase(),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxPrimaryRoyal
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.displayName ?: "Google User",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxTextPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = user.email ?: user.uid,
                            fontSize = 12.sp,
                            color = MaxTextTertiary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                MaxButton(
                    text = "Sign Out",
                    onClick = onSignOut,
                    variant = MaxButtonVariant.GHOST,
                    height = 36.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaxAccentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaxAccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Google Account Authentication",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxTextPrimary
                        )
                        Text(
                            text = "Sign in to activate your 7-day trial and sync license",
                            fontSize = 12.sp,
                            color = MaxTextTertiary
                        )
                    }
                }

                MaxButton(
                    text = "Sign in with Google",
                    onClick = onSignIn,
                    variant = MaxButtonVariant.PRIMARY,
                    height = 42.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private data class LicenseHeroConfig(
    val title: String,
    val subtitle: String,
    val badgeColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentGradient: List<Color>
)

@Composable
fun CloudLicenseHeroCard(
    user: FirebaseUserAccount?,
    license: CloudLicense,
    entitlement: Entitlement,
    dailyUsage: Long = 0L,
    syncState: LicenseSyncState,
    onSync: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

    val hero = when {
        license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE -> {
            LicenseHeroConfig(
                title = "LIFETIME PREMIUM ACTIVE",
                subtitle = "Permanent Lifetime Access • Unlimited AI Messages",
                badgeColor = MaxRoyalGold,
                icon = Icons.Default.Verified,
                accentGradient = listOf(Color(0xFF2A2311), MaxSurfaceCard)
            )
        }
        entitlement is Entitlement.Premium -> {
            LicenseHeroConfig(
                title = "MAX ${license.plan.name} ACTIVE",
                subtitle = if (license.expiresAtEpochMillis != null) "Expires: ${formatter.format(Instant.ofEpochMilli(license.expiresAtEpochMillis))}" else "Permanent Active License",
                badgeColor = MaxRoyalGold,
                icon = Icons.Default.Verified,
                accentGradient = listOf(Color(0xFF2A2311), MaxSurfaceCard)
            )
        }
        license.plan == LicensePlan.LIFETIME && license.status != LicenseStatus.ACTIVE -> {
            LicenseHeroConfig(
                title = "Lifetime Inactive (${license.status.name})",
                subtitle = "Your lifetime license is currently not active. Please re-activate or contact support.",
                badgeColor = MaxDestructiveApple,
                icon = Icons.Default.Warning,
                accentGradient = listOf(Color(0xFF331418), MaxSurfaceCard)
            )
        }
        entitlement is Entitlement.Trial -> {
            val days = license.remainingTrialDays()
            val usageStatus = "$dailyUsage/20 messages used today"
            LicenseHeroConfig(
                title = "3-Day Free Trial Active",
                subtitle = "$days days remaining • $usageStatus",
                badgeColor = if (dailyUsage >= 20L) MaxDestructiveApple else MaxAccentCyan,
                icon = if (dailyUsage >= 20L) Icons.Default.Warning else Icons.Default.HourglassTop,
                accentGradient = listOf(Color(0xFF0D253A), MaxSurfaceCard)
            )
        }
        entitlement is Entitlement.Expired || license.isExpired() -> LicenseHeroConfig(
            title = "Free Trial Ended",
            subtitle = "Your 3-day trial has ended. Activate MAX Premium to continue.",
            badgeColor = MaxDestructiveApple,
            icon = Icons.Default.ErrorOutline,
            accentGradient = listOf(Color(0xFF331418), MaxSurfaceCard)
        )
        entitlement is Entitlement.Revoked -> LicenseHeroConfig(
            title = "License Revoked",
            subtitle = "This license was revoked by the issuer.",
            badgeColor = MaxDestructiveApple,
            icon = Icons.Default.Block,
            accentGradient = listOf(Color(0xFF331418), MaxSurfaceCard)
        )
        entitlement is Entitlement.Invalid -> LicenseHeroConfig(
            title = "Invalid License",
            subtitle = "Signature mismatch or corrupted license.",
            badgeColor = MaxDestructiveApple,
            icon = Icons.Default.Warning,
            accentGradient = listOf(Color(0xFF331418), MaxSurfaceCard)
        )
        else -> LicenseHeroConfig(
            title = "Free Tier",
            subtitle = "Sign in with Google to unlock 3-day trial and cloud licensing.",
            badgeColor = MaxTextTertiary,
            icon = Icons.Default.Lock,
            accentGradient = listOf(MaxSurfaceElevated, MaxSurfaceCard)
        )
    }

    val title = hero.title
    val subtitle = hero.subtitle
    val badgeColor = hero.badgeColor
    val icon = hero.icon
    val accentGradient = hero.accentGradient

    MaxCard(
        cornerRadius = MaxDimensions.radius2xl,
        backgroundColor = MaxSurfaceCard,
        contentPadding = PaddingValues(20.dp),
        specularHighlight = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(badgeColor.copy(alpha = 0.25f), badgeColor.copy(alpha = 0.10f))
                            )
                        )
                        .border(MaxDimensions.borderHairline, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(MaxDimensions.radiusMd)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaxTextPrimary,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaxTextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            HorizontalDivider(color = MaxBorderHairline, thickness = MaxDimensions.borderHairline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Sync status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val (statusText, statusColor) = when (syncState) {
                        is LicenseSyncState.Syncing -> "Syncing..." to MaxAccentCyan
                        is LicenseSyncState.Synced -> {
                            if (syncState.isFromCache) {
                                (if (syncState.isStale) "Stale Cache" else "Offline Cache") to Color(0xFFF59E0B)
                            } else {
                                "Cloud Synced" to Color(0xFF10B981)
                            }
                        }
                        is LicenseSyncState.Error -> "Offline / Sync Error" to MaxDestructiveApple
                        LicenseSyncState.Idle -> "Ready" to MaxTextTertiary
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = MaxTextSecondary
                    )
                }

                if (user != null) {
                    MaxButton(
                        text = "Sync Now",
                        onClick = onSync,
                        variant = MaxButtonVariant.GHOST,
                        height = 36.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceStatusCard(device: DeviceRegistration?) {
    MaxCard(
        cornerRadius = MaxDimensions.radiusXl,
        backgroundColor = MaxSurfaceCard,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF3B82F6).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device?.deviceModel ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxTextPrimary
                )
                Text(
                    text = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) • Device Active",
                    fontSize = 12.sp,
                    color = MaxTextTertiary
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "REGISTERED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }
        }
    }
}
