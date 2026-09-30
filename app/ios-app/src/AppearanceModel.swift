import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync

/// The app's Light/Dark/System choice (shared `DisplayPreferences`) as a SwiftUI color scheme.
@MainActor
final class AppearanceModel: ObservableObject {
    @Published private(set) var colorScheme: ColorScheme?

    init() {
        colorScheme = Self.scheme(for: DisplayPreferencesBridge.shared.prefs.themeMode)
        Task { [weak self] in
            do {
                for try await prefs in asyncSequence(for: DisplayPreferencesBridge.shared.prefsFlow) {
                    self?.colorScheme = Self.scheme(for: prefs.themeMode)
                }
            } catch {}
        }
    }

    private static func scheme(for mode: ThemeMode) -> ColorScheme? {
        switch mode {
        case .light: return .light
        case .dark: return .dark
        default: return nil
        }
    }
}
