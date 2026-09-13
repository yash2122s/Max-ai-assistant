namespace MAXOverlay.Models
{
    /// <summary>
    /// Explicit visual presentation states for the Dynamic Island HUD.
    /// Maps directly from backend states (VoiceState, TaskState, DeviceControl, Confirmation)
    /// to visual styling, geometry, animations, and interaction modes.
    /// </summary>
    public enum OverlayVisualState
    {
        /// <summary>Compact 220x42 idle capsule with breathing cyan reactor and floating particles.</summary>
        Idle,

        /// <summary>Transient wake-detected ignition pulse.</summary>
        WakeDetected,

        /// <summary>Expanded 480x110 listening state with real audio-reactive microphone waveform and user transcript.</summary>
        Listening,

        /// <summary>Expanded 480x110 thinking state with rotating energy rings and "Thinking..." display.</summary>
        Thinking,

        /// <summary>Expanded 480x110 speaking state with assistant transcript, audio-reactive waveform, and perimeter light sweep.</summary>
        Speaking,

        /// <summary>Expanded 480x110 task running state with glowing progress bar and step information.</summary>
        TaskRunning,

        /// <summary>Expanded 480x110 confirmation state with amber attention glow and interactive Approve/Reject buttons.</summary>
        Confirmation
    }

    /// <summary>
    /// Transient UI animation triggers driven purely by backend events.
    /// </summary>
    public enum TransientVisualEffect
    {
        WakePulse,
        SuccessPulse,
        ErrorPulse
    }
}
