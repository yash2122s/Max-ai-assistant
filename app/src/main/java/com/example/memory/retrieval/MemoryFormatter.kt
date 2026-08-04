package com.example.memory.retrieval

import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory

object MemoryFormatter {

    fun formatForPrompt(memories: List<PermanentMemory>): String {
        if (memories.isEmpty()) return ""

        val sections = mutableListOf<String>()
        sections.add("### User Permanent Memory")

        // Group memories with tags vs untagged
        val (taggedMemories, untaggedMemories) = memories.partition { it.tags.isNotBlank() }

        if (taggedMemories.isNotEmpty()) {
            sections.add("")
            sections.add("#### Topics & People (Tagged Context)")

            // Extract primary tag for grouping
            val groupedByTag = taggedMemories.groupBy { mem ->
                mem.tags.split(",").firstOrNull()?.trim()?.uppercase() ?: "GENERAL"
            }

            for ((tag, memList) in groupedByTag) {
                sections.add("- [Tag: $tag]")
                for (memory in memList) {
                    val prefix = if (memory.pinned) "  ★ " else "  - "
                    val tagInfo = if (memory.tags.isNotBlank()) " (Tags: ${memory.tags})" else ""
                    sections.add("$prefix${memory.title}: ${memory.content}$tagInfo")
                }
            }
        }

        if (untaggedMemories.isNotEmpty()) {
            sections.add("")
            sections.add("#### General Facts & Preferences")
            val typeGrouped = untaggedMemories.groupBy { it.type }
            val typeOrder = listOf(
                MemoryType.FACT,
                MemoryType.PREFERENCE,
                MemoryType.GOAL,
                MemoryType.CUSTOM
            )

            for (type in typeOrder) {
                val typeMemories = typeGrouped[type] ?: continue
                if (typeMemories.isEmpty()) continue

                sections.add("")
                sections.add("##### ${type.name.lowercase().replaceFirstChar { it.uppercase() }}s")

                for (memory in typeMemories) {
                    val prefix = if (memory.pinned) "★ " else "- "
                    sections.add("$prefix${memory.title}: ${memory.content}")
                }
            }
        }

        return sections.joinToString("\n")
    }

    fun formatSingleMemory(memory: PermanentMemory): String {
        val pin = if (memory.pinned) " [PINNED]" else ""
        return "${memory.title}$pin (${memory.category}/${memory.type.name}): ${memory.content}"
    }
}
