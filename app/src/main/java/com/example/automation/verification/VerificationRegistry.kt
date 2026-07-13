package com.example.automation.verification

object VerificationRegistry {
    private val verifierMap = mutableMapOf<String, Verifier>()
    private var frozen = false

    fun register(verifier: Verifier) {
        check(!frozen) { "VerificationRegistry is frozen." }
        verifier.supportedTools.forEach { toolName ->
            val key = toolName.uppercase()
            if (verifierMap.containsKey(key)) {
                throw IllegalStateException("Duplicate verifier registered for tool: $toolName")
            }
            verifierMap[key] = verifier
        }
    }

    fun freeze() {
        frozen = true
    }

    fun getVerifierForTool(toolName: String): Verifier? = verifierMap[toolName.uppercase()]
}
