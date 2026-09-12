import SwiftUI

/// Authoritative single source of truth for the MAX macOS HUD state machine.
/// Implements full presentation support for canonical task lifecycle events.
public enum HUDState: Equatable, Hashable {
    case idle
    case listening
    case thinking
    case executing(worker: String = "Worker")
    case verifying
    case waitingConfirmation(prompt: String = "Confirmation Required")
    case waitingClarification
    case responding
    case completed
    case error(message: String)
    case cancelled

    /// Dynamic target width for the morphing HUD window
    public var targetWidth: CGFloat {
        switch self {
        case .idle:
            return 130
        case .listening:
            return 260
        case .thinking:
            return 220
        case .executing:
            return 270
        case .verifying:
            return 240
        case .waitingConfirmation:
            return 310
        case .waitingClarification:
            return 260
        case .responding:
            return 280
        case .completed:
            return 190
        case .error:
            return 280
        case .cancelled:
            return 200
        }
    }

    /// Dynamic target height for the morphing HUD window
    public var targetHeight: CGFloat {
        switch self {
        case .idle:
            return 34
        case .listening:
            return 44
        case .thinking:
            return 48
        case .executing:
            return 48
        case .verifying:
            return 44
        case .waitingConfirmation:
            return 52
        case .waitingClarification:
            return 46
        case .responding:
            return 46
        case .completed:
            return 38
        case .error:
            return 42
        case .cancelled:
            return 38
        }
    }

    /// Primary accent tint for the glowing glass outline
    public var accentColor: Color {
        switch self {
        case .idle:
            return Color(red: 0.4, green: 0.4, blue: 0.5).opacity(0.4)
        case .listening:
            return Color(red: 0.0, green: 0.75, blue: 1.0)
        case .thinking:
            return Color(red: 0.65, green: 0.4, blue: 1.0)
        case .executing:
            return Color(red: 0.2, green: 0.6, blue: 1.0)
        case .verifying:
            return Color(red: 0.9, green: 0.7, blue: 0.1)
        case .waitingConfirmation:
            return Color(red: 1.0, green: 0.55, blue: 0.0)
        case .waitingClarification:
            return Color(red: 0.8, green: 0.5, blue: 0.9)
        case .responding:
            return Color(red: 0.0, green: 0.9, blue: 0.6)
        case .completed:
            return Color(red: 0.2, green: 0.85, blue: 0.4)
        case .error:
            return Color(red: 1.0, green: 0.3, blue: 0.3)
        case .cancelled:
            return Color(red: 0.6, green: 0.6, blue: 0.6)
        }
    }

    /// State title shown in accessibility / UI descriptors
    public var stateTitle: String {
        switch self {
        case .idle:
            return "MAX Idle"
        case .listening:
            return "Listening..."
        case .thinking:
            return "Thinking..."
        case .executing(let worker):
            return "Executing (\(worker))..."
        case .verifying:
            return "Verifying..."
        case .waitingConfirmation(let prompt):
            return prompt
        case .waitingClarification:
            return "Need Clarification"
        case .responding:
            return "MAX Speaking"
        case .completed:
            return "Done"
        case .error(let msg):
            return "Error: \(msg)"
        case .cancelled:
            return "Cancelled"
        }
    }

    /// Accessibility description for VoiceOver compliance
    public var accessibilityDescription: String {
        switch self {
        case .idle:
            return "MAX assistant is idle and ready."
        case .listening:
            return "MAX is actively listening to microphone input."
        case .thinking:
            return "MAX is processing request and reasoning."
        case .executing(let worker):
            return "MAX is executing task via \(worker)."
        case .verifying:
            return "MAX is verifying task outcome."
        case .waitingConfirmation(let prompt):
            return "MAX is waiting for confirmation: \(prompt)"
        case .waitingClarification:
            return "MAX requires user clarification."
        case .responding:
            return "MAX is speaking audio response."
        case .completed:
            return "MAX has completed response."
        case .error(let msg):
            return "MAX encountered an error: \(msg)"
        case .cancelled:
            return "MAX task was cancelled."
        }
    }

    /// Auto-transition timeout interval (if applicable) before returning to .idle
    public var autoDismissInterval: TimeInterval? {
        switch self {
        case .completed:
            return 1.4
        case .cancelled:
            return 1.6
        case .error:
            return 3.0
        default:
            return nil
        }
    }
}
