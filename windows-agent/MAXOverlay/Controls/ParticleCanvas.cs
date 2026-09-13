using System;
using System.Windows;
using System.Windows.Media;

namespace MAXOverlay.Controls
{
    public enum ParticleMode
    {
        IdleCapsule,
        ReactorSurround,
        Inactive
    }

    public struct Particle
    {
        public double X;
        public double Y;
        public double Vx;
        public double Vy;
        public double Size;
        public double Alpha;
        public double Life;
        public double MaxLife;
        public double Angle;
        public double Radius;
        public double AngularSpeed;
    }

    /// <summary>
    /// GPU-composited lightweight particle system for AI HUD ambient motes and orbital sparks.
    /// Operates without per-frame allocations.
    /// </summary>
    public class ParticleCanvas : FrameworkElement
    {
        private const int MaxParticles = 24;
        private readonly Particle[] _particles = new Particle[MaxParticles];
        private readonly Random _rng = new();

        private Brush _cyanParticleBrush;
        private Brush _violetParticleBrush;
        private ParticleMode _mode = ParticleMode.IdleCapsule;

        public ParticleMode Mode
        {
            get => _mode;
            set
            {
                _mode = value;
                InitParticles();
            }
        }

        public ParticleCanvas()
        {
            ClipToBounds = true;
            IsHitTestVisible = false;

            var cyan = new SolidColorBrush(Color.FromArgb(200, 0x00, 0xF0, 0xFF));
            cyan.Freeze();
            _cyanParticleBrush = cyan;

            var violet = new SolidColorBrush(Color.FromArgb(180, 0xC0, 0x84, 0xFC));
            violet.Freeze();
            _violetParticleBrush = violet;

            InitParticles();
        }

        private void InitParticles()
        {
            double w = ActualWidth > 0 ? ActualWidth : 220;
            double h = ActualHeight > 0 ? ActualHeight : 42;

            for (int i = 0; i < MaxParticles; i++)
            {
                ResetParticle(i, w, h);
            }
        }

        private void ResetParticle(int index, double w, double h)
        {
            ref var p = ref _particles[index];
            p.MaxLife = 60 + _rng.Next(120);
            p.Life = _rng.Next((int)p.MaxLife);
            p.Size = 1.0 + _rng.NextDouble() * 2.2;
            p.Alpha = 0.2 + _rng.NextDouble() * 0.6;

            if (_mode == ParticleMode.ReactorSurround)
            {
                p.Angle = _rng.NextDouble() * Math.PI * 2;
                p.Radius = 10 + _rng.NextDouble() * 24;
                p.AngularSpeed = (0.02 + _rng.NextDouble() * 0.04) * (_rng.Next(2) == 0 ? 1 : -1);
                p.X = 18 + Math.Cos(p.Angle) * p.Radius;
                p.Y = (h / 2.0) + Math.Sin(p.Angle) * p.Radius;
                p.Vx = 0;
                p.Vy = 0;
            }
            else
            {
                // Idle capsule drift
                p.X = _rng.NextDouble() * w;
                p.Y = 4 + _rng.NextDouble() * (h - 8);
                p.Vx = 0.15 + _rng.NextDouble() * 0.35;
                p.Vy = (_rng.NextDouble() - 0.5) * 0.15;
            }
        }

        public void UpdateFrame()
        {
            if (_mode == ParticleMode.Inactive) return;

            double w = ActualWidth;
            double h = ActualHeight;
            if (w <= 0 || h <= 0) return;

            for (int i = 0; i < MaxParticles; i++)
            {
                ref var p = ref _particles[i];
                p.Life++;

                if (p.Life >= p.MaxLife || p.X > w + 10 || p.X < -10 || p.Y > h + 10 || p.Y < -10)
                {
                    ResetParticle(i, w, h);
                    continue;
                }

                if (_mode == ParticleMode.ReactorSurround)
                {
                    p.Angle += p.AngularSpeed;
                    p.Radius += (_rng.NextDouble() - 0.5) * 0.2;
                    p.X = 20 + Math.Cos(p.Angle) * p.Radius;
                    p.Y = (h / 2.0) + Math.Sin(p.Angle) * p.Radius;
                }
                else
                {
                    p.X += p.Vx;
                    p.Y += p.Vy;
                }
            }

            InvalidateVisual();
        }

        protected override void OnRender(DrawingContext dc)
        {
            base.OnRender(dc);

            if (_mode == ParticleMode.Inactive) return;

            for (int i = 0; i < MaxParticles; i++)
            {
                ref var p = ref _particles[i];
                double progress = p.Life / p.MaxLife;
                // Fade in then fade out smoothly
                double fade = Math.Sin(progress * Math.PI);
                double currentAlpha = Math.Clamp(p.Alpha * fade, 0.0, 1.0);

                if (currentAlpha <= 0.02) continue;

                var brush = (i % 3 == 0) ? _violetParticleBrush : _cyanParticleBrush;
                dc.PushOpacity(currentAlpha);
                dc.DrawEllipse(brush, null, new Point(p.X, p.Y), p.Size, p.Size);
                dc.Pop();
            }
        }
    }
}
