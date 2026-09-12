import SwiftUI

/// Structured error indicator with user-friendly message
public struct ErrorView: View {
    var message: String

    public var body: some View {
        HStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(Color(red: 1.0, green: 0.3, blue: 0.3).opacity(0.25))
                    .frame(width: 22, height: 22)

                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(Color(red: 1.0, green: 0.4, blue: 0.4))
            }

            Text(message.isEmpty ? "Connection Error" : message)
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundColor(Color.white.opacity(0.95))
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .padding(.horizontal, 14)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("MAX Error: \(message)")
    }
}
