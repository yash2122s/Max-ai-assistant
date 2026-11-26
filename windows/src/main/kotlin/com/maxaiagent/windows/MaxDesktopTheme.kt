package com.maxaiagent.windows

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.maxaiagent.core.MaxThemeTokens

private val MaxLightColors = lightColorScheme(
    primary = Color(MaxThemeTokens.PRIMARY),
    primaryContainer = Color(MaxThemeTokens.PRIMARY_CONTAINER),
    background = Color(MaxThemeTokens.BG_LIGHT),
    surface = Color(MaxThemeTokens.SURFACE_LIGHT),
    surfaceVariant = Color(MaxThemeTokens.SURFACE_VARIANT_LIGHT),
    onBackground = Color(MaxThemeTokens.TEXT_PRIMARY_LIGHT),
    onSurface = Color(MaxThemeTokens.TEXT_PRIMARY_LIGHT),
    onSurfaceVariant = Color(MaxThemeTokens.TEXT_SECONDARY_LIGHT),
    outline = Color(MaxThemeTokens.BORDER_LIGHT),
    error = Color(MaxThemeTokens.ERROR)
)

private val MaxDarkColors = darkColorScheme(
    primary = Color(MaxThemeTokens.PRIMARY),
    primaryContainer = Color(MaxThemeTokens.PRIMARY_CONTAINER),
    background = Color(MaxThemeTokens.BG_DARK),
    surface = Color(MaxThemeTokens.SURFACE_DARK),
    surfaceVariant = Color(MaxThemeTokens.SURFACE_VARIANT_DARK),
    onBackground = Color(MaxThemeTokens.TEXT_PRIMARY_DARK),
    onSurface = Color(MaxThemeTokens.TEXT_PRIMARY_DARK),
    onSurfaceVariant = Color(MaxThemeTokens.TEXT_SECONDARY_DARK),
    outline = Color(MaxThemeTokens.BORDER_DARK),
    error = Color(MaxThemeTokens.ERROR)
)

@Composable
fun MaxDesktopTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MaxDarkColors else MaxLightColors,
        content = content
    )
}
