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

/// Native Receipts: filterable list; opening or adding a receipt uses the Compose editor for now.
struct ReceiptsScreen: View {
    @StateObject private var model = ReceiptsModel()
    @ObservedObject private var overlay = ComposeOverlay.shared

    var body: some View {
        let state = model.state
        NavigationStack {
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
                                Button("Add receipt") { model.screen.openReceipt(receiptId: nil) }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                    }
                    .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(state.rows, id: \.id) { row in
                            Button {
                                model.screen.openReceipt(receiptId: row.id)
                            } label: {
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
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Receipts")
            .toolbar {
                if !overlay.detailOpen {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { model.screen.openReceipt(receiptId: nil) } label: {
                            Label("Add", systemImage: "plus")
                        }
                    }
                }
            }
        }
        .task { await model.observe() }
    }
}
