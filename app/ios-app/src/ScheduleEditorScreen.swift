import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCore

/// Native schedule editor, driven by the shared `ScheduleEditorViewModel`.
struct ScheduleEditorScreen: View {
    @StateObject private var model: FlowModel<ScheduleEditorUiState, ScheduleEditorScreenModel>
    let onDone: (String) -> Void

    init(scheduleId: String?, presetType: ScheduleType?, onDone: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: FlowModel(
            create: { ScheduleEditorScreenModel(scheduleId: scheduleId, presetType: presetType) }, state: { $0.state }, flow: { $0.stateFlow }
        ))
        self.onDone = onDone
    }

    var body: some View {
        Group {
            if model.state.load == .ready {
                ScheduleEditorForm(state: model.state, screen: model.screen, onDone: onDone)
            } else {
                EditorChrome(title: model.state.title, load: model.state.load,
                             missingMessage: model.state.missingMessage, saveEnabled: false,
                             delete: nil, showsKeyboardDone: false,
                             onSave: {}, onDelete: {}) { EmptyView() }
            }
        }
        .task { await model.observe() }
    }
}

/// Create local drafts with the ready snapshot, avoiding another form update
/// from onAppear while the navigation push is laying out its rows.
private struct ScheduleEditorForm: View {
    let state: ScheduleEditorUiState
    let screen: ScheduleEditorScreenModel
    let onDone: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var titleDraft: EditorTextDraft
    @State private var amount: String
    @State private var monthDay: String
    @State private var notes: String
    @FocusState private var focusedField: Field?
    private enum Field: Hashable { case monthDay, notes }

    init(state: ScheduleEditorUiState, screen: ScheduleEditorScreenModel, onDone: @escaping (String) -> Void) {
        self.state = state
        self.screen = screen
        self.onDone = onDone
        let draft = EditorTextDraft()
        draft.value = state.fields.title
        _titleDraft = State(initialValue: draft)
        _amount = State(initialValue: state.fields.amountText)
        _monthDay = State(initialValue: state.fields.monthDayText)
        _notes = State(initialValue: state.fields.notes)
    }

    var body: some View {
        let f = state.fields
        EditorChrome(
            title: state.title,
            load: state.load,
            missingMessage: state.missingMessage,
            saveEnabled: state.saveEnabled,
            delete: state.deleteConfirm,
            showsKeyboardDone: focusedField != nil,
            onSave: {
                screen.setTitle(value: titleDraft.value)
                await finish(screen.save())
            },
            onDelete: { await finish(screen.delete()) }
        ) {
            Section {
                Picker("Type", selection: Binding(get: { f.type }, set: { screen.setType(value: $0) })) {
                    ForEach(state.types, id: \.self) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                EditorNativeTextField(label: "Name", value: f.title, prompt: state.namePlaceholder,
                                      draft: titleDraft) { screen.setTitle(value: $0) }
                LabeledContent("Amount") {
                    EditorNativeTextField(label: "Amount", value: amount, prompt: "0.00",
                                          keyboard: .decimalPad, trailing: true) {
                        amount = $0
                        screen.setAmountText(value: $0)
                    }
                }
                EditorChoicePicker(state.accountLabel, selection: Binding(get: { f.accountId }, set: { screen.setAccount(id: $0) }),
                                   choices: state.accounts)
                .disabled(state.accounts.isEmpty)
                if state.isTransfer {
                    EditorChoicePicker("To account", selection: Binding(get: { f.toAccountId }, set: { screen.setToAccount(id: $0) }),
                                       choices: state.toAccountChoices, noneLabel: "Choose…")
                    .disabled(state.accounts.count < 2)
                }
                EditorChoicePicker("Category", selection: Binding(get: { f.categoryId }, set: { screen.setCategory(id: $0) }),
                                   choices: state.categories, noneLabel: "Uncategorized")
            } footer: {
                if let hint = state.accountHint ?? (state.isTransfer ? state.toAccountHint : nil) {
                    Text(hint)
                }
            }

            Section {
                EditorChoicePicker("Repeats", selection: Binding(
                    get: { f.frequency.name },
                    set: { name in
                        if let frequency = state.frequencies.first(where: { $0.frequency.name == name }) {
                            screen.setFrequency(value: frequency.frequency)
                        }
                    }
                ), choices: state.frequencies.map { Choice(id: $0.frequency.name, label: $0.label) })
                DatePicker(state.startLabel, selection: Binding(
                    get: { DateBridge.date(fromEpochDay: f.startEpochDay) },
                    set: { screen.setStart(epochDay: DateBridge.epochDay(from: $0)) }
                ), displayedComponents: .date)
                if let unit = state.intervalUnit {
                    let interval = Int(f.intervalText) ?? 1
                    Stepper("Every \(interval) \(unit)", value: Binding(
                        get: { interval },
                        set: { screen.setInterval(value: Int32(max(1, $0))) }
                    ), in: 1...99)
                }
                timingDetails(state)
                if state.showsEndDate {
                    Toggle("Ends", isOn: Binding(
                        get: { f.endEpochDay != nil },
                        set: { on in
                            if on { screen.setEnd(epochDay: f.startEpochDay) } else { screen.clearEnd() }
                        }
                    ))
                    if let end = f.endEpochDay {
                        DatePicker("Ends on", selection: Binding(
                            get: { DateBridge.date(fromEpochDay: end.int64Value) },
                            set: { screen.setEnd(epochDay: DateBridge.epochDay(from: $0)) }
                        ), displayedComponents: .date)
                    }
                }
            } header: {
                Text("Timing")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(state.nextOccurrence)
                    ForEach(state.errors, id: \.self) { Text($0).foregroundStyle(.red) }
                }
            }

            Section {
                Toggle("Auto-pay", isOn: Binding(get: { f.autoPay }, set: { screen.setAutoPay(value: $0) }))
                TextField("Notes", text: $notes, axis: .vertical)
                    .focused($focusedField, equals: .notes)
                    .lineLimit(2...6)
                    .onChange(of: notes) { _, value in screen.setNotes(value: value) }
            } header: {
                Text("Details")
            } footer: {
                Text(state.autoPayHint)
            }
        }
    }

    @ViewBuilder
    private func timingDetails(_ state: ScheduleEditorUiState) -> some View {
        let f = state.fields
        switch f.frequency {
        case .weekly:
            HStack(spacing: 6) {
                ForEach(state.weekdays, id: \.index) { day in
                    let on = (Int(state.effectiveWeekdayMask) & (1 << Int(day.index))) != 0
                    Button(String(day.shortLabel.prefix(2))) { screen.toggleWeekday(index: day.index) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .tint(on ? .accentColor : .secondary)
                        .font(.footnote.weight(on ? .semibold : .regular))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .accessibilityLabel(day.fullLabel)
                        .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        case .monthlyByDay:
            Toggle("Last day of the month", isOn: Binding(get: { f.lastDay }, set: { screen.setLastDay(value: $0) }))
            if !f.lastDay {
                LabeledContent("Day of month") {
                    TextField("1–31", text: $monthDay)
                        .focused($focusedField, equals: .monthDay)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(state.monthDayError ? .red : .primary)
                        .onChange(of: monthDay) { _, value in screen.setMonthDayText(value: value) }
                }
            }
        case .monthlyByNthWeekday:
            EditorChoicePicker("Week", selection: Binding(
                get: { String(f.nth) },
                set: { if let value = $0.flatMap(Int32.init) { screen.setNth(value: value) } }
            ), choices: state.nthOptions)
            EditorChoicePicker("Day", selection: Binding(
                get: { String(f.nthWeekdayIndex) },
                set: { if let index = $0.flatMap(Int32.init) { screen.setNthWeekday(index: index) } }
            ), choices: state.weekdays.map { Choice(id: String($0.index), label: $0.fullLabel) })
        default:
            EmptyView()
        }
    }

    private func finish(_ call: @escaping NativeSuspend<String?, Error, KotlinUnit>) async {
        let result: String?? = try? await asyncFunction(for: call)
        if let message = result ?? nil {
            onDone(message)
            dismiss()
        }
    }
}
