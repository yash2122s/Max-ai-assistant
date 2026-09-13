using System;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Windows.Threading;
using MAXOverlay.Models;

namespace MAXOverlay.Services
{
    /// <summary>
    /// Thread-safe observable state holder that dispatches property changes to the UI thread.
    /// All state updates from the WebSocket client are routed through here.
    /// Backend remains the single source of truth.
    /// </summary>
    public class StateManager : INotifyPropertyChanged
    {
        private readonly Dispatcher _dispatcher;

        public event PropertyChangedEventHandler? PropertyChanged;
        public event Action<TransientVisualEffect>? TransientEffectTriggered;

        // Voice state
        private VoiceState _voiceState = VoiceState.WakeWordListening;
        public VoiceState VoiceState
        {
            get => _voiceState;
            private set
            {
                if (_voiceState != value)
                {
                    _voiceState = value;
                    OnPropertyChanged();
                    OnPropertyChanged(nameof(IsSessionActive));
                    OnPropertyChanged(nameof(IsCompact));
                    OnPropertyChanged(nameof(VisualState));
                }
            }
        }

        public bool IsSessionActive => VoiceStateParser.IsSessionActive(_voiceState);
        public bool IsCompact => VoiceStateParser.IsCompact(_voiceState);

        // Audio level (0.0 – 1.0 normalized)
        private double _audioLevel;
        public double AudioLevel
        {
            get => _audioLevel;
            private set
            {
                _audioLevel = value;
                OnPropertyChanged();
            }
        }

        // Transcripts
        private string _userTranscript = "";
        public string UserTranscript
        {
            get => _userTranscript;
            private set
            {
                if (_userTranscript != value)
                {
                    _userTranscript = value;
                    OnPropertyChanged();
                }
            }
        }

        private string _assistantTranscript = "";
        public string AssistantTranscript
        {
            get => _assistantTranscript;
            private set
            {
                if (_assistantTranscript != value)
                {
                    _assistantTranscript = value;
                    OnPropertyChanged();
                }
            }
        }

        // Connection
        private bool _isConnected;
        public bool IsConnected
        {
            get => _isConnected;
            set
            {
                if (_isConnected != value)
                {
                    _isConnected = value;
                    OnPropertyChanged();
                }
            }
        }

        // Task state
        private TaskStatePayload? _activeTask;
        public TaskStatePayload? ActiveTask
        {
            get => _activeTask;
            private set
            {
                _activeTask = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(HasActiveTask));
                OnPropertyChanged(nameof(VisualState));
            }
        }
        public bool HasActiveTask => _activeTask != null;

        // Confirmation
        private ConfirmationPayload? _pendingConfirmation;
        public ConfirmationPayload? PendingConfirmation
        {
            get => _pendingConfirmation;
            private set
            {
                _pendingConfirmation = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(HasPendingConfirmation));
                OnPropertyChanged(nameof(VisualState));
            }
        }
        public bool HasPendingConfirmation => _pendingConfirmation != null;

        // Device control
        private DeviceStatePayload? _deviceControl;
        public DeviceStatePayload? DeviceControl
        {
            get => _deviceControl;
            private set
            {
                _deviceControl = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(IsDeviceControlActive));
            }
        }
        public bool IsDeviceControlActive => _deviceControl != null
            && _deviceControl.State is "DEVICE_CONTROL_STARTED" or "DEVICE_CONTROL_RUNNING";

        /// <summary>
        /// Derived centralized Visual State mapping for consistent HUD presentation.
        /// Pure projection of backend state without secondary state machine logic.
        /// </summary>
        public OverlayVisualState VisualState
        {
            get
            {
                if (HasPendingConfirmation)
                    return OverlayVisualState.Confirmation;

                return _voiceState switch
                {
                    VoiceState.WakeDetected => OverlayVisualState.WakeDetected,
                    VoiceState.LiveConversation => OverlayVisualState.Listening,
                    VoiceState.Processing => OverlayVisualState.Thinking,
                    VoiceState.AssistantSpeaking => OverlayVisualState.Speaking,
                    VoiceState.Cooldown => OverlayVisualState.Listening,
                    _ => HasActiveTask ? OverlayVisualState.TaskRunning : OverlayVisualState.Idle
                };
            }
        }

        // Audio level smoothing: fast attack for snappy response, smooth decay
        private const double AttackRate = 0.40;
        private const double DecayRate = 0.12;
        private double _smoothedLevel;

        public StateManager(Dispatcher dispatcher)
        {
            _dispatcher = dispatcher;
        }

        /// <summary>
        /// Process an incoming event from the WebSocket client.
        /// Routes to the appropriate state field based on event type.
        /// </summary>
        public void ProcessEvent(OverlayEvent evt)
        {
            _dispatcher.BeginInvoke(() =>
            {
                try
                {
                    switch (evt.Type)
                    {
                        case "voice_state":
                            ProcessVoiceState(evt.Payload);
                            break;

                        case "voice_audio_level":
                            ProcessAudioLevel(evt.Payload);
                            break;

                        case "transcript_user":
                            ProcessUserTranscript(evt.Payload);
                            break;

                        case "transcript_assistant":
                            ProcessAssistantTranscript(evt.Payload);
                            break;

                        case "task_started":
                        case "step_started":
                        case "overlay_task_state":
                            ProcessTaskState(evt.Payload);
                            break;

                        case "step_completed":
                            ProcessTaskState(evt.Payload);
                            TransientEffectTriggered?.Invoke(TransientVisualEffect.SuccessPulse);
                            break;

                        case "task_completed":
                            ActiveTask = null;
                            TransientEffectTriggered?.Invoke(TransientVisualEffect.SuccessPulse);
                            break;

                        case "step_failed":
                        case "task_failed":
                            ActiveTask = null;
                            TransientEffectTriggered?.Invoke(TransientVisualEffect.ErrorPulse);
                            break;

                        case "task_cancelled":
                            ActiveTask = null;
                            break;

                        case "confirmation_required":
                            ProcessConfirmation(evt.Payload);
                            break;

                        case "device_control":
                            ProcessDeviceControl(evt.Payload);
                            break;
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[StateManager] Error processing {evt.Type}: {ex.Message}");
                }
            });
        }

        private void ProcessVoiceState(JsonElement payload)
        {
            var stateStr = payload.TryGetProperty("state", out var s) ? s.GetString() : null;
            var newState = VoiceStateParser.Parse(stateStr);
            VoiceState = newState;

            if (newState == VoiceState.WakeDetected)
            {
                TransientEffectTriggered?.Invoke(TransientVisualEffect.WakePulse);
                AssistantTranscript = "";
                UserTranscript = "";
            }
            else if (newState == VoiceState.WakeWordListening)
            {
                AssistantTranscript = "";
                UserTranscript = "";
            }
        }

        private void ProcessAudioLevel(JsonElement payload)
        {
            double rms = payload.TryGetProperty("rms", out var r) ? r.GetDouble() : 0.0;
            // Normalize RMS to 0-1 range (typical speech RMS 300-8000)
            double normalized = Math.Clamp(rms / 6000.0, 0.0, 1.0);

            // Exponential smoothing: fast attack, smooth decay
            if (normalized > _smoothedLevel)
                _smoothedLevel += (normalized - _smoothedLevel) * AttackRate;
            else
                _smoothedLevel += (normalized - _smoothedLevel) * DecayRate;

            AudioLevel = Math.Clamp(_smoothedLevel, 0.0, 1.0);
        }

        private void ProcessUserTranscript(JsonElement payload)
        {
            var text = payload.TryGetProperty("text", out var t) ? t.GetString() : null;
            if (!string.IsNullOrWhiteSpace(text))
            {
                UserTranscript = text!;
                AssistantTranscript = ""; // Clear previous assistant response when user begins speaking
            }
        }

        private void ProcessAssistantTranscript(JsonElement payload)
        {
            var text = payload.TryGetProperty("text", out var t) ? t.GetString() : null;
            if (!string.IsNullOrWhiteSpace(text))
            {
                AssistantTranscript += text;
            }
        }

        private void ProcessTaskState(JsonElement payload)
        {
            var task = JsonSerializer.Deserialize<TaskStatePayload>(payload.GetRawText());
            if (task != null)
            {
                ActiveTask = task;
            }
        }

        private void ProcessConfirmation(JsonElement payload)
        {
            var conf = JsonSerializer.Deserialize<ConfirmationPayload>(payload.GetRawText());
            if (conf != null)
            {
                PendingConfirmation = conf;
            }
        }

        private void ProcessDeviceControl(JsonElement payload)
        {
            var dev = JsonSerializer.Deserialize<DeviceStatePayload>(payload.GetRawText());
            if (dev != null)
            {
                if (dev.State is "DEVICE_CONTROL_FINISHED" or "DEVICE_CONTROL_SUCCESS" or "DEVICE_CONTROL_FAILED")
                {
                    DeviceControl = dev;
                    var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
                    timer.Tick += (s, e) => { DeviceControl = null; timer.Stop(); };
                    timer.Start();
                }
                else
                {
                    DeviceControl = dev;
                }
            }
        }

        public void ClearConfirmation()
        {
            PendingConfirmation = null;
        }

        private void OnPropertyChanged([CallerMemberName] string? name = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        }
    }
}
