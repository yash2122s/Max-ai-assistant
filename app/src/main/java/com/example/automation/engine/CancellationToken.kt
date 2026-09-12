package com.example.automation.engine

class CancellationToken(
    @Volatile private var isCancelledFlag: Boolean = false,
    private val isCancelledCheck: () -> Boolean = { false }
) {
    val isCancelled: Boolean
        get() = isCancelledFlag || isCancelledCheck()

    fun cancel() {
        isCancelledFlag = true
    }
}
