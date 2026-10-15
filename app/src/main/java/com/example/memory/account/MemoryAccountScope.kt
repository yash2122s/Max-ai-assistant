package com.example.memory.account

import com.example.firebase.auth.FirebaseAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.security.MessageDigest

data class MemorySession(val ownerKey: String, val generation: Long)

object MemoryAccountScope {
    const val GUEST = "guest"
    const val LEGACY = "legacy"
    const val UNAVAILABLE = "unavailable"
    private val state = MutableStateFlow(MemorySession(UNAVAILABLE, 0L))
    val sessions: StateFlow<MemorySession> = state.asStateFlow()
    private var auth: FirebaseAuthManager? = null
    private var observation: Job? = null

    fun capture(): MemorySession = state.value
    fun isCurrent(session: MemorySession): Boolean = session.ownerKey != UNAVAILABLE && state.value == session && auth?.let { it.isCurrentSession(it.sessionState.value) } != false
    fun requireCurrent(session: MemorySession) { if (!isCurrent(session)) throw CancellationException("Memory account session is unavailable or stale") }
    fun directoryName(session: MemorySession): String = sha256(session.ownerKey).take(32)
    fun bind(authManager: FirebaseAuthManager) {
        unbind()
        auth = authManager
        authManager.addSessionInvalidationListener(this) { transitionUnavailable() }
        transitionUnavailable()
        // Publish the SDK's already-authoritative state synchronously before starting
        // collection. This removes the initialization window in which deterministic
        // callers can capture UNAVAILABLE despite an authenticated Firebase session.
        publishFromAuth(authManager)
        observation = CoroutineScope(Dispatchers.Main.immediate).launch {
            authManager.userState.collect { publishFromAuth(authManager) }
        }
    }
    fun unbind() { observation?.cancel(); observation = null; auth?.removeSessionInvalidationListener(this); auth = null; transitionUnavailable() }
    private fun transitionUnavailable() { state.value = MemorySession(UNAVAILABLE, state.value.generation + 1) }
    private fun publishFromAuth(a: FirebaseAuthManager) {
        if (auth !== a || !a.isCurrentSession(a.sessionState.value)) return
        val key = a.currentUid?.let { "uid:$it" } ?: GUEST
        if (state.value.ownerKey != key) transitionUnavailable()
        state.value = MemorySession(key, state.value.generation + 1)
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
