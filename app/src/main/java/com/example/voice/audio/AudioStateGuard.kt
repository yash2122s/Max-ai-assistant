package com.example.voice.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe state machine guarding the single microphone owner (AudioRecorder).
 * Enforces valid state transitions:
 * IDLE -> STARTING -> LISTENING -> STOPPING -> IDLE
 * Rejects duplicate STARTING / LISTENING requests and duplicate STOPPING requests.
 */
enum class AudioGuardState {
    IDLE,
    STARTING,
    LISTENING,
    STOPPING
}

class AudioStateGuard {
    private val _state = MutableStateFlow(AudioGuardState.IDLE)
    val state: StateFlow<AudioGuardState> = _state.asStateFlow()

    private val currentState = AtomicReference(AudioGuardState.IDLE)
    private val lock = Any()

    fun getState(): AudioGuardState = currentState.get()

    /**
     * Tries to transition IDLE -> STARTING.
     * Returns true if transition succeeded, false if already STARTING or LISTENING or STOPPING.
     */
    fun tryBeginStarting(): Boolean {
        synchronized(lock) {
            val curr = currentState.get()
            if (curr == AudioGuardState.IDLE) {
                currentState.set(AudioGuardState.STARTING)
                _state.value = AudioGuardState.STARTING
                Log.d("AudioStateGuard", "Transition: IDLE -> STARTING")
                return true
            }
            Log.w("AudioStateGuard", "Rejected STARTING from state: $curr")
            return false
        }
    }

    /**
     * Transitions STARTING -> LISTENING after AudioRecord successfully starts.
     */
    fun markListening(): Boolean {
        synchronized(lock) {
            val curr = currentState.get()
            if (curr == AudioGuardState.STARTING) {
                currentState.set(AudioGuardState.LISTENING)
                _state.value = AudioGuardState.LISTENING
                Log.d("AudioStateGuard", "Transition: STARTING -> LISTENING")
                return true
            }
            Log.w("AudioStateGuard", "Cannot mark LISTENING from state: $curr")
            return false
        }
    }

    /**
     * Tries to transition LISTENING (or STARTING) -> STOPPING.
     * Returns true if transition succeeded, false if already STOPPING or IDLE.
     */
    fun tryBeginStopping(): Boolean {
        synchronized(lock) {
            val curr = currentState.get()
            if (curr == AudioGuardState.LISTENING || curr == AudioGuardState.STARTING) {
                currentState.set(AudioGuardState.STOPPING)
                _state.value = AudioGuardState.STOPPING
                Log.d("AudioStateGuard", "Transition: $curr -> STOPPING")
                return true
            }
            Log.d("AudioStateGuard", "Ignored STOPPING from state: $curr")
            return false
        }
    }

    /**
     * Transitions STOPPING -> IDLE after AudioRecord has stopped and resources are released.
     */
    fun markIdle() {
        synchronized(lock) {
            currentState.set(AudioGuardState.IDLE)
            _state.value = AudioGuardState.IDLE
            Log.d("AudioStateGuard", "Transition: STOPPING -> IDLE")
        }
    }

    /**
     * Resets immediately to IDLE if error occurred during startup.
     */
    fun abortStarting() {
        synchronized(lock) {
            currentState.set(AudioGuardState.IDLE)
            _state.value = AudioGuardState.IDLE
            Log.d("AudioStateGuard", "Aborted STARTING -> IDLE")
        }
    }
}
