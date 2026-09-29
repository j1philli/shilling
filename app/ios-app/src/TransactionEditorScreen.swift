import SwiftUI
import QuickLook
import KotlinModules
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCore

/// Native transaction editor (create, or view/edit a recorded one with its receipts), driven by
/// the shared `TransactionEditorViewModel`.
struct TransactionEditorScreen: View {
    @StateObject private var model: FlowModel<TransactionEditorUiState, TransactionEditorScreenModel>
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var amount = ""
    @State private var loaded = false
    @State private var toast: Toast?
    @State private var preview: URL?
    let onDone: (Toast) -> Void

    init(postingId: String?, onDone: @escaping (Toast) -> Void) {
        let screen = TransactionEditorScreenModel(postingId: postingId)
        _model = StateObject(wrappedValue: FlowModel(screen: screen, initial: screen.state, flow: screen.stateFlow))
        self.onDone = onDone
    }

    private var screen: TransactionEditorScreenModel { model.screen }

    var body: some View {
        let state = model.state
        let f = state.fields
        EditorChrome(
            title: state.title,
            subtitle: state.subtitle,
            load: state.load,
            missingMessage: state.missingMessage,
            saveEnabled: state.saveEnabled,
            delete: state.deleteConfirm,
            onSave: {
                let result: String?? = try? await asyncFunction(for: screen.save())
                if let message = result ?? nil { finish(Toast(message)) }
            },
            onDelete: {
                let result: UndoHandle?? = try? await asyncFunction(for: screen.delete())
                if let handle = result ?? nil { finish(undoToast(handle)) }
            }
        ) {
            Section {
                if state.typeEditable {
                    Picker("Type", selection: Binding(get: { f.type }, set: { screen.setType(value: $0) })) {
                        ForEach(state.types, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                TextField("Description", text: $title)
                    .onChange(of: title) { _, value in screen.setTitle(value: value) }
                LabeledContent("Amount") {
                    TextField("0.00", text: $amount)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: amount) { _, value in screen.setAmountText(value: value) }
                }
                DatePicker("Date", selection: Binding(
                    get: { DateBridge.date(fromEpochDay: f.dateEpochDay) },
                    set: { screen.setDate(epochDay: DateBridge.epochDay(from: $0)) }
                ), displayedComponents: .date)
            } footer: {
                if let hint = state.dateHint { Text(hint) }
            }

            Section {
                Picker(state.accountLabel, selection: Binding(get: { f.accountId }, set: { screen.setAccount(id: $0) })) {
                    ForEach(state.accounts, id: \.id) { Text($0.label).tag(Optional($0.id)) }
                }
                .disabled(state.accounts.isEmpty)
                if state.isTransfer {
                    Picker("To account", selection: Binding(get: { f.toAccountId }, set: { screen.setToAccount(id: $0) })) {
                        Text("Choose…").tag(String?.none)
                        ForEach(state.toAccountChoices, id: \.id) { Text($0.label).tag(Optional($0.id)) }
                    }
                    .disabled(state.toAccountHint != nil)
                }
                Picker("Category", selection: Binding(get: { f.categoryId }, set: { screen.setCategory(id: $0) })) {
                    Text(state.categoryNoneLabel).tag(String?.none)
                    ForEach(state.categories, id: \.id) { Text($0.label).tag(Optional($0.id)) }
                }
            } footer: {
                if let hint = state.accountHint ?? (state.isTransfer ? state.toAccountHint : nil) {
                    Text(hint)
                }
            }

            if state.showsReceipts {
                Section("Receipts") {
                    if state.receipts.isEmpty {
                        Text("No receipts attached.").foregroundStyle(.secondary)
                    }
                    ForEach(state.receipts, id: \.id) { receipt in
                        Button { open(receipt) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(receipt.name).foregroundStyle(.primary).lineLimit(1)
                                Text(receipt.addedLabel).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button("Detach", systemImage: "link.badge.minus") { detach(receipt) }
                                .tint(.orange)
                        }
                        .contextMenu {
                            Button("Open", systemImage: "eye") { open(receipt) }
                            Button("Detach", systemImage: "link.badge.minus") { detach(receipt) }
                        }
                    }
                    ReceiptSourceButtons { file in attach(file) }
                }
            }
        }
        .quickLookPreview($preview)
        .overlay(alignment: .bottom) { ToastView(toast: $toast) }
        .task { await model.observe() }
        .onChange(of: state.load) { _, _ in syncFields() }
        .onAppear { syncFields() }
    }

    private func syncFields() {
        guard !loaded, model.state.load == .ready else { return }
        loaded = true
        title = model.state.fields.title
        amount = model.state.fields.amountText
    }

    private func finish(_ toast: Toast) {
        onDone(toast)
        dismiss()
    }

    private func undoToast(_ handle: UndoHandle) -> Toast {
        Toast(handle.message, actionLabel: "Undo") {
            Task { _ = try? await asyncFunction(for: handle.undo()) }
        }
    }

    private func open(_ receipt: AttachedReceiptUi) {
        Task {
            let path: String?? = try? await asyncFunction(for: screen.previewPath(receiptId: receipt.id))
            if let path = path ?? nil {
                preview = URL(fileURLWithPath: path)
            } else {
                toast = Toast("Couldn't open \(receipt.name)")
            }
        }
    }

    private func detach(_ receipt: AttachedReceiptUi) {
        Task {
            let result: UndoHandle?? = try? await asyncFunction(for: screen.detachReceipt(receiptId: receipt.id))
            if let handle = result ?? nil { toast = undoToast(handle) }
        }
    }

    private func attach(_ file: PickedFile) {
        Task {
            let result: String?? = try? await asyncFunction(for: screen.attachReceipt(fileName: file.name, data: file.data))
            if let message = result ?? nil { toast = Toast(message) }
        }
    }
}
