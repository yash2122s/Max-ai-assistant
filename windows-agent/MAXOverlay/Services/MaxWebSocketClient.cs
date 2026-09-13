using System;
using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MAXOverlay.Models;

namespace MAXOverlay.Services
{
    /// <summary>
    /// Auto-reconnecting WebSocket client that connects to the Python MAX backend's
    /// UI Bridge endpoint (ws://127.0.0.1:9002).
    /// </summary>
    public class MaxWebSocketClient : IDisposable
    {
        private readonly string _uri;
        private ClientWebSocket? _ws;
        private CancellationTokenSource? _cts;
        private bool _disposed;
        private int _reconnectAttempt;
        private const int MaxReconnectDelay = 15_000;

        public event Action<OverlayEvent>? EventReceived;
        public event Action<bool>? ConnectionStateChanged;

        public bool IsConnected => _ws?.State == WebSocketState.Open;

        public MaxWebSocketClient(string uri = "ws://127.0.0.1:9002")
        {
            _uri = uri;
        }

        public async Task StartAsync()
        {
            _cts = new CancellationTokenSource();
            await ConnectLoop(_cts.Token);
        }

        public void Stop()
        {
            _cts?.Cancel();
            try { _ws?.Abort(); } catch { }
        }

        private async Task ConnectLoop(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    _ws?.Dispose();
                    _ws = new ClientWebSocket();
                    _ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(10);

                    Debug.WriteLine($"[WS] Connecting to {_uri}...");
                    await _ws.ConnectAsync(new Uri(_uri), ct);

                    _reconnectAttempt = 0;
                    Debug.WriteLine("[WS] Connected");
                    ConnectionStateChanged?.Invoke(true);

                    await ReceiveLoop(ct);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"[WS] Connection error: {ex.Message}");
                    ConnectionStateChanged?.Invoke(false);
                }

                if (ct.IsCancellationRequested) break;

                // Exponential backoff: 1s, 2s, 4s, 8s, 15s max
                _reconnectAttempt++;
                int delay = Math.Min(1000 * (1 << Math.Min(_reconnectAttempt - 1, 4)), MaxReconnectDelay);
                Debug.WriteLine($"[WS] Reconnecting in {delay}ms (attempt {_reconnectAttempt})...");
                await Task.Delay(delay, ct).ConfigureAwait(false);
            }
        }

        private async Task ReceiveLoop(CancellationToken ct)
        {
            var buffer = new byte[16384];
            var sb = new StringBuilder();

            while (_ws?.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                sb.Clear();
                WebSocketReceiveResult result;

                do
                {
                    result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Debug.WriteLine("[WS] Server closed connection");
                        ConnectionStateChanged?.Invoke(false);
                        return;
                    }
                    sb.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
                }
                while (!result.EndOfMessage);

                var json = sb.ToString();
                try
                {
                    var evt = JsonSerializer.Deserialize<OverlayEvent>(json);
                    if (evt != null)
                    {
                        EventReceived?.Invoke(evt);
                    }
                }
                catch (JsonException ex)
                {
                    Debug.WriteLine($"[WS] JSON parse error: {ex.Message}");
                }
            }
        }

        public async Task SendAsync(OverlayMessage message)
        {
            if (_ws?.State != WebSocketState.Open) return;

            try
            {
                var json = JsonSerializer.Serialize(message);
                var bytes = Encoding.UTF8.GetBytes(json);
                await _ws.SendAsync(
                    new ArraySegment<byte>(bytes),
                    WebSocketMessageType.Text,
                    true,
                    _cts?.Token ?? CancellationToken.None
                );
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[WS] Send error: {ex.Message}");
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _cts?.Cancel();
            _ws?.Dispose();
            _cts?.Dispose();
        }
    }
}
