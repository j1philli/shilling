import SwiftUI

/// A transient confirmation (the Compose app's snackbar), optionally with an action such as Undo.
struct Toast: Identifiable {
    let id = UUID()
    let message: String
    var actionLabel: String?
    var action: (() -> Void)?

    init(_ message: String, actionLabel: String? = nil, action: (() -> Void)? = nil) {
        self.message = message
        self.actionLabel = actionLabel
        self.action = action
    }
}

/// Shows [message] at the bottom for a few seconds (longer when it has an action).
struct ToastView: View {
    @Binding var toast: Toast?

    var body: some View {
        if let toast {
            HStack(spacing: 16) {
                Text(toast.message)
                    .font(.subheadline)
                    .lineLimit(2)
                if let label = toast.actionLabel, let action = toast.action {
                    Button(label) {
                        action()
                        withAnimation { self.toast = nil }
                    }
                    .font(.subheadline.weight(.semibold))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())
            .padding(.bottom, 24)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: toast.id) {
                try? await Task.sleep(for: .seconds(toast.action == nil ? 2.5 : 10))
                withAnimation { self.toast = nil }
            }
        }
    }
}
