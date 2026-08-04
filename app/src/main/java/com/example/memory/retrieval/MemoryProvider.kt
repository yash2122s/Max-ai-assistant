package com.example.memory.retrieval

import com.example.memory.data.PermanentMemory

interface MemoryProvider {
    suspend fun getRelevantMemories(query: String): List<PermanentMemory>
    suspend fun getAllEnabled(): List<PermanentMemory>
    suspend fun getAll(): List<PermanentMemory>
}
