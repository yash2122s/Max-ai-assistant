import SwiftUI

/// Reasoning state with animated pulsing orb and orbiting particles
public struct ThinkingView: View {
    @State private var rotationAngle: Double = 0.0
    @State private var corePulse: Bool = false

    public var body: some View {
        HStack(spacing: 12) {
            // Orbital Thinking Core
            ZStack {
                // Central Glowing Orb
                Circle()
                    .fill(
                        RadialGradient(
                            gradient: Gradient(colors: [
                                Color(red: 0.75, green: 0.45, blue: 1.0),
                                Color(red: 0.45, green: 0.2, blue: 0.8).opacity(0.4)
                            ]),
                            center: .center,
                            startRadius: 2,
                            endRadius: 10
                        )
                    )
                    .frame(width: 14, height: 14)
                    .scaleEffect(corePulse ? 1.25 : 0.85)

                // Orbiting Ring with Satellite Particles
                Circle()
                    .stroke(
                        AngularGradient(
                            gradient: Gradient(colors: [
                                Color(red: 0.8, green: 0.5, blue: 1.0),
                                Color.clear,
                                Color(red: 0.5, green: 0.8, blue: 1.0),
                                Color.clear
                            ]),
                            center: .center
                        ),
                        lineWidth: 1.5
                    )
                    .frame(width: 24, height: 24)
                    .rotationEffect(.degrees(rotationAngle))

                // Orbital Particle
                Circle()
                    .fill(Color.white)
                    .frame(width: 3.5, height: 3.5)
                    .offset(x: 12)
                    .rotationEffect(.degrees(rotationAngle))
            }

            VStack(alignment: .leading, spacing: 1) {
                Text("Thinking")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)

                Text("Reasoning plan...")
                    .font(.system(size: 10, weight: .regular, design: .rounded))
                    .foregroundColor(Color.white.opacity(0.65))
            }

            Spacer()
        }
        .padding(.horizontal, 14)
        .onAppear {
            withAnimation(.linear(duration: 2.0).repeatForever(autoreverses: false)) {
                rotationAngle = 360
            }
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                corePulse = true
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("MAX Thinking and reasoning")
    }
}
