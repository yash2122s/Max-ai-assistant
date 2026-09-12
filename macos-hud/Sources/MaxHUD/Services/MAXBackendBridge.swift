import Foundation
import Combine

/// Protocol for objects that receive state updates from the MAX backend.
public protocol MAXBackendBridgeDelegate: AnyObject {
    func bridgeDidReceiveState(_ state: HUDState)
    func bridgeDidReceiveAudioAmplitude(_ amplitude: Float)
    func bridgeConnectionStateChanged(isConnected: Bool)
}

/// Robust WebSocket / SSE bridge connecting the macOS HUD to the MAX assistant core.
/// Adapts canonical task events into macOS HUD visual states.
public final class MAXBackendBridge: NSObject, URLSessionWebSocketDelegate {
    public weak var delegate: MAXBackendBridgeDelegate?

    private var webSocketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var isConnected: Bool = false
    private var reconnectAttempts: Int = 0
    private var serverURL: URL
    private var isRunning: Bool = false

    public init(serverURL: URL = URL(string: "ws://localhost:8765/ws")!) {
        self.serverURL = serverURL
        super.init()
    }

    public func connect() {
        guard !isRunning else { return }
        isRunning = true
        initiateConnection()
    }

    public func disconnect() {
        isRunning = false
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        isConnected = false
        delegate?.bridgeConnectionStateChanged(isConnected: false)
    }

    private func initiateConnection() {
        guard isRunning else { return }
        urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
        webSocketTask = urlSession?.webSocketTask(with: serverURL)
        webSocketTask?.resume()
        receiveNextMessage()
    }

    private func receiveNextMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self, self.isRunning else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.parseIncomingPayload(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.parseIncomingPayload(text)
                    }
                @unknown default:
                    break
                }
                self.receiveNextMessage()

            case .failure(let error):
                print("[MAXBackendBridge] WebSocket error: \(error.localizedDescription)")
                self.handleDisconnection()
            }
        }
    }

    /// Parses structured MAX JSON packets and maps them to HUDState
    public func parseIncomingPayload(_ jsonString: String) {
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }

        let type = json["type"] as? String ?? json["event"] as? String ?? json["event_type"] as? String ?? ""

        switch type.lowercased() {
        case "state_change", "status":
            let rawState = json["state"] as? String ?? json["status"] as? String ?? "idle"
            let hudState = mapRawStateToHUD(rawState, details: json)
            delegate?.bridgeDidReceiveState(hudState)

        case "listening", "user_speech_start":
            delegate?.bridgeDidReceiveState(.listening)

        case "thinking", "reasoning", "task_created":
            delegate?.bridgeDidReceiveState(.thinking)

        case "task_started", "executing", "task_progress":
            let worker = json["worker"] as? String ?? json["source"] as? String ?? "Agent"
            delegate?.bridgeDidReceiveState(.executing(worker: worker))

        case "task_verification_started", "verifying":
            delegate?.bridgeDidReceiveState(.verifying)

        case "task_waiting_confirmation", "waiting_confirmation":
            let prompt = json["message"] as? String ?? "Confirmation Required"
            delegate?.bridgeDidReceiveState(.waitingConfirmation(prompt: prompt))

        case "task_waiting_clarification", "waiting_clarification":
            delegate?.bridgeDidReceiveState(.waitingClarification)

        case "responding", "speaking", "tts_start":
            delegate?.bridgeDidReceiveState(.responding)

        case "turn_complete", "task_completed", "completed", "tts_end":
            delegate?.bridgeDidReceiveState(.completed)

        case "task_cancelled", "cancelled":
            delegate?.bridgeDidReceiveState(.cancelled)

        case "audio_amplitude", "audio_level", "amplitude":
            if let amp = (json["amplitude"] as? NSNumber)?.floatValue ?? (json["level"] as? NSNumber)?.floatValue {
                delegate?.bridgeDidReceiveAudioAmplitude(amp)
            }

        case "error", "task_failed", "task_verification_failed":
            let msg = json["message"] as? String ?? json["error"] as? String ?? "Execution Error"
            delegate?.bridgeDidReceiveState(.error(message: msg))

        default:
            break
        }
    }

    private func mapRawStateToHUD(_ raw: String, details: [String: Any]) -> HUDState {
        switch raw.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) {
        case "listening", "mic_active", "user_speaking":
            return .listening
        case "thinking", "planning", "processing", "reasoning", "queued":
            return .thinking
        case "running", "executing", "task_started":
            let worker = details["worker"] as? String ?? details["source"] as? String ?? "Worker"
            return .executing(worker: worker)
        case "verifying", "task_verification_started":
            return .verifying
        case "waiting_confirmation", "task_waiting_confirmation":
            let prompt = details["message"] as? String ?? "Confirmation Required"
            return .waitingConfirmation(prompt: prompt)
        case "waiting_clarification", "task_waiting_clarification":
            return .waitingClarification
        case "responding", "speaking", "tts_active":
            return .responding
        case "completed", "done", "turn_complete", "task_completed":
            return .completed
        case "cancelled", "task_cancelled":
            return .cancelled
        case "error", "failed", "verification_failed", "permission_denied":
            let msg = details["message"] as? String ?? "Internal Error"
            return .error(message: msg)
        default:
            return .idle
        }
    }

    private func handleDisconnection() {
        isConnected = false
        delegate?.bridgeConnectionStateChanged(isConnected: false)
        guard isRunning else { return }

        // Exponential backoff reconnect
        reconnectAttempts += 1
        let delay = min(10.0, Double(reconnectAttempts) * 1.5)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.initiateConnection()
        }
    }

    // MARK: - URLSessionWebSocketDelegate
    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        isConnected = true
        reconnectAttempts = 0
        delegate?.bridgeConnectionStateChanged(isConnected: true)
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        handleDisconnection()
    }
}
