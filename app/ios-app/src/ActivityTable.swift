import SwiftUI
import UIKit
import KotlinModules

/// Renders the existing shared snapshot with reusable cells. ActivityScreen
/// still owns observation, search, and its SwiftUI navigation stack.
struct ActivityTable: UIViewRepresentable {
    let state: ActivityUiState
    let selectRange: (Int32) -> Void
    let openTransaction: (String?) -> Void
    let importCSV: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UITableView {
        let table = UITableView(frame: .zero, style: .insetGrouped)
        table.accessibilityIdentifier = "activity.list"
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 61
        table.keyboardDismissMode = .onDrag
        table.register(ActivityCell.self, forCellReuseIdentifier: "transaction")
        table.register(ActivityRangeCell.self, forCellReuseIdentifier: "range")
        table.register(UITableViewCell.self, forCellReuseIdentifier: "empty")
        context.coordinator.attach(table)
        return table
    }

    func updateUIView(_ table: UITableView, context: Context) {
        context.coordinator.update(self)
    }

    final class Coordinator: NSObject, UITableViewDelegate {
        enum Section: Hashable { case range, empty, day(String) }
        enum Item: Hashable { case range, empty, transaction(String) }
        private var state: ActivityUiState?
        private var rows: [String: ActivityRowUi] = [:]
        private final class Source: UITableViewDiffableDataSource<Section, Item> {
            override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
                if case .day(let title) = sectionIdentifier(for: section) { return title }
                return nil
            }
        }
        private var source: Source!
        private var selectRange: ((Int32) -> Void)?
        private var openTransaction: ((String?) -> Void)?
        private var importCSV: (() -> Void)?

        func attach(_ table: UITableView) {
            table.delegate = self
            source = Source(tableView: table) { [weak self] table, path, item in
                self?.cell(table, path: path, item: item)
            }
        }

        func update(_ view: ActivityTable) {
            selectRange = view.selectRange
            openTransaction = view.openTransaction
            importCSV = view.importCSV
            guard state != view.state else { return }
            let previous = state
            let previousRows = rows
            state = view.state
            rows = Dictionary(uniqueKeysWithValues: view.state.sections.flatMap { $0.rows }.map { ($0.id, $0) })
            var snapshot = NSDiffableDataSourceSnapshot<Section, Item>()
            snapshot.appendSections([.range])
            snapshot.appendItems([.range], toSection: .range)
            if view.state.empty != nil {
                snapshot.appendSections([.empty])
                snapshot.appendItems([.empty], toSection: .empty)
            } else {
                for section in view.state.sections {
                    let id = Section.day(section.header)
                    snapshot.appendSections([id])
                    snapshot.appendItems(section.rows.map { .transaction($0.id) }, toSection: id)
                }
            }
            var changed: [Item] = []
            if previous?.selectedRange != view.state.selectedRange || previous?.ranges != view.state.ranges {
                changed.append(.range)
            }
            if previous?.empty != view.state.empty { changed.append(.empty) }
            changed += rows.compactMap { id, row in previousRows[id] == row ? nil : .transaction(id) }
            let existing = Set(source.snapshot().itemIdentifiers)
            let current = Set(snapshot.itemIdentifiers)
            snapshot.reconfigureItems(changed.filter { existing.contains($0) && current.contains($0) })
            source.apply(snapshot, animatingDifferences: false)
        }

        private func cell(_ table: UITableView, path: IndexPath, item: Item) -> UITableViewCell {
            switch item {
            case .range:
                let cell = table.dequeueReusableCell(withIdentifier: "range", for: path) as! ActivityRangeCell
                if let state {
                    cell.configure(state)
                    cell.picker.removeTarget(self, action: #selector(rangeChanged), for: .valueChanged)
                    cell.picker.addTarget(self, action: #selector(rangeChanged), for: .valueChanged)
                }
                return cell
            case .transaction(let id):
                let cell = table.dequeueReusableCell(withIdentifier: "transaction", for: path) as! ActivityCell
                if let row = rows[id] { cell.configure(row) }
                return cell
            case .empty:
                let cell = table.dequeueReusableCell(withIdentifier: "empty", for: path)
                cell.selectionStyle = .none
                cell.backgroundColor = .clear
                if let empty = state?.empty {
                    cell.contentConfiguration = UIHostingConfiguration { [weak self] in
                        ContentUnavailableView {
                            Label(empty.title, systemImage: empty.showActions ? "tray" : "magnifyingglass")
                        } description: {
                            Text(empty.message)
                        } actions: {
                            if empty.showActions {
                                Button("Add transaction") { self?.openTransaction?(nil) }
                                    .buttonStyle(.borderedProminent)
                                Button("Import from CSV") { self?.importCSV?() }
                            }
                        }
                    }
                }
                return cell
            }
        }

        @objc private func rangeChanged(_ picker: UISegmentedControl) {
            guard let state, state.ranges.indices.contains(picker.selectedSegmentIndex) else { return }
            selectRange?(state.ranges[picker.selectedSegmentIndex].months)
        }

        func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
            tableView.deselectRow(at: indexPath, animated: true)
            if case .transaction(let id) = source.itemIdentifier(for: indexPath) { openTransaction?(id) }
        }
    }
}

private final class ActivityRangeCell: UITableViewCell {
    let picker = UISegmentedControl()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear
        picker.accessibilityIdentifier = "activity.range"
        picker.accessibilityLabel = "Range"
        picker.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(picker)
        NSLayoutConstraint.activate([
            picker.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            picker.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            picker.topAnchor.constraint(equalTo: contentView.topAnchor),
            picker.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ state: ActivityUiState) {
        let labels = state.ranges.map(\.label)
        let current = (0..<picker.numberOfSegments).compactMap { picker.titleForSegment(at: $0) }
        if labels != current {
            picker.removeAllSegments()
            for (index, label) in labels.enumerated() { picker.insertSegment(withTitle: label, at: index, animated: false) }
        }
        picker.selectedSegmentIndex = state.ranges.firstIndex(of: state.selectedRange) ?? UISegmentedControl.noSegment
    }
}

private final class ActivityCell: UITableViewCell {
    private let title = UILabel()
    private let supporting = UILabel()
    private let amount = UILabel()
    private let dot = UIView()
    private let textAndAmount = UIStackView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        accessoryType = .disclosureIndicator
        separatorInset.left = 38 // Align separators with text after the category dot.
        isAccessibilityElement = true
        accessibilityTraits = .button
        for label in [title, supporting, amount] { label.adjustsFontForContentSizeCategory = true }
        supporting.textColor = .secondaryLabel
        amount.setContentCompressionResistancePriority(.required, for: .horizontal)
        amount.setContentHuggingPriority(.required, for: .horizontal)
        let detail = UIStackView(arrangedSubviews: [title, supporting])
        detail.axis = .vertical
        detail.spacing = 2
        detail.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        title.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        supporting.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        textAndAmount.spacing = 8
        textAndAmount.addArrangedSubview(detail)
        textAndAmount.addArrangedSubview(amount)
        dot.layer.cornerRadius = 5
        let row = UIStackView(arrangedSubviews: [dot, textAndAmount])
        row.alignment = .center
        row.spacing = 12
        row.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(row)
        NSLayoutConstraint.activate([
            dot.widthAnchor.constraint(equalToConstant: 10),
            dot.heightAnchor.constraint(equalToConstant: 10),
            row.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            row.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 11),
            row.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -11),
        ])
        updateFonts()
        registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) { (cell: ActivityCell, _: UITraitCollection) in
            cell.updateFonts()
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ row: ActivityRowUi) {
        title.text = row.title
        supporting.text = row.supporting
        amount.text = row.amount
        amount.textColor = row.type == .income ? .systemGreen : row.type == .transfer ? .secondaryLabel : .label
        dot.backgroundColor = Color(hex: row.categoryColor).map { UIColor($0) } ?? .tertiaryLabel
        accessibilityIdentifier = "transaction.\(row.id)"
        accessibilityLabel = [row.title, row.supporting, row.amount].joined(separator: ", ")
    }

    private func updateFonts() {
        title.font = .preferredFont(forTextStyle: .body, compatibleWith: traitCollection)
        supporting.font = .preferredFont(forTextStyle: .footnote, compatibleWith: traitCollection)
        amount.font = .preferredFont(forTextStyle: .body, compatibleWith: traitCollection)
        let accessible = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        textAndAmount.axis = accessible ? .vertical : .horizontal
        textAndAmount.alignment = accessible ? .fill : .center
        for label in [title, supporting, amount] {
            label.numberOfLines = accessible ? 0 : 1
            label.lineBreakMode = accessible ? .byWordWrapping : .byTruncatingTail
        }
        contentView.invalidateIntrinsicContentSize()
    }
}
