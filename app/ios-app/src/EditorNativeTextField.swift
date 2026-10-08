import SwiftUI
import UIKit

/// Keeps first-responder and keyboard accessory changes inside the text field.
/// Title drafts still update immediately and publish to the model after 120 ms.
struct EditorNativeTextField: UIViewRepresentable {
    let label: String
    let value: String
    var prompt: String? = nil
    var draft: EditorTextDraft? = nil
    var keyboard: UIKeyboardType = .default
    var trailing = false
    let onChange: (String) -> Void
    @Environment(\.isEnabled) private var enabled
    @Environment(\.layoutDirection) private var direction

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UITextField {
        let field = Field()
        field.text = value
        field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed), for: .editingChanged)
        field.returnKeyType = .done
        field.adjustsFontForContentSizeCategory = true
        draft?.value = value
        return field
    }
    func updateUIView(_ field: UITextField, context: Context) {
        context.coordinator.draft = draft
        context.coordinator.onChange = onChange
        field.placeholder = prompt ?? label
        field.accessibilityLabel = label
        field.keyboardType = keyboard
        field.textAlignment = trailing ? (direction == .rightToLeft ? .left : .right) : .natural
        field.font = .preferredFont(forTextStyle: .body, compatibleWith: field.traitCollection)
        field.isEnabled = enabled
        if draft == nil && !field.isFirstResponder && field.text != value { field.text = value }
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextField, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? uiView.intrinsicContentSize.width, height: max(30, uiView.intrinsicContentSize.height))
    }
    static func dismantleUIView(_ field: UITextField, coordinator: Coordinator) {
        coordinator.pending?.cancel()
        coordinator.onChange = nil
        field.delegate = nil
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var draft: EditorTextDraft?
        var onChange: ((String) -> Void)?
        var pending: DispatchWorkItem?
        @objc func changed(_ field: UITextField) {
            let value = field.text ?? ""
            guard let draft else { onChange?(value); return }
            draft.value = value
            pending?.cancel()
            let update = DispatchWorkItem { [weak self] in self?.onChange?(value) }
            pending = update
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: update)
        }
        func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
        deinit { pending?.cancel() }
    }

    private final class Field: UITextField {
        override func becomeFirstResponder() -> Bool {
            if inputAccessoryView == nil {
                let toolbar = UIToolbar(frame: CGRect(x: 0, y: 0, width: 320, height: 44))
                toolbar.autoresizingMask = .flexibleWidth
                toolbar.items = [UIBarButtonItem(systemItem: .flexibleSpace),
                                 UIBarButtonItem(title: "Done", style: .done, target: self, action: #selector(done))]
                inputAccessoryView = toolbar
            }
            return super.becomeFirstResponder()
        }
        @objc private func done() { resignFirstResponder() }
    }
}
