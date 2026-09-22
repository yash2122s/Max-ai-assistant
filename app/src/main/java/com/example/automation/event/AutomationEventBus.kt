package com.example.automation.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AutomationEventBus {
    private val _events = MutableSharedFlow<AutomationEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AutomationEvent> = _events.asSharedFlow()

    fun publish(event: AutomationEvent) {
        _events.tryEmit(event)
    }
}
