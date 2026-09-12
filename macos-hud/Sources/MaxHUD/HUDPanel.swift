import AppKit
import SwiftUI

/// Specialized floating NSPanel configured for Dynamic Island behavior on macOS.
/// Stays above all normal windows, joins all Spaces, and never steals keyboard focus.
public final class HUDPanel: NSPanel {

    public init(contentRect: NSRect) {
        super.init(
            contentRect: contentRect,
            styleMask: [
                .borderless,
                .nonactivatingPanel,
                .fullSizeContentView
            ],
            backing: .buffered,
            defer: false
        )

        self.isOpaque = false
        self.backgroundColor = .clear
        self.hasShadow = false
        self.level = .statusBar
        self.collectionBehavior = [
            .canJoinAllSpaces,
            .fullScreenAuxiliary,
            .stationary,
            .ignoresCycle
        ]
        self.isMovableByWindowBackground = true
        self.hidesOnDeactivate = false
        self.isReleasedWhenClosed = false
        self.ignoresMouseEvents = false

        // Register for screen and resolution changes
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(screenParametersDidChange),
            name: NSApplication.didChangeScreenParametersNotification,
            object: nil
        )
    }

    @objc private func screenParametersDidChange() {
        repositionOnActiveScreen()
    }

    /// Positions the HUD at the top-center of the primary / active display, respecting notch safe areas.
    public func repositionOnActiveScreen(width: CGFloat? = nil, height: CGFloat? = nil, animated: Bool = true) {
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }

        let currentW = width ?? frame.width
        let currentH = height ?? frame.height

        let screenFrame = screen.visibleFrame
        let fullScreenFrame = screen.frame

        // Calculate notch / top margin offset
        let topPadding: CGFloat = 8
        let notchHeight: CGFloat = fullScreenFrame.height - (screenFrame.origin.y + screenFrame.height)
        let effectiveTop = fullScreenFrame.maxY - max(notchHeight, 28) - topPadding

        let targetX = fullScreenFrame.midX - (currentW / 2.0)
        let targetY = effectiveTop - currentH

        let targetFrame = NSRect(x: targetX, y: targetY, width: currentW, height: currentH)

        if animated {
            NSAnimationContext.runAnimationGroup { context in
                context.duration = 0.35
                context.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
                self.animator().setFrame(targetFrame, display: true)
            }
        } else {
            self.setFrame(targetFrame, display: true)
        }
    }

    public override var canBecomeKey: Bool {
        return false // Never steal keyboard focus
    }

    public override var canBecomeMain: Bool {
        return false // Never become main window to avoid activating app
    }
}
