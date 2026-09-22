using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Shapes;
using System.Windows.Threading;
using MAXOverlay.Controls;
using MAXOverlay.Models;
using MAXOverlay.Services;

namespace MAXOverlay
{
    public partial class MainWindow : Window
    {
        // Win32 interop for click-through, TOOLWINDOW, and capture exclusion
        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hwnd, int index);

        [DllImport("user32.dll")]
        private static extern int SetWindowLong(IntPtr hwnd, int index, int newStyle);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetWindowDisplayAffinity(IntPtr hwnd, uint affinity);

        private const int GWL_EXSTYLE = -20;
        private const int WS_EX_TRANSPARENT = 0x00000020;
        private const int WS_EX_TOOLWINDOW = 0x00000080;
        private const uint WDA_EXCLUDEFROMCAPTURE = 0x00000011;

        private readonly MaxWebSocketClient _wsClient;
        private readonly StateManager _state;
        private readonly DispatcherTimer _renderTimer;
        private DeviceOverlayWindow? _deviceOverlay;
        private IntPtr _hwnd;
        private bool _isExpanded;
        private double _animationPhase;
        private double _thinkingAngle;
        private double _sweepOffset;
        private DateTime _confirmationStartTime;
        private double _confirmationDurationSec = 90.0;

        public MainWindow()
        {
            App.Log("MainWindow constructor start");
            InitializeComponent();

            _state = new StateManager(Dispatcher);
            _wsClient = new MaxWebSocketClient();

            // Wire WebSocket & StateManager
            _wsClient.EventReceived += evt => _state.ProcessEvent(evt);
            _wsClient.ConnectionStateChanged += connected =>
            {
                Dispatcher.BeginInvoke(() =>
                {
                    _state.IsConnected = connected;
                    CompactConnDot.Fill = connected
                        ? (Brush)FindResource("CyanCoreBrush")
                        : (Brush)FindResource("AmberWarningBrush");
                    CompactStatusText.Text = connected ? "MAX" : "MAX ⏳";
                });
            };

            _state.PropertyChanged += OnStatePropertyChanged;
            _state.TransientEffectTriggered += OnTriggerTransientEffect;

            // GPU Render Loop (~60 FPS)
            _renderTimer = new DispatcherTimer(DispatcherPriority.Render)
            {
                Interval = TimeSpan.FromMilliseconds(16)
            };
            _renderTimer.Tick += OnRenderTick;

            Loaded += OnWindowLoaded;
            App.Log("MainWindow constructor complete");
        }

        private void OnWindowLoaded(object sender, RoutedEventArgs e)
        {
            App.Log("MainWindow.OnWindowLoaded start");
            _hwnd = new WindowInteropHelper(this).Handle;

            // Apply TOOLWINDOW style (hide from Alt+Tab)
            int exStyle = GetWindowLong(_hwnd, GWL_EXSTYLE);
            SetWindowLong(_hwnd, GWL_EXSTYLE, exStyle | WS_EX_TOOLWINDOW);

            // Exclude MAX Overlay from screenshots
            try
            {
                SetWindowDisplayAffinity(_hwnd, WDA_EXCLUDEFROMCAPTURE);
            }
            catch (Exception ex)
            {
                App.Log($"Could not set WDA_EXCLUDEFROMCAPTURE: {ex.Message}");
            }

            // Set initial click-through for idle capsule
            SetClickThrough(true);

            // Position at top center of monitor
            MonitorManager.PositionTopCenter(this, Width, Height);

            // Start render loop
            _renderTimer.Start();

            // Connect to MAX Python backend
            _ = Task.Run(() => _wsClient.StartAsync());
            App.Log("MainWindow.OnWindowLoaded complete");
        }

        private void OnStatePropertyChanged(object? sender, PropertyChangedEventArgs e)
        {
            switch (e.PropertyName)
            {
                case nameof(StateManager.VisualState):
                case nameof(StateManager.VoiceState):
                    ApplyVisualState(_state.VisualState);
                    break;

                case nameof(StateManager.UserTranscript):
                case nameof(StateManager.AssistantTranscript):
                    UpdateTranscript();
                    break;

                case nameof(StateManager.HasPendingConfirmation):
                    UpdateConfirmationUI();
                    break;

                case nameof(StateManager.HasActiveTask):
                    UpdateTaskUI();
                    break;

                case nameof(StateManager.IsDeviceControlActive):
                    UpdateDeviceOverlay();
                    break;
            }
        }

        /// <summary>
        /// Unified Visual State Dispatcher.
        /// Centralizes geometry sizing, reactor colors, glows, badges, and interaction flags.
        /// </summary>
        private void ApplyVisualState(OverlayVisualState visualState)
        {
            bool shouldExpand = visualState != OverlayVisualState.Idle;

            if (shouldExpand && !_isExpanded)
                ExpandIsland();
            else if (!shouldExpand && _isExpanded)
                CollapseIsland();

            switch (visualState)
            {
                case OverlayVisualState.Idle:
                    HudParticles.Mode = ParticleMode.IdleCapsule;
                    PerimeterSweepBorder.Opacity = 0;
                    SetClickThrough(true);
                    break;

                case OverlayVisualState.WakeDetected:
                    HudParticles.Mode = ParticleMode.ReactorSurround;
                    ExpandedStatusLabel.Text = "WAKE DETECTED";
                    ExpandedStatusDot.Fill = (Brush)FindResource("CyanCoreBrush");
                    SetGlowColor(Color.FromArgb(255, 0x00, 0xF0, 0xFF), 26);
                    AudioWaveform.Visibility = Visibility.Visible;
                    ThinkingPanel.Visibility = Visibility.Collapsed;
                    TaskPanel.Visibility = Visibility.Collapsed;
                    PerimeterSweepBorder.Opacity = 0;
                    SetClickThrough(false);
                    break;

                case OverlayVisualState.Listening:
                    HudParticles.Mode = ParticleMode.ReactorSurround;
                    ExpandedStatusLabel.Text = "LISTENING";
                    ExpandedStatusDot.Fill = (Brush)FindResource("CyanCoreBrush");
                    ExpandedSubStateText.Text = "Voice Input";
                    SetGlowColor(Color.FromArgb(255, 0x38, 0xBD, 0xF8), 24);
                    AudioWaveform.SetVisualTheme(isSpeaking: false, isViolet: false);
                    AudioWaveform.Visibility = Visibility.Visible;
                    ThinkingPanel.Visibility = Visibility.Collapsed;
                    TaskPanel.Visibility = Visibility.Collapsed;
                    PerimeterSweepBorder.Opacity = 0;
                    SetClickThrough(false);
                    break;

                case OverlayVisualState.Thinking:
                    HudParticles.Mode = ParticleMode.ReactorSurround;
                    ExpandedStatusLabel.Text = "THINKING";
                    ExpandedStatusDot.Fill = (Brush)FindResource("VioletCoreBrush");
                    ExpandedSubStateText.Text = "Planning";
                    SetGlowColor(Color.FromArgb(255, 0x8B, 0x5C, 0xF6), 26);
                    AudioWaveform.Visibility = Visibility.Collapsed;
                    ThinkingPanel.Visibility = Visibility.Visible;
                    TaskPanel.Visibility = Visibility.Collapsed;
                    PerimeterSweepBorder.Opacity = 0;
                    SetClickThrough(false);
                    break;

                case OverlayVisualState.Speaking:
                    HudParticles.Mode = ParticleMode.ReactorSurround;
                    ExpandedStatusLabel.Text = "MAX SPEAKING";
                    ExpandedStatusDot.Fill = (Brush)FindResource("SkyBlueGlowBrush");
                    ExpandedSubStateText.Text = "Realtime Voice";
                    SetGlowColor(Color.FromArgb(255, 0x00, 0xF0, 0xFF), 28);
                    AudioWaveform.SetVisualTheme(isSpeaking: true, isViolet: false);
                    AudioWaveform.Visibility = Visibility.Visible;
                    ThinkingPanel.Visibility = Visibility.Collapsed;
                    TaskPanel.Visibility = Visibility.Collapsed;
                    PerimeterSweepBorder.Opacity = 0.8;
                    SetClickThrough(false);
                    break;

                case OverlayVisualState.TaskRunning:
                    HudParticles.Mode = ParticleMode.ReactorSurround;
                    ExpandedStatusLabel.Text = "EXECUTING";
                    ExpandedStatusDot.Fill = (Brush)FindResource("VioletGlowBrush");
                    SetGlowColor(Color.FromArgb(255, 0x8B, 0x5C, 0xF6), 24);
                    AudioWaveform.Visibility = Visibility.Collapsed;
                    ThinkingPanel.Visibility = Visibility.Collapsed;
                    TaskPanel.Visibility = Visibility.Visible;
                    PerimeterSweepBorder.Opacity = 0;
                    SetClickThrough(false);
                    UpdateTaskUI();
                    break;

                case OverlayVisualState.Confirmation:
                    ExpandedStatusLabel.Text = "ACTION REQUIRED";
                    ExpandedStatusDot.Fill = (Brush)FindResource("AmberWarningBrush");
                    SetGlowColor(Color.FromArgb(255, 0xF5, 0x9E, 0x0B), 30);
                    AudioWaveform.Visibility = Visibility.Collapsed;
                    ThinkingPanel.Visibility = Visibility.Collapsed;
                    TaskPanel.Visibility = Visibility.Collapsed;
                    ConfirmationPanel.Visibility = Visibility.Visible;
                    PerimeterSweepBorder.Opacity = 0;
                    SetClickThrough(false); // Enable mouse click interaction for Approve/Reject buttons
                    break;
            }
        }

        private void SetGlowColor(Color color, double blurRadius)
        {
            var animColor = new ColorAnimation(IslandGlow.Color, color, TimeSpan.FromMilliseconds(250));
            var animBlur = new DoubleAnimation(IslandGlow.BlurRadius, blurRadius, TimeSpan.FromMilliseconds(250));
            IslandGlow.BeginAnimation(DropShadowEffect.ColorProperty, animColor);
            IslandGlow.BeginAnimation(DropShadowEffect.BlurRadiusProperty, animBlur);
        }

        private void ExpandIsland()
        {
            _isExpanded = true;
            SetClickThrough(false);

            var widthAnim = new DoubleAnimation(Width, 480, TimeSpan.FromMilliseconds(280))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };
            var heightAnim = new DoubleAnimation(Height, 110, TimeSpan.FromMilliseconds(280))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };

            widthAnim.Completed += (s, e) =>
            {
                CompactView.Visibility = Visibility.Collapsed;
                ExpandedView.Visibility = Visibility.Visible;
                MonitorManager.PositionTopCenter(this, Width, Height);
            };

            BeginAnimation(WidthProperty, widthAnim);
            BeginAnimation(HeightProperty, heightAnim);
        }

        private void CollapseIsland()
        {
            _isExpanded = false;
            CompactView.Visibility = Visibility.Visible;
            ExpandedView.Visibility = Visibility.Collapsed;

            var widthAnim = new DoubleAnimation(Width, 220, TimeSpan.FromMilliseconds(280))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };
            var heightAnim = new DoubleAnimation(Height, 42, TimeSpan.FromMilliseconds(280))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };

            heightAnim.Completed += (s, e) =>
            {
                SetClickThrough(true);
                MonitorManager.PositionTopCenter(this, Width, Height);
            };

            BeginAnimation(WidthProperty, widthAnim);
            BeginAnimation(HeightProperty, heightAnim);
            SetGlowColor(Color.FromArgb(255, 0x38, 0xBD, 0xF8), 18);
        }

        private void SetClickThrough(bool transparent)
        {
            if (_hwnd == IntPtr.Zero) return;
            int exStyle = GetWindowLong(_hwnd, GWL_EXSTYLE);
            if (transparent)
                SetWindowLong(_hwnd, GWL_EXSTYLE, exStyle | WS_EX_TRANSPARENT);
            else
                SetWindowLong(_hwnd, GWL_EXSTYLE, exStyle & ~WS_EX_TRANSPARENT);
        }

        // ── GPU Render Loop (~60 FPS) ────────────────────────────────────────

        private void OnRenderTick(object? sender, EventArgs e)
        {
            _animationPhase += 0.05;
            _thinkingAngle += 3.5;
            _sweepOffset = (_sweepOffset + 0.012) % 1.0;

            double audioLevel = _state.AudioLevel;

            // 1. Update audio-reactive waveform
            if (AudioWaveform.Visibility == Visibility.Visible)
            {
                AudioWaveform.UpdateFrame(audioLevel);
            }

            // 2. Update particle system
            HudParticles.UpdateFrame();

            // 3. Update idle breathing pulse
            if (!_isExpanded)
            {
                double breath = 0.5 + 0.5 * Math.Sin(_animationPhase);
                double scale = 0.88 + breath * 0.24;
                IdleReactorCore.RenderTransform = new ScaleTransform(scale, scale, 5, 5);
                IdleReactorHalo.RenderTransform = new ScaleTransform(1.0 + breath * 0.35, 1.0 + breath * 0.35, 9, 9);
                IdleReactorHalo.Opacity = 0.3 + breath * 0.5;
            }
            else
            {
                // 4. Update expanded reactor audio pulse
                double scale = 1.0 + audioLevel * 0.45;
                ExpandedReactorCore.RenderTransform = new ScaleTransform(scale, scale, 5.5, 5.5);

                // 5. Update thinking orbital rings
                if (_state.VisualState == OverlayVisualState.Thinking)
                {
                    OrbitRotate1.Angle = _thinkingAngle % 360;
                    OrbitRotate2.Angle = (-_thinkingAngle * 1.4) % 360;
                }

                // 6. Update speaking perimeter light sweep
                if (_state.VisualState == OverlayVisualState.Speaking)
                {
                    SweepStop0.Offset = Math.Max(0.0, _sweepOffset - 0.25);
                    SweepStop1.Offset = _sweepOffset;
                    SweepStop2.Offset = Math.Min(1.0, _sweepOffset + 0.25);
                }

                // 7. Update confirmation timer bar
                if (_state.HasPendingConfirmation)
                {
                    double elapsed = (DateTime.UtcNow - _confirmationStartTime).TotalSeconds;
                    double remainingFraction = Math.Clamp(1.0 - (elapsed / _confirmationDurationSec), 0.0, 1.0);
                    ConfirmExpiryProgress.Value = remainingFraction * 100;
                }
            }
        }

        // ── Transcript Subtitles ─────────────────────────────────────────────

        private void UpdateTranscript()
        {
            if (!string.IsNullOrEmpty(_state.AssistantTranscript))
            {
                TranscriptText.Text = $"✨ {_state.AssistantTranscript}";
                TranscriptText.Foreground = (Brush)FindResource("SkyBlueGlowBrush");
            }
            else if (!string.IsNullOrEmpty(_state.UserTranscript))
            {
                TranscriptText.Text = $"🗣️ {_state.UserTranscript}";
                TranscriptText.Foreground = (Brush)FindResource("TextSecondaryBrush");
            }
            else
            {
                TranscriptText.Text = "";
            }
        }

        // ── Task HUD Panel ───────────────────────────────────────────────────

        private void UpdateTaskUI()
        {
            if (_state.HasActiveTask && !_state.HasPendingConfirmation)
            {
                var task = _state.ActiveTask!;
                TaskTitleText.Text = string.IsNullOrEmpty(task.Tool) ? task.Title : $"[{task.Tool}] {task.Title}";
                TaskProgressBar.Value = task.Progress * 100;
                TaskProgressPct.Text = $"{Math.Round(task.Progress * 100)}%";
                if (task.TotalSteps > 0)
                {
                    ExpandedSubStateText.Text = $"Step {task.Step}/{task.TotalSteps}";
                }
            }
        }

        // ── Confirmation Panel ───────────────────────────────────────────────

        private void UpdateConfirmationUI()
        {
            if (_state.HasPendingConfirmation)
            {
                var conf = _state.PendingConfirmation!;
                _confirmationStartTime = DateTime.UtcNow;
                _confirmationDurationSec = conf.ExpiresInSeconds > 0 ? conf.ExpiresInSeconds : 90.0;

                ConfirmActionText.Text = $"⚠️ {conf.ActionName}";
                ConfirmDescText.Text = $"{conf.Description} ({conf.Target})";
                ConfirmationPanel.Visibility = Visibility.Visible;
                ApplyVisualState(OverlayVisualState.Confirmation);
            }
            else
            {
                ConfirmationPanel.Visibility = Visibility.Collapsed;
                ApplyVisualState(_state.VisualState);
            }
        }

        private async void OnApproveClick(object sender, RoutedEventArgs e)
        {
            if (_state.PendingConfirmation == null) return;
            var ticketId = _state.PendingConfirmation.TicketId;
            _state.ClearConfirmation();

            await _wsClient.SendAsync(new OverlayMessage
            {
                Type = "confirmation_response",
                TicketId = ticketId,
                Action = "approve"
            });
        }

        private async void OnRejectClick(object sender, RoutedEventArgs e)
        {
            if (_state.PendingConfirmation == null) return;
            var ticketId = _state.PendingConfirmation.TicketId;
            _state.ClearConfirmation();

            await _wsClient.SendAsync(new OverlayMessage
            {
                Type = "confirmation_response",
                TicketId = ticketId,
                Action = "reject",
                Reason = "User rejected from overlay UI"
            });
        }

        // ── Transient Visual Effects (Pulse / Checkmark / Shake) ─────────────

        private void OnTriggerTransientEffect(TransientVisualEffect effect)
        {
            Dispatcher.BeginInvoke(() =>
            {
                switch (effect)
                {
                    case TransientVisualEffect.SuccessPulse:
                        ShowTransientOverlay("✓", (Color)FindResource("ColorEmeraldSuccess"), (Brush)FindResource("EmeraldGlowBrush"));
                        break;
                    case TransientVisualEffect.ErrorPulse:
                        ShowTransientOverlay("✕", (Color)FindResource("ColorCrimsonError"), (Brush)FindResource("CrimsonGlowBrush"));
                        break;
                    case TransientVisualEffect.WakePulse:
                        ShowTransientOverlay("⚡", (Color)FindResource("ColorCyanCore"), (Brush)FindResource("CyanCoreBrush"));
                        break;
                }
            });
        }

        private void ShowTransientOverlay(string icon, Color pulseColor, Brush textBrush)
        {
            TransientIconText.Text = icon;
            TransientIconText.Foreground = textBrush;
            TransientPulseOverlay.Background = new SolidColorBrush(Color.FromArgb(80, pulseColor.R, pulseColor.G, pulseColor.B));

            var fadeIn = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(150))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };
            var fadeOut = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(500))
            {
                BeginTime = TimeSpan.FromMilliseconds(700),
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn }
            };

            TransientPulseOverlay.BeginAnimation(OpacityProperty, fadeIn);
            var sb = new Storyboard();
            sb.Children.Add(fadeOut);
            Storyboard.SetTarget(fadeOut, TransientPulseOverlay);
            Storyboard.SetTargetProperty(fadeOut, new PropertyPath(OpacityProperty));
            sb.Begin();
        }

        // ── Fullscreen Device Overlay ────────────────────────────────────────

        private void UpdateDeviceOverlay()
        {
            if (_state.IsDeviceControlActive)
            {
                if (_deviceOverlay == null)
                {
                    _deviceOverlay = new DeviceOverlayWindow();
                    _deviceOverlay.Closed += (s, e) => _deviceOverlay = null;
                    _deviceOverlay.Show();
                }
                _deviceOverlay.SetDeviceState(_state.DeviceControl?.State ?? "DEVICE_CONTROL_RUNNING");
            }
            else
            {
                if (_deviceOverlay != null)
                {
                    if (_state.DeviceControl?.State != null)
                    {
                        _deviceOverlay.SetDeviceState(_state.DeviceControl.State);
                    }
                    _deviceOverlay.FadeOutAndClose();
                }
            }
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            _renderTimer.Stop();
            _wsClient.Stop();
            _wsClient.Dispose();
            _deviceOverlay?.Close();
            base.OnClosing(e);
        }
    }
}
