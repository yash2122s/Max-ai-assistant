import AppState from "../state.js";

let unsubscribe = null;
let refreshInterval = null;

export default {
    init(container) {
        container.innerHTML = `
            <div style="display: grid; grid-template-columns: 2fr 1fr; gap: 16px; align-items: start;">
                <div class="card" style="margin: 0;">
                    <div class="card-header">
                        <span class="card-title">System Overview</span>
                    </div>
                    <p>Telemetry metrics and real-time operational updates.</p>
                    <div style="margin-top: 16px; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px;">
                        <div class="card" style="margin: 0; background: var(--bg-color);">
                            <div class="form-label">WebSocket Status</div>
                            <div id="dash-ws-status" style="font-size: 20px; font-weight: 700; margin-top: 8px;">Disconnected</div>
                        </div>
                        <div class="card" style="margin: 0; background: var(--bg-color);">
                            <div class="form-label font-bold">Active Connections</div>
                            <div id="dash-conn-count" style="font-size: 24px; font-weight: 700; margin-top: 8px;">0</div>
                        </div>
                        <div class="card" style="margin: 0; background: var(--bg-color);">
                            <div class="form-label">Tool Commands Count</div>
                            <div id="dash-req-count" style="font-size: 24px; font-weight: 700; margin-top: 8px;">0</div>
                        </div>
                    </div>
                </div>
                
                <div class="card" style="margin: 0;">
                    <div class="card-header">
                        <span class="card-title">Pairing Authorization</span>
                    </div>
                    <p style="font-size: 12px; margin-bottom: 8px; color: var(--text-secondary);">Scan this QR code from the MAX phone app to pair automatically.</p>
                    <div id="qrcode-container" style="display: flex; justify-content: center; background: white; padding: 12px; border-radius: 6px; margin: 12px 0;"></div>
                    <div style="text-align: center; font-size: 24px; font-weight: bold; letter-spacing: 4px; color: var(--brand-color);" id="pairing-code-display">------</div>
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
        unsubscribe = AppState.subscribe(state => {
            const wsStatusEl = document.getElementById("dash-ws-status");
            const connEl = document.getElementById("dash-conn-count");
            const reqEl = document.getElementById("dash-req-count");
            
            if (wsStatusEl) {
                wsStatusEl.textContent = state.connectionStatus.toUpperCase();
                wsStatusEl.className = state.connectionStatus === "connected" ? "brand-accent" : "";
            }
            if (connEl) connEl.textContent = state.connections.length;
            if (reqEl) reqEl.textContent = state.metrics.tool_requests || 0;
        });

        let lastCode = "";
        const updatePairingData = () => {
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
                                    // Generate QR code using locally bundled library (type 0 for auto-sizing)
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


        updatePairingData();
        refreshInterval = setInterval(updatePairingData, 3000);
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
