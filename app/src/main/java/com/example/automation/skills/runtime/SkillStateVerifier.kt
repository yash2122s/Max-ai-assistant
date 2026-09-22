package com.example.automation.skills.runtime

import android.content.Context
import android.util.Log
import com.example.automation.skills.compiler.BoundSkillStep
import com.example.automation.skills.model.StepVerification
import com.example.automation.verification.RuntimeStepVerifier

/**
 * Result of step state verification.
 */
data class StepVerificationResult(
    val verified: Boolean,
    val message: String = "",
    val elapsedMs: Long = 0L
)

/**
 * Bounded state verifier for MAX Automation Skill transitions.
 * Ensures an action is not marked as successful until the expected UI state change is confirmed.
 */
object SkillStateVerifier {

    private const val TAG = "SkillStateVerifier"

    suspend fun verifyStep(
        context: Context,
        step: BoundSkillStep
    ): StepVerificationResult {
        val verification = step.verification
        if (verification == null || verification.type == "NONE") {
            return StepVerificationResult(verified = true, message = "No explicit verification declared for step")
        }

        val startTime = System.currentTimeMillis()
        val type = verification.type.uppercase()
        val expectedValue = verification.expectedValue ?: ""
        val timeoutMs = if (verification.timeoutMs > 0) verification.timeoutMs else 3000L

        Log.d(TAG, "Verifying state '$type' for step '${step.stepId}' (timeout=${timeoutMs}ms, expected='$expectedValue')")

        val verified = when (type) {
            "APP_FOREGROUND" -> {
                val pkg = expectedValue.ifEmpty { (step.arguments["package_name"] as? String) ?: "" }
                RuntimeStepVerifier.verifyAppForeground(context, pkg, timeoutMs)
            }
            "ELEMENT_VISIBLE" -> {
                val target = expectedValue.ifEmpty { step.semanticTarget }
                val grounded = AdaptiveUiGrounder.groundTarget(target)
                if (grounded.success) {
                    true
                } else {
                    RuntimeStepVerifier.verifyTargetVisible(context, target, timeoutMs = timeoutMs)
                }
            }
            "TEXT_MATCH", "TEXT_ENTERED" -> {
                val expectedText = expectedValue.ifEmpty { (step.arguments["text"] as? String) ?: "" }
                RuntimeStepVerifier.verifyTextEntered(context, expectedText, timeoutMs = timeoutMs)
            }
            "ELEMENT_GONE" -> {
                val target = expectedValue.ifEmpty { step.semanticTarget }
                val grounded = AdaptiveUiGrounder.groundTarget(target)
                !grounded.success
            }
            else -> {
                Log.d(TAG, "Custom/unrecognized state '$type', assuming element visibility check for '$expectedValue'")
                if (expectedValue.isNotBlank()) {
                    RuntimeStepVerifier.verifyTargetVisible(context, expectedValue, timeoutMs = timeoutMs)
                } else {
                    true
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val msg = if (verified) {
            "✓ Verified state '$type' in ${elapsed}ms"
        } else {
            "✗ State verification failed for '$type' (expected: '$expectedValue') after ${elapsed}ms"
        }

        Log.i(TAG, "Step '${step.stepId}' verification: $msg")
        return StepVerificationResult(verified = verified, message = msg, elapsedMs = elapsed)
    }
}
