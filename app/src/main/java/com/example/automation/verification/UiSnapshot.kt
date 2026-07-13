package com.example.automation.verification

import android.graphics.Rect

data class UiSnapshot(
    val packageName: String,
    val activityName: String = "",
    val nodes: List<UiNode> = emptyList()
)

data class UiNode(
    val id: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val isClickable: Boolean,
    val bounds: Rect
)
