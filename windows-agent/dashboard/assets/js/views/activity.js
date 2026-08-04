import AppState from "../state.js";

let unsubscribe = null;

export default {
    init(container) {
        container.innerHTML = `
            <div class="card">
                <div class="card-header">
                    <span class="card-title">Live Activity Timeline</span>
                </div>
                <p>Real-time event stream from the agent's internal EventBus.</p>
                <div id="activity-list" style="margin-top: 16px; display: flex; flex-direction: column; gap: 8px;">
                    <div style="color: var(--text-secondary); text-align: center; padding: 20px;">No events recorded yet.</div>
                </div>
            </div>
        `;
    },

    show(container) {
        unsubscribe = AppState.subscribe(state => {
            const listEl = document.getElementById("activity-list");
            if (!listEl) return;

            // Define developer vs normal visibility lists
            const filteredEvents = state.events.filter(evt => {
                if (state.developerMode) return true;
                return !["heartbeat", "tool_progress", "tool_requested", "pair_started"].includes(evt.type);
            });

            if (filteredEvents.length === 0) {
                listEl.innerHTML = `<div style="color: var(--text-secondary); text-align: center; padding: 20px; font-size: 13px;">No events matching current visibility level.</div>`;
                return;
            }

            listEl.innerHTML = filteredEvents.map(evt => {
                const timeStr = new Date(evt.timestamp * 1000).toLocaleTimeString();
                let statusClass = "connected"; // Connected green badge
                if (evt.type.includes("failed") || evt.type.includes("disconnected") || evt.type.includes("shutdown")) {
                    statusClass = "disconnected"; // Disconnected red badge
                } else if (evt.type.includes("requested") || evt.type.includes("progress") || evt.type.includes("started")) {
                    statusClass = "connecting"; // Connecting yellow/orange badge
                }

                return `
                    <div class="card" style="margin: 0; padding: 12px; display: flex; align-items: center; justify-content: space-between; border-left: 3px solid var(--accent-color); font-size: 13px;">
                        <div style="display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0;">
                            <span class="status-badge ${statusClass}" style="flex-shrink: 0;">${evt.type.toUpperCase()}</span>
                            <span style="font-family: monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--text-primary);">
                                ${JSON.stringify(evt.payload || {})}
                            </span>
                        </div>
                        <div style="font-size: 11px; color: var(--text-secondary); margin-left: 12px; flex-shrink: 0;">${timeStr}</div>
                    </div>
                `;
            }).join("");
        });
    },

    destroy() {
        if (unsubscribe) {
            unsubscribe();
            unsubscribe = null;
        }
    }
};
export const view = { init: (c)=>container.innerHTML="" };
