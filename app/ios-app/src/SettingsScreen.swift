import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync

/// Observes the shared `SettingsViewModel` (through `SettingsScreenModel`) for SwiftUI.
@MainActor
final class SettingsModel: ObservableObject {
    @Published private(set) var state: SettingsUiState
    let screen = SettingsScreenModel()

    init() {
        state = screen.state
    }

    func observe() async {
        do {
            for try await value in asyncSequence(for: screen.stateFlow) {
                state = value
            }
        } catch {}
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
                CredentialsForm(screen: model.screen, enabled: guest.authAvailable,
                                disabledReason: guest.disabledReason, toast: $toast)
                if let pending = guest.pendingConfirmation {
                    Text(pending).font(.footnote).foregroundStyle(.secondary)
                }
            } else if let signedIn = account as? SettingsAccountSignedIn {
                VStack(alignment: .leading, spacing: 4) {
                    Text(signedIn.title).font(.headline)
                    Text(signedIn.planLabel).font(.subheadline).foregroundStyle(.secondary)
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

// MARK: - Account form

/// Email + password form: create an account (upgrading the guest) or sign in.
private struct CredentialsForm: View {
    let screen: SettingsScreenModel
    let enabled: Bool
    let disabledReason: String?
    @Binding var toast: Toast?

    @State private var mode: HostedCredentialsMode = .createAccount
    @State private var email = ""
    @State private var password = ""
    @State private var submitting = false
    @State private var message: String?
    @State private var confirmEmailMessage: String?

    var body: some View {
        Text(CredentialsCopy.shared.prompt(mode: mode))
            .font(.subheadline)
            .foregroundStyle(.secondary)
        if let disabledReason {
            Text(disabledReason).font(.footnote).foregroundStyle(.secondary)
        }
        TextField("Email", text: $email)
            .textContentType(.emailAddress)
            .keyboardType(.emailAddress)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        SecureField("Password", text: $password)
            .textContentType(mode == .signIn ? .password : .newPassword)
        Button {
            submit()
        } label: {
            HStack {
                if submitting { ProgressView() }
                Text(CredentialsCopy.shared.submitLabel(mode: mode, signInLabel: "Sign in"))
            }
        }
        .disabled(!enabled || submitting || email.trimmingCharacters(in: .whitespaces).isEmpty || password.isEmpty)
        Button(CredentialsCopy.shared.switchLabel(mode: mode)) {
            mode = CredentialsCopy.shared.other(mode: mode)
            message = nil
        }
        .disabled(!enabled || submitting)
        if let message {
            Text(message).font(.footnote)
        }
        EmptyView()
            .alert(CredentialsCopy.shared.CONFIRM_EMAIL_TITLE, isPresented: Binding(
                get: { confirmEmailMessage != nil },
                set: { if !$0 { confirmEmailMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(confirmEmailMessage ?? "")
            }
    }

    private func submit() {
        submitting = true
        Task {
            defer { submitting = false }
            guard let outcome = try? await asyncFunction(
                for: screen.submitCredentials(mode: mode, email: email, password: password)
            ) else { return }
            message = outcome.message
            if outcome.succeeded {
                email = ""
                password = ""
            }
            confirmEmailMessage = outcome.confirmEmailMessage
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
