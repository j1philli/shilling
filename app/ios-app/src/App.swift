import SwiftUI
import UIKit
import KotlinModules
import KMPNativeCoroutinesAsync

@main
struct ShillingApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSnapshotShield = true
    @State private var shieldDismissWorkItem: DispatchWorkItem?
    @StateObject private var appearance = AppearanceModel()
    @StateObject private var appPhase = AppPhaseModel()
    @StateObject private var account: SettingsModel
    @State private var showPasswordSetup = false
    @State private var authCallbackError: String?

    init() {
        IosKoinKt.startIosKoin()
        _showSnapshotShield = State(initialValue: !Self.hasPendingReceiptCameraLaunch())
        _account = StateObject(wrappedValue: SettingsModel())
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
                    TabBarView().id(appPhase.spaceId).ignoresSafeArea(.all)
                }

                ReceiptShortcutLaunchView()
                    .ignoresSafeArea(.all)
                    .opacity(showSnapshotShield ? 1 : 0)
                    .allowsHitTesting(showSnapshotShield)
                    .animation(.easeInOut(duration: 0.22), value: showSnapshotShield)
            }
            .preferredColorScheme(appearance.colorScheme)
            .task { await appPhase.observe() }
            .task { await appPhase.observeSpace() }
            .task { await account.observe() }
            .onChange(of: needsPasswordSetup) { _, needed in
                if needed { showPasswordSetup = true }
            }
            .sheet(isPresented: $showPasswordSetup) {
                PasswordSetupSheet(screen: account.screen)
            }
            .alert("Could not open link", isPresented: Binding(
                get: { authCallbackError != nil },
                set: { if !$0 { authCallbackError = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(authCallbackError ?? "")
            }
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
                    refreshAccountStatus()
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
        if url.host == "auth-callback" {
            Task {
                let result = try? await asyncFunction(for: AppRoot.shared.handleAuthCallback(url: url.absoluteString))
                if result != "Account ready." {
                    authCallbackError = result ?? "Could not open the sign-in link."
                }
            }
            return
        }
        if url.host == "receipt-camera" {
            ReceiptCameraRequest.shared.pending = true
        }
    }

    private var needsPasswordSetup: Bool {
        (account.state.account as? SettingsAccountSignedIn)?.needsPasswordSetup == true
    }

    private func refreshAccountStatus() {
        Task { _ = try? await asyncFunction(for: account.screen.refreshAccountStatus()) }
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

private struct PasswordSetupSheet: View {
    let screen: SettingsScreenModel
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var message: String?
    @State private var submitting = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Your email is confirmed. Set a password to sign in on another device.")
                    SecureField("Password", text: $password)
                        .textContentType(.newPassword)
                    if let message { Text(message).font(.footnote).foregroundStyle(.secondary) }
                }
                Button("Set password") {
                    submitting = true
                    Task {
                        defer { submitting = false }
                        message = try? await asyncFunction(for: screen.setPassword(password: password))
                        if message == "Password set." { dismiss() }
                    }
                }
                .disabled(password.isEmpty || submitting)
            }
            .navigationTitle("Set your password")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Later") { dismiss() }
                }
            }
        }
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
