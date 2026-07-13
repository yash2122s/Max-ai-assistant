package com.example.automation.verification

data class VerificationResult(
    val success: Boolean,
    val reason: String?,
    val retryRecommended: Boolean,
    val snapshot: DeviceContext
)
