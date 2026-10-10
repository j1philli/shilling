import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync

/// Observes the shared `ReceiptsViewModel` (through `ReceiptsScreenModel`) for SwiftUI.
@MainActor
final class ReceiptsModel: ObservableObject {
    @Published private(set) var state: ReceiptsUiState
    let screen = ReceiptsScreenModel()

    init() {
        state = screen.state
    }

    func observe() async {
        do {
            for try await value in asyncSequence(for: screen.stateFlow) {
                // Keep the displayed snapshot through cache-expiry reloads.
                // A genuine empty result carries empty-state copy and still applies.
                guard !value.rows.isEmpty || value.empty != nil else { continue }
                if state != value { state = value }
            }
        } catch {}
    }

    deinit {
        screen.close()
    }
}

/// Native Receipts: filterable list, with the native receipt editor pushed onto its stack.
struct ReceiptsScreen: View {
    @StateObject private var model = ReceiptsModel()
    @State private var path: [Editor] = []
    @State private var selected: Editor?
    @State private var twoPane = false
    @State private var toast: Toast?
    @ObservedObject private var cameraRequest = ReceiptCameraRequest.shared

    /** Native editors pushed onto Receipts' navigation stack, or shown beside the list when wide. */
    enum Editor: Hashable {
        case receipt(String?)
        /// A new receipt with the camera up ("Scan Receipt").
        case scan
    }

    var body: some View {
        ListDetailLayout(
            selection: $selected,
            path: $path,
            emptyTitle: "No receipt selected",
            emptyMessage: "Choose a receipt to view or attach it.",
            list: { list(twoPane: $0) },
            detail: { editor($0) }
        )
        .overlay(alignment: .bottom) { ToastView(toast: $toast) }
        .task { await model.observe() }
        .onReceive(cameraRequest.$pending) { pending in
            guard pending else { return }
            cameraRequest.pending = false
            if twoPane { selected = .scan } else { path = [.scan] }
        }
    }

    private func list(twoPane: Bool) -> some View {
        let state = model.state
        let open: (Editor) -> Void = { editor in if twoPane { selected = editor } else { path.append(editor) } }
        return NavigationStack(path: $path) {
            ReceiptTable(state: state,
                selectFilter: { model.screen.setFilter(filter: $0) },
                openReceipt: { open(.receipt($0)) })
            // UITableView adjusts its own navigation/tab-bar insets.
            .ignoresSafeArea(.container, edges: .vertical)
            .navigationTitle("Receipts")
            .navigationBarTitleDisplayMode(.large)
            .navigationDestination(for: Editor.self) { editor($0) }
            .toolbar {
                if path.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { open(.receipt(nil)) } label: {
                            Label("Add", systemImage: "plus")
                        }
                    }
                }
            }
        }
        .onAppear { self.twoPane = twoPane }
        .onChange(of: twoPane) { _, value in self.twoPane = value }
    }

    @ViewBuilder
    private func editor(_ editor: Editor) -> some View {
        switch editor {
        case .receipt(let id):
            ReceiptEditorScreen(receiptId: id) { result in done(result) }
        case .scan:
            ReceiptEditorScreen(receiptId: nil, launchCamera: true) { result in done(result) }
        }
    }

    private func done(_ result: Toast) {
        toast = result
        selected = nil
    }
}
