import SwiftUI
import KotlinModules

/// Entity choices can be long. Build their native menu only when it is opened,
/// instead of making a SwiftUI view graph for every option during navigation.
struct EditorChoicePicker: View {
    let label: String
    @Binding var selection: String?
    let choices: [Choice]
    let noneLabel: String?

    init(_ label: String, selection: Binding<String?>, choices: [Choice], noneLabel: String? = nil) {
        self.label = label
        _selection = selection
        self.choices = choices
        self.noneLabel = noneLabel
    }

    var body: some View {
        LabeledContent(label) {
            HStack(spacing: 6) {
                Text(choices.first { $0.id == selection }?.label ?? noneLabel ?? "")
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.caption2)
            }
            .foregroundStyle(.secondary)
        }
        .accessibilityHidden(true)
        .overlay {
            // The whole row opens the menu, including the label. The native control
            // exposes the label and selected value as a single accessibility element.
            DeferredChoiceMenu(label: label, selection: $selection, choices: choices, noneLabel: noneLabel)
        }
    }
}

private struct DeferredChoiceMenu: UIViewRepresentable {
    let label: String
    @Binding var selection: String?
    let choices: [Choice]
    let noneLabel: String?
    @Environment(\.isEnabled) private var isEnabled

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .custom)
        button.showsMenuAsPrimaryAction = true
        button.isAccessibilityElement = true
        button.menu = UIMenu(options: .singleSelection, children: [
            UIDeferredMenuElement.uncached { [weak coordinator = context.coordinator] completion in
                completion(coordinator?.actions() ?? [])
            }
        ])
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.picker = self
        let selected = choices.first { $0.id == selection }?.label ?? noneLabel ?? ""
        button.isEnabled = isEnabled
        button.accessibilityLabel = label
        button.accessibilityValue = selected
    }

    @MainActor
    final class Coordinator {
        var picker: DeferredChoiceMenu

        init(_ picker: DeferredChoiceMenu) { self.picker = picker }

        func actions() -> [UIMenuElement] {
            var items: [UIMenuElement] = []
            if let noneLabel = picker.noneLabel { items.append(action(id: nil, title: noneLabel)) }
            items += picker.choices.map { action(id: $0.id, title: $0.label) }
            return items
        }

        private func action(id: String?, title: String) -> UIAction {
            UIAction(title: title, state: picker.selection == id ? .on : .off) { [weak self] _ in
                self?.picker.selection = id
            }
        }
    }
}
