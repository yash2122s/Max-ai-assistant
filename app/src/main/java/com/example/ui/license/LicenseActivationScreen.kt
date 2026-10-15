package com.example.ui.license

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val clipboardManager = LocalClipboardManager.current
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

    MaxScreen(
        title = "Account & Licensing",
        eyebrow = "CLOUD LICENSING",
        auroraMode = AuroraMode.SUBTLE,
        onBack = onNavigateBack
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
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
                onSignOut = { viewModel.signOut() },
                onSwitchAccount = {
                    (context as? Activity)?.let(viewModel::switchAccount)
                }
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
                    style = MaxType.Eyebrow,
                    color = MaxSemanticColors.textMuted,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )

                MaxCard(
                    cornerRadius = MaxDimensions.radiusXl,
                    contentPadding = PaddingValues(MaxDimensions.spaceMd)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        Text(
                            text = "Enter Lifetime License Key",
                            style = MaxType.Headline,
                            color = MaxSemanticColors.textPrimary
                        )

                        // If wrong key, display the error banner
                        if (importState is LicenseImportState.Error) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                                    .background(MaxSemanticColors.destructiveSurface)
                                    .border(MaxDimensions.borderHairline, MaxSemanticColors.destructive.copy(alpha = 0.5f), RoundedCornerShape(MaxDimensions.radiusMd))
                                    .padding(MaxDimensions.spaceSm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = MaxSemanticColors.destructive,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "Invalid License Key",
                                        style = MaxType.Body,
                                        fontWeight = FontWeight.Bold,
                                        color = MaxSemanticColors.destructive
                                    )
                                    Text(
                                        text = (importState as LicenseImportState.Error).error,
                                        style = MaxType.Caption,
                                        color = MaxSemanticColors.destructive.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Enter your commercial license key to permanently bind Lifetime Premium to your Google account.",
                                style = MaxType.Caption,
                                color = MaxSemanticColors.textSecondary
                            )
                        }

                        MaxTextField(
                            value = licenseKeyInput,
                            onValueChange = { raw ->
                                val cleaned = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.replace(" ", "-")
                                licenseKeyInput = cleaned
                                if (importState is LicenseImportState.Error) {
                                    viewModel.resetState()
                                }
                            },
                            placeholder = "MAX-LIFE-XXXX-XXXX-XXXX",
                            singleLine = true,
                            isError = importState is LicenseImportState.Error,
                            trailingIcon = {
                                if (licenseKeyInput.isNotEmpty()) {
                                    IconButton(onClick = {
                                        licenseKeyInput = ""
                                        if (importState is LicenseImportState.Error) {
                                            viewModel.resetState()
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear",
                                            tint = MaxSemanticColors.textMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    IconButton(onClick = {
                                        val clipText = clipboardManager.getText()?.text
                                        if (!clipText.isNullOrBlank()) {
                                            licenseKeyInput = com.example.firebase.model.LicenseKeyHelper.normalize(clipText)
                                            if (importState is LicenseImportState.Error) {
                                                viewModel.resetState()
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "Paste from clipboard",
                                            tint = MaxSemanticColors.accentCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Key: MAX-LIFE-XXXX-XXXX-XXXX",
                                style = MaxType.Caption,
                                color = MaxSemanticColors.textMuted
                            )
                            TextButton(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text
                                    if (!clipText.isNullOrBlank()) {
                                        licenseKeyInput = com.example.firebase.model.LicenseKeyHelper.normalize(clipText)
                                        if (importState is LicenseImportState.Error) {
                                            viewModel.resetState()
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = MaxSemanticColors.accentCyan,
                                    modifier = Modifier.size(MaxIconTokens.Micro)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Paste from Clipboard",
                                    style = MaxType.Caption,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaxSemanticColors.accentCyan
                                )
                            }
                        }

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
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit = onSignIn
) {
    MaxCard(
        cornerRadius = MaxDimensions.radius2xl,
        contentPadding = PaddingValues(MaxDimensions.spaceMd),
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
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaxSemanticColors.primaryRoyal.copy(alpha = 0.2f))
                            .border(MaxDimensions.borderHairline, MaxSemanticColors.primaryRoyal.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (user.displayName?.firstOrNull() ?: user.email?.firstOrNull() ?: 'U').uppercase(),
                            style = MaxType.Headline,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.primaryRoyal
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.displayName ?: "Google User",
                            style = MaxType.Body,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxSemanticColors.textPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = user.email ?: user.uid,
                            style = MaxType.Caption,
                            color = MaxSemanticColors.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MaxButton(
                    text = "Sign Out",
                    onClick = onSignOut,
                    variant = MaxButtonVariant.GHOST,
                    height = 40.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                )
                MaxButton(
                    text = "Switch Account",
                    onClick = onSwitchAccount,
                    variant = MaxButtonVariant.GHOST,
                    height = 40.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaxSemanticColors.accentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaxSemanticColors.accentCyan,
                            modifier = Modifier.size(MaxIconTokens.Large)
                        )
                    }
                    Column {
                        Text(
                            text = "Google Account Authentication",
                            style = MaxType.Body,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxSemanticColors.textPrimary
                        )
                        Text(
                            text = "Sign in to activate your 7-day trial and sync license",
                            style = MaxType.Caption,
                            color = MaxSemanticColors.textSecondary
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
                badgeColor = MaxSemanticColors.royalGold,
                icon = Icons.Default.Verified,
                accentGradient = listOf(MaxSemanticColors.warningSurface, MaxSemanticColors.surface)
            )
        }
        entitlement is Entitlement.Premium -> {
            LicenseHeroConfig(
                title = "MAX ${license.plan.name} ACTIVE",
                subtitle = if (license.expiresAtEpochMillis != null) "Expires: ${formatter.format(Instant.ofEpochMilli(license.expiresAtEpochMillis))}" else "Permanent Active License",
                badgeColor = MaxSemanticColors.royalGold,
                icon = Icons.Default.Verified,
                accentGradient = listOf(MaxSemanticColors.warningSurface, MaxSemanticColors.surface)
            )
        }
        license.plan == LicensePlan.LIFETIME && license.status != LicenseStatus.ACTIVE -> {
            LicenseHeroConfig(
                title = "Lifetime Inactive (${license.status.name})",
                subtitle = "Your lifetime license is currently not active. Please re-activate or contact support.",
                badgeColor = MaxSemanticColors.destructive,
                icon = Icons.Default.Warning,
                accentGradient = listOf(MaxSemanticColors.destructiveSurface, MaxSemanticColors.surface)
            )
        }
        entitlement is Entitlement.Trial -> {
            val days = license.remainingTrialDays()
            val usageStatus = "$dailyUsage/20 messages used today"
            LicenseHeroConfig(
                title = "3-Day Free Trial Active",
                subtitle = "$days days remaining • $usageStatus",
                badgeColor = if (dailyUsage >= 20L) MaxSemanticColors.destructive else MaxSemanticColors.accentCyan,
                icon = if (dailyUsage >= 20L) Icons.Default.Warning else Icons.Default.HourglassTop,
                accentGradient = listOf(MaxSemanticColors.infoSurface, MaxSemanticColors.surface)
            )
        }
        entitlement is Entitlement.Expired || license.isExpired() -> LicenseHeroConfig(
            title = "Free Trial Ended",
            subtitle = "Your 3-day trial has ended. Activate MAX Premium to continue.",
            badgeColor = MaxSemanticColors.destructive,
            icon = Icons.Default.ErrorOutline,
            accentGradient = listOf(MaxSemanticColors.destructiveSurface, MaxSemanticColors.surface)
        )
        entitlement is Entitlement.Revoked -> LicenseHeroConfig(
            title = "License Revoked",
            subtitle = "This license was revoked by the issuer.",
            badgeColor = MaxSemanticColors.destructive,
            icon = Icons.Default.Block,
            accentGradient = listOf(MaxSemanticColors.destructiveSurface, MaxSemanticColors.surface)
        )
        entitlement is Entitlement.Invalid -> LicenseHeroConfig(
            title = "Invalid License",
            subtitle = "Signature mismatch or corrupted license.",
            badgeColor = MaxSemanticColors.destructive,
            icon = Icons.Default.Warning,
            accentGradient = listOf(MaxSemanticColors.destructiveSurface, MaxSemanticColors.surface)
        )
        else -> LicenseHeroConfig(
            title = "Free Tier",
            subtitle = "Sign in with Google to unlock 3-day trial and cloud licensing.",
            badgeColor = MaxSemanticColors.textMuted,
            icon = Icons.Default.Lock,
            accentGradient = listOf(MaxSemanticColors.surfaceElevated, MaxSemanticColors.surface)
        )
    }

    val title = hero.title
    val subtitle = hero.subtitle
    val badgeColor = hero.badgeColor
    val icon = hero.icon

    MaxCard(
        cornerRadius = MaxDimensions.radius2xl,
        contentPadding = PaddingValues(MaxDimensions.spaceLg),
        specularHighlight = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
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
                        style = MaxType.Headline,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaxType.Caption,
                        color = MaxSemanticColors.textSecondary
                    )
                }
            }

            HorizontalDivider(color = MaxSemanticColors.border, thickness = MaxDimensions.borderHairline)

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
                        is LicenseSyncState.Syncing -> "Syncing..." to MaxSemanticColors.accentCyan
                        is LicenseSyncState.Synced -> {
                            if (syncState.isFromCache) {
                                (if (syncState.isStale) "Stale Cache" else "Offline Cache") to MaxSemanticColors.warning
                            } else {
                                "Cloud Synced" to MaxSemanticColors.success
                            }
                        }
                        is LicenseSyncState.Error -> "Offline / Sync Error" to MaxSemanticColors.destructive
                        LicenseSyncState.Idle -> "Ready" to MaxSemanticColors.textMuted
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusText,
                        style = MaxType.Caption,
                        color = MaxSemanticColors.textSecondary
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
        contentPadding = PaddingValues(MaxDimensions.spaceMd)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                    .background(MaxSemanticColors.infoSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaxSemanticColors.info,
                    modifier = Modifier.size(MaxIconTokens.Medium)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device?.deviceModel ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    style = MaxType.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxSemanticColors.textPrimary
                )
                Text(
                    text = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) • Device Active",
                    style = MaxType.Caption,
                    color = MaxSemanticColors.textSecondary
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MaxDimensions.radiusXs))
                    .background(MaxSemanticColors.successSurface)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "REGISTERED",
                    style = MaxType.Eyebrow,
                    fontWeight = FontWeight.Bold,
                    color = MaxSemanticColors.success
                )
            }
        }
    }
}
