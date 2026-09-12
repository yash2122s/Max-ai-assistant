package com.example.rag.model

data class RagDiagnostics(
    val status: String = "READY",
    val embeddingProvider: String = "Local",
    val embeddingModel: String = "local-hash-v1",
    val dimensions: Int = 128,
    val memoriesIndexed: Int = 0,
    val chunksIndexed: Int = 0,
    val failedJobs: Int = 0,
    val pendingJobs: Int = 0,
    val lastIndexTimestamp: Long = 0L,
    val lastRetrievalDurationMs: Long = 0L
)
