import XCTest
@testable import MaxHUD

final class MockBridgeDelegate: MAXBackendBridgeDelegate {
    var receivedStates: [HUDState] = []
    var receivedAmplitudes: [Float] = []
    var isConnected: Bool = false

    func bridgeDidReceiveState(_ state: HUDState) {
        receivedStates.append(state)
    }

    func bridgeDidReceiveAudioAmplitude(_ amplitude: Float) {
        receivedAmplitudes.append(amplitude)
    }

    func bridgeConnectionStateChanged(isConnected: Bool) {
        self.isConnected = isConnected
    }
}

final class BackendBridgeTests: XCTestCase {

    func testPayloadParsing() {
        let bridge = MAXBackendBridge()
        let delegate = MockBridgeDelegate()
        bridge.delegate = delegate

        // 1. Listening event
        bridge.parseIncomingPayload("{\"type\": \"listening\"}")
        XCTAssertEqual(delegate.receivedStates.last, .listening)

        // 2. Thinking event
        bridge.parseIncomingPayload("{\"type\": \"thinking\"}")
        XCTAssertEqual(delegate.receivedStates.last, .thinking)

        // 3. Responding event
        bridge.parseIncomingPayload("{\"type\": \"responding\"}")
        XCTAssertEqual(delegate.receivedStates.last, .responding)

        // 4. Completed event
        bridge.parseIncomingPayload("{\"type\": \"completed\"}")
        XCTAssertEqual(delegate.receivedStates.last, .completed)

        // 5. Audio amplitude
        bridge.parseIncomingPayload("{\"type\": \"audio_amplitude\", \"amplitude\": 0.72}")
        XCTAssertEqual(delegate.receivedAmplitudes.last ?? 0, 0.72, accuracy: 0.001)

        // 6. Error event
        bridge.parseIncomingPayload("{\"type\": \"error\", \"message\": \"Microphone disconnected\"}")
        XCTAssertEqual(delegate.receivedStates.last, .error(message: "Microphone disconnected"))
    }
}
