using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace MAXOverlay.Services
{
    /// <summary>
    /// Multi-monitor management using modern PerMonitorV2 DPI APIs.
    /// Uses GetDpiForWindow (not GetDpiForMonitor) per user specification.
    /// </summary>
    public static class MonitorManager
    {
        [DllImport("user32.dll")]
        private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

        [DllImport("user32.dll")]
        private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

        [DllImport("user32.dll")]
        private static extern uint GetDpiForWindow(IntPtr hwnd);

        [DllImport("user32.dll")]
        private static extern uint GetDpiForSystem();

        private const uint MONITOR_DEFAULTTOPRIMARY = 1;

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT
        {
            public int Left, Top, Right, Bottom;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MONITORINFO
        {
            public int cbSize;
            public RECT rcMonitor;
            public RECT rcWork;
            public uint dwFlags;
        }

        /// <summary>
        /// Positions a window at the top-center of its current monitor, accounting for DPI scaling.
        /// </summary>
        public static void PositionTopCenter(Window window, double windowWidth, double windowHeight, double topMargin = 8.0)
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            if (hwnd == IntPtr.Zero) return;

            var monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTOPRIMARY);
            var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (!GetMonitorInfo(monitor, ref mi)) return;

            // Get DPI scale for this window (PerMonitorV2)
            uint dpi = GetDpiForWindow(hwnd);
            if (dpi == 0) dpi = GetDpiForSystem();
            double scale = dpi / 96.0;

            double monitorWidthDip = (mi.rcWork.Right - mi.rcWork.Left) / scale;
            double monitorTopDip = mi.rcWork.Top / scale;
            double monitorLeftDip = mi.rcWork.Left / scale;

            window.Left = monitorLeftDip + (monitorWidthDip - windowWidth) / 2.0;
            window.Top = monitorTopDip + topMargin;
        }

        /// <summary>
        /// Gets the work area rect of the primary monitor in physical pixels.
        /// </summary>
        public static Rect GetPrimaryWorkArea()
        {
            var hwnd = IntPtr.Zero; // Will use primary monitor
            var monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTOPRIMARY);
            var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (!GetMonitorInfo(monitor, ref mi))
                return new Rect(0, 0, SystemParameters.PrimaryScreenWidth, SystemParameters.PrimaryScreenHeight);

            return new Rect(
                mi.rcMonitor.Left, mi.rcMonitor.Top,
                mi.rcMonitor.Right - mi.rcMonitor.Left,
                mi.rcMonitor.Bottom - mi.rcMonitor.Top
            );
        }

        /// <summary>
        /// Gets the full monitor rect (including taskbar area) in DIPs for a given window.
        /// </summary>
        public static Rect GetFullMonitorRect(Window window)
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            if (hwnd == IntPtr.Zero)
                return new Rect(0, 0, SystemParameters.PrimaryScreenWidth, SystemParameters.PrimaryScreenHeight);

            var monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTOPRIMARY);
            var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (!GetMonitorInfo(monitor, ref mi))
                return new Rect(0, 0, SystemParameters.PrimaryScreenWidth, SystemParameters.PrimaryScreenHeight);

            uint dpi = GetDpiForWindow(hwnd);
            if (dpi == 0) dpi = GetDpiForSystem();
            double scale = dpi / 96.0;

            return new Rect(
                mi.rcMonitor.Left / scale,
                mi.rcMonitor.Top / scale,
                (mi.rcMonitor.Right - mi.rcMonitor.Left) / scale,
                (mi.rcMonitor.Bottom - mi.rcMonitor.Top) / scale
            );
        }
    }
}
