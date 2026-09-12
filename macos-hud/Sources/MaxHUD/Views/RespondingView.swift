import SwiftUI

/// Audio response playback view reacting to assistant speech amplitude
public struct RespondingView: View {
    @ObservedObject var visualizer: AudioVisualizerModel
    @State private var glow: Bool = false

    public var body: some View {
        HStack(spacing: 12) {
            // Speaker / Sound Wave Icon
            ZStack {
                Circle()
                    .fill(Color(red: 0.0, green: 0.9, blue: 0.6).opacity(glow ? 0.35 : 0.15))
                    .frame(width: 24, height: 24)

                Image(systemName: "waveform.badge.magnifyingglass")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(Color(red: 0.2, green: 0.95, blue: 0.7))
            }

            Text("MAX")
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundColor(.white)

            Spacer()

            // Dynamic Response Waveform
            WaveformView(
                visualizer: visualizer,
                barColor: Color(red: 0.0, green: 0.9, blue: 0.6),
                barCount: 12,
                maxHeight: 20
            )
        }
        .padding(.horizontal, 14)
        .onAppear {
            visualizer.startLiveMotion()
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                glow = true
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("MAX Speaking response")
    }
}
