import Foundation
import UIKit

/// Prepares external picker inputs before passing their bytes to the Store5-backed editor.
/// No repository or UIKit presentation work runs in these background tasks.
enum ReceiptFilePreparation {
    static func read(_ url: URL) async throws -> PickedFile {
        try await Task.detached(priority: .userInitiated) {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            return try autoreleasepool {
                PickedFile(name: url.lastPathComponent, data: try Data(contentsOf: url))
            }
        }.value
    }

    static func jpeg(_ image: UIImage, name: String) async -> PickedFile? {
        await Task.detached(priority: .userInitiated) {
            autoreleasepool {
                guard let data = image.jpegData(compressionQuality: 0.85) else { return nil as PickedFile? }
                return PickedFile(name: name, data: data)
            }
        }.value
    }
}
