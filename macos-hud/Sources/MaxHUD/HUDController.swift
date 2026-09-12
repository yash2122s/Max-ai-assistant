import SwiftUI
import AppKit
import Combine

/// Central controller coordinating the HUD state machine, panel frame animations, audio visualizer, and backend bridge.
public final class HUDController: ObservableObject, MAXBackendBridgeDelegate {
    @Published public private(set) var state: HUDState = .idle
    @Published public private(set) var isConnected: Bool = false

    public let visualizer: AudioVisualizerModel
    public let backendBridge: MAXBackendBridge
    public weak var panel: HUDPanel?

    private var autoDismissTimer: AnyCancellable?

    public init(
        visualizer: AudioVisualizerModel = AudioVisualizerModel(),
        backendBridge: MAXBackendBridge = MAXBackendBridge()
    ) {
        self.visualizer = visualizer
        self.backendBridge = backendBridge
        self.backendBridge.delegate = self
    }

    public func start() {
        backendBridge.connect()
    }

    public func stop() {
        backendBridge.disconnect()
        visualizer.stopMotion()
        autoDismissTimer?.cancel()
    }

    /// Authoritative state transition method
    public func transition(to newState: HUDState) {
        DispatchQueue.main.async {
            guard self.state != newState else { return }
            self.autoDismissTimer?.cancel()
            self.state = newState

            // Sync visualizer motion
            switch newState {
            case .listening, .responding:
                self.visualizer.startLiveMotion()
            case .idle, .completed, .error:
                self.visualizer.stopMotion()
            case .thinking:
                self.visualizer.stopMotion()
            }

            // Animate panel frame to match state target dimensions
            self.panel?.repositionOnActiveScreen(
                width: newState.targetWidth,
                height: newState.targetHeight,
                animated: true
            )

            // Setup auto-dismiss timeout if applicable
            if let interval = newState.autoDismissInterval {
                self.autoDismissTimer = Just(())
                    .delay(for: .seconds(interval), scheduler: DispatchQueue.main)
                    .sink { [weak self] _ in
                        self?.transition(to: .idle)
                    }
            }
        }
    }

    // MARK: - MAXBackendBridgeDelegate
    public func bridgeDidReceiveState(_ newState: HUDState) {
        transition(to: newState)
    }

    public func bridgeDidReceiveAudioAmplitude(_ amplitude: Float) {
        visualizer.updateAmplitude(amplitude)
    }

    public func bridgeConnectionStateChanged(isConnected: Bool) {
        DispatchQueue.main.async {
            self.isConnected = isConnected
            if !isConnected && self.state != .idle {
                // If disconnected while waiting, show brief error then collapse to idle
                self.transition(to: .error(message: "Offline"))
            }
        }
    }
}
