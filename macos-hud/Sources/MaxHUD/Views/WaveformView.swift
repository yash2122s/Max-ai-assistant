import SwiftUI

/// Responsive multi-bar dynamic waveform visualizer
public struct WaveformView: View {
    @ObservedObject var visualizer: AudioVisualizerModel
    var barColor: Color
    var barCount: Int = 10
    var maxHeight: CGFloat = 22

    public var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<min(barCount, visualizer.bands.count), id: \.self) { idx in
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(
                        LinearGradient(
                            gradient: Gradient(colors: [barColor.opacity(0.7), barColor]),
                            startPoint: .bottom,
                            endPoint: .top
                        )
                    )
                    .frame(width: 3, height: max(4, visualizer.bands[idx] * maxHeight))
                    .animation(.spring(response: 0.15, dampingFraction: 0.7), value: visualizer.bands[idx])
            }
        }
        .frame(height: maxHeight)
    }
}
