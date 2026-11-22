package com.example.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay

/* ═══════════════════════════════════════════════════════════════
   MAIN SCREEN
   ═══════════════════════════════════════════════════════════════ */
@Composable
fun OnboardingScreen(
    onOnboardingFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    LaunchedEffect(uiState.currentStep) {
        scrollState.scrollTo(0)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is OnboardingUiEvent.ShowToast ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is OnboardingUiEvent.OnboardingFinished -> onOnboardingFinished()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaxBaseDark)) {

        // ── 1. Animated aurora background with parallax + floating particles
        MaxAuroraBackground(mode = AuroraMode.FULL, scrollOffset = scrollState.value.toFloat())

            Scaffold(containerColor = Color.Transparent) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // ── 2. Step progress indicator
                    StepIndicator(
                        currentStep = uiState.currentStep,
                        modifier = Modifier.padding(
                            horizontal = MaxDimensions.spaceLg,
                            vertical = MaxDimensions.spaceSm
                        )
                    )

                    // ── 3. Animated step transition
                    AnimatedContent(
                        targetState = uiState.currentStep,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            val dir = if (forward) 1 else -1
                            (slideInHorizontally(
                                animationSpec = tween(MaxMotion.NORMAL, easing = MaxMotion.Ease)
                            ) { dir * it / 3 } + fadeIn(tween(MaxMotion.NORMAL))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = tween(MaxMotion.FAST, easing = MaxMotion.Ease)
                                ) { -dir * it / 3 } + fadeOut(tween(MaxMotion.FAST)))
                        },
                        contentAlignment = Alignment.Center,
                        label = "onboarding-step",
                        modifier = Modifier.weight(1f)
                    ) { step ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(
                                    horizontal = MaxDimensions.spaceLg,
                                    vertical = MaxDimensions.spaceLg
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            when (step) {
                                OnboardingStep.AUTH -> AuthStepView(
                                    uiState = uiState,
                                    onSignIn = {
                                        (context as? Activity)?.let { viewModel.signInWithGoogle(it) }
                                            ?: Toast.makeText(context, "Activity context unavailable", Toast.LENGTH_SHORT).show()
                                    },
                                    onContinue = { viewModel.goToStep(OnboardingStep.WELCOME) }
                                )
                                OnboardingStep.WELCOME -> WelcomeStepView(
                                    onGetStarted = { viewModel.goToStep(OnboardingStep.PERMISSIONS) }
                                )
                                OnboardingStep.PERMISSIONS -> PermissionsStepView(
                                    uiState = uiState,
                                    onRequestAccessibility = { viewModel.requestAccessibility() },
                                    onRequestOverlay = { viewModel.requestOverlay() },
                                    onRequestMicrophone = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    onRequestBattery = { viewModel.requestBatteryOptimization() },
                                    onContinue = { viewModel.goToStep(OnboardingStep.AI_CONFIG) }
                                )
                                OnboardingStep.AI_CONFIG -> AiConfigStepView(
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
        }
}

/* ═══════════════════════════════════════════════════════════════
   CORE TYPOGRAPHY COMPONENTS
   ═══════════════════════════════════════════════════════════════ */

@Composable
fun GradientText(
    text: String,
    style: TextStyle,
    colors: List<Color> = listOf(MaxAccentCyan, MaxPrimaryRoyal, MaxAccentCyan),
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = style.copy(brush = Brush.linearGradient(colors)),
        modifier = modifier
    )
}

@Composable
fun KineticTitle(
    text: String,
    style: TextStyle,
    startDelayMs: Long = 0,
    staggerMs: Long = 90,
    modifier: Modifier = Modifier,
    renderWord: @Composable (String, Int) -> Unit = { word, _ ->
        Text(text = word, style = style, color = Color.White)
    }
) {
    val words = text.split(" ")
    val visibles = remember { words.map { mutableStateOf(false) } }

    LaunchedEffect(Unit) {
        words.indices.forEach { i ->
            delay(startDelayMs + i * staggerMs)
            visibles[i].value = true
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        words.forEachIndexed { i, word ->
            val visible = visibles[i].value
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(520, easing = MaxMotion.Ease),
                label = "alpha$i"
            )
            val translateY by animateFloatAsState(
                targetValue = if (visible) 0f else 28f,
                animationSpec = spring(
                    dampingRatio = 0.75f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "ty$i"
            )

            Box(
                modifier = Modifier.graphicsLayer {
                    this.alpha = alpha
                    this.translationY = translateY
                }
            ) {
                renderWord(word, i)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimatedWords(
    text: String,
    style: TextStyle,
    color: Color,
    startDelayMs: Long = 200,
    staggerMs: Long = 55,
    modifier: Modifier = Modifier
) {
    val words = text.split(" ")
    val visibles = remember { words.map { mutableStateOf(false) } }

    LaunchedEffect(Unit) {
        words.indices.forEach { i ->
            delay(startDelayMs + i * staggerMs)
            visibles[i].value = true
        }
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        words.forEachIndexed { i, word ->
            val visible = visibles[i].value
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(400, easing = MaxMotion.Ease),
                label = "wa$i"
            )
            val blur by animateFloatAsState(
                targetValue = if (visible) 0f else 6f,
                animationSpec = tween(500),
                label = "wb$i"
            )
            Text(
                text = word,
                style = style,
                color = color,
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = alpha
                        this.translationY = blur * 2f
                    }
                    .padding(horizontal = 3.dp)
            )
        }
    }
}

@Composable
fun NumberedBadge(
    number: Int,
    accent: Color = MaxAccentCyan
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.18f),
                        accent.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 0.8.dp,
                brush = Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.55f),
                        accent.copy(alpha = 0.15f)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            color = accent
        )
    }
}

@Composable
fun ShimmerText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sp"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.55f),
            Color.White,
            MaxAccentCyan,
            Color.White,
            Color.White.copy(alpha = 0.55f)
        ),
        start = Offset(progress * 350f - 300f, 0f),
        end = Offset(progress * 350f + 100f, 0f)
    )

    Text(
        text = text,
        style = style.copy(brush = brush),
        modifier = modifier
    )
}

@Composable
private fun HeroTitle(eyebrow: String = "WELCOME TO") {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Eyebrow — small, tracked, muted
        Text(
            text = eyebrow,
            style = MaxType.Eyebrow.copy(
                color = MaxTextSecondary.copy(alpha = 0.7f)
            )
        )
        // Display — huge, gradient
        KineticTitle(
            text = "MAX",
            style = MaxType.Display,
            staggerMs = 40,
            renderWord = { word, _ ->
                GradientText(
                    text = word,
                    style = MaxType.Display,
                    colors = listOf(MaxAccentCyan, Color.White, MaxPrimaryRoyal)
                )
            }
        )
    }
}

@Composable
private fun NumberedFeatureBullet(
    number: Int,
    title: String,
    subtitle: String,
    index: Int,
    accent: Color = MaxAccentCyan
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 110L)
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500, easing = MaxMotion.Ease),
        label = "a"
    )
    val translateX by animateFloatAsState(
        targetValue = if (visible) 0f else -20f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "x"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                this.translationX = translateX
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NumberedBadge(number = number, accent = accent)
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaxType.Body.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White,
                    letterSpacing = (-0.2).sp
                )
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaxType.Caption.copy(color = Color.White.copy(alpha = 0.70f))
            )
        }
    }
}


/* ═══════════════════════════════════════════════════════════════
   2. STEP INDICATOR
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun StepIndicator(
    currentStep: OnboardingStep,
    modifier: Modifier = Modifier
) {
    val steps = OnboardingStep.entries
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            val active = index <= currentStep.ordinal
            val isCurrent = step == currentStep

            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (isCurrent) 30.dp else 14.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                    .clip(CircleShape)
                    .background(
                        if (active) Brush.horizontalGradient(listOf(MaxPrimaryRoyal, MaxAccentCyan))
                        else SolidColor(MaxBorder.copy(alpha = 0.4f))
                    )
            )
            if (index < steps.lastIndex) Spacer(Modifier.width(6.dp))
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   3. HERO LOGO (pulsing + glow + app logo support)
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun HeroLogo(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    letter: String? = null,
    icon: ImageVector? = null,
    @DrawableRes logoRes: Int? = null,
    logoPadding: Dp = 0.dp
) {
    val transition = rememberInfiniteTransition(label = "logo")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(MaxMotion.SLOW * 4, easing = MaxMotion.Ease),
            RepeatMode.Reverse
        ),
        label = "scale"
    )
    val glow by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            tween(MaxMotion.SLOW * 4, easing = MaxMotion.Ease),
            RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = modifier.size(size * 1.4f),
        contentAlignment = Alignment.Center
    ) {
        // Halo (Brand-colored cyan/royal radial glow)
        Box(
            modifier = Modifier
                .size(size * 1.4f)
                .scale(scale)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaxAccentCyan.copy(alpha = glow),
                            MaxPrimaryRoyal.copy(alpha = glow * 0.5f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        // Core tile
        Box(
            modifier = Modifier
                .size(size)
                .scale(scale)
                .clip(RoundedCornerShape(MaxDimensions.radiusXl))
                .then(
                    if (logoRes == null) {
                        Modifier.background(Brush.linearGradient(listOf(MaxPrimaryRoyal, MaxAccentCyan)))
                    } else {
                        Modifier
                    }
                )
                .padding(logoPadding),
            contentAlignment = Alignment.Center
        ) {
            when {
                logoRes != null -> Image(
                    painter = painterResource(id = logoRes),
                    contentDescription = "MAX logo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                letter != null -> Text(
                    text = letter,
                    fontSize = (size.value * 0.48f).sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   AUTH STEP
   ═══════════════════════════════════════════════════════════════ */
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
        HeroLogo(logoRes = R.drawable.app_logo)

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        // ══ HERO TITLE — split, gradient, kinetic ══
        HeroTitle()

        Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

        // ══ ANIMATED SUBTITLE — word by word ══
        AnimatedWords(
            text = "Google Sign-In & License Activation Required",
            style = MaxType.Caption.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = Color.White.copy(alpha = 0.85f),
            startDelayMs = 250
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        // ══ FEATURE CARD — numbered premium bullets ══
        MaxCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaxSurfaceCard
        ) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
            ) {
                NumberedFeatureBullet(1, "Full Autonomous Features", "Autonomous tools, real-time voice, and memory sync.", 0)
                NumberedFeatureBullet(2, "Cloud License & Firestore Sync", "Your device license is encrypted and backed up in Firestore.", 1)
                NumberedFeatureBullet(3, "Zero Manual Configuration", "Google Identity automatically registers and activates your device.", 2)
            }
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        // ══ AUTH CARD ══
        MaxCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaxSurfaceCard
        ) {
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
                        color = MaxAccentCyan,
                        trackColor = MaxAccentCyan.copy(alpha = 0.15f),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(MaxDimensions.space2xs))
                    ShimmerText(
                        text = "Authenticating with Google",
                        style = MaxType.Body.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Activating cloud license and registering device",
                        style = MaxType.Caption,
                        color = MaxTextSecondary
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
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color(0xFF10B981).copy(alpha = 0.25f),
                                            Color(0xFF10B981).copy(alpha = 0.05f)
                                        )
                                    )
                                ),
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
                                style = MaxType.Body.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = uiState.userAccount.email ?: "",
                                style = MaxType.Caption,
                                color = MaxTextSecondary
                            )
                            val planName = uiState.cloudLicense?.plan?.name ?: "TRIAL"
                            val statusName = uiState.cloudLicense?.status?.name ?: "ACTIVE"
                            Text(
                                text = "License: $planName ($statusName)",
                                style = MaxType.Caption.copy(fontWeight = FontWeight.SemiBold),
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
                                    style = MaxType.Caption,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                    }

                    Text(
                        text = "SIGN-IN REQUIRED",
                        style = MaxType.Eyebrow.copy(color = MaxAccentCyan)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Connect your Google account to activate MAX",
                        style = MaxType.Caption,
                        color = Color.White.copy(alpha = 0.80f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

                    MaxButton(
                        text = "Sign in with Google",
                        onClick = onSignIn,
                        variant = MaxButtonVariant.GOOGLE,
                        leadingIcon = {
                            Image(
                                painter = painterResource(id = R.drawable.ic_google_logo),
                                contentDescription = "Google Logo",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   WELCOME STEP
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun WelcomeStepView(onGetStarted: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        HeroLogo(logoRes = R.drawable.app_logo, size = 96.dp)

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        // ══ HERO TITLE — split, gradient, kinetic ══
        HeroTitle(eyebrow = "MEET")

        Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

        // ══ ANIMATED SUBTITLE — word by word ══
        AnimatedWords(
            text = "Your Autonomous Personal AI Assistant",
            style = MaxType.Caption.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = MaxTextSecondary,
            startDelayMs = 250
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))

        // ══ FEATURE CARD — numbered premium bullets ══
        MaxCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaxSurfaceCard
        ) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
            ) {
                NumberedFeatureBullet(1, "Real-time Voice Intelligence", "Low latency, conversational audio interactions.", 0)
                NumberedFeatureBullet(2, "Deep Device Automation", "Click, scroll, answer calls, schedule tasks, and control settings.", 1)
                NumberedFeatureBullet(3, "Encrypted Local Memory", "Persistent Room vector memory stored strictly on your device.", 2)
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

/* ═══════════════════════════════════════════════════════════════
   PERMISSIONS STEP
   ═══════════════════════════════════════════════════════════════ */
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
            text = "SYSTEM ACCESS",
            style = MaxType.Eyebrow.copy(color = MaxAccentCyan.copy(alpha = 0.85f))
        )
        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
        Text(
            text = "Required Permissions",
            style = MaxType.Title,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
        Text(
            text = "MAX needs these permissions to automate actions for you",
            style = MaxType.Caption,
            color = MaxTextSecondary
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

/* ═══════════════════════════════════════════════════════════════
   ANIMATED PERMISSION CARD
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun PermissionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    val tint by animateColorAsState(
        targetValue = if (isGranted) Color(0xFF10B981) else MaxAccentCyan,
        animationSpec = tween(450, easing = MaxMotion.Ease),
        label = "tint"
    )
    val badgeScale by animateFloatAsState(
        targetValue = if (isGranted) 1f else 0.94f,
        animationSpec = MaxMotion.Bouncy,
        label = "badgeScale"
    )
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    MaxCard(
        modifier = Modifier
            .fillMaxWidth()
            .scale(badgeScale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !isGranted
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRequest()
            }
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
                    .background(
                        Brush.linearGradient(
                            listOf(
                                tint.copy(alpha = 0.22f),
                                tint.copy(alpha = 0.05f)
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(MaxDimensions.spaceMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaxType.Body.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaxType.Caption,
                    color = Color.White.copy(alpha = 0.70f)
                )
            }

            Spacer(modifier = Modifier.width(MaxDimensions.spaceSm))

            Surface(
                shape = RoundedCornerShape(MaxDimensions.radiusPill),
                color = tint.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))
            ) {
                Text(
                    text = if (isGranted) "GRANTED" else "GRANT",
                    color = tint,
                    fontSize = if (isGranted) 10.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        horizontal = if (isGranted) 10.dp else 14.dp,
                        vertical = 6.dp
                    )
                )
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════
   AI CONFIG STEP
   ═══════════════════════════════════════════════════════════════ */
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
            text = "INTELLIGENCE ENGINE",
            style = MaxType.Eyebrow.copy(color = MaxAccentCyan.copy(alpha = 0.85f))
        )
        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
        Text(
            text = "AI Configuration",
            style = MaxType.Title,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
        Text(
            text = "Enter your Gemini API Key to enable MAX intelligence",
            style = MaxType.Caption,
            color = MaxTextSecondary
        )

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        val context = LocalContext.current
        MaxCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaxSurfaceCard
        ) {
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
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = MaxTextSecondary) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                        .background(MaxAccentCyan.copy(alpha = 0.12f))
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
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaxAccentCyan,
                            modifier = Modifier.size(MaxIconTokens.Medium)
                        )
                        Text(
                            text = "Get Free Key from Google AI Studio",
                            style = MaxType.Caption.copy(fontWeight = FontWeight.SemiBold),
                            color = MaxAccentCyan
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaxAccentCyan.copy(alpha = 0.7f),
                        modifier = Modifier.size(MaxIconTokens.Small)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

        MaxCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaxSurfaceCard
        ) {
            Column(
                modifier = Modifier.padding(MaxDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                Text(
                    text = "Assistant Voice & Persona",
                    style = MaxType.Headline,
                    color = Color.White
                )

                val isMale = uiState.liveVoiceName.equals("Puck", ignoreCase = true) ||
                        uiState.liveVoiceName.equals("Charon", ignoreCase = true) ||
                        uiState.liveVoiceName.equals("Fenrir", ignoreCase = true)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VoiceOption(
                        label = "Female",
                        tagline = "Caring & Warm",
                        icon = Icons.Default.Face,
                        selected = !isMale,
                        accent = Color(0xFF8B5CF6),
                        modifier = Modifier.weight(1f),
                        onClick = { onVoiceSelected("Aoede") }
                    )
                    VoiceOption(
                        label = "Male",
                        tagline = "Confident & Sharp",
                        icon = Icons.Default.RecordVoiceOver,
                        selected = isMale,
                        accent = MaxAccentCyan,
                        modifier = Modifier.weight(1f),
                        onClick = { onVoiceSelected("Puck") }
                    )
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

/* ═══════════════════════════════════════════════════════════════
   VOICE OPTION (animated selection)
   ═══════════════════════════════════════════════════════════════ */
@Composable
private fun VoiceOption(
    label: String,
    tagline: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.14f) else MaxSurfaceVariant,
        animationSpec = tween(300),
        label = "bg"
    )
    val borderC by animateColorAsState(
        targetValue = if (selected) accent else MaxBorder,
        animationSpec = tween(300),
        label = "border"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = MaxMotion.Bouncy,
        label = "vscale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
            .background(bg)
            .border(1.dp, borderC, RoundedCornerShape(MaxDimensions.radiusMd))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) accent else MaxTextMuted,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaxType.Caption.copy(fontWeight = FontWeight.Bold),
                    fontSize = 13.sp,
                    color = if (selected) accent else MaxTextPrimary
                )
            }
            Text(
                text = tagline,
                style = MaxType.Caption.copy(fontSize = 11.sp),
                color = MaxTextMuted
            )
        }
    }
}
