import SwiftUI

/// Success confirmation pulse before smooth collapse back to IDLE
public struct CompletedView: View {
    @State private var scaleCheck: CGFloat = 0.5
    @State private var opacityCheck: Double = 0.0

    public var body: some View {
        HStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(Color(red: 0.2, green: 0.85, blue: 0.4).opacity(0.25))
                    .frame(width: 22, height: 22)

                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(Color(red: 0.3, green: 0.95, blue: 0.5))
            }
            .scaleEffect(scaleCheck)
            .opacity(opacityCheck)

            Text("Done")
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .foregroundColor(.white)
        }
        .padding(.horizontal, 14)
        .onAppear {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) {
                scaleCheck = 1.0
                opacityCheck = 1.0
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("MAX Completed response")
    }
}
