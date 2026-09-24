import AppIntents
import Foundation

struct OpenReceiptCameraIntent: AppIntent {
    static var title: LocalizedStringResource = "Scan Receipt"
    static var description: IntentDescription = "Opens Shilling to scan a receipt"
    static var openAppWhenRun: Bool = true
    private static let deepLink = URL(string: "shilling.finance://receipt-camera")!

    func perform() async throws -> some IntentResult & OpensIntent {
        if let defaults = UserDefaults(suiteName: "group.finance.shilling.app") {
            defaults.set(true, forKey: "pendingReceiptCamera")
        }

        return .result(opensIntent: OpenURLIntent(Self.deepLink))
    }
}
