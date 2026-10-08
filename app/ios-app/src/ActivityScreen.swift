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
            ActivityTable(state: state,
                          selectRange: { model.screen.setRange(months: $0) },
                          openTransaction: { path.append(.transaction($0)) },
                          importCSV: { path.append(.importCSV) })
            .ignoresSafeArea(.container, edges: .vertical)
            .navigationTitle("Activity")
            .navigationBarTitleDisplayMode(.large)
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
