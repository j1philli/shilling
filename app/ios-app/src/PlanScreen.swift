import SwiftUI
import KotlinModules
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCore

/// Observes the shared Plan view models (through `PlanScreenModel`) for SwiftUI.
@MainActor
final class PlanModel: ObservableObject {
    @Published private(set) var overview: PlanOverviewUiState
    @Published private(set) var schedules: SchedulesUiState
    @Published private(set) var categories: CategoriesUiState
    @Published private(set) var accounts: AccountsUiState
    @Published private(set) var pendingRequest: PlanRequest?
    let screen = PlanScreenModel()

    init() {
        overview = screen.overviewState
        schedules = screen.schedulesState
        categories = screen.categoriesState
        accounts = screen.accountsState
        pendingRequest = screen.pendingRequest
    }

    func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.collect(self.screen.overviewStateFlow) { self.overview = $0 } }
            group.addTask { await self.collect(self.screen.schedulesStateFlow) { self.schedules = $0 } }
            group.addTask { await self.collect(self.screen.categoriesStateFlow) { self.categories = $0 } }
            group.addTask { await self.collect(self.screen.accountsStateFlow) { self.accounts = $0 } }
            group.addTask { await self.collect(self.screen.pendingRequestFlow) { self.pendingRequest = $0 } }
        }
    }

    private func collect<T>(_ flow: @escaping NativeFlow<T, Error, KotlinUnit>, _ apply: @escaping @MainActor (T) -> Void) async {
        do {
            for try await value in asyncSequence(for: flow) {
                apply(value)
            }
        } catch {}
    }

    /// Runs an undoable action and returns its toast (with Undo), if it did anything.
    func perform(_ action: @escaping () -> NativeSuspend<String?, Error, KotlinUnit>) async -> Toast? {
        guard let message = try? await asyncFunction(for: action()) ?? nil else { return nil }
        return Toast(message, actionLabel: "Undo") { [screen] in
            Task { _ = try? await asyncFunction(for: screen.undoLast()) }
        }
    }

    deinit {
        screen.close()
    }
}

/// Native Plan: Overview (week/month, by day or by category) plus Schedules, Categories and
/// Accounts, with native editors pushed onto its navigation stack.
struct PlanScreen: View {
    @StateObject private var model = PlanModel()
    @ObservedObject private var overlay = ComposeOverlay.shared
    @State private var section: PlanSection = .overview
    @State private var toast: Toast?
    @State private var pickingDate = false
    @State private var amountEdit: AmountEdit?
    @State private var path: [PlanEditor] = []

    /** Native editors pushed onto Plan's navigation stack. */
    enum PlanEditor: Hashable {
        case category(String?)
        case account(String?)
        case schedule(String?, ScheduleType?)
        case transaction(String)
    }

    private struct AmountEdit {
        let key: String
        let prompt: ChangeAmountPrompt
        var text: String
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    Picker("Section", selection: $section) {
                        ForEach(PlanSection.entries, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                switch section {
                case .schedules: schedulesContent
                case .categories: categoriesContent
                case .accounts: accountsContent
                default: overviewContent
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Plan")
            .navigationDestination(for: PlanEditor.self) { editor in
                switch editor {
                case .category(let id):
                    CategoryEditorScreen(categoryId: id) { message in toast = Toast(message) }
                case .account(let id):
                    AccountEditorScreen(accountId: id) { message in toast = Toast(message) }
                case .schedule(let id, let type):
                    ScheduleEditorScreen(scheduleId: id, presetType: type) { message in toast = Toast(message) }
                case .transaction(let id):
                    TransactionEditorScreen(postingId: id) { result in toast = result }
                }
            }
            .toolbar {
                if !overlay.detailOpen && section != .overview && path.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: addInSection) { Label("Add", systemImage: "plus") }
                    }
                }
            }
        }
        .overlay(alignment: .bottom) { ToastView(toast: $toast) }
        .sheet(isPresented: $pickingDate) { datePickerSheet }
        .alert(amountEdit?.prompt.title ?? "", isPresented: Binding(
            get: { amountEdit != nil },
            set: { if !$0 { amountEdit = nil } }
        )) {
            TextField("Amount", text: Binding(
                get: { amountEdit?.text ?? "" },
                set: { amountEdit?.text = $0 }
            ))
            .keyboardType(.decimalPad)
            Button("Save") { saveAmount() }
                .disabled(!model.screen.isValidAmount(text: amountEdit?.text ?? ""))
            Button("Cancel", role: .cancel) { amountEdit = nil }
        } message: {
            Text(amountEdit?.prompt.message ?? "")
        }
        .task { await model.observe() }
        .onChange(of: model.pendingRequest) { _, request in apply(request) }
        .onAppear { apply(model.pendingRequest) }
    }

    // MARK: Overview

    @ViewBuilder
    private var overviewContent: some View {
        let state = model.overview
        Section {
            Picker("Period", selection: Binding(get: { state.period }, set: { model.screen.setPeriod(period: $0) })) {
                ForEach(state.periods, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            HStack {
                Button { model.screen.step(direction: -1) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel(state.previousLabel)
                Spacer()
                Button(state.rangeLabel) { pickingDate = true }
                    .font(.headline)
                Spacer()
                Button { model.screen.step(direction: 1) } label: { Image(systemName: "chevron.right") }
                    .accessibilityLabel(state.nextLabel)
            }
            .buttonStyle(.borderless)
            if let reset = state.resetLabel {
                Button(reset) { model.screen.resetToCurrent() }
            }
        } footer: {
            if let subtitle = state.subtitle { Text(subtitle) }
        }

        Section {
            HStack(alignment: .top) {
                SummaryFigure(label: "Income", value: state.summary.income, color: .green)
                Spacer()
                SummaryFigure(label: "Expenses", value: state.summary.expenses, color: .primary)
                Spacer()
                SummaryFigure(label: state.summary.netLabel, value: state.summary.net,
                              color: state.summary.netPositive ? .green : .red, trailing: true)
            }
            Picker("Group", selection: Binding(get: { state.grouping }, set: { model.screen.setGrouping(grouping: $0) })) {
                ForEach(state.groupings, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
        }

        if let emptyTitle = state.emptyTitle {
            Section {
                ContentUnavailableView(emptyTitle, systemImage: "calendar", description: Text(state.emptyMessage))
            }
            .listRowBackground(Color.clear)
        } else if state.grouping == .byDay {
            ForEach(state.days, id: \.header) { day in
                Section(day.header) {
                    ForEach(day.rows, id: \.key) { row in occurrenceRow(row) }
                }
            }
        } else {
            ForEach(state.categories, id: \.key) { group in
                Section {
                    Button { model.screen.toggleCategory(key: group.key) } label: {
                        HStack(spacing: 12) {
                            Circle().fill(Color(hex: group.color) ?? Color(.tertiaryLabel)).frame(width: 10, height: 10)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(group.name).font(.headline)
                                Text(group.countLabel).font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(group.total).foregroundStyle(group.totalType.amountColor)
                            Image(systemName: group.expanded ? "chevron.up" : "chevron.down")
                                .foregroundStyle(.secondary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(group.expanded ? "Collapse" : "Expand")
                    if group.expanded {
                        ForEach(group.lines, id: \.key) { line in
                            Button {
                                if line.isSchedule { path.append(.schedule(line.key, nil)) }
                                else if let id = line.postingId { path.append(.transaction(id)) }
                            } label: {
                                ListRow(title: line.title, supporting: line.supporting,
                                        trailing: line.amount, trailingColor: line.type.amountColor)
                                    .padding(.leading, 22)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    private func occurrenceRow(_ row: OccurrenceUi) -> some View {
        HStack(spacing: 12) {
            Circle().fill(Color(hex: row.categoryColor) ?? Color(.tertiaryLabel)).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).lineLimit(1)
                Text(row.supporting).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(row.amount).foregroundStyle(row.type.amountColor).lineLimit(1).fixedSize()
            if row.posted {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityLabel(row.postedLabel)
            } else {
                Button { run { model.screen.markPosted(key: row.key) } } label: {
                    Image(systemName: "checkmark.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(row.markLabel)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if row.posted, let id = row.postingId { path.append(.transaction(id)) }
        }
        .swipeActions(edge: .leading) {
            if !row.posted {
                Button(row.markLabel) { run { model.screen.markPosted(key: row.key) } }.tint(.green)
            }
        }
        .swipeActions(edge: .trailing) {
            if !row.posted {
                Button("Skip") { run { model.screen.skip(key: row.key) } }.tint(.orange)
                Button("Amount") { editAmount(row.key) }.tint(.blue)
            }
        }
        .contextMenu {
            if row.posted {
                if let id = row.postingId {
                    Button("View transaction") { path.append(.transaction(id)) }
                }
                if row.canUnmark {
                    Button(row.unmarkLabel) { run { model.screen.unmark(key: row.key) } }
                }
            } else {
                Button(row.markLabel) { run { model.screen.markPosted(key: row.key) } }
                Button("Change amount…") { editAmount(row.key) }
                Button("Skip this time") { run { model.screen.skip(key: row.key) } }
            }
        }
    }

    // MARK: Schedules / Categories / Accounts

    @ViewBuilder
    private var schedulesContent: some View {
        let state = model.schedules
        Section {
            Picker("Type", selection: Binding(
                get: { state.filter?.name ?? "ALL" },
                set: { name in model.screen.setScheduleFilter(type: state.filterOptions.first { $0.name == name }) }
            )) {
                Text("All").tag("ALL")
                ForEach(state.filterOptions, id: \.self) { Text($0.pluralLabel).tag($0.name) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        }
        if let empty = state.empty {
            emptySection(empty) { path.append(.schedule(nil, state.filter)) }
        } else {
            ForEach(Array(state.groups.enumerated()), id: \.offset) { _, group in
                Section {
                    ForEach(group.rows, id: \.id) { row in
                        NavigationLink(value: PlanEditor.schedule(row.id, nil)) {
                            ListRow(title: row.title, supporting: row.supporting, trailing: row.amount,
                                    trailingColor: row.type.amountColor, dot: Color(hex: row.categoryColor))
                        }
                    }
                } header: {
                    if let header = group.header { Text(header) }
                }
            }
        }
    }

    @ViewBuilder
    private var categoriesContent: some View {
        let state = model.categories
        if let empty = state.empty {
            emptySection(empty) { path.append(.category(nil)) }
        } else {
            Section {
                ForEach(state.rows, id: \.id) { row in
                    NavigationLink(value: PlanEditor.category(row.id)) {
                        ListRow(title: row.name, dot: Color(hex: row.color) ?? Color(.tertiaryLabel))
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var accountsContent: some View {
        let state = model.accounts
        if let empty = state.empty {
            emptySection(empty) { path.append(.account(nil)) }
        } else {
            Section {
                ForEach(state.rows, id: \.id) { row in
                    NavigationLink(value: PlanEditor.account(row.id)) {
                        ListRow(title: row.name, trailing: row.balance)
                    }
                }
            } footer: {
                if let subtitle = state.subtitle { Text(subtitle) }
            }
        }
    }

    private func emptySection(_ empty: ListEmpty, add: @escaping () -> Void) -> some View {
        Section {
            ContentUnavailableView {
                Label(empty.title, systemImage: "tray")
            } description: {
                Text(empty.message)
            } actions: {
                Button(empty.actionLabel, action: add).buttonStyle(.borderedProminent)
            }
        }
        .listRowBackground(Color.clear)
    }

    // MARK: Actions

    private func addInSection() {
        switch section {
        case .schedules: path.append(.schedule(nil, model.schedules.filter))
        case .categories: path.append(.category(nil))
        case .accounts: path.append(.account(nil))
        default: break
        }
    }

    private func run(_ action: @escaping () -> NativeSuspend<String?, Error, KotlinUnit>) {
        Task {
            if let result = await model.perform(action) {
                withAnimation { toast = result }
            }
        }
    }

    private func editAmount(_ key: String) {
        guard let prompt = model.screen.changeAmountPrompt(key: key) else { return }
        amountEdit = AmountEdit(key: key, prompt: prompt, text: prompt.initialText)
    }

    private func saveAmount() {
        guard let edit = amountEdit else { return }
        amountEdit = nil
        run { model.screen.changeAmount(key: edit.key, text: edit.text) }
    }

    private func apply(_ request: PlanRequest?) {
        guard let request else { return }
        section = request.section
        if let period = request.period { model.screen.setPeriod(period: period) }
        model.screen.consumeRequest()
    }

    // MARK: Date picker

    @State private var pickedDate = Date()

    private var datePickerSheet: some View {
        NavigationStack {
            DatePicker("Date", selection: $pickedDate, displayedComponents: .date)
                .datePickerStyle(.graphical)
                .padding()
                .navigationTitle("Go to \(model.overview.period.label.lowercased())")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { pickingDate = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Go") {
                            let parts = Calendar.current.dateComponents([.year, .month, .day], from: pickedDate)
                            model.screen.jumpTo(year: Int32(parts.year ?? 2000), month: Int32(parts.month ?? 1), day: Int32(parts.day ?? 1))
                            pickingDate = false
                        }
                    }
                }
        }
        .presentationDetents([.medium, .large])
        .onAppear { pickedDate = DateBridge.date(fromEpochDay: model.overview.rangeStartEpochDay) }
    }
}

// MARK: - Building blocks

private struct SummaryFigure: View {
    let label: String
    let value: String
    let color: Color
    var trailing = false

    var body: some View {
        VStack(alignment: trailing ? .trailing : .leading, spacing: 2) {
            Text(label).font(.footnote).foregroundStyle(.secondary)
            Text(value).font(.title3.weight(.semibold)).foregroundStyle(color)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Title + optional supporting line, trailing value and leading color dot.
struct ListRow: View {
    let title: String
    var supporting: String? = nil
    var trailing: String? = nil
    var trailingColor: Color = .primary
    var dot: Color? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let dot {
                Circle().fill(dot).frame(width: 10, height: 10)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                if let supporting {
                    Text(supporting).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if let trailing {
                Text(trailing).foregroundStyle(trailingColor).lineLimit(1).fixedSize()
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
