import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCore

/// Observes one Kotlin `StateFlow` for SwiftUI and closes its screen model when released.
@MainActor
final class FlowModel<State, Screen: IosViewModelHost>: ObservableObject {
    @Published private(set) var state: State
    let screen: Screen
    private let flow: NativeFlow<State, Error, KotlinUnit>

    init(screen: Screen, initial: State, flow: @escaping NativeFlow<State, Error, KotlinUnit>) {
        self.screen = screen
        self.state = initial
        self.flow = flow
    }

    func observe() async {
        do {
            for try await value in asyncSequence(for: flow) {
                state = value
            }
        } catch {}
    }

    deinit {
        screen.close()
    }
}

/// Save / delete chrome shared by the native editors: Save in the toolbar, a destructive delete
/// with confirmation, and loading / deleted states.
struct EditorChrome<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    let load: EditorLoad
    let missingMessage: String
    let saveEnabled: Bool
    let delete: ConfirmCopy?
    let onSave: () async -> Void
    let onDelete: () async -> Void
    @ViewBuilder let content: Content

    @State private var confirmingDelete = false
    @State private var working = false

    var body: some View {
        Group {
            switch load {
            case .loading:
                ProgressView()
            case .missing:
                ContentUnavailableView(missingMessage, systemImage: "trash")
            default:
                Form {
                    content
                    if let delete {
                        Section {
                            Button(delete.confirmLabel, role: .destructive) { confirmingDelete = true }
                        }
                        .confirmationDialog(delete.title, isPresented: $confirmingDelete, titleVisibility: .visible) {
                            Button(delete.confirmLabel, role: .destructive) {
                                Task { await onDelete() }
                            }
                        } message: {
                            Text(delete.message)
                        }
                    }
                }
                // Number pads have no return key.
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .navigationTitle(title)
        .modifier(NavigationSubtitle(text: subtitle))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") {
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    working = true
                    Task {
                        await onSave()
                        working = false
                    }
                }
                .disabled(!saveEnabled || working || load != .ready)
            }
        }
    }
}

/// `navigationSubtitle` where available (iOS 26+).
private struct NavigationSubtitle: ViewModifier {
    let text: String?

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), let text {
            content.navigationSubtitle(text)
        } else {
            content
        }
    }
}

// MARK: - Category

struct CategoryEditorScreen: View {
    @StateObject private var model: FlowModel<CategoryEditorUiState, CategoryEditorScreenModel>
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var loaded = false
    let onDone: (String) -> Void

    init(categoryId: String?, onDone: @escaping (String) -> Void) {
        let screen = CategoryEditorScreenModel(categoryId: categoryId)
        _model = StateObject(wrappedValue: FlowModel(screen: screen, initial: screen.state, flow: screen.stateFlow))
        self.onDone = onDone
    }

    var body: some View {
        let state = model.state
        EditorChrome(
            title: state.title,
            load: state.load,
            missingMessage: state.missingMessage,
            saveEnabled: state.saveEnabled,
            delete: state.deleteConfirm,
            onSave: { await finish(model.screen.save()) },
            onDelete: { await finish(model.screen.delete()) }
        ) {
            Section {
                TextField("Name", text: $name, prompt: Text("e.g. Groceries"))
                    .onChange(of: name) { _, value in model.screen.setName(value: value) }
            }
            Section("Color") {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 44))], spacing: 12) {
                    ForEach(state.swatches, id: \.hex) { swatch in
                        let selected = swatch.hex.caseInsensitiveCompare(state.color) == .orderedSame
                        Button { model.screen.setColor(hex: swatch.hex) } label: {
                            Circle()
                                .fill(Color(hex: swatch.hex) ?? .gray)
                                .frame(width: 36, height: 36)
                                .overlay {
                                    if selected {
                                        Image(systemName: "checkmark").font(.body.weight(.bold)).foregroundStyle(.white)
                                    }
                                }
                                .padding(3)
                                .overlay(Circle().stroke(selected ? Color.primary : .clear, lineWidth: 2))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(swatch.name)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .task { await model.observe() }
        .onChange(of: state.load) { _, _ in syncFields() }
        .onAppear { syncFields() }
    }

    private func syncFields() {
        guard !loaded, model.state.load == .ready else { return }
        loaded = true
        name = model.state.name
    }

    private func finish(_ call: @escaping NativeSuspend<String?, Error, KotlinUnit>) async {
        let result: String?? = try? await asyncFunction(for: call)
        if let message = result ?? nil {
            onDone(message)
            dismiss()
        }
    }
}

// MARK: - Account

struct AccountEditorScreen: View {
    @StateObject private var model: FlowModel<AccountEditorUiState, AccountEditorScreenModel>
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var balance = ""
    @State private var loaded = false
    let onDone: (String) -> Void

    init(accountId: String?, onDone: @escaping (String) -> Void) {
        let screen = AccountEditorScreenModel(accountId: accountId)
        _model = StateObject(wrappedValue: FlowModel(screen: screen, initial: screen.state, flow: screen.stateFlow))
        self.onDone = onDone
    }

    var body: some View {
        let state = model.state
        EditorChrome(
            title: state.title,
            load: state.load,
            missingMessage: state.missingMessage,
            saveEnabled: state.saveEnabled,
            delete: state.deleteConfirm,
            onSave: { await finish(model.screen.save()) },
            onDelete: { await finish(model.screen.delete()) }
        ) {
            Section {
                TextField("Name", text: $name, prompt: Text("e.g. Checking"))
                    .onChange(of: name) { _, value in model.screen.setName(value: value) }
            }
            Section {
                LabeledContent(state.balanceLabel) {
                    TextField("0.00", text: $balance)
                        .keyboardType(.numbersAndPunctuation)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: balance) { _, value in model.screen.setBalanceText(value: value) }
                }
            } footer: {
                Text(state.balanceHint)
            }
        }
        .task { await model.observe() }
        .onChange(of: state.load) { _, _ in syncFields() }
        .onAppear { syncFields() }
    }

    private func syncFields() {
        guard !loaded, model.state.load == .ready else { return }
        loaded = true
        name = model.state.name
        balance = model.state.balanceText
    }

    private func finish(_ call: @escaping NativeSuspend<String?, Error, KotlinUnit>) async {
        let result: String?? = try? await asyncFunction(for: call)
        if let message = result ?? nil {
            onDone(message)
            dismiss()
        }
    }
}
