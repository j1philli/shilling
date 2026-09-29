import SwiftUI
import UIKit
import KotlinModules

@main
struct ShillingApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSnapshotShield = true
    @State private var shieldDismissWorkItem: DispatchWorkItem?
    @StateObject private var appearance = AppearanceModel()
    @StateObject private var appPhase = AppPhaseModel()

    init() {
        IosKoinKt.startIosKoin()
        _showSnapshotShield = State(initialValue: !Self.hasPendingReceiptCameraLaunch())
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                switch appPhase.phase {
                case .onboarding:
                    OnboardingScreen()
                case .starting:
                    ZStack {
                        Color(.systemBackground).ignoresSafeArea()
                        ProgressView()
                    }
                default:
                    // Rebuilt on each return to the main app (e.g. after Start over).
                    TabBarView().ignoresSafeArea(.all)
                }

                ReceiptShortcutLaunchView()
                    .ignoresSafeArea(.all)
                    .opacity(showSnapshotShield ? 1 : 0)
                    .allowsHitTesting(showSnapshotShield)
                    .animation(.easeInOut(duration: 0.22), value: showSnapshotShield)
            }
            .preferredColorScheme(appearance.colorScheme)
            .task { await appPhase.observe() }
            .onOpenURL { url in
                handleDeepLink(url)
            }
            .onAppear {
                checkAppGroupFlag()
            }
            .onChange(of: scenePhase) { _, newPhase in
                switch newPhase {
                case .active:
                    scheduleSnapshotShieldDismissIfNeeded()
                case .inactive, .background:
                    shieldDismissWorkItem?.cancel()
                    shieldDismissWorkItem = nil
                    showSnapshotShield = false
                @unknown default:
                    break
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                // Fires on every foreground transition, including when the widget intent opens the app
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    checkAppGroupFlag()
                }
            }
        }
    }

    private func handleDeepLink(_ url: URL) {
        guard url.scheme == "shilling.finance" else { return }
        if url.host == "receipt-camera" {
            ReceiptCameraRequest.shared.pending = true
        }
    }

    private func checkAppGroupFlag() {
        guard let defaults = UserDefaults(suiteName: "group.finance.shilling.app") else {
            print("[DeepLink] Could not open App Group UserDefaults")
            return
        }
        let flag = defaults.bool(forKey: "pendingReceiptCamera")
        print("[DeepLink] checkAppGroupFlag: pendingReceiptCamera = \(flag)")
        if flag {
            defaults.removeObject(forKey: "pendingReceiptCamera")
            ReceiptCameraRequest.shared.pending = true
            print("[DeepLink] Set DeepLinkAction to receiptCamera")
        }
    }

    private static func hasPendingReceiptCameraLaunch() -> Bool {
        guard let defaults = UserDefaults(suiteName: "group.finance.shilling.app") else {
            return false
        }
        return defaults.bool(forKey: "pendingReceiptCamera")
    }

    private func scheduleSnapshotShieldDismissIfNeeded() {
        shieldDismissWorkItem?.cancel()

        let workItem = DispatchWorkItem {
            withAnimation(.easeOut(duration: 0.12)) {
                showSnapshotShield = false
            }
            shieldDismissWorkItem = nil
        }

        shieldDismissWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: workItem)
    }
}

private struct ReceiptShortcutLaunchView: View {
    var body: some View {
        ZStack {
            Color(.systemBackground)
            Image("ShortcutLaunchIcon")
                .resizable()
                .interpolation(.high)
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }
}
