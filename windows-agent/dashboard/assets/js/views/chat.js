/**
 * JARVIS Windows Agent — Sci-Fi Live AI Chat View
 */

let sseEventSource = null;
let speechRecognition = null;
let isRecording = false;

export default {
    async init(container) {
        container.innerHTML = `
            <div class="jarvis-chat-wrapper">
                <!-- Top Status Deck -->
                <div class="chat-status-bar">
                    <div class="core-avatar-orb">
                        <div class="orb-core"></div>
                        <div class="orb-ring"></div>
                    </div>
                    <div class="core-info">
                        <div class="core-title">JARVIS AI SYSTEM CORE</div>
                        <div class="core-subtitle" id="core-state-label">LIVE VOICE & TEXT INTERFACE • TENGLISH READY</div>
                    </div>
                    <div class="core-badges">
                        <span class="badge online">● LIVE AGENT ON PORT 9001</span>
                    </div>
                </div>

                <!-- Chat History Log -->
                <div class="chat-messages-container" id="chat-messages">
                    <div class="message jarvis-msg">
                        <div class="msg-header">
                            <span class="msg-role">🤖 JARVIS</span>
                            <span class="msg-time">${new Date().toLocaleTimeString()}</span>
                        </div>
                        <div class="msg-content">
                            Namaskaram boss! Nenu JARVIS. Windows Agent active ga undhi. 
                            Meeru system controls (volume, brightness, applications, search) or general questions edhainaa ikkada text through or mic tap chesi adagavachhu!
                        </div>
                    </div>
                </div>

                <!-- Input Controls Bar -->
                <div class="chat-input-bar">
                    <button id="mic-toggle-btn" class="chat-icon-btn" title="Tap to speak">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path>
                            <path d="M19 10v2a7 7 0 0 1-14 0v-2"></path>
                            <line x1="12" y1="19" x2="12" y2="23"></line>
                            <line x1="8" y1="23" x2="16" y2="23"></line>
                        </svg>
                    </button>
                    <input type="text" id="chat-input-field" placeholder="Ask JARVIS... (e.g. 'Laptop screen brightness 50% pettu', 'Chrome open cheyyi')" autocomplete="off" />
                    <button id="send-btn" class="chat-send-btn">
                        <span>Send</span>
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <line x1="22" y1="2" x2="11" y2="13"></line>
                            <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
                        </svg>
                    </button>
                </div>
            </div>

            <style>
                .jarvis-chat-wrapper {
                    display: flex;
                    flex-direction: column;
                    height: calc(100vh - 110px);
                    background: rgba(10, 14, 23, 0.95);
                    border: 1px solid rgba(212, 160, 23, 0.2);
                    border-radius: 12px;
                    overflow: hidden;
                    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
                }
                .chat-status-bar {
                    display: flex;
                    align-items: center;
                    padding: 16px 20px;
                    background: rgba(18, 24, 38, 0.9);
                    border-bottom: 1px solid rgba(212, 160, 23, 0.2);
                    gap: 16px;
                }
                .core-avatar-orb {
                    position: relative;
                    width: 36px;
                    height: 36px;
                    border-radius: 50%;
                    background: radial-gradient(circle, #ffd700 0%, #b8860b 70%);
                    box-shadow: 0 0 15px rgba(255, 215, 0, 0.6);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                .orb-core {
                    width: 14px;
                    height: 14px;
                    border-radius: 50%;
                    background: #fff;
                    box-shadow: 0 0 10px #fff;
                    animation: pulseOrb 2s infinite ease-in-out;
                }
                @keyframes pulseOrb {
                    0% { transform: scale(0.9); opacity: 0.8; }
                    50% { transform: scale(1.1); opacity: 1; box-shadow: 0 0 20px #ffd700; }
                    100% { transform: scale(0.9); opacity: 0.8; }
                }
                .core-info {
                    flex: 1;
                }
                .core-title {
                    font-size: 14px;
                    font-weight: 700;
                    letter-spacing: 1px;
                    color: #ffd700;
                    font-family: monospace;
                }
                .core-subtitle {
                    font-size: 11px;
                    color: rgba(255, 255, 255, 0.6);
                    margin-top: 2px;
                }
                .status-badge.online {
                    background: rgba(46, 204, 113, 0.15);
                    color: #2ecc71;
                    border: 1px solid rgba(46, 204, 113, 0.4);
                    padding: 4px 10px;
                    border-radius: 20px;
                    font-size: 11px;
                    font-weight: 600;
                }
                .chat-messages-container {
                    flex: 1;
                    overflow-y: auto;
                    padding: 20px;
                    display: flex;
                    flex-direction: column;
                    gap: 16px;
                }
                .message {
                    max-width: 80%;
                    padding: 12px 16px;
                    border-radius: 12px;
                    font-size: 14px;
                    line-height: 1.5;
                    word-wrap: break-word;
                }
                .user-msg {
                    align-self: flex-end;
                    background: linear-gradient(135deg, rgba(212, 160, 23, 0.25), rgba(184, 134, 11, 0.15));
                    border: 1px solid rgba(255, 215, 0, 0.3);
                    color: #fff;
                    border-bottom-right-radius: 2px;
                }
                .jarvis-msg {
                    align-self: flex-start;
                    background: rgba(26, 34, 52, 0.85);
                    border: 1px solid rgba(255, 215, 0, 0.15);
                    color: #e0e6ed;
                    border-bottom-left-radius: 2px;
                }
                .msg-header {
                    display: flex;
                    justify-content: space-between;
                    font-size: 11px;
                    color: rgba(255, 215, 0, 0.8);
                    margin-bottom: 6px;
                    font-family: monospace;
                }
                .msg-time {
                    color: rgba(255, 255, 255, 0.4);
                }
                .chat-input-bar {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    padding: 14px 20px;
                    background: rgba(18, 24, 38, 0.95);
                    border-top: 1px solid rgba(212, 160, 23, 0.2);
                }
                #chat-input-field {
                    flex: 1;
                    background: rgba(10, 14, 23, 0.8);
                    border: 1px solid rgba(212, 160, 23, 0.3);
                    border-radius: 8px;
                    padding: 10px 14px;
                    color: #fff;
                    font-size: 14px;
                    outline: none;
                    transition: border 0.2s;
                }
                #chat-input-field:focus {
                    border-color: #ffd700;
                    box-shadow: 0 0 10px rgba(255, 215, 0, 0.2);
                }
                .chat-icon-btn, .chat-send-btn {
                    background: linear-gradient(135deg, #ffd700, #b8860b);
                    border: none;
                    border-radius: 8px;
                    color: #0a0e17;
                    font-weight: 700;
                    cursor: pointer;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    gap: 6px;
                    padding: 10px 16px;
                    transition: transform 0.1s, opacity 0.2s;
                }
                .chat-icon-btn {
                    padding: 10px;
                    border-radius: 50%;
                }
                .chat-icon-btn.recording {
                    background: #e74c3c;
                    color: #fff;
                    animation: pulseRecord 1s infinite alternate;
                }
                @keyframes pulseRecord {
                    0% { transform: scale(1); box-shadow: 0 0 5px #e74c3c; }
                    100% { transform: scale(1.1); box-shadow: 0 0 15px #e74c3c; }
                }
                .chat-send-btn:hover, .chat-icon-btn:hover {
                    opacity: 0.9;
                }
                .chat-send-btn:active, .chat-icon-btn:active {
                    transform: scale(0.96);
                }
            </style>
        `;

        this.bindEvents();
        this.initSSE();
    },

    bindEvents() {
        const sendBtn = document.getElementById("send-btn");
        const inputField = document.getElementById("chat-input-field");
        const micBtn = document.getElementById("mic-toggle-btn");

        if (sendBtn && inputField) {
            sendBtn.addEventListener("click", () => this.sendMessage());
            inputField.addEventListener("keydown", (e) => {
                if (e.key === "Enter") this.sendMessage();
            });
        }

        if (micBtn) {
            micBtn.addEventListener("click", () => this.toggleMic());
        }
    },

    async sendMessage() {
        const inputField = document.getElementById("chat-input-field");
        if (!inputField) return;

        const text = inputField.value.trim();
        if (!text) return;

        inputField.value = "";
        
        // Append user message UI immediately
        this.appendMessage("user", text);

        try {
            const res = await fetch("/api/v1/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ message: text })
            });
            const data = await res.json();
            if (data && data.success && data.data.jarvis_response) {
                // Response will also arrive via SSE stream, but fallback append if needed
                if (!document.querySelector(`[data-text="${CSS.escape(data.data.jarvis_response)}"]`)) {
                    this.appendMessage("jarvis", data.data.jarvis_response);
                }
            }
        } catch (e) {
            console.error("Failed to send chat message:", e);
        }
    },

    appendMessage(role, text) {
        const messagesBox = document.getElementById("chat-messages");
        if (!messagesBox) return;

        const msgDiv = document.createElement("div");
        msgDiv.className = `message ${role === "user" ? "user-msg" : "jarvis-msg"}`;
        msgDiv.setAttribute("data-text", text);
        msgDiv.innerHTML = `
            <div class="msg-header">
                <span class="msg-role">${role === "user" ? "👤 YOU" : "🤖 JARVIS"}</span>
                <span class="msg-time">${new Date().toLocaleTimeString()}</span>
            </div>
            <div class="msg-content">${this.escapeHtml(text)}</div>
        `;

        messagesBox.appendChild(msgDiv);
        messagesBox.scrollTop = messagesBox.scrollHeight;
    },

    toggleMic() {
        const micBtn = document.getElementById("mic-toggle-btn");
        if (!("webkitSpeechRecognition" in window) && !("SpeechRecognition" in window)) {
            alert("Speech recognition is not supported in this browser. Please use Chrome or Edge.");
            return;
        }

        if (isRecording) {
            if (speechRecognition) speechRecognition.stop();
            isRecording = false;
            if (micBtn) micBtn.classList.remove("recording");
            return;
        }

        const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
        speechRecognition = new SpeechRec();
        speechRecognition.continuous = false;
        speechRecognition.interimResults = false;
        speechRecognition.lang = "en-IN"; // Supports English / Tenglish accent

        speechRecognition.onstart = () => {
            isRecording = true;
            if (micBtn) micBtn.classList.add("recording");
        };

        speechRecognition.onresult = (event) => {
            const transcript = event.results[0][0].transcript;
            const inputField = document.getElementById("chat-input-field");
            if (inputField) {
                inputField.value = transcript;
                this.sendMessage();
            }
        };

        speechRecognition.onerror = (e) => {
            console.error("Speech recognition error:", e);
            isRecording = false;
            if (micBtn) micBtn.classList.remove("recording");
        };

        speechRecognition.onend = () => {
            isRecording = false;
            if (micBtn) micBtn.classList.remove("recording");
        };

        speechRecognition.start();
    },

    initSSE() {
        if (sseEventSource) return;

        sseEventSource = new EventSource("/api/v1/events");
        sseEventSource.addEventListener("chat_message", (e) => {
            try {
                const event = JSON.parse(e.data);
                const payload = event.payload;
                if (payload && payload.role && payload.text) {
                    if (payload.role === "jarvis") {
                        this.appendMessage("jarvis", payload.text);
                    }
                }
            } catch (err) {
                console.error("Error parsing chat_message SSE:", err);
            }
        });
    },

    escapeHtml(str) {
        return str
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    },

    destroy() {
        if (sseEventSource) {
            sseEventSource.close();
            sseEventSource = null;
        }
        if (speechRecognition && isRecording) {
            speechRecognition.stop();
            isRecording = false;
        }
    }
};
