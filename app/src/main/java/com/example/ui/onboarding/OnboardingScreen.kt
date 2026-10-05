package com.example.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
fun OnboardingScreen(
    onOnboardingFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshPermissions()
    }

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
                is OnboardingUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is OnboardingUiEvent.OnboardingFinished -> {
                    onOnboardingFinished()
                }
            }
        }
    }

    Scaffold(
        containerColor = MaxBgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = MaxDimensions.spaceLg, vertical = MaxDimensions.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (uiState.currentStep) {
                OnboardingStep.AUTH -> {
                    AuthStepView(
                        uiState = uiState,
                        onSignIn = {
                            val activity = context as? Activity
                            if (activity != null) {
                                viewModel.signInWithGoogle(activity)
                            } else {
                                Toast.makeText(context, "Activity context unavailable", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onContinue = {
                            viewModel.goToStep(OnboardingStep.WELCOME)
                        }
                    )
                }
                OnboardingStep.WELCOME -> {
                    WelcomeStepView(onGetStarted = { viewModel.goToStep(OnboardingStep.PERMISSIONS) })
                }
                OnboardingStep.PERMISSIONS -> {
                    PermissionsStepView(
                        uiState = uiState,
                        onRequestAccessibility = { viewModel.requestAccessibility() },
                        onRequestOverlay = { viewModel.requestOverlay() },
                        onRequestMicrophone = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestBattery = { viewModel.requestBatteryOptimization() },
                        onContinue = { viewModel.goToStep(OnboardingStep.AI_CONFIG) }
                    )
                }
                OnboardingStep.AI_CONFIG -> {
                    AiConfigStepView(
                        uiState = uiState,
                        onApiKeyChanged = { viewModel.updateGeminiApiKey(it) },
                        onVoiceSelected = { viewModel.updateLiveVoiceName(it) },
                        onFinish = { viewModel.completeOnboarding() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthStepView(
    uiState: OnboardingUiState,
    onSignIn: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(MaxDimensions.radiusXl))
                .background(Brush.linearGradient(listOf(MaxPrimary, MaxSecondary))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        Text(
            text = "Welcome to MAX",
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            color = MaxTextPrimary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

        Text(
            text = "Google Sign-In & License Activation Required",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaxPrimary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        // Feature Highlights
        MaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                FeatureBullet("Instant 7-Day Free Trial", "Full autonomous tools, real-time voice, and memory.")
                FeatureBullet("Cloud License & Firestore Sync", "Your device license is encrypted and backed up in Firestore.")
                FeatureBullet("Zero Manual Configuration", "Google Identity automatically registers and activates your device.")
            }
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        // Auth Action / Status Card
        MaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MaxDimensions.spaceMd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                if (uiState.isAuthenticating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaxPrimary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    Text(
                        text = "Authenticating with Google...",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxTextPrimary
                    )
                    Text(
                        text = "Activating cloud license and registering device",
                        fontSize = 12.sp,
                        color = MaxTextMuted
                    )
                } else if (uiState.userAccount != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.userAccount.displayName ?: "Google User",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaxTextPrimary
                            )
                            Text(
                                text = uiState.userAccount.email ?: "",
                                fontSize = 12.sp,
                                color = MaxTextMuted
                            )
                            val planName = uiState.cloudLicense?.plan?.name ?: "TRIAL"
                            val statusName = uiState.cloudLicense?.status?.name ?: "ACTIVE"
                            Text(
                                text = "License: $planName ($statusName)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                    MaxButton(
                        text = "Continue to Setup",
                        onClick = onContinue,
                        variant = MaxButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    if (uiState.authError != null) {
                        Surface(
                            shape = RoundedCornerShape(MaxDimensions.radiusMd),
                            color = Color(0xFFEF4444).copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(MaxDimensions.spaceSm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = uiState.authError,
                                    fontSize = 12.sp,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    }

                    Text(
                        text = "Sign in to unlock MAX Assistant",
                        fontSize = 13.sp,
                        color = MaxTextMuted
                    )

                    Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

                    MaxButton(
                        text = "Sign in with Google",
                        onClick = onSignIn,
                        variant = MaxButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStepView(
    onGetStarted: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))

        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(MaxDimensions.radiusXl))
                .background(Brush.linearGradient(listOf(MaxPrimary, MaxSecondary))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "M",
                fontSize = 46.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        Text(
            text = "Welcome to MAX",
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            color = MaxTextPrimary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

        Text(
            text = "Your Autonomous Personal AI Assistant",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaxPrimary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        MaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                FeatureBullet("Real-time Voice Intelligence", "Low latency, conversational audio interactions.")
                FeatureBullet("Deep Device Automation", "Click, scroll, answer calls, schedule tasks, and control settings.")
                FeatureBullet("Encrypted Local Memory", "Persistent Room vector memory stored strictly on your device.")
            }
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))

        MaxButton(
            text = "Get Started",
            onClick = onGetStarted,
            variant = MaxButtonVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FeatureBullet(title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaxPrimary,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaxTextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaxTextMuted
            )
        }
    }
}

@Composable
private fun PermissionsStepView(
    uiState: OnboardingUiState,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestBattery: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Required Permissions",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaxTextPrimary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

        Text(
            text = "MAX needs these permissions to automate actions for you",
            fontSize = 13.sp,
            color = MaxTextMuted
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
        ) {
            PermissionCard(
                title = "Accessibility Service",
                description = "Required to inspect UI elements and perform autonomous actions.",
                icon = Icons.Filled.Settings,
                isGranted = uiState.isAccessibilityGranted,
                onRequest = onRequestAccessibility
            )

            PermissionCard(
                title = "Display Over Other Apps",
                description = "Required to show the MAX voice orb and visual execution feedback.",
                icon = Icons.Filled.Layers,
                isGranted = uiState.isOverlayGranted,
                onRequest = onRequestOverlay
            )

            PermissionCard(
                title = "Microphone",
                description = "Required for real-time voice communication and wake-word detection.",
                icon = Icons.Filled.Mic,
                isGranted = uiState.isMicrophoneGranted,
                onRequest = onRequestMicrophone
            )

            PermissionCard(
                title = "Ignore Battery Optimizations",
                description = "Keeps MAX responsive in the background when your screen is locked.",
                icon = Icons.Filled.Warning,
                isGranted = uiState.isBatteryOptimizationsIgnored,
                onRequest = onRequestBattery
            )
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))

        MaxButton(
            text = "Continue",
            onClick = onContinue,
            variant = MaxButtonVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    MaxCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted) { onRequest() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaxSurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF10B981) else MaxPrimaryRoyal,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(MaxDimensions.spaceMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaxTextMuted,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(MaxDimensions.spaceSm))

            if (isGranted) {
                Surface(
                    shape = RoundedCornerShape(MaxDimensions.radiusPill),
                    color = Color(0xFF10B981).copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "GRANTED",
                        color = Color(0xFF10B981),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(MaxDimensions.radiusPill),
                    color = MaxPrimaryRoyal.copy(alpha = 0.1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaxPrimaryRoyal.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "GRANT",
                        color = MaxPrimaryRoyal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiConfigStepView(
    uiState: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onVoiceSelected: (String) -> Unit,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AI Configuration",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaxTextPrimary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

        Text(
            text = "Enter your Gemini API Key to enable MAX intelligence",
            fontSize = 13.sp,
            color = MaxTextMuted
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        val context = LocalContext.current
        MaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                MaxTextField(
                    value = uiState.geminiApiKey,
                    onValueChange = onApiKeyChanged,
                    label = "Gemini API Key",
                    placeholder = "AIzaSy...",
                    isPassword = true,
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = MaxTextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                        .background(MaxPrimary.copy(alpha = 0.12f))
                        .clickable {
                            try {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://aistudio.google.com/app/apikey")
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = MaxPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Get Free Key from Google AI Studio",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxPrimary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaxPrimary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        MaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                Text(
                    text = "Assistant Voice & Persona",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxTextPrimary
                )

                val isMale = uiState.liveVoiceName.equals("Puck", ignoreCase = true) ||
                        uiState.liveVoiceName.equals("Charon", ignoreCase = true) ||
                        uiState.liveVoiceName.equals("Fenrir", ignoreCase = true)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Female Option
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                            .background(if (!isMale) Color(0xFFFF4081).copy(alpha = 0.12f) else MaxSurfaceVariant)
                            .border(
                                1.dp,
                                if (!isMale) Color(0xFFFF4081) else MaxBorder,
                                RoundedCornerShape(MaxDimensions.radiusMd)
                            )
                            .clickable { onVoiceSelected("Aoede") }
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Face,
                                    contentDescription = null,
                                    tint = if (!isMale) Color(0xFFFF4081) else MaxTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Female",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!isMale) Color(0xFFFF4081) else MaxTextPrimary
                                )
                            }
                            Text(
                                text = "Caring & Warm",
                                fontSize = 11.sp,
                                color = MaxTextMuted
                            )
                        }
                    }

                    // Male Option
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                            .background(if (isMale) MaxPrimary.copy(alpha = 0.12f) else MaxSurfaceVariant)
                            .border(
                                1.dp,
                                if (isMale) MaxPrimary else MaxBorder,
                                RoundedCornerShape(MaxDimensions.radiusMd)
                            )
                            .clickable { onVoiceSelected("Puck") }
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = if (isMale) MaxPrimary else MaxTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Male",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMale) MaxPrimary else MaxTextPrimary
                                )
                            }
                            Text(
                                text = "Confident & Sharp",
                                fontSize = 11.sp,
                                color = MaxTextMuted
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))

        MaxButton(
            text = "Complete & Enter MAX",
            onClick = onFinish,
            variant = MaxButtonVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
