import SwiftUI
import Combine

/// Lightweight, efficient audio visualizer model providing reactive waveform metrics.
/// Automatically pauses/throttles when the HUD is in IDLE to guarantee 0% CPU consumption.
public final class AudioVisualizerModel: ObservableObject {
    @Published public private(set) var amplitude: CGFloat = 0.0
    @Published public private(set) var bands: [CGFloat] = Array(repeating: 0.15, count: 12)
    @Published public private(set) var isActive: Bool = false

    private var animationTimer: AnyCancellable?
    private var phase: Double = 0.0

    public init() {}

    /// Updates live microphone or speaker amplitude (normalized 0.0 to 1.0)
    public func updateAmplitude(_ rawAmp: Float) {
        let clamped = CGFloat(max(0.0, min(1.0, rawAmp)))
        DispatchQueue.main.async {
            self.amplitude = clamped
            // Calculate multi-frequency band heights from amplitude
            for i in 0..<self.bands.count {
                let spread = sin(Double(i) / Double(self.bands.count) * .pi)
                let variance = sin(self.phase + Double(i) * 0.5) * 0.2
                let bandVal = max(0.12, min(1.0, (clamped * CGFloat(spread + variance))))
                self.bands[i] = bandVal
            }
        }
    }

    /// Explicitly updates frequency spectrum bands if FFT data is available
    public func updateBands(_ rawBands: [Float]) {
        guard !rawBands.isEmpty else { return }
        DispatchQueue.main.async {
            let count = min(self.bands.count, rawBands.count)
            for i in 0..<count {
                self.bands[i] = CGFloat(max(0.1, min(1.0, rawBands[i])))
            }
        }
    }

    /// Starts subtle procedural waveform motion for active listening/responding when raw audio stream is smooth
    public func startLiveMotion() {
        guard !isActive else { return }
        isActive = true
        animationTimer = Timer.publish(every: 1.0 / 30.0, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                guard let self = self, self.isActive else { return }
                self.phase += 0.25
                // Synthesize organic natural breathing movement when amplitude is steady
                if self.amplitude < 0.15 {
                    for i in 0..<self.bands.count {
                        let wave = 0.2 + 0.15 * sin(self.phase + Double(i) * 0.6)
                        self.bands[i] = CGFloat(max(0.1, wave))
                    }
                }
            }
    }

    /// Stops animation timers and resets bands to flat resting heights (zero CPU load in IDLE)
    public func stopMotion() {
        isActive = false
        animationTimer?.cancel()
        animationTimer = nil
        DispatchQueue.main.async {
            self.amplitude = 0.0
            self.bands = Array(repeating: 0.12, count: 12)
        }
    }
}
