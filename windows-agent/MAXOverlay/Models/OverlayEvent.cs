using System.Text.Json;
using System.Text.Json.Serialization;

namespace MAXOverlay.Models
{
    /// <summary>
    /// Envelope for all events received from the Python MAX backend via WebSocket.
    /// Matches the EventBus publish format: { schema_version, id, type, timestamp, payload }
    /// </summary>
    public class OverlayEvent
    {
        [JsonPropertyName("schema_version")]
        public int SchemaVersion { get; set; } = 1;

        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("type")]
        public string Type { get; set; } = "";

        [JsonPropertyName("timestamp")]
        public double Timestamp { get; set; }

        [JsonPropertyName("payload")]
        public JsonElement Payload { get; set; }
    }

    /// <summary>
    /// Message sent FROM the overlay TO the Python backend.
    /// </summary>
    public class OverlayMessage
    {
        [JsonPropertyName("type")]
        public string Type { get; set; } = "";

        [JsonPropertyName("ticket_id")]
        public string? TicketId { get; set; }

        [JsonPropertyName("action")]
        public string? Action { get; set; }

        [JsonPropertyName("reason")]
        public string? Reason { get; set; }

        [JsonPropertyName("timestamp")]
        public double Timestamp { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0;
    }
}
