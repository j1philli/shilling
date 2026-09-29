import UIKit
import KotlinModules

/// Native tab bar around the single Compose UI. Compose owns navigation; this controller mirrors
/// its tabs and selection (via `NativeTabBridge`) and moves the Compose view into whichever tab
/// is showing. The bar stays hidden until the main scaffold is up (onboarding, sign-in).
final class ShillingTabBarController: UITabBarController, UITabBarControllerDelegate {
    private let composeController = MainViewControllerKt.MainViewController()
    /// Hosts Compose while there are no tabs (before the main scaffold appears).
    private lazy var placeholderTab = UITab(title: "", image: nil, identifier: "") { _ in TabContainerController() }

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = self
        setTabs([placeholderTab], animated: false)
        setTabBarHidden(true, animated: false)
        attachCompose(to: placeholderTab)
        NativeTabBridge.shared.setListener { [weak self] tabs, selected in
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
                ) { _ in TabContainerController() }
            }, animated: false)
        }
        setTabBarHidden(false, animated: false)
        if let key = selected, let tab = tabs.first(where: { $0.identifier == key }) {
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

    private func attachCompose(to tab: UITab) {
        guard let container = tab.viewController, composeController.parent !== container else { return }
        composeController.willMove(toParent: nil)
        composeController.view.removeFromSuperview()
        composeController.removeFromParent()
        container.addChild(composeController)
        composeController.view.frame = container.view.bounds
        composeController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.view.addSubview(composeController.view)
        composeController.didMove(toParent: container)
    }
}

/// Empty per-tab container; the shared Compose view is moved into the selected one.
private final class TabContainerController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
    }
}
