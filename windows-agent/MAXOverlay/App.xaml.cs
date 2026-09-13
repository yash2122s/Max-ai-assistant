using System;
using System.IO;
using System.Windows;

namespace MAXOverlay
{
    public partial class App : Application
    {
        private static readonly string LogPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "max_overlay.log");

        public static void Log(string msg)
        {
            try
            {
                File.AppendAllText(LogPath, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {msg}{Environment.NewLine}");
            }
            catch { }
        }

        protected override void OnStartup(StartupEventArgs e)
        {
            Log("App.OnStartup began");
            base.OnStartup(e);

            AppDomain.CurrentDomain.UnhandledException += (s, args) =>
            {
                Log($"[CRITICAL] AppDomain unhandled: {args.ExceptionObject}");
            };

            DispatcherUnhandledException += (s, args) =>
            {
                Log($"[ERROR] Dispatcher unhandled: {args.Exception}");
                args.Handled = true;
            };

            Log("App.OnStartup completed initialization");
        }
    }
}
