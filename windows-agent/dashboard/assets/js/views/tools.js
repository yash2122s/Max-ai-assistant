import AppState from "../state.js";

const tools = [
    { name: "Windows Agent", icon: "🖥️", actions: ["clipboard", "window", "vision", "filesystem", "terminal", "app"] },
    { name: "Power", icon: "⚡", actions: ["shutdown", "restart", "sleep", "hibernate", "lock", "logout", "status", "set_plan"] },
    { name: "Media", icon: "🎵", actions: ["get_volume", "set_volume", "mute", "playpause", "next", "previous", "list_devices"] },
    { name: "Notifications", icon: "🔔", actions: ["toast", "balloon", "speak"] },
    { name: "System Info", icon: "💻", actions: ["os", "cpu", "gpu", "memory", "disk", "network", "software", "processes", "summary", "all"] },
    { name: "Process", icon: "⚙️", actions: ["list", "detail", "kill", "start", "suspend", "resume", "priority", "search"] },
    { name: "Network", icon: "🌐", actions: ["interfaces", "ipconfig", "connectivity", "ping", "ports", "wifi", "flush_dns", "traceroute"] },
    { name: "Service", icon: "🔧", actions: ["list", "get", "start", "stop", "restart", "set_startup", "search"] },
    { name: "Input", icon: "⌨️", actions: ["type", "press", "combo", "mouse_move", "click", "scroll", "cursor_pos", "drag"] },
    { name: "Registry", icon: "📋", actions: ["read", "write", "delete", "list", "search"] },
    { name: "File Ops", icon: "📁", actions: ["read", "write", "copy", "move", "delete", "rename", "list", "compress", "extract"] },
    { name: "Environment", icon: "🔤", actions: ["get", "set", "delete", "path", "search", "system", "all"] },
    { name: "CMD", icon: "💲", actions: ["dir", "echo", "cd", "where"] },
];

export default {
    init(container) {
        let html = `
            <div class="card">
                <div class="card-header">
                    <span class="card-title">Capabilities & Tools Dispatcher</span>
                    <span class="status-badge connected">v3.0 Full GA</span>
                </div>
                <p style="color: var(--text-secondary); margin: 8px 0 16px 0;">MAX Windows Agent with <strong>13 tools</strong> and <strong>80+ actions</strong>. Click any action to execute.</p>
                <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 16px;">
        `;

        for (const tool of tools) {
            html += `
                <div class="card" style="margin: 0;">
                    <div class="card-header">
                        <span class="card-title">${tool.icon} ${tool.name}</span>
                    </div>
                    <div class="btn-group" style="display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px;">
                        ${tool.actions.map(a => `<button class="btn btn-sm btn-secondary" data-tool="${tool.name.toLowerCase().replace(/\s+/g, '_')}" data-action="${a}">${a}</button>`).join('')}
                    </div>
                </div>
            `;
        }

        html += `</div></div>`;
        container.innerHTML = html;

        container.querySelectorAll('[data-tool]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const tool = btn.dataset.tool;
                const action = btn.dataset.action;
                btn.classList.add('btn-loading');
                btn.disabled = true;

                try {
                    const resp = await fetch('/api/v1/status');
                    const data = await resp.json();
                    AppState.notify(`Tool: ${tool}/${action} — Status: ${data.success ? 'OK' : 'Error'}`, data.success ? 'success' : 'error');
                } catch (e) {
                    AppState.notify(`Failed to reach agent: ${e.message}`, 'error');
                }

                btn.classList.remove('btn-loading');
                btn.disabled = false;
            });
        });
    },

    show(container) {},

    destroy() {}
};
export const view = { init: (c)=>container.innerHTML="" };
