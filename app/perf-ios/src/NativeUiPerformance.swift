import SwiftUI
import UIKit
import KotlinModules
import KMPNativeCoroutinesAsync
import os
import QuickLook
import ImageIO
import UniformTypeIdentifiers

struct NativeUiPerformanceView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { NativeUiPerformanceController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// Uses production SwiftUI files and a synthetic Store5 graph, with optional local hosted metadata.
@MainActor
final class NativeUiPerformanceController: UIViewController {
    private var host: UIViewController?
    private var work: Task<Void, Never>?
    private var displayLink: CADisplayLink?
    private var frameGaps: [Double] = []
    private var previousFrame: CFTimeInterval?
    private var scrollView: UIScrollView?
    private var scrolling = false
    private let start = CACurrentMediaTime()
    private let signposter = OSSignposter(subsystem: "finance.shilling.perf", category: "PointsOfInterest")
    private var interval: OSSignpostIntervalState?
    private var phaseCpu = 0.0
    private var phaseStarted = 0.0
    private let reportURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("native-ui.jsonl")

    override func viewDidLoad() {
        super.viewDidLoad()
        UIApplication.shared.isIdleTimerDisabled = true
        view.backgroundColor = .systemBackground
        mount(AnyView(ProgressView("Preparing synthetic data…")))
        try? Data().write(to: reportURL)
        work = Task { await run() }
    }

    private func run() async {
        do {
            _ = try await asyncFunction(for: NativeUiFixture.shared.seed())
            emit(["event": "seeded", "postings": 10000, "schedules": 1000, "receipts": 250])
            let args = ProcessInfo.processInfo.arguments
            let requested = args.firstIndex(of: "--ui-screen").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
            let screens = requested.map { [$0] } ?? ["activity", "receipts", "plan"]
            for screen in screens {
                try Task.checkCancellation()
                if screen == "home" { try await runHome(); continue }
                if screen == "editors" { try await runEditors(); continue }
                if screen == "editor-navigation" { try await runEditorNavigation(); continue }
                if screen == "editor-choices" { try await runEditorChoices(); continue }
                if screen == "editor-save" { try await runEditorSave(); continue }
                if screen == "plan-transitions" { try await runPlanTransitions(); continue }
                if screen == "hosted-settings" { try await runHostedSettings(); continue }
                if screen == "tabs" { try await runTabs(); continue }
                if screen == "tab-resume" { try await runTabResume(); continue }
                if screen == "loaded-empty" {
                    try await checkLoadedEmptyStates()
                    await pause(6)
                    emit(["event": "empty-states-closed", "database": querySnapshot()])
                    continue
                }
                if screen == "receipt-previews" { try await runReceiptPreviews(); continue }
                if screen == "import-regression" {
                    let checks = try await asyncFunction(for: NativeUiFixture.shared.checkImportRegression())
                    mount(AnyView(Text("Import regression passed")))
                    await pause(6)
                    emit(["event": "import-checked", "checks": checks, "database": querySnapshot()])
                    continue
                }
                begin(screen, "load")
                switch screen {
                case "activity": mount(AnyView(ActivityScreen()))
                case "receipts": mount(AnyView(ReceiptsScreen()))
                case "plan": mount(AnyView(PlanScreen()))
                default: throw FixtureError("Unknown screen")
                }
                let minimum = screen == "activity" ? 7000 : screen == "receipts" ? 250 : 1000
                let list = try await waitForList(minimum: minimum)
                let initialPlanItems = screen == "plan" ? itemCount(list) : 0
                // Allow the populated collection to reach a display callback before ending load.
                await pause(0.05)
                end(screen, "load", extra: ["listItems": itemCount(list), "visibleCells": list.visibleCells.count])
                await pause(1)
                screenshot(screen)

                begin(screen, "scroll")
                scrollView = list
                scrolling = true
                await pause(5)
                scrolling = false
                scrollView = nil
                end(screen, "scroll", extra: ["contentOffsetY": list.contentOffset.y, "listItems": itemCount(list)])

                if screen == "plan" {
                    list.setContentOffset(CGPoint(x: 0, y: -list.adjustedContentInset.top), animated: false)
                    await pause(0.3)
                    try selectSegment("Accounts")
                    await pause(7) // Exceeds WhileSubscribed's 5-second stop timeout.
                    guard itemCount(list) < 100 && itemCount(list) >= 10 else {
                        throw FixtureError("Plan Accounts section did not become visible")
                    }
                    screenshot("plan-accounts")
                    begin(screen, "hidden-sections")
                    for i in 0..<10 {
                        _ = try await asyncFunction(for: NativeUiFixture.shared.changePosting(index: Int32(i)))
                        await pause(0.3)
                    }
                    await pause(1)
                    end(screen, "hidden-sections", extra: ["mutations": 10, "listItems": itemCount(list)])
                    // Keep the section visible for a separate steady CPU window in Instruments.
                    begin(screen, "idle")
                    await pause(5)
                    end(screen, "idle")
                    if args.contains("--ui-check-navigation") {
                        // Exercise resubscription after Overview has stopped, then each section.
                        for (title, minimum, maximum) in [("Overview", initialPlanItems - 1, initialPlanItems + 2),
                                                         ("By category", 40, 100),
                                                         ("By day", initialPlanItems - 1, initialPlanItems + 2),
                                                         ("Schedules", 1000, 1003),
                                                         ("Categories", 41, 42), ("Accounts", 11, 12)] {
                            try selectSegment(title)
                            let rendered = try await waitForList(minimum: minimum, maximum: maximum)
                            emit(["event": "navigation-check", "section": title, "listItems": itemCount(rendered)])
                            await pause(0.2)
                            screenshot("check-\(title.lowercased().replacingOccurrences(of: " ", with: "-"))")
                        }
                    }
                }
                if !args.contains("--ui-hold") {
                    mount(AnyView(Text("Completed \(screen)")))
                    await pause(6)
                }
            }
            emit(["event": "complete"])
        } catch {
            stopFrames()
            emit(["event": "failed", "reason": String(describing: error)])
        }
    }

    private func runHome() async throws {
        begin("home", "load")
        let model = HomeModel()
        mount(AnyView(HomeScreen(createModel: { model }, onDestination: { _ in })))
        let deadline = CACurrentMediaTime() + 30
        while model.state.accountCount != 10 || model.state.recent.count != 3 ||
                model.state.weeklyValue != "1000 due" || model.state.receiptCount != 250 {
            guard CACurrentMediaTime() < deadline else { throw FixtureError("Home did not populate") }
            await pause(0.02)
        }
        await pause(0.05)
        guard let list = descendants(view).compactMap({ $0 as? UIScrollView }).first,
              list.contentSize.height > 100 else { throw FixtureError("Home did not render") }
        end("home", "load", extra: ["recentRows": model.state.recent.count, "weeklyValue": model.state.weeklyValue])
        await pause(1)
        screenshot("home")
        begin("home", "updates")
        for i in 0..<10 {
            _ = try await asyncFunction(for: NativeUiFixture.shared.changePosting(index: Int32(i)))
            await pause(0.3)
        }
        await pause(1)
        end("home", "updates")
        try await verifyHomeAccountUpdates(model)
        begin("home", "scroll")
        scrollView = list
        scrolling = true
        await pause(3)
        scrolling = false
        scrollView = nil
        end("home", "scroll", extra: ["contentOffsetY": list.contentOffset.y])
        screenshot("home-scrolled")
    }

    private func verifyHomeAccountUpdates(_ home: HomeModel) async throws {
        let editor = AccountEditorScreenModel(accountId: "ui-account-0")
        defer { editor.close() }
        let deadline = CACurrentMediaTime() + 5
        while editor.state.load != .ready {
            guard CACurrentMediaTime() < deadline else { throw FixtureError("Account editor did not load") }
            await pause(0.02)
        }
        let original = editor.state.balanceText
        guard let balance = Double(original) else { throw FixtureError("Unexpected synthetic balance") }
        let total = home.state.totalBalance
        do {
            for (text, expected) in [(String(balance + 1), total + 1), (original, total)] {
                editor.setBalanceText(value: text)
                guard try await asyncFunction(for: editor.save()) != nil else { throw FixtureError("Account save failed") }
                let deadline = CACurrentMediaTime() + 5
                while abs(home.state.totalBalance - expected) > 0.001 {
                    guard CACurrentMediaTime() < deadline else { throw FixtureError("Home did not publish account change") }
                    await pause(0.02)
                }
            }
        } catch {
            editor.setBalanceText(value: original)
            _ = try? await asyncFunction(for: editor.save())
            throw error
        }
        emit(["event": "home-live-account-check", "restoredTotalBalance": home.state.totalBalance])
    }

    private func runEditors() async throws {
        mount(AnyView(Text("Editor lifecycle check")))
        await pause(1)
        for cycle in 0..<3 {
            for kind in ["account", "category", "schedule", "transaction"] {
                let revision = EditorRevision()
                begin("editors", "open-\(kind)")
                mount(AnyView(EditorLifecycleView(kind: kind, revision: revision)))
                let count = itemCount(try await waitForList(minimum: 2))
                let deadline = CACurrentMediaTime() + 10
                while !descendants(view).compactMap({ $0 as? UITextField })
                    .contains(where: { ($0.text ?? "").hasPrefix("Synthetic") }) {
                    guard CACurrentMediaTime() < deadline else { throw FixtureError("\(kind) editor fields not loaded") }
                    await pause(0.02)
                }
                await pause(0.05)
                end("editors", "open-\(kind)", extra: ["cycle": cycle, "listItems": count])
                if cycle == 0 { screenshot("editor-\(kind)") }
                begin("editors", "rebuild-\(kind)")
                for i in 1...20 { revision.value = i; await pause(0.04) }
                end("editors", "rebuild-\(kind)", extra: ["cycle": cycle, "parentUpdates": 20])
                weak var previousHost = host
                mount(AnyView(Text("Editor closed")))
                await pause(1)
                emit(["event": "editor-closed", "editor": kind, "cycle": cycle,
                      "controllerReleased": previousHost == nil,
                      "database": querySnapshot()])
            }
        }
        await pause(6)
        begin("editors", "after-close-updates")
        for i in 0..<10 {
            _ = try await asyncFunction(for: NativeUiFixture.shared.changePosting(index: Int32(i)))
            await pause(0.1)
        }
        await pause(1)
        end("editors", "after-close-updates")
    }

    private func runEditorSave() async throws {
        let route = EditorNavigationRoute()
        mount(AnyView(EditorNavigationFixture(route: route)))
        _ = try await waitForList(minimum: 4)
        for kind in ["account", "category", "schedule", "transaction"] {
            withAnimation { route.path = [kind] }
            let deadline = CACurrentMediaTime() + 10
            var field: UITextField?
            while field == nil {
                field = descendants(view).compactMap { $0 as? UITextField }
                    .first { ($0.text ?? "").hasPrefix("Synthetic \(kind)") }
                guard CACurrentMediaTime() < deadline else { throw FixtureError("Save-check editor did not load") }
                if field == nil { await pause(0.02) }
            }
            guard let field, let original = field.text else { throw FixtureError("Missing editor name") }
            await pause(0.6)
            guard field.becomeFirstResponder() else { throw FixtureError("Name could not focus") }
            await pause(0.6)
            let bars = descendants(view).compactMap { $0 as? UINavigationBar }
            let items = bars.flatMap { bar in
                (bar.topItem?.rightBarButtonItems ?? []) + (bar.topItem?.trailingItemGroups.flatMap(\.barButtonItems) ?? [])
                    + (bar.topItem?.pinnedTrailingGroup?.barButtonItems ?? [])
            }
            guard let save = items.first(where: { $0.title == "Save" || $0.primaryAction?.title == "Save" }),
                  save.isEnabled, save.action != nil || save.primaryAction != nil else {
                emit(["event": "save-controls", "items": items.map {
                    ["title": $0.title ?? "", "action": $0.action.map(NSStringFromSelector) ?? "",
                     "primary": $0.primaryAction?.title ?? "", "custom": $0.customView.map { String(describing: type(of: $0)) } ?? ""]
                }, "buttons": descendants(view).compactMap { $0 as? UIButton }.map {
                    ["title": $0.currentTitle ?? "", "accessibility": $0.accessibilityLabel ?? ""]
                }])
                screenshot("save-controls")
                throw FixtureError("Native Save action unavailable")
            }
            let typedAt = CACurrentMediaTime()
            field.insertText(" save probe")
            await pause(0.04) // Let SwiftUI publish the local draft, inside its 120 ms debounce.
            guard let expected = field.text, expected == original + " save probe" else { throw FixtureError("Name insert failed") }
            let saveDelayMs = (CACurrentMediaTime() - typedAt) * 1000
            guard saveDelayMs < 120 else { throw FixtureError("Rapid-save check missed the debounce window") }
            if let action = save.primaryAction {
                UIControl().sendAction(action)
            } else if let action = save.action {
                guard UIApplication.shared.sendAction(action, to: save.target, from: save, for: nil) else {
                    throw FixtureError("Native Save action did not dispatch")
                }
            }
            await pause(1)
            let saved: String
            if kind == "account" {
                let model = AccountEditorScreenModel(accountId: "ui-account-0")
                defer { model.close() }
                let deadline = CACurrentMediaTime() + 5
                while model.state.load != .ready {
                    guard CACurrentMediaTime() < deadline else { throw FixtureError("Saved account did not load") }
                    await pause(0.02)
                }
                saved = model.state.name
                model.setName(value: original)
                guard try await asyncFunction(for: model.save()) != nil else { throw FixtureError("Account restore failed") }
            } else if kind == "category" {
                let model = CategoryEditorScreenModel(categoryId: "ui-category-0")
                defer { model.close() }
                let deadline = CACurrentMediaTime() + 5
                while model.state.load != .ready {
                    guard CACurrentMediaTime() < deadline else { throw FixtureError("Saved category did not load") }
                    await pause(0.02)
                }
                saved = model.state.name
                model.setName(value: original)
                guard try await asyncFunction(for: model.save()) != nil else { throw FixtureError("Category restore failed") }
            } else if kind == "schedule" {
                let model = ScheduleEditorScreenModel(scheduleId: "ui-schedule-0", presetType: nil)
                defer { model.close() }
                let deadline = CACurrentMediaTime() + 5
                while model.state.load != .ready {
                    guard CACurrentMediaTime() < deadline else { throw FixtureError("Saved schedule did not load") }
                    await pause(0.02)
                }
                saved = model.state.fields.title
                model.setTitle(value: original)
                guard try await asyncFunction(for: model.save()) != nil else { throw FixtureError("Schedule restore failed") }
            } else {
                let model = TransactionEditorScreenModel(postingId: "ui-posting-0")
                defer { model.close() }
                let deadline = CACurrentMediaTime() + 5
                while model.state.load != .ready {
                    guard CACurrentMediaTime() < deadline else { throw FixtureError("Saved transaction did not load") }
                    await pause(0.02)
                }
                saved = model.state.fields.title
                model.setTitle(value: original)
                guard try await asyncFunction(for: model.save()) != nil else { throw FixtureError("Transaction restore failed") }
            }
            guard saved == expected, route.path.isEmpty else { throw FixtureError("Rapid save did not persist the complete name and dismiss") }
            emit(["event": "editor-save-checked", "editor": kind, "saveDelayMs": saveDelayMs, "restored": true])
        }
    }

    private func runEditorChoices() async throws {
        let route = EditorNavigationRoute()
        mount(AnyView(EditorNavigationFixture(route: route)))
        _ = try await waitForList(minimum: 4)
        for kind in ["schedule", "transaction"] {
            withAnimation { route.path = [kind] }
            let deadline = CACurrentMediaTime() + 10
            while !descendants(view).contains(where: { ($0 as? UITextField)?.text?.hasPrefix("Synthetic \(kind)") == true }) {
                guard CACurrentMediaTime() < deadline else { throw FixtureError("Choice editor did not load") }
                await pause(0.02)
            }
            await pause(0.7)
            let category = try choiceButton("Category")
            let original = category.accessibilityValue ?? ""
            for title in ["Synthetic category 1", "Uncategorized", original] {
                begin("editor-choices", "\(kind)-category-menu")
                let actions = try await openChoices(category)
                end("editor-choices", "\(kind)-category-menu", extra: ["choices": actions.count])
                guard actions.count == 41, actions.filter({ $0.state == .on }).count == 1,
                      actions.contains(where: { $0.state == .on && $0.title == category.accessibilityValue }),
                      let action = actions.first(where: { $0.title == title }) else {
                    throw FixtureError("Category choices or current selection were incorrect")
                }
                await pause(0.35) // Capture the presented menu, after its opening animation.
                screenshot("\(kind)-category-menu")
                category.sendAction(action)
                category.contextMenuInteraction?.dismissMenu()
                await pause(0.5)
                guard category.accessibilityValue == title else { throw FixtureError("Category selection did not reach the editor") }
            }
            if kind == "schedule" {
                try selectSegment("Transfer")
                await pause(0.5)
            }
            let from = try choiceButton("From account")
            let oldFrom = from.accessibilityValue ?? ""
            let accounts = try await openChoices(from)
            guard accounts.count == 10, let changed = accounts.first(where: { $0.title == "Synthetic account 2" }) else {
                throw FixtureError("Account choices were incorrect")
            }
            from.sendAction(changed)
            from.contextMenuInteraction?.dismissMenu()
            await pause(0.5)
            guard from.accessibilityValue == changed.title else { throw FixtureError("Account selection did not reach the editor") }
            let to = try choiceButton("To account")
            let destinations = try await openChoices(to)
            guard destinations.count == 10, !destinations.contains(where: { $0.title == changed.title }),
                  destinations.contains(where: { $0.title == oldFrom }) else {
                throw FixtureError("Transfer choices did not refresh after changing the source account")
            }
            to.contextMenuInteraction?.dismissMenu()
            await pause(0.5)
            screenshot("\(kind)-choices")
            let fields = descendants(view).compactMap { $0 as? UITextField }
            guard let titleField = fields.first(where: { ($0.text ?? "").hasPrefix("Synthetic \(kind)") }),
                  let amountField = fields.first(where: { $0.keyboardType == .decimalPad }) else {
                throw FixtureError("Editor keyboard fields were not available")
            }
            try await verifyKeyboardDone(titleField)
            guard titleField.becomeFirstResponder() else { throw FixtureError("Title could not refocus") }
            await pause(0.2)
            // Move directly between fields without dismissing the keyboard. A late
            // resignation from the old field must not hide the new field's Done button.
            try await verifyKeyboardDone(amountField)
            withAnimation { route.path = [] }
            await pause(1)
            emit(["event": "choices-checked", "editor": kind, "categoryChoices": 41,
                  "accountChoices": 10, "transferChoices": 9, "keyboardDoneChecks": 2,
                  "directFocusSwitchChecked": true, "database": querySnapshot()])
        }
    }

    private func verifyKeyboardDone(_ field: UITextField) async throws {
        guard field.becomeFirstResponder() else { throw FixtureError("Editor field could not focus") }
        await pause(0.7)
        let windows = view.window?.windowScene?.windows ?? []
        let items = windows.flatMap { descendants($0) }.compactMap { $0 as? UIToolbar }.flatMap { $0.items ?? [] }
        guard let done = items.first(where: { $0.title == "Done" }), let action = done.action,
              UIApplication.shared.sendAction(action, to: done.target, from: done, for: nil) else {
            throw FixtureError("Editor keyboard Done button was unavailable: \(items.map { $0.title ?? String(describing: $0.customView) })")
        }
        await pause(0.4)
        guard !field.isFirstResponder else { throw FixtureError("Done did not dismiss the editor keyboard") }
    }

    private func choiceButton(_ label: String) throws -> UIButton {
        guard let button = descendants(view).compactMap({ $0 as? UIButton })
            .first(where: { $0.accessibilityLabel == label && $0.menu != nil && $0.window != nil }),
              button.isEnabled, button.bounds.width > 0 else { throw FixtureError("\(label) choice button is unavailable") }
        guard button.isAccessibilityElement, button.bounds.width > 250 else {
            throw FixtureError("\(label) choice button lost its accessibility element or full row target")
        }
        var ancestor: UIView? = button
        while let current = ancestor {
            guard !current.accessibilityElementsHidden else { throw FixtureError("\(label) choices are hidden from accessibility") }
            ancestor = current.superview
        }
        return button
    }

    private func openChoices(_ button: UIButton) async throws -> [UIAction] {
        func actions(in menu: UIMenu) -> [UIAction] {
            menu.children.flatMap { element -> [UIAction] in
                if let action = element as? UIAction { return [action] }
                if let submenu = element as? UIMenu { return actions(in: submenu) }
                return []
            }
        }
        button.performPrimaryAction()
        let deadline = CACurrentMediaTime() + 5
        var visible: [UIAction] = []
        while visible.isEmpty {
            await pause(0.05)
            button.contextMenuInteraction?.updateVisibleMenu { menu in
                visible = actions(in: menu)
                return menu
            }
            guard CACurrentMediaTime() < deadline else { throw FixtureError("Native choice menu did not present") }
        }
        return visible
    }

    private func runEditorNavigation() async throws {
        let route = EditorNavigationRoute()
        mount(AnyView(EditorNavigationFixture(route: route)))
        _ = try await waitForList(minimum: 4)
        await pause(0.5)
        for cycle in 0..<2 {
            for kind in ["account", "category", "schedule", "transaction"] {
                begin("editor-navigation", "push-\(kind)")
                withAnimation { route.path = [kind] }
                let deadline = CACurrentMediaTime() + 10
                var field: UITextField?
                while field == nil {
                    field = descendants(view).compactMap { $0 as? UITextField }
                        .first { ($0.text ?? "").hasPrefix("Synthetic \(kind)") && $0.window != nil }
                    guard CACurrentMediaTime() < deadline else { throw FixtureError("\(kind) did not render after push") }
                    if field == nil { await pause(0.02) }
                }
                await pause(0.05)
                end("editor-navigation", "push-\(kind)", extra: ["cycle": cycle])
                // Field readiness precedes the end of the push animation. Record
                // its tail separately, and let it settle before focusing a field.
                begin("editor-navigation", "push-settle-\(kind)")
                await pause(0.4)
                end("editor-navigation", "push-settle-\(kind)", extra: ["cycle": cycle])
                if cycle == 0 { screenshot("navigation-\(kind)") }

                guard let field else { throw FixtureError("\(kind) field disappeared") }
                begin("editor-navigation", "focus-\(kind)")
                guard field.becomeFirstResponder() else { throw FixtureError("\(kind) field could not focus") }
                await pause(0.4)
                end("editor-navigation", "focus-\(kind)", extra: ["cycle": cycle])

                begin("editor-navigation", "typing-\(kind)")
                for _ in 0..<10 {
                    field.insertText("q")
                    await pause(0.05)
                }
                guard field.text?.hasSuffix("qqqqqqqqqq") == true else {
                    throw FixtureError("\(kind) typing was not shown in the native field")
                }
                // Include the last debounced model update in the measurement.
                await pause(0.2)
                end("editor-navigation", "typing-\(kind)", extra: ["cycle": cycle, "keystrokes": 10, "settleMs": 200])
                field.resignFirstResponder()
                await pause(0.2)

                begin("editor-navigation", "pop-\(kind)")
                withAnimation { route.path = [] }
                await pause(1)
                end("editor-navigation", "pop-\(kind)", extra: ["cycle": cycle])
                emit(["event": "navigation-closed", "editor": kind, "cycle": cycle,
                      "database": querySnapshot()])
            }
        }
        await pause(6)
        begin("editor-navigation", "after-close-updates")
        for i in 0..<10 {
            _ = try await asyncFunction(for: NativeUiFixture.shared.changePosting(index: Int32(i)))
            await pause(0.1)
        }
        await pause(1)
        end("editor-navigation", "after-close-updates")
    }

    private func runPlanTransitions() async throws {
        mount(AnyView(PlanScreen()))
        let overviewItems = itemCount(try await waitForList(minimum: 1000))
        await pause(0.5)
        for cycle in 0..<2 {
            for (title, minimum, maximum) in [("Schedules", 1000, 1003), ("Categories", 41, 42),
                                             ("Accounts", 11, 12), ("Overview", overviewItems - 1, overviewItems + 2),
                                             ("By category", 40, 100), ("By day", overviewItems - 1, overviewItems + 2)] {
                begin("plan-transitions", title)
                try selectSegment(title)
                let list = try await waitForList(minimum: minimum, maximum: maximum)
                await pause(0.5)
                end("plan-transitions", title, extra: ["cycle": cycle, "listItems": itemCount(list)])
            }
        }
        mount(AnyView(Text("Plan closed")))
        await pause(6)
        emit(["event": "plan-closed", "database": querySnapshot()])
    }

    private func runHostedSettings() async throws {
        let tabs = ShillingTabBarController()
        mountController(tabs)
        guard let settings = tabs.tabs.first(where: { $0.identifier == "SETTINGS" }),
              let home = tabs.tabs.first(where: { $0.identifier == "HOME" }) else { throw FixtureError("Missing Settings/Home tab") }
        begin("hosted-settings", "visible")
        tabs.selectedTab = settings
        await pause(35)
        screenshot("hosted-settings")
        end("hosted-settings", "visible")
        tabs.selectedTab = home
        await pause(1)
        begin("hosted-settings", "hidden")
        await pause(35)
        end("hosted-settings", "hidden")
        begin("hosted-settings", "reopened")
        tabs.selectedTab = settings
        await pause(5)
        end("hosted-settings", "reopened")
    }

    private func runTabs() async throws {
        var tabs: ShillingTabBarController? = ShillingTabBarController()
        mountController(tabs!)
        for cycle in 0..<6 {
            for key in ["HOME", "PLAN", "ACTIVITY", "RECEIPTS", "SETTINGS"] {
                begin("tabs", "select-\(key)")
                guard let tab = tabs?.tabs.first(where: { $0.identifier == key }) else { throw FixtureError("Missing tab") }
                tabs?.selectedTab = tab
                if key == "PLAN" { _ = try await waitForList(minimum: 1000) }
                if key == "ACTIVITY" { _ = try await waitForList(minimum: 7000) }
                if key == "RECEIPTS" { _ = try await waitForList(minimum: 250) }
                await pause(0.5)
                end("tabs", "select-\(key)", extra: ["cycle": cycle])
            }
            emit(["event": "tab-cycle", "cycle": cycle, "residentMiB": residentMiB(),
                  "tabCount": tabs?.tabs.count ?? 0, "database": querySnapshot()])
        }
        weak var released = tabs
        mount(AnyView(Text("Tab lifetime check")))
        tabs = nil
        await pause(6)
        emit(["event": "tabs-closed", "controllerReleased": released == nil,
              "residentMiB": residentMiB(), "database": querySnapshot()])
        begin("tabs", "after-close-updates")
        for i in 0..<10 { _ = try await asyncFunction(for: NativeUiFixture.shared.changePosting(index: Int32(i))) }
        await pause(1)
        end("tabs", "after-close-updates")
    }

    private func runTabResume() async throws {
        var tabs: ShillingTabBarController? = ShillingTabBarController()
        mountController(tabs!)
        for (key, minimum, maximum) in [("ACTIVITY", 7000, 7600), ("PLAN", 1000, 1010), ("RECEIPTS", 250, 252)] {
            guard let tab = tabs?.tabs.first(where: { $0.identifier == key }),
                  let settings = tabs?.tabs.first(where: { $0.identifier == "SETTINGS" }) else {
                throw FixtureError("Missing tab")
            }
            tabs?.selectedTab = tab
            let list = try await waitForList(minimum: minimum, maximum: maximum)
            await pause(0.5)
            list.setContentOffset(CGPoint(x: 0, y: 1200), animated: false)
            await pause(0.5)
            let previousOffset = list.contentOffset.y
            let previousItems = itemCount(list)
            tabs?.selectedTab = settings
            await pause(7) // Expires the shared StateFlow replay cache.
            begin("tab-resume", key)
            tabs?.selectedTab = tab
            let resumed = try await waitForList(minimum: minimum, maximum: maximum)
            await pause(1)
            end("tab-resume", key, extra: ["beforeOffsetY": previousOffset,
                "afterOffsetY": resumed.contentOffset.y, "beforeItems": previousItems,
                "afterItems": itemCount(resumed)])
            screenshot("resume-\(key.lowercased())")
        }
        weak var released = tabs
        mount(AnyView(Text("Resume check closed")))
        tabs = nil
        await pause(6)
        emit(["event": "resume-closed", "controllerReleased": released == nil, "database": querySnapshot()])
    }

    private func checkLoadedEmptyStates() async throws {
        let activity = ActivityModel()
        let receipts = ReceiptsModel()
        let plan = PlanModel()
        var tasks = [Task { await activity.observe() }, Task { await receipts.observe() },
                     Task { await plan.observe(.schedules) }]
        defer { tasks.forEach { $0.cancel() } }
        func wait(_ condition: () -> Bool) async throws {
            let deadline = CACurrentMediaTime() + 30
            while !condition() {
                guard CACurrentMediaTime() < deadline else { throw FixtureError("Loaded empty state did not update") }
                await pause(0.02)
            }
        }
        try await wait { !activity.state.sections.isEmpty && receipts.state.rows.count == 250 && !plan.schedules.groups.isEmpty }
        activity.screen.setQuery(text: "no synthetic transactions match this query")
        plan.screen.setScheduleFilter(type: .income)
        try await wait { activity.state.empty != nil && activity.state.sections.isEmpty &&
            plan.schedules.empty != nil && plan.schedules.groups.isEmpty }
        _ = try await asyncFunction(for: NativeUiFixture.shared.setReceiptsHidden(hidden: true))
        do {
            try await wait { receipts.state.empty != nil && receipts.state.rows.isEmpty }
            emit(["event": "empty-states-checked", "activity": true, "schedules": true, "receipts": true])
            _ = try await asyncFunction(for: NativeUiFixture.shared.setReceiptsHidden(hidden: false))
        } catch {
            _ = try? await asyncFunction(for: NativeUiFixture.shared.setReceiptsHidden(hidden: false))
            throw error
        }
        activity.screen.setQuery(text: "")
        plan.screen.setScheduleFilter(type: nil)
        try await wait { !activity.state.sections.isEmpty && receipts.state.rows.count == 250 && !plan.schedules.groups.isEmpty }
        emit(["event": "empty-states-restored"])
        tasks[0].cancel()
        await tasks[0].value
        await pause(6)
        _ = try await asyncFunction(for: NativeUiFixture.shared.changePosting(index: 777))
        tasks[0] = Task { await activity.observe() }
        try await wait { activity.state.sections.flatMap(\.rows).contains { $0.title == "Synthetic update 777" } }
        _ = try await asyncFunction(for: NativeUiFixture.shared.changePosting(index: 9))
        try await wait { activity.state.sections.flatMap(\.rows).contains { $0.title == "Synthetic update 9" } }
        emit(["event": "hidden-update-checked"])
    }

    private func runReceiptPreviews() async throws {
        let baseline = ProcessInfo.processInfo.arguments.contains("--ui-preview-baseline")
        let files = try await Task.detached {
            // This fixture owns its entire sandbox. Reset copies left by earlier baseline runs.
            let temporary = URL(fileURLWithPath: NSTemporaryDirectory())
            for directory in try FileManager.default.contentsOfDirectory(at: temporary, includingPropertiesForKeys: nil)
                where directory.lastPathComponent.hasPrefix("receipt-preview-") {
                try FileManager.default.removeItem(at: directory)
            }
            return try makeSyntheticPreviewFiles()
        }.value
        try await measureReceiptPreparation(files, baseline: baseline)
        do {
            for (index, url) in files.enumerated() {
                _ = try await asyncFunction(for: NativeUiFixture.shared.installReceipt(
                    path: url.path, receiptId: "ui-preview-\(index)", name: url.lastPathComponent))
            }
            await pause(2)
            for cycle in 0..<3 {
                for (index, url) in files.enumerated() {
                    let kind = index == 0 ? "jpeg" : "pdf"
                    begin("receipt-previews", "prepare-\(kind)")
                    let path = try await asyncFunction(for: NativeUiFixture.shared.previewReceipt(
                        receiptId: "ui-preview-\(index)", name: url.lastPathComponent))
                    guard let path else { throw FixtureError("Missing preview path") }
                    let size = (try FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)?.int64Value ?? 0
                    end("receipt-previews", "prepare-\(kind)", extra: ["cycle": cycle, "fileBytes": size])
                    var preview: QLPreviewController? = QLPreviewController()
                    preview!.modalPresentationStyle = .overFullScreen
                    var source: FixturePreviewSource? = FixturePreviewSource(URL(fileURLWithPath: path))
                    preview!.dataSource = source
                    let owner = ReceiptPreviewModel()
                    if !baseline { owner.url = URL(fileURLWithPath: path) }
                    begin("receipt-previews", "display-\(kind)")
                    guard let presenter = view.window?.rootViewController else { throw FixtureError("Missing presenter") }
                    await withCheckedContinuation { continuation in
                        presenter.present(preview!, animated: true) { continuation.resume() }
                    }
                    await pause(2)
                    guard preview!.view.window != nil, preview!.currentPreviewItem != nil else { throw FixtureError("Preview not presented") }
                    end("receipt-previews", "display-\(kind)", extra: ["cycle": cycle])
                    if cycle == 0 { screenshot("preview-\(kind)") }
                    weak var released = preview
                    await withCheckedContinuation { continuation in
                        preview!.dismiss(animated: true) { continuation.resume() }
                    }
                    preview = nil
                    source = nil
                    owner.url = nil
                    await pause(0.5)
                    if !baseline {
                        let deadline = CACurrentMediaTime() + 3
                        while temporaryPreviewBytes() > 0, CACurrentMediaTime() < deadline { await pause(0.05) }
                    }
                    emit(["event": "preview-closed", "kind": kind, "cycle": cycle,
                          "controllerReleased": released == nil, "residentMiB": residentMiB(),
                          "temporaryPreviewBytes": temporaryPreviewBytes()])
                }
            }
        } catch {
            for index in files.indices { _ = try? await asyncFunction(for: NativeUiFixture.shared.removeReceipt(receiptId: "ui-preview-\(index)")) }
            throw error
        }
        for index in files.indices { _ = try await asyncFunction(for: NativeUiFixture.shared.removeReceipt(receiptId: "ui-preview-\(index)")) }
    }

    private func measureReceiptPreparation(_ files: [URL], baseline: Bool) async throws {
        guard let image = UIImage(contentsOfFile: files[0].path) else { throw FixtureError("Missing source image") }
        for cycle in 0..<3 {
            begin("receipt-preparation", "read-file")
            await pause(0.05) // Establish display callbacks before synchronous baseline work.
            let fileBytes: Int
            if baseline { fileBytes = try autoreleasepool { try Data(contentsOf: files[1]).count } }
            else { fileBytes = try await ReceiptFilePreparation.read(files[1]).data.count }
            await pause(0.05)
            end("receipt-preparation", "read-file", extra: ["cycle": cycle, "fileBytes": fileBytes])
            begin("receipt-preparation", "encode-jpeg")
            await pause(0.05)
            let jpegBytes: Int
            if baseline { jpegBytes = autoreleasepool { image.jpegData(compressionQuality: 0.85)?.count ?? 0 } }
            else { jpegBytes = await ReceiptFilePreparation.jpeg(image, name: "Synthetic receipt.jpg")?.data.count ?? 0 }
            guard jpegBytes > 0 else { throw FixtureError("JPEG encoding failed") }
            await pause(0.05)
            end("receipt-preparation", "encode-jpeg", extra: ["cycle": cycle, "fileBytes": jpegBytes])
        }
    }

    private func temporaryPreviewBytes() -> Int64 {
        let temp = URL(fileURLWithPath: NSTemporaryDirectory())
        return ((try? FileManager.default.contentsOfDirectory(at: temp, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("receipt-preview-") }
            .reduce(0) { total, directory in
                total + ((try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? [])
                    .reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
            }
    }

    private func querySnapshot() -> [String: Any] {
        guard let data = NativeUiFixture.shared.queryMetrics().data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return value
    }

    private func mount(_ root: AnyView) {
        mountController(UIHostingController(rootView: root))
    }

    private func mountController(_ controller: UIViewController) {
        host?.willMove(toParent: nil)
        host?.view.removeFromSuperview()
        host?.removeFromParent()
        addChild(controller)
        controller.view.frame = view.bounds
        controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(controller.view)
        controller.didMove(toParent: self)
        host = controller
    }

    private func selectSegment(_ title: String) throws {
        guard let picker = descendants(view).compactMap({ $0 as? UISegmentedControl })
            .first(where: { control in (0..<control.numberOfSegments).contains { control.titleForSegment(at: $0) == title } }),
            let index = (0..<picker.numberOfSegments).first(where: { picker.titleForSegment(at: $0) == title }) else {
            throw FixtureError("\(title) picker not rendered")
        }
        picker.selectedSegmentIndex = index
        picker.sendActions(for: .valueChanged)
    }

    private func waitForList(minimum: Int, maximum: Int = .max) async throws -> UICollectionView {
        let deadline = CACurrentMediaTime() + 30
        while CACurrentMediaTime() < deadline {
            if let list = descendants(view).compactMap({ $0 as? UICollectionView })
                .first(where: { itemCount($0) >= minimum && itemCount($0) < maximum && !$0.visibleCells.isEmpty }) { return list }
            await pause(0.05)
        }
        let observed = descendants(view).compactMap { $0 as? UICollectionView }
            .map { "items=\(itemCount($0)),visible=\($0.visibleCells.count)" }
        throw FixtureError("No populated native list rendered within 30 seconds (expected \(minimum)..<\(maximum), observed \(observed))")
    }

    private func itemCount(_ list: UICollectionView) -> Int {
        (0..<list.numberOfSections).reduce(0) { $0 + list.numberOfItems(inSection: $1) }
    }

    private func descendants(_ root: UIView) -> [UIView] {
        [root] + root.subviews.flatMap { descendants($0) }
    }

    private func begin(_ screen: String, _ phase: String) {
        NativeUiFixture.shared.resetQueries()
        frameGaps = []
        previousFrame = nil
        phaseCpu = cpuSeconds()
        phaseStarted = CACurrentMediaTime()
        interval = signposter.beginInterval("NativeUIWorkload", "\(screen, privacy: .public) / \(phase, privacy: .public)")
        displayLink = CADisplayLink(target: self, selector: #selector(frame(_:)))
        displayLink?.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 60, preferred: 60)
        displayLink?.add(to: .main, forMode: .common)
        emit(["event": "begin", "screen": screen, "phase": phase])
    }

    private func end(_ screen: String, _ phase: String, extra: [String: Any] = [:]) {
        stopFrames()
        if let interval { signposter.endInterval("NativeUIWorkload", interval) }
        interval = nil
        let samples = frameGaps.sorted()
        var metrics: [String: Any] = ["event": "measurement", "screen": screen, "phase": phase,
            "elapsedMs": (CACurrentMediaTime() - phaseStarted) * 1000,
            "cpuMs": (cpuSeconds() - phaseCpu) * 1000,
            "displayCallbacks": samples.count, "gapsOver25ms": samples.filter { $0 > 25 }.count,
            "maxCallbackGapMs": samples.last ?? 0,
            "p95CallbackGapMs": samples.isEmpty ? 0 : samples[Int(Double(samples.count - 1) * 0.95)],
            "residentMiB": residentMiB()]
        if let data = NativeUiFixture.shared.queryMetrics().data(using: .utf8),
           let queries = try? JSONSerialization.jsonObject(with: data) { metrics["database"] = queries }
        metrics.merge(extra) { _, new in new }
        emit(metrics)
    }

    @objc private func frame(_ link: CADisplayLink) {
        if let previousFrame { frameGaps.append((link.timestamp - previousFrame) * 1000) }
        previousFrame = link.timestamp
        if scrolling, let scrollView {
            let end = max(0, scrollView.contentSize.height - scrollView.bounds.height)
            let next = min(end, scrollView.contentOffset.y + 8)
            scrollView.setContentOffset(CGPoint(x: 0, y: next), animated: false)
        }
    }

    private func stopFrames() { displayLink?.invalidate(); displayLink = nil }
    private func pause(_ seconds: Double) async { try? await Task.sleep(for: .seconds(seconds)) }

    private func emit(_ values: [String: Any]) {
        var record = values
        record["wallClockSeconds"] = Date().timeIntervalSince1970
        record["sinceStartSeconds"] = CACurrentMediaTime() - start
        record["thermalState"] = ProcessInfo.processInfo.thermalState.rawValue
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "--ui-run"), i + 1 < args.count { record["run"] = args[i + 1] }
        guard let data = try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return }
        print("ShillingNativeUI \(text)")
        fflush(stdout)
        if let output = try? FileHandle(forWritingTo: reportURL) {
            defer { try? output.close() }
            try? output.seekToEnd()
            try? output.write(contentsOf: data + Data([10]))
        }
    }

    private func screenshot(_ name: String) {
        let root: UIView = view.window ?? view
        let image = UIGraphicsImageRenderer(bounds: root.bounds).image { _ in
            root.drawHierarchy(in: root.bounds, afterScreenUpdates: true)
        }
        try? image.pngData()?.write(to: reportURL.deletingLastPathComponent().appendingPathComponent("native-\(name).png"))
    }

    private func cpuSeconds() -> Double {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        return Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec)
            + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1_000_000
    }

    private func residentMiB() -> Double {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Double(info.resident_size) / 1_048_576 : -1
    }

    deinit { work?.cancel(); displayLink?.invalidate() }
}

private struct FixtureError: Error { let message: String; init(_ message: String) { self.message = message } }

private final class FixturePreviewSource: NSObject, QLPreviewControllerDataSource {
    let url: URL
    init(_ url: URL) { self.url = url }
    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
}

private func makeSyntheticPreviewFiles() throws -> [URL] {
    let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("synthetic-receipt-source")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let jpeg = directory.appendingPathComponent("Synthetic 24MP receipt.jpg")
    let pdf = directory.appendingPathComponent("Synthetic 20 page receipt.pdf")
    if FileManager.default.fileExists(atPath: jpeg.path), FileManager.default.fileExists(atPath: pdf.path) { return [jpeg, pdf] }
    let width = 4000, height = 6000
    var pixels = Data(count: width * height * 4)
    pixels.withUnsafeMutableBytes { raw in
        let p = raw.bindMemory(to: UInt8.self)
        var seed: UInt32 = 12345
        for i in stride(from: 0, to: p.count, by: 4) {
            seed = seed &* 1664525 &+ 1013904223
            let shade = UInt8(truncatingIfNeeded: seed >> 24)
            p[i] = shade; p[i + 1] = shade; p[i + 2] = shade; p[i + 3] = 255
        }
    }
    guard let provider = CGDataProvider(data: pixels as CFData),
          let bitmap = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
              bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
              provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent),
          let destination = CGImageDestinationCreateWithURL(jpeg as CFURL, UTType.jpeg.identifier as CFString, 1, nil)
    else { throw FixtureError("Could not create synthetic JPEG") }
    CGImageDestinationAddImage(destination, bitmap, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
    guard CGImageDestinationFinalize(destination) else { throw FixtureError("JPEG write failed") }
    var page = CGRect(x: 0, y: 0, width: 612, height: 792)
    guard let consumer = CGDataConsumer(url: pdf as CFURL), let context = CGContext(consumer: consumer, mediaBox: &page, nil)
    else { throw FixtureError("Could not create synthetic PDF") }
    for _ in 0..<20 {
        context.beginPDFPage(nil)
        context.draw(bitmap, in: page.insetBy(dx: 20, dy: 20))
        context.endPDFPage()
    }
    context.closePDF()
    return [jpeg, pdf]
}

@MainActor
private final class EditorRevision: ObservableObject { @Published var value = 0 }

@MainActor
private final class EditorNavigationRoute: ObservableObject { @Published var path: [String] = [] }

private struct EditorNavigationFixture: View {
    @ObservedObject var route: EditorNavigationRoute

    var body: some View {
        NavigationStack(path: $route.path) {
            List {
                ForEach(["account", "category", "schedule", "transaction"], id: \.self) { kind in
                    NavigationLink(kind.capitalized, value: kind)
                }
            }
            .navigationTitle("Synthetic editors")
            .navigationDestination(for: String.self) { kind in
                switch kind {
                case "account": AccountEditorScreen(accountId: "ui-account-0", onDone: { _ in })
                case "category": CategoryEditorScreen(categoryId: "ui-category-0", onDone: { _ in })
                case "schedule": ScheduleEditorScreen(scheduleId: "ui-schedule-0", presetType: nil, onDone: { _ in })
                default: TransactionEditorScreen(postingId: "ui-posting-0", onDone: { _ in })
                }
            }
        }
    }
}

private struct EditorLifecycleView: View {
    let kind: String
    @ObservedObject var revision: EditorRevision

    var body: some View {
        NavigationStack {
            Group {
                switch kind {
                case "account": AccountEditorScreen(accountId: "ui-account-0", onDone: { _ in })
                case "category": CategoryEditorScreen(categoryId: "ui-category-0", onDone: { _ in })
                case "schedule": ScheduleEditorScreen(scheduleId: "ui-schedule-0", presetType: nil, onDone: { _ in })
                default: TransactionEditorScreen(postingId: "ui-posting-0", onDone: { _ in })
                }
            }
            .overlay(alignment: .bottomTrailing) { Text("Revision \(revision.value)").font(.caption2) }
        }
    }
}
