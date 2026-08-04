class StateManager {
    constructor() {
        this.listeners = new Set();
        this.state = {
            connections: [],
            devices: [],
            developerMode: localStorage.getItem("dev-mode") === "true",
            theme: localStorage.getItem("theme") || "dark",
            metrics: {
                events_sent: 0,
                connections: 0,
                tool_requests: 0,
                avg_latency_ms: 0
            },
            events: [],
            connectionStatus: "disconnected" // "disconnected" | "connecting" | "connected"
        };
    }
    
    get() {
        return this.state;
    }
    
    set(newState) {
        this.state = { ...this.state, ...newState };
        this.notify();
    }
    
    subscribe(callback) {
        this.listeners.add(callback);
        // Invoke immediately with current state
        callback(this.state);
        return () => this.listeners.delete(callback);
    }
    
    notify() {
        for (const callback of this.listeners) {
            try {
                callback(this.state);
            } catch (e) {
                console.error("Error invoking state listener:", e);
            }
        }
    }
}

export const AppState = new StateManager();
export default AppState;
