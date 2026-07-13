package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryDark,
    secondary = GradientStart,
    background = BgDark,
    surface = SurfaceDark,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = SurfaceDarker,
    onSurfaceVariant = TextLight
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme for immersive UI
  dynamicColor: Boolean = false, // Disable dynamic color to enforce our theme
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
