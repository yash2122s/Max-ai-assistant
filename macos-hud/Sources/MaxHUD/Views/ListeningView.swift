import SwiftUI

/// Active microphone listening view with live reactive waveform
public struct ListeningView: View {
    @ObservedObject var visualizer: AudioVisualizerModel
    @State private var pulse: Bool = false

    public var body: some View {
        HStack(spacing: 10) {
            // Glowing Microphone Icon
            ZStack {
                Circle()
                    .fill(Color(red: 0.0, green: 0.75, blue: 1.0).opacity(pulse ? 0.35 : 0.15))
                    .frame(width: 24, height: 24)
                    .scaleEffect(pulse ? 1.2 : 0.95)

                Image(systemName: "mic.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(Color(red: 0.2, green: 0.85, blue: 1.0))
            }

            Text("Listening")
                .font(.system(size: 13, weight: .medium, design: .rounded))
                .foregroundColor(.white)

            Spacer()

            // Dynamic Waveform
            WaveformView(
                visualizer: visualizer,
                barColor: Color(red: 0.0, green: 0.75, blue: 1.0),
                barCount: 8,
                maxHeight: 18
            )
        }
        .padding(.horizontal, 14)
        .onAppear {
            visualizer.startLiveMotion()
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("MAX Listening to speech")
    }
}
