package com.example.automation.engine

class CancellationToken(private val isCancelledCheck: () -> Boolean = { false }) {
    val isCancelled: Boolean
        get() = isCancelledCheck()
}
