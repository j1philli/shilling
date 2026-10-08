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
    @State private var toast: Toast?
    @ObservedObject private var cameraRequest = ReceiptCameraRequest.shared

    /** Native editors pushed onto Receipts' navigation stack. */
    enum Editor: Hashable {
        case receipt(String?)
        /// A new receipt with the camera up ("Scan Receipt").
        case scan
    }

    var body: some View {
        let state = model.state
        NavigationStack(path: $path) {
            ReceiptTable(state: state,
                selectFilter: { model.screen.setFilter(filter: $0) },
                openReceipt: { path.append(.receipt($0)) })
            // UITableView adjusts its own navigation/tab-bar insets.
            .ignoresSafeArea(.container, edges: .vertical)
            .navigationTitle("Receipts")
            .navigationBarTitleDisplayMode(.large)
            .navigationDestination(for: Editor.self) { editor in
                switch editor {
                case .receipt(let id):
                    ReceiptEditorScreen(receiptId: id) { result in toast = result }
                case .scan:
                    ReceiptEditorScreen(receiptId: nil, launchCamera: true) { result in toast = result }
                }
            }
            .toolbar {
                if path.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { path.append(.receipt(nil)) } label: {
                            Label("Add", systemImage: "plus")
                        }
                    }
                }
            }
        }
        .overlay(alignment: .bottom) { ToastView(toast: $toast) }
        .task { await model.observe() }
        .onReceive(cameraRequest.$pending) { pending in
            guard pending else { return }
            cameraRequest.pending = false
            path = [.scan]
        }
    }
}
