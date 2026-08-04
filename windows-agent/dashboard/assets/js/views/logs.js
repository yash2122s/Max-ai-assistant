import AppState from "../state.js";

export default {
    init(container) {
        container.innerHTML = `
            <div class="card">
                <div class="card-header">
                    <span class="card-title">System Log Viewer</span>
                </div>
                <p>Monitor live log traces from `max-agent.log`.</p>
                <div style="background-color: var(--sidebar-bg); border: 1px solid var(--border-color); border-radius: 6px; padding: 16px; margin-top: 16px; font-family: monospace; font-size: 12px; color: var(--text-secondary); max-height: 300px; overflow-y: auto;">
                    <div>[INFO] Initialized server. Current pairing code: 834250</div>
                    <div>[INFO] WebSocket server listening on ws://localhost:9000</div>
                    <div>[INFO] HTTP server listening on http://localhost:9001</div>
                </div>
            </div>
        `;
    },

    show(container) {
        // Initialization behavior
    },

    destroy() {
        // Teardown behavior
    }
};
export const view = { init: (c)=>container.innerHTML="" };
