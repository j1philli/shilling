import SwiftUI
import UIKit
import KotlinModules

/// A native list of the shared presentation snapshot. Navigation and observation
/// remain in ReceiptsScreen; this view owns no repository or subscriptions.
struct ReceiptTable: UIViewRepresentable {
    let state: ReceiptsUiState
    let selectFilter: (ReceiptFilter) -> Void
    let openReceipt: (String?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UITableView {
        let table = UITableView(frame: .zero, style: .insetGrouped)
        table.accessibilityIdentifier = "receipts.list"
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 61
        table.register(ReceiptCell.self, forCellReuseIdentifier: "receipt")
        table.register(ReceiptFilterCell.self, forCellReuseIdentifier: "filter")
        table.register(UITableViewCell.self, forCellReuseIdentifier: "empty")
        context.coordinator.attach(table)
        return table
    }

    func updateUIView(_ table: UITableView, context: Context) {
        context.coordinator.update(self)
    }

    final class Coordinator: NSObject, UITableViewDelegate {
        enum Item: Hashable { case filter, empty, receipt(String) }
        private var state: ReceiptsUiState?
        private var rows: [String: ReceiptRowUi] = [:]
        private final class Source: UITableViewDiffableDataSource<Int, Item> {
            var subtitle: String?
            override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
                section == 0 ? subtitle : nil
            }
        }
        private var source: Source!
        private var selectFilter: ((ReceiptFilter) -> Void)?
        private var openReceipt: ((String?) -> Void)?

        func attach(_ table: UITableView) {
            table.delegate = self
            source = Source(tableView: table) { [weak self] table, path, item in
                self?.cell(table, path: path, item: item)
            }
        }

        func update(_ view: ReceiptTable) {
            selectFilter = view.selectFilter
            openReceipt = view.openReceipt
            guard state != view.state else { return }
            let previous = state
            let previousRows = rows
            state = view.state
            rows = Dictionary(uniqueKeysWithValues: view.state.rows.map { ($0.id, $0) })
            var snapshot = NSDiffableDataSourceSnapshot<Int, Item>()
            snapshot.appendSections([0, 1])
            snapshot.appendItems([.filter], toSection: 0)
            let items: [Item] = view.state.empty == nil ? view.state.rows.map { .receipt($0.id) } : [.empty]
            snapshot.appendItems(items, toSection: 1)
            let existing = Set(source.snapshot().itemIdentifiers)
            var changed: [Item] = []
            let footerChanged = previous != nil && previous?.subtitle != view.state.subtitle
            source.subtitle = view.state.subtitle
            if footerChanged { snapshot.reloadSections([0]) }
            if !footerChanged && (previous?.filter != view.state.filter || previous?.filters != view.state.filters) {
                changed.append(.filter)
            }
            if previous?.empty != view.state.empty { changed.append(.empty) }
            changed += view.state.rows.compactMap { row in
                previousRows[row.id] == row ? nil : .receipt(row.id)
            }
            let current = Set(snapshot.itemIdentifiers)
            snapshot.reconfigureItems(changed.filter { existing.contains($0) && current.contains($0) })
            source.apply(snapshot, animatingDifferences: false)
        }

        private func cell(_ table: UITableView, path: IndexPath, item: Item) -> UITableViewCell {
            switch item {
            case .filter:
                let cell = table.dequeueReusableCell(withIdentifier: "filter", for: path) as! ReceiptFilterCell
                if let state {
                    cell.configure(state: state)
                    cell.picker.removeTarget(self, action: #selector(filterChanged), for: .valueChanged)
                    cell.picker.addTarget(self, action: #selector(filterChanged), for: .valueChanged)
                }
                return cell
            case .receipt(let id):
                let cell = table.dequeueReusableCell(withIdentifier: "receipt", for: path) as! ReceiptCell
                if let row = rows[id] { cell.configure(row) }
                return cell
            case .empty:
                let cell = table.dequeueReusableCell(withIdentifier: "empty", for: path)
                cell.selectionStyle = .none
                cell.backgroundColor = .clear
                if let empty = state?.empty {
                    cell.contentConfiguration = UIHostingConfiguration { [weak self] in
                        ContentUnavailableView {
                            Label(empty.title, systemImage: "doc.text")
                        } description: {
                            if let message = empty.message { Text(message) }
                        } actions: {
                            if empty.showAdd {
                                Button("Add receipt") { self?.openReceipt?(nil) }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                    }
                }
                return cell
            }
        }

        @objc private func filterChanged(_ picker: UISegmentedControl) {
            guard let state, state.filters.indices.contains(picker.selectedSegmentIndex) else { return }
            selectFilter?(state.filters[picker.selectedSegmentIndex])
        }

        func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
            tableView.deselectRow(at: indexPath, animated: true)
            if case .receipt(let id) = source.itemIdentifier(for: indexPath) { openReceipt?(id) }
        }
    }
}

private final class ReceiptFilterCell: UITableViewCell {
    let picker = UISegmentedControl()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear
        picker.accessibilityIdentifier = "receipts.filter"
        picker.accessibilityLabel = "Filter"
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

    func configure(state: ReceiptsUiState) {
        let labels = state.filters.map(\.label)
        let current = (0..<picker.numberOfSegments).compactMap { picker.titleForSegment(at: $0) }
        if labels != current {
            picker.removeAllSegments()
            for (index, label) in labels.enumerated() { picker.insertSegment(withTitle: label, at: index, animated: false) }
        }
        picker.selectedSegmentIndex = state.filters.firstIndex(of: state.filter) ?? UISegmentedControl.noSegment
    }
}

/// UIKit owns sizing for these rows, avoiding a SwiftUI hosting view per cell
/// when the tab's entire hierarchy is reattached to its window.
private final class ReceiptCell: UITableViewCell {
    private let title = UILabel()
    private let supporting = UILabel()
    private let amount = UILabel()
    private let detail = UIStackView()
    private let textAndAmount = UIStackView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        accessoryType = .disclosureIndicator
        isAccessibilityElement = true
        accessibilityTraits = .button
        for label in [title, supporting, amount] { label.adjustsFontForContentSizeCategory = true }
        supporting.textColor = .secondaryLabel
        amount.setContentCompressionResistancePriority(.required, for: .horizontal)
        amount.setContentHuggingPriority(.required, for: .horizontal)
        detail.axis = .vertical
        detail.spacing = 2
        detail.addArrangedSubview(title)
        detail.addArrangedSubview(supporting)
        textAndAmount.spacing = 8
        textAndAmount.addArrangedSubview(detail)
        textAndAmount.addArrangedSubview(amount)
        let icon = UIImageView(image: UIImage(systemName: "doc.text"))
        icon.tintColor = .secondaryLabel
        icon.contentMode = .scaleAspectFit
        icon.setContentHuggingPriority(.required, for: .horizontal)
        icon.setContentCompressionResistancePriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [icon, textAndAmount])
        row.alignment = .center
        row.spacing = 12
        row.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            row.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 11),
            row.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -11),
        ])
        updateFonts()
        registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) { (cell: ReceiptCell, _: UITraitCollection) in
            cell.updateFonts()
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ row: ReceiptRowUi) {
        title.text = row.title
        supporting.text = row.supporting
        amount.text = row.amount
        amount.isHidden = row.amount == nil
        accessibilityIdentifier = "receipt.\(row.id)"
        accessibilityLabel = [row.title, row.supporting, row.amount].compactMap { $0 }.joined(separator: ", ")
    }

    private func updateFonts() {
        title.font = .preferredFont(forTextStyle: .body, compatibleWith: traitCollection)
        supporting.font = .preferredFont(forTextStyle: .footnote, compatibleWith: traitCollection)
        amount.font = .preferredFont(forTextStyle: .body, compatibleWith: traitCollection)
        let accessible = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        textAndAmount.axis = accessible ? .vertical : .horizontal
        textAndAmount.alignment = accessible ? .fill : .center
        title.numberOfLines = accessible ? 0 : 1
        supporting.numberOfLines = accessible ? 0 : 1
        amount.numberOfLines = accessible ? 0 : 1
        amount.lineBreakMode = accessible ? .byWordWrapping : .byTruncatingTail
        contentView.invalidateIntrinsicContentSize()
    }
}
