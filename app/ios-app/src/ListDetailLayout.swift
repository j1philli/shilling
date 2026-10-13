import SwiftUI

/// Compose's wide-layout breakpoint (720dp), measured on the current window width so layouts follow
/// iPad multitasking, rotation and foldables as they change.
enum WideLayout {
    static let minWidth: CGFloat = 720
}

/// Native counterpart of Compose's `ListDetailLayout` (same breakpoint and list width).
///
/// Two-pane (window 720pt or wider: iPad, an unfolded foldable, an iPhone in landscape): the list
/// on the left, the editor for `selection` (or the empty message) on the right with its own
/// navigation bar and a Close button. Narrower: only the list, whose rows push their editor. `list` is told which mode is active so rows and
/// Add buttons can pick the right path (see `DetailLink`).
struct ListDetailLayout<Key: Hashable, ListContent: View, Detail: View>: View {
    @Binding var selection: Key?
    /// The list's navigation path; the open editor moves between it and `selection` when the
    /// window crosses the breakpoint (folding or unfolding, resizing a window, rotating).
    @Binding var path: [Key]
    /// False shows the list alone at any width (e.g. Plan's Overview, which has no detail).
    var enabled = true
    let emptyTitle: String
    let emptyMessage: String
    @ViewBuilder let list: (_ twoPane: Bool) -> ListContent
    @ViewBuilder let detail: (Key) -> Detail

    @State private var width: CGFloat = 0

    private var twoPane: Bool { enabled && width >= WideLayout.minWidth }

    var body: some View {
        HStack(spacing: 0) {
            // Rebuilt when the mode changes: a navigation bar laid out full width keeps its
            // trailing items there, under iPadOS's floating tab bar, once the pane narrows.
            list(twoPane)
                .id(twoPane)
                .frame(width: twoPane ? min(max(width * 0.4, 300), 420) : nil)
            if twoPane {
                Divider().ignoresSafeArea()
                NavigationStack {
                    if let selection {
                        detail(selection)
                            .id(selection)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close", systemImage: "xmark") { self.selection = nil }
                                }
                            }
                    } else {
                        ContentUnavailableView(emptyTitle, systemImage: "sidebar.right", description: Text(emptyMessage))
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .onChange(of: twoPane) { _, wide in
            if wide, selection == nil, path.count == 1 {
                selection = path.removeLast()
            } else if !wide, enabled, let open = selection {
                // Not when `enabled` turned false (e.g. Plan switching to Overview): that view has
                // no detail, so the selection is just dropped.
                selection = nil
                path.append(open)
            }
        }
    }
}

/// A list row that pushes `value` on narrow screens and selects it for the detail pane in
/// two-pane mode (highlighted while selected).
struct DetailLink<Value: Hashable, Label: View>: View {
    let value: Value
    /// The detail-pane selection when two-pane; nil when the row should push instead.
    let selection: Binding<Value?>?
    @ViewBuilder let label: Label

    var body: some View {
        if let selection {
            let selected = selection.wrappedValue == value
            Button { selection.wrappedValue = value } label: { label }
                .buttonStyle(.plain)
                .listRowBackground(selected ? Color.accentColor.opacity(0.18) : Color(.secondarySystemGroupedBackground))
                .accessibilityAddTraits(selected ? .isSelected : [])
        } else {
            NavigationLink(value: value) { label }
        }
    }
}

extension View {
    /// Caps scrolling content at `maxWidth`, centered, like Compose's `ReadableColumn` /
    /// `maxContentWidth` (forms 640, Settings 720, Plan Overview and Import 840).
    func readableWidth(_ maxWidth: CGFloat) -> some View {
        modifier(ReadableWidth(maxWidth: maxWidth))
    }
}

private struct ReadableWidth: ViewModifier {
    let maxWidth: CGFloat
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        let margin = (width - maxWidth) / 2
        // Narrower than the cap: keep the list's own (inset grouped) margins.
        Group {
            if margin > 20 {
                content.contentMargins(.horizontal, margin, for: .scrollContent)
            } else {
                content
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}
