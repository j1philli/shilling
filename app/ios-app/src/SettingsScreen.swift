import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync

/// Observes the shared `SettingsViewModel` (through `SettingsScreenModel`) for SwiftUI.
@MainActor
final class SettingsModel: ObservableObject {
    @Published private(set) var state: SettingsUiState
    @Published private(set) var devicesState: HostedDevicesUiState
    @Published private(set) var spacesState: HostedSpacesUiState
    @Published private(set) var silverOffers: [IosBillingOffer] = []
    @Published private(set) var goldOffers: [IosBillingOffer] = []
    @Published private(set) var managementURL: String?
    @Published private(set) var billingError: String?
    let screen = SettingsScreenModel()

    init() {
        state = screen.state
        devicesState = screen.devicesState
        spacesState = screen.spacesState
    }

    func observe() async {
        do {
            for try await value in asyncSequence(for: screen.stateFlow) {
                // StateFlow replays its current object when a tab becomes visible again.
                if state !== value { state = value }
            }
        } catch {}
    }

    func observeDevices() async {
        do {
            for try await value in asyncSequence(for: screen.devicesStateFlow) {
                if devicesState !== value { devicesState = value }
            }
        } catch {}
    }

    func observeSpaces() async {
        do { for try await value in asyncSequence(for: screen.spacesStateFlow) { if spacesState !== value { spacesState = value } } } catch {}
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
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = SettingsModel()
    @State private var confirmingAccountAction = false
    @State private var toast: Toast?
    @State private var password = ""
    @State private var accountMessage: String?
    @State private var spaceName = ""
    @State private var spaceBusiness = false
    @State private var inviteEmail = ""
    @State private var invitationCode = ""
    @State private var spaceMessage: String?
    @State private var transferLink = ""
    @State private var transferFrom = ""
    @State private var transferTo = ""
    @State private var transferTitle = ""
    @State private var transferAmount = ""
    @State private var transferDate = ""
    @State private var pendingSpaceAction: (() -> Void)?

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
                        spacesSection
                        if model.spacesState.gold { transfersSection }
                    }
                }
                Section("Product analytics") {
                    Toggle("Share usage events", isOn: Binding(
                        get: { state.analyticsConsent },
                        set: { model.screen.setAnalyticsConsent(value: $0) }
                    ))
                    .disabled(!state.analyticsConfigured)
                    Text(state.analyticsNotice)
                        .font(.footnote).foregroundStyle(.secondary)
                    if !state.analyticsConfigured {
                        Text("Available after a PostHog project is configured.")
                            .font(.footnote).foregroundStyle(.secondary)
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
        .alert("Confirm membership change", isPresented: Binding(get: { pendingSpaceAction != nil }, set: { if !$0 { pendingSpaceAction = nil } })) {
            Button("Confirm", role: .destructive) { let action = pendingSpaceAction; pendingSpaceAction = nil; action?() }
            Button("Cancel", role: .cancel) { pendingSpaceAction = nil }
        } message: {
            Text("This changes access to the space. Leaving or removing a member stops future sync; copies already saved on devices remain. A space must retain an owner while other members remain.")
        }
        .overlay(alignment: .bottom) { ToastView(toast: $toast) }
        .task { await model.observe() }
        .task(id: scenePhase) {
            if scenePhase == .active { await model.observeSpaces() }
        }
        .onAppear { transferLink = model.screen.createTransferId(); transferDate = model.screen.transferToday }
        .task(id: scenePhase) {
            if scenePhase == .active { await model.observeDevices() }
        }
        .task(id: model.devicesState.billingConfig?.iosPublicKey) { await model.loadBilling() }
    }

    private func spaceAction(_ action: String, space: String? = nil, name: String? = nil, kind: String? = nil, email: String? = nil, role: String? = nil, user: String? = nil, invitation: String? = nil, code: String? = nil) {
        Task {
            do { spaceMessage = try await asyncFunction(for: model.screen.spaceAction(action: action, spaceId: space, name: name, kind: kind, email: email, role: role, userId: user, invitationId: invitation, code: code)) }
            catch { spaceMessage = error.localizedDescription }
        }
    }

    private var spacesSection: some View {
        let state = model.spacesState
        let active = state.data.spaces.first { $0.id == state.data.activeSpaceId }
        return Section("Finance spaces") {
            if state.data.requiresSpaceSelection { Text("Gold has ended. Choose one active space to resume hosted sync; your other local books are kept.").foregroundStyle(.red) }
            Text("Each space keeps its accounts, transactions and receipts separate. One paid member can sponsor the space.")
            if let error = state.error { Text(error).foregroundStyle(.red) }
            if let message = spaceMessage { Text(message) }
            Button("Refresh spaces") { model.screen.refreshSpaces() }
            ForEach(state.data.spaces, id: \.id) { space in
                if space.id == active?.id { Text("\(space.name) · \(space.kind) · active") }
                else { Button("Open \(space.name)") { spaceAction("select", space: space.id) } }
            }
            TextField("New space name", text: $spaceName)
            Toggle("Business space", isOn: $spaceBusiness)
            Button("Create space") { spaceAction("create", name: spaceName, kind: spaceBusiness ? "business" : "home") }.disabled(spaceName.trimmingCharacters(in: .whitespaces).isEmpty)
            Text("Free and Silver include one space. Gold supports multiple spaces.")
            TextField("Invitation code", text: $invitationCode).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Accept invitation") { spaceAction("accept", code: invitationCode.trimmingCharacters(in: .whitespaces)) }.disabled(invitationCode.isEmpty)
            Button("Decline invitation") { spaceAction("decline", code: invitationCode.trimmingCharacters(in: .whitespaces)) }.disabled(invitationCode.isEmpty)
            if let active = active {
                ForEach(state.data.members, id: \.userId) { member in
                    Text("\(member.email ?? member.userId) · \(member.role)")
                    if active.role == "owner" {
                        Menu("Change role") {
                            ForEach(["owner", "admin", "member"].filter { $0 != member.role }, id: \.self) { role in
                                Button("Make \(role)") { pendingSpaceAction = { spaceAction("role", space: active.id, role: role, user: member.userId) } }
                            }
                        }
                    }
                    if active.role == "owner" || active.role == "admin" && member.role == "member" {
                        Button("Remove member", role: .destructive) { pendingSpaceAction = { spaceAction("remove", space: active.id, user: member.userId) } }
                    }
                }
                if active.role == "owner" || active.role == "admin" {
                    TextField("Invite email address", text: $inviteEmail).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Create invitation") { spaceAction("invite", space: active.id, email: inviteEmail, role: "member") }.disabled(inviteEmail.isEmpty)
                    if let code = state.data.invitationCode { Text("Invitation code: \(code)").textSelection(.enabled) }
                    ForEach(state.data.invitations, id: \.id) { invitation in
                        Text("\(invitation.email) · expires \(invitation.expiresAt)")
                        Button("Revoke invitation") { spaceAction("revoke_invitation", space: active.id, invitation: invitation.id) }
                    }
                }
                Button("Leave \(active.name)", role: .destructive) { pendingSpaceAction = { spaceAction("leave", space: active.id) } }
            }
        }.disabled(state.busy)
    }

    private func accountKey(_ account: SpaceAccountChoice) -> String { "\(account.spaceId)/\(account.accountId)" }
    private func transferAction(_ transfer: LinkedTransfer?, delete: Bool) {
        let from = model.spacesState.accounts.first { accountKey($0) == transferFrom }
        let to = model.spacesState.accounts.first { accountKey($0) == transferTo }
        guard let sourceSpace = transfer?.key.fromSpace ?? from?.spaceId,
              let sourceAccount = transfer?.debit.accountId ?? from?.accountId,
              let targetSpace = transfer?.key.toSpace ?? to?.spaceId,
              let targetAccount = transfer?.credit.accountId ?? to?.accountId else { return }
        Task {
            do { spaceMessage = try await asyncFunction(for: model.screen.transferAction(linkId: transfer?.key.linkId ?? transferLink, fromSpace: sourceSpace, fromAccount: sourceAccount, toSpace: targetSpace, toAccount: targetAccount, title: transferTitle, amount: transferAmount, date: transferDate, delete: delete)) }
            catch { spaceMessage = error.localizedDescription }
        }
    }
    private var transfersSection: some View {
        Section("Linked space transfers") {
            Text("Open one of the spaces first. Sync both spaces to this device before editing a pair. Enter both sides in the same currency; currency conversion is not supported.")
            ForEach(model.spacesState.transfers, id: \.key.linkId) { transfer in
                Text("\(transfer.debit.title ?? "Transfer") · \(transfer.debit.amount) · \(transfer.debit.date)")
                Button("Edit both sides") {
                    transferLink = transfer.key.linkId
                    transferFrom = "\(transfer.key.fromSpace)/\(transfer.debit.accountId)"
                    transferTo = "\(transfer.key.toSpace)/\(transfer.credit.accountId)"
                    transferTitle = transfer.debit.title ?? ""
                    transferAmount = String(transfer.debit.amount)
                    transferDate = transfer.debit.date.description
                }
                Button("Delete both sides", role: .destructive) { pendingSpaceAction = { transferAction(transfer, delete: true) } }
            }
            Text("Transfer ID: \(transferLink)").textSelection(.enabled)
            Button("Start new transfer") { transferLink = model.screen.createTransferId(); transferTitle = ""; transferAmount = "" }
            Picker("From", selection: $transferFrom) {
                Text("Choose account").tag("")
                ForEach(model.spacesState.accounts, id: \.self) { account in Text(account.label).tag(accountKey(account)) }
            }
            Picker("To", selection: $transferTo) {
                Text("Choose account").tag("")
                ForEach(model.spacesState.accounts, id: \.self) { account in Text(account.label).tag(accountKey(account)) }
            }
            TextField("Transfer title", text: $transferTitle)
            TextField("Amount", text: $transferAmount)
            TextField("Date (YYYY-MM-DD)", text: $transferDate)
            Button("Save both sides") { transferAction(nil, delete: false) }.disabled(transferFrom.isEmpty || transferTo.isEmpty || transferTitle.isEmpty || transferAmount.isEmpty)
        }.disabled(model.spacesState.busy)
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
                                disabledReason: guest.disabledReason, guestUpgrade: true) { mode, email, password in
                    try? await asyncFunction(for: model.screen.submitCredentials(mode: mode, email: email, password: password))
                }
                if let pending = guest.pendingConfirmation {
                    Text(pending).font(.footnote).foregroundStyle(.secondary)
                    Button("Check confirmation") {
                        Task {
                            accountMessage = try? await asyncFunction(for: model.screen.refreshAccountStatus())
                        }
                    }
                }
            } else if let signedIn = account as? SettingsAccountSignedIn {
                VStack(alignment: .leading, spacing: 4) {
                    Text(signedIn.title).font(.headline)
                }
                if let pending = signedIn.pendingConfirmation {
                    Text(pending).font(.footnote).foregroundStyle(.secondary)
                }
                if signedIn.needsPasswordSetup {
                    Text("Email confirmed. Set a password to sign in on another device.")
                        .font(.footnote).foregroundStyle(.secondary)
                    SecureField("New password", text: $password)
                        .textContentType(.newPassword)
                    Button("Set password") {
                        Task {
                            accountMessage = try? await asyncFunction(for: model.screen.setPassword(password: password))
                            if accountMessage == "Password set." { password = "" }
                        }
                    }
                    .disabled(password.isEmpty)
                }
            } else if let selfHosted = account as? SettingsAccountSelfHosted {
                VStack(alignment: .leading, spacing: 4) {
                    Text(selfHosted.TITLE).font(.headline)
                    Text(selfHosted.BODY).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            if let accountMessage {
                Text(accountMessage).font(.footnote).foregroundStyle(.secondary)
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
            Text("Live bank connections are deferred and unavailable")
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
    @State private var analyticsHost = ""
    @State private var analyticsProjectToken = ""

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
            if analyticsHost.isEmpty { analyticsHost = screen.analyticsHost }
            if analyticsProjectToken.isEmpty { analyticsProjectToken = screen.analyticsProjectToken }
        }

        Section("PostHog project") {
            TextField("HTTPS host", text: $analyticsHost)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Project token", text: $analyticsProjectToken)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Save PostHog project") {
                toast = Toast(screen.saveAnalyticsConfig(host: analyticsHost, projectToken: analyticsProjectToken)
                    ? "PostHog project saved" : "Enter an HTTPS host and project token")
            }
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
