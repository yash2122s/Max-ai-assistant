using System.Text.Json.Serialization;

namespace MAXOverlay.Models
{
    /// <summary>
    /// Task progress payload from the Python TaskEngine.
    /// </summary>
    public class TaskStatePayload
    {
        [JsonPropertyName("task_id")]
        public string TaskId { get; set; } = "";

        [JsonPropertyName("title")]
        public string Title { get; set; } = "";

        [JsonPropertyName("step")]
        public int Step { get; set; }

        [JsonPropertyName("total_steps")]
        public int TotalSteps { get; set; }

        [JsonPropertyName("progress")]
        public double Progress { get; set; }

        [JsonPropertyName("status")]
        public string Status { get; set; } = "";

        [JsonPropertyName("tool")]
        public string? Tool { get; set; }
    }
}
