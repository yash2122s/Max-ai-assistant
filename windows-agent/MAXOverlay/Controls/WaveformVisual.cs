using System;
using System.Windows;
using System.Windows.Media;

namespace MAXOverlay.Controls
{
    /// <summary>
    /// High-performance GPU-composited audio-reactive HUD waveform.
    /// Renders real-time vocal spectrum bars driven directly by actual backend RMS audio levels.
    /// Uses direct DrawingContext rendering with zero per-frame garbage collection.
    /// </summary>
    public class WaveformVisual : FrameworkElement
    {
        private const int BarCount = 32;
        private readonly double[] _currentHeights = new double[BarCount];
        private readonly double[] _targetHeights = new double[BarCount];
        private readonly double[] _harmonicWeights = new double[BarCount];

        private double _audioLevel;
        private double _phase;
        private Brush _barBrush;
        private Brush _glowCapBrush;

        public WaveformVisual()
        {
            ClipToBounds = true;
            IsHitTestVisible = false;

            // Pre-calculate harmonic vocal spectrum curve (peaks naturally in voice mid-frequencies)
            for (int i = 0; i < BarCount; i++)
            {
                double normalizedIndex = (double)i / (BarCount - 1); // 0 to 1
                // Symmetrical bell-curve with slight high-frequency voice presence
                double bell = Math.Sin(normalizedIndex * Math.PI);
                _harmonicWeights[i] = 0.4 + 0.6 * Math.Pow(bell, 0.7);
            }

            // Create frozen brushes for performance
            var gradient = new LinearGradientBrush
            {
                StartPoint = new Point(0, 0),
                EndPoint = new Point(0, 1)
            };
            gradient.GradientStops.Add(new GradientStop(Color.FromArgb(230, 0x00, 0xF0, 0xFF), 0.0));
            gradient.GradientStops.Add(new GradientStop(Color.FromArgb(190, 0x38, 0xBD, 0xF8), 0.4));
            gradient.GradientStops.Add(new GradientStop(Color.FromArgb(120, 0x8B, 0x5C, 0xF6), 1.0));
            gradient.Freeze();
            _barBrush = gradient;

            var capBrush = new SolidColorBrush(Color.FromArgb(255, 0x7D, 0xD3, 0xFC));
            capBrush.Freeze();
            _glowCapBrush = capBrush;
        }

        public void SetVisualTheme(bool isSpeaking, bool isViolet)
        {
            LinearGradientBrush gradient;
            if (isViolet)
            {
                gradient = new LinearGradientBrush
                {
                    StartPoint = new Point(0, 0),
                    EndPoint = new Point(0, 1)
                };
                gradient.GradientStops.Add(new GradientStop(Color.FromArgb(240, 0xC0, 0x84, 0xFC), 0.0));
                gradient.GradientStops.Add(new GradientStop(Color.FromArgb(180, 0x8B, 0x5C, 0xF6), 0.5));
                gradient.GradientStops.Add(new GradientStop(Color.FromArgb(100, 0x38, 0xBD, 0xF8), 1.0));
            }
            else
            {
                gradient = new LinearGradientBrush
                {
                    StartPoint = new Point(0, 0),
                    EndPoint = new Point(0, 1)
                };
                gradient.GradientStops.Add(new GradientStop(Color.FromArgb(240, 0x00, 0xF0, 0xFF), 0.0));
                gradient.GradientStops.Add(new GradientStop(Color.FromArgb(190, 0x38, 0xBD, 0xF8), 0.5));
                gradient.GradientStops.Add(new GradientStop(Color.FromArgb(110, 0x8B, 0x5C, 0xF6), 1.0));
            }
            gradient.Freeze();
            _barBrush = gradient;
        }

        /// <summary>
        /// Update waveform physics for one frame (~60 FPS) based on actual audio level.
        /// </summary>
        public void UpdateFrame(double audioLevel)
        {
            _audioLevel = Math.Clamp(audioLevel, 0.0, 1.0);
            _phase += 0.08;

            double maxH = Math.Max(20, ActualHeight);
            double baseMinHeight = 2.0;

            for (int i = 0; i < BarCount; i++)
            {
                if (_audioLevel > 0.01)
                {
                    // Harmonic modulation reacting to real RMS audio
                    double modulation = 0.75 + 0.25 * Math.Sin(_phase * 2.5 + i * 0.45)
                                      + 0.15 * Math.Cos(_phase * 4.0 - i * 0.3);
                    double target = baseMinHeight + (_audioLevel * (maxH - baseMinHeight) * _harmonicWeights[i] * modulation);
                    _targetHeights[i] = Math.Clamp(target, baseMinHeight, maxH);
                }
                else
                {
                    // Smooth idle resting pulse
                    double idlePulse = baseMinHeight + 1.0 * Math.Sin(_phase * 0.8 + i * 0.2);
                    _targetHeights[i] = Math.Max(baseMinHeight, idlePulse);
                }

                // Attack / decay interpolation
                double attackSpeed = 0.35;
                double decaySpeed = 0.18;
                double speed = _targetHeights[i] > _currentHeights[i] ? attackSpeed : decaySpeed;
                _currentHeights[i] += (_targetHeights[i] - _currentHeights[i]) * speed;
            }

            InvalidateVisual();
        }

        protected override void OnRender(DrawingContext dc)
        {
            base.OnRender(dc);

            double width = ActualWidth;
            double height = ActualHeight;
            if (width <= 0 || height <= 0) return;

            double spacing = 2.5;
            double totalSpacing = spacing * (BarCount - 1);
            double barWidth = Math.Max(2.0, (width - totalSpacing) / BarCount);
            double centerY = height / 2.0;

            for (int i = 0; i < BarCount; i++)
            {
                double barHeight = _currentHeights[i];
                double x = i * (barWidth + spacing);
                double y = centerY - (barHeight / 2.0);

                var rect = new Rect(x, y, barWidth, barHeight);
                double radius = barWidth / 2.0;
                dc.DrawRoundedRectangle(_barBrush, null, rect, radius, radius);

                // Draw bright glowing cap if bar is elevated
                if (barHeight > 6.0)
                {
                    var topCap = new Rect(x, y, barWidth, 2.0);
                    dc.DrawRoundedRectangle(_glowCapBrush, null, topCap, 1.0, 1.0);
                    var botCap = new Rect(x, y + barHeight - 2.0, barWidth, 2.0);
                    dc.DrawRoundedRectangle(_glowCapBrush, null, botCap, 1.0, 1.0);
                }
            }
        }
    }
}
