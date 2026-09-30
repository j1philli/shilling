import SwiftUI
import KotlinModules

/// Email + password rows (inside a `Form` section): sign in, or create an account. Used by
/// Welcome and Settings; `submit` returns the outcome to show, or nil if the call failed.
struct CredentialsForm: View {
    let enabled: Bool
    let disabledReason: String?
    let guestUpgrade: Bool
    let submit: (HostedCredentialsMode, String, String) async -> CredentialsOutcome?

    @State private var mode: HostedCredentialsMode
    @State private var email = ""
    @State private var password = ""
    @State private var submitting = false
    @State private var message: String?
    @State private var confirmEmailMessage: String?

    init(
        initialMode: HostedCredentialsMode,
        enabled: Bool,
        disabledReason: String?,
        guestUpgrade: Bool = false,
        submit: @escaping (HostedCredentialsMode, String, String) async -> CredentialsOutcome?
    ) {
        _mode = State(initialValue: initialMode)
        self.enabled = enabled
        self.disabledReason = disabledReason
        self.guestUpgrade = guestUpgrade
        self.submit = submit
    }

    var body: some View {
        Text(CredentialsCopy.shared.prompt(mode: mode, guestUpgrade: guestUpgrade))
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
            .onChange(of: email) { _, _ in
                mode = .createAccount
                password = ""
                message = nil
            }
        if mode == .signIn {
            SecureField("Password", text: $password)
                .textContentType(.password)
        }
        Button {
            send()
        } label: {
            HStack {
                if submitting { ProgressView() }
                Text("Continue")
            }
        }
        .disabled(!enabled || submitting || email.trimmingCharacters(in: .whitespaces).isEmpty)
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

    private func send() {
        submitting = true
        Task {
            defer { submitting = false }
            if mode == .signIn && password.isEmpty {
                message = "Enter your password."
                return
            }
            guard let outcome = await submit(mode, email, password) else { return }
            message = outcome.message
            if outcome.showPasswordInput {
                mode = .signIn
                password = ""
            } else if outcome.succeeded {
                email = ""
                password = ""
            }
            confirmEmailMessage = outcome.confirmEmailMessage
        }
    }

}
