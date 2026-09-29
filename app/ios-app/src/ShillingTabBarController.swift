import SwiftUI
import UIKit
import KotlinModules

/// Native tab bar around the app. Compose owns navigation; this controller mirrors its tabs and
/// selection (via `NativeTabBridge`). Tabs with a SwiftUI screen (`nativeScreen(for:)`) show it;
/// every other tab shows the single Compose view. The bar stays hidden until the main scaffold is
/// up (onboarding, sign-in).
///
/// The Compose view must never leave the window: Compose Multiplatform disposes its scene when it
/// does (losing the Compose tabs' navigation state) and crashes when it comes back. So it always moves into the selected tab's container, and sits hidden under the
/// SwiftUI screen on native tabs.
final class ShillingTabBarController: UITabBarController, UITabBarControllerDelegate {
    private let composeController = MainViewControllerKt.MainViewController()
    /// A Compose detail/editor screen is open; it shows over the native tab screen until closed.
    private var detailOpen = false
    /// Hosts Compose while there are no tabs (before the main scaffold appears).
    private lazy var placeholderTab = UITab(title: "", image: nil, identifier: "") { _ in TabContainerController() }

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = self
        setTabs([placeholderTab], animated: false)
        setTabBarHidden(true, animated: false)
        attachCompose(to: placeholderTab)
        NativeTabBridge.shared.setListener { [weak self] tabs, selected, detailOpen in
            self?.detailOpen = detailOpen.boolValue
            ComposeOverlay.shared.detailOpen = detailOpen.boolValue
            self?.update(tabs: tabs, selected: selected)
        }
    }

    private func update(tabs nativeTabs: [NativeTab], selected: String?) {
        if nativeTabs.isEmpty {
            if tabs != [placeholderTab] { setTabs([placeholderTab], animated: false) }
            setTabBarHidden(true, animated: false)
            attachCompose(to: placeholderTab)
            return
        }
        let keys = nativeTabs.map(\.key)
        if tabs.map(\.identifier) != keys {
            // Reuse existing tabs so their containers survive a reorder.
            let existing = Dictionary(uniqueKeysWithValues: tabs.map { ($0.identifier, $0) })
            setTabs(nativeTabs.map { tab in
                existing[tab.key] ?? UITab(
                    title: tab.title,
                    image: UIImage(systemName: tab.systemImage),
                    identifier: tab.key
                ) { [weak self] _ in TabContainerController(native: self?.nativeScreen(for: tab.key)) }
            }, animated: false)
        }
        setTabBarHidden(false, animated: false)
        let tab = selected.flatMap { key in tabs.first { $0.identifier == key } } ?? selectedTab
        if let tab {
            if selectedTab !== tab { selectedTab = tab }
            attachCompose(to: tab)
        }
    }

    func tabBarController(_ tabBarController: UITabBarController, shouldSelectTab tab: UITab) -> Bool {
        // Move Compose before the switch so the new tab never shows empty.
        attachCompose(to: tab)
        NativeTabBridge.shared.select(key: tab.identifier)
        return true
    }

    /// SwiftUI screens that replace the Compose UI for a tab, keyed by tab identifier.
    private func nativeScreen(for key: String) -> UIViewController? {
        switch key {
        case "HOME":
            return UIHostingController(rootView: HomeScreen { destination in
                NativeTabBridge.shared.openHome(destination: destination)
            })
        case "ACTIVITY":
            return UIHostingController(rootView: ActivityScreen())
        case "SETTINGS":
            return UIHostingController(rootView: SettingsScreen())
        default:
            return nil
        }
    }

    private func attachCompose(to tab: UITab) {
        guard let container = tab.viewController as? TabContainerController else { return }
        if composeController.parent !== container {
            composeController.willMove(toParent: nil)
            composeController.view.removeFromSuperview()
            composeController.removeFromParent()
            container.addChild(composeController)
            composeController.view.frame = container.view.bounds
            composeController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.view.insertSubview(composeController.view, at: 0)
            composeController.didMove(toParent: container)
        }
        // On native tabs Compose sits hidden below the SwiftUI screen, except while it shows a
        // detail/editor screen the native screen opened.
        let showCompose = container.native == nil || detailOpen
        composeController.view.isHidden = !showCompose
        // Hide the native screen too (not just cover it), so its toolbar and accessibility
        // elements don't show through the Compose editor.
        container.native?.view.isHidden = showCompose
    }
}

/// Per-tab container. Holds the tab's SwiftUI screen, if it has one, and the shared Compose view
/// whenever the tab is selected.
private final class TabContainerController: UIViewController {
    let native: UIViewController?

    init(native: UIViewController? = nil) {
        self.native = native
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        guard let native else { return }
        addChild(native)
        native.view.frame = view.bounds
        native.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(native.view)
        native.didMove(toParent: self)
    }
}

/// Whether a Compose detail/editor screen is showing over the native tab screens. On the iPhone Duo
/// the system lifts toolbar items into the side column, outside the hidden native view, so native
/// screens drop their toolbar items while this is set.
@MainActor
final class ComposeOverlay: ObservableObject {
    static let shared = ComposeOverlay()
    @Published var detailOpen = false
}
