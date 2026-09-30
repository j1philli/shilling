import SwiftUI
import QuickLook
import KotlinModules
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCore

/// Native receipt editor: add a receipt from the camera, photos or Files, or edit one's details
/// and attached transaction. Driven by the shared `ReceiptEditorViewModel`.
struct ReceiptEditorScreen: View {
    @StateObject private var model: FlowModel<ReceiptEditorUiState, ReceiptEditorScreenModel>
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var amount = ""
    @State private var notes = ""
    @State private var loaded = false
    @State private var toast: Toast?
    @State private var preview: URL?
    @State private var picking = false
    let launchCamera: Bool
    let onDone: (Toast) -> Void

    init(receiptId: String?, launchCamera: Bool = false, onDone: @escaping (Toast) -> Void) {
        let screen = ReceiptEditorScreenModel(receiptId: receiptId)
        _model = StateObject(wrappedValue: FlowModel(screen: screen, initial: screen.state, flow: screen.stateFlow))
        self.launchCamera = launchCamera
        self.onDone = onDone
    }

    private var screen: ReceiptEditorScreenModel { model.screen }

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
            onSave: { await finish(screen.save()) },
            onDelete: { await finish(screen.delete()) }
        ) {
            if state.isNew {
                Section {
                    ReceiptSourceButtons(launchCamera: launchCamera) { file in
                        screen.setFile(fileName: file.name, data: file.data)
                        if name.isEmpty { name = file.name }
                    }
                } header: {
                    Text("File")
                } footer: {
                    Text(state.fileLabel ?? state.noFileLabel)
                }
                Section {
                    TextField("Name", text: $name)
                        .onChange(of: name) { _, value in screen.setName(value: value) }
                }
            } else {
                Section {
                    Button { open() } label: { Label("Open receipt", systemImage: "eye") }
                }
            }

            Section {
                Toggle("Receipt date", isOn: Binding(
                    get: { f.receiptDateEpochDay != nil },
                    set: { on in
                        if on { screen.setReceiptDate(epochDay: DateBridge.epochDay(from: Date())) } else { screen.clearReceiptDate() }
                    }
                ))
                if let day = f.receiptDateEpochDay {
                    DatePicker("Date", selection: Binding(
                        get: { DateBridge.date(fromEpochDay: day.int64Value) },
                        set: { screen.setReceiptDate(epochDay: DateBridge.epochDay(from: $0)) }
                    ), displayedComponents: .date)
                }
                LabeledContent("Amount") {
                    TextField("Optional", text: $amount)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: amount) { _, value in screen.setAmountText(value: value) }
                }
                TextField("Notes", text: $notes, axis: .vertical)
                    .lineLimit(2...6)
                    .onChange(of: notes) { _, value in screen.setNotes(value: value) }
            }

            Section("Transaction") {
                Text(state.attachmentLabel ?? state.notAttachedLabel)
                    .foregroundStyle(state.attachmentLabel == nil ? .secondary : .primary)
                Button(state.attachLabel) { picking = true }
                if state.attachmentLabel != nil {
                    Button("Detach", role: .destructive) { detach() }
                }
            }
        }
        .sheet(isPresented: $picking, onDismiss: { screen.setPickerQuery(value: "") }) {
            AttachTransactionSheet(screen: screen) { postingId in
                picking = false
                attach(postingId)
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
        let f = model.state.fields
        name = f.name
        amount = f.amountText
        notes = f.notes
    }

    private func finish(_ call: @escaping NativeSuspend<String?, Error, KotlinUnit>) async {
        let result: String?? = try? await asyncFunction(for: call)
        if let message = result ?? nil {
            onDone(Toast(message))
            dismiss()
        }
    }

    private func open() {
        Task {
            let result: String?? = try? await asyncFunction(for: screen.previewPath())
            if let path = result ?? nil {
                preview = URL(fileURLWithPath: path)
            } else {
                toast = Toast("Couldn't open \(model.state.title)")
            }
        }
    }

    private func attach(_ postingId: String) {
        Task {
            let result: String?? = try? await asyncFunction(for: screen.attach(postingId: postingId))
            if let message = result ?? nil { toast = Toast(message) }
        }
    }

    private func detach() {
        Task {
            let result: UndoHandle?? = try? await asyncFunction(for: screen.detach())
            if let handle = result ?? nil {
                toast = Toast(handle.message, actionLabel: "Undo") {
                    Task { _ = try? await asyncFunction(for: handle.undo()) }
                }
            }
        }
    }
}

/// Searchable list of recent transactions to attach a receipt to.
private struct AttachTransactionSheet: View {
    let screen: ReceiptEditorScreenModel
    let onSelect: (String) -> Void
    @State private var picker: AttachPickerUiState
    @State private var query = ""
    @Environment(\.dismiss) private var dismiss

    init(screen: ReceiptEditorScreenModel, onSelect: @escaping (String) -> Void) {
        self.screen = screen
        self.onSelect = onSelect
        _picker = State(initialValue: screen.picker)
    }

    var body: some View {
        NavigationStack {
            List {
                if picker.candidates.isEmpty {
                    ContentUnavailableView(picker.emptyMessage, systemImage: "tray")
                        .listRowBackground(Color.clear)
                }
                ForEach(picker.candidates, id: \.postingId) { candidate in
                    Button { onSelect(candidate.postingId) } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(candidate.title).foregroundStyle(.primary).lineLimit(1)
                                Text(candidate.supporting).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer(minLength: 8)
                            Text(candidate.amount).foregroundStyle(candidate.type.amountColor).fixedSize()
                        }
                        .contentShape(Rectangle())
                        .accessibilityElement(children: .combine)
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle(picker.title)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
            .onChange(of: query) { _, text in screen.setPickerQuery(value: text) }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .task {
            do {
                for try await value in asyncSequence(for: screen.pickerFlow) { picker = value }
            } catch {}
        }
    }
}
