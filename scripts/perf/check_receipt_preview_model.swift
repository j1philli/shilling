// Run on macOS:
// xcrun swiftc -swift-version 5 -parse-as-library \
//   app/ios-app/src/ReceiptPreviewModel.swift scripts/perf/check_receipt_preview_model.swift \
//   -o /tmp/shilling-preview-check && /tmp/shilling-preview-check
import Foundation

@main
struct ReceiptPreviewCheck {
    @MainActor
    static func main() async throws {
        let manager = FileManager.default
        let temporary = URL(fileURLWithPath: NSTemporaryDirectory())
        func file(_ prefix: String) throws -> URL {
            let directory = temporary.appendingPathComponent(prefix + UUID().uuidString)
            try manager.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appendingPathComponent("synthetic.txt")
            try Data("synthetic".utf8).write(to: url)
            return url
        }
        func waitForRemoval(_ url: URL) async throws {
            let deadline = Date().addingTimeInterval(10)
            while manager.fileExists(atPath: url.path), Date() < deadline {
                try await Task.sleep(for: .milliseconds(20))
            }
            precondition(!manager.fileExists(atPath: url.path), "Managed copy was not removed")
        }
        let first = try file("receipt-preview-"), second = try file("receipt-preview-")
        let third = try file("receipt-preview-"), unrelated = try file("shilling-preview-check-")
        defer {
            for url in [first, second, third, unrelated] {
                try? manager.removeItem(at: url.deletingLastPathComponent())
            }
        }
        var model: ReceiptPreviewModel? = ReceiptPreviewModel()
        model!.url = first
        model!.url = first
        model!.url = second
        try await waitForRemoval(first)
        precondition(manager.fileExists(atPath: second.path), "Current preview was removed")
        model!.url = nil
        try await waitForRemoval(second)
        model!.url = third
        weak var released = model
        model = nil
        precondition(released == nil)
        try await waitForRemoval(third)
        let other = ReceiptPreviewModel()
        other.url = unrelated
        other.url = nil
        try await Task.sleep(for: .milliseconds(100))
        precondition(manager.fileExists(atPath: unrelated.path), "Unrelated file was removed")
        print("PASS: replacement, dismissal, destruction, unrelated-file protection")
    }
}
