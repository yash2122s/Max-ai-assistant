import XCTest
@testable import MaxHUD

final class HUDStateTests: XCTestCase {

    func testTargetDimensions() {
        XCTAssertEqual(HUDState.idle.targetWidth, 130)
        XCTAssertEqual(HUDState.idle.targetHeight, 34)

        XCTAssertEqual(HUDState.listening.targetWidth, 260)
        XCTAssertEqual(HUDState.listening.targetHeight, 44)

        XCTAssertEqual(HUDState.thinking.targetWidth, 220)
        XCTAssertEqual(HUDState.thinking.targetHeight, 48)

        XCTAssertEqual(HUDState.responding.targetWidth, 280)
        XCTAssertEqual(HUDState.responding.targetHeight, 46)

        XCTAssertEqual(HUDState.completed.targetWidth, 190)
        XCTAssertEqual(HUDState.completed.targetHeight, 38)

        XCTAssertEqual(HUDState.error(message: "test").targetWidth, 280)
        XCTAssertEqual(HUDState.error(message: "test").targetHeight, 42)
    }

    func testAutoDismissIntervals() {
        XCTAssertNil(HUDState.idle.autoDismissInterval)
        XCTAssertNil(HUDState.listening.autoDismissInterval)
        XCTAssertNil(HUDState.thinking.autoDismissInterval)
        XCTAssertNil(HUDState.responding.autoDismissInterval)

        XCTAssertEqual(HUDState.completed.autoDismissInterval, 1.4)
        XCTAssertEqual(HUDState.error(message: "fail").autoDismissInterval, 3.0)
    }

    func testAccessibilityDescriptions() {
        XCTAssertTrue(HUDState.idle.accessibilityDescription.contains("idle"))
        XCTAssertTrue(HUDState.listening.accessibilityDescription.contains("listening"))
        XCTAssertTrue(HUDState.thinking.accessibilityDescription.contains("reasoning"))
        XCTAssertTrue(HUDState.responding.accessibilityDescription.contains("speaking"))
        XCTAssertTrue(HUDState.completed.accessibilityDescription.contains("completed"))
        XCTAssertTrue(HUDState.error(message: "timeout").accessibilityDescription.contains("timeout"))
    }
}
