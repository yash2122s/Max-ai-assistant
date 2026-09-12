import SwiftUI

/// Root Dynamic Island / MAX HUD view with morphing glassmorphism, responsive spring transitions, and accent glow.
public struct MaxHUDView: View {
    @ObservedObject var controller: HUDController
    @ObservedObject var visualizer: AudioVisualizerModel

    public init(controller: HUDController, visualizer: AudioVisualizerModel) {
        self.controller = controller
        self.visualizer = visualizer
    }

    public var body: some View {
        ZStack {
            // Background Ultra-Thin Frosted Glass Capsule
            Capsule()
                .fill(Material.ultraThinMaterial)
                .background(
                    Capsule()
                        .fill(Color.black.opacity(0.78))
                )
                .overlay(
                    Capsule()
                        .stroke(
                            LinearGradient(
                                gradient: Gradient(colors: [
                                    controller.state.accentColor.opacity(0.8),
                                    controller.state.accentColor.opacity(0.2)
                                ]),
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 1.2
                        )
                )
                .shadow(color: controller.state.accentColor.opacity(0.25), radius: 10, x: 0, y: 3)
                .shadow(color: Color.black.opacity(0.4), radius: 6, x: 0, y: 4)

            // Dynamic State Content
            Group {
                switch controller.state {
                case .idle:
                    IdleView()
                        .transition(.asymmetric(insertion: .scale(scale: 0.9).combined(with: .opacity), removal: .opacity))
                case .listening:
                    ListeningView(visualizer: visualizer)
                        .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .thinking:
                    ThinkingView()
                        .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .executing(let worker):
                    HStack(spacing: 8) {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: Color(red: 0.2, green: 0.6, blue: 1.0)))
                            .scaleEffect(0.7)
                        Text("Executing (\(worker))...")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .verifying:
                    HStack(spacing: 8) {
                        Image(systemName: "shield.lefthalf.filled")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(Color(red: 0.9, green: 0.7, blue: 0.1))
                        Text("Verifying...")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .waitingConfirmation(let prompt):
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(Color(red: 1.0, green: 0.55, blue: 0.0))
                        Text(prompt)
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 14)
                    .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .waitingClarification:
                    HStack(spacing: 8) {
                        Image(systemName: "questionmark.circle.fill")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(Color(red: 0.8, green: 0.5, blue: 0.9))
                        Text("Clarification needed")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .responding:
                    RespondingView(visualizer: visualizer)
                        .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .completed:
                    CompletedView()
                        .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .cancelled:
                    HStack(spacing: 8) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(Color(red: 0.7, green: 0.7, blue: 0.7))
                        Text("Cancelled")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                case .error(let msg):
                    ErrorView(message: msg)
                        .transition(.asymmetric(insertion: .scale(scale: 0.95).combined(with: .opacity), removal: .opacity))
                }
            }
        }
        .frame(width: controller.state.targetWidth, height: controller.state.targetHeight)
        .animation(.spring(response: 0.38, dampingFraction: 0.78, blendDuration: 0.2), value: controller.state)
        .onTapGesture {
            if controller.state == .idle {
                controller.transition(to: .listening)
            }
        }
    }
}
