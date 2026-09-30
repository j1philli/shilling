import SwiftUI
import KotlinModules

/// Email + password rows (inside a `Form` section): sign in, or create an account. Used by
/// Welcome and Settings; `submit` returns the outcome to show, or nil if the call failed.
struct CredentialsForm: View {
    let enabled: Bool
    let disabledReason: String?
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
        submit: @escaping (HostedCredentialsMode, String, String) async -> CredentialsOutcome?
    ) {
        _mode = State(initialValue: initialMode)
        self.enabled = enabled
        self.disabledReason = disabledReason
        self.submit = submit
    }

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
            send()
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

    private func send() {
        submitting = true
        Task {
            defer { submitting = false }
            guard let outcome = await submit(mode, email, password) else { return }
            message = outcome.message
            if outcome.succeeded {
                email = ""
                password = ""
            }
            confirmEmailMessage = outcome.confirmEmailMessage
        }
    }
}
