import AppState from "../state.js";

let unsubscribe = null;
let refreshInterval = null;

function formatUptime(seconds) {
    if (!seconds || isNaN(seconds)) return "0s";
    const d = Math.floor(seconds / (3600 * 24));
    const h = Math.floor((seconds % (3600 * 24)) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    if (d > 0) return `${d}d ${h}h ${m}m`;
    if (h > 0) return `${h}h ${m}m ${s}s`;
    return `${m}m ${s}s`;
}

export default {
    init(container) {
        container.innerHTML = `
            <div style="display: grid; grid-template-columns: 2fr 1fr; gap: 20px; align-items: start;">
                <div>
                    <!-- Real-Time Hardware Performance Telemetry -->
                    <div class="card" style="margin-bottom: 20px;">
                        <div class="card-header">
                            <span class="card-title">Real-Time Windows Hardware Telemetry</span>
                            <span class="status-badge connected" style="font-size: 11px;">Live Feed</span>
                        </div>
                        
                        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 16px;">
                            <!-- CPU Usage -->
                            <div class="card" style="margin: 0; background: var(--bg-color); border: 1px solid var(--border-color);">
                                <div style="display: flex; justify-content: space-between; align-items: center;">
                                    <div class="form-label">CPU Utilization</div>
                                    <span id="dash-cpu-cores" style="font-size: 10px; color: var(--text-secondary); background: var(--card-bg); padding: 2px 6px; border-radius: 4px;">- Cores</span>
                                </div>
                                <div id="dash-cpu-pct" style="font-size: 26px; font-weight: 700; margin: 6px 0; color: var(--accent-color);">0%</div>
                                <div style="width: 100%; background: var(--border-color); height: 6px; border-radius: 3px; overflow: hidden;">
                                    <div id="dash-cpu-bar" style="width: 0%; height: 100%; background: var(--accent-gradient); transition: width 0.4s ease;"></div>
                                </div>
                            </div>

                            <!-- RAM Memory -->
                            <div class="card" style="margin: 0; background: var(--bg-color); border: 1px solid var(--border-color);">
                                <div style="display: flex; justify-content: space-between; align-items: center;">
                                    <div class="form-label">Memory (RAM)</div>
                                    <span id="dash-ram-details" style="font-size: 10px; color: var(--text-secondary);">0 / 0 GB</span>
                                </div>
                                <div id="dash-ram-pct" style="font-size: 26px; font-weight: 700; margin: 6px 0; color: var(--success-color);">0%</div>
                                <div style="width: 100%; background: var(--border-color); height: 6px; border-radius: 3px; overflow: hidden;">
                                    <div id="dash-ram-bar" style="width: 0%; height: 100%; background: var(--success-color); transition: width 0.4s ease;"></div>
                                </div>
                            </div>

                            <!-- Storage Disk -->
                            <div class="card" style="margin: 0; background: var(--bg-color); border: 1px solid var(--border-color);">
                                <div style="display: flex; justify-content: space-between; align-items: center;">
                                    <div class="form-label">Storage (Disk C:)</div>
                                    <span id="dash-disk-details" style="font-size: 10px; color: var(--text-secondary);">0 / 0 GB</span>
                                </div>
                                <div id="dash-disk-pct" style="font-size: 26px; font-weight: 700; margin: 6px 0; color: #3b82f6;">0%</div>
                                <div style="width: 100%; background: var(--border-color); height: 6px; border-radius: 3px; overflow: hidden;">
                                    <div id="dash-disk-bar" style="width: 0%; height: 100%; background: #3b82f6; transition: width 0.4s ease;"></div>
                                </div>
                            </div>
                        </div>

                        <div style="margin-top: 16px; display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 16px;">
                            <!-- System Uptime -->
                            <div class="card" style="margin: 0; background: var(--bg-color); border: 1px solid var(--border-color);">
                                <div class="form-label">System Uptime</div>
                                <div id="dash-sys-uptime" style="font-size: 18px; font-weight: 700; margin-top: 6px; color: var(--text-primary);">0s</div>
                                <div style="font-size: 10px; color: var(--text-secondary); margin-top: 4px;">Host: <span id="dash-hostname">localhost</span></div>
                            </div>

                            <!-- Network Latency & WS -->
                            <div class="card" style="margin: 0; background: var(--bg-color); border: 1px solid var(--border-color);">
                                <div class="form-label">Pipeline Latency</div>
                                <div id="dash-latency" style="font-size: 18px; font-weight: 700; margin-top: 6px; color: var(--warning-color);">0 ms</div>
                                <div style="font-size: 10px; color: var(--text-secondary); margin-top: 4px;">WS Status: <span id="dash-ws-status">DISCONNECTED</span></div>
                            </div>

                            <!-- Executed Tool Commands -->
                            <div class="card" style="margin: 0; background: var(--bg-color); border: 1px solid var(--border-color);">
                                <div class="form-label">Tool Executions</div>
                                <div id="dash-req-count" style="font-size: 18px; font-weight: 700; margin-top: 6px; color: var(--accent-color);">0</div>
                                <div style="font-size: 10px; color: var(--text-secondary); margin-top: 4px;">Active Clients: <span id="dash-conn-count">0</span></div>
                            </div>
                        </div>
                    </div>

                    <!-- System Information Banner -->
                    <div class="card" style="margin: 0;">
                        <div class="card-header">
                            <span class="card-title">Agent System Environment</span>
                        </div>
                        <div style="display: flex; gap: 24px; font-size: 13px; color: var(--text-secondary);">
                            <div><strong>OS System:</strong> <span id="dash-os-info" style="color: var(--text-primary);">Windows Agent</span></div>
                            <div><strong>Protocol Version:</strong> <span style="color: var(--text-primary);">v2.1 GA</span></div>
                            <div><strong>Agent Uptime:</strong> <span id="dash-agent-uptime" style="color: var(--text-primary);">0s</span></div>
                        </div>
                    </div>
                </div>

                <!-- Pairing Authorization Node -->
                <div class="card" style="margin: 0;">
                    <div class="card-header">
                        <span class="card-title">Pairing Authorization</span>
                    </div>
                    <p style="font-size: 12px; margin-bottom: 8px; color: var(--text-secondary);">Scan this QR code from the MAX phone app to pair automatically.</p>
                    <div id="qrcode-container" style="display: flex; justify-content: center; background: white; padding: 12px; border-radius: 6px; margin: 12px 0;"></div>
                    <div style="text-align: center; font-size: 24px; font-weight: bold; letter-spacing: 4px; color: var(--accent-color);" id="pairing-code-display">------</div>
                    <p style="font-size: 10px; color: var(--text-secondary); text-align: center; margin-top: 8px;">Code rotates every 120 seconds.</p>
                    <div style="font-size: 10px; color: var(--text-secondary); word-break: break-all; margin-top: 12px; border-top: 1px solid var(--border-color); padding-top: 8px; text-align: left;">
                        <strong>Device ID:</strong> <span id="pairing-device-id" style="font-family: monospace;">unknown</span><br/>
                        <strong>SSL Hash:</strong> <span id="pairing-ssl-hash" style="font-family: monospace;">unknown</span>
                    </div>
                </div>
            </div>
        `;
    },

    show(container) {
        const updateMetricsUI = (state) => {
            const metrics = state.metrics || {};

            const cpuPctEl = document.getElementById("dash-cpu-pct");
            const cpuBarEl = document.getElementById("dash-cpu-bar");
            const cpuCoresEl = document.getElementById("dash-cpu-cores");
            if (cpuPctEl) cpuPctEl.textContent = `${metrics.cpu_percent || 0}%`;
            if (cpuBarEl) cpuBarEl.style.width = `${Math.min(100, metrics.cpu_percent || 0)}%`;
            if (cpuCoresEl) cpuCoresEl.textContent = `${metrics.cpu_cores || 4} Cores`;

            const ramPctEl = document.getElementById("dash-ram-pct");
            const ramBarEl = document.getElementById("dash-ram-bar");
            const ramDetailsEl = document.getElementById("dash-ram-details");
            if (ramPctEl) ramPctEl.textContent = `${metrics.ram_percent || 0}%`;
            if (ramBarEl) ramBarEl.style.width = `${Math.min(100, metrics.ram_percent || 0)}%`;
            if (ramDetailsEl) ramDetailsEl.textContent = `${metrics.ram_used_gb || 0} / ${metrics.ram_total_gb || 0} GB`;

            const diskPctEl = document.getElementById("dash-disk-pct");
            const diskBarEl = document.getElementById("dash-disk-bar");
            const diskDetailsEl = document.getElementById("dash-disk-details");
            if (diskPctEl) diskPctEl.textContent = `${metrics.disk_percent || 0}%`;
            if (diskBarEl) diskBarEl.style.width = `${Math.min(100, metrics.disk_percent || 0)}%`;
            if (diskDetailsEl) diskDetailsEl.textContent = `${metrics.disk_used_gb || 0} / ${metrics.disk_total_gb || 0} GB`;

            const sysUptimeEl = document.getElementById("dash-sys-uptime");
            const agentUptimeEl = document.getElementById("dash-agent-uptime");
            const hostnameEl = document.getElementById("dash-hostname");
            const osInfoEl = document.getElementById("dash-os-info");
            if (sysUptimeEl) sysUptimeEl.textContent = formatUptime(metrics.system_uptime);
            if (agentUptimeEl) agentUptimeEl.textContent = formatUptime(metrics.agent_uptime);
            if (hostnameEl) hostnameEl.textContent = metrics.hostname || "Windows System";
            if (osInfoEl) osInfoEl.textContent = metrics.os_info || "Windows 11";

            const latencyEl = document.getElementById("dash-latency");
            if (latencyEl) latencyEl.textContent = `${metrics.avg_latency_ms || 0} ms`;

            const wsStatusEl = document.getElementById("dash-ws-status");
            const connEl = document.getElementById("dash-conn-count");
            const reqEl = document.getElementById("dash-req-count");

            if (wsStatusEl) {
                wsStatusEl.textContent = state.connectionStatus.toUpperCase();
                wsStatusEl.className = state.connectionStatus === "connected" ? "brand-accent" : "";
            }
            if (connEl) connEl.textContent = state.connections ? state.connections.length : 0;
            if (reqEl) reqEl.textContent = metrics.tool_requests || 0;
        };

        unsubscribe = AppState.subscribe(updateMetricsUI);

        let lastCode = "";
        const updatePairingAndMetrics = () => {
            fetch("/api/v1/metrics")
                .then(r => r.json())
                .then(res => {
                    if (res.success && res.data) {
                        AppState.set({ metrics: res.data });
                    }
                }).catch(() => {});

            fetch("/api/pairing")
                .then(response => {
                    if (!response.ok) throw new Error("Localhost access restricted");
                    return response.json();
                })
                .then(res => {
                    if (res.success && res.data) {
                        const data = res.data;
                        const codeDisplay = document.getElementById("pairing-code-display");
                        const devIdDisplay = document.getElementById("pairing-device-id");
                        const sslHashDisplay = document.getElementById("pairing-ssl-hash");

                        if (codeDisplay) codeDisplay.textContent = data.pairing_code;
                        if (devIdDisplay) devIdDisplay.textContent = data.device_id || "unknown";
                        if (sslHashDisplay) sslHashDisplay.textContent = data.cert_fingerprint || "none";

                        if (data.pairing_code !== lastCode) {
                            lastCode = data.pairing_code;
                            const qrContainer = document.getElementById("qrcode-container");
                            if (qrContainer) {
                                qrContainer.innerHTML = "";
                                try {
                                    const qr = qrcode(0, 'L');
                                    qr.addData(JSON.stringify(data));
                                    qr.make();
                                    qrContainer.innerHTML = qr.createImgTag(4);
                                } catch (err) {
                                    console.error("Failed to generate QR code:", err);
                                    qrContainer.textContent = "QR Error";
                                }
                            }
                        }
                    }
                })
                .catch(err => {
                    console.error("Error fetching pairing data:", err);
                    const codeDisplay = document.getElementById("pairing-code-display");
                    if (codeDisplay) codeDisplay.textContent = "LOCKED";
                });
        };

        updatePairingAndMetrics();
        refreshInterval = setInterval(updatePairingAndMetrics, 2000);
    },

    destroy() {
        if (unsubscribe) {
            unsubscribe();
            unsubscribe = null;
        }
        if (refreshInterval) {
            clearInterval(refreshInterval);
            refreshInterval = null;
        }
    }
};

