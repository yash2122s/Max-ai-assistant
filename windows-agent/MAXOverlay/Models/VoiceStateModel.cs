namespace MAXOverlay.Models
{
    /// <summary>
    /// Voice state enum mirroring Python VoiceState.
    /// The overlay reacts to these states for visual morphing.
    /// </summary>
    public enum VoiceState
    {
        Idle,
        WakeWordListening,
        WakeDetected,
        LiveConversation,
        Processing,
        AssistantSpeaking,
        Cooldown,
        Unknown
    }

    public static class VoiceStateParser
    {
        public static VoiceState Parse(string? value)
        {
            return value?.ToLowerInvariant() switch
            {
                "idle" => VoiceState.Idle,
                "wake_word_listening" => VoiceState.WakeWordListening,
                "wake_detected" => VoiceState.WakeDetected,
                "live_conversation" => VoiceState.LiveConversation,
                "processing" => VoiceState.Processing,
                "assistant_speaking" => VoiceState.AssistantSpeaking,
                "cooldown" => VoiceState.Cooldown,
                _ => VoiceState.Unknown
            };
        }

        /// <summary>Returns true if the session is actively conversing (not waiting for wake word).</summary>
        public static bool IsSessionActive(VoiceState state)
        {
            return state is VoiceState.LiveConversation
                or VoiceState.AssistantSpeaking
                or VoiceState.Processing
                or VoiceState.WakeDetected
                or VoiceState.Cooldown;
        }

        /// <summary>Returns true if the overlay should show the compact idle pill.</summary>
        public static bool IsCompact(VoiceState state)
        {
            return state is VoiceState.Idle
                or VoiceState.WakeWordListening;
        }
    }
}
