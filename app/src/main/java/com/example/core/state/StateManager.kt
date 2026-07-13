package com.example.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object StateManager {
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state = _state.asStateFlow()

    fun transitionTo(newState: AgentState) {
        _state.value = newState
    }
}
