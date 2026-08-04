import AppState from "../state.js";

export default {
    init(container) {
        container.innerHTML = `
            <div class="card">
                <div class="card-header">
                    <span class="card-title">Console Settings</span>
                </div>
                <p>Configure interface options and agent communication parameters.</p>
                <form style="margin-top: 16px; max-width: 400px;">
                    <div class="form-group">
                        <label class="form-label" for="agent-name-input">Agent Identifier</label>
                        <input id="agent-name-input" type="text" class="form-input" value="MAX Windows Agent" readonly>
                    </div>
                    <div class="form-group">
                        <label class="form-label" for="ws-port-input">WebSocket Port</label>
                        <input id="ws-port-input" type="text" class="form-input" value="9000" readonly>
                    </div>
                    <div class="form-group">
                        <label class="form-label" for="http-port-input">HTTP Port</label>
                        <input id="http-port-input" type="text" class="form-input" value="9001" readonly>
                    </div>
                </form>
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
