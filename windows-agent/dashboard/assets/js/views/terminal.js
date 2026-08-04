import AppState from "../state.js";

export default {
    init(container) {
        container.innerHTML = `
            <div class="card">
                <div class="card-header">
                    <span class="card-title">Interactive Terminal</span>
                </div>
                <p>Execute agent commands directly from the browser operations console.</p>
                <div style="background-color: #09090b; border: 1px solid var(--border-color); border-radius: 6px; padding: 16px; margin-top: 16px; font-family: monospace; color: #38bdf8;">
                    <div>Microsoft Windows [Version 10.0.22631]</div>
                    <div>(c) Microsoft Corporation. All rights reserved.</div>
                    <br>
                    <div>C:\\Users\\yaswa\\Downloads\\gemini-live> <span style="background-color: var(--text-primary); width: 8px; height: 16px; display: inline-block;"></span></div>
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
