import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync

/// Observes the shared `ActivityViewModel` (through `ActivityScreenModel`) for SwiftUI.
@MainActor
final class ActivityModel: ObservableObject {
    @Published private(set) var state: ActivityUiState
    let screen = ActivityScreenModel()

    init() {
        state = screen.state
    }

    func observe() async {
        do {
            for try await value in asyncSequence(for: screen.stateFlow) {
                // The shared replay cache expires while this tab is hidden. Its
                // initial placeholder is not a loaded empty result: publishing
                // it here clears the native list and discards its scroll position.
                guard !value.sections.isEmpty || value.empty != nil else { continue }
                // Reloads also create equal immutable snapshots. Avoid rebuilding
                // every visible row when the underlying data has not changed.
                if state != value { state = value }
            }
        } catch {}
    }

    deinit {
        screen.close()
    }
}

/// Native Activity: recorded transactions by day, with search and a history range.
struct ActivityScreen: View {
    @StateObject private var model = ActivityModel()
    @State private var query = ""
    @State private var path: [Editor] = []
    @State private var toast: Toast?

    /** Native editors pushed onto Activity's navigation stack. */
    enum Editor: Hashable {
        case transaction(String?)
        case importCSV
    }

    var body: some View {
        let state = model.state
        NavigationStack(path: $path) {
            List {
                Section {
                    Picker("Range", selection: Binding(
                        get: { Int(state.selectedRange.months) },
                        set: { model.screen.setRange(months: Int32($0)) }
                    )) {
                        ForEach(state.ranges, id: \.months) { range in
                            Text(range.label).tag(Int(range.months))
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }

                if let empty = state.empty {
                    Section {
                        ContentUnavailableView {
                            Label(empty.title, systemImage: empty.showActions ? "tray" : "magnifyingglass")
                        } description: {
                            Text(empty.message)
                        } actions: {
                            if empty.showActions {
                                Button("Add transaction") { path.append(.transaction(nil)) }
                                    .buttonStyle(.borderedProminent)
                                Button("Import from CSV") { path.append(.importCSV) }
                            }
                        }
                    }
                    .listRowBackground(Color.clear)
                } else {
                    ForEach(state.sections, id: \.header) { section in
                        Section(section.header) {
                            ForEach(section.rows, id: \.id) { row in
                                NavigationLink(value: Editor.transaction(row.id)) {
                                    ActivityRow(row: row)
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Activity")
            .searchable(text: $query, prompt: "Search transactions")
            .onChange(of: query) { _, text in model.screen.setQuery(text: text) }
            .navigationDestination(for: Editor.self) { editor in
                switch editor {
                case .transaction(let id):
                    TransactionEditorScreen(postingId: id) { result in toast = result }
                case .importCSV:
                    ImportScreen { result in toast = result }
                }
            }
            .toolbar {
                if path.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { path.append(.importCSV) } label: {
                            Label("Import", systemImage: "square.and.arrow.down")
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { path.append(.transaction(nil)) } label: {
                            Label("Add", systemImage: "plus")
                        }
                    }
                }
            }
        }
        .overlay(alignment: .bottom) { ToastView(toast: $toast) }
        .task { await model.observe() }
    }
}

private struct ActivityRow: View {
    let row: ActivityRowUi

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color(hex: row.categoryColor) ?? Color(.tertiaryLabel))
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).lineLimit(1)
                Text(row.supporting)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(row.amount)
                .foregroundStyle(row.type.amountColor)
                .lineLimit(1)
                .fixedSize()
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
