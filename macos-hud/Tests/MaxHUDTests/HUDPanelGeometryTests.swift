import XCTest
@testable import MaxHUD

final class HUDPanelGeometryTests: XCTestCase {

    func testCenteringMath() {
        let screenWidth: CGFloat = 1728
        let screenHeight: CGFloat = 1117
        let hudWidth: CGFloat = 260
        let hudHeight: CGFloat = 44
        let topNotchOffset: CGFloat = 36

        let targetX = (screenWidth / 2.0) - (hudWidth / 2.0)
        let targetY = screenHeight - topNotchOffset - hudHeight

        XCTAssertEqual(targetX, 734)
        XCTAssertEqual(targetY, 1037)
    }

    func testAspectRatiosForMorphingStates() {
        let states: [HUDState] = [
            .idle,
            .listening,
            .thinking,
            .responding,
            .completed,
            .error(message: "test")
        ]

        for s in states {
            XCTAssertGreaterThan(s.targetWidth, s.targetHeight, "HUD must maintain landscape capsule ratio for state \(s)")
            XCTAssertGreaterThanOrEqual(s.targetHeight, 34, "Height must be >= 34 for state \(s)")
        }
    }
}
