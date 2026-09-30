import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync

/// Observes the shared `SettingsViewModel` (through `SettingsScreenModel`) for SwiftUI.
@MainActor
final class SettingsModel: ObservableObject {
    @Published private(set) var state: SettingsUiState
    @Published private(set) var devicesState: HostedDevicesUiState
    @Published private(set) var silverOffers: [IosBillingOffer] = []
    @Published private(set) var goldOffers: [IosBillingOffer] = []
    @Published private(set) var managementURL: String?
    @Published private(set) var billingError: String?
    let screen = SettingsScreenModel()

    init() {
        state = screen.state
        devicesState = screen.devicesState
    }

    func observe() async {
        do {
            for try await value in asyncSequence(for: screen.stateFlow) {
                state = value
            }
        } catch {}
    }

    func observeDevices() async {
        do {
            for try await value in asyncSequence(for: screen.devicesStateFlow) {
                devicesState = value
            }
        } catch {}
    }

    func loadBilling() async {
        guard devicesState.canPurchase, devicesState.billingConfig != nil else { return }
        do {
            silverOffers = try await asyncFunction(for: screen.silverOffers())
            goldOffers = try await asyncFunction(for: screen.goldOffers())
            managementURL = try? await asyncFunction(for: screen.subscriptionManagementUrl())
            billingError = nil
        } catch {
            billingError = error.localizedDescription
        }
    }

    deinit {
        screen.close()
    }
}

/// Native Settings: same sections, copy and actions as the Compose Settings.
struct SettingsScreen: View {
    @StateObject private var model = SettingsModel()
    @State private var confirmingAccountAction = false
    @State private var toast: Toast?

    var body: some View {
        let state = model.state
        NavigationStack {
            Form {
                appearanceSection(state)
                if let account = state.account {
                    accountSection(account)
                }
                if let sync = state.sync {
                    syncSection(sync)
                }
                if model.devicesState.visible {
                    devicesSection(model.devicesState)
                    if !model.devicesState.selfHosted {
                        subscriptionSection(model.devicesState)
                    }
                }
                aboutSection
                if let developer = state.developer,
                   model.screen.developerToolsAlwaysOn || state.developerToolsUnlocked {
                    DeveloperSections(developer: developer, screen: model.screen, toast: $toast)
                }
            }
            .navigationTitle("Settings")
        }
        .overlay(alignment: .bottom) { ToastView(toast: $toast) }
        .task { await model.observe() }
        .task { await model.observeDevices() }
        .task(id: model.devicesState.billingConfig?.iosPublicKey) { await model.loadBilling() }
    }

    // MARK: Appearance

    private func appearanceSection(_ state: SettingsUiState) -> some View {
        Section {
            Picker("Theme", selection: Binding(
                get: { state.prefs.themeMode },
                set: { model.screen.setThemeMode(mode: $0) }
            )) {
                ForEach(model.screen.themeModes, id: \.self) { mode in
                    Text(mode.label).tag(mode)
                }
            }
            .pickerStyle(.segmented)

            Picker("Currency symbol", selection: Binding(
                get: { state.prefs.currencySymbol },
                set: { model.screen.setCurrencySymbol(symbol: $0) }
            )) {
                ForEach(model.screen.currencyOptions, id: \.self) { symbol in
                    Text(symbol.trimmingCharacters(in: .whitespaces)).tag(symbol)
                }
            }

            Picker("Week starts on", selection: Binding(
                get: { Int(model.screen.weekStartIndex(state: state)) },
                set: { model.screen.setWeekStart(index: Int32($0)) }
            )) {
                ForEach(Array(model.screen.weekDayLabels.enumerated()), id: \.offset) { index, label in
                    Text(label).tag(index)
                }
            }
        } header: {
            Text("Appearance")
        } footer: {
            Text("\(state.currencyExample). Plan and Home show weeks starting on the chosen day.")
        }
    }

    // MARK: Account

    @ViewBuilder
    private func accountSection(_ account: SettingsAccount) -> some View {
        Section("Account") {
            if let guest = account as? SettingsAccountGuest {
                VStack(alignment: .leading, spacing: 4) {
                    Text(guest.title).font(.headline)
                    Text(guest.body).font(.subheadline).foregroundStyle(.secondary)
                }
                CredentialsForm(initialMode: .createAccount, enabled: guest.authAvailable,
                                disabledReason: guest.disabledReason) { mode, email, password in
                    try? await asyncFunction(for: model.screen.submitCredentials(mode: mode, email: email, password: password))
                }
                if let pending = guest.pendingConfirmation {
                    Text(pending).font(.footnote).foregroundStyle(.secondary)
                }
            } else if let signedIn = account as? SettingsAccountSignedIn {
                VStack(alignment: .leading, spacing: 4) {
                    Text(signedIn.title).font(.headline)
                }
                if let pending = signedIn.pendingConfirmation {
                    Text(pending).font(.footnote).foregroundStyle(.secondary)
                }
            } else if let selfHosted = account as? SettingsAccountSelfHosted {
                VStack(alignment: .leading, spacing: 4) {
                    Text(selfHosted.TITLE).font(.headline)
                    Text(selfHosted.BODY).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Button(account.actionLabel, role: account.confirm.destructive ? .destructive : nil) {
                confirmingAccountAction = true
            }
            .confirmationDialog(account.confirm.title, isPresented: $confirmingAccountAction, titleVisibility: .visible) {
                Button(account.confirm.confirmLabel, role: account.confirm.destructive ? .destructive : nil) {
                    Task { _ = try? await asyncFunction(for: model.screen.confirmAccountAction()) }
                }
            } message: {
                Text(account.confirm.message)
            }
        }
    }

    // MARK: Sync

    private func syncSection(_ sync: SyncStatusUi) -> some View {
        Section("Sync") {
            VStack(alignment: .leading, spacing: 4) {
                Text(sync.title).font(.headline)
                Text(sync.detail).font(.subheadline).foregroundStyle(.secondary)
            }
            if sync.showRetry {
                Button("Try again") { model.screen.retrySync() }
                    .disabled(!sync.retryEnabled)
            }
        }
    }

    private func devicesSection(_ devices: HostedDevicesUiState) -> some View {
        Group {
            Section {
                Toggle("Cloud relay", isOn: Binding(
                    get: { devices.relayEnabled },
                    set: { model.screen.setCloudRelay(enabled: $0) }
                ))
                .disabled(!devices.relayAvailable && !devices.relayEnabled)
            } header: {
                Text("Devices & relay")
            } footer: {
                Text("Allow this device to use a TURN server when direct WebRTC cannot connect. The relay carries encrypted traffic.")
            }

            if !devices.selfHosted {
                Section {
                    if let plan = devices.planLabel {
                        Text("\(plan) plan · \(devices.deviceAllowance ?? "")")
                            .foregroundStyle(.secondary)
                    }
                    if devices.loading { ProgressView() }
                    if let error = devices.error {
                        Text(error).foregroundStyle(.red)
                    }
                    ForEach(devices.devices, id: \.deviceId) { device in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(device.deviceId).font(.footnote.monospaced())
                            Text("\(device.ownerLabel) · \(device.connectionLabel)")
                                .font(.caption).foregroundStyle(.secondary)
                            if device.canRemove {
                                Button("Remove device", role: .destructive) {
                                    Task {
                                        let message = try? await asyncFunction(for: model.screen.removeDevice(deviceId: device.deviceId))
                                        toast = Toast(message ?? "Could not remove device")
                                    }
                                }
                            }
                        }
                    }
                    Button("Refresh devices") { model.screen.refreshDevices() }
                } header: {
                    Text("Devices in this finance space")
                } footer: {
                    Text("Connection status is measured from this device's open WebRTC data channels.")
                }
            }
        }
    }

    private func subscriptionSection(_ devices: HostedDevicesUiState) -> some View {
        Section("Subscription") {
            Text("Your plan: \(devices.accountPlanLabel ?? "Loading")")
            Text(devices.bankReadingEnabled
                 ? "Bank reading is included when available"
                 : "Bank reading requires Silver")
                .font(.footnote).foregroundStyle(.secondary)
            if !devices.canPurchase {
                Text("Create an account to subscribe and restore purchases on other devices.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                ForEach(model.silverOffers, id: \.packageId) { offer in
                    Button("Silver: \(offer.title) — \(offer.price)") {
                        Task {
                            do {
                                let purchased = try await asyncFunction(for: model.screen.purchaseSilver(packageId: offer.packageId))
                                if purchased.boolValue { toast = Toast("Purchase complete. Refreshing plan...") }
                            } catch { toast = Toast(error.localizedDescription) }
                        }
                    }
                }
                ForEach(model.goldOffers, id: \.packageId) { offer in
                    Button("Gold: \(offer.title) — \(offer.price)") {
                        Task {
                            do {
                                let purchased = try await asyncFunction(for: model.screen.purchaseGold(packageId: offer.packageId))
                                if purchased.boolValue { toast = Toast("Purchase complete. Refreshing plan...") }
                            } catch { toast = Toast(error.localizedDescription) }
                        }
                    }
                }
                if devices.billingConfig != nil {
                    Button("Restore purchases") {
                        Task {
                            do {
                                _ = try await asyncFunction(for: model.screen.restorePurchases())
                                toast = Toast("Purchases restored. Refreshing plan...")
                            } catch { toast = Toast(error.localizedDescription) }
                        }
                    }
                }
                if let link = model.managementURL, let url = URL(string: link) {
                    Link("Manage subscription", destination: url)
                }
            }
            if let error = model.billingError {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            Button("Refresh plan") { model.screen.refreshDevices() }
        }
    }

    // MARK: About

    private var aboutSection: some View {
        Section("About") {
            Button {
                if model.screen.aboutTapped() && !model.screen.developerToolsAlwaysOn {
                    toast = Toast("Developer tools enabled")
                }
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Shilling").font(.headline)
                    Text("Household budgeting that syncs directly between your devices.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - Developer tools

private struct DeveloperSections: View {
    let developer: DeveloperInfo
    let screen: SettingsScreenModel
    @Binding var toast: Toast?

    @State private var serverUrl = ""
    @State private var householdId = ""

    var body: some View {
        Section {
            Text(developer.connectionSummary)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            if developer.selfHosted {
                TextField("Household ID", text: $householdId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Save household ID") { toast = Toast(screen.saveHouseholdId(id: householdId)) }
                    .disabled(householdId.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            TextField("Server URL", text: $serverUrl)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Save and reconnect") { toast = Toast(screen.saveServerUrl(url: serverUrl)) }
                .disabled(serverUrl.trimmingCharacters(in: .whitespaces).isEmpty)
        } header: {
            Text("Developer tools")
        } footer: {
            if !developer.selfHosted {
                Text("Changing the server may switch between managed and self-hosted mode.")
            }
        }
        .onAppear {
            if serverUrl.isEmpty { serverUrl = developer.serverUrl }
            if householdId.isEmpty { householdId = developer.householdId }
        }

        if !developer.bootstrapLines.isEmpty {
            Section("Bootstrap status") {
                ForEach(developer.bootstrapLines, id: \.self) { Text($0).font(.footnote) }
                if let error = developer.bootstrapError {
                    Text(error).font(.footnote).foregroundStyle(.secondary)
                }
                Button {
                    screen.retrySync()
                } label: {
                    HStack {
                        if developer.bootstrapChecking { ProgressView() }
                        Text(developer.bootstrapChecking ? "Checking..." : "Retry now")
                    }
                }
                .disabled(developer.bootstrapChecking)
            }
        }

        Section {
            Button("Add sample data") {
                Task {
                    if let message = try? await asyncFunction(for: screen.addSampleData()) {
                        toast = Toast(message)
                    }
                }
            }
        } header: {
            Text("Sample data")
        } footer: {
            Text("Adds demo accounts, categories and schedules for testing.")
        }
    }
}
