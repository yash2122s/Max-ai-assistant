using System;
using System.Windows;
using System.Windows.Media;

namespace MAXOverlay.Controls
{
    /// <summary>
    /// GPU-composited high-performance HUD perimeter visual for fullscreen device control.
    /// Draws corner reticle nodes, traveling laser/energy beams, edge particles, and subtle scan-lines.
    /// </summary>
    public class DevicePerimeterVisual : FrameworkElement
    {
        private double _phase;
        private string _state = "DEVICE_CONTROL_RUNNING";
        private Color _primaryColor = Color.FromArgb(255, 0x00, 0xF0, 0xFF);
        private Color _secondaryColor = Color.FromArgb(255, 0x38, 0xBD, 0xF8);
        private Color _glowColor = Color.FromArgb(120, 0x00, 0xF0, 0xFF);

        private Pen _beamPen = null!;
        private Pen _reticlePen = null!;
        private Brush _nodeBrush = null!;
        private Brush _particleBrush = null!;
        private Pen _scanlinePen = null!;

        public DevicePerimeterVisual()
        {
            IsHitTestVisible = false;
            ClipToBounds = true;
            UpdateTheme();
        }

        public void SetState(string state)
        {
            _state = state;
            UpdateTheme();
            InvalidateVisual();
        }

        private void UpdateTheme()
        {
            switch (_state)
            {
                case "DEVICE_CONTROL_SUCCESS":
                    _primaryColor = Color.FromArgb(255, 0x10, 0xB9, 0x81);
                    _secondaryColor = Color.FromArgb(255, 0x34, 0xD3, 0x99);
                    _glowColor = Color.FromArgb(140, 0x10, 0xB9, 0x81);
                    break;

                case "DEVICE_CONTROL_FAILED":
                    _primaryColor = Color.FromArgb(255, 0xEF, 0x44, 0x44);
                    _secondaryColor = Color.FromArgb(255, 0xF8, 0x71, 0x71);
                    _glowColor = Color.FromArgb(140, 0xEF, 0x44, 0x44);
                    break;

                case "DEVICE_CONTROL_STARTED":
                case "DEVICE_CONTROL_RUNNING":
                default:
                    _primaryColor = Color.FromArgb(255, 0x00, 0xF0, 0xFF);
                    _secondaryColor = Color.FromArgb(255, 0x38, 0xBD, 0xF8);
                    _glowColor = Color.FromArgb(120, 0x00, 0xF0, 0xFF);
                    break;
            }

            var beamBrush = new SolidColorBrush(_primaryColor);
            beamBrush.Freeze();
            _beamPen = new Pen(beamBrush, 2.5);
            _beamPen.Freeze();

            var reticleBrush = new SolidColorBrush(_secondaryColor);
            reticleBrush.Freeze();
            _reticlePen = new Pen(reticleBrush, 1.8);
            _reticlePen.Freeze();

            var node = new SolidColorBrush(_primaryColor);
            node.Freeze();
            _nodeBrush = node;

            var part = new SolidColorBrush(Color.FromArgb(220, 0xFF, 0xFF, 0xFF));
            part.Freeze();
            _particleBrush = part;

            var scanBrush = new SolidColorBrush(Color.FromArgb(8, 0x00, 0xF0, 0xFF));
            scanBrush.Freeze();
            _scanlinePen = new Pen(scanBrush, 1.0);
            _scanlinePen.Freeze();
        }

        public void UpdateFrame()
        {
            _phase += 0.02;
            InvalidateVisual();
        }

        protected override void OnRender(DrawingContext dc)
        {
            base.OnRender(dc);

            double w = ActualWidth;
            double h = ActualHeight;
            if (w <= 0 || h <= 0) return;

            // 1. Subtle horizontal scan-lines (spaced every 8px)
            for (double y = 4; y < h; y += 8)
            {
                dc.DrawLine(_scanlinePen, new Point(0, y), new Point(w, y));
            }

            // 2. Corner HUD Reticles (L-brackets + node dots at 4 corners)
            double reticleSize = 38.0;
            double inset = 4.0;
            double pulse = 0.6 + 0.4 * Math.Sin(_phase * 3.0);

            DrawCornerReticle(dc, inset, inset, reticleSize, isTop: true, isLeft: true, pulse);
            DrawCornerReticle(dc, w - inset, inset, reticleSize, isTop: true, isLeft: false, pulse);
            DrawCornerReticle(dc, inset, h - inset, reticleSize, isTop: false, isLeft: true, pulse);
            DrawCornerReticle(dc, w - inset, h - inset, reticleSize, isTop: false, isLeft: false, pulse);

            // 3. Flowing Perimeter Energy Beams
            // Top beam
            DrawFlowingEdge(dc, new Point(inset + reticleSize, inset), new Point(w - inset - reticleSize, inset), isHorizontal: true, offsetFactor: 0.0);
            // Bottom beam
            DrawFlowingEdge(dc, new Point(w - inset - reticleSize, h - inset), new Point(inset + reticleSize, h - inset), isHorizontal: true, offsetFactor: 0.5);
            // Left beam
            DrawFlowingEdge(dc, new Point(inset, h - inset - reticleSize), new Point(inset, inset + reticleSize), isHorizontal: false, offsetFactor: 0.75);
            // Right beam
            DrawFlowingEdge(dc, new Point(w - inset, inset + reticleSize), new Point(w - inset, h - inset - reticleSize), isHorizontal: false, offsetFactor: 0.25);
        }

        private void DrawCornerReticle(DrawingContext dc, double x, double y, double size, bool isTop, bool isLeft, double pulse)
        {
            double dx = isLeft ? size : -size;
            double dy = isTop ? size : -size;

            // Corner L-lines
            dc.DrawLine(_reticlePen, new Point(x, y), new Point(x + dx, y));
            dc.DrawLine(_reticlePen, new Point(x, y), new Point(x, y + dy));

            // Small tick mark
            double tickX = isLeft ? (x + size * 0.5) : (x - size * 0.5);
            double tickY = isTop ? (y + 4) : (y - 4);
            dc.DrawLine(_reticlePen, new Point(tickX, y), new Point(tickX, tickY));

            // Central pulsing node dot
            double nodeX = isLeft ? (x + 8) : (x - 8);
            double nodeY = isTop ? (y + 8) : (y - 8);
            double nodeRadius = 3.0 + pulse * 2.0;

            dc.PushOpacity(pulse);
            dc.DrawEllipse(_nodeBrush, null, new Point(nodeX, nodeY), nodeRadius, nodeRadius);
            dc.Pop();
        }

        private void DrawFlowingEdge(DrawingContext dc, Point start, Point end, bool isHorizontal, double offsetFactor)
        {
            double travelingProgress = (_phase * 0.5 + offsetFactor) % 1.0;
            if (travelingProgress < 0) travelingProgress += 1.0;

            var gradient = new LinearGradientBrush
            {
                StartPoint = new Point(0, 0),
                EndPoint = isHorizontal ? new Point(1, 0) : new Point(0, 1)
            };

            double head = travelingProgress;
            double tail = Math.Max(0.0, head - 0.35);

            gradient.GradientStops.Add(new GradientStop(Color.FromArgb(20, _primaryColor.R, _primaryColor.G, _primaryColor.B), 0.0));
            gradient.GradientStops.Add(new GradientStop(Color.FromArgb(40, _primaryColor.R, _primaryColor.G, _primaryColor.B), tail));
            gradient.GradientStops.Add(new GradientStop(_primaryColor, head));
            gradient.GradientStops.Add(new GradientStop(Color.FromArgb(20, _primaryColor.R, _primaryColor.G, _primaryColor.B), Math.Min(1.0, head + 0.05)));
            gradient.GradientStops.Add(new GradientStop(Color.FromArgb(15, _primaryColor.R, _primaryColor.G, _primaryColor.B), 1.0));
            gradient.Freeze();

            var edgePen = new Pen(gradient, 2.0);
            edgePen.Freeze();
            dc.DrawLine(edgePen, start, end);

            // Leading traveling spark particle
            double sparkX = start.X + (end.X - start.X) * head;
            double sparkY = start.Y + (end.Y - start.Y) * head;
            dc.DrawEllipse(_particleBrush, null, new Point(sparkX, sparkY), 2.5, 2.5);
        }
    }
}
