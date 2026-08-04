package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ui.theme.*
import com.example.viewmodel.ChatViewModel
import com.example.automation.engine.ActionDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.network.ConnectionState

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel
        get() = ChatViewModel.instance
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.example.network.agent.WindowsToolExecutor.initialize(this)
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
        
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedVoice = prefs.getString("voice_name", "Aoede") ?: "Aoede"
        val savedLanguage = prefs.getString("response_language", "Tenglish") ?: "Tenglish"
        val savedModel = prefs.getString("gemini_model", "Auto") ?: "Auto"
        val initialKey = if (savedKey.isNotEmpty()) savedKey else BuildConfig.GEMINI_API_KEY
        

        // Scan installed apps on start
        com.example.knowledge.apps.InstalledAppsRepository.scanAndSaveInstalledApps(applicationContext)

        viewModel.initialize(applicationContext, initialKey, savedVoice, savedLanguage, savedModel)
        
        setContent {
            MyApplicationTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val navController = androidx.navigation.compose.rememberNavController()
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route ?: "home"

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        com.example.ui.screens.DrawerContent(
                            onNavigate = { route ->
                                scope.launch { drawerState.close() }
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            currentRoute = currentRoute
                        )
                    }
                ) {
                    androidx.navigation.compose.NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            Scaffold(modifier = Modifier.fillMaxSize(), containerColor = BgDark) { innerPadding ->
                                ChatScreen(
                                    viewModel = viewModel,
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.padding(innerPadding)
                                )
                            }
                        }
                        composable("settings") {
                            com.example.ui.screens.SettingsScreen(
                                currentApiKey = prefs.getString("api_key", "") ?: "",
                                onSaveApiKey = { newKey ->
                                    prefs.edit().putString("api_key", newKey).apply()
                                    viewModel.reconnect(
                                        newKey,
                                        prefs.getString("voice_name", "Aoede") ?: "Aoede",
                                        prefs.getString("response_language", "Tenglish") ?: "Tenglish"
                                    )
                                },
                                currentVoice = prefs.getString("voice_name", "Aoede") ?: "Aoede",
                                onSaveVoice = { newVoice ->
                                    prefs.edit().putString("voice_name", newVoice).apply()
                                    viewModel.reconnect(
                                        prefs.getString("api_key", "") ?: "",
                                        newVoice,
                                        prefs.getString("response_language", "Tenglish") ?: "Tenglish"
                                    )
                                },
                                currentLanguage = prefs.getString("response_language", "Tenglish") ?: "Tenglish",
                                onSaveLanguage = { newLang ->
                                    prefs.edit().putString("response_language", newLang).apply()
                                    viewModel.reconnect(
                                        prefs.getString("api_key", "") ?: "",
                                        prefs.getString("voice_name", "Aoede") ?: "Aoede",
                                        newLang,
                                        prefs.getString("gemini_model", "Auto") ?: "Auto"
                                    )
                                },
                                currentModel = prefs.getString("gemini_model", "Auto") ?: "Auto",
                                onSaveModel = { newModel ->
                                    prefs.edit().putString("gemini_model", newModel).apply()
                                    viewModel.reconnect(
                                        prefs.getString("api_key", "") ?: "",
                                        prefs.getString("voice_name", "Aoede") ?: "Aoede",
                                        prefs.getString("response_language", "Tenglish") ?: "Tenglish",
                                        newModel
                                    )
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("permissions") {
                            com.example.ui.screens.PermissionsScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("privacy") {
                            com.example.ui.screens.PrivacyPolicyScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("about") {
                            com.example.ui.screens.AboutScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("period_tracker") {
                            com.example.ui.screens.PeriodTrackerScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("reminders") {
                            com.example.ui.screens.RemindersScreen(
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                        composable("memory") {
                            val memoryViewModel: com.example.memory.viewmodel.MemoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                            com.example.memory.ui.MemoryScreen(
                                viewModel = memoryViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(viewModel: ChatViewModel, onMenuClick: () -> Unit, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    LaunchedEffect(uiState.pendingAutomation) {
        uiState.pendingAutomation?.let { jsonObj ->
            ActionDispatcher.dispatch(context, jsonObj)
            viewModel.clearPendingAutomation()
        }
    }
    
    LaunchedEffect(uiState.isRecording) {
        val intent = android.content.Intent(context, com.example.service.VoiceForegroundService::class.java)
        if (uiState.isRecording) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
    }
    
    Box(modifier = modifier.fillMaxSize().background(BgDark)) {
        // Atmospheric Glow Background
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-100).dp)
                .size(300.dp)
                .background(Blue600.copy(alpha = 0.2f), CircleShape)
                .blur(100.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 50.dp, y = 150.dp)
                .size(250.dp)
                .background(Purple600.copy(alpha = 0.15f), CircleShape)
                .blur(80.dp)
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            TopBar(connectionState = uiState.connectionState, onMenuClick = onMenuClick)
            
            // Dynamic Connection Error Banner
            uiState.error?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = NeonPink.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Error: $err",
                            color = NeonPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            
            // Main Live Viewport
            Box(modifier = Modifier.weight(1f).padding(horizontal = 24.dp)) {
                
                if (uiState.messages.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "MAX Hero Logo",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .shadow(16.dp, spotColor = Blue600.copy(alpha = 0.6f))
                                .border(2.dp, Brush.linearGradient(listOf(NeonBlue, NeonPink)), CircleShape)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Hello! How can I assist you?",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Tap a quick suggestion below or start speaking",
                            fontSize = 12.sp,
                            color = TextLight.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        
                        // Suggestion Chips Grid
                        val suggestions = listOf(
                            "🖥️ Check PC Status",
                            "📶 Turn on Bluetooth",
                            "📱 List Installed Apps",
                            "⚡ What can MAX do?"
                        )
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            suggestions.chunked(2).forEach { rowChips ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    rowChips.forEach { chipText ->
                                        Surface(
                                            onClick = { 
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                viewModel.sendTextMessage(chipText) 
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            color = SurfaceDark,
                                            border = BorderStroke(1.dp, BorderDark),
                                            shadowElevation = 4.dp
                                        ) {
                                            Text(
                                                text = chipText,
                                                color = TextLight,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = PaddingValues(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (uiState.isThinking) {
                            item {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = true,
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically()
                                ) {
                                    ThinkingIndicator()
                                }
                            }
                        }
                        items(uiState.messages, key = { "${it.timestamp}_${it.role}" }) { message ->
                            androidx.compose.animation.AnimatedVisibility(
                                visible = true,
                                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically()
                            ) {
                                if (message.role == "You") {
                                    UserMessageCard(message.content, message.timestamp)
                                } else {
                                    GeminiMessageCard(message.content, message.timestamp)
                                }
                            }
                        }
                    }
                }
            }
            
            // Bottom Controls
            BottomControls(
                isRecording = uiState.isRecording,
                onToggleRecording = { viewModel.toggleRecording() },
                onSendMessage = { viewModel.sendTextMessage(it) }
            )
        }
    }
}

@Composable
fun TopBar(connectionState: ConnectionState, onMenuClick: () -> Unit) {
    var isDesktopConnected by remember { mutableStateOf(com.example.network.agent.WindowsToolExecutor.isAgentAvailable()) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    
    LaunchedEffect(Unit) {
        while (true) {
            isDesktopConnected = com.example.network.agent.WindowsToolExecutor.isAgentAvailable()
            kotlinx.coroutines.delay(2000)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "status_pulse")
    val dotPulseScale by pulseTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "dot_pulse"
    )

    val indicatorColor = when (connectionState) {
        ConnectionState.CONNECTED -> Emerald400
        ConnectionState.CONNECTING -> Color(0xFFFB923C)
        ConnectionState.FAILED -> Color(0xFFFF007F)
        ConnectionState.DISCONNECTED -> Color.Gray
    }
    
    val indicatorText = when (connectionState) {
        ConnectionState.CONNECTED -> "WEBSOCKET ACTIVE"
        ConnectionState.CONNECTING -> "CONNECTING..."
        ConnectionState.FAILED -> "CONNECTION FAILED"
        ConnectionState.DISCONNECTED -> "DISCONNECTED"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(24.dp),
        color = SurfaceDarker.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, BorderDark),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "App Icon",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .shadow(8.dp, spotColor = Blue600.copy(alpha = 0.5f))
                        .border(1.dp, Brush.linearGradient(listOf(NeonBlue, NeonPink)), CircleShape)
                )
                Column {
                    Text("MAX Ai Agent", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextLight)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .graphicsLayer {
                                        if (connectionState == ConnectionState.CONNECTED) {
                                            scaleX = dotPulseScale
                                            scaleY = dotPulseScale
                                        }
                                    }
                                    .clip(CircleShape)
                                    .background(indicatorColor)
                            )
                            Text(indicatorText, fontSize = 9.sp, color = indicatorColor, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                        Text("•", fontSize = 9.sp, color = Color.Gray)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val desktopColor = if (isDesktopConnected) Emerald400 else Color(0xFFFF007F)
                            val desktopText = if (isDesktopConnected) "PC LINKED" else "PC OFFLINE"
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .graphicsLayer {
                                        if (isDesktopConnected) {
                                            scaleX = dotPulseScale
                                            scaleY = dotPulseScale
                                        }
                                    }
                                    .clip(CircleShape)
                                    .background(desktopColor)
                            )
                            Text(desktopText, fontSize = 9.sp, color = desktopColor, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }
                }
            }
            
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onMenuClick()
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(SurfaceDark, CircleShape)
                    .border(1.dp, BorderDark, CircleShape)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextLight)
            }
        }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", color = TextLight) },
        text = {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Gemini API Key", color = TextLight) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                )
            )
        },
        confirmButton = {
            Button(onClick = { onSave(apiKey) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextLight)
            }
        },
        containerColor = SurfaceDarker
    )
}

fun formatRelativeTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = (diff / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        seconds < 30 -> "Just now"
        minutes < 1 -> "${seconds}s ago"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

@Composable
fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "dots")
    val dot1Alpha by transition.animateFloat(
        initialValue = 0.2f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 0), repeatMode = RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2Alpha by transition.animateFloat(
        initialValue = 0.2f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 200), repeatMode = RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3Alpha by transition.animateFloat(
        initialValue = 0.2f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 400), repeatMode = RepeatMode.Reverse),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark.copy(alpha = 0.5f))
            .border(1.dp, Brush.linearGradient(listOf(Emerald400.copy(alpha = 0.2f), NeonBlue.copy(alpha = 0.2f))), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("MAX is thinking", fontSize = 12.sp, color = TextLight.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Emerald400.copy(alpha = dot1Alpha)))
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Emerald400.copy(alpha = dot2Alpha)))
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Emerald400.copy(alpha = dot3Alpha)))
        }
    }
}

@Composable
fun GeminiMessageCard(content: String, timestamp: Long = System.currentTimeMillis()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark.copy(alpha = 0.85f))
            .border(1.dp, Brush.horizontalGradient(listOf(Emerald400.copy(alpha = 0.45f), NeonBlue.copy(alpha = 0.3f), Color.Transparent)), RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Emerald400, Color.Transparent)))
                )
                Text(
                    "MAX AI",
                    fontSize = 11.sp,
                    color = Emerald400,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp
                )
            }
            Text(
                formatRelativeTimestamp(timestamp),
                fontSize = 10.sp,
                color = TextLight.copy(alpha = 0.5f)
            )
        }
        Text(content, fontSize = 15.sp, color = TextLight, lineHeight = 23.sp)
    }
}

@Composable
fun UserMessageCard(content: String, timestamp: Long = System.currentTimeMillis()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(SurfaceDark.copy(alpha = 0.95f), SurfaceDarker.copy(alpha = 0.9f))))
            .border(1.dp, Brush.horizontalGradient(listOf(NeonBlue.copy(alpha = 0.5f), NeonPink.copy(alpha = 0.35f))), RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(NeonBlue, Color.Transparent)))
                )
                Text(
                    "YOU",
                    fontSize = 11.sp,
                    color = NeonBlue,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp
                )
            }
            Text(
                formatRelativeTimestamp(timestamp),
                fontSize = 10.sp,
                color = TextLight.copy(alpha = 0.5f)
            )
        }
        Text(content, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
    }
}

@Composable
fun BottomControls(
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val rms by ChatViewModel.rmsFlow.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDarker.copy(alpha = 0.95f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .border(1.dp, BorderDark, RoundedCornerShape(20.dp)),
                placeholder = { Text("Ask MAX anything...", color = TextLight.copy(alpha = 0.5f), fontSize = 14.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    cursorColor = NeonBlue
                ),
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                trailingIcon = {
                    if (inputText.isNotBlank()) {
                        IconButton(onClick = { 
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onSendMessage(inputText)
                            inputText = ""
                        }) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = NeonBlue)
                        }
                    }
                }
            )
            
            // Soundwave live visualizer when active
            if (isRecording || rms > 0.05f) {
                com.example.voice.ui.SoundWave(
                    rms = rms,
                    isRecording = isRecording,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            
            val micScale by animateFloatAsState(
                targetValue = if (isRecording) 0.95f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "mic_scale"
            )

            val pulseTransition = rememberInfiniteTransition(label = "pulse")
            val pulseHaloScale by pulseTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_halo"
            )

            Box(contentAlignment = Alignment.Center) {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .graphicsLayer {
                                scaleX = pulseHaloScale
                                scaleY = pulseHaloScale
                            }
                            .clip(CircleShape)
                            .background(NeonPink.copy(alpha = 0.35f))
                    )
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            scaleX = micScale
                            scaleY = micScale
                        }
                        .shadow(12.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) Brush.linearGradient(listOf(NeonPink, Color(0xFFFF0055)))
                            else Brush.linearGradient(listOf(NeonBlue, Blue600))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onToggleRecording()
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Microphone",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}
