package com.example.automation.verification

object VerificationRegistry {
    private val verifierMap = mutableMapOf<String, Verifier>()
    private var frozen = false

    fun register(verifier: Verifier) {
        if (frozen) {
            return
        }
        verifier.supportedTools.forEach { toolName ->
            val key = toolName.uppercase()
            verifierMap[key] = verifier
        }
    }

    fun freeze() {
        frozen = true
    }

    fun resetForTests() {
        frozen = false
        verifierMap.clear()
    }

    fun getVerifierForTool(toolName: String): Verifier? = verifierMap[toolName.uppercase()]
}
