package com.example.ui.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.preferences.SettingsManager
import com.example.ui.components.*
import com.example.ui.theme.*

private enum class WebConsoleTab {
    IN_APP_PREVIEW,
    LIVE_SERVER_SETUP
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebDashboardScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    var currentTab by remember { mutableStateOf(WebConsoleTab.IN_APP_PREVIEW) }
    var configuredUrl by remember { mutableStateOf(settingsManager.webDashboardUrl) }
    val bundledAssetUrl = "file:///android_asset/website/index.html"
    var activeWebUrl by remember { mutableStateOf(configuredUrl) }

    var isWebViewLoading by remember { mutableStateOf(true) }
    var webViewError by remember { mutableStateOf<String?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    fun launchExternalBrowser(targetUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    MaxScreen(
        title = "Web Dashboard",
        eyebrow = "WORKSPACE CONSOLE",
        auroraMode = AuroraMode.SUBTLE,
        scrollable = (currentTab == WebConsoleTab.LIVE_SERVER_SETUP),
        onBack = onNavigateBack,
        topBarActions = {
            IconButton(
                onClick = { launchExternalBrowser(configuredUrl) }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open in external browser",
                    tint = MaxSemanticColors.accentCyan
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
        ) {
            // Segmented Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaxDimensions.spaceMd)
                    .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                    .background(MaxSemanticColors.surfaceVariant.copy(alpha = 0.5f))
                    .border(MaxDimensions.borderHairline, MaxSemanticColors.border, RoundedCornerShape(MaxDimensions.radiusMd))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf(
                    WebConsoleTab.IN_APP_PREVIEW to "In-App Console",
                    WebConsoleTab.LIVE_SERVER_SETUP to "Website URL & Setup"
                )

                tabs.forEach { (tab, label) ->
                    val isSelected = currentTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(
                                if (isSelected) MaxSemanticColors.accentCyan.copy(alpha = 0.22f)
                                else Color.Transparent
                            )
                            .clickable { currentTab = tab }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaxType.Caption,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaxSemanticColors.accentCyan else MaxSemanticColors.textSecondary
                        )
                    }
                }
            }

            // Tab Content
            when (currentTab) {
                WebConsoleTab.IN_APP_PREVIEW -> {
                    // Quick Action Control Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaxDimensions.spaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (webViewError == null) MaxSemanticColors.success else MaxSemanticColors.destructive)
                            )
                            Text(
                                text = if (activeWebUrl.startsWith("file://")) "Offline Bundled Mode" else "Live Vercel Production",
                                style = MaxType.Caption,
                                fontWeight = FontWeight.SemiBold,
                                color = if (activeWebUrl.startsWith("file://")) MaxSemanticColors.textSecondary else MaxSemanticColors.accentCyan
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Toggle between Live URL and Offline Asset
                            IconButton(
                                onClick = {
                                    val nextUrl = if (activeWebUrl == bundledAssetUrl) configuredUrl else bundledAssetUrl
                                    activeWebUrl = nextUrl
                                    webViewInstance?.loadUrl(nextUrl)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (activeWebUrl == bundledAssetUrl) Icons.Default.CloudQueue else Icons.Default.OfflinePin,
                                    contentDescription = "Switch between live and offline",
                                    tint = MaxSemanticColors.accentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Reload button
                            IconButton(
                                onClick = { webViewInstance?.reload() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload",
                                    tint = MaxSemanticColors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Open in external browser
                            IconButton(
                                onClick = { launchExternalBrowser(configuredUrl) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open in browser",
                                    tint = MaxSemanticColors.accentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // WebView Container
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = MaxDimensions.spaceMd, vertical = 2.dp)
                            .clip(RoundedCornerShape(MaxDimensions.radiusLg))
                            .border(MaxDimensions.borderHairline, MaxSemanticColors.border, RoundedCornerShape(MaxDimensions.radiusLg))
                            .background(Color(0xFF080B12))
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = true
                                    settings.allowContentAccess = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            isWebViewLoading = true
                                            webViewError = null
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isWebViewLoading = false
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                isWebViewLoading = false
                                                webViewError = error?.description?.toString() ?: "Failed to load"
                                            }
                                        }
                                    }

                                    loadUrl(activeWebUrl)
                                    webViewInstance = this
                                }
                            },
                            update = { webView ->
                                webViewInstance = webView
                            }
                        )

                        // Loading Indicator
                        if (isWebViewLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(36.dp)
                                    .align(Alignment.Center),
                                color = MaxSemanticColors.accentCyan,
                                strokeWidth = 3.dp
                            )
                        }

                        // Error Banner if network fails
                        webViewError?.let { err ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .align(Alignment.Center)
                                    .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                                    .background(MaxSemanticColors.surface)
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaxSemanticColors.destructive,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = "Failed to load live page",
                                        style = MaxType.Body,
                                        fontWeight = FontWeight.Bold,
                                        color = MaxSemanticColors.textPrimary
                                    )
                                    Text(
                                        text = err,
                                        style = MaxType.Caption,
                                        color = MaxSemanticColors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MaxButton(
                                            text = "Retry",
                                            onClick = { webViewInstance?.loadUrl(activeWebUrl) },
                                            variant = MaxButtonVariant.PRIMARY
                                        )
                                        MaxButton(
                                            text = "Use Offline Mode",
                                            onClick = {
                                                activeWebUrl = bundledAssetUrl
                                                webViewInstance?.loadUrl(bundledAssetUrl)
                                            },
                                            variant = MaxButtonVariant.SECONDARY
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                WebConsoleTab.LIVE_SERVER_SETUP -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaxDimensions.spaceMd),
                        verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
                    ) {
                        // Hero Info Card
                        MaxCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MaxDimensions.spaceMd)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(MaxSemanticColors.accentCyan, MaxSemanticColors.primaryRoyal)
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Language,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = "MAX AI Official Website",
                                            style = MaxType.Headline,
                                            fontWeight = FontWeight.Bold,
                                            color = MaxSemanticColors.textPrimary
                                        )
                                        Text(
                                            text = "max-ai-agent-official.vercel.app",
                                            style = MaxType.Caption,
                                            color = MaxSemanticColors.accentCyan
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                                Text(
                                    text = "The official MAX AI website is deployed on Vercel with real-time 3D Three.js WebGL animations, interactive crystal core, and feature showcase.",
                                    style = MaxType.Body,
                                    color = MaxSemanticColors.textSecondary
                                )
                            }
                        }

                        // URL Config Card
                        MaxCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MaxDimensions.spaceMd),
                                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                            ) {
                                Text(
                                    text = "OFFICIAL WEBSITE URL",
                                    style = MaxType.Eyebrow,
                                    color = MaxSemanticColors.textMuted
                                )

                                OutlinedTextField(
                                    value = configuredUrl,
                                    onValueChange = {
                                        configuredUrl = it
                                        settingsManager.webDashboardUrl = it
                                        activeWebUrl = it
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(SettingsManager.DEFAULT_WEB_DASHBOARD_URL) },
                                    singleLine = true,
                                    textStyle = MaxType.Body.copy(fontFamily = FontFamily.Monospace),
                                    shape = RoundedCornerShape(MaxDimensions.radiusMd),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaxSemanticColors.accentCyan,
                                        unfocusedBorderColor = MaxSemanticColors.border,
                                        focusedContainerColor = MaxSemanticColors.surfaceVariant.copy(alpha = 0.4f),
                                        unfocusedContainerColor = MaxSemanticColors.surfaceVariant.copy(alpha = 0.2f)
                                    )
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                                ) {
                                    MaxButton(
                                        text = "Open in Chrome / Browser",
                                        onClick = { launchExternalBrowser(configuredUrl) },
                                        variant = MaxButtonVariant.PRIMARY,
                                        modifier = Modifier.weight(1f)
                                    )
                                    MaxButton(
                                        text = "Reset Default",
                                        onClick = {
                                            configuredUrl = SettingsManager.DEFAULT_WEB_DASHBOARD_URL
                                            settingsManager.webDashboardUrl = SettingsManager.DEFAULT_WEB_DASHBOARD_URL
                                            activeWebUrl = SettingsManager.DEFAULT_WEB_DASHBOARD_URL
                                            Toast.makeText(context, "URL reset to official Vercel site", Toast.LENGTH_SHORT).show()
                                        },
                                        variant = MaxButtonVariant.OUTLINE
                                    )
                                }
                            }
                        }

                        // Deployment Details Card
                        MaxCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MaxDimensions.spaceMd),
                                verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                            ) {
                                Text(
                                    text = "PRODUCTION DEPLOYMENT",
                                    style = MaxType.Eyebrow,
                                    color = MaxSemanticColors.textMuted
                                )

                                Text(
                                    text = "• Live URL: https://max-ai-agent-official.vercel.app/\n• GitHub: https://github.com/yash2122s/Max-ai-agent-official\n• Stack: HTML5, CSS3 3D Perspective, JavaScript, Three.js WebGL",
                                    style = MaxType.Caption,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaxSemanticColors.textSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
                    }
                }
            }
        }
    }
}
