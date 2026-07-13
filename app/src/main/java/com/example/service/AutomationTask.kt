package com.example.service

import java.io.Serializable

data class AutomationStep(
    val action: String,       // "OPEN_APP", "CLICK_TEXT", "CLICK_ID", "TYPE_TEXT", "WAIT"
    val pkg: String? = null,
    val text: String? = null,
    val id: String? = null,
    val delayMs: Long = 0L
) : Serializable

data class AutomationTask(
    val type: String, // "SEND_MESSAGE", "GENERIC"
    val contact: String? = null,
    val message: String? = null,
    val appName: String? = null,
    var step: Int = 0, // 0: Init, 1: Searching, 2: ResultClicked, 3: MessageEntered, 4: Done
    val steps: List<AutomationStep> = emptyList(),
    var currentStepIndex: Int = 0,
    var startTime: Long = System.currentTimeMillis(),
    val isScheduled: Boolean = false
) : Serializable
