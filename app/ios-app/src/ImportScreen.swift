import SwiftUI
import UniformTypeIdentifiers
import KotlinModules
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCore

/// Native CSV import: choose a bank export, map its columns, review rows and import. Driven by the
/// shared `ImportViewModel`.
struct ImportScreen: View {
    @StateObject private var model: FlowModel<ImportUiState, ImportScreenModel>
    @Environment(\.dismiss) private var dismiss
    @State private var choosingFile = false
    @State private var confirming = false
    @State private var importing = false
    let onDone: (Toast) -> Void

    init(onDone: @escaping (Toast) -> Void) {
        let screen = ImportScreenModel()
        _model = StateObject(wrappedValue: FlowModel(screen: screen, initial: screen.state, flow: screen.stateFlow))
        self.onDone = onDone
    }

    private var screen: ImportScreenModel { model.screen }

    var body: some View {
        let state = model.state
        Form {
            Section {
                Button { choosingFile = true } label: { Label(state.chooseLabel, systemImage: "square.and.arrow.down") }
            } footer: {
                Text(state.fileLabel ?? state.subtitle)
            }
            switch state.stage {
            case .noFile:
                ContentUnavailableView(state.emptyTitle, systemImage: "tablecells", description: Text(state.emptyMessage))
                    .listRowBackground(Color.clear)
            case .unreadable:
                ContentUnavailableView(state.unreadableTitle, systemImage: "exclamationmark.triangle")
                    .listRowBackground(Color.clear)
            default:
                mappingSections(state)
                reviewSection(state)
            }
        }
        .navigationTitle(state.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if state.stage == .ready {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Import") { confirming = true }
                        .disabled(!state.importEnabled || importing)
                }
            }
        }
        .confirmationDialog(state.confirm?.title ?? "", isPresented: $confirming, titleVisibility: .visible) {
            Button(state.confirm?.confirmLabel ?? "Import") { runImport() }
        } message: {
            Text(state.confirm?.message ?? "")
        }
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: [.commaSeparatedText, .plainText, .text]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                screen.loadFile(fileName: url.lastPathComponent, data: data)
            }
        }
        .task { await model.observe() }
    }

    @ViewBuilder
    private func mappingSections(_ state: ImportUiState) -> some View {
        Section {
            Toggle(state.headerToggleLabel, isOn: Binding(get: { state.hasHeader }, set: { screen.setHasHeader(value: $0) }))
            columnPicker("Date", state.columns, Int(state.dateColumn)) { screen.setDateColumn(index: $0) }
            Picker("Date format", selection: Binding(get: { state.dateFormat }, set: { screen.setDateFormat(format: $0) })) {
                ForEach(state.dateFormats, id: \.format) { Text($0.label).tag($0.format) }
            }
            columnPicker("Description", state.columns, Int(state.descriptionColumn)) { screen.setDescriptionColumn(index: $0) }
            columnPicker("Amount", state.columns, Int(state.amountColumn)) { screen.setAmountColumn(index: $0) }
        } header: {
            Text("Columns")
        } footer: {
            Text(state.amountHint)
        }
        Section {
            Picker("Account", selection: Binding(get: { state.accountId }, set: { screen.setAccount(id: $0) })) {
                ForEach(state.accounts, id: \.id) { Text($0.label).tag(Optional($0.id)) }
            }
            .disabled(state.accounts.isEmpty)
            Picker("Default category", selection: Binding(get: { state.defaultCategoryId }, set: { screen.setDefaultCategory(id: $0) })) {
                Text(state.noCategoryLabel).tag(String?.none)
                ForEach(state.categories, id: \.id) { Text($0.label).tag(Optional($0.id)) }
            }
        } header: {
            Text("Import into")
        } footer: {
            Text(state.accountHint ?? state.categoryHint)
        }
    }

    private func columnPicker(_ title: String, _ columns: [ColumnChoice], _ selected: Int, _ set: @escaping (Int32) -> Void) -> some View {
        Picker(title, selection: Binding(get: { selected }, set: { set(Int32($0)) })) {
            ForEach(columns, id: \.index) { Text($0.label).tag(Int($0.index)) }
        }
    }

    private func reviewSection(_ state: ImportUiState) -> some View {
        Section {
            ForEach(state.rows, id: \.rowIndex) { row in
                ImportRow(row: row, categories: state.categories, noCategoryLabel: state.noCategoryLabel, screen: screen)
            }
        } header: {
            HStack {
                Text("Review")
                Spacer()
                Text(state.reviewSummary).textCase(nil)
            }
        }
    }

    private func runImport() {
        importing = true
        Task {
            let result: String?? = try? await asyncFunction(for: screen.importRows())
            importing = false
            if let message = result ?? nil {
                onDone(Toast(message))
                dismiss()
            }
        }
    }
}

private struct ImportRow: View {
    let row: ImportRowUi
    let categories: [CategoryChoice]
    let noCategoryLabel: String
    let screen: ImportScreenModel

    var body: some View {
        HStack(spacing: 12) {
            Button {
                screen.setIncluded(rowIndex: row.rowIndex, included: !row.included)
            } label: {
                Image(systemName: row.included ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(row.included ? Color.accentColor : .secondary)
            }
            .buttonStyle(.plain)
            .disabled(!row.valid)
            .accessibilityLabel(row.title)
            .accessibilityAddTraits(row.included ? .isSelected : [])

            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .lineLimit(1)
                    .foregroundStyle(row.included ? .primary : .secondary)
                Text(row.supporting)
                    .font(.footnote)
                    .foregroundStyle(row.valid ? Color.secondary : Color.red)
                    .lineLimit(1)
                if row.valid {
                    Menu {
                        Button(noCategoryLabel) { screen.setRowCategory(rowIndex: row.rowIndex, categoryId: nil) }
                        ForEach(categories, id: \.id) { category in
                            Button(category.label) { screen.setRowCategory(rowIndex: row.rowIndex, categoryId: category.id) }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            if row.categoryId != nil {
                                Circle().fill(Color(hex: row.categoryColor) ?? .gray).frame(width: 8, height: 8)
                            }
                            Text(row.categoryLabel).font(.footnote)
                            Image(systemName: "chevron.up.chevron.down").font(.caption2)
                        }
                    }
                    .accessibilityLabel("Category, \(row.categoryLabel)")
                }
            }
            Spacer(minLength: 8)
            if let amount = row.amount {
                Text(amount).foregroundStyle(row.type.amountColor).fixedSize()
            }
        }
    }
}
