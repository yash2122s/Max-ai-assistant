package com.example.network

import com.google.gson.annotations.SerializedName

data class SetupMessage(
    val setup: SetupConfig
)

data class SetupConfig(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: SystemInstruction? = null
)

data class GenerationConfig(
    val responseModalities: List<String> = listOf("TEXT"),
    val speechConfig: SpeechConfig? = null,
    val thinkingConfig: ThinkingConfig? = null
)

data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null
)

data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null
)

data class PrebuiltVoiceConfig(
    val voiceName: String
)

data class ThinkingConfig(
    @SerializedName("thinkingLevel") val thinkingLevel: String? = "HIGH",
    @SerializedName("thinkingBudget") val thinkingBudget: Int? = null
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
    val toolCall: ToolCall? = null,
    val setupComplete: com.google.gson.JsonObject? = null,
    val toolCallCancellation: ToolCallCancellation? = null,
    val sessionResumptionUpdate: SessionResumptionUpdate? = null,
    val goAway: com.google.gson.JsonObject? = null
)

data class ToolCallCancellation(val ids: List<String> = emptyList())

data class SessionResumptionUpdate(val newHandle: String? = null, val resumable: Boolean = false)

data class ToolCall(
    val functionCalls: List<FunctionCall>
)

data class ServerContent(
    val modelTurn: ModelTurn? = null,
    val turnComplete: Boolean? = null,
    val generationComplete: Boolean? = null,
    val endOfTurn: Boolean? = null,
    val inputTranscription: InputTranscription? = null,
    @SerializedName(value = "interimInputTranscription", alternate = ["interim_input_transcription"])
    val interimInputTranscription: InputTranscription? = null,
    val outputTranscription: OutputTranscription? = null,
    val interrupted: Boolean? = null,
    @SerializedName(value = "interactionStatus", alternate = ["interaction_status"])
    val interactionStatus: String? = null
)

data class InputTranscription(
    val text: String? = null
)

data class OutputTranscription(
    val text: String? = null
)

data class ModelTurn(
    val parts: List<ResponsePart>
)

data class ResponsePart(
    val text: String? = null,
    val inlineData: InlineData? = null,
    val thought: Boolean? = null
)

data class InlineData(
    val mimeType: String,
    val data: String // Base64 encoded audio
)

data class FunctionCall(
    val name: String,
    val args: com.google.gson.JsonObject? = null,
    val id: String? = null
)
