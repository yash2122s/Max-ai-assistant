package com.example.automation.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AutomationEventBus {
    private val _events = MutableSharedFlow<AutomationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AutomationEvent> = _events.asSharedFlow()

    fun publish(event: AutomationEvent) {
        _events.tryEmit(event)
    }
}
