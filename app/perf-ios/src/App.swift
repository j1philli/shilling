import SwiftUI
import UIKit
import KotlinModules

@main
struct ShillingPerformanceApp: App {
    var body: some Scene {
        WindowGroup { PerformanceView().ignoresSafeArea() }
    }
}

private struct PerformanceView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        P2pPerformanceKt.PerformanceViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
