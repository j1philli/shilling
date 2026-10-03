import SwiftUI
import KotlinModules

/// Native Home: the same tiles and copy as the Compose Home, driven by the shared `HomeViewModel`.
struct HomeScreen: View {
    @StateObject private var model: HomeModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    let onDestination: (HomeDestination) -> Void

    init(createModel: @escaping @MainActor () -> HomeModel = { HomeModel() },
         onDestination: @escaping (HomeDestination) -> Void) {
        _model = StateObject(wrappedValue: createModel())
        self.onDestination = onDestination
    }

    var body: some View {
        let state = model.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(state.dateLabel)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if sizeClass == .regular {
                        wideLayout(state)
                    } else {
                        compactLayout(state)
                    }
                }
                .padding(.horizontal)
                .padding(.bottom)
                .frame(maxWidth: 1000)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(state.greeting)
        }
        .task { await model.observe() }
    }

    @ViewBuilder
    private func compactLayout(_ state: HomeUiState) -> some View {
        balanceCard(state)
        HStack(alignment: .top, spacing: 12) {
            weekTile(state, preview: 2)
            monthTile(state)
        }
        .fixedSize(horizontal: false, vertical: true)
        activityTile(state)
        HStack(alignment: .top, spacing: 12) {
            schedulesTile(state)
            receiptsTile(state)
        }
        .fixedSize(horizontal: false, vertical: true)
        categoriesTile(state)
    }

    @ViewBuilder
    private func wideLayout(_ state: HomeUiState) -> some View {
        HStack(alignment: .top, spacing: 12) {
            balanceCard(state)
            VStack(spacing: 12) {
                weekTile(state, preview: 0)
                monthTile(state)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        activityTile(state)
        HStack(alignment: .top, spacing: 12) {
            schedulesTile(state)
            receiptsTile(state)
            categoriesTile(state)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Tiles

    private func balanceCard(_ state: HomeUiState) -> some View {
        Button { onDestination(.accounts) } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Total balance").font(.subheadline.weight(.medium))
                    Spacer()
                    TileIcon(systemName: "building.columns", tint: .accentColor)
                }
                Text(state.balanceValue)
                    .font(.system(.largeTitle, design: .rounded).weight(.bold))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Text(state.balanceCaption)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(20)
            .background(
                LinearGradient(
                    colors: [Color.accentColor.opacity(0.22), Color(.secondarySystemGroupedBackground)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                in: RoundedRectangle(cornerRadius: 24, style: .continuous)
            )
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    private func weekTile(_ state: HomeUiState, preview: Int) -> some View {
        HomeTile(label: "This week", value: state.weeklyValue, caption: state.weeklyCaption,
                 systemImage: "calendar", tint: .accentColor) {
            onDestination(.week)
        } extra: {
            ForEach(Array(state.upcoming.prefix(preview)), id: \.self) { item in
                MiniRow(title: item.tx.title,
                        trailing: FormattingKt.formatSigned(type: item.tx.type, amount: item.tx.amount),
                        trailingColor: item.tx.type.amountColor)
            }
        }
    }

    private func monthTile(_ state: HomeUiState) -> some View {
        HomeTile(label: "This month", value: state.monthValue, caption: state.monthCaption,
                 systemImage: "chart.bar", tint: state.monthNet >= 0 ? .green : .red, valueLines: 1) {
            onDestination(.month)
        }
    }

    private func activityTile(_ state: HomeUiState) -> some View {
        HomeTile(label: "Activity", value: state.activityValue, caption: state.activityCaption,
                 systemImage: "clock.arrow.circlepath", tint: .secondary) {
            onDestination(.activity)
        } extra: {
            ForEach(state.recent, id: \.id) { item in
                MiniRow(title: item.title,
                        trailing: item.amount,
                        trailingColor: item.type.amountColor,
                        dot: Color(hex: item.categoryColor))
            }
        }
    }

    private func schedulesTile(_ state: HomeUiState) -> some View {
        HomeTile(label: "Schedules", value: state.scheduleValue, caption: state.scheduleCaption,
                 systemImage: "arrow.left.arrow.right", tint: .accentColor) {
            onDestination(.schedules)
        }
    }

    private func receiptsTile(_ state: HomeUiState) -> some View {
        let tint: Color = state.receiptCount == 0 ? .secondary : (state.unattachedReceipts == 0 ? .green : .accentColor)
        return HomeTile(label: "Receipts", value: state.receiptsValue, caption: state.receiptsCaption,
                        systemImage: "doc.text", tint: tint) {
            onDestination(.receipts)
        }
    }

    private func categoriesTile(_ state: HomeUiState) -> some View {
        HomeTile(label: "Categories", value: state.categoriesValue, caption: state.categoriesCaption,
                 systemImage: "tag", tint: .secondary) {
            onDestination(.categories)
        } extra: {
            ForEach(state.topCategories, id: \.self) { total in
                MiniRow(title: total.category?.name ?? "Uncategorized",
                        trailing: FormattingKt.formatCurrency(amount: total.total),
                        trailingColor: .primary,
                        dot: Color(hex: total.category?.color) ?? Color(.tertiaryLabel))
            }
        }
    }
}

// MARK: - Building blocks

private struct HomeTile<Extra: View>: View {
    let label: String
    let value: String
    let caption: String?
    let systemImage: String
    let tint: Color
    let valueLines: Int
    let action: () -> Void
    @ViewBuilder let extra: Extra

    init(label: String, value: String, caption: String?, systemImage: String, tint: Color, valueLines: Int = 2,
         action: @escaping () -> Void, @ViewBuilder extra: () -> Extra = { EmptyView() }) {
        self.label = label
        self.value = value
        self.caption = caption
        self.systemImage = systemImage
        self.tint = tint
        self.valueLines = valueLines
        self.action = action
        self.extra = extra()
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(label)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text(value)
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(tint == .secondary ? Color.primary : tint)
                            .lineLimit(valueLines)
                            .minimumScaleFactor(valueLines == 1 ? 0.6 : 1)
                        if let caption {
                            Text(caption)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                    Spacer(minLength: 8)
                    TileIcon(systemName: systemImage, tint: tint)
                }
                extra
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(16)
            .background(Color(.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct TileIcon: View {
    let systemName: String
    let tint: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.body.weight(.semibold))
            .foregroundStyle(tint)
            .frame(width: 36, height: 36)
            .background(tint.opacity(0.14), in: Circle())
            .accessibilityHidden(true)
    }
}

private struct MiniRow: View {
    let title: String
    let trailing: String
    var trailingColor: Color = .primary
    var dot: Color? = nil

    var body: some View {
        HStack(spacing: 6) {
            if let dot {
                Circle().fill(dot).frame(width: 8, height: 8)
            }
            Text(title).lineLimit(1)
            Spacer(minLength: 8)
            // Amounts keep their width; the title truncates instead.
            Text(trailing)
                .fontWeight(.medium)
                .foregroundStyle(trailingColor)
                .lineLimit(1)
                .fixedSize()
        }
        .font(.footnote)
    }
}

extension ScheduleType {
    /// Income reads positive, transfers are neutral, expenses are plain.
    var amountColor: Color {
        switch self {
        case .income: return .green
        case .transfer: return .secondary
        default: return .primary
        }
    }
}

extension Color {
    /// `#RRGGBB` / `#AARRGGBB` category colors; nil when missing or malformed.
    init?(hex: String?) {
        guard var text = hex?.trimmingCharacters(in: .whitespaces) else { return nil }
        if text.hasPrefix("#") { text.removeFirst() }
        guard let value = UInt64(text, radix: 16), text.count == 6 || text.count == 8 else { return nil }
        let argb = text.count == 6 ? (0xFF00_0000 | value) : value
        self.init(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }
}
