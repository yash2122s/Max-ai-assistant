package com.example.network

/** In-memory transport continuity only. Durable task/approval truth belongs to AgentTaskStore. */
class LiveSessionState {
    private var model: String? = null
    private var handle: String? = null
    private val calls = linkedMapOf<String, String>()
    private val results = mutableMapOf<String, String>()

    @Synchronized
    fun updateResumption(modelName: String, update: SessionResumptionUpdate) {
        model = modelName
        handle = update.newHandle?.takeIf { update.resumable && it.isNotBlank() }
    }

    @Synchronized
    fun resumptionHandle(modelName: String): String? = handle?.takeIf { model == modelName }

    /** Never evict an executed call to make room: that could replay an irreversible action. */
    @Synchronized
    fun reserveCall(id: String, signature: String): Boolean {
        if (id.isBlank() || calls.containsKey(id) || calls.size >= MAX_CALLS) return false
        calls[id] = signature
        return true
    }

    @Synchronized
    fun resultFor(id: String, signature: String): String? = results[id]?.takeIf { calls[id] == signature }

    @Synchronized
    fun recordResult(id: String, result: String) {
        if (id in calls) results[id] = result
    }

    @Synchronized
    fun reset() {
        model = null
        handle = null
        calls.clear()
        results.clear()
    }

    companion object { private const val MAX_CALLS = 512 }
}
