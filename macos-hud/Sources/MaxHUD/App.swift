import SwiftUI
import AppKit

public final class AppDelegate: NSObject, NSApplicationDelegate {
    private var hudPanel: HUDPanel?
    private var hudController: HUDController?

    public func applicationDidFinishLaunching(_ notification: Notification) {
        // Prevent application from showing in Dock if configured as pure accessory
        NSApp.setActivationPolicy(.accessory)

        let visualizer = AudioVisualizerModel()
        let bridge = MAXBackendBridge()
        let controller = HUDController(visualizer: visualizer, backendBridge: bridge)
        self.hudController = controller

        // Initialize HUD Panel
        let initialWidth = HUDState.idle.targetWidth
        let initialHeight = HUDState.idle.targetHeight
        let initialRect = NSRect(x: 0, y: 0, width: initialWidth, height: initialHeight)

        let panel = HUDPanel(contentRect: initialRect)
        let rootView = MaxHUDView(controller: controller, visualizer: visualizer)
        panel.contentView = NSHostingView(rootView: rootView)

        controller.panel = panel
        self.hudPanel = panel

        // Position at top center
        panel.repositionOnActiveScreen(width: initialWidth, height: initialHeight, animated: false)
        panel.orderFrontRegardless()

        // Start backend bridge
        controller.start()
    }

    public func applicationWillTerminate(_ notification: Notification) {
        hudController?.stop()
    }
}

@main
public struct MaxHUDApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    public init() {}

    public var body: some Scene {
        Settings {
            EmptyView()
        }
    }
}
