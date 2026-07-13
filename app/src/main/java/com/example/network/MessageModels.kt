package com.example.network

data class SetupMessage(
    val setup: SetupConfig
)

data class SetupConfig(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: SystemInstruction? = null
)

data class GenerationConfig(
    val responseModalities: List<String> = listOf("TEXT")
)

data class SystemInstruction(
    val parts: List<Part>
)

data class Part(
    val text: String
)

data class ClientContentMessage(
    val clientContent: ClientContent
)

data class ClientContent(
    val turns: List<Turn>,
    val turnComplete: Boolean = true
)

data class Turn(
    val role: String,
    val parts: List<Part>
)

// Response models
data class ServerMessage(
    val serverContent: ServerContent?,
    val toolCall: ToolCall? = null
)

data class ToolCall(
    val functionCalls: List<FunctionCall>
)

data class ServerContent(
    val modelTurn: ModelTurn?,
    val turnComplete: Boolean? = null,
    val inputTranscription: InputTranscription? = null
)

data class InputTranscription(
    val text: String? = null
)

data class ModelTurn(
    val parts: List<ResponsePart>
)

data class ResponsePart(
    val text: String? = null,
    val inlineData: InlineData? = null
)

data class InlineData(
    val mimeType: String,
    val data: String // Base64 encoded audio
)

data class FunctionCall(
    val name: String,
    val args: com.google.gson.JsonObject,
    val id: String? = null
)
