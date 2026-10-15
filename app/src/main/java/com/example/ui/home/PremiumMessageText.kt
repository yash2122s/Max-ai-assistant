package com.example.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

internal data class MessageBlock(val text: String, val language: String? = null, val code: Boolean = false)

/** Keeps incomplete streaming fences visible and preserves code indentation. */
internal fun messageBlocks(text: String): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    val lines = mutableListOf<String>()
    var code = false
    var language: String? = null
    fun flush() {
        if (lines.isNotEmpty()) blocks += MessageBlock(lines.joinToString("\n"), language, code)
        lines.clear()
    }
    text.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            flush()
            code = !code
            language = if (code) line.trim().removePrefix("```").trim().ifBlank { null } else null
        } else lines += line
    }
    flush()
    return blocks
}

private val inlinePattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

@Composable
internal fun PremiumMessageText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { messageBlocks(text) }
    val textColor = MaxSemanticColors.textPrimary
    val textSecondaryColor = MaxSemanticColors.textSecondary
    val codeBgColor = MaxSemanticColors.surfaceVariant

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            if (block.code) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(MaxDimensions.radiusMd))
                    .background(codeBgColor).padding(12.dp)) {
                    Text(
                        text = block.language ?: "CODE",
                        style = MaxType.Eyebrow,
                        color = textSecondaryColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = block.text,
                        style = MaxType.Mono,
                        color = textColor,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    )
                }
            } else {
                block.text.lines().forEach { line ->
                    val heading = line.startsWith("# ") || line.startsWith("## ") || line.startsWith("### ")
                    val display = when {
                        heading -> line.substringAfter(' ')
                        line.startsWith("- ") || line.startsWith("* ") -> "• ${line.drop(2)}"
                        else -> line
                    }
                    if (display.isEmpty()) Spacer(Modifier.height(4.dp)) else {
                        val styled = remember(display, codeBgColor) {
                            buildAnnotatedString {
                                var cursor = 0
                                inlinePattern.findAll(display).forEach { match ->
                                    append(display.substring(cursor, match.range.first))
                                    val isCode = match.value.startsWith('`')
                                    withStyle(if (isCode) SpanStyle(fontFamily = FontFamily.Monospace,
                                        background = codeBgColor, color = MaxSemanticColors.accentCyan)
                                        else SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                        append(if (isCode) match.groupValues[2] else match.groupValues[1])
                                    }
                                    cursor = match.range.last + 1
                                }
                                append(display.substring(cursor))
                            }
                        }
                        Text(
                            text = styled,
                            color = textColor,
                            style = if (heading) MaxType.Headline else MaxType.Body,
                            fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
