import XCTest
@testable import MaxHUD

final class AudioVisualizerTests: XCTestCase {

    func testInitialValues() {
        let model = AudioVisualizerModel()
        XCTAssertEqual(model.amplitude, 0.0)
        XCTAssertEqual(model.bands.count, 12)
        XCTAssertFalse(model.isActive)
    }

    func testAmplitudeClamping() {
        let model = AudioVisualizerModel()
        let expectation = self.expectation(description: "AmplitudeUpdated")

        model.updateAmplitude(0.85)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            XCTAssertEqual(model.amplitude, 0.85, accuracy: 0.001)
            XCTAssertGreaterThan(model.bands[6], 0.1)
            expectation.fulfill()
        }

        waitForExpectations(timeout: 1.0)
    }

    func testDirectBandUpdates() {
        let model = AudioVisualizerModel()
        let expectation = self.expectation(description: "BandsUpdated")

        let testBands: [Float] = [0.2, 0.4, 0.6, 0.8, 1.0, 0.8, 0.6, 0.4, 0.2, 0.1, 0.1, 0.1]
        model.updateBands(testBands)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            XCTAssertEqual(model.bands[4], 1.0, accuracy: 0.001)
            expectation.fulfill()
        }

        waitForExpectations(timeout: 1.0)
    }

    func testMotionLifecycle() {
        let model = AudioVisualizerModel()
        model.startLiveMotion()
        XCTAssertTrue(model.isActive)

        model.stopMotion()
        XCTAssertFalse(model.isActive)
    }
}
