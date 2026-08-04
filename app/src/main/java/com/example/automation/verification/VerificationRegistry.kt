package com.example.automation.verification

object VerificationRegistry {
    private val verifierMap = mutableMapOf<String, Verifier>()
    private var frozen = false

    fun register(verifier: Verifier) {
        val firstTool = verifier.supportedTools.firstOrNull()?.uppercase()
        if (frozen) {
            if (firstTool != null && verifierMap.containsKey(firstTool)) {
                return
            }
            frozen = false
        }
        verifier.supportedTools.forEach { toolName ->
            val key = toolName.uppercase()
            if (verifierMap.containsKey(key)) {
                if (verifierMap[key] === verifier) return@forEach
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
