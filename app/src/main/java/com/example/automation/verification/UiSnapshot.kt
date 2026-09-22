package com.example.automation.verification

import android.graphics.Rect

data class UiSnapshot(
    val packageName: String,
    val activityName: String = "",
    val nodes: List<UiNode> = emptyList()
)

data class UiNode(
    val id: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String = "",
    val isClickable: Boolean = false,
    val bounds: Rect = Rect()
)
