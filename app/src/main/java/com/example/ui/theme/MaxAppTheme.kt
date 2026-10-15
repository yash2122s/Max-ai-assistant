package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun MaxAppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaxTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(
            LocalMaxMotion provides MaxMotion,
            LocalMaxSemanticColors provides MaxSemanticColors
        ) {
            content()
        }
    }
}
