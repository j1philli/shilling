import Combine
import SwiftUI
import UIKit
import KotlinModules

/// The app's native tab bar: the user's tab order (shared `TabOrder`) then Settings, each tab a
/// SwiftUI screen. Home tiles switch tabs here; a receipt-camera request selects Receipts.
final class ShillingTabBarController: UITabBarController {
    private var cameraRequest: AnyCancellable?

    override func viewDidLoad() {
        super.viewDidLoad()
        setTabs(NativeTabBridge.shared.tabs().map { tab in
            UITab(title: tab.title, image: UIImage(systemName: tab.systemImage), identifier: tab.key) { [weak self] _ in
                self?.screen(for: tab.key) ?? UIViewController()
            }
        }, animated: false)
        cameraRequest = ReceiptCameraRequest.shared.$pending
            .filter { $0 }
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.select(key: "RECEIPTS") }
    }

    private func select(key: String) {
        if let tab = tabs.first(where: { $0.identifier == key }) { selectedTab = tab }
    }

    private func screen(for key: String) -> UIViewController {
        switch key {
        case "HOME":
            return UIHostingController(rootView: HomeScreen { [weak self] destination in
                self?.select(key: NativeTabBridge.shared.openHome(destination: destination))
            })
        case "PLAN":
            return UIHostingController(rootView: PlanScreen())
        case "ACTIVITY":
            return UIHostingController(rootView: ActivityScreen())
        case "RECEIPTS":
            return UIHostingController(rootView: ReceiptsScreen())
        default:
            return UIHostingController(rootView: SettingsScreen())
        }
    }
}

/// "Scan Receipt" (App Intent / `shilling.finance://receipt-camera`): Receipts opens a new receipt
/// with the camera up.
@MainActor
final class ReceiptCameraRequest: ObservableObject {
    static let shared = ReceiptCameraRequest()
    @Published var pending = false
}

struct TabBarView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> ShillingTabBarController { ShillingTabBarController() }
    func updateUIViewController(_ controller: ShillingTabBarController, context: Context) {}
}
