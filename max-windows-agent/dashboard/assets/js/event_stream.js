import AppState from "./state.js";
import NotificationCenter from "./notifications.js";

const KNOWN_EVENTS = [
    "agent_started", 
    "agent_shutdown", 
    "client_connected", 
    "client_disconnected",
    "pair_started", 
    "pair_success", 
    "pair_failed", 
    "tool_requested",
    "tool_progress", 
    "tool_completed", 
    "tool_failed", 
    "heartbeat"
];

let eventSource = null;

async function fetchInitialStatus() {
    try {
        const response = await fetch("/api/v1/status");
        if (response.ok) {
            const result = await response.json();
            if (result.success) {
                AppState.set({
                    connections: result.data.connections || [],
                    devices: result.data.paired_devices_list || []
                });
            }
        }
    } catch (e) {
        console.error("Failed to fetch initial status:", e);
    }
}

async function fetchMetrics() {
    try {
        const response = await fetch("/api/v1/metrics");
        if (response.ok) {
            const result = await response.json();
            if (result.success) {
                AppState.set({ metrics: result.data });
            }
        }
    } catch (e) {
        console.error("Failed to fetch metrics:", e);
    }
}

export function connect() {
    if (eventSource) {
        return;
    }

    AppState.set({ connectionStatus: "connecting" });

    // Establish SSE event connection
    eventSource = new EventSource("/api/v1/events");

    eventSource.onopen = () => {
        AppState.set({ connectionStatus: "connected" });
        NotificationCenter.info("Console Connected", "Established real-time event pipeline.");
        fetchInitialStatus();
        fetchMetrics();
    };

    eventSource.onerror = (err) => {
        AppState.set({ connectionStatus: "disconnected" });
        console.warn("SSE connection error, attempting automatic recovery:", err);
    };

    // Register listeners for all specific event types
    KNOWN_EVENTS.forEach(eventType => {
        eventSource.addEventListener(eventType, (e) => {
            try {
                const eventEnvelope = JSON.parse(e.data);
                handleIncomingEvent(eventEnvelope);
            } catch (err) {
                console.error(`Failed to parse incoming event of type ${eventType}:`, err);
            }
        });
    });
}

function handleIncomingEvent(event) {
    // Append to app state events history (limit to 100 recent events)
    const currentEvents = [...AppState.get().events];
    currentEvents.unshift(event);
    if (currentEvents.length > 100) {
        currentEvents.pop();
    }
    
    AppState.set({ events: currentEvents });

    // Reactively update connection lists or metrics based on event type
    const eventType = event.type;
    const payload = event.payload || {};

    if (eventType === "client_connected" || eventType === "client_disconnected" || eventType === "pair_success") {
        fetchInitialStatus();
    }
    
    // Auto-refresh metrics on tool events
    if (eventType.startsWith("tool_") || eventType === "client_connected" || eventType === "client_disconnected") {
        fetchMetrics();
    }

    // Direct user-facing notification prompts
    if (eventType === "pair_success") {
        NotificationCenter.success("Device Paired", `Device ${payload.device_name} has successfully paired.`);
    } else if (eventType === "pair_failed") {
        NotificationCenter.error("Pairing Failed", `Pairing attempt failed: ${payload.reason}`);
    } else if (eventType === "client_connected") {
        NotificationCenter.info("Device Connected", `Device ${payload.device_name || payload.device_id} connected.`);
    } else if (eventType === "client_disconnected") {
        NotificationCenter.warning("Device Disconnected", `Device ${payload.device_id} disconnected.`);
    } else if (eventType === "tool_failed") {
        NotificationCenter.error("Tool Execution Failed", `${payload.tool}/${payload.action} failed: ${payload.error}`);
    } else if (eventType === "tool_completed") {
        NotificationCenter.success("Tool Completed", `${payload.tool}/${payload.action} completed successfully.`);
    }
}

export function disconnect() {
    if (eventSource) {
        eventSource.close();
        eventSource = null;
        AppState.set({ connectionStatus: "disconnected" });
        NotificationCenter.info("Console Disconnected", "Real-time event pipeline closed.");
    }
}
