package com.example.memory.retrieval

import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory

object MemoryFormatter {

    fun formatForPrompt(memories: List<PermanentMemory>): String {
        if (memories.isEmpty()) return ""

        val grouped = memories.groupBy { it.type }

        val sections = mutableListOf<String>()
        sections.add("### User Permanent Memory")

        val typeOrder = listOf(
            MemoryType.FACT,
            MemoryType.PREFERENCE,
            MemoryType.GOAL,
            MemoryType.CUSTOM
        )

        for (type in typeOrder) {
            val typeMemories = grouped[type] ?: continue
            if (typeMemories.isEmpty()) continue

            sections.add("")
            sections.add("${type.name.lowercase().replaceFirstChar { it.uppercase() }}s")

            for (memory in typeMemories) {
                val prefix = if (memory.pinned) "★ " else "- "
                sections.add("$prefix${memory.title}: ${memory.content}")
            }
        }

        return sections.joinToString("\n")
    }

    fun formatSingleMemory(memory: PermanentMemory): String {
        val pin = if (memory.pinned) " [PINNED]" else ""
        return "${memory.title}$pin (${memory.category}/${memory.type.name}): ${memory.content}"
    }
}
