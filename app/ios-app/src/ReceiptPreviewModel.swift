import Foundation
import Combine

/// Owns the Store5 preview copy for one editor, including dismissal and replacement.
@MainActor
final class ReceiptPreviewModel: ObservableObject {
    @Published var url: URL? {
        didSet {
            guard oldValue != url else { return }
            copy = url.map(ReceiptPreviewCopy.init)
        }
    }
    private var copy: ReceiptPreviewCopy?
}

/// An immutable lease releases its copy even when the editor itself disappears.
private final class ReceiptPreviewCopy {
    let url: URL

    init(_ url: URL) { self.url = url }

    deinit {
        let directory = url.deletingLastPathComponent().standardizedFileURL
        let temporary = URL(fileURLWithPath: NSTemporaryDirectory()).standardizedFileURL
        guard directory.lastPathComponent.hasPrefix("receipt-preview-"),
              directory.deletingLastPathComponent() == temporary else { return }
        DispatchQueue.global(qos: .utility).async {
            try? FileManager.default.removeItem(at: directory)
        }
    }
}
