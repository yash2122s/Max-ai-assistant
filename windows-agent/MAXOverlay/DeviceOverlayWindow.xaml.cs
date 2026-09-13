using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace MAXOverlay
{
    /// <summary>
    /// Fullscreen, always-on-top, strictly click-through overlay for device control effects.
    /// Uses GPU-composited DevicePerimeterVisual for corner reticles, laser beams, and scanlines.
    /// </summary>
    public partial class DeviceOverlayWindow : Window
    {
        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hwnd, int index);

        [DllImport("user32.dll")]
        private static extern int SetWindowLong(IntPtr hwnd, int index, int newStyle);

        private const int GWL_EXSTYLE = -20;
        private const int WS_EX_TRANSPARENT = 0x00000020;
        private const int WS_EX_TOOLWINDOW = 0x00000080;

        private readonly DispatcherTimer _renderTimer;

        public DeviceOverlayWindow()
        {
            InitializeComponent();

            _renderTimer = new DispatcherTimer(DispatcherPriority.Render)
            {
                Interval = TimeSpan.FromMilliseconds(16)
            };
            _renderTimer.Tick += (s, e) => PerimeterVisual.UpdateFrame();

            Loaded += OnWindowLoaded;
        }

        private void OnWindowLoaded(object sender, RoutedEventArgs e)
        {
            var hwnd = new WindowInteropHelper(this).Handle;

            // Enforce strictly click-through and tool window (hidden from Alt+Tab)
            int exStyle = GetWindowLong(hwnd, GWL_EXSTYLE);
            SetWindowLong(hwnd, GWL_EXSTYLE, exStyle | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW);

            _renderTimer.Start();

            // Smooth fade-in
            Opacity = 0;
            var fadeIn = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(400))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };
            BeginAnimation(OpacityProperty, fadeIn);
        }

        public void SetDeviceState(string state)
        {
            PerimeterVisual.SetState(state);
        }

        /// <summary>Fade out and close the overlay window.</summary>
        public void FadeOutAndClose()
        {
            var fadeOut = new DoubleAnimation(Opacity, 0, TimeSpan.FromMilliseconds(450))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn }
            };
            fadeOut.Completed += (s, e) =>
            {
                _renderTimer.Stop();
                Close();
            };
            BeginAnimation(OpacityProperty, fadeOut);
        }

        protected override void OnClosed(EventArgs e)
        {
            _renderTimer.Stop();
            base.OnClosed(e);
        }
    }
}
