import SwiftUI
import UIKit

/// Synthetic controls for attributing tab resume cost; never used by the app.
enum NativeTabProbe: String, CaseIterable {
    case uikit, text, navigation, list, staticReceipts, nativeTable, production

    var hasList: Bool { self == .list || self == .staticReceipts || self == .nativeTable || self == .production }

    @MainActor func controller() -> UIViewController {
        if self == .uikit {
            let controller = UIViewController()
            let label = UILabel()
            label.text = "Receipt control"
            label.textAlignment = .center
            label.backgroundColor = .systemBackground
            controller.view = label
            return controller
        }
        let root: AnyView
        switch self {
        case .text: root = AnyView(Text("Receipt control"))
        case .navigation:
            root = AnyView(NavigationStack {
                Text("Receipt control").navigationTitle("Receipts")
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Add", systemImage: "plus") {} } }
            })
        case .list: root = AnyView(StaticReceiptProbe(navigation: false))
        case .staticReceipts: root = AnyView(StaticReceiptProbe(navigation: true))
        case .nativeTable:
            root = AnyView(NavigationStack {
                NativeReceiptTableProbe().ignoresSafeArea(.container, edges: .vertical)
                    .navigationTitle("Receipts")
                    .navigationBarTitleDisplayMode(.large)
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Add", systemImage: "plus") {} } }
            })
        default: root = AnyView(ReceiptsScreen())
        }
        return TabProbeHostingController(rootView: root)
    }
}

/// Same synthetic row count/height, using UITableView instead of SwiftUI.List.
/// Fixed heights are a benchmark control at the fixture's default text size.
private struct NativeReceiptTableProbe: UIViewRepresentable {
    func makeCoordinator() -> Source { Source() }
    func makeUIView(context: Context) -> UITableView {
        let table = UITableView(frame: .zero, style: .insetGrouped)
        table.dataSource = context.coordinator
        table.delegate = context.coordinator
        table.rowHeight = 60.5
        table.estimatedRowHeight = 60.5
        return table
    }
    func updateUIView(_ uiView: UITableView, context: Context) {}

    final class Source: NSObject, UITableViewDataSource, UITableViewDelegate {
        func numberOfSections(in tableView: UITableView) -> Int { 2 }
        func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 1 : 250 }
        func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
            section == 0 ? "250 synthetic receipts" : nil
        }
        func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
            indexPath.section == 0 ? 32 : 60.5
        }
        func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
            if indexPath.section == 0 {
                let cell = UITableViewCell()
                let picker = UISegmentedControl(items: ["All", "Attached", "Unattached"])
                picker.selectedSegmentIndex = 0
                picker.translatesAutoresizingMaskIntoConstraints = false
                cell.contentView.addSubview(picker)
                NSLayoutConstraint.activate([
                    picker.leadingAnchor.constraint(equalTo: cell.contentView.leadingAnchor),
                    picker.trailingAnchor.constraint(equalTo: cell.contentView.trailingAnchor),
                    picker.centerYAnchor.constraint(equalTo: cell.contentView.centerYAnchor),
                ])
                cell.backgroundColor = .clear
                return cell
            }
            let cell = tableView.dequeueReusableCell(withIdentifier: "receipt") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "receipt")
            cell.textLabel?.text = "Synthetic receipt \(indexPath.row)"
            cell.textLabel?.font = .preferredFont(forTextStyle: .body)
            cell.detailTextLabel?.text = "Added Sep 21 · Not attached"
            cell.detailTextLabel?.font = .preferredFont(forTextStyle: .footnote)
            cell.detailTextLabel?.textColor = .secondaryLabel
            cell.imageView?.image = UIImage(systemName: "doc.text", withConfiguration: UIImage.SymbolConfiguration(textStyle: .body))
            cell.imageView?.tintColor = .secondaryLabel
            if cell.accessoryView == nil {
                let amount = UILabel()
                amount.text = "$12.34"
                amount.font = .preferredFont(forTextStyle: .body)
                amount.setContentCompressionResistancePriority(.required, for: .horizontal)
                let arrow = UIImageView(image: UIImage(systemName: "chevron.right", withConfiguration: UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)))
                arrow.tintColor = .tertiaryLabel
                let accessory = UIStackView(arrangedSubviews: [amount, arrow])
                accessory.alignment = .center
                accessory.spacing = 10
                accessory.frame = CGRect(origin: .zero, size: accessory.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize))
                cell.accessoryView = accessory
            }
            return cell
        }
    }
}

private struct StaticReceiptProbe: View {
    let navigation: Bool
    @State private var filter = "All"

    var body: some View {
        if navigation {
            NavigationStack {
                content.navigationTitle("Receipts")
                    .navigationDestination(for: Int.self) { Text("Receipt \($0)") }
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Add", systemImage: "plus") {} } }
            }
        } else { content }
    }

    private var content: some View {
        List {
            Section {
                Picker("Filter", selection: $filter) {
                    ForEach(["All", "Attached", "Unattached"], id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            } footer: { Text("250 synthetic receipts") }
            Section {
                ForEach(0..<250, id: \.self) { index in
                    NavigationLink(value: index) {
                        HStack(spacing: 12) {
                            Image(systemName: "doc.text").foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Synthetic receipt \(index)").lineLimit(1)
                                Text("Added Sep 21 · Not attached")
                                    .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer(minLength: 8)
                            Text("$12.34").lineLimit(1).fixedSize()
                        }
                        .contentShape(Rectangle())
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }.listStyle(.insetGrouped)
    }
}

/// Records public controller lifecycle/trait values only during the measured
/// resume. Buffering avoids console/file I/O from inside a layout callback.
final class TabProbeHostingController: UIHostingController<AnyView> {
    var capture = false
    var events: [[String: Any]] = []

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        record("willAppear")
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        record("didAppear")
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        record("layout")
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        record("safeArea")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        record("traits", previousStyle: previousTraitCollection?.userInterfaceStyle.rawValue)
    }

    private func record(_ event: String, previousStyle: Int? = nil) {
        guard capture else { return }
        var sample: [String: Any] = ["event": event, "time": CACurrentMediaTime(),
            "inWindow": viewIfLoaded?.window != nil,
            "style": traitCollection.userInterfaceStyle.rawValue,
            "scale": traitCollection.displayScale,
            "sizeCategory": traitCollection.preferredContentSizeCategory.rawValue,
            "horizontalSizeClass": traitCollection.horizontalSizeClass.rawValue,
            "verticalSizeClass": traitCollection.verticalSizeClass.rawValue,
            "safeTop": viewIfLoaded?.safeAreaInsets.top ?? -1,
            "safeBottom": viewIfLoaded?.safeAreaInsets.bottom ?? -1]
        if let previousStyle { sample["previousStyle"] = previousStyle }
        events.append(sample)
    }
}
