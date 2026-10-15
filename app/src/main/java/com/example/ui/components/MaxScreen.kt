package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.MaxDimensions

/**
 * Universal screen wrapper. Every migrated screen uses this to get:
 * consistent background, aurora option, top bar, scroll, and padding.
 */
@Composable
fun MaxScreen(
    title: String? = null,
    eyebrow: String? = null,
    auroraMode: AuroraMode = AuroraMode.SUBTLE,
    showTopBar: Boolean = true,
    scrollable: Boolean = true,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    topBarActions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        MaxAuroraBackground(mode = auroraMode)

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (showTopBar && (title != null || eyebrow != null)) {
                    MaxTopBar(
                        title = title ?: "",
                        subtitle = eyebrow,
                        onBackClick = onBack,
                        actions = topBarActions
                    )
                }
            },
            floatingActionButton = floatingActionButton
        ) { padding ->
            val columnModifier = if (scrollable) {
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = MaxDimensions.spaceLg,
                        vertical = MaxDimensions.spaceMd
                    )
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            }
            Column(modifier = columnModifier) {
                content()
            }
        }
    }
}
