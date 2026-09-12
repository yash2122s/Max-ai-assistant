package com.example.rag.chunking

import com.example.memory.data.PermanentMemory
import com.example.rag.model.RagChunk

/**
 * Deterministic semantic chunker that splits memory text along natural boundaries
 * (headings, paragraphs, bullet groups, sentences) into retrieval-optimized chunks.
 */
object RagChunker {

    private const val DEFAULT_TARGET_CHUNK_SIZE = 350
    private const val DEFAULT_MAX_CHUNK_SIZE = 600

    fun chunkMemory(
        memory: PermanentMemory,
        targetSize: Int = DEFAULT_TARGET_CHUNK_SIZE,
        maxSize: Int = DEFAULT_MAX_CHUNK_SIZE
    ): List<RagChunk> {
        val fullContent = "${memory.title}\n\n${memory.content}".trim()
        val contentHash = memory.effectiveContentHash()

        if (fullContent.length <= targetSize) {
            val chunkId = RagChunk.computeChunkId(memory.memoryId, contentHash, 0)
            return listOf(
                RagChunk(
                    chunkId = chunkId,
                    memoryId = memory.memoryId,
                    chunkIndex = 0,
                    content = fullContent,
                    contentHash = contentHash,
                    category = memory.category,
                    tags = memory.tags,
                    provider = memory.provider,
                    sourceRecordId = memory.sourceRecordId,
                    createdAt = memory.createdAt,
                    updatedAt = memory.updatedAt
                )
            )
        }

        val rawSections = splitIntoSemanticSegments(fullContent)
        val chunkTexts = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (section in rawSections) {
            val trimmedSec = section.trim()
            if (trimmedSec.isEmpty()) continue

            if (currentChunk.isNotEmpty() && (currentChunk.length + trimmedSec.length + 2 > targetSize)) {
                chunkTexts.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }

            if (trimmedSec.length > maxSize) {
                // Hard sentence / word split for overly long sections
                val subSentences = splitBySentences(trimmedSec)
                for (sub in subSentences) {
                    if (currentChunk.isNotEmpty() && (currentChunk.length + sub.length + 1 > targetSize)) {
                        chunkTexts.add(currentChunk.toString().trim())
                        currentChunk = StringBuilder()
                    }
                    if (sub.length > maxSize) {
                        // Hard chunk by words if a single sentence exceeds maxSize
                        val words = sub.split(" ")
                        for (w in words) {
                            if (currentChunk.isNotEmpty() && (currentChunk.length + w.length + 1 > targetSize)) {
                                chunkTexts.add(currentChunk.toString().trim())
                                currentChunk = StringBuilder()
                            }
                            if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                            currentChunk.append(w)
                        }
                    } else {
                        if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                        currentChunk.append(sub)
                    }
                }
            } else {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(trimmedSec)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunkTexts.add(currentChunk.toString().trim())
        }

        if (chunkTexts.isEmpty()) {
            chunkTexts.add(fullContent)
        }

        return chunkTexts.mapIndexed { index, text ->
            val chunkId = RagChunk.computeChunkId(memory.memoryId, contentHash, index)
            RagChunk(
                chunkId = chunkId,
                memoryId = memory.memoryId,
                chunkIndex = index,
                content = text,
                contentHash = contentHash,
                category = memory.category,
                tags = memory.tags,
                provider = memory.provider,
                sourceRecordId = memory.sourceRecordId,
                createdAt = memory.createdAt,
                updatedAt = memory.updatedAt
            )
        }
    }

    private fun splitIntoSemanticSegments(text: String): List<String> {
        val segments = mutableListOf<String>()
        val lines = text.lines()
        val currentSeg = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            val isHeading = trimmed.startsWith("#") || trimmed.startsWith("##") || trimmed.startsWith("###")
            val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") || trimmed.startsWith("1. ")

            if ((isHeading || isBullet) && currentSeg.isNotEmpty()) {
                segments.add(currentSeg.toString())
                currentSeg.clear()
            }

            if (trimmed.isEmpty() && currentSeg.isNotEmpty()) {
                segments.add(currentSeg.toString())
                currentSeg.clear()
            } else if (trimmed.isNotEmpty()) {
                if (currentSeg.isNotEmpty()) currentSeg.append("\n")
                currentSeg.append(trimmed)
            }
        }

        if (currentSeg.isNotEmpty()) {
            segments.add(currentSeg.toString())
        }

        return segments
    }

    private fun splitBySentences(text: String): List<String> {
        val pattern = Regex("(?<=[.!?])\\s+")
        return text.split(pattern).filter { it.isNotBlank() }
    }
}
