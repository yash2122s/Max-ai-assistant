package com.example.rag.model

data class RagFilters(
    val categories: Set<String>? = null,
    val tags: Set<String>? = null,
    val providers: Set<String>? = null,
    val excludeMemoryIds: Set<String>? = null,
    val requirePinned: Boolean = false
)
