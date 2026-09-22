package com.example.ui.onboarding

import android.widget.Toast
import androidx.compose.foundation.background
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
                OnboardingStep.WELCOME -> {
                    WelcomeStepView(onGetStarted = { viewModel.goToStep(OnboardingStep.PERMISSIONS) })
                }
                OnboardingStep.PERMISSIONS -> {
                    PermissionsStepView(
                        uiState = uiState,
                        onRequestAccessibility = { viewModel.requestAccessibility() },
                        onRequestOverlay = { viewModel.requestOverlay() },
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
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaxTextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = MaxTextMuted)
        }
    }
}

@Composable
private fun PermissionsStepView(
    uiState: OnboardingUiState,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onContinue: () -> Unit
) {
    var showA11yDisclosureDialog by remember { mutableStateOf(false) }

    if (showA11yDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showA11yDisclosureDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Accessibility, contentDescription = null, tint = MaxPrimary)
                    Text("Accessibility Service Disclosure", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaxTextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "MAX uses the Accessibility Service API strictly to execute your voice commands and autonomous tasks on your behalf.",
                        fontSize = 13.sp,
                        color = MaxTextPrimary
                    )
                    Text(
                        text = "• Click on UI elements & navigate apps\n• Type text into search and chat inputs\n• Answer/reject calls via voice\n• Scroll and interact with screens",
                        fontSize = 12.sp,
                        color = MaxTextSecondary
                    )
                    Text(
                        text = "Privacy Guarantee: MAX does NOT monitor your personal data, does NOT collect keystrokes, and does NOT share screen contents.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxPrimary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showA11yDisclosureDialog = false
                        onRequestAccessibility()
                    }
                ) {
                    Text("Agree & Enable", fontWeight = FontWeight.Bold, color = MaxPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showA11yDisclosureDialog = false }) {
                    Text("Cancel", color = MaxTextMuted)
                }
            },
            containerColor = MaxSurface,
            shape = RoundedCornerShape(MaxDimensions.radiusLg)
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "System Setup",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaxTextPrimary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))

        Text(
            text = "Grant required Android permissions for MAX automation",
            fontSize = 13.sp,
            color = MaxTextMuted
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        MaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                OnboardingPermissionRow(
                    title = "Accessibility Service",
                    subtitle = "Required for autonomous clicking, typing & UI control",
                    isGranted = uiState.isAccessibilityGranted,
                    onGrant = { showA11yDisclosureDialog = true }
                )
                HorizontalDivider(color = MaxBorder, thickness = 0.5.dp)
                OnboardingPermissionRow(
                    title = "Display Over Other Apps",
                    subtitle = "Required for dynamic floating HUD capsule",
                    isGranted = uiState.isOverlayGranted,
                    onGrant = onRequestOverlay
                )
                HorizontalDivider(color = MaxBorder, thickness = 0.5.dp)
                OnboardingPermissionRow(
                    title = "Ignore Battery Optimizations",
                    subtitle = "Prevents Android from killing AI background tasks",
                    isGranted = uiState.isBatteryOptimizationsIgnored,
                    onGrant = onRequestBattery
                )
            }
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
private fun OnboardingPermissionRow(
    title: String,
    subtitle: String? = null,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = MaxDimensions.spaceSm)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaxTextPrimary
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaxTextMuted,
                    lineHeight = 14.sp
                )
            }
        }
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = MaxSuccess,
                modifier = Modifier.size(20.dp)
            )
        } else {
            MaxButton(
                text = "Grant",
                onClick = onGrant,
                variant = MaxButtonVariant.PRIMARY,
                modifier = Modifier.height(30.dp)
            )
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

        MaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
                MaxTextField(
                    value = uiState.geminiApiKey,
                    onValueChange = onApiKeyChanged,
                    label = "Gemini API Key",
                    placeholder = "AIzaSy...",
                    isPassword = true,
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = MaxTextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )
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
