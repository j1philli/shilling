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
                state = value
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
            List {
                Section {
                    Picker("Filter", selection: Binding(
                        get: { state.filter },
                        set: { model.screen.setFilter(filter: $0) }
                    )) {
                        ForEach(state.filters, id: \.self) { filter in
                            Text(filter.label).tag(filter)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                } footer: {
                    if let subtitle = state.subtitle {
                        Text(subtitle)
                    }
                }

                if let empty = state.empty {
                    Section {
                        ContentUnavailableView {
                            Label(empty.title, systemImage: "doc.text")
                        } description: {
                            if let message = empty.message { Text(message) }
                        } actions: {
                            if empty.showAdd {
                                Button("Add receipt") { path.append(.receipt(nil)) }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                    }
                    .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(state.rows, id: \.id) { row in
                            NavigationLink(value: Editor.receipt(row.id)) {
                                HStack(spacing: 12) {
                                    Image(systemName: "doc.text")
                                        .foregroundStyle(.secondary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(row.title).lineLimit(1)
                                        Text(row.supporting)
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 8)
                                    if let amount = row.amount {
                                        Text(amount).lineLimit(1).fixedSize()
                                    }
                                }
                                .contentShape(Rectangle())
                                .accessibilityElement(children: .combine)
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Receipts")
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
