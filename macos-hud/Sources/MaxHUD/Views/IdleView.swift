import SwiftUI

/// Compact resting pill state for MAX Dynamic Island
public struct IdleView: View {
    @State private var breathing: Bool = false

    public var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(
                    RadialGradient(
                        gradient: Gradient(colors: [
                            Color(red: 0.2, green: 0.6, blue: 1.0).opacity(breathing ? 0.9 : 0.5),
                            Color(red: 0.1, green: 0.3, blue: 0.8).opacity(0.2)
                        ]),
                        center: .center,
                        startRadius: 1,
                        endRadius: 8
                    )
                )
                .frame(width: 8, height: 8)
                .scaleEffect(breathing ? 1.15 : 0.85)

            Text("MAX")
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundColor(Color.white.opacity(0.85))
                .tracking(1.2)
        }
        .padding(.horizontal, 12)
        .onAppear {
            withAnimation(.easeInOut(duration: 2.5).repeatForever(autoreverses: true)) {
                breathing = true
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("MAX Assistant Idle")
    }
}
