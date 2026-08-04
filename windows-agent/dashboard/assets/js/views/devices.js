import AppState from "../state.js";

export default {
    init(container) {
        container.innerHTML = `
            <div class="card">
                <div class="card-header">
                    <span class="card-title">Connected Devices</span>
                </div>
                <p>Track all active and authorized Android and Web client devices.</p>
                <div style="margin-top: 16px;">
                    <div style="color: var(--text-secondary); font-size: 13px; text-align: center; padding: 24px; border: 1px dashed var(--border-color); border-radius: 6px;">
                        No authorized devices are currently connected. Display pairing codes in Settings to authorize a new client.
                    </div>
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
