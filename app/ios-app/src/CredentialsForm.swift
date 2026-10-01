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
    @State private var emailNotice: String?

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
        if let emailNotice {
            Label("Check your email", systemImage: "envelope")
                .font(.headline)
            Text(emailNotice)
                .font(.subheadline)
            Button("Use another email") {
                self.emailNotice = nil
                message = nil
                mode = .createAccount
            }
        } else {
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
                .disabled(submitting)
                .onChange(of: email) { _, _ in
                    mode = .createAccount
                    password = ""
                    message = nil
                }
            if mode == .signIn {
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .disabled(submitting)
            }
            Button {
                send()
            } label: {
                HStack {
                    if submitting { ProgressView() }
                    Text(submitting ? "Please wait…" : "Continue")
                }
            }
            .disabled(!enabled || submitting || email.trimmingCharacters(in: .whitespaces).isEmpty)
            if let message {
                Text(message).font(.footnote)
            }
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
            guard let outcome = await submit(mode, email, password) else {
                message = "Could not complete the request. Please try again."
                return
            }
            message = outcome.message
            if outcome.showPasswordInput {
                mode = .signIn
                password = ""
            } else if outcome.emailSent {
                emailNotice = outcome.confirmEmailMessage
                    ?? "We sent a sign-in link to \(email). Open it on this device to continue."
                password = ""
            } else if outcome.succeeded {
                password = ""
            }
        }
    }

}
