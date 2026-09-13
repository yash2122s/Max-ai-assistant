using System.Text.Json.Serialization;

namespace MAXOverlay.Models
{
    /// <summary>
    /// Device control state payload for the fullscreen edge effect overlay.
    /// </summary>
    public class DeviceStatePayload
    {
        [JsonPropertyName("state")]
        public string State { get; set; } = "";

        [JsonPropertyName("device")]
        public string? Device { get; set; }

        [JsonPropertyName("action")]
        public string? Action { get; set; }
    }

    /// <summary>
    /// Confirmation request payload pushed by the ConfirmationGate.
    /// </summary>
    public class ConfirmationPayload
    {
        [JsonPropertyName("ticket_id")]
        public string TicketId { get; set; } = "";

        [JsonPropertyName("action_name")]
        public string ActionName { get; set; } = "";

        [JsonPropertyName("description")]
        public string Description { get; set; } = "";

        [JsonPropertyName("target")]
        public string Target { get; set; } = "";

        [JsonPropertyName("expires_in_seconds")]
        public double ExpiresInSeconds { get; set; } = 90.0;
    }
}
