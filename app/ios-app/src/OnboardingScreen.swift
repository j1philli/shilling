import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync

/// Observes `AppRoot.phase` (onboarding, starting, main app) for SwiftUI.
@MainActor
final class AppPhaseModel: ObservableObject {
    @Published private(set) var spaceId: String = AppRoot.shared.spaceId
    @Published private(set) var phase: AppPhase = AppRoot.shared.phase

    func observeSpace() async {
        do { for try await value in asyncSequence(for: AppRoot.shared.spaceIdFlow) { spaceId = value } } catch {}
    }

    func observe() async {
        do {
            for try await value in asyncSequence(for: AppRoot.shared.phaseFlow) {
                phase = value
            }
        } catch {}
    }
}

/// Native Welcome flow: continue as a guest, sign in / sign up, or connect a self-hosted server.
/// Driven by the shared `OnboardingViewModel`.
struct OnboardingScreen: View {
    @StateObject private var model: FlowModel<OnboardingUiState, OnboardingScreenModel>

    init() {
        let screen = OnboardingScreenModel()
        _model = StateObject(wrappedValue: FlowModel(screen: screen, initial: screen.state, flow: screen.stateFlow))
    }

    private var screen: OnboardingScreenModel { model.screen }

    var body: some View {
        let state = model.state
        NavigationStack(path: Binding(
            get: { state.route == .landing || state.selfHostedOnly ? [] : [state.route] },
            set: { if $0.isEmpty { screen.back() } }
        )) {
            Group {
                if state.selfHostedOnly {
                    SelfHostedForm(state: state, screen: screen)
                } else {
                    landing(state)
                }
            }
            .navigationDestination(for: OnboardingRoute.self) { route in
                switch route {
                case .login: login(state)
                default: SelfHostedForm(state: state, screen: screen)
                }
            }
        }
        .alert(state.destructiveConfirm?.title ?? "", isPresented: Binding(
            get: { state.destructiveConfirm != nil },
            set: { if !$0 { screen.dismissDestructive() } }
        )) {
            Button(state.destructiveConfirm?.confirmLabel ?? "", role: .destructive) { screen.confirmDestructive() }
            Button(state.keepLabel, role: .cancel) { screen.dismissDestructive() }
        } message: {
            Text(state.destructiveConfirm?.message ?? "")
        }
        .task { await model.observe() }
    }

    private func landing(_ state: OnboardingUiState) -> some View {
        ScrollView {
            VStack(spacing: 20) {
                Image("ShortcutLaunchIcon")
                    .resizable()
                    .interpolation(.high)
                    .frame(width: 88, height: 88)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .accessibilityLabel("Shilling logo")
                    .padding(.top, 32)
                VStack(spacing: 8) {
                    Text(state.welcomeTitle).font(.largeTitle.bold()).multilineTextAlignment(.center)
                    Text(state.welcomeMessage)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                if let title = state.heldDataTitle {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(title).font(.subheadline.weight(.semibold))
                        Text(state.heldDataMessage).font(.footnote).foregroundStyle(.secondary)
                        Button(state.heldDataAction) { screen.open(route: .login) }
                            .font(.subheadline.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                if state.analyticsAvailable {
                    Toggle("Share usage events", isOn: Binding(
                        get: { state.analyticsConsent },
                        set: { screen.setAnalyticsConsent(value: $0) }
                    ))
                    Text(state.analyticsNotice).font(.footnote).foregroundStyle(.secondary)
                }
                OptionCard(option: state.getStarted) { screen.getStarted() }
                OptionCard(option: state.signIn) { screen.open(route: .login) }
                Button(state.selfHostedLabel) { screen.open(route: .selfHosted) }
                    .font(.footnote)
                    .padding(.top, 8)
            }
            .frame(maxWidth: 540)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemGroupedBackground))
    }

    private func login(_ state: OnboardingUiState) -> some View {
        Form {
            Section {
                CredentialsForm(initialMode: .createAccount, enabled: state.authReady, disabledReason: state.authDisabledReason) { mode, email, password in
                    try? await asyncFunction(for: screen.submitCredentials(mode: mode, email: email, password: password))
                }
            } header: {
                Text(state.loginSubtitle).textCase(nil)
            }
        }
        .navigationTitle(state.signIn.title)
    }
}

private struct OptionCard: View {
    let option: OnboardingOption
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(option.title).font(.headline).foregroundStyle(.primary)
                    Text(option.body).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").foregroundStyle(.tint)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

private struct SelfHostedForm: View {
    let state: OnboardingUiState
    let screen: OnboardingScreenModel
    @State private var url = ""
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                TextField("Server URL", text: $url)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: url) { _, value in screen.setSelfHostedUrl(value: value) }
                    .onSubmit { screen.continueSelfHosted() }
            } header: {
                Text(state.selfHostedMessage).textCase(nil)
            } footer: {
                Text(state.selfHostedHint).foregroundStyle(state.selfHostedError == nil ? Color.secondary : Color.red)
            }
            if state.analyticsAvailable {
                Section {
                    Toggle("Share usage events", isOn: Binding(
                        get: { state.analyticsConsent },
                        set: { screen.setAnalyticsConsent(value: $0) }
                    ))
                    Text(state.analyticsNotice).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Section {
                Button {
                    screen.continueSelfHosted()
                } label: {
                    HStack {
                        if state.validatingSelfHosted { ProgressView() }
                        Text("Continue")
                    }
                }
                .disabled(!state.canContinueSelfHosted)
            }
        }
        .navigationTitle(state.selfHostedLabel)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            url = state.selfHostedUrl
        }
    }
}
