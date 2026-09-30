import Foundation
import KotlinModules
import KMPNativeCoroutinesAsync

/// Observes the shared `HomeViewModel` (through `HomeScreenModel`) for SwiftUI.
@MainActor
final class HomeModel: ObservableObject {
    @Published private(set) var state: HomeUiState
    private let screen = HomeScreenModel()

    init() {
        state = screen.state
    }

    /// Streams state until the calling task is cancelled (use from `.task`).
    func observe() async {
        do {
            for try await value in asyncSequence(for: screen.stateFlow) {
                state = value
            }
        } catch {
            // Cancelled with the view; nothing to report.
        }
    }

    deinit {
        screen.close()
    }
}
