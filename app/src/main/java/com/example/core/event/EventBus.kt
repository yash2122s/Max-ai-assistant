package com.example.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    suspend fun post(event: AgentEvent) {
        _events.emit(event)
    }
}
